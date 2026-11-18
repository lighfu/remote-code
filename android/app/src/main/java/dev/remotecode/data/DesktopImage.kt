package dev.remotecode.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.IOException
import kotlin.math.min

internal data class DesktopRegion(val x: Int, val y: Int, val width: Int, val height: Int, val sourceX: Int = 0, val sourceY: Int = 0) {
    fun fits(frameWidth: Int, frameHeight: Int): Boolean =
        x >= 0 && y >= 0 && width > 0 && height > 0 &&
            x.toLong() + width <= frameWidth && y.toLong() + height <= frameHeight

    companion object {
        fun parse(single: String?, atlas: String?): List<DesktopRegion> {
            if (single == null && atlas == null) return emptyList()
            if (single != null && atlas != null) throw IOException("Ambiguous desktop regions")
            val parts = if (single != null) listOf(single) else checkNotNull(atlas).split(';', limit = 9)
            if (atlas != null && parts.size !in 2..8) throw IOException("Invalid desktop atlas")
            val result = ArrayList<DesktopRegion>(parts.size)
            for (part in parts) {
                val v = part.split(',', limit = 7).map { it.toIntOrNull() ?: throw IOException("Invalid desktop region") }
                if (v.size != if (single != null) 4 else 6) throw IOException("Invalid desktop region")
                val region = DesktopRegion(v[0], v[1], v[2], v[3], v.getOrElse(4) { 0 }, v.getOrElse(5) { 0 })
                if (!region.fits(16_384, 16_384) || !region.fitsSource(16_384, 16_384) ||
                    result.any { region.overlaps(it, false) || region.overlaps(it, true) }) throw IOException("Invalid desktop rectangles")
                result.add(region)
            }
            return result
        }
    }

    fun fitsSource(w: Int, h: Int) = sourceX >= 0 && sourceY >= 0 &&
        sourceX.toLong() + width <= w && sourceY.toLong() + height <= h

    fun covers(other: DesktopRegion) = x <= other.x && y <= other.y &&
        x + width >= other.x + other.width && y + height >= other.y + other.height

    // Keep the visible pieces and their corresponding coordinates in the original atlas.
    fun uncoveredBy(updates: List<DesktopRegion>): List<DesktopRegion> {
        if (updates.any { it.covers(this) }) return emptyList()
        var pieces = listOf(this)
        for (cut in updates) {
            if (pieces.none { it.overlaps(cut, false) }) continue
            pieces = buildList {
                for (piece in pieces) {
                    val left = maxOf(piece.x, cut.x)
                    val top = maxOf(piece.y, cut.y)
                    val right = minOf(piece.x + piece.width, cut.x + cut.width)
                    val bottom = minOf(piece.y + piece.height, cut.y + cut.height)
                    if (left >= right || top >= bottom) {
                        add(piece)
                        continue
                    }
                    fun keep(x: Int, y: Int, r: Int, b: Int) {
                        if (r > x && b > y) add(DesktopRegion(x, y, r - x, b - y,
                            piece.sourceX + x - piece.x, piece.sourceY + y - piece.y))
                    }
                    keep(piece.x, piece.y, piece.x + piece.width, top)
                    keep(piece.x, bottom, piece.x + piece.width, piece.y + piece.height)
                    keep(piece.x, top, left, bottom)
                    keep(right, top, piece.x + piece.width, bottom)
                }
            }
        }
        return pieces
    }

    private fun overlaps(other: DesktopRegion, source: Boolean): Boolean {
        val left = if (source) sourceX else x
        val top = if (source) sourceY else y
        val rightLeft = if (source) other.sourceX else other.x
        val rightTop = if (source) other.sourceY else other.y
        return left < rightLeft + other.width && rightLeft < left + width &&
            top < rightTop + other.height && rightTop < top + height
    }
}

// Keep recent patches as GPU-ready images. Only their still-visible pixels need to be
// baked into new immutable tiles when they exceed the decoder's memory/draw budget.
internal class DesktopImage private constructor(
    val width: Int,
    val height: Int,
    private val background: Background?,
    private val tiles: Array<Bitmap?>,
    private val overlays: List<Overlay> = emptyList(),
) {
    private val columns = (width + TileSize - 1) / TileSize

    fun draw(canvas: Canvas, destination: RectF, paint: Paint) {
        if (canvas.isHardwareAccelerated) textureLimit = min(canvas.maximumBitmapWidth, canvas.maximumBitmapHeight)
        val saved = canvas.save()
        canvas.translate(destination.left, destination.top)
        canvas.scale(destination.width() / width, destination.height() / height)
        val clip = canvas.clipBounds
        val bounds = Rect()
        if (background != null && background.fits(canvas)) {
            bounds.set(0, 0, width, height)
            canvas.drawBitmap(background.bitmap, null, bounds, paint)
        }
        val backgroundTiles = background?.takeUnless { it.fits(canvas) }?.textures()
        for (index in tiles.indices) {
            val tile = tiles[index] ?: backgroundTiles?.get(index) ?: continue
            val x = index % columns * TileSize
            val y = index / columns * TileSize
            bounds.set(x, y, x + tile.width, y + tile.height)
            if (!Rect.intersects(bounds, clip)) continue
            canvas.drawBitmap(tile, null, bounds, paint)
        }
        for (overlay in overlays) overlay.draw(canvas, paint, clip)
        canvas.restoreToCount(saved)
    }

    fun patch(regions: List<DesktopRegion>, bitmap: Bitmap): DesktopImage {
        val retained = ArrayDeque<Overlay>()
        for (old in overlays) {
            val visible = old.regions.flatMap { it.uncoveredBy(regions) }
            if (visible.isNotEmpty()) retained.addLast(old.copy(regions = visible))
        }
        val incoming = Overlay(Background(bitmap), regions)
        val direct = fitsTexture(bitmap)
        if (direct) retained.addLast(incoming)

        var next = tiles
        fun flatten(layer: Overlay) {
            if (next === tiles) next = tiles.clone()
            for (region in layer.regions) updateTiles(next, region, layer.source.bitmap)
        }
        var layeredBytes = retained.sumOf { it.source.bitmap.allocationByteCount.toLong() }
        var rects = retained.sumOf { it.regions.size }
        val keepLatest = if (direct) 1 else 0
        while (retained.size > keepLatest && (retained.size > MaxLayers || rects > MaxLayerRects || layeredBytes > layerBudget)) {
            val oldest = retained.removeFirst()
            flatten(oldest)
            rects -= oldest.regions.size
            layeredBytes -= oldest.source.bitmap.allocationByteCount
        }
        if (!direct) flatten(incoming)
        val remainingBackground = background?.takeIf { next === tiles || next.any { tile -> tile == null } }
        return DesktopImage(width, height, remainingBackground, next, retained)
    }

    private fun updateTiles(next: Array<Bitmap?>, region: DesktopRegion, bitmap: Bitmap) {
        val part = Rect(region.x, region.y, region.x + region.width, region.y + region.height)
        val src = Rect()
        val dst = Rect()
        for (row in part.top / TileSize..(part.bottom - 1) / TileSize) {
            for (col in part.left / TileSize..(part.right - 1) / TileSize) {
                val x = col * TileSize
                val y = row * TileSize
                val w = min(TileSize, width - x)
                val h = min(TileSize, height - y)
                val index = row * columns + col
                if (part.contains(x, y, x + w, y + h)) {
                    next[index] = Bitmap.createBitmap(bitmap, region.sourceX + x - part.left, region.sourceY + y - part.top, w, h)
                    continue
                }
                val old = next[index] ?: checkNotNull(background).bitmap
                // Several surviving strips may touch the same tile in one update. Once a
                // private copy exists, only apply each new strip to it.
                val reuse = next[index] !== tiles[index] && old.isMutable
                val result = if (reuse) old else Bitmap.createBitmap(w, h, old.config ?: Bitmap.Config.ARGB_8888)
                val painter = Canvas(result)
                if (!reuse) {
                    if (next[index] == null) src.set(x, y, x + w, y + h) else src.set(0, 0, w, h)
                    dst.set(0, 0, w, h)
                    painter.drawBitmap(old, src, dst, null)
                }
                dst.set(part)
                if (!dst.intersect(x, y, x + w, y + h)) continue
                src.set(dst)
                src.offset(region.sourceX - part.left, region.sourceY - part.top)
                dst.offset(-x, -y)
                painter.drawBitmap(bitmap, src, dst, null)
                next[index] = result
            }
        }
    }

    companion object {
        private const val TileSize = 512
        private const val CanvasByteLimit = 96 * 1024 * 1024
        private const val MaxLayers = 6
        private const val MaxLayerRects = 64
        private val layerBudget = minOf(24L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)
        @Volatile private var textureLimit = 2048

        private fun fitsTexture(bitmap: Bitmap) = bitmap.width <= textureLimit && bitmap.height <= textureLimit &&
            bitmap.byteCount <= CanvasByteLimit

        fun full(bitmap: Bitmap): DesktopImage {
            val columns = (bitmap.width + TileSize - 1) / TileSize
            val rows = (bitmap.height + TileSize - 1) / TileSize
            val background = Background(bitmap)
            val tiles = arrayOfNulls<Bitmap>(columns * rows)
            if (!fitsTexture(bitmap)) {
                background.textures().copyInto(tiles)
                return DesktopImage(bitmap.width, bitmap.height, null, tiles)
            }
            return DesktopImage(bitmap.width, bitmap.height, background, tiles)
        }
    }

    private data class Overlay(val source: Background, val regions: List<DesktopRegion>) {
        fun draw(canvas: Canvas, paint: Paint, clip: Rect) {
            val src = Rect()
            val dst = Rect()
            val textures = if (source.fits(canvas)) null else source.textures()
            for (region in regions) {
                src.set(region.sourceX, region.sourceY, region.sourceX + region.width, region.sourceY + region.height)
                dst.set(region.x, region.y, region.x + region.width, region.y + region.height)
                if (!Rect.intersects(dst, clip)) continue
                if (textures == null) {
                    canvas.drawBitmap(source.bitmap, src, dst, paint)
                    continue
                }
                // A view can move to a canvas with a smaller texture limit.
                for (row in region.sourceY / TileSize..(region.sourceY + region.height - 1) / TileSize) {
                    for (col in region.sourceX / TileSize..(region.sourceX + region.width - 1) / TileSize) {
                        val tile = textures[row * source.columns + col]
                        val x = col * TileSize
                        val y = row * TileSize
                        src.set(x, y, x + tile.width, y + tile.height)
                        if (!src.intersect(region.sourceX, region.sourceY, region.sourceX + region.width, region.sourceY + region.height)) continue
                        dst.set(src)
                        dst.offset(region.x - region.sourceX, region.y - region.sourceY)
                        src.offset(-x, -y)
                        canvas.drawBitmap(tile, src, dst, paint)
                    }
                }
            }
        }
    }

    private class Background(val bitmap: Bitmap) {
        val columns = (bitmap.width + TileSize - 1) / TileSize
        private val rows = (bitmap.height + TileSize - 1) / TileSize
        private var textureTiles: Array<Bitmap>? = null

        fun fits(canvas: Canvas) = !canvas.isHardwareAccelerated ||
            (bitmap.width <= canvas.maximumBitmapWidth && bitmap.height <= canvas.maximumBitmapHeight &&
                bitmap.byteCount <= CanvasByteLimit)

        // Only the UI thread uses this cache. All incremental images share
        // the same base, even when that base exceeds the device texture size.
        fun textures(): Array<Bitmap> = textureTiles ?: Array(columns * rows) { index ->
            val x = index % columns * TileSize
            val y = index / columns * TileSize
            Bitmap.createBitmap(bitmap, x, y, min(TileSize, bitmap.width - x), min(TileSize, bitmap.height - y))
        }.also { textureTiles = it }
    }
}
