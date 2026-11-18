package dev.remotecode.ui.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.remotecode.R
import kotlin.math.ceil

internal const val DesktopFpsWindowMs = 30_000L
internal data class DesktopOverlaySample(
    val atMs: Long, val fps: Float?, val mbps: Float?, val captureMs: Long?, val decodeMs: Long?,
)

internal data class DesktopDebugStats(
    val width: Int, val height: Int, val capture: String, val codec: String,
    val captureMs: Long?, val decodeMs: Long, val fps: Float?,
)

private enum class Series(val color: Color) {
    Fps(Color(0xff7de4cd)), Bitrate(Color(0xffb4c7ff)), Capture(Color(0xffffc674)), Decode(Color(0xff88d1ff));

    fun value(sample: DesktopOverlaySample): Float? = when (this) {
        Fps -> sample.fps
        Bitrate -> sample.mbps
        Capture -> sample.captureMs?.toFloat()
        Decode -> sample.decodeMs?.toFloat()
    }?.takeIf { it.isFinite() && it >= 0f }
}

@Composable
internal fun DesktopDebugOverlay(
    stats: DesktopDebugStats, history: List<DesktopOverlaySample>, target: Int, graphMask: Int, modifier: Modifier,
) {
    val rate = stats.fps?.let { stringResource(R.string.desktop_debug_fps, it, target) } ?: stringResource(R.string.desktop_fps_warmup)
    val dimensions = "${stats.width} × ${stats.height} · ${stats.capture.uppercase()} · ${stats.codec.uppercase()}"
    val timing = listOfNotNull(stats.captureMs?.let { stringResource(R.string.desktop_debug_capture, it) },
        stringResource(R.string.desktop_debug_decode, stats.decodeMs)).joinToString(" · ")
    val latest = history.lastOrNull()
    val bitrate = latest?.mbps?.let { stringResource(R.string.desktop_debug_bitrate, it) }
    val style = MaterialTheme.typography.labelSmall
    Column(modifier.background(Color.Black.copy(alpha = .78f), MaterialTheme.shapes.small)
        .padding(horizontal = 8.dp, vertical = 5.dp)) {
        Text(listOfNotNull(rate, dimensions, timing, bitrate).joinToString("\n"), color = Color.White, style = style)
        if (graphMask and 1 != 0) Trend(history, Series.Fps, R.string.desktop_fps_history, step = 5)
        if (graphMask and 2 != 0) Trend(history, Series.Bitrate, R.string.desktop_bitrate_history, step = 1)
        if (graphMask and 4 != 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val pc = stringResource(R.string.desktop_graph_pc)
                val decode = stringResource(R.string.desktop_graph_decode)
                Text(if (latest?.captureMs == null) "$pc —" else pc, color = Series.Capture.color, style = style)
                Text(if (latest?.decodeMs == null) "$decode —" else decode, color = Series.Decode.color, style = style)
            }
            Trend(history, Series.Capture, R.string.desktop_timing_history, step = 5, secondary = Series.Decode)
        }
    }
}

@Composable
private fun Trend(history: List<DesktopOverlaySample>, primary: Series, caption: Int, step: Int, secondary: Series? = null) {
    val peak = history.maxOfOrNull { sample ->
        maxOf(primary.value(sample) ?: 0f, secondary?.value(sample) ?: 0f)
    } ?: 0f
    val axisMax = (ceil(peak.toDouble() / step) * step).toInt().coerceAtLeast(step)
    Canvas(Modifier.width(155.dp).height(28.dp).padding(vertical = 3.dp)) {
        val grid = Color.White.copy(alpha = .25f)
        for (line in 0..2) {
            val y = size.height * line / 2
            drawLine(grid, Offset(0f, y), Offset(size.width, y))
        }
        trace(history, primary, axisMax)
        if (secondary != null) trace(history, secondary, axisMax, dashed = true)
    }
    Text(stringResource(caption, (DesktopFpsWindowMs / 1000).toInt(), axisMax),
        color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.labelSmall)
}

private fun DrawScope.trace(history: List<DesktopOverlaySample>, series: Series, axisMax: Int, dashed: Boolean = false) {
    val end = history.lastOrNull()?.atMs ?: return
    val path = Path()
    var previousAt: Long? = null
    var last: Offset? = null
    for (sample in history) {
        val value = series.value(sample)
        if (value == null || end - sample.atMs > DesktopFpsWindowMs) {
            previousAt = null
            last = null
            continue
        }
        val x = size.width * (1f - (end - sample.atMs).toFloat() / DesktopFpsWindowMs)
        val y = size.height * (1f - (value / axisMax).coerceAtMost(1f))
        val point = Offset(x, y)
        if (previousAt == null || sample.atMs - previousAt > 2500L) {
            path.moveTo(x, y)
            drawCircle(series.color, 1.5.dp.toPx(), point)
        } else {
            path.lineTo(x, y)
        }
        previousAt = sample.atMs
        last = point
    }
    val dash = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())) else null
    drawPath(path, series.color, style = Stroke(width = 1.5.dp.toPx(), pathEffect = dash))
    if (last != null) drawCircle(series.color, 2.dp.toPx(), last)
}
