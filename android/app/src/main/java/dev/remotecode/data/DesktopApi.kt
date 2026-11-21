package dev.remotecode.data

import android.os.SystemClock
import dev.remotecode.R
import dev.remotecode.i18n.AppStringResolver
import dev.remotecode.i18n.text
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

@Serializable
internal data class DesktopDisplay(val id: String, val name: String, val x: Int, val y: Int, val width: Int, val height: Int, val primary: Boolean = false)

@Serializable
internal data class DesktopDisplays(
    val displays: List<DesktopDisplay>,
    val captureMethods: List<String> = listOf("gdi"),
    val codecs: List<String> = listOf("jpeg"),
    val touchpad: Boolean = false,
    val clipboard: Boolean = false,
    val nativeResolution: Boolean = false,
    val conditionalFrames: Boolean = false,
    val cursorMetadata: Boolean = false,
    val frameStream: Boolean = false,
    val regionFrames: Boolean = false,
    val streamBitrate: Boolean = false,
    val layout: String? = null,
    val compressionControls: Boolean = false,
    val audio: Boolean = false,
    val liveDrag: Boolean = false,
)

internal class DesktopStreamUnsupported : IOException()
internal class DesktopStreamHttpError(val status: Int) : IOException("Desktop stream HTTP $status")
internal class DesktopFrameExpired(message: String) : Exception(message)

@Serializable
internal data class DesktopClipboard(val sequence: Long, val text: String? = null, val tooLarge: Boolean = false)
internal data class DesktopPoint(val x: Double, val y: Double)

internal fun defaultScrollPoint(display: String, monitors: List<DesktopDisplay>): DesktopPoint {
    val center = DesktopPoint(0.5, 0.5)
    if (display != "all" || monitors.isEmpty()) return center
    val primary = monitors.firstOrNull { it.primary } ?: monitors.first()
    val left = monitors.minOf { it.x }.toLong()
    val top = monitors.minOf { it.y }.toLong()
    val width = monitors.maxOf { it.x.toLong() + it.width } - left
    val height = monitors.maxOf { it.y.toLong() + it.height } - top
    if (width <= 0 || height <= 0) return center
    return DesktopPoint((primary.x - left + primary.width / 2.0) / width, (primary.y - top + primary.height / 2.0) / height)
}
internal data class DesktopInput(
    val type: String,
    val point: DesktopPoint? = null,
    val end: DesktopPoint? = null,
    val delta: Int? = null,
    val text: String? = null,
    val key: String? = null,
    val dragId: String? = null,
) {
    fun payload(display: String, layout: String, frameAt: Long? = null): JsonObject = buildJsonObject {
        put("type", type)
        put("display", display)
        put("layout", layout)
        frameAt?.let { put("frameAt", it) }
        point?.let { put("x", it.x); put("y", it.y) }
        end?.let { put("endX", it.x); put("endY", it.y) }
        delta?.let { put("delta", it) }
        text?.let { put("text", it) }
        key?.let { put("key", it) }
        dragId?.let { put("dragId", it) }
    }
}

internal class DesktopApi(client: OkHttpClient, private val strings: AppStringResolver, private val current: () -> ServerConfig?) {
    private val http = client.newBuilder().cache(null).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).callTimeout(6, TimeUnit.SECONDS).build()
    private val streamHttp = http.newBuilder().dispatcher(Dispatcher()).callTimeout(0, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    private val frameHttp = streamHttp.newBuilder().callTimeout(60, TimeUnit.SECONDS).build()

    private fun request(server: ServerConfig, path: String): Request.Builder =
        Request.Builder().url(server.url.trimEnd('/') + "/api/desktop/" + path)
            .auth(server).cacheControl(CacheControl.Builder().noStore().noCache().build())

    private fun checkTarget(server: ServerConfig) {
        if (server.connectionIdentity() != current()?.connectionIdentity()) throw ApiException(strings.text(R.string.core_server_changed))
    }

    private suspend fun <T> execute(
        server: ServerConfig, request: Request, allowNotModified: Boolean = false,
        client: OkHttpClient = http, read: (Response) -> T,
    ): T {
        checkTarget(server)
        return client.newCall(request).readResponse(strings::connectionError) { response, _ ->
            checkTarget(server)
            if (!response.isSuccessful && !(allowNotModified && response.code == 304)) {
                val detail = response.peekServerError()
                if (response.code == 409 && detail?.startsWith("Desktop frame has expired") == true) {
                    throw DesktopFrameExpired(strings.text(R.string.desktop_input_wait_frame))
                }
                val message = when (response.code) {
                    404, 405 -> strings.text(R.string.desktop_old_server)
                    409 -> strings.text(R.string.desktop_layout_changed)
                    501 -> strings.text(R.string.desktop_unavailable)
                    else -> detail ?: strings.text(R.string.core_http_error, response.code)
                }
                throw ApiException(message, response.code)
            }
            read(response).also { checkTarget(server) }
        }
    }

    suspend fun displayInfo(server: ServerConfig): DesktopDisplays =
        execute(server, request(server, "displays").build()) {
            AppJson.decodeFromString<DesktopDisplays>(it.body.string())
        }

    suspend fun displays(server: ServerConfig): List<DesktopDisplay> = displayInfo(server).displays

    private fun <T> streamResponse(
        server: ServerConfig, request: () -> Request, read: ProducerScope<T>.(Call, Response) -> Unit,
    ): Flow<T> = callbackFlow {
        checkTarget(server)
        val call = streamHttp.newCall(request())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(e) }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { read(call, it) }
                    close()
                } catch (e: Exception) { close(e) }
            }
        })
        awaitClose { call.cancel() }
    }

    fun audio(server: ServerConfig): Flow<ByteArray> =
        streamResponse<ByteArray>(server, { request(server, "audio").build() }) { call, response ->
            if (!response.isSuccessful) throw DesktopStreamHttpError(response.code)
            val type = response.body.contentType()
            if (type?.type != "audio" || type.subtype != "x-raw" ||
                type.parameter("format") != "S16LE" || type.parameter("rate") != "48000" ||
                type.parameter("channels") != "2") throw DesktopStreamUnsupported()
            val source = response.body.source()
            while (!call.isCanceled()) {
                // The final chunk can be shorter than 20 ms but must contain whole stereo frames.
                val size = if (source.request(3840)) 3840L else source.buffer.size
                if (size == 0L) break
                if (size % 4 != 0L) throw IOException("Incomplete stereo PCM frame")
                val pcm = source.readByteArray(size)
                checkTarget(server)
                if (trySendBlocking(pcm).isClosed) break
            }
        }.buffer(1)

    private fun frameRequest(server: ServerConfig, display: String, resolution: DesktopResolution, capture: String,
                             codec: String, separateCursor: Boolean, fps: Int? = null, regions: Boolean = false,
                             bitrateKbps: Int? = null, compression: DesktopCompression? = null): Request.Builder {
        val req = request(server, if (fps != null) "stream" else "frame")
        val url = req.build().url.newBuilder().addQueryParameter("display", display)
            .addQueryParameter("capture", capture).addQueryParameter("codec", codec)
        if (resolution != DesktopResolution.Standard) url.addQueryParameter("detail", resolution.wire)
        if (separateCursor) url.addQueryParameter("cursor", "separate")
        if (fps != null) url.addQueryParameter("fps", fps.toString())
        if (regions) url.addQueryParameter("regions", "1")
        when (compression) {
            is DesktopCompression.Jpeg -> if (codec == "jpeg") url.addQueryParameter("jpegQuality", compression.quality.toString())
            is DesktopCompression.Png -> if (codec == "png") url.addQueryParameter("pngCompression", compression.wire)
            null -> Unit
        }
        if (fps != null && bitrateKbps != null) url.addQueryParameter("bitrateKbps", bitrateKbps.toString())
        return req.url(url.build())
    }

    suspend fun frame(server: ServerConfig, display: String, resolution: DesktopResolution, capture: String = "auto",
                      codec: String = "jpeg", separateCursor: Boolean = false, previous: DesktopFrame? = null,
                      decodeColor: DesktopDecodeColor = DesktopDecodeColor.Auto, compression: DesktopCompression? = null): DesktopFrame {
        val req = frameRequest(server, display, resolution, capture, codec, separateCursor, compression = compression)
        val reusable = previous?.takeIf { it.codec == codec && it.decodeColor == decodeColor && it.compression == compression }
        reusable?.headers?.etag?.let { req.header("If-None-Match", it) }
        val next = execute(server, req.build(), allowNotModified = reusable?.headers?.etag != null, client = frameHttp) { response ->
            if (response.code == 304) {
                val headers = try { FrameHeaders.read(response.headers) } catch (_: IOException) { return@execute null }
                return@execute reusable?.takeIf { headers.validates(it.headers) }?.copy(headers = headers, receivedPayloadBytes = 0)
            }
            val headers = FrameHeaders.read(response.headers)
            val body = response.body
            if (body.contentType()?.let { it.type == "image" && it.subtype == codec } != true)
                throw ApiException(strings.text(R.string.desktop_invalid_frame))
            val limit = frameByteLimit(resolution == DesktopResolution.Native, codec)
            val source = body.source()
            val size = body.contentLength()
            val bytes = allocateDesktopFrame(strings) {
                if (size < 0) {
                    if (source.request(limit + 1)) throw ApiException(strings.text(R.string.desktop_frame_too_large))
                    source.readByteArray()
                } else {
                    if (size > limit) throw ApiException(strings.text(R.string.desktop_frame_too_large))
                    ByteArray(size.toInt()).also { source.readFully(it) }
                }
            }
            decodeDesktopFrame(bytes, headers, codec, resolution, strings, decodeColor = decodeColor, compression = compression)
        }
        // Intermediaries may omit custom headers on a 304. Request a complete frame before reusing its layout.
        return next ?: frame(server, display, resolution, capture, codec, separateCursor, decodeColor = decodeColor, compression = compression)
    }

    fun stream(server: ServerConfig, display: String, resolution: DesktopResolution, capture: String, codec: String,
               separateCursor: Boolean, fps: Int, regions: Boolean = false, bitrateKbps: Int? = null,
               decodeColor: DesktopDecodeColor = DesktopDecodeColor.Auto, decodeLatest: Boolean = true,
               compression: DesktopCompression? = null,
               onPayloadBytes: (Int) -> Unit = {}): Flow<Pair<Long, DesktopFrame>> {
        val request = frameRequest(server, display, resolution, capture, codec, separateCursor, fps, regions, bitrateKbps, compression).build()
        val needsOrderedDelivery = regions || !decodeLatest
        val packets = streamResponse<DesktopPacketSlot>(server, { request }) { call, response ->
            val type = response.body.contentType()
            val boundary = type?.parameter("boundary")
            if (response.code in listOf(400, 404, 405, 415, 501)) throw DesktopStreamUnsupported()
            if (!response.isSuccessful) throw DesktopStreamHttpError(response.code)
            if (type?.type != "multipart" || type.subtype != "x-mixed-replace" ||
                boundary.isNullOrBlank() || boundary.length > 70) throw DesktopStreamUnsupported()
            val reader = DesktopFrameReader(response.body.source(), boundary, codec,
                frameByteLimit(resolution == DesktopResolution.Native, codec), regions, onPayloadBytes)
            var pending: DesktopPacketSlot? = null
            while (!call.isCanceled()) {
                val packet = allocateDesktopFrame(strings) { reader.next(SystemClock::elapsedRealtime) }
                checkTarget(server)
                if (decodeLatest && pending?.replace(packet) == true) continue
                val slot = DesktopPacketSlot(packet)
                val sent = if (needsOrderedDelivery) trySendBlocking(slot) else trySend(slot)
                if (sent.isClosed) break
                pending = slot
            }
        }.buffer(if (needsOrderedDelivery) 1 else Channel.CONFLATED)
        return flow {
            var bytes: ByteArray? = null
            var last: DesktopFrame? = null
            packets.collect { slot ->
                val packet = slot.take()
                val old = last
                val next = if (old != null && bytes === packet.bytes) {
                    old.copy(headers = packet.headers, receivedPayloadBytes = packet.receivedPayloadBytes)
                } else {
                    if (packet.regions.isNotEmpty() && (old?.headers?.etag != packet.baseETag || old?.layout != packet.headers.layout))
                        throw IOException("Missing decoded desktop patch base")
                    decodeDesktopFrame(packet.bytes, packet.headers, codec, resolution, strings, packet.regions, old?.image,
                        packet.receivedPayloadBytes, decodeColor, compression)
                }
                bytes = packet.bytes
                last = next
                emit(packet.receivedAtMs to next)
            }
        }.flowOn(Dispatchers.Default).buffer(Channel.CONFLATED)
    }

    private fun privateMutation(payload: JsonObject): okhttp3.RequestBody {
        val body = payload.toString().toByteArray()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        // Input is a one-off physical action. Even an HTTP follow-up must not replay it.
        return object : okhttp3.RequestBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: okio.BufferedSink) = body.writeTo(sink)
        }
    }

    suspend fun input(server: ServerConfig, display: String, layout: String, input: DesktopInput, frameAt: Long? = null): Long? =
        execute(server, request(server, "input").post(privateMutation(input.payload(display, layout, frameAt))).build()) {
            it.header("X-Remote-Code-Desktop-Cursor-Revision")?.toLongOrNull()?.takeIf { revision -> revision >= 0 }
        }

    suspend fun clipboard(server: ServerConfig, after: Long? = null): DesktopClipboard {
        val path = if (after == null) "clipboard" else "clipboard?after=$after"
        return execute(server, request(server, path).build(), read = ::readClipboard)
    }

    suspend fun copyToPc(server: ServerConfig, sequence: Long, text: String): DesktopClipboard {
        val payload = buildJsonObject { put("sequence", sequence); put("text", text) }
        return execute(server, request(server, "clipboard").post(privateMutation(payload)).build(), read = ::readClipboard)
    }

    private fun readClipboard(response: Response): DesktopClipboard {
        val source = response.body.source()
        if (source.request(6L * 65536 + 257)) throw IOException("Oversized clipboard response")
        val clip = AppJson.decodeFromString<DesktopClipboard>(source.readUtf8())
        if (clip.sequence !in 0..0xffffffffL || (clip.text?.toByteArray()?.size ?: 0) > 65536) {
            throw IOException("Invalid clipboard response")
        }
        return clip
    }
}
