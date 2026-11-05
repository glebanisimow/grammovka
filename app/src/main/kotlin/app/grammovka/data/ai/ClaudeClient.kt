package app.grammovka.data.ai

import app.grammovka.data.keys.AnthropicApi
import app.grammovka.data.net.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

enum class ClaudeErrorKind { NO_KEY, OFFLINE, INVALID_KEY, NO_PERMISSION, BILLING, RATE_LIMIT, OVERLOADED, SERVER, BAD_REQUEST, TIMEOUT, BUDGET }

class ClaudeException(val kind: ClaudeErrorKind, val status: Int? = null, val apiMessage: String? = null) : IOException("${kind.name} $status")

/** Raw Messages API response pieces we use. */
data class ClaudeResponse(val json: JsonObject) {
    val content: JsonArray get() = json["content"] as? JsonArray ?: JsonArray(emptyList())
    val stopReason: String? get() = (json["stop_reason"] as? JsonPrimitive)?.contentOrNull

    val usage: Usage
        get() {
            val u = json["usage"] as? JsonObject ?: return Usage()
            fun i(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.intOrNull ?: 0
            return Usage(
                input = i(u, "input_tokens"),
                output = i(u, "output_tokens"),
                cacheRead = i(u, "cache_read_input_tokens"),
                cacheWrite = i(u, "cache_creation_input_tokens"),
                webSearches = i(u["server_tool_use"] as? JsonObject, "web_search_requests"),
            )
        }

    /** Concatenated text blocks (structured outputs arrive in a text block). */
    val text: String
        get() = content.mapNotNull { b -> b.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }?.get("text")?.jsonPrimitive?.contentOrNull }
            .joinToString("")
}

/**
 * Thin client for `POST /v1/messages` — one endpoint, full control over the tool loop.
 * Docs: https://platform.claude.com/docs/en/api/messages ; errors: https://platform.claude.com/docs/en/api/errors ;
 * version: https://platform.claude.com/docs/en/api/versioning (2026-10-21 removes temperature/top_p/top_k).
 * Retries 429, 529 and 5xx with exponential backoff honoring `retry-after`, at most 3 attempts.
 * Never logs headers: they carry the key.
 */
class ClaudeClient(http: OkHttpClient) {
    private val http = http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()

    suspend fun send(apiKey: String, body: JsonObject, timeoutMs: Long): ClaudeResponse = withTimeout(timeoutMs) {
        var attempt = 0
        while (true) {
            attempt++
            val req = Request.Builder()
                .url("${AnthropicApi.BASE_URL}/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", AnthropicApi.VERSION)
                .header("content-type", "application/json")
                .post(body.toString().toRequestBody(jsonType))
                .build()
            val resp = try {
                http.await(req)
            } catch (e: IOException) {
                throw ClaudeException(ClaudeErrorKind.OFFLINE)
            }
            val code = resp.code
            val retryAfter = resp.header("retry-after")?.toDoubleOrNull()
            val text = resp.use { it.body.string() }
            if (code in 200..299) return@withTimeout ClaudeResponse(lenient.parseToJsonElement(text).jsonObject)
            val retryable = code == 429 || code == 529 || code in 500..599
            if (retryable && attempt < MAX_ATTEMPTS) {
                val backoff = retryAfter?.let { (it * 1000).toLong() } ?: (1000.0 * 2.0.pow(attempt - 1)).toLong()
                delay(min(backoff, 20_000))
                continue
            }
            throw ClaudeException(kindOf(code), code, apiMessage(text))
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        private val lenient = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun kindOf(code: Int): ClaudeErrorKind = when (code) {
            400, 404, 409, 413 -> ClaudeErrorKind.BAD_REQUEST
            401 -> ClaudeErrorKind.INVALID_KEY
            402 -> ClaudeErrorKind.BILLING
            403 -> ClaudeErrorKind.NO_PERMISSION
            429 -> ClaudeErrorKind.RATE_LIMIT
            529 -> ClaudeErrorKind.OVERLOADED
            504 -> ClaudeErrorKind.TIMEOUT
            else -> ClaudeErrorKind.SERVER
        }

        private fun apiMessage(text: String): String? = runCatching {
            lenient.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }
}

/** What happened and what to do (UX: no apologies, no "something went wrong"). */
fun ClaudeException.userMessage(): String = when (kind) {
    ClaudeErrorKind.NO_KEY -> "Нет ключа Anthropic. Добавьте его в «Настройки → Ключи API» или введите продукт вручную."
    ClaudeErrorKind.OFFLINE -> "Нет связи с Claude. Проверьте интернет или введите продукт вручную."
    ClaudeErrorKind.INVALID_KEY -> "Claude не принял ключ. Проверьте его в «Настройки → Ключи API»."
    ClaudeErrorKind.NO_PERMISSION -> "У ключа нет доступа к модели. Проверьте права ключа или модель в настройках."
    ClaudeErrorKind.BILLING -> "На счёте Anthropic нет средств. Пополните баланс в консоли Anthropic."
    ClaudeErrorKind.RATE_LIMIT -> "Claude ограничил частоту запросов или исчерпан лимит расходов в консоли. Повторите через минуту."
    ClaudeErrorKind.OVERLOADED -> "Claude перегружен. Повторите через минуту."
    ClaudeErrorKind.SERVER -> "Сервис Claude ответил ошибкой ${status ?: ""}. Повторите позже."
    ClaudeErrorKind.BAD_REQUEST -> "Claude отклонил запрос${apiMessage?.let { ": $it" } ?: "."} Проверьте модель в настройках."
    ClaudeErrorKind.TIMEOUT -> "Claude не ответил вовремя. Повторите или введите продукт вручную."
    ClaudeErrorKind.BUDGET -> "Месячный лимит на AI исчерпан. AI-функции выключены до конца месяца; лимит меняется в настройках."
}
