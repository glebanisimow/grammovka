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

sealed interface LabelResult {
    data class Found(val draft: FoodDraft, val note: String?, val packageG: Double?) : LabelResult
    data class NotFound(val note: String?) : LabelResult
}

/**
 * PARSE, RESOLVE and LABEL over [ClaudeClient]. Request shapes checked on 2026-11-05 against:
 * structured outputs https://platform.claude.com/docs/en/build-with-claude/structured-outputs (output_config.format),
 * strict tools https://platform.claude.com/docs/en/agents-and-tools/tool-use/strict-tool-use,
 * web search https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool (web_search_20250305),
 * tool results https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls,
 * caching https://platform.claude.com/docs/en/build-with-claude/prompt-caching,
 * images https://platform.claude.com/docs/en/build-with-claude/vision.
 * Invariant 2: everything returned here goes to the confirm screen; nothing is saved from here.
 */
class AiService(
    private val client: ClaudeClient,
    private val secrets: SecretStore,
    private val settings: SettingsStore,
    private val costs: AiCostTracker,
    private val usda: UsdaApi,
    private val off: OffApi,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun hasKey(): Boolean = secrets.get(Secret.ANTHROPIC_KEY) != null

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
        val body = buildJsonObject {
            put("max_tokens", 2000)
            put("system", systemBlocks(Prompts.PROMPT_PARSE_V1))
            putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", input.toString()) } }
            putJsonObject("output_config") { putJsonObject("format") { put("type", "json_schema"); put("schema", schema) } }
        }
        val resp = call(AiPurpose.PARSE, Prompts.PARSE_VERSION, body, PARSE_TIMEOUT_MS)
        val out = json.parseToJsonElement(resp.text).jsonObject["items"]!!.jsonArray
        return out.map { e ->
            val o = e.jsonObject
            ParsedFragment(
                fragment = o.s("fragment").orEmpty(), foodId = o.l("foodId"),
                grams = o.d("grams"), pieces = o.d("pieces"), nameRu = o.s("nameRu").orEmpty(),
            )
        }
    }

    // ------------------------------------------------------------------ RESOLVE

    /** Tool loop: client tools hit USDA/OFF through our cached clients; web search runs server-side. */
    suspend fun resolve(requests: List<String>): List<Proposal> {
        val tools = buildJsonArray {
            add(tool("usda_search", "Search USDA FoodData Central. Query must be in English.",
                obj("query_en" to str(), "data_types" to arrOfEnum("Foundation", "SR Legacy", "Survey (FNDDS)"))))
            add(tool("usda_food", "Get one USDA food by fdcId with per-100 g nutrients.", obj("fdc_id" to JsonObject(mapOf("type" to JsonPrimitive("integer"))))))
            add(tool("off_search", "Full-text search in Open Food Facts, filtered to products sold in Poland.", obj("query" to str(), "country" to str())))
            add(tool("off_product", "Get one Open Food Facts product by barcode.", obj("barcode" to str())))
            add(buildJsonObject {
                put("type", "web_search_20250305"); put("name", "web_search"); put("max_uses", 3)
                putJsonObject("user_location") { put("type", "approximate"); put("country", "PL"); put("timezone", "Europe/Warsaw") }
            })
            add(tool("propose_foods", "Final answer: one proposal per requested food.", proposeSchema(), cache = true))
        }
        val messages = mutableListOf<JsonElement>(
            buildJsonObject {
                put("role", "user")
                put("content", "Find nutrition per 100 g for: " + requests.joinToString("; ") { "\"$it\"" })
            },
        )
        val seenUsda = mutableMapOf<Long, Nutrients>()
        val seenOff = mutableMapOf<String, Nutrients>()
        var total = Usage()
        val model = settings.current().models.getValue(AiPurpose.RESOLVE)
        try {
            repeat(MAX_TOOL_ROUNDS) {
                val body = buildJsonObject {
                    put("max_tokens", 4000)
                    put("system", systemBlocks(Prompts.PROMPT_RESOLVE_V1))
                    put("tools", tools)
                    put("messages", JsonArray(messages))
                }
                val resp = call(AiPurpose.RESOLVE, Prompts.RESOLVE_VERSION, body, OTHER_TIMEOUT_MS, record = false)
                total += resp.usage
                messages += buildJsonObject { put("role", "assistant"); put("content", resp.content) }
                val toolUses = resp.content.map { it.jsonObject }.filter { it.s("type") == "tool_use" }
                toolUses.firstOrNull { it.s("name") == "propose_foods" }?.let { final ->
                    costs.record(AiPurpose.RESOLVE, model, Prompts.RESOLVE_VERSION, total, ok = true)
                    return proposals(final["input"]!!.jsonObject, seenUsda, seenOff)
                }
                when (resp.stopReason) {
                    "pause_turn" -> return@repeat // send the paused turn back unchanged
                    "tool_use" -> {
                        val results = toolUses.map { tu -> toolResult(tu, seenUsda, seenOff) }
                        messages += buildJsonObject { put("role", "user"); put("content", JsonArray(results)) }
                    }
                    else -> {
                        messages += buildJsonObject {
                            put("role", "user")
                            put("content", "Call propose_foods now with what you have; use CLAUDE_ESTIMATE where nothing was found.")
                        }
                    }
                }
            }
            throw ClaudeException(ClaudeErrorKind.BAD_REQUEST, apiMessage = "Claude не завершил поиск за $MAX_TOOL_ROUNDS шагов")
        } catch (e: ClaudeException) {
            costs.record(AiPurpose.RESOLVE, model, Prompts.RESOLVE_VERSION, total, ok = false, errorCode = e.kind.name)
            throw e
        }
    }

    private suspend fun toolResult(tu: JsonObject, seenUsda: MutableMap<Long, Nutrients>, seenOff: MutableMap<String, Nutrients>): JsonObject {
        val id = tu.s("id")!!
        val input = tu["input"]?.jsonObject ?: JsonObject(emptyMap())
        val (content, isError) = try {
            when (tu.s("name")) {
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
                    hits.forEach { h -> h.per100?.let { seenOff[h.code] = it } }
                    buildJsonArray { hits.forEach { h -> addJsonObject { put("barcode", h.code); put("name", h.name); put("brand", h.brand); put("quantity", h.quantity); put("per100g", nut(h.per100)) } } }.toString() to false
                }
                "off_product" -> {
                    val p = off.product(input.s("barcode").orEmpty())
                    p?.per100?.let { seenOff[p.code] = it }
                    (p?.let { buildJsonObject { put("barcode", it.code); put("name", it.name); put("brand", it.brand); put("per100g", nut(it.per100)) } }?.toString() ?: "not found") to false
                }
                else -> "unknown tool" to true
            }
        } catch (e: NetException) {
            "error: ${e.problem.name.lowercase()}" to true
        }
        return buildJsonObject {
            put("type", "tool_result"); put("tool_use_id", id); put("content", content)
            if (isError) put("is_error", true)
        }
    }

    /**
     * Turns propose_foods into drafts. Invariant 1: numbers for USDA/OFF come from our own parsed database
     * responses when available, not from the model's transcription; a WEB proposal without a URL and any
     * HIGH-confidence estimate are downgraded.
     */
    private fun proposals(input: JsonObject, seenUsda: Map<Long, Nutrients>, seenOff: Map<String, Nutrients>): List<Proposal> =
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
                    note = "USDA FoodData Central, fdcId $ref" + if (db == null) " (цифры от Claude, проверьте)" else ""
                }
                FoodSource.OFF -> {
                    val db = ref?.let(seenOff::get)
                    if (db != null) per100 = db
                    note = "Open Food Facts, штрихкод $ref" + if (db == null) " (цифры от Claude, проверьте)" else ""
                }
                FoodSource.WEB -> if (ref != null && (ref.startsWith("http://") || ref.startsWith("https://"))) {
                    note = "Веб: $ref"
                } else {
                    source = FoodSource.CLAUDE_ESTIMATE; ref = null; confidence = Confidence.LOW
                    note = "Источник без ссылки: считается оценкой Claude."
                }
                else -> {
                    source = FoodSource.CLAUDE_ESTIMATE; ref = null
                    note = "Оценка Claude: в базах не нашлось. Проверьте цифры."
                }
            }
            if (source == FoodSource.CLAUDE_ESTIMATE && (confidence == null || confidence == Confidence.HIGH)) confidence = Confidence.MEDIUM
            val portionName = o.s("typicalPortionName")
            val portionG = o.d("typicalPortionG")?.takeIf { it > 0 }
            Proposal(
                request = o.s("request").orEmpty(),
                draft = FoodDraft(
                    name = o.s("nameRu").orEmpty(), per100 = per100,
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
        val body = buildJsonObject {
            put("max_tokens", 1000)
            put("system", systemBlocks(Prompts.PROMPT_LABEL_V1))
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64"); put("media_type", "image/jpeg")
                                put("data", Base64.getEncoder().encodeToString(jpeg))
                            }
                        }
                        addJsonObject { put("type", "text"); put("text", "Extract the nutrition table.") }
                    }
                }
            }
            putJsonObject("output_config") { putJsonObject("format") { put("type", "json_schema"); put("schema", schema) } }
        }
        val o = json.parseToJsonElement(call(AiPurpose.LABEL, Prompts.LABEL_VERSION, body, OTHER_TIMEOUT_MS).text).jsonObject
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

    // ------------------------------------------------------------------ plumbing

    private suspend fun call(purpose: AiPurpose, version: String, body: JsonObject, timeoutMs: Long, record: Boolean = true): ClaudeResponse {
        val s = settings.current()
        val key = secrets.get(Secret.ANTHROPIC_KEY) ?: throw ClaudeException(ClaudeErrorKind.NO_KEY)
        try {
            costs.check(s.aiMonthlyLimitUsd)
        } catch (e: AiBudgetExceeded) {
            throw ClaudeException(ClaudeErrorKind.BUDGET)
        }
        val model = s.models.getValue(purpose)
        val full = JsonObject(body + ("model" to JsonPrimitive(model)))
        return try {
            client.send(key, full, timeoutMs).also { if (record) costs.record(purpose, model, version, it.usage, ok = true) }
        } catch (e: TimeoutCancellationException) {
            if (record) costs.record(purpose, model, version, Usage(), ok = false, errorCode = "TIMEOUT")
            throw ClaudeException(ClaudeErrorKind.TIMEOUT)
        } catch (e: ClaudeException) {
            if (record) costs.record(purpose, model, version, Usage(), ok = false, errorCode = e.kind.name)
            throw e
        }
    }

    companion object {
        const val PARSE_TIMEOUT_MS = 15_000L
        const val OTHER_TIMEOUT_MS = 90_000L
        const val MAX_TOOL_ROUNDS = 8

        /** System prompt as a text block with a cache breakpoint. */
        fun systemBlocks(text: String) = buildJsonArray {
            addJsonObject { put("type", "text"); put("text", text); putJsonObject("cache_control") { put("type", "ephemeral") } }
        }

        fun tool(name: String, description: String, schema: JsonObject, cache: Boolean = false) = buildJsonObject {
            put("name", name); put("description", description); put("strict", true); put("input_schema", schema)
            if (cache) putJsonObject("cache_control") { put("type", "ephemeral") }
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
