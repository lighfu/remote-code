package dev.remotecode.data

import dev.remotecode.R
import dev.remotecode.i18n.AppStringResolver
import dev.remotecode.i18n.localizedServerMessage
import dev.remotecode.i18n.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

private const val STREAM_PATH = "/api/uploads"
private const val CHUNK_PATH = "/api/uploads/chunked"
internal const val ATTACHMENT_CHUNK_BYTES = 4 * 1024 * 1024
private val chunkUploadId = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
private val emptyBinary = ByteArray(0).toRequestBody("application/octet-stream".toMediaType())

internal class AttachmentUpload(
    private val client: OkHttpClient,
    private val strings: AppStringResolver,
    private val activeServer: () -> ServerConfig?,
) {
    suspend fun upload(
        cfg: ServerConfig, name: String, mime: String, size: Long?,
        open: () -> InputStream, onProgress: (Long) -> Unit,
    ): UploadedFile = withContext(Dispatchers.IO) {
        try {
            requireCurrent(cfg)
            if (size != null && size > MAX_ATTACHMENT_BYTES) throw tooLarge(MAX_ATTACHMENT_BYTES)
            if (size != null && size <= LEGACY_ATTACHMENT_BYTES) {
                return@withContext uploadSingle(cfg, name, mime, open, onProgress)
            }

            val initUrl = endpoint(cfg, CHUNK_PATH, name, mime).newBuilder()
                .apply { if (size != null) addQueryParameter("size", size.toString()) }
                .build()
            val result = try {
                request(cfg, "POST", initUrl, CHUNK_PATH, emptyBinary)
            } catch (e: ApiException) {
                if (e.code != 404 && e.code != 405) throw e
                if (size != null) throw ApiException(strings.text(R.string.core_large_upload_server_update_required), e.code)
                return@withContext uploadSingle(cfg, name, mime, open, onProgress)
            }
            val id = runCatching { AppJson.parseToJsonElement(result).str("id") }.getOrNull()
            if (id == null || !chunkUploadId.matches(id)) throw decodeError()
            uploadChunks(cfg, initUrl.newBuilder().query(null).addPathSegment(id).build(), id, size, open, onProgress)
        } catch (e: IOException) {
            throw ApiException(e.message ?: strings.text(R.string.core_upload_error)).apply { initCause(e) }
        } catch (_: SecurityException) {
            throw ApiException(strings.text(R.string.core_file_access_revoked))
        }
    }

    private suspend fun uploadSingle(
        cfg: ServerConfig, name: String, mime: String, open: () -> InputStream, onProgress: (Long) -> Unit,
    ): UploadedFile {
        val context = currentCoroutineContext()
        val body = FileUploadBody(
            open = {
                try { open() } catch (e: SecurityException) { throw IOException(strings.text(R.string.core_file_access_revoked), e) }
            },
            onProgress = onProgress,
            checkActive = { checkRequestActive(context) },
            maximum = LEGACY_ATTACHMENT_BYTES,
            strings = strings,
        )
        return decodeFile(request(cfg, "POST", endpoint(cfg, STREAM_PATH, name, mime), STREAM_PATH, body), LEGACY_ATTACHMENT_BYTES)
    }

    private suspend fun uploadChunks(
        cfg: ServerConfig, url: HttpUrl, id: String, size: Long?, open: () -> InputStream, onProgress: (Long) -> Unit,
    ): UploadedFile {
        val context = currentCoroutineContext()
        var offset = 0L
        var complete = false
        try {
            open().use { input ->
                val buffer = ByteArray(ATTACHMENT_CHUNK_BYTES)
                while (true) {
                    requireCurrent(cfg)
                    val count = readChunk(input, buffer, context)
                    if (count == 0) break
                    val next = offset + count
                    if (next > MAX_ATTACHMENT_BYTES) throw tooLarge(MAX_ATTACHMENT_BYTES)
                    if (size != null && next > size) throw sizeChanged()
                    val body = FileUploadBody(
                        open = { ByteArrayInputStream(buffer, 0, count) },
                        onProgress = { onProgress(offset + it) },
                        checkActive = { checkRequestActive(context) },
                        maximum = count.toLong(),
                        length = count.toLong(),
                        strings = strings,
                    )
                    val chunkUrl = url.newBuilder().addQueryParameter("offset", offset.toString()).build()
                    val result = request(cfg, "PUT", chunkUrl, CHUNK_PATH, body)
                    val reported = runCatching { (AppJson.parseToJsonElement(result) as? JsonObject)?.get("offset")?.jsonPrimitive?.longOrNull }.getOrNull()
                    if (reported != next) throw decodeError()
                    offset = next
                }
            }
            if (size != null && offset != size) throw sizeChanged()
            val finish = url.newBuilder().addQueryParameter("offset", offset.toString()).addQueryParameter("final", "true").build()
            val file = decodeFile(request(cfg, "PUT", finish, CHUNK_PATH, emptyBinary), MAX_ATTACHMENT_BYTES)
            if (file.id != id || file.size != offset) throw decodeError()
            complete = true
            onProgress(offset)
            return file
        } finally {
            if (!complete) abort(cfg, url)
        }
    }

    private fun readChunk(input: InputStream, buffer: ByteArray, context: CoroutineContext): Int {
        var count = 0
        while (count < buffer.size) {
            context.ensureActive()
            val read = input.read(buffer, count, minOf(64 * 1024, buffer.size - count))
            if (read < 0) break
            count += read
        }
        return count
    }

    private fun endpoint(cfg: ServerConfig, path: String, name: String, mime: String): HttpUrl =
        (cfg.url.trimEnd('/') + path).toHttpUrl().newBuilder().addQueryParameter("name", name).addQueryParameter("mime", mime).build()

    private suspend fun request(cfg: ServerConfig, method: String, url: HttpUrl, logPath: String, body: RequestBody): String {
        currentCoroutineContext().ensureActive()
        requireCurrent(cfg)
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        val trace = HttpTrace().apply { dispatched() }
        val req = Request.Builder().url(url).auth(cfg).tag(HttpTrace::class.java, trace).method(method, body).build()
        return client.newCall(req).readResponse(connectionFailure = { error ->
            val message = error.message ?: strings.text(R.string.core_upload_error)
            NetLog.api(method, logPath, null, elapsed(), null, message)
            ApiException(message).apply { initCause(error) }
        }) { response, _ ->
            val headersMs = elapsed()
            val text = response.peekBody(16L * 1024).string()
            val timing = NetTiming.fromResponse(response, headersMs, elapsed() - headersMs)
            val message = when {
                response.isSuccessful -> null
                response.code == 404 && logPath == STREAM_PATH -> strings.text(R.string.core_upload_server_update_required)
                response.code == 403 && response.header("cf-ray") != null -> strings.text(R.string.core_upload_access_denied)
                else -> response.peekServerError()?.let { localizedServerMessage(it, strings) }
                    ?: strings.text(R.string.core_upload_http_error, response.code)
            }
            NetLog.api(method, logPath, response.code, elapsed(), text.toByteArray().size.toLong(), message, timing = timing)
            if (message != null) throw ApiException(message, response.code)
            text
        }
    }

    private suspend fun abort(cfg: ServerConfig, url: HttpUrl) = withContext(NonCancellable) {
        // 接続先が変更されても、部分アップロードだけは元のサーバーで片付ける。
        runCatching {
            val call = client.newCall(Request.Builder().url(url).auth(cfg).delete().build())
            call.timeout().timeout(5, TimeUnit.SECONDS)
            call.readResponse(connectionFailure = { it }) { _, _ -> Unit }
        }
        Unit
    }

    private fun requireCurrent(cfg: ServerConfig) {
        if (activeServer()?.connectionIdentity() != cfg.connectionIdentity()) {
            throw ApiException(strings.text(R.string.core_send_server_changed))
        }
    }

    private fun decodeFile(body: String, maximum: Long): UploadedFile {
        val file = try { AppJson.decodeFromString(UploadedFile.serializer(), body) }
        catch (_: SerializationException) { throw decodeError() }
        if (file.id.isBlank() || file.size !in 0..maximum) throw decodeError()
        return file
    }

    private fun decodeError() = ApiException(strings.text(R.string.core_upload_decode_error))
    private fun tooLarge(maximum: Long) = ApiException(strings.text(R.string.core_attachment_too_large, maximum / ATTACHMENT_MIB), 413)
    private fun sizeChanged() = ApiException(strings.text(R.string.core_upload_size_changed))

    private fun checkRequestActive(context: CoroutineContext) {
        // OkHttp のバックグラウンドスレッドからは IOException で中断を伝える。
        if (!context.isActive) throw IOException("Canceled")
    }
}
