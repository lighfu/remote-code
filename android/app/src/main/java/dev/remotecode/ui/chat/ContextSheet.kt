package dev.remotecode.ui.chat

import dev.remotecode.R
import dev.remotecode.i18n.appString
import dev.remotecode.i18n.localizedServerMessage
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ContextCategory
import dev.remotecode.data.ContextDetail
import dev.remotecode.data.NamedTokens
import dev.remotecode.data.SessionDetailField
import kotlinx.coroutines.launch

fun tokensText(n: Long): String = when {
    n >= 1_000_000 -> "%.2fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}

private fun categoryLabel(c: ContextCategory): String = when (c.id) {
    "system_prompt" -> appString(R.string.chat_context_sheet_041)
    "system_tools" -> appString(R.string.chat_context_sheet_040)
    "system_tools_deferred" -> appString(R.string.chat_context_sheet_039)
    "mcp_tools" -> appString(R.string.chat_context_sheet_038)
    "mcp_tools_deferred" -> appString(R.string.chat_context_sheet_037)
    "mcp_instructions" -> appString(R.string.chat_context_sheet_036)
    "custom_agents" -> appString(R.string.chat_context_sheet_001)
    "memory_files" -> appString(R.string.chat_context_sheet_035)
    "skills" -> appString(R.string.chat_chat_screen_003)
    "messages" -> appString(R.string.chat_context_sheet_034)
    "autocompact_buffer" -> appString(R.string.chat_context_sheet_033)
    "compact_buffer" -> appString(R.string.chat_context_sheet_032)
    "free_space" -> appString(R.string.chat_context_sheet_031)
    else -> localizedServerMessage(c.name)
}

private val Palette = listOf(
    Color(0xFF5B8DEF), Color(0xFFF0883E), Color(0xFF2EB67D), Color(0xFFB37FEB), Color(0xFFE5B83B),
    Color(0xFF3FB8C9), Color(0xFFE5677B), Color(0xFF8BC34A), Color(0xFF9E8CFF), Color(0xFFFF9E80),
)

@Composable
fun ContextSheet(sessionId: String, onDismiss: () -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val state = rememberLiveSessionDetail(sessionId, SessionDetailField.Context, 30_000, cached = { target ->
        if (repo.session(sessionId)?.provider == "codex") null
        else repo.api.contextDetail(sessionId, cached = true, expectedServer = target)
    }) { target -> repo.api.contextDetail(sessionId, expectedServer = target) }

    ModalBottomSheet(modifier = dev.remotecode.ui.common.hapticArea(), onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.chat_context), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = state::refresh, enabled = !state.loading) { Icon(Icons.Rounded.Refresh, stringResource(R.string.chat_refresh)) }
        }
        val d = state.detail
        val error = state.error
        val loading = state.loading
        if (loading && d != null) {
            Text(
                stringResource(R.string.chat_context_sheet_029),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, bottom = 4.dp),
            )
        }
        when {
            loading && d == null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            error != null && d == null -> Text(localizedServerMessage(error), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
            d != null -> ContextBody(d, header = { ContextControls(sessionId, onChanged = state::refresh, onCompacted = onDismiss) })
        }
    }
}

/** null はモデル既定の長さ。 */
private val WINDOWS: List<Long?> = listOf(null, 200_000L, 400_000L, 600_000L, 800_000L, 1_000_000L)

private fun windowLabel(w: Long?): String = when (w) {
    null -> appString(R.string.chat_context_sheet_028)
    1_000_000L -> appString(R.string.chat_context_sheet_027)
    else -> appString(R.string.chat_context_sheet_026, w / 10_000)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ContextControls(sessionId: String, onChanged: () -> Unit, onCompacted: () -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val sessions by repo.sessions.collectAsState()
    val session = sessions.firstOrNull { it.id == sessionId } ?: return
    val scope = rememberCoroutineScope()
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmCompact by remember { mutableStateOf(false) }
    var instructions by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
        if (session.provider == "claude") {
        Text(stringResource(R.string.chat_context_sheet_025), style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WINDOWS.forEach { w ->
                androidx.compose.material3.FilterChip(
                    selected = session.compactWindow == w,
                    enabled = !busy,
                    onClick = {
                        if (session.compactWindow == w) return@FilterChip
                        haptics.tick()
                        busy = true
                        message = null
                        scope.launch {
                            try {
                                repo.api.setCompactWindow(sessionId, w)
                                message = if (session.watching == true) appString(R.string.chat_context_sheet_024) else appString(R.string.chat_context_sheet_023)
                                onChanged()
                            } catch (e: Exception) {
                                message = e.message ?: appString(R.string.chat_context_sheet_022)
                            }
                            busy = false
                        }
                    },
                    label = { Text(windowLabel(w)) },
                )
            }
        }
        }
        message?.let { Text(localizedServerMessage(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        androidx.compose.material3.FilledTonalButton(
            onClick = { haptics.tick(); confirmCompact = true },
            enabled = !busy,
            shapes = androidx.compose.material3.ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Compress, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.chat_context_sheet_021))
        }
    }

    if (confirmCompact) {
        androidx.compose.material3.AlertDialog(
            modifier = dev.remotecode.ui.common.hapticArea(),
            onDismissRequest = { confirmCompact = false },
            title = { Text(stringResource(R.string.chat_context_sheet_020)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.chat_context_sheet_019))
                    androidx.compose.material3.OutlinedTextField(
                        value = instructions,
                        onValueChange = { instructions = it },
                        label = { Text(stringResource(R.string.chat_context_sheet_018)) },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    confirmCompact = false
                    haptics.confirm()
                    scope.launch {
                        try {
                            repo.api.compact(sessionId, instructions.trim())
                            instructions = ""
                            onCompacted()
                        } catch (e: Exception) {
                            message = e.message ?: appString(R.string.chat_context_sheet_017)
                        }
                    }
                }) { Text(stringResource(R.string.chat_context_sheet_016)) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmCompact = false }) { Text(stringResource(R.string.chat_cancel_short)) } },
        )
    }
}

@Composable
private fun ContextBody(d: ContextDetail, header: @Composable () -> Unit = {}) {
    val inWindow = d.categories.filter { it.kind != "deferred" && it.tokens > 0 }
    val deferred = d.categories.filter { it.kind == "deferred" && it.tokens > 0 }
    val colors = inWindow.mapIndexed { i, c ->
        c to when (c.kind) {
            "free" -> MaterialTheme.colorScheme.surfaceContainerHighest
            "buffer" -> MaterialTheme.colorScheme.outlineVariant
            else -> Palette[i % Palette.size]
        }
    }
    val window = d.maxTokens.coerceAtLeast(1)
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${d.percentage.toInt()}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.chat_context_sheet_015, tokensText(d.totalTokens), tokensText(d.maxTokens)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Text(
                d.model + if (d.isAutoCompactEnabled && d.autoCompactThreshold != null) stringResource(R.string.chat_context_sheet_014, tokensText(d.autoCompactThreshold)) else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (d.estimated) {
                Text(
                    stringResource(R.string.chat_context_sheet_013),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
                colors.forEach { (c, color) ->
                    val w = c.tokens.toFloat() / window
                    if (w > 0f) Box(Modifier.weight(w).fillMaxHeight().background(color))
                }
            }
            Spacer(Modifier.height(16.dp))
            header()
        }
        items(colors.size) { i ->
            val (c, color) = colors[i]
            TokenRow(categoryLabel(c), c.tokens, window, color)
        }
        if (deferred.isNotEmpty()) {
            item { SectionTitle(appString(R.string.chat_context_sheet_012)) }
            items(deferred.size) { i -> TokenRow(categoryLabel(deferred[i]), deferred[i].tokens, null, null) }
        }
        d.messages?.let { m ->
            item {
                Expandable(appString(R.string.chat_context_sheet_011), tokensText(m.toolCalls + m.toolResults + m.attachments + m.assistant + m.user)) {
                    TokenRow(appString(R.string.chat_context_sheet_010), m.user, window, null)
                    TokenRow(appString(R.string.chat_context_sheet_009), m.assistant, window, null)
                    TokenRow(appString(R.string.chat_context_sheet_008), m.toolCalls, window, null)
                    TokenRow(appString(R.string.chat_context_sheet_007), m.toolResults, window, null)
                    TokenRow(appString(R.string.chat_context_sheet_006), m.attachments, window, null)
                    if (m.byTool.isNotEmpty()) {
                        Text(stringResource(R.string.chat_context_sheet_005), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                        m.byTool.forEach { TokenRow(it.name, it.tokens, window, null) }
                    }
                }
            }
        }
        listSection(appString(R.string.chat_context_sheet_004), d.mcpServers) { appString(R.string.chat_context_sheet_003, it.name, it.tools) }
        if (d.memoryFiles.isNotEmpty()) {
            item {
                Expandable(appString(R.string.chat_context_sheet_002), tokensText(d.memoryFiles.sumOf { it.tokens })) {
                    d.memoryFiles.forEach { TokenRow("${it.path.substringAfterLast('\\').substringAfterLast('/')}（${it.type}）", it.tokens, window, null) }
                }
            }
        }
        listSection(appString(R.string.chat_chat_screen_003), d.skills) { it.name }
        listSection(appString(R.string.chat_context_sheet_001), d.agents) { it.name }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.listSection(title: String, list: List<NamedTokens>, label: (NamedTokens) -> String) {
    if (list.isEmpty()) return
    item {
        Expandable(title, tokensText(list.sumOf { it.tokens })) {
            list.forEach { TokenRow(label(it), it.tokens, null, null) }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Expandable(title: String, total: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Text(total, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.padding(start = 4.dp))
        }
        if (open) content()
    }
}

@Composable
private fun TokenRow(label: String, tokens: Long, window: Long?, color: Color?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (color != null) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(tokensText(tokens), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (window != null) {
            Text(
                "%.1f%%".format(tokens * 100.0 / window),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(52.dp).padding(start = 8.dp),
            )
        }
    }
}
