package app.grammovka.data.ai

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * Anthropic Messages API. Request shapes checked on 2026-11-05 against:
 * structured outputs https://platform.claude.com/docs/en/build-with-claude/structured-outputs (output_config.format),
 * strict tools https://platform.claude.com/docs/en/agents-and-tools/tool-use/strict-tool-use,
 * web search https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool (web_search_20250305),
 * tool results https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls,
 * caching https://platform.claude.com/docs/en/build-with-claude/prompt-caching,
 * images https://platform.claude.com/docs/en/build-with-claude/vision,
 * effort https://platform.claude.com/docs/en/build-with-claude/effort.
 */
class AnthropicTransport(
    private val client: ClaudeClient,
    private val country: suspend () -> app.grammovka.data.settings.Country,
) : AiTransport {
    override val provider = AiProvider.ANTHROPIC
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun json(key: String, model: String, system: String, parts: List<Part>, schema: JsonObject, maxTokens: Int, timeoutMs: Long): AiAnswer {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", systemBlocks(system))
            putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", content(parts)) } }
            putJsonObject("output_config") { putJsonObject("format") { put("type", "json_schema"); put("schema", schema) } }
        }
        val resp = send(key, body, timeoutMs)
        return AiAnswer(json.parseToJsonElement(resp.text).jsonObject, resp.usage)
    }

    override suspend fun toolLoop(
        key: String, model: String, system: String, user: String, tools: List<ToolDef>, final: ToolDef,
        exec: suspend (String, JsonObject) -> ToolOutput, timeoutMs: Long,
    ): AiAnswer {
        val c = country()
        val toolsJson = buildJsonArray {
            tools.forEach { add(tool(it)) }
            add(buildJsonObject {
                put("type", "web_search_20250305"); put("name", "web_search"); put("max_uses", 3)
                putJsonObject("user_location") { put("type", "approximate"); put("country", c.iso); put("timezone", c.timezone) }
            })
            add(tool(final, cache = true))
        }
        val messages = mutableListOf<JsonElement>(buildJsonObject { put("role", "user"); put("content", user) })
        var total = Usage()
        try {
            repeat(MAX_TOOL_ROUNDS) {
                val body = buildJsonObject {
                    put("model", model)
                    put("max_tokens", 4000)
                    put("system", systemBlocks(system))
                    put("tools", toolsJson)
                    put("messages", JsonArray(messages))
                }
                val resp = send(key, body, timeoutMs)
                total += resp.usage
                messages += buildJsonObject { put("role", "assistant"); put("content", resp.content) }
                val toolUses = resp.content.map { it.jsonObject }.filter { it.str("type") == "tool_use" }
                toolUses.firstOrNull { it.str("name") == final.name }?.let { return AiAnswer(it["input"]!!.jsonObject, total) }
                when (resp.stopReason) {
                    "pause_turn" -> return@repeat // send the paused turn back unchanged
                    "tool_use" -> {
                        val results = toolUses.map { tu ->
                            val out = exec(tu.str("name").orEmpty(), tu["input"] as? JsonObject ?: JsonObject(emptyMap()))
                            buildJsonObject {
                                put("type", "tool_result"); put("tool_use_id", tu.str("id")!!); put("content", out.content)
                                if (out.isError) put("is_error", true)
                            }
                        }
                        messages += buildJsonObject { put("role", "user"); put("content", JsonArray(results)) }
                    }
                    else -> messages += buildJsonObject {
                        put("role", "user")
                        put("content", "Call ${final.name} now with what you have; use CLAUDE_ESTIMATE where nothing was found.")
                    }
                }
            }
            throw ClaudeException(ClaudeErrorKind.BAD_REQUEST, apiMessage = "Claude не завершил поиск за $MAX_TOOL_ROUNDS шагов")
        } catch (e: ClaudeException) {
            throw e.withUsage(total)
        }
    }

    override fun stream(key: String, model: String, system: String, user: String, maxTokens: Int): Flow<StreamEvent> {
        // Thinking stays adaptive (Sonnet 5.5 rejects `disabled`); effort keeps it short.
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", systemBlocks(system))
            putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", user) } }
            putJsonObject("output_config") { put("effort", "medium") }
        }
        return client.stream(key, body)
    }

    private suspend fun send(key: String, body: JsonObject, timeoutMs: Long): ClaudeResponse = try {
        client.send(key, body, timeoutMs)
    } catch (e: TimeoutCancellationException) {
        throw ClaudeException(ClaudeErrorKind.TIMEOUT)
    }

    private fun content(parts: List<Part>): JsonElement {
        (parts.singleOrNull() as? Part.Text)?.let { return JsonPrimitive(it.text) }
        return buildJsonArray {
            parts.forEach { p ->
                when (p) {
                    is Part.Text -> addJsonObject { put("type", "text"); put("text", p.text) }
                    is Part.Jpeg -> addJsonObject {
                        put("type", "image")
                        putJsonObject("source") {
                            put("type", "base64"); put("media_type", "image/jpeg")
                            put("data", Base64.getEncoder().encodeToString(p.bytes))
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val MAX_TOOL_ROUNDS = 8

        /** System prompt as a text block with a cache breakpoint. */
        fun systemBlocks(text: String) = buildJsonArray {
            addJsonObject { put("type", "text"); put("text", text); putJsonObject("cache_control") { put("type", "ephemeral") } }
        }

        fun tool(t: ToolDef, cache: Boolean = false) = buildJsonObject {
            put("name", t.name); put("description", t.description); put("strict", true); put("input_schema", t.schema)
            if (cache) putJsonObject("cache_control") { put("type", "ephemeral") }
        }

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    }
}
