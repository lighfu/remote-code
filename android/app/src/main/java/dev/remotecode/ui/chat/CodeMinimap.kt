package dev.remotecode.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.remotecode.ui.common.LazyListScrollTrack
import dev.remotecode.ui.theme.Extra
import kotlinx.coroutines.Dispatchers
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
    val accent = colors.primary
    LazyListScrollTrack(listState, count, description, modifier.width(48.dp).background(colors.surfaceContainer)) { window, movable ->
        val height = if (movable) size.height else minOf(size.height, count * 3.dp.toPx())
        if (height < 1f || size.width < 1f) return@LazyListScrollTrack
        val pad = 2.dp.roundToPx()
        image?.let {
            drawImage(it, dstOffset = androidx.compose.ui.unit.IntOffset(pad, 0),
                dstSize = IntSize((size.width.roundToInt() - 2 * pad).coerceAtLeast(1), height.roundToInt()),
                filterQuality = FilterQuality.Low)
        }
        val thumb = window.thumb(height, 20.dp.toPx())
        val top = window.progress * (height - thumb)
        drawRect(accent.copy(alpha = 0.14f), Offset(0f, top), Size(size.width, thumb))
        drawRect(accent.copy(alpha = 0.85f), Offset(size.width - 3.dp.toPx(), top), Size(3.dp.toPx(), thumb))
    }
}
