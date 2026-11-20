package app.grammovka.data.ai

import app.grammovka.data.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/** One parsed chat completion. */
data class QwenReply(
    val message: JsonObject,
    val content: String,
    val toolCalls: List<QwenToolCall>,
    val finishReason: String?,
    val usage: Usage,
)

data class QwenToolCall(val id: String, val name: String, val arguments: String)

/** A piece of a streamed answer, before it becomes a [StreamEvent]. */
sealed interface QwenChunk {
    data class Text(val text: String) : QwenChunk
    data class Finish(val reason: String) : QwenChunk
    data class Totals(val usage: Usage) : QwenChunk
    data object End : QwenChunk
}

/**
 * Wire format of Alibaba Cloud Model Studio, OpenAI-compatible mode (`POST {base}/chat/completions`). Pure functions,
 * tested on recorded responses. Docs (checked 2026-11-20):
 * structured output https://www.alibabacloud.com/help/en/model-studio/qwen-structured-output (json_schema for text;
 * images fall back to json_object, which needs the word JSON in the prompt; thinking must be off),
 * images https://www.alibabacloud.com/help/en/model-studio/vision (base64 data URLs),
 * web search https://www.alibabacloud.com/help/en/model-studio/web-search (`enable_search`; sources are not returned
 * in this mode, so the model is asked to quote URLs), errors https://www.alibabacloud.com/help/en/model-studio/error-code.
 */
object QwenProtocol {
    val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    fun userContent(parts: List<Part>): JsonElement {
        (parts.singleOrNull() as? Part.Text)?.let { return JsonPrimitive(it.text) }
        return buildJsonArray {
            parts.forEach { p ->
                when (p) {
                    is Part.Text -> addJsonObject { put("type", "text"); put("text", p.text) }
                    is Part.Jpeg -> addJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") { put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(p.bytes)) }
                    }
                }
            }
        }
    }

    fun message(role: String, content: JsonElement) = buildJsonObject { put("role", role); put("content", content) }

    fun message(role: String, content: String) = message(role, JsonPrimitive(content))

    /** The schema is also spelled out in the system prompt: json_object mode (images) ignores response_format's schema. */
    fun systemWithSchema(system: String, schema: JsonObject) =
        system + "\n\nReply with only a JSON object (no prose, no code fences) that matches this JSON Schema:\n" + schema.toString()

    fun responseFormat(schema: JsonObject, hasImages: Boolean): JsonObject = if (hasImages) {
        buildJsonObject { put("type", "json_object") }
    } else {
        buildJsonObject {
            put("type", "json_schema")
            putJsonObject("json_schema") { put("name", "answer"); put("strict", true); put("schema", schema) }
        }
    }

    fun tool(t: ToolDef) = buildJsonObject {
        put("type", "function")
        putJsonObject("function") { put("name", t.name); put("description", t.description); put("parameters", t.schema) }
    }

    fun parseReply(o: JsonObject): QwenReply {
        val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val msg = choice?.get("message") as? JsonObject ?: JsonObject(emptyMap())
        val calls = (msg["tool_calls"] as? JsonArray).orEmpty().mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            val f = c["function"] as? JsonObject ?: return@mapNotNull null
            QwenToolCall(c.str("id").orEmpty(), f.str("name").orEmpty(), f.str("arguments").orEmpty())
        }
        return QwenReply(msg, msg.str("content").orEmpty(), calls, choice?.str("finish_reason"), usage(o["usage"] as? JsonObject))
    }

    /** The assistant turn as it goes back into the history: role, text and tool calls only. */
    fun historyOf(reply: QwenReply): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", reply.content)
        reply.message["tool_calls"]?.takeIf { it !is JsonNull }?.let { put("tool_calls", it) }
    }

    fun toolResult(callId: String, content: String) = buildJsonObject {
        put("role", "tool"); put("tool_call_id", callId); put("content", content)
    }

    /** `prompt_tokens` includes cached ones; they are kept apart like Anthropic's cache reads. */
    fun usage(u: JsonObject?): Usage {
        if (u == null) return Usage()
        val prompt = u.int("prompt_tokens") ?: 0
        val cached = (u["prompt_tokens_details"] as? JsonObject)?.int("cached_tokens") ?: 0
        return Usage(input = prompt - cached, output = u.int("completion_tokens") ?: 0, cacheRead = cached)
    }

    /** Models sometimes wrap JSON in prose or a code fence even when asked not to. */
    fun extractJson(text: String): JsonObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { lenient.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }.getOrNull()
    }

    /** Required top-level keys of [schema] missing in [o]. */
    fun missingKeys(o: JsonObject, schema: JsonObject): List<String> =
        (schema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it !in o }

    /** One SSE line → chunks. Reasoning deltas (thinking) and empty keep-alives are skipped. */
    fun streamLine(line: String): List<QwenChunk> {
        if (!line.startsWith("data:")) return emptyList()
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") return listOf(QwenChunk.End)
        val o = runCatching { lenient.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return emptyList()
        val out = mutableListOf<QwenChunk>()
        val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        ((choice?.get("delta") as? JsonObject)?.str("content"))?.takeIf { it.isNotEmpty() }?.let { out += QwenChunk.Text(it) }
        choice?.str("finish_reason")?.let { out += QwenChunk.Finish(it) }
        (o["usage"] as? JsonObject)?.let { out += QwenChunk.Totals(usage(it)) }
        return out
    }

    /** Error body → (code, message). Both `{"error":{…}}` and DashScope's flat `{"code","message"}` occur. */
    fun errorOf(text: String): Pair<String?, String?> {
        val o = runCatching { lenient.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null to null
        val e = o["error"] as? JsonObject ?: o
        return (e.str("code") ?: e.str("type")) to e.str("message")
    }

    fun kindOf(status: Int, code: String?): ClaudeErrorKind {
        val c = code.orEmpty()
        return when {
            c.startsWith("Arrearage", ignoreCase = true) -> ClaudeErrorKind.BILLING
            c.contains("insufficient_quota", ignoreCase = true) || c.contains("AllocationQuota", ignoreCase = true) -> ClaudeErrorKind.RATE_LIMIT
            status == 401 -> ClaudeErrorKind.INVALID_KEY
            status == 403 -> ClaudeErrorKind.NO_PERMISSION
            status == 429 -> ClaudeErrorKind.RATE_LIMIT
            status == 504 -> ClaudeErrorKind.TIMEOUT
            status in 500..599 -> ClaudeErrorKind.SERVER
            else -> ClaudeErrorKind.BAD_REQUEST
        }
    }

    /** Rate limits and server errors are retried; a used-up quota or an unpaid account is not. */
    fun retryable(status: Int, code: String?): Boolean =
        (status == 429 || status in 500..599) && kindOf(status, code).let { it != ClaudeErrorKind.BILLING } &&
            !(code.orEmpty().contains("insufficient_quota", true) || code.orEmpty().contains("AllocationQuota", true))

    /** The content filter is worth a plain-language message: it is not a bug of the app or of the key. */
    fun messageOf(code: String?, message: String?): String? =
        if (code.orEmpty().contains("DataInspectionFailed", true) || code.orEmpty().contains("data_inspection_failed", true)) {
            "фильтр содержимого Alibaba Cloud не пропустил запрос"
        } else {
            message
        }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}

/**
 * HTTP for Qwen: `Authorization: Bearer`, retries 429/5xx with exponential backoff honoring `retry-after`, at most
 * 3 attempts. Never logs headers: they carry the key.
 */
class QwenClient(http: OkHttpClient) {
    private val http = http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()

    suspend fun send(baseUrl: String, apiKey: String, body: JsonObject, timeoutMs: Long): QwenReply = withTimeout(timeoutMs) {
        val resp = open(baseUrl, apiKey, body)
        val text = resp.use { it.body.string() }
        QwenProtocol.parseReply(QwenProtocol.lenient.parseToJsonElement(text).jsonObject)
    }

    /** `stream: true` with usage in the last chunk; cancelling the collector closes the connection. */
    fun stream(baseUrl: String, apiKey: String, body: JsonObject): Flow<QwenChunk> = channelFlow {
        val full = JsonObject(
            body + ("stream" to JsonPrimitive(true)) + ("stream_options" to buildJsonObject { put("include_usage", true) }),
        )
        val resp = open(baseUrl, apiKey, full)
        runInterruptible(Dispatchers.IO) {
            resp.use { r ->
                val source = r.body.source()
                try {
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        QwenProtocol.streamLine(line).forEach { trySendBlocking(it) }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    throw ClaudeException(ClaudeErrorKind.TIMEOUT, provider = AiProvider.QWEN)
                } catch (e: java.io.InterruptedIOException) {
                    throw e // the collector was cancelled
                } catch (e: IOException) {
                    throw ClaudeException(ClaudeErrorKind.OFFLINE, provider = AiProvider.QWEN)
                }
            }
        }
    }

    /** A 2xx response (caller closes it), or a [ClaudeException] after retries. */
    private suspend fun open(baseUrl: String, apiKey: String, body: JsonObject): okhttp3.Response {
        var attempt = 0
        while (true) {
            attempt++
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonType))
                .build()
            val resp = try {
                http.await(req)
            } catch (e: IOException) {
                throw ClaudeException(ClaudeErrorKind.OFFLINE, provider = AiProvider.QWEN)
            }
            if (resp.code in 200..299) return resp
            val code = resp.code
            val retryAfter = resp.header("retry-after")?.toDoubleOrNull()
            val text = resp.use { it.body.string() }
            val (errCode, errMessage) = QwenProtocol.errorOf(text)
            if (QwenProtocol.retryable(code, errCode) && attempt < MAX_ATTEMPTS) {
                delay(min(retryAfter?.let { (it * 1000).toLong() } ?: (1000.0 * 2.0.pow(attempt - 1)).toLong(), 20_000))
                continue
            }
            throw ClaudeException(QwenProtocol.kindOf(code, errCode), code, QwenProtocol.messageOf(errCode, errMessage), AiProvider.QWEN)
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
