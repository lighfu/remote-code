package dev.remotecode.ui.chat

import dev.remotecode.R
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.remotecode.data.Entry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** entries は古い順、LazyColumn は逆順。 */
private class ConvIndex(private val list: LazyListState, private val entries: List<Entry>, private val offset: Int) {
    val size get() = entries.size
    fun listIndexOf(conv: Int) = offset + (entries.size - 1 - conv)
    fun convOf(listIndex: Int) = (entries.size - 1 - (listIndex - offset)).coerceIn(0, (entries.size - 1).coerceAtLeast(0))

    fun topConv(): Int {
        val top = list.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: return entries.size - 1
        return convOf(top)
    }
}

private fun Entry.isUserMessage() = kind == "user"

private suspend fun LazyListState.bringToTop(index: Int) {
    scrollToItem(index)
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewport = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    val shift = viewport - info.size - 24
    if (shift > 0) scrollBy(-shift.toFloat())
}

@Composable
fun ScrollHaptics(list: LazyListState, entries: List<Entry>, offset: Int) {
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    val idx = remember(entries, offset) { ConvIndex(list, entries, offset) }
    val userMarks = remember(entries) { entries.mapIndexedNotNull { i, e -> if (e.isUserMessage()) i else null } }
    LaunchedEffect(idx, userMarks) {
        var lastTurn: Int? = null
        var lastEdge: Boolean? = null
        androidx.compose.runtime.snapshotFlow {
            val top = idx.topConv()
            Triple(userMarks.count { it <= top }, list.canScrollForward, list.canScrollBackward)
        }.collect { (turn, fwd, back) ->
            val edge = !fwd || !back
            if (list.isScrollInProgress) {
                if (lastTurn != null && turn != lastTurn) haptics.tick()
                if (lastEdge == false && edge) haptics.edge()
            }
            lastTurn = turn
            lastEdge = edge
        }
    }
}

@Composable
fun ConversationRail(list: LazyListState, entries: List<Entry>, offset: Int, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val idx = remember(entries, offset) { ConvIndex(list, entries, offset) }
    var height by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    var visible by remember { mutableStateOf(false) }
    val scrolling by remember { derivedStateOf { list.isScrollInProgress } }
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) visible = true
        else {
            delay(1500)
            visible = false
        }
    }
    if (entries.size < 8) return

    // 0 は最古、1 は最新。
    val frac by remember(idx) {
        derivedStateOf { if (idx.size <= 1) 1f else idx.topConv().toFloat() / (idx.size - 1) }
    }
    val shown = if (dragging) dragFrac else frac
    val userMarks = remember(entries) { entries.mapIndexedNotNull { i, e -> if (e.isUserMessage()) i else null } }
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    var lastTurn by remember { mutableIntStateOf(-1) }

    fun jump(f: Float) {
        dragFrac = f.coerceIn(0f, 1f)
        val conv = (dragFrac * (idx.size - 1)).roundToInt()
        val turn = userMarks.count { it <= conv }
        if (turn != lastTurn) {
            if (lastTurn >= 0) haptics.fineTick()
            lastTurn = turn
        }
        scope.launch { list.bringToTop(idx.listIndexOf(conv)) }
    }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Box(Modifier.fillMaxHeight().padding(vertical = 12.dp)) {
            if (dragging) {
                val conv = (dragFrac * (idx.size - 1)).roundToInt()
                val near = userMarks.lastOrNull { it <= conv }?.let { entries[it] } ?: userMarks.firstOrNull()?.let { entries[it] }
                val y = (dragFrac * height).roundToInt()
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(-120, (y - 60).coerceAtLeast(0)) }
                        .widthIn(max = 260.dp),
                ) {
                    Text(
                        near?.text?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: stringResource(R.string.chat_conversation_nav_004),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .width(28.dp)
                    .fillMaxHeight()
                    .onSizeChanged { height = it.height.coerceAtLeast(1) }
                    .pointerInput(idx) {
                        detectTapGestures { p -> jump(p.y / height) }
                    }
                    .pointerInput(idx) {
                        detectVerticalDragGestures(
                            onDragStart = { p -> dragging = true; lastTurn = -1; haptics.context(); jump(p.y / height) },
                            onDragEnd = { dragging = false; haptics.edge() },
                            onDragCancel = { dragging = false },
                        ) { change, _ -> change.consume(); jump(change.position.y / height) }
                    },
            ) {
                val density = LocalDensity.current
                Box(
                    Modifier.align(Alignment.TopCenter).width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                )
                userMarks.forEach { i ->
                    val y = if (idx.size <= 1) 0 else (i.toFloat() / (idx.size - 1) * height).roundToInt()
                    Box(
                        Modifier.align(Alignment.TopCenter).offset { IntOffset(0, y - with(density) { 1.dp.roundToPx() }) }
                            .size(width = 12.dp, height = 3.dp).clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
                    )
                }
                val thumb = 40.dp
                val thumbPx = with(density) { thumb.roundToPx() }
                Box(
                    Modifier.align(Alignment.TopCenter)
                        .offset { IntOffset(0, (shown * (height - thumbPx)).roundToInt()) }
                        .size(width = 8.dp, height = thumb).clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
fun MessageJumpButtons(list: LazyListState, entries: List<Entry>, offset: Int, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val idx = remember(entries, offset) { ConvIndex(list, entries, offset) }
    val userMarks = remember(entries) { entries.mapIndexedNotNull { i, e -> if (e.isUserMessage()) i else null } }
    if (userMarks.size < 2) return
    val top by remember(idx) { derivedStateOf { idx.topConv() } }
    val prev = userMarks.lastOrNull { it < top }
    val next = userMarks.firstOrNull { it > top }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 3.dp,
        modifier = modifier,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.IconButton(onClick = { prev?.let { p -> scope.launch { list.bringToTop(idx.listIndexOf(p)) } } }, enabled = prev != null) {
                Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.chat_conversation_nav_003))
            }
            Text(stringResource(R.string.chat_conversation_nav_002), style = MaterialTheme.typography.labelSmall)
            androidx.compose.material3.IconButton(onClick = { next?.let { n -> scope.launch { list.bringToTop(idx.listIndexOf(n)) } } }, enabled = next != null) {
                Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.chat_conversation_nav_001))
            }
        }
    }
}
