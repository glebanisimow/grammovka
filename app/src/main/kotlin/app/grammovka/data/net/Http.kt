package app.grammovka.data.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** Cancelling the coroutine cancels the HTTP call and closes the connection («Остановить»). */
suspend fun OkHttpClient.await(req: Request): Response = suspendCancellableCoroutine { cont ->
    val call = newCall(req)
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

enum class NetProblem { OFFLINE, RATE_LIMIT, SERVER, BAD_KEY, NO_KEY, BAD_RESPONSE }

class NetException(val problem: NetProblem, val code: Int? = null, message: String? = null) : IOException(message ?: problem.name)

/** Small async GET that maps HTTP failures to [NetProblem]. */
suspend fun OkHttpClient.getText(req: Request): String {
    val resp = try {
        await(req)
    } catch (e: NetException) {
        throw e
    } catch (e: IOException) {
        throw NetException(NetProblem.OFFLINE, message = e.javaClass.simpleName)
    }
    resp.use {
        when (it.code) {
            in 200..299 -> return it.body.string()
            401, 403 -> throw NetException(NetProblem.BAD_KEY, it.code)
            429, 503 -> throw NetException(NetProblem.RATE_LIMIT, it.code)
            in 500..599 -> throw NetException(NetProblem.SERVER, it.code)
            else -> throw NetException(NetProblem.BAD_RESPONSE, it.code)
        }
    }
}
