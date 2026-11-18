package dev.remotecode.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.remotecode.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class DesktopLogEntry(val time: Long, val message: String, val count: Int = 1)

internal class DesktopEventLog {
    val entries = mutableStateListOf<DesktopLogEntry>()

    fun add(message: String, time: Long = System.currentTimeMillis()) {
        val previous = entries.lastOrNull()
        if (previous?.message == message) {
            entries[entries.lastIndex] = previous.copy(time = time, count = previous.count + 1)
            return
        }
        if (entries.size >= 32) entries.removeAt(0)
        entries.add(DesktopLogEntry(time, message))
    }
}

@Composable
internal fun DesktopConnectionLog(log: DesktopEventLog, modifier: Modifier) {
    if (log.entries.isEmpty()) return
    var expanded by remember(log) { mutableStateOf(false) }
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val style = MaterialTheme.typography.labelSmall
    Column(modifier.widthIn(max = 300.dp).background(Color.Black.copy(alpha = .8f), MaterialTheme.shapes.small)
        .padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(Modifier.clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.desktop_log_title), color = Color.White, style = style)
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null,
                modifier = Modifier.size(20.dp), tint = Color.White)
        }
        val entries = if (expanded) log.entries.asReversed() else log.entries.takeLast(2).asReversed()
        Column(if (expanded) Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()) else Modifier) {
            for (entry in entries) {
                val repetitions = if (entry.count > 1) " ×${entry.count}" else ""
                Text("${clock.format(Date(entry.time))}  ${entry.message}$repetitions",
                    color = Color.White, style = style)
            }
        }
    }
}
