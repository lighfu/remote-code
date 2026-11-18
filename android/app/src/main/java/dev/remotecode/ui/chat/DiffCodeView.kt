package dev.remotecode.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.remotecode.R
import dev.remotecode.ui.theme.CodeScale
import dev.remotecode.ui.theme.Extra
import dev.remotecode.ui.theme.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun DiffCodeView(text: String, path: String, wrapLines: Boolean, modifier: Modifier = Modifier) {
    DiffCodeRows(text, path, wrapLines, modifier, Int.MAX_VALUE, showMinimap = true)
}

@Composable
internal fun DiffCodeRows(text: String, path: String, wrapLines: Boolean, modifier: Modifier, maxLines: Int, showMinimap: Boolean = false) {
    key(text, path, maxLines) {
        val initial = remember { if (text.length <= 16_384) highlightDiff(text, path, maxLines) else null }
        val document by produceState(initial) {
            if (initial == null) value = withContext(Dispatchers.Default) { highlightDiff(text, path, maxLines) }
        }
        val diff = document
        if (diff == null) {
            LinearProgressIndicator(modifier.fillMaxWidth().wrapContentHeight())
        } else {
            DiffTable(diff, wrapLines, modifier, showMinimap)
        }
    }
}

@Composable
private fun DiffTable(diff: DiffCodeDocument, wrapLines: Boolean, modifier: Modifier, showMinimap: Boolean) {
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 12.sp * CodeScale, lineHeight = 18.sp * CodeScale)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cell = remember(style, density, measurer) { with(density) { measurer.measure("M", style).size.width.toDp() } }
    val digits = remember(diff) { diff.rows.maxOfOrNull { maxOf(it.line.old ?: 0, it.line.new ?: 0) } ?: 0 }
    val gutter = cell * maxOf(3, digits.toString().length) + 4.dp
    val sign = cell * 2
    val horizontal = rememberScrollState()
    val list = rememberLazyListState()
    val colors = Extra.colors
    val longest = remember(diff, wrapLines) {
        if (wrapLines) 0 else diff.rows.maxOfOrNull { row ->
            // The code font can fall back to a double-width glyph for CJK and emoji.
            var width = 0
            for (c in row.code.text) width += when { c == '\t' -> 8; c >= '\u2e80' -> 2; else -> 1 }
            width
        } ?: 0
    }
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.chat_code_viewer_003, if (digits == 0) stringResource(R.string.chat_code_viewer_002) else ""),
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().weight(1f, fill = showMinimap)) {
            BoxWithConstraints(Modifier.weight(1f)) {
                // Android text layouts have a finite pixel width. Exceptionally long lines continue
                // on the next visual line inside that width, keeping all their text selectable.
                val maxLayout = with(density) { 16_000.toDp() }
                val width = if (wrapLines) maxWidth else maxOf(maxWidth,
                    minOf(maxLayout, gutter * 2 + sign + cell * longest + 24.dp))
                val scroll = if (wrapLines) Modifier else Modifier.horizontalScroll(horizontal)
                ChatSelectionContainer(Modifier.fillMaxWidth().then(scroll)) {
                    LazyColumn(Modifier.width(width), state = list) {
                        items(diff.rows) { row ->
                            val line = row.line
                            val tint = when (line.kind) {
                                '+' -> colors.diffAdd
                                '-' -> colors.diffRemove
                                else -> Color.Unspecified
                            }
                            val metadata = line.kind == '@'
                            val background = when {
                                tint != Color.Unspecified -> tint.copy(alpha = 0.12f)
                                metadata -> MaterialTheme.colorScheme.surfaceContainerHigh
                                else -> Color.Transparent
                            }
                            Row(Modifier.fillMaxWidth().background(background).padding(horizontal = 4.dp)) {
                                if (!metadata) DisableSelection {
                                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                                    Text(line.old?.toString().orEmpty(), Modifier.width(gutter), style = style, textAlign = TextAlign.End, color = muted)
                                    Text(line.new?.toString().orEmpty(), Modifier.width(gutter), style = style, textAlign = TextAlign.End, color = muted)
                                    Text(line.kind.toString(), Modifier.width(sign), style = style, textAlign = TextAlign.Center, color = tint)
                                }
                                Text(coloredLine(row.code, Int.MAX_VALUE), Modifier.weight(1f), style = style,
                                    color = if (metadata) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                    softWrap = wrapLines || width >= maxLayout)
                            }
                        }
                        if (diff.totalLines > diff.rows.size) item {
                            Text(stringResource(R.string.chat_code_viewer_001, diff.totalLines - diff.rows.size).trimStart('\n'),
                                Modifier.padding(horizontal = 8.dp), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (showMinimap && diff.rows.isNotEmpty()) {
                CodeMinimap(diff.rows, list, stringResource(R.string.code_navigation_minimap), Modifier.fillMaxHeight(),
                    markAt = { diff.rows[it].line.kind }) { it.code }
            }
        }
    }
}
