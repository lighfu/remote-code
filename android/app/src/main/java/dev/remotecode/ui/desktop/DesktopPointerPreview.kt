package dev.remotecode.ui.desktop

import dev.remotecode.data.DesktopCursor
import dev.remotecode.data.DesktopDisplay
import dev.remotecode.data.DesktopInput
import dev.remotecode.data.DesktopPoint
import kotlin.math.floor

/** Main-thread cursor state. A sampled Windows position supersedes local motion once it includes the input. */
internal class DesktopPointerPreview {
    private var source = DesktopCursor(embedded = true)
    private var predicted: DesktopPoint? = null
    private var awaitingInput = false
    private var acknowledged: Long? = null
    private var display = ""
    private var displays: List<DesktopDisplay> = emptyList()
    private var targets: List<DesktopDisplay> = emptyList()
    private var left = 0.0
    private var top = 0.0
    private var spanX = 1.0
    private var spanY = 1.0
    private var remainderX = 0.0
    private var remainderY = 0.0

    /** An embedded Windows cursor still has a position the viewport can follow. */
    val location: DesktopPoint? get() = predicted ?: source.position
    val position: DesktopPoint? get() = predicted ?: source.position?.takeUnless { source.embedded }
    val embedded: Boolean get() = source.embedded

    fun setDisplays(selection: String, list: List<DesktopDisplay>) {
        if (selection == display && list == displays) return
        display = selection
        displays = list
        targets = list.filter { (selection == "all" || it.id == selection) && it.width > 0 && it.height > 0 }
        if (targets.isNotEmpty()) {
            left = targets.minOf { it.x }.toDouble()
            top = targets.minOf { it.y }.toDouble()
            spanX = (targets.maxOf { it.x.toDouble() + it.width } - left - 1).coerceAtLeast(1.0)
            spanY = (targets.maxOf { it.y.toDouble() + it.height } - top - 1).coerceAtLeast(1.0)
        }
        source = DesktopCursor(embedded = true)
        reset()
    }

    fun sample(cursor: DesktopCursor) {
        val revision = cursor.revision
        if (revision != null && source.revision?.let { revision < it } == true) return
        source = cursor
        if (cursor.position == null || revision == null) reset() else reconcile()
    }

    fun queued(input: DesktopInput) {
        if (source.revision == null || targets.isEmpty()) return
        if (location == null) return
        val next = when (input.type) {
            "moveBy", "cursorDragBy", "cursorDragMove" -> input.point
                ?.takeIf { it.x != 0.0 || it.y != 0.0 }?.let(::relativePosition)
            "drag" -> input.end
            "move", "click", "doubleClick", "rightClick", "scroll" -> input.point
            else -> null
        } ?: return
        predicted = next
        awaitingInput = true
    }

    fun quantize(input: DesktopInput): DesktopInput? {
        if (targets.isEmpty() || (input.type != "moveBy" && input.type != "cursorDragMove")) return input
        val delta = input.point ?: return input
        if (delta.x == 0.0 && delta.y == 0.0) return input // Drag keepalive.
        val x = delta.x * spanX + remainderX
        val y = delta.y * spanY + remainderY
        val pixelsX = roundPixels(x).coerceIn(-spanX, spanX)
        val pixelsY = roundPixels(y).coerceIn(-spanY, spanY)
        remainderX = x - pixelsX
        remainderY = y - pixelsY
        if (pixelsX == 0.0 && pixelsY == 0.0) return null
        return input.copy(point = DesktopPoint(pixelsX / spanX, pixelsY / spanY))
    }

    fun relativePosition(delta: DesktopPoint): DesktopPoint? {
        val start = location ?: return null
        if (source.revision == null || targets.isEmpty()) return null
        return nearest(left + roundPixels(start.x * spanX) + roundPixels(delta.x * spanX),
            top + roundPixels(start.y * spanY) + roundPixels(delta.y * spanY))
    }

    fun settle(revision: Long?, moreQueued: Boolean) {
        if (predicted == null) return
        if (revision == null) {
            reset()
            return
        }
        acknowledged = revision
        awaitingInput = moreQueued
        reconcile()
    }

    private fun reconcile() {
        val ack = acknowledged ?: return
        if (!awaitingInput && (source.revision ?: return) >= ack) clearPrediction()
    }

    fun reset() {
        remainderX = 0.0
        remainderY = 0.0
        clearPrediction()
    }

    private fun clearPrediction() {
        predicted = null
        awaitingInput = false
        acknowledged = null
    }

    // Project into a real monitor in physical pixels. Normalized distances would pick the
    // wrong screen in the gaps of a wide, staggered virtual desktop.
    private fun nearest(px: Double, py: Double): DesktopPoint {
        var bestX = px
        var bestY = py
        var distance = Double.POSITIVE_INFINITY
        for (monitor in targets) {
            val mx = px.coerceIn(monitor.x.toDouble(), monitor.x.toDouble() + monitor.width - 1)
            val my = py.coerceIn(monitor.y.toDouble(), monitor.y.toDouble() + monitor.height - 1)
            val dx = px - mx
            val dy = py - my
            val squared = dx * dx + dy * dy
            if (squared < distance) {
                bestX = mx
                bestY = my
                distance = squared
            }
        }
        return DesktopPoint((bestX - left) / spanX, (bestY - top) / spanY)
    }

    // Match Go's math.Round for positive and negative physical pixel deltas.
    private fun roundPixels(value: Double): Double = if (value < 0.0) -floor(0.5 - value) else floor(0.5 + value)
}
