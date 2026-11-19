package dev.remotecode.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlin.math.roundToInt

@Composable
internal fun LazyListScrollTrack(
    listState: LazyListState,
    count: Int,
    description: String,
    modifier: Modifier,
    drawTrack: DrawScope.(ListScrollWindow, Boolean) -> Unit,
) {
    val seeks = remember(listState, count) { Channel<Float>(Channel.CONFLATED) }
    LaunchedEffect(seeks) {
        for (progress in seeks) listState.seekTrack(progress, count)
    }
    val movable = listState.canScrollForward || listState.canScrollBackward
    Canvas(modifier
        .semantics {
            contentDescription = description
            val progress = listState.scrollWindow(count).progress
            progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            if (movable) setProgress { value -> seeks.trySend(value.coerceIn(0f, 1f)).isSuccess }
        }
        .onKeyEvent { event ->
            if (!movable || event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val window = listState.scrollWindow(count)
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
                val window = listState.scrollWindow(count)
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
    ) { drawTrack(listState.scrollWindow(count), movable) }
}

@Composable
internal fun LazyListScrollbar(listState: LazyListState, description: String, modifier: Modifier = Modifier) {
    if (!listState.canScrollForward && !listState.canScrollBackward) return
    val track = MaterialTheme.colorScheme.outlineVariant
    val handle = MaterialTheme.colorScheme.primary
    LazyListScrollTrack(listState, listState.layoutInfo.totalItemsCount, description, modifier.width(24.dp)) { window, _ ->
        val width = 4.dp.toPx()
        val x = (size.width - width) / 2
        val corner = CornerRadius(width / 2)
        drawRoundRect(track, Offset(x, 0f), Size(width, size.height), corner)
        val thumb = window.thumb(size.height, 20.dp.toPx())
        drawRoundRect(handle, Offset(x, window.progress * (size.height - thumb)), Size(width, thumb), corner)
    }
}

internal data class ListScrollWindow(val start: Float, val end: Float) {
    val span get() = end - start
    val progress get() = if (span >= 1f) 0f else (start / (1f - span)).coerceIn(0f, 1f)
    fun thumb(height: Float, minimum: Float) = (height * span).coerceIn(minOf(minimum, height / 2), height)
}

internal fun scrollWindow(
    count: Int, firstIndex: Int, firstOffset: Int, firstSize: Int, lastIndex: Int, lastOffset: Int,
    lastSize: Int, viewportStart: Int, viewportEnd: Int,
): ListScrollWindow {
    if (count <= 0) return ListScrollWindow(0f, 1f)
    fun position(index: Int, offset: Int, size: Int, edge: Int): Float {
        val portion = ((edge - offset).toFloat() / size.coerceAtLeast(1)).coerceIn(0f, 1f)
        return ((index + portion) / count).coerceIn(0f, 1f)
    }
    return ListScrollWindow(position(firstIndex, firstOffset, firstSize, viewportStart),
        position(lastIndex, lastOffset, lastSize, viewportEnd))
}

private fun LazyListState.scrollWindow(count: Int): ListScrollWindow {
    val info = layoutInfo
    val items = info.visibleItemsInfo
    val first = items.firstOrNull { it.offset + it.size > info.viewportStartOffset } ?: return ListScrollWindow(0f, 1f)
    val last = items.lastOrNull { it.offset < info.viewportEndOffset } ?: first
    return scrollWindow(count, first.index, first.offset, first.size, last.index, last.offset, last.size,
        info.viewportStartOffset, info.viewportEndOffset)
}

private suspend fun LazyListState.seekTrack(progress: Float, count: Int) {
    if (progress <= 0f) return scrollToItem(0)
    val position = if (progress >= 1f) count.toFloat() else progress * (1f - scrollWindow(count).span) * count
    val index = position.toInt().coerceIn(0, count - 1)
    var item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        // An offscreen item has no measured height until the lazy list lays it out.
        scrollToItem(index)
        item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    }
    val pixels = ((position - index) * item.size).roundToInt() - layoutInfo.viewportStartOffset
    scrollToItem(index, pixels.coerceAtLeast(0))
}
