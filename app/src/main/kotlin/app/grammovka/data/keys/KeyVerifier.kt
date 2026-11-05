package app.grammovka.data.keys

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** Outcome of a "Проверить ключ" request. */
sealed interface KeyCheck {
    data object Ok : KeyCheck
    data object Invalid : KeyCheck
    data object NoPermission : KeyCheck
    data object RateLimited : KeyCheck
    data class ServiceDown(val code: Int) : KeyCheck
    data class Unexpected(val code: Int) : KeyCheck
    data class Network(val message: String?) : KeyCheck
}

interface KeyVerifier {
    suspend fun verify(key: String): KeyCheck
}

/**
 * Anthropic: `GET /v1/models?limit=1` — free, no tokens spent.
 * Docs: https://platform.claude.com/docs/en/api/models/list,
 * errors: https://platform.claude.com/docs/en/api/errors (401 authentication_error, 403 permission_error,
 * 429 rate_limit_error, 529 overloaded_error), versions: https://platform.claude.com/docs/en/api/versioning
 */
class AnthropicKeyVerifier(private val http: OkHttpClient) : KeyVerifier {
    override suspend fun verify(key: String): KeyCheck {
        val req = Request.Builder()
            .url("${AnthropicApi.BASE_URL}/v1/models?limit=1")
            .header("x-api-key", key)
            .header("anthropic-version", AnthropicApi.VERSION)
            .get()
            .build()
        return http.check(req, ::mapAnthropicStatus)
    }
}

/**
 * USDA FoodData Central via api.data.gov: a one-result search.
 * Docs: https://fdc.nal.usda.gov/api-guide, errors: https://api.data.gov/docs/developer-manual/
 * (403 API_KEY_INVALID / API_KEY_MISSING, 429 OVER_RATE_LIMIT).
 */
class UsdaKeyVerifier(private val http: OkHttpClient) : KeyVerifier {
    override suspend fun verify(key: String): KeyCheck {
        val url = "https://api.nal.usda.gov/fdc/v1/foods/search".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", key)
            .addQueryParameter("query", "apple")
            .addQueryParameter("pageSize", "1")
            .build()
        return http.check(Request.Builder().url(url).get().build(), ::mapUsdaStatus)
    }
}

object AnthropicApi {
    const val BASE_URL = "https://api.anthropic.com"

    /** Latest version listed at https://platform.claude.com/docs/en/api/versioning (checked 2026-11-05). */
    const val VERSION = "2026-10-21"
}

internal fun mapAnthropicStatus(code: Int): KeyCheck = when (code) {
    in 200..299 -> KeyCheck.Ok
    401 -> KeyCheck.Invalid
    403 -> KeyCheck.NoPermission
    429 -> KeyCheck.RateLimited
    in 500..599 -> KeyCheck.ServiceDown(code)
    else -> KeyCheck.Unexpected(code)
}

internal fun mapUsdaStatus(code: Int): KeyCheck = when (code) {
    in 200..299 -> KeyCheck.Ok
    401, 403 -> KeyCheck.Invalid
    429 -> KeyCheck.RateLimited
    in 500..599 -> KeyCheck.ServiceDown(code)
    else -> KeyCheck.Unexpected(code)
}

private suspend fun OkHttpClient.check(req: Request, map: (Int) -> KeyCheck): KeyCheck =
    withContext(Dispatchers.IO) {
        try {
            await(newCall(req)).use { map(it.code) }
        } catch (e: IOException) {
            KeyCheck.Network(e.javaClass.simpleName)
        }
    }

/** Cancelling the coroutine cancels the HTTP call and closes the connection. */
private suspend fun await(call: Call): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, r, _ -> r.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
}
