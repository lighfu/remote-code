package dev.remotecode.data

import dev.remotecode.R
import dev.remotecode.i18n.AppStringResolver
import dev.remotecode.i18n.text
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

internal fun AppStringResolver.connectionError(error: IOException): ApiException =
    ApiException(text(R.string.core_pc_connection_error, error.localizedMessage.orEmpty())).apply { initCause(error) }

internal fun Response.peekServerError(): String? =
    runCatching { AppJson.parseToJsonElement(peekBody(2048).string()).str("error") }.getOrNull()

/** Decode with the socket still bound to the waiting caller's cancellation. */
internal suspend fun <T> Call.readResponse(
    connectionFailure: (IOException) -> Exception,
    read: (Response, isActive: () -> Boolean) -> T,
): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(connectionFailure(e)))
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use { read(it) { continuation.isActive } } }
            if (continuation.isActive) continuation.resumeWith(result)
        }
    })
}
