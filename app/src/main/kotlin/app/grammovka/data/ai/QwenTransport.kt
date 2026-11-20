package app.grammovka.data.ai

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Qwen over [QwenClient]. Differences from Anthropic, all checked on a real key 2026-11-20:
 * - structured output: `response_format` json_schema for text, json_object for images (the schema is in the system
 *   prompt for both); thinking off; an answer that is not valid JSON is retried once with the parse error;
 * - web search is not a server tool next to ours: `web_search` here is a client tool that runs a separate
 *   request with `enable_search` and returns the found text with URLs (a URL-less WEB proposal is downgraded to an
 *   estimate by [AiService] anyway); each such request counts as one paid search;
 * - streaming: OpenAI-style deltas, usage in the last chunk.
 */
class QwenTransport(private val client: QwenClient, private val baseUrl: suspend () -> String) : AiTransport {
    override val provider = AiProvider.QWEN

    override suspend fun json(key: String, model: String, system: String, parts: List<Part>, schema: JsonObject, maxTokens: Int, timeoutMs: Long): AiAnswer {
        val base = baseUrl()
        val hasImages = parts.any { it is Part.Jpeg }
        val messages = mutableListOf<JsonElement>(
            QwenProtocol.message("system", QwenProtocol.systemWithSchema(system, schema)),
            QwenProtocol.message("user", QwenProtocol.userContent(parts)),
        )
        var total = Usage()
        try {
            repeat(2) { attempt ->
                // No max_tokens: the structured-output docs ask not to cap it (a cut answer is broken JSON).
                val body = buildJsonObject {
                    put("model", model)
                    put("messages", JsonArray(messages))
                    put("response_format", QwenProtocol.responseFormat(schema, hasImages))
                    put("enable_thinking", false)
                }
                val reply = send(base, key, body, timeoutMs)
                total += reply.usage
                val o = QwenProtocol.extractJson(reply.content)
                val missing = o?.let { QwenProtocol.missingKeys(it, schema) }
                if (o != null && missing.isNullOrEmpty()) return AiAnswer(o, total)
                if (attempt == 0) {
                    messages += QwenProtocol.message("assistant", reply.content)
                    messages += QwenProtocol.message(
                        "user",
                        if (o == null) "That was not a valid JSON object. Reply again with only the JSON object."
                        else "The JSON lacks required keys: ${missing!!.joinToString()}. Reply again with the complete JSON object.",
                    )
                }
            }
            throw ClaudeException(ClaudeErrorKind.BAD_REQUEST, apiMessage = "ответ не в формате JSON", provider = provider)
        } catch (e: ClaudeException) {
            throw e.withUsage(total)
        }
    }

    override suspend fun toolLoop(
        key: String, model: String, system: String, user: String, tools: List<ToolDef>, final: ToolDef,
        exec: suspend (String, JsonObject) -> ToolOutput, timeoutMs: Long,
    ): AiAnswer {
        val base = baseUrl()
        val toolsJson = buildJsonArray {
            tools.forEach { add(QwenProtocol.tool(it)) }
            add(QwenProtocol.tool(WEB_SEARCH))
            add(QwenProtocol.tool(final))
        }
        val messages = mutableListOf<JsonElement>(QwenProtocol.message("system", system), QwenProtocol.message("user", user))
        var total = Usage()
        var searches = 0
        try {
            repeat(MAX_TOOL_ROUNDS) {
                val body = buildJsonObject {
                    put("model", model)
                    put("messages", JsonArray(messages))
                    put("tools", toolsJson)
                    put("enable_thinking", false)
                }
                val reply = send(base, key, body, timeoutMs)
                total += reply.usage
                messages += QwenProtocol.historyOf(reply)
                reply.toolCalls.firstOrNull { it.name == final.name }?.let { call ->
                    val args = QwenProtocol.extractJson(call.arguments)
                    if (args != null && QwenProtocol.missingKeys(args, final.schema).isEmpty()) return AiAnswer(args, total)
                    // Broken arguments: answer every call of this turn (the API requires it) and ask again.
                    reply.toolCalls.forEach { c ->
                        messages += QwenProtocol.toolResult(c.id, if (c === call) "error: arguments are not valid JSON for ${final.name}; call it again" else "skipped")
                    }
                    return@repeat
                }
                if (reply.toolCalls.isEmpty()) {
                    messages += QwenProtocol.message("user", "Call ${final.name} now with what you have; use CLAUDE_ESTIMATE where nothing was found.")
                    return@repeat
                }
                reply.toolCalls.forEach { c ->
                    val input = QwenProtocol.extractJson(c.arguments) ?: JsonObject(emptyMap())
                    val out = if (c.name == WEB_SEARCH.name) {
                        if (searches >= MAX_SEARCHES) {
                            ToolOutput("error: the search limit is used up; continue with what you have", isError = true)
                        } else {
                            searches++
                            val (text, u) = webSearch(base, key, model, (input["query"] as? JsonPrimitive)?.contentOrNull.orEmpty(), timeoutMs)
                            total += u
                            ToolOutput(text)
                        }
                    } else {
                        exec(c.name, input)
                    }
                    messages += QwenProtocol.toolResult(c.id, if (out.isError && !out.content.startsWith("error")) "error: ${out.content}" else out.content)
                }
            }
            throw ClaudeException(ClaudeErrorKind.BAD_REQUEST, apiMessage = "Qwen не завершил поиск за $MAX_TOOL_ROUNDS шагов", provider = provider)
        } catch (e: ClaudeException) {
            throw e.withUsage(total)
        }
    }

    /** A separate request with the provider's search on; the answer quotes the pages it used. */
    private suspend fun webSearch(base: String, key: String, model: String, query: String, timeoutMs: Long): Pair<String, Usage> {
        val body = buildJsonObject {
            put("model", model)
            put(
                "messages",
                buildJsonArray {
                    add(QwenProtocol.message("system", SEARCH_SYSTEM))
                    add(QwenProtocol.message("user", query))
                },
            )
            put("enable_search", true)
            putJsonObject("search_options") { put("forced_search", true) }
            put("enable_thinking", false)
        }
        val reply = send(base, key, body, timeoutMs)
        return reply.content.ifBlank { "nothing found" } to reply.usage.copy(webSearches = 1)
    }

    override fun stream(key: String, model: String, system: String, user: String, maxTokens: Int): Flow<StreamEvent> = flow {
        val body = buildJsonObject {
            put("model", model)
            put(
                "messages",
                buildJsonArray {
                    add(QwenProtocol.message("system", system))
                    add(QwenProtocol.message("user", user))
                },
            )
            put("max_tokens", maxTokens)
            put("enable_thinking", false)
        }
        var usage = Usage()
        var finish: String? = null
        client.stream(baseUrl(), key, body).collect { c ->
            when (c) {
                is QwenChunk.Text -> emit(StreamEvent.Text(c.text))
                is QwenChunk.Finish -> finish = c.reason
                is QwenChunk.Totals -> usage = c.usage
                QwenChunk.End -> Unit
            }
        }
        emit(StreamEvent.Done(usage, if (finish == "content_filter") "refusal" else finish))
    }

    private suspend fun send(base: String, key: String, body: JsonObject, timeoutMs: Long): QwenReply = try {
        client.send(base, key, body, timeoutMs)
    } catch (e: TimeoutCancellationException) {
        throw ClaudeException(ClaudeErrorKind.TIMEOUT, provider = provider)
    }

    companion object {
        const val MAX_TOOL_ROUNDS = 8
        const val MAX_SEARCHES = 3

        val WEB_SEARCH = ToolDef(
            "web_search",
            "Search the web (manufacturer pages, Polish shops, nutrition sites). Returns what was found, with page URLs.",
            AiService.obj("query" to AiService.str()),
        )

        private val SEARCH_SYSTEM = """
            You look up nutrition facts on the web for a food diary app. Search for the query and report the values per 100 g
            (kcal, protein, fat, carbohydrates, fiber) exactly as the pages publish them, each with the full URL of the page.
            Prefer the manufacturer, then large shops, then nutrition databases. If pages disagree, list each with its URL.
            If nothing reliable is found, say so plainly. Do not estimate.
        """.trimIndent()
    }
}
