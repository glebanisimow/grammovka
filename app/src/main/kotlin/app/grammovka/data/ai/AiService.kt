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

    /** Tool loop: client tools hit USDA/OFF through our cached clients; web search is the provider's. */
    suspend fun resolve(requests: List<String>): List<Proposal> {
        val tools = listOf(
            ToolDef("usda_search", "Search USDA FoodData Central. Query must be in English.",
                obj("query_en" to str(), "data_types" to arrOfEnum("Foundation", "SR Legacy", "Survey (FNDDS)"))),
            ToolDef("usda_food", "Get one USDA food by fdcId with per-100 g nutrients.", obj("fdc_id" to JsonObject(mapOf("type" to JsonPrimitive("integer"))))),
            ToolDef("off_search", "Full-text search in Open Food Facts, filtered to products sold in Poland.", obj("query" to str(), "country" to str())),
            ToolDef("off_product", "Get one Open Food Facts product by barcode.", obj("barcode" to str())),
        )
        val final = ToolDef("propose_foods", "Final answer: one proposal per requested food.", proposeSchema())
        val seenUsda = mutableMapOf<Long, Nutrients>()
        val seenOff = mutableMapOf<String, Nutrients>()
        val offBrands = mutableMapOf<String, String>()
        val (t, key, model) = prepare(AiPurpose.RESOLVE)
        val user = "Find nutrition per 100 g for: " + requests.joinToString("; ") { "\"$it\"" }
        val answer = try {
            t.toolLoop(key, model, Prompts.PROMPT_RESOLVE_V2, user, tools, final, { name, input -> toolResult(name, input, seenUsda, seenOff, offBrands) }, OTHER_TIMEOUT_MS)
        } catch (e: ClaudeException) {
            costs.record(AiPurpose.RESOLVE, model, Prompts.RESOLVE_VERSION, e.usage, ok = false, errorCode = e.kind.name)
            throw e
        }
        costs.record(AiPurpose.RESOLVE, model, Prompts.RESOLVE_VERSION, answer.usage, ok = true)
        return proposals(answer.json, seenUsda, seenOff, offBrands, t.provider)
    }

    private suspend fun toolResult(
        name: String,
        input: JsonObject,
        seenUsda: MutableMap<Long, Nutrients>,
        seenOff: MutableMap<String, Nutrients>,
        offBrands: MutableMap<String, String>,
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
                    val hits = off.search(input.s("query").orEmpty())
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
            AiPurpose.LABEL, Prompts.LABEL_VERSION, Prompts.PROMPT_LABEL_V1,
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
        val o = structured(AiPurpose.MEAL_PHOTO, Prompts.PHOTO_VERSION, Prompts.PROMPT_PHOTO_V2, parts, schema, 2000, OTHER_TIMEOUT_MS)
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
            t.stream(key, model, Prompts.PROMPT_WEEKLY_V3, digest, 8000).collect { e ->
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
