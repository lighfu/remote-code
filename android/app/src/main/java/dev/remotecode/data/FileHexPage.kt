package dev.remotecode.data

import dev.remotecode.R
import dev.remotecode.i18n.AppStringResolver
import dev.remotecode.i18n.localizedServerMessage
import dev.remotecode.i18n.text
import okhttp3.Call
import okhttp3.Response

internal data class FileHexPage(val bytes: ByteArray, val etag: String?)

internal suspend fun Call.readHexPage(
    offset: Long, end: Long, size: Long, etag: String?, strings: AppStringResolver,
): FileHexPage {
    val started = System.nanoTime()
    fun elapsed() = (System.nanoTime() - started) / 1_000_000
    return readResponse({ error ->
        strings.connectionError(error).also { NetLog.api("GET", "/api/file/raw", null, elapsed(), null, it.message) }
    }) { response, isActive ->
        val headersMs = elapsed()
        val page = runCatching { decodeHexPage(response, offset, end, size, etag, strings) }
        if (isActive()) {
            val total = elapsed()
            NetLog.api("GET", "/api/file/raw", response.code, total, page.getOrNull()?.bytes?.size?.toLong(),
                page.exceptionOrNull()?.message, timing = NetTiming.fromResponse(response, headersMs, total - headersMs))
        }
        page.getOrThrow()
    }
}

private fun decodeHexPage(
    response: Response, offset: Long, end: Long, size: Long, etag: String?, strings: AppStringResolver,
): FileHexPage {
    if (!response.isSuccessful) {
        val detail = response.peekServerError()?.let { localizedServerMessage(it, strings) }
        throw ApiException(detail ?: strings.text(R.string.core_file_http_error, response.code), response.code)
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
    return FileHexPage(source.readByteArray(count), receivedEtag)
}
