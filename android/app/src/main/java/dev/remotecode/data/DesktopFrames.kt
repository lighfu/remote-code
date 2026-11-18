package dev.remotecode.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.remotecode.R
import dev.remotecode.i18n.AppStringResolver
import dev.remotecode.i18n.text
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Headers
import okio.BufferedSource

internal data class DesktopCursor(val position: DesktopPoint? = null, val embedded: Boolean = false, val revision: Long? = null)

internal data class FrameHeaders(
    val layout: String,
    val capture: String,
    val etag: String?,
    val cursor: DesktopCursor,
    val captureMs: Long?,
    val frameAt: Long?,
) {
    companion object {
        fun read(headers: Headers): FrameHeaders {
            val prefix = "X-Remote-Code-Desktop-"
            val layout = headers[prefix + "Layout"]?.takeIf { it.isNotBlank() } ?: throw IOException("Missing desktop layout")
            val revision = headers[prefix + "Cursor-Revision"]?.toLongOrNull()?.takeIf { it >= 0 }
            fun point(value: String): DesktopPoint {
                val xy = value.split(',', limit = 2).map { it.toDoubleOrNull()?.takeIf { n -> n in 0.0..1.0 } }
                val x = xy.firstOrNull()
                val y = xy.getOrNull(1)
                if (x == null || y == null) throw IOException("Invalid desktop cursor")
                return DesktopPoint(x, y)
            }
            val cursor = when (val value = headers[prefix + "Cursor"]) {
                null, "embedded" -> DesktopCursor(headers[prefix + "Cursor-Position"]?.let(::point), embedded = true, revision = revision)
                "hidden" -> DesktopCursor(revision = revision)
                else -> DesktopCursor(point(value), revision = revision)
            }
            return FrameHeaders(layout, headers[prefix + "Capture"] ?: "gdi", headers["ETag"], cursor,
                headers[prefix + "Frame-Ms"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.toLong(),
                headers[prefix + "Frame-At"]?.toLongOrNull()?.takeIf { it > 0 })
        }
    }

    fun validates(previous: FrameHeaders?) = previous != null && etag != null && etag == previous.etag && layout == previous.layout
}

internal data class DesktopFrame(
    val image: DesktopImage, val headers: FrameHeaders, val codec: String, val decodeMs: Long,
    // Stream total (including skipped frames), or this response's body size when polling.
    val receivedPayloadBytes: Long,
) {
    val layout get() = headers.layout
    val captureMethod get() = headers.capture
}

// Large virtual desktops can span several 4K monitors. Check the area before allocating pixels.
internal fun supportsFrameDimensions(width: Int, height: Int, native: Boolean): Boolean {
    val edge = if (native) 16_384 else 4096
    return width in 1..edge && height in 1..edge && width.toLong() * height <= 32L * 1024 * 1024
}

internal fun frameByteLimit(native: Boolean, codec: String): Long {
    val limitMiB = when {
        native -> 132L  // Includes row filters and PNG framing for 32 M pixels at 4 bytes/pixel.
        codec == "png" -> 32L
        else -> 12L
    }
    return minOf(limitMiB * 1024 * 1024, Runtime.getRuntime().maxMemory() / 2)
}

internal data class EncodedDesktopFrame(
    val bytes: ByteArray, val headers: FrameHeaders, val receivedAtMs: Long,
    val regions: List<DesktopRegion> = emptyList(), val baseETag: String? = null,
    val receivedPayloadBytes: Long = bytes.size.toLong(),
) {
    // Both packets have already passed wire ETag validation. An independent keyframe or
    // a patch replacing every affected pixel makes decoding the older packet redundant.
    fun replacedBy(next: EncodedDesktopFrame): EncodedDesktopFrame? = when {
        bytes === next.bytes -> copy(headers = next.headers, receivedAtMs = next.receivedAtMs,
            receivedPayloadBytes = next.receivedPayloadBytes)
        next.regions.isEmpty() -> next
        regions.isNotEmpty() && next.baseETag == headers.etag && next.headers.layout == headers.layout &&
            regions.all { old -> next.regions.any { it.covers(old) } } -> next.copy(baseETag = baseETag)
        else -> null
    }
}

// One reader publishes into the bounded channel, and one decoder takes the slot.
// Until the decoder takes it, a newer replacement can stay current without waiting
// for a slow decode or growing a queue of already-obsolete images on the socket.
internal class DesktopPacketSlot(first: EncodedDesktopFrame) {
    private val pending = AtomicReference<EncodedDesktopFrame?>(first)

    fun replace(next: EncodedDesktopFrame): Boolean {
        val old = pending.get() ?: return false
        val replacement = old.replacedBy(next) ?: return false
        return pending.compareAndSet(old, replacement)
    }

    fun take(): EncodedDesktopFrame = checkNotNull(pending.getAndSet(null))
}

internal fun <T> allocateDesktopFrame(strings: AppStringResolver, block: () -> T): T = try {
    block()
} catch (_: OutOfMemoryError) {
    throw ApiException(strings.text(R.string.desktop_frame_too_large))
}

internal fun decodeDesktopFrame(
    bytes: ByteArray, headers: FrameHeaders, codec: String, resolution: DesktopResolution, strings: AppStringResolver,
    regions: List<DesktopRegion> = emptyList(), previous: DesktopImage? = null, receivedPayloadBytes: Long = bytes.size.toLong(),
): DesktopFrame {
    val start = System.nanoTime()
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outMimeType != "image/$codec" || options.outWidth <= 0 || options.outHeight <= 0)
        throw ApiException(strings.text(R.string.desktop_invalid_frame))
    val highColor = codec == "png" || resolution != DesktopResolution.Standard
    val pixelBytes = options.outWidth.toLong() * options.outHeight * if (highColor) 4 else 2
    if (!supportsFrameDimensions(options.outWidth, options.outHeight, resolution == DesktopResolution.Native) ||
        pixelBytes > Runtime.getRuntime().maxMemory() / 2)
        throw ApiException(strings.text(R.string.desktop_frame_too_large))
    if (regions.isNotEmpty() && (previous == null ||
        regions.any { !it.fits(previous.width, previous.height) || !it.fitsSource(options.outWidth, options.outHeight) } ||
        (regions.size == 1 && (regions[0].width != options.outWidth || regions[0].height != options.outHeight))))
        throw IOException("Desktop patch dimensions do not match the frame")
    options.inJustDecodeBounds = false
    options.inPreferredConfig = if (highColor) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
    val image = allocateDesktopFrame(strings) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("Invalid desktop image")
        if (regions.isEmpty()) DesktopImage.full(bitmap) else checkNotNull(previous).patch(regions, bitmap)
    }
    return DesktopFrame(image, headers, codec, (System.nanoTime() - start) / 1_000_000, receivedPayloadBytes)
}

/** A keyframe, an ordered region update, or a fresh validation of the last image. */
internal class DesktopFrameReader(
    private val source: BufferedSource, private val boundary: String, private val codec: String, private val limit: Long,
    private val allowRegions: Boolean = false, private val onPayloadBytes: (Int) -> Unit = {},
) {
    private var previous: EncodedDesktopFrame? = null

    fun next(now: () -> Long): EncodedDesktopFrame {
        if (previous != null && source.readUtf8LineStrict(2).isNotEmpty()) throw IOException("Invalid desktop frame separator")
        if (source.readUtf8LineStrict(100) != "--$boundary") throw IOException("Desktop stream ended")
        val builder = Headers.Builder()
        var headerBytes = 0
        while (true) {
            val line = source.readUtf8LineStrict(2048)
            if (line.isEmpty()) break
            headerBytes += line.length + 2
            if (headerBytes > 8192 || ':' !in line) throw IOException("Invalid desktop frame headers")
            builder.add(line.substringBefore(':'), line.substringAfter(':').trim())
        }
        val headers = builder.build()
        val meta = FrameHeaders.read(headers)
        val prefix = "X-Remote-Code-Desktop-"
        val regions = DesktopRegion.parse(headers[prefix + "Region"], headers[prefix + "Regions"])
        val baseETag = headers[prefix + "Base-ETag"]
        if (regions.isNotEmpty()) {
            val old = previous?.headers
            if (!allowRegions || old == null || baseETag.isNullOrBlank() || old.etag != baseETag ||
                meta.etag.isNullOrBlank() || meta.etag == baseETag || meta.layout != old.layout)
                throw IOException("Desktop patch requires its preceding image")
        } else if (baseETag != null) throw IOException("Unexpected desktop patch validator")
        val length = headers["Content-Length"]?.toLongOrNull() ?: throw IOException("Missing desktop frame length")
        if (length !in 0..limit) throw IOException("Invalid desktop frame length")
        val receivedAt = now()
        val bytes = when {
            length > 0 && headers["Content-Type"] == "image/$codec" -> ByteArray(length.toInt()).also { source.readFully(it) }
            length == 0L && regions.isEmpty() && headers["Content-Type"] == "application/octet-stream" && meta.validates(previous?.headers) -> previous!!.bytes
            else -> throw IOException("Invalid desktop frame")
        }
        val total = (previous?.receivedPayloadBytes ?: 0L) + length
        if (length > 0) onPayloadBytes(length.toInt())
        return EncodedDesktopFrame(bytes, meta, receivedAt, regions, baseETag, total).also { previous = it }
    }
}
