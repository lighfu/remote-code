package dev.remotecode.data

import dev.remotecode.R
import dev.remotecode.i18n.AppStringResolver
import dev.remotecode.i18n.localizedServerMessage
import dev.remotecode.i18n.text
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

internal data class FileHexPage(val bytes: ByteArray, val etag: String?)

internal suspend fun Call.readHexPage(
    offset: Long, end: Long, size: Long, etag: String?, strings: AppStringResolver,
): FileHexPage = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    val started = System.nanoTime()
    fun elapsed() = (System.nanoTime() - started) / 1_000_000
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isActive) return
            val message = strings.text(R.string.core_pc_connection_error, e.localizedMessage.orEmpty())
            NetLog.api("GET", "/api/file/raw", null, elapsed(), null, message)
            continuation.resumeWith(Result.failure(ApiException(message).apply { initCause(e) }))
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                val headersMs = elapsed()
                val result = runCatching {
                    if (!response.isSuccessful) {
                        val detail = runCatching { AppJson.parseToJsonElement(response.peekBody(2048).string()).str("error") }.getOrNull()
                        val message = detail?.let { localizedServerMessage(it, strings) } ?: strings.text(R.string.core_file_http_error, response.code)
                        throw ApiException(message, response.code)
                    }
                    val count = end - offset + 1
                    val range = response.header("Content-Range")
                    if (response.code != 206 || range == null || range.substringBefore('/') != "bytes $offset-$end") {
                        throw ApiException(strings.text(R.string.core_response_decode_error), response.code)
                    }
                    val receivedEtag = response.header("ETag")
                    if (range.substringAfter('/').toLongOrNull() != size || etag != null && etag != receivedEtag) {
                        throw ApiException(strings.text(R.string.core_file_http_error, 412), 412)
                    }
                    val source = response.body.source()
                    if (!source.request(count) || source.request(count + 1)) {
                        throw ApiException(strings.text(R.string.core_response_decode_error), response.code)
                    }
                    FileHexPage(source.readByteArray(count), receivedEtag)
                }
                if (continuation.isActive) {
                    val total = elapsed()
                    NetLog.api("GET", "/api/file/raw", response.code, total, result.getOrNull()?.bytes?.size?.toLong(),
                        result.exceptionOrNull()?.message, timing = NetTiming.fromResponse(response, headersMs, total - headersMs))
                    continuation.resumeWith(result)
                }
            }
        }
    })
}
