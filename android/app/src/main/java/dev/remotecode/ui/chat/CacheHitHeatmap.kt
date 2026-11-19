package dev.remotecode.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.remotecode.R
import dev.remotecode.data.CacheDetail
import dev.remotecode.data.CacheHeatmap
import dev.remotecode.data.CacheHeatmapCell
import dev.remotecode.data.CacheRequest

private const val Columns = 16
private const val MaxCells = 128
private val CellHeight = 15.dp
private val CellGap = 3.dp
private val HitColors = listOf(Color(0xFFD56C80), Color(0xFFD7AA55), Color(0xFF318878), Color(0xFF65CF9E))
private val HitLabels = listOf("<50%", "50–89%", "90–98%", "≥99%")

private fun hitBand(rate: Double): Int = when {
    rate < 0.50 -> 0
    rate < 0.90 -> 1
    rate < 0.99 -> 2
    else -> 3
}

private fun recentHeatmap(requests: List<CacheRequest>): CacheHeatmap {
    val groupSize = ((requests.size + MaxCells - 1) / MaxCells).coerceAtLeast(1)
    val cells = requests.asReversed().chunked(groupSize).map { group ->
        var readTotal = 0.0
        var inputTotal = 0.0
        var low = 0
        for (request in group) {
            val read = request.read.coerceAtLeast(0).toDouble()
            val input = read + request.write5m.coerceAtLeast(0) + request.write1h.coerceAtLeast(0) + request.uncached.coerceAtLeast(0)
            readTotal += read
            inputTotal += input
            if (input > 0 && read < 0.9 * input) low++
        }
        CacheHeatmapCell(readTotal, inputTotal, low)
    }
    return CacheHeatmap(groupSize, cells)
}

@Composable
internal fun CacheHitHeatmap(detail: CacheDetail) {
    val history = detail.heatmap?.takeIf { it.requestsPerCell > 0 && it.cells.isNotEmpty() && detail.totals.requests > 0 }
    val heatmap = history ?: remember(detail.recent) { recentHeatmap(detail.recent) }
    val cells = heatmap.cells
    if (cells.isEmpty()) return

    val groupSize = heatmap.requestsPerCell
    val partial = history == null && detail.totals.requests > detail.recent.size
    val offset = if (partial) detail.totals.requests - detail.recent.size else 0
    val lastRequest = if (history != null) detail.totals.requests else offset + detail.recent.size
    val rows = (cells.size + Columns - 1) / Columns
    var selectedRequest by remember { mutableIntStateOf(0) }
    val selectedIndex = if (selectedRequest == 0) cells.lastIndex
        else ((selectedRequest - offset - 1) / groupSize).coerceIn(0, cells.lastIndex)
    fun select(index: Int) {
        selectedRequest = offset + index * groupSize + 1
    }
    val first = offset + selectedIndex * groupSize + 1
    val last = minOf(lastRequest, first + groupSize - 1)
    val cell = cells[selectedIndex]
    val title = stringResource(R.string.cache_heatmap_title)
    val caption = stringResource(
        if (partial) R.string.cache_heatmap_recent else R.string.cache_heatmap_order,
        if (partial) detail.recent.size else groupSize,
    )
    val range = if (first == last) first.toString() else "$first–$last"
    val rate = cell.hitRate?.let { stringResource(R.string.cache_heatmap_percent, it * 100) }
        ?: stringResource(R.string.cache_heatmap_no_input)
    val selection = stringResource(R.string.cache_heatmap_selection, range, rate)
    val previous = stringResource(R.string.cache_heatmap_previous)
    val next = stringResource(R.string.cache_heatmap_next)
    val selectedColor = MaterialTheme.colorScheme.onSurface
    val emptyColor = MaterialTheme.colorScheme.outlineVariant
    val lowColor = HitColors.first()
    val grouped = groupSize > 1

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Canvas(
                Modifier.fillMaxWidth().height(CellHeight * rows + CellGap * (rows - 1))
                    .semantics {
                        contentDescription = title
                        stateDescription = selection
                        customActions = buildList {
                            if (selectedIndex > 0) add(CustomAccessibilityAction(previous) { select(selectedIndex - 1); true })
                            if (selectedIndex < cells.lastIndex) add(CustomAccessibilityAction(next) { select(selectedIndex + 1); true })
                        }
                    }
                    .pointerInput(offset, groupSize, cells.size) {
                        detectTapGestures { tap ->
                            val gap = CellGap.toPx()
                            val cellWidth = (size.width - gap * (Columns - 1)) / Columns
                            if (cellWidth <= 0 || tap.x < 0 || tap.y < 0) return@detectTapGestures
                            val column = (tap.x / (cellWidth + gap)).toInt()
                            val row = (tap.y / (CellHeight.toPx() + gap)).toInt()
                            val index = row * Columns + column
                            if (column in 0 until Columns && index in cells.indices) select(index)
                        }
                    },
            ) {
                val gap = CellGap.toPx()
                val cellWidth = (size.width - gap * (Columns - 1)) / Columns
                if (cellWidth <= 0) return@Canvas
                val height = CellHeight.toPx()
                val corner = CornerRadius(3.dp.toPx())
                cells.forEachIndexed { i, entry ->
                    val origin = Offset((i % Columns) * (cellWidth + gap), (i / Columns) * (height + gap))
                    val color = entry.hitRate?.let { HitColors[hitBand(it)] } ?: emptyColor
                    drawRoundRect(color, origin, Size(cellWidth, height), corner)
                    if (grouped && entry.low > 0 && (entry.hitRate ?: 0.0) >= 0.90) {
                        val mark = 3.dp.toPx()
                        drawRoundRect(lowColor, origin + Offset(0f, height - mark), Size(cellWidth, mark), CornerRadius(mark / 2))
                    }
                    if (i == selectedIndex) drawRoundRect(selectedColor, origin, Size(cellWidth, height), corner, style = Stroke(1.5.dp.toPx()))
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HitColors.zip(HitLabels).forEach { (color, label) -> HeatmapLegend(color, label) }
            }
            if (grouped) {
                Text(stringResource(R.string.cache_heatmap_marks), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(selection, style = MaterialTheme.typography.labelMedium)
            if (grouped && cell.low > 0) Text(stringResource(R.string.cache_heatmap_low, cell.low), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun HeatmapLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
