package app.grammovka.data.ai

import app.grammovka.data.ai.prompts.Prompts

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 *   streamed request with `enable_search` and the `agent` strategy, which really opens pages. The default strategy
 *   (and `forced_search` alone) only adds a ~200-token snippet or nothing, and the model then answers from memory
 *   with plausible but unread URLs — measured 2026-11-20 on a fresh NBP rate and on Polish products, so it is not
 *   used. A search that runs past [SEARCH_TIMEOUT_MS] returns «not found», and the food falls back to a marked
 *   estimate (a URL-less WEB proposal is downgraded by [AiService] anyway); each search counts as one paid search;
 * - streaming: OpenAI-style deltas, usage in the last chunk.
 */
class QwenTransport(
    private val client: QwenClient,
    private val baseUrl: suspend () -> String,
    private val country: suspend () -> app.grammovka.data.settings.Country,
) : AiTransport {
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
                // The calls of one turn run in parallel (two foods = two searches at once: 64 s → about half on a
                // real request 2026-11-20); results go back in the call order.
                val searchAllowed = reply.toolCalls.map { c ->
                    c.name != WEB_SEARCH.name || (searches < MAX_SEARCHES).also { if (it) searches++ }
                }
                val outs = coroutineScope {
                    reply.toolCalls.mapIndexed { i, c ->
                        async {
                            val input = QwenProtocol.extractJson(c.arguments) ?: JsonObject(emptyMap())
                            when {
                                c.name != WEB_SEARCH.name -> exec(c.name, input) to Usage()
                                !searchAllowed[i] -> ToolOutput("error: the search limit is used up; continue with what you have", isError = true) to Usage()
                                else -> webSearch(base, key, (input["query"] as? JsonPrimitive)?.contentOrNull.orEmpty(), Prompts.webSearchSystem(country()))
                                    .let { w -> ToolOutput(w.text) to w.usage }
                            }
                        }
                    }.awaitAll()
                }
                reply.toolCalls.zip(outs).forEach { (c, r) ->
                    val (out, u) = r
                    total += u
                    messages += QwenProtocol.toolResult(c.id, if (out.isError && !out.content.startsWith("error")) "error: ${out.content}" else out.content)
                }
            }
            throw ClaudeException(ClaudeErrorKind.BAD_REQUEST, apiMessage = "Qwen не завершил поиск за $MAX_TOOL_ROUNDS шагов", provider = provider)
        } catch (e: ClaudeException) {
            throw e.withUsage(total)
        }
    }

    /**
     * A separate streamed request with the `agent` search strategy (it needs streaming and thinking:
     * https://www.alibabacloud.com/help/en/model-studio/web-search); the answer quotes the pages it read.
     * Its tokens are charged at the RESOLVE model's price, which is above [SEARCH_MODEL]'s, so the limit errs high.
     */
    override suspend fun webLookup(key: String, query: String, system: String): WebText = webSearch(baseUrl(), key, query, system)

    private suspend fun webSearch(base: String, key: String, query: String, system: String): WebText {
        val body = buildJsonObject {
            put("model", SEARCH_MODEL)
            put(
                "messages",
                buildJsonArray {
                    add(QwenProtocol.message("system", system))
                    add(QwenProtocol.message("user", query))
                },
            )
            put("enable_search", true)
            putJsonObject("search_options") { put("forced_search", true); put("search_strategy", "agent") }
        }
        val text = StringBuilder()
        var usage: Usage? = null
        val finished = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            client.stream(base, key, body).collect { c ->
                when (c) {
                    is QwenChunk.Text -> text.append(c.text)
                    is QwenChunk.Totals -> usage = c.usage
                    else -> Unit
                }
            }
        } != null
        // A cut-off search has no usage report; charge a typical agent search (≈60k in, 5k out) so the limit still bites.
        val spent = (usage ?: Usage(input = 60_000, output = 5_000)).copy(webSearches = 1)
        if (!finished) return WebText("nothing found: the search took too long; continue with the databases or an estimate", spent, false)
        return WebText(text.toString().trim().ifBlank { "nothing found" }, spent, true)
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

        /**
         * Agent search: qwen3.7-plus read real pages in 26–110 s; qwen3.6-plus once searched for 6 minutes. With the
         * stop-at-the-first-page prompt (2026-11-21) Belarusian brands took 11–37 s, while one glazed curd snack ran
         * 200 s and 20 ¢ chasing fiber that no page had; hence 45 s, then a marked estimate.
         */
        const val SEARCH_MODEL = "qwen3.7-plus"
        const val SEARCH_TIMEOUT_MS = 45_000L

        val WEB_SEARCH = ToolDef(
            "web_search",
            "Search the web (manufacturer pages, large local shops, nutrition sites). Returns what was found, with page URLs. " +
                "Slow (up to 45 seconds): call it only after the databases, and for all foods that need it in the same turn, they run in parallel.",
            AiService.obj("query" to AiService.str()),
        )

    }
}
