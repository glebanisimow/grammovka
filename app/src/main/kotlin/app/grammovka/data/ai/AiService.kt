package app.grammovka.data.ai

import app.grammovka.data.ai.prompts.Prompts
import app.grammovka.data.net.NetException
import app.grammovka.data.remote.OffApi
import app.grammovka.data.remote.UsdaApi
import app.grammovka.data.settings.Secret
import app.grammovka.data.settings.SecretStore
import app.grammovka.data.settings.SettingsStore
import app.grammovka.domain.model.AiPurpose
import app.grammovka.domain.model.CatalogFood
import app.grammovka.domain.model.Confidence
import app.grammovka.domain.model.FoodBasis
import app.grammovka.domain.model.FoodDraft
import app.grammovka.domain.model.FoodSource
import app.grammovka.domain.model.Nutrients
import app.grammovka.domain.model.Nutrition
import app.grammovka.domain.parse.Fragment
import app.grammovka.domain.parse.PortionNames
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/** PARSE output for one fragment. */
data class ParsedFragment(val fragment: String, val foodId: Long?, val grams: Double?, val pieces: Double?, val nameRu: String)

/** RESOLVE output: an editable draft plus a human note about where the numbers came from. */
data class Proposal(val request: String, val draft: FoodDraft, val note: String, val portionG: Double?)

/** One food recognised from photos, already resolved to an editable draft; [grams] is a visual or label estimate. */
data class PhotoFood(val draft: FoodDraft, val note: String, val grams: Double?)

data class PhotoResult(val foods: List<PhotoFood>, val notes: String?)

sealed interface LabelResult {
    data class Found(val draft: FoodDraft, val note: String?, val packageG: Double?) : LabelResult
    data class NotFound(val note: String?) : LabelResult
}

/**
 * PARSE, RESOLVE, LABEL, MEAL_PHOTO, ACTIVITY and ANALYSIS over the active provider's [AiTransport]
 * (Anthropic or Qwen, «Настройки → Ключи API и ИИ»). Prompts, schemas, keys, the monthly limit, cost records and all
 * mapping to drafts live here, so both providers keep the same invariants.
 * Invariant 2: everything returned here goes to the confirm screen; nothing is saved from here.
 */
class AiService(
    private val transports: Map<AiProvider, AiTransport>,
    private val secrets: SecretStore,
    private val settings: SettingsStore,
    private val costs: AiCostTracker,
    private val usda: UsdaApi,
    private val off: OffApi,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun hasKey(): Boolean = secrets.get(keyOf(provider())) != null

    suspend fun provider(): AiProvider = settings.current().aiProvider

    // ------------------------------------------------------------------ PARSE

    suspend fun parse(items: List<Pair<Fragment, List<CatalogFood>>>): List<ParsedFragment> {
        val input = buildJsonObject {
            putJsonArray("fragments") {
                items.forEach { (f, cands) ->
                    addJsonObject {
                        put("fragment", f.raw.trim())
                        putJsonArray("candidates") {
                            cands.take(8).forEach { c ->
                                addJsonObject {
                                    put("id", c.id); put("name", c.name); c.brand?.let { put("brand", it) }
                                    if (c.aliases.isNotEmpty()) putJsonArray("aliases") { c.aliases.forEach { add(it) } }
                                }
                            }
                        }
                    }
                }
            }
        }
        val schema = obj(
            "items" to arr(
                obj(
                    "fragment" to str(), "foodId" to nullable("integer"), "grams" to nullable("number"),
                    "pieces" to nullable("number"), "nameRu" to str(),
                ),
            ),
        )
        val o = structured(AiPurpose.PARSE, Prompts.PARSE_VERSION, Prompts.PROMPT_PARSE_V1, listOf(Part.Text(input.toString())), schema, 2000, PARSE_TIMEOUT_MS)
        val out = o["items"]!!.jsonArray
        return out.map { e ->
            val o = e.jsonObject
            ParsedFragment(
                fragment = o.s("fragment").orEmpty(), foodId = o.l("foodId"),
                grams = o.d("grams"), pieces = o.d("pieces"), nameRu = o.s("nameRu").orEmpty(),
            )
        }
    }

    // ------------------------------------------------------------------ RESOLVE

    /**
     * Claude: a tool loop (client tools hit USDA/OFF through our cached clients; web search is the provider's).
     * Qwen: [resolveDirect], which is several times faster than its tool loop.
     */
    suspend fun resolve(requests: List<String>): List<Proposal> {
        val (t, key, model) = prepare(AiPurpose.RESOLVE)
        if (t.provider == AiProvider.QWEN) return resolveDirect(t, key, requests)
        val country = settings.current().country
        val tools = listOf(
            ToolDef("usda_search", "Search USDA FoodData Central. Query must be in English.",
                obj("query_en" to str(), "data_types" to arrOfEnum("Foundation", "SR Legacy", "Survey (FNDDS)"))),
            ToolDef("usda_food", "Get one USDA food by fdcId with per-100 g nutrients.", obj("fdc_id" to JsonObject(mapOf("type" to JsonPrimitive("integer"))))),
            ToolDef("off_search", "Full-text search in Open Food Facts, filtered to products sold in ${country.nameEn}.", obj("query" to str(), "country" to str())),
            ToolDef("off_product", "Get one Open Food Facts product by barcode.", obj("barcode" to str())),
        )
        val final = ToolDef("propose_foods", "Final answer: one proposal per requested food.", proposeSchema())
        // Concurrent maps: a transport may run the tool calls of one turn in parallel.
        val seenUsda = java.util.concurrent.ConcurrentHashMap<Long, Nutrients>()
        val seenOff = java.util.concurrent.ConcurrentHashMap<String, Nutrients>()
        val offBrands = java.util.concurrent.ConcurrentHashMap<String, String>()
        val user = "Find nutrition per 100 g for: " + requests.joinToString("; ") { "\"$it\"" }
        val answer = try {
            t.toolLoop(key, model, Prompts.resolveV3(country), user, tools, final, { name, input -> toolResult(name, input, seenUsda, seenOff, offBrands, country) }, OTHER_TIMEOUT_MS)
        } catch (e: ClaudeException) {
            costs.record(AiPurpose.RESOLVE, model, Prompts.RESOLVE_VERSION, e.usage, ok = false, errorCode = e.kind.name)
            throw e
        }
        costs.record(AiPurpose.RESOLVE, model, Prompts.RESOLVE_VERSION, answer.usage, ok = true)
        return proposals(answer.json, seenUsda, seenOff, offBrands, t.provider)
    }

    /**
     * Qwen without a tool loop (measured 2026-11-21 on 4 foods: the loop took 228 s and 11 ¢, qwen3.7-plus made one
     * call per round, repeated reworded USDA queries and sometimes ran out of rounds). Here: one call plans the
     * queries, the app queries USDA and Open Food Facts itself in parallel, one call picks, and only branded foods
     * missing from the databases go to the web (in parallel, [QwenTransport.SEARCH_TIMEOUT_MS] each), whose answers
     * one more call turns into numbers. Invariant 1 holds as in the loop: database numbers come from [seenUsda] / [seenOff].
     */
    private suspend fun resolveDirect(t: AiTransport, key: String, requests: List<String>): List<Proposal> {
        val s = settings.current()
        val country = s.country
        val fastModel = s.models.getValue(AiPurpose.PARSE)
        val pickModel = s.models.getValue(AiPurpose.RESOLVE)

        // 1. Plan.
        val planSchema = obj(
            "foods" to arr(
                obj(
                    "request" to str(), "queryEn" to str(), "usdaTypes" to arrOfEnum("Foundation", "SR Legacy", "Survey (FNDDS)"),
                    "branded" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))), "offQuery" to nullable("string"),
                ),
            ),
        )
        val planInput = buildJsonObject { putJsonArray("foods") { requests.forEach { add(it) } } }.toString()
        val plan = resolveCall(t, key, fastModel, Prompts.RESOLVE_PLAN_VERSION, Prompts.resolvePlan(country), planInput, planSchema)
            .arr("foods")
        // Index by position; a short plan falls back to the request itself as the USDA query.
        val planned = requests.indices.map { i -> plan.getOrNull(i) ?: buildJsonObject { put("queryEn", requests[i]); put("branded", false) } }

        // 2. The app searches, all foods at once.
        val seenUsda = java.util.concurrent.ConcurrentHashMap<Long, Nutrients>()
        val seenOff = java.util.concurrent.ConcurrentHashMap<String, Nutrients>()
        val offBrands = java.util.concurrent.ConcurrentHashMap<String, String>()
        val candidates = coroutineScope {
            planned.map { p ->
                async {
                    val errors = mutableListOf<String>()
                    val usdaHits = try {
                        val types = p["usdaTypes"]?.let { it as? JsonArray }?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                        usda.search(p.s("queryEn").orEmpty(), types).filter { it.per100 != null }
                    } catch (e: NetException) {
                        errors += "USDA: ${e.problem.name.lowercase()}"; emptyList()
                    }
                    val offQuery = p.s("offQuery")?.takeIf { p["branded"]?.jsonPrimitive?.booleanOrNull == true }
                    val offHits = if (offQuery == null) emptyList() else try {
                        off.search(offQuery, country).filter { it.per100 != null }
                    } catch (e: NetException) {
                        errors += "Open Food Facts: ${e.problem.name.lowercase()}"; emptyList()
                    }
                    usdaHits.forEach { f -> seenUsda[f.fdcId] = f.per100!! }
                    offHits.forEach { h -> seenOff[h.code] = h.per100!!; h.brand?.let { offBrands[h.code] = it } }
                    buildJsonArray {
                        usdaHits.forEach { f -> addJsonObject { put("db", "USDA"); put("ref", f.fdcId.toString()); put("name", f.description); put("dataType", f.dataType); put("per100g", nut(f.per100)) } }
                        offHits.forEach { h -> addJsonObject { put("db", "OFF"); put("ref", h.code); put("name", h.name); put("brand", h.brand); put("quantity", h.quantity); put("per100g", nut(h.per100)) } }
                    } to errors
                }
            }.awaitAll()
        }

        // 3. Pick.
        val pickSchema = proposeSchema().let { base ->
            val item = base["properties"]!!.jsonObject["foods"]!!.jsonObject["items"]!!.jsonObject
            val props = item["properties"]!!.jsonObject.toMutableMap().apply { put("choice", enumOf("db", "web", "estimate")) }
            obj("foods" to arr(obj(*props.map { it.key to it.value.jsonObject }.toTypedArray())))
        }
        val pickInput = buildJsonObject {
            putJsonArray("foods") {
                requests.forEachIndexed { i, r ->
                    addJsonObject {
                        put("request", r)
                        put("branded", planned[i]["branded"] ?: JsonPrimitive(false))
                        put("candidates", candidates[i].first)
                        if (candidates[i].second.isNotEmpty()) put("searchErrors", candidates[i].second.joinToString())
                    }
                }
            }
        }.toString()
        val picks = resolveCall(t, key, pickModel, Prompts.RESOLVE_PICK_VERSION, Prompts.resolvePick(country), pickInput, pickSchema)
            .arr("foods").toMutableList()
        // The confirm screen pairs proposals with requests verbatim.
        picks.indices.forEach { i -> requests.getOrNull(i)?.let { r -> picks[i] = JsonObject(picks[i] + ("request" to JsonPrimitive(r))) } }

        // 4. Web, only for what the pick could not match.
        val webIdx = picks.indices.filter { picks[it].s("choice") == "web" }.take(QwenTransport.MAX_SEARCHES)
        if (webIdx.isNotEmpty()) {
            val found = coroutineScope {
                webIdx.map { i ->
                    async {
                        val name = picks[i].s("nameRu")?.takeIf(String::isNotBlank) ?: picks[i].s("request").orEmpty()
                        val w = try {
                            t.webLookup(key, "$name — пищевая ценность на 100 г", Prompts.webSearchSystem(country))
                        } catch (e: ClaudeException) {
                            costs.record(AiPurpose.RESOLVE, QwenTransport.SEARCH_MODEL, Prompts.RESOLVE_WEB_VERSION, e.usage, ok = false, errorCode = e.kind.name)
                            null
                        }
                        w?.let { costs.record(AiPurpose.RESOLVE, QwenTransport.SEARCH_MODEL, Prompts.RESOLVE_WEB_VERSION, it.usage, ok = it.finished, errorCode = if (it.finished) null else "TIMEOUT") }
                        i to w?.takeIf { it.finished }?.text
                    }
                }.awaitAll()
            }.filter { it.second != null }
            if (found.isNotEmpty()) {
                val exSchema = obj(
                    "foods" to arr(
                        obj(
                            "request" to str(), "found" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                            "kcal" to nullable("number"), "protein" to nullable("number"), "fat" to nullable("number"),
                            "carbs" to nullable("number"), "fiber" to nullable("number"), "url" to nullable("string"),
                        ),
                    ),
                )
                val exInput = buildJsonObject {
                    putJsonArray("foods") { found.forEach { (i, text) -> addJsonObject { put("request", picks[i].s("request")); put("answer", text) } } }
                }.toString()
                val extracted = try {
                    resolveCall(t, key, fastModel, Prompts.RESOLVE_WEB_VERSION, Prompts.RESOLVE_WEB_EXTRACT, exInput, exSchema).arr("foods")
                } catch (e: ClaudeException) {
                    emptyList() // the estimates stay; the user sees them marked
                }
                found.forEachIndexed { k, (i, _) ->
                    val x = extracted.getOrNull(k) ?: return@forEachIndexed
                    val url = x.s("url")
                    val ok = x["found"]?.jsonPrimitive?.booleanOrNull == true && url != null &&
                        listOf("kcal", "protein", "fat", "carbs").all { x.d(it) != null }
                    if (ok) {
                        picks[i] = JsonObject(
                            picks[i] + mapOf(
                                "source" to JsonPrimitive("WEB"), "sourceRef" to JsonPrimitive(url), "confidence" to JsonPrimitive("HIGH"),
                                "kcal" to JsonPrimitive(x.d("kcal")), "protein" to JsonPrimitive(x.d("protein")),
                                "fat" to JsonPrimitive(x.d("fat")), "carbs" to JsonPrimitive(x.d("carbs")),
                                "fiber" to (x.d("fiber")?.let(::JsonPrimitive) ?: JsonNull),
                            ),
                        )
                    }
                }
            }
        }
        return proposals(JsonObject(mapOf("foods" to JsonArray(picks))), seenUsda, seenOff, offBrands, t.provider)
    }

    /** One structured RESOLVE step, recorded under RESOLVE with its own model and prompt version. */
    private suspend fun resolveCall(t: AiTransport, key: String, model: String, version: String, system: String, input: String, schema: JsonObject): JsonObject {
        val answer = try {
            t.json(key, model, system, listOf(Part.Text(input)), schema, 4000, OTHER_TIMEOUT_MS)
        } catch (e: ClaudeException) {
            costs.record(AiPurpose.RESOLVE, model, version, e.usage, ok = false, errorCode = e.kind.name)
            throw e
        }
        costs.record(AiPurpose.RESOLVE, model, version, answer.usage, ok = true)
        return answer.json
    }

    private fun JsonObject.arr(k: String): List<JsonObject> = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private suspend fun toolResult(
        name: String,
        input: JsonObject,
        seenUsda: MutableMap<Long, Nutrients>,
        seenOff: MutableMap<String, Nutrients>,
        offBrands: MutableMap<String, String>,
        country: app.grammovka.data.settings.Country,
    ): ToolOutput {
        val (content, isError) = try {
            when (name) {
                "usda_search" -> {
                    val types = input["data_types"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                    val foods = usda.search(input.s("query_en").orEmpty(), types)
                    foods.forEach { f -> f.per100?.let { seenUsda[f.fdcId] = it } }
                    buildJsonArray { foods.forEach { f -> addJsonObject { put("fdcId", f.fdcId); put("description", f.description); put("dataType", f.dataType); put("per100g", nut(f.per100)) } } }.toString() to false
                }
                "usda_food" -> {
                    val f = usda.food(input.l("fdc_id") ?: 0)
                    f?.per100?.let { seenUsda[f.fdcId] = it }
                    (f?.let { buildJsonObject { put("fdcId", it.fdcId); put("description", it.description); put("per100g", nut(it.per100)) } }?.toString() ?: "not found") to false
                }
                "off_search" -> {
                    val hits = off.search(input.s("query").orEmpty(), country)
                    hits.forEach { h -> h.per100?.let { seenOff[h.code] = it }; h.brand?.let { offBrands[h.code] = it } }
                    buildJsonArray { hits.forEach { h -> addJsonObject { put("barcode", h.code); put("name", h.name); put("brand", h.brand); put("quantity", h.quantity); put("per100g", nut(h.per100)) } } }.toString() to false
                }
                "off_product" -> {
                    val p = off.product(input.s("barcode").orEmpty())
                    p?.per100?.let { seenOff[p.code] = it }
                    p?.brand?.let { offBrands[p.code] = it }
                    (p?.let { buildJsonObject { put("barcode", it.code); put("name", it.name); put("brand", it.brand); put("per100g", nut(it.per100)) } }?.toString() ?: "not found") to false
                }
                else -> "unknown tool" to true
            }
        } catch (e: NetException) {
            "error: ${e.problem.name.lowercase()}" to true
        }
        return ToolOutput(content, isError)
    }

    /**
     * Turns propose_foods into drafts. Invariant 1: numbers for USDA/OFF come from our own parsed database
     * responses when available, not from the model's transcription; a WEB proposal without a URL and any
     * HIGH-confidence estimate are downgraded.
     */
    private fun proposals(
        input: JsonObject,
        seenUsda: Map<Long, Nutrients>,
        seenOff: Map<String, Nutrients>,
        offBrands: Map<String, String>,
        provider: AiProvider,
    ): List<Proposal> =
        input["foods"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            var source = runCatching { FoodSource.valueOf(o.s("source")!!) }.getOrDefault(FoodSource.CLAUDE_ESTIMATE)
            var ref = o.s("sourceRef")
            var confidence = runCatching { Confidence.valueOf(o.s("confidence")!!) }.getOrNull()
            val model = Nutrients(o.d("kcal") ?: 0.0, o.d("protein") ?: 0.0, o.d("fat") ?: 0.0, o.d("carbs") ?: 0.0, o.d("fiber"))
            var per100 = model
            val note: String
            when (source) {
                FoodSource.USDA -> {
                    val db = ref?.toLongOrNull()?.let(seenUsda::get)
                    if (db != null) per100 = db
                    note = "USDA FoodData Central, fdcId $ref" + if (db == null) " (цифры от ${provider.title}, проверьте)" else ""
                }
                FoodSource.OFF -> {
                    val db = ref?.let(seenOff::get)
                    if (db != null) per100 = db
                    note = "Open Food Facts, штрихкод $ref" + if (db == null) " (цифры от ${provider.title}, проверьте)" else ""
                }
                FoodSource.WEB -> if (ref != null && (ref.startsWith("http://") || ref.startsWith("https://"))) {
                    note = "Веб: $ref"
                } else {
                    source = FoodSource.CLAUDE_ESTIMATE; ref = null; confidence = Confidence.LOW
                    note = "Источник без ссылки: считается оценкой ${provider.title}."
                }
                else -> {
                    source = FoodSource.CLAUDE_ESTIMATE; ref = null
                    note = "Оценка ${provider.title}: в базах не нашлось. Проверьте цифры."
                }
            }
            if (source == FoodSource.CLAUDE_ESTIMATE && (confidence == null || confidence == Confidence.HIGH)) confidence = Confidence.MEDIUM
            val portionName = o.s("typicalPortionName")
            val portionG = o.d("typicalPortionG")?.takeIf { it > 0 }
            Proposal(
                request = o.s("request").orEmpty(),
                draft = FoodDraft(
                    name = o.s("nameRu").orEmpty(), per100 = per100,
                    brand = if (source == FoodSource.OFF) ref?.let(offBrands::get) else null,
                    basis = runCatching { FoodBasis.valueOf(o.s("basis")!!) }.getOrDefault(FoodBasis.AS_SOLD),
                    source = source, sourceRef = ref, confidence = confidence,
                    barcode = if (source == FoodSource.OFF) ref else null,
                    portions = if (portionG != null) listOf((portionName ?: PortionNames.PIECE) to portionG) else emptyList(),
                ),
                note = note,
                portionG = portionG,
            )
        }

    // ------------------------------------------------------------------ LABEL

    /** [jpeg] is already downscaled to ~1568 px on the long side. Per-serving → per-100 g is done here, in code. */
    suspend fun label(jpeg: ByteArray, barcode: String?): LabelResult {
        val schema = obj(
            "found" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
            "name" to nullable("string"), "brand" to nullable("string"),
            "kcal" to nullable("number"), "kj" to nullable("number"),
            "protein" to nullable("number"), "fat" to nullable("number"), "carbs" to nullable("number"), "fiber" to nullable("number"),
            "per" to enumOf("100g", "100ml", "serving"),
            "servingG" to nullable("number"), "packageG" to nullable("number"),
            "ean" to nullable("string"), "notes" to nullable("string"),
        )
        val o = structured(
            AiPurpose.LABEL, Prompts.LABEL_VERSION, Prompts.labelV2(settings.current().country),
            listOf(Part.Jpeg(jpeg), Part.Text("Extract the nutrition table.")), schema, 1000, OTHER_TIMEOUT_MS,
        )
        val notes = o.s("notes")
        if (o["found"]?.jsonPrimitive?.booleanOrNull != true) return LabelResult.NotFound(notes)
        val kcal = o.d("kcal") ?: o.d("kj")?.let(Nutrition::kcalFromKj)
        val p = o.d("protein"); val f = o.d("fat"); val c = o.d("carbs")
        if (kcal == null || p == null || f == null || c == null) return LabelResult.NotFound(notes ?: "Не удалось прочитать все значения таблицы.")
        var per100 = Nutrients(kcal, p, f, c, o.d("fiber"))
        val per = o.s("per")
        if (per == "serving") {
            val sg = o.d("servingG") ?: return LabelResult.NotFound("На этикетке значения на порцию без веса порции. Введите вручную.")
            per100 = Nutrition.per100FromServing(per100, sg)
        }
        val draft = FoodDraft(
            name = o.s("name").orEmpty(), brand = o.s("brand"), per100 = per100, basis = FoodBasis.AS_SOLD,
            source = FoodSource.LABEL, sourceRef = barcode ?: o.s("ean"), barcode = barcode ?: o.s("ean"),
        )
        val note = buildList {
            add("С фото этикетки" + if (per == "serving") ", пересчитано с порции ${o.d("servingG")?.toInt()} г на 100 г" else "")
            notes?.let { add(it) }
        }.joinToString(". ")
        return LabelResult.Found(draft, note, o.d("packageG"))
    }

    // ------------------------------------------------------------------ PHOTO

    /**
     * Food without a nutrition table (a bun, minced meat in plain packaging, a plate): vision identifies what it is
     * and estimates grams (MEAL_PHOTO), then the existing RESOLVE loop finds per-100 g values in USDA / OFF / web,
     * falling back to a marked Claude estimate. A table, if one is printed after all, is taken as LABEL.
     * Several images go in one user turn (https://platform.claude.com/docs/en/build-with-claude/vision).
     * [jpegs] are already downscaled to ~1568 px. Invariant 2: everything goes to the confirm screen.
     */
    suspend fun photo(jpegs: List<ByteArray>, hint: String?, barcode: String?): PhotoResult {
        require(jpegs.isNotEmpty())
        val num = { nullable("number") }
        val schema = obj(
            "items" to arr(
                obj(
                    "nameRu" to str(), "queryEn" to str(), "details" to str(),
                    "packaged" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                    "brand" to nullable("string"), "packageG" to num(),
                    "grams" to num(), "gramsBasis" to enumOf("label", "visual", "unknown"),
                    "tableKcal" to num(), "tableProtein" to num(), "tableFat" to num(), "tableCarbs" to num(), "tableFiber" to num(),
                ),
            ),
            "notes" to nullable("string"),
        )
        val parts = buildList {
            jpegs.forEachIndexed { i, jpeg -> add(Part.Text("Photo ${i + 1}:")); add(Part.Jpeg(jpeg)) }
            val note = hint?.trim()?.takeIf(String::isNotEmpty)
            add(Part.Text(if (note != null) "User note: $note" else "No user note."))
        }
        val o = structured(AiPurpose.MEAL_PHOTO, Prompts.PHOTO_VERSION, Prompts.photoV3(settings.current().country), parts, schema, 2000, OTHER_TIMEOUT_MS)
        val requests = PhotoMapping.requests(o)
        val resolved = if (requests.isEmpty()) emptyList() else resolve(requests)
        return PhotoMapping.combine(o, resolved, barcode)
    }

    // ------------------------------------------------------------------ ACTIVITY

    /**
     * Free-text workouts → structured records with Claude's gross kcal estimate (structured output, same docs as above).
     * Invariant 2: the result only pre-fills the workout confirm sheet; the app computes net kcal and all sums.
     */
    suspend fun activity(text: String, sex: String?, ageYears: Int?, weightKg: Double?): List<ActivityProposal> {
        val num = { nullable("number") }
        val schema = obj(
            "items" to arr(
                obj(
                    "type" to enumOf("WALK", "RUN", "BIKE", "STRENGTH", "OTHER"),
                    "title" to str(), "minutes" to num(), "distanceKm" to num(), "avgHr" to num(),
                    "intensity" to enumOf("LIGHT", "MODERATE", "HARD", "UNKNOWN"),
                    "grossKcal" to num(), "confidence" to enumOf("HIGH", "MEDIUM", "LOW"), "reasoning" to str(),
                ),
            ),
        )
        val input = buildJsonObject {
            put("text", text.trim())
            putJsonObject("body") { put("sex", sex); put("ageYears", ageYears); put("weightKg", weightKg) }
        }
        val o = structured(AiPurpose.ACTIVITY, Prompts.ACTIVITY_VERSION, Prompts.PROMPT_ACTIVITY_V1, listOf(Part.Text(input.toString())), schema, 1500, OTHER_TIMEOUT_MS)
        return ActivityMapping.proposals(o)
    }

    // ------------------------------------------------------------------ ANALYSIS

    /**
     * Weekly review: the app's aggregates in ([digest], JSON prepared by code — invariant 3), prose out, streamed.
     * Emits text pieces as they arrive. Nothing here changes data.
     */
    fun weeklyReview(digest: String): Flow<String> = flow {
        val (t, key, model) = prepare(AiPurpose.ANALYSIS)
        var usage = Usage()
        try {
            t.stream(key, model, Prompts.PROMPT_WEEKLY_V4, digest, 8000).collect { e ->
                when (e) {
                    is StreamEvent.Text -> emit(e.text)
                    is StreamEvent.Done -> {
                        usage = e.usage
                        if (e.stopReason == "refusal") {
                            throw ClaudeException(ClaudeErrorKind.BAD_REQUEST, apiMessage = "${t.provider.title} отказался разбирать эти данные", provider = t.provider)
                        }
                    }
                }
            }
            costs.record(AiPurpose.ANALYSIS, model, Prompts.WEEKLY_VERSION, usage, ok = true)
        } catch (e: ClaudeException) {
            costs.record(AiPurpose.ANALYSIS, model, Prompts.WEEKLY_VERSION, usage, ok = false, errorCode = e.kind.name)
            throw e
        }
    }

    // ------------------------------------------------------------------ plumbing

    private data class Prepared(val transport: AiTransport, val key: String, val model: String)

    /** Active provider, its key and the model for [purpose]; the monthly limit is checked here for every call. */
    private suspend fun prepare(purpose: AiPurpose): Prepared {
        val s = settings.current()
        val t = transports.getValue(s.aiProvider)
        val key = secrets.get(keyOf(s.aiProvider)) ?: throw ClaudeException(ClaudeErrorKind.NO_KEY, provider = s.aiProvider)
        try {
            costs.check(s.aiMonthlyLimitUsd)
        } catch (e: AiBudgetExceeded) {
            throw ClaudeException(ClaudeErrorKind.BUDGET, provider = s.aiProvider)
        }
        return Prepared(t, key, s.models.getValue(purpose))
    }

    private suspend fun structured(
        purpose: AiPurpose, version: String, system: String, parts: List<Part>, schema: JsonObject, maxTokens: Int, timeoutMs: Long,
    ): JsonObject {
        val (t, key, model) = prepare(purpose)
        val answer = try {
            t.json(key, model, system, parts, schema, maxTokens, timeoutMs)
        } catch (e: ClaudeException) {
            costs.record(purpose, model, version, e.usage, ok = false, errorCode = e.kind.name)
            throw e
        } catch (e: kotlinx.serialization.SerializationException) {
            costs.record(purpose, model, version, Usage(), ok = false, errorCode = "BAD_JSON")
            throw ClaudeException(ClaudeErrorKind.BAD_REQUEST, apiMessage = "ответ не в формате JSON", provider = t.provider)
        }
        costs.record(purpose, model, version, answer.usage, ok = true)
        return answer.json
    }

    companion object {
        const val PARSE_TIMEOUT_MS = 15_000L
        const val OTHER_TIMEOUT_MS = 90_000L

        fun keyOf(p: AiProvider): Secret = when (p) {
            AiProvider.ANTHROPIC -> Secret.ANTHROPIC_KEY
            AiProvider.QWEN -> Secret.QWEN_KEY
        }

        fun str() = JsonObject(mapOf("type" to JsonPrimitive("string")))
        fun nullable(type: String) = JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive(type), JsonPrimitive("null")))))
        fun enumOf(vararg v: String) = JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to JsonArray(v.map(::JsonPrimitive))))
        fun arr(items: JsonObject) = JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to items))
        fun arrOfEnum(vararg v: String) = arr(enumOf(*v))

        /** Object schema with every property required and no extras (structured outputs / strict tools). */
        fun obj(vararg props: Pair<String, JsonObject>) = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(props.toMap()),
                "required" to JsonArray(props.map { JsonPrimitive(it.first) }),
                "additionalProperties" to JsonPrimitive(false),
            ),
        )

        fun proposeSchema() = obj(
            "foods" to arr(
                obj(
                    "request" to str(), "nameRu" to str(),
                    "kcal" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                    "protein" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                    "fat" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                    "carbs" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                    "fiber" to nullable("number"),
                    "basis" to enumOf("RAW", "COOKED", "AS_SOLD"),
                    "source" to enumOf("USDA", "OFF", "WEB", "CLAUDE_ESTIMATE"),
                    "sourceRef" to nullable("string"),
                    "confidence" to enumOf("HIGH", "MEDIUM", "LOW"),
                    "typicalPortionName" to nullable("string"),
                    "typicalPortionG" to nullable("number"),
                ),
            ),
        )

        private fun nut(n: Nutrients?): JsonElement = n?.let {
            buildJsonObject { put("kcal", it.kcal); put("protein", it.protein); put("fat", it.fat); put("carbs", it.carbs); put("fiber", it.fiber) }
        } ?: JsonNull

        private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
        private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: (this[k] as? JsonPrimitive)?.doubleOrNull?.toLong()
    }
}
