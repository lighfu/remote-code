package dev.remotecode.ui.desktop

import dev.remotecode.data.DesktopCursor
import dev.remotecode.data.DesktopDisplay
import dev.remotecode.data.DesktopInput
import dev.remotecode.data.DesktopPoint
import kotlin.math.round

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
            "moveBy", "cursorDragBy" -> input.point?.let(::relativePosition)
            "drag" -> input.end
            "move", "click", "doubleClick", "rightClick", "scroll" -> input.point
            else -> null
        } ?: return
        predicted = next
        awaitingInput = true
    }

    fun relativePosition(delta: DesktopPoint): DesktopPoint? {
        val start = location ?: return null
        if (source.revision == null || targets.isEmpty()) return null
        return nearest(start.x + delta.x, start.y + delta.y)
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
        if (!awaitingInput && (source.revision ?: return) >= ack) reset()
    }

    fun reset() {
        predicted = null
        awaitingInput = false
        acknowledged = null
    }

    // Project into a real monitor in physical pixels. Normalized distances would pick the
    // wrong screen in the gaps of a wide, staggered virtual desktop.
    private fun nearest(x: Double, y: Double): DesktopPoint {
        val px = left + round(x * spanX)
        val py = top + round(y * spanY)
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
}
