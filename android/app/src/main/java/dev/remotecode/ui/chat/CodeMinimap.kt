package dev.remotecode.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.remotecode.ui.theme.Extra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** A fixed-width map of logical lines. Only the viewport overlay changes during a scroll. */
@Composable
internal fun <T> CodeMinimap(
    rows: List<T>,
    listState: LazyListState,
    description: String,
    modifier: Modifier = Modifier,
    markAt: (Int) -> Char? = { null },
    lineOf: (T) -> CodeLine,
) {
    if (rows.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val inks = codeInkColors()
    val changes = Extra.colors
    val palette = remember(inks, colors, changes) {
        MinimapPalette(
            colors.onSurfaceVariant.toArgb(), inks.map { it.toArgb() }.toIntArray(),
            changes.diffAdd.toArgb(), changes.diffRemove.toArgb(),
            colors.tertiary.toArgb(), colors.secondary.toArgb(),
        )
    }
    val mapKey = remember(rows, lineOf, markAt, palette) { Any() }
    val image = key(mapKey) {
        val map by produceState<ImageBitmap?>(initialValue = null) {
            value = withContext(Dispatchers.Default) {
                val raster = codeMinimapRaster(rows, palette, markAt, lineOf)
                Bitmap.createBitmap(raster.pixels, raster.width, raster.height, Bitmap.Config.ARGB_8888).asImageBitmap()
            }
        }
        map
    }
    val count = rows.size
    val seeks = remember(listState, rows) { Channel<Float>(Channel.CONFLATED) }
    LaunchedEffect(seeks) {
        for (progress in seeks) listState.seekMinimap(progress, count)
    }
    val movable = listState.canScrollForward || listState.canScrollBackward
    val accent = colors.primary
    Canvas(modifier.width(48.dp).background(colors.surfaceContainer)
        .semantics {
            contentDescription = description
            val progress = listState.minimapWindow(count).progress
            progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            if (movable) setProgress { value -> seeks.trySend(value.coerceIn(0f, 1f)).isSuccess }
        }
        .onKeyEvent { event ->
            if (!movable || event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val window = listState.minimapWindow(count)
            val page = window.span / (1f - window.span).coerceAtLeast(1f / count)
            val step = 1f / (count * (1f - window.span)).coerceAtLeast(1f)
            val target = when (event.key) {
                Key.MoveHome -> 0f
                Key.MoveEnd -> 1f
                Key.DirectionUp -> window.progress - step
                Key.DirectionDown -> window.progress + step
                Key.PageUp -> window.progress - page
                Key.PageDown -> window.progress + page
                else -> return@onKeyEvent false
            }
            seeks.trySend(target.coerceIn(0f, 1f)).isSuccess
        }
        .focusable(enabled = movable)
        .pointerInput(seeks, movable) {
            if (!movable) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                val height = size.height.toFloat()
                val window = listState.minimapWindow(count)
                val thumb = window.thumb(height, 20.dp.toPx())
                val travel = height - thumb
                if (travel <= 0f) return@awaitEachGesture
                val top = window.progress * travel
                val y = down.position.y
                val grabbed = y in top..(top + thumb)
                val grabOffset = if (grabbed) y - top else thumb / 2f
                fun seek(yPosition: Float) {
                    seeks.trySend(((yPosition - grabOffset) / travel).coerceIn(0f, 1f))
                }
                down.consume()
                if (!grabbed) seek(y)
                do {
                    val point = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (point.position.y != point.previousPosition.y) seek(point.position.y)
                    point.consume()
                } while (point.pressed)
            }
        }
    ) {
        val height = if (movable) size.height else minOf(size.height, count * 3.dp.toPx())
        if (height < 1f || size.width < 1f) return@Canvas
        val pad = 2.dp.roundToPx()
        image?.let {
            drawImage(it, dstOffset = androidx.compose.ui.unit.IntOffset(pad, 0),
                dstSize = IntSize((size.width.roundToInt() - 2 * pad).coerceAtLeast(1), height.roundToInt()),
                filterQuality = FilterQuality.Low)
        }
        val window = listState.minimapWindow(count)
        val thumb = window.thumb(height, 20.dp.toPx())
        val top = window.progress * (height - thumb)
        drawRect(accent.copy(alpha = 0.14f), Offset(0f, top), Size(size.width, thumb))
        drawRect(accent.copy(alpha = 0.85f), Offset(size.width - 3.dp.toPx(), top), Size(3.dp.toPx(), thumb))
    }
}

internal data class MinimapWindow(val start: Float, val end: Float) {
    val span get() = end - start
    val progress get() = if (span >= 1f) 0f else (start / (1f - span)).coerceIn(0f, 1f)
    fun thumb(height: Float, minimum: Float) = (height * span).coerceIn(minOf(minimum, height / 2), height)
}

internal fun minimapWindow(
    count: Int, firstIndex: Int, firstOffset: Int, firstSize: Int, lastIndex: Int, lastOffset: Int,
    lastSize: Int, viewportStart: Int, viewportEnd: Int,
): MinimapWindow {
    if (count <= 0) return MinimapWindow(0f, 1f)
    fun position(index: Int, offset: Int, size: Int, edge: Int): Float {
        val portion = ((edge - offset).toFloat() / size.coerceAtLeast(1)).coerceIn(0f, 1f)
        return ((index + portion) / count).coerceIn(0f, 1f)
    }
    return MinimapWindow(position(firstIndex, firstOffset, firstSize, viewportStart),
        position(lastIndex, lastOffset, lastSize, viewportEnd))
}

private fun LazyListState.minimapWindow(count: Int): MinimapWindow {
    val info = layoutInfo
    val items = info.visibleItemsInfo
    val first = items.firstOrNull { it.offset + it.size > info.viewportStartOffset } ?: return MinimapWindow(0f, 1f)
    val last = items.lastOrNull { it.offset < info.viewportEndOffset } ?: first
    return minimapWindow(count, first.index, first.offset, first.size, last.index, last.offset, last.size,
        info.viewportStartOffset, info.viewportEndOffset)
}

private suspend fun LazyListState.seekMinimap(progress: Float, count: Int) {
    if (progress <= 0f) return scrollToItem(0)
    val position = if (progress >= 1f) count.toFloat() else progress * (1f - minimapWindow(count).span) * count
    val index = position.toInt().coerceIn(0, count - 1)
    var item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        // An offscreen wrapped line has no measured height until the lazy list lays it out.
        scrollToItem(index)
        item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    }
    val pixels = ((position - index) * item.size).roundToInt() - layoutInfo.viewportStartOffset
    scrollToItem(index, pixels.coerceAtLeast(0))
}
