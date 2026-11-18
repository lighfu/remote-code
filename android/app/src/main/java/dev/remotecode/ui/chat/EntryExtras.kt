package dev.remotecode.ui.chat

import dev.remotecode.R
import dev.remotecode.i18n.appString
import dev.remotecode.i18n.localizedServerMessage
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.remotecode.data.Entry
import dev.remotecode.data.ToolWrapPreferences
import dev.remotecode.data.agentSections
import dev.remotecode.data.isActiveAgent
import dev.remotecode.ui.common.EntryMedia
import dev.remotecode.ui.theme.Extra
import dev.remotecode.ui.theme.ChatFont
import dev.remotecode.ui.theme.Mono

val EDIT_TOOLS = setOf("Edit", "MultiEdit", "Write", "NotebookEdit", "fileChange")
internal val COMMAND_TOOLS = setOf("Bash", "PowerShell", "command")

internal data class ToolCardInput(val heading: String?, val description: String?, val detail: String?)

internal fun toolCardInput(entry: Entry, showDetail: Boolean): ToolCardInput {
    val title = entry.title?.takeIf { it.isNotBlank() } ?: entry.tool
    val detail = if (showDetail && entry.tool !in EDIT_TOOLS) {
        entry.detail?.takeIf { it.isNotBlank() } ?: entry.title?.takeIf { entry.tool in COMMAND_TOOLS && it.isNotBlank() }
    } else null
    val heading = if (title != null && detail != null && title.previewsInput(detail)) {
        entry.tool?.takeUnless { it == detail }
    } else title
    val description = entry.text?.takeUnless { it.isBlank() || it == title || it == heading || it == detail }
    return ToolCardInput(heading, description, detail)
}

private fun String.previewsInput(input: String): Boolean {
    if (this == input) return true
    val prefix = when {
        endsWith('…') -> dropLast(1)
        endsWith("...") -> dropLast(3)
        else -> return false
    }
    return prefix.isNotBlank() && input.startsWith(prefix)
}

fun diffStats(diff: String?): Pair<Int, Int> {
    if (diff.isNullOrEmpty()) return 0 to 0
    val rows = parseDiff(diff)
    return rows.count { it.kind == '+' } to rows.count { it.kind == '-' }
}

private fun editStats(entry: Entry): Pair<Int, Int> {
    val detail = entry.detail ?: return 0 to 0
    if (entry.tool == "fileChange" && isLegacyFileSnapshot(entry.title, entry.files, detail)) return 0 to 0
    return diffStats(detail)
}

/** 最後の太字見出し、なければ最終段落の先頭文を使う。 */
fun thinkingHeadline(text: String): String {
    val bold = Regex("""\*\*(.+?)\*\*""").findAll(text).lastOrNull()?.groupValues?.get(1)
    if (!bold.isNullOrBlank()) return bold.trim()
    val para = text.trim().split(Regex("""\n\s*\n""")).lastOrNull { it.isNotBlank() }?.trim().orEmpty()
    val sentence = para.split(Regex("""(?<=[。．.!?！？])\s*""")).firstOrNull { it.isNotBlank() }.orEmpty()
    return sentence.replace(Regex("""\s+"""), " ").take(90)
}

@Composable
fun ThinkingView(e: Entry, mode: String) {
    val text = e.text.orEmpty().trim()
    if (text.isEmpty() && !e.streaming) return
    var expanded by rememberSaveable(e.id) { mutableStateOf<Boolean?>(null) }
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = ChatFont, fontStyle = FontStyle.Italic)
    val horizontalPadding = 6.dp
    val bodyIndent = 22.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val summaryByDefault = if (mode == "summary") {
            true
        } else if (text.isNotEmpty() && '\n' !in text && '\r' !in text && constraints.hasBoundedWidth) {
            val bodyWidth = with(LocalDensity.current) {
                constraints.maxWidth - 2 * horizontalPadding.roundToPx() - bodyIndent.roundToPx()
            }
            val measurer = rememberTextMeasurer()
            bodyWidth > 0 && !measurer.measure(
                text, style = bodyStyle, maxLines = 1, constraints = Constraints(maxWidth = bodyWidth),
            ).hasVisualOverflow
        } else {
            false
        }
        val open = expanded ?: !summaryByDefault
        val summary = summaryByDefault && !open
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable { expanded = !open }
                .animateContentSize()
                .padding(horizontal = horizontalPadding, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lightbulb, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.size(6.dp))
                val label = if (e.streaming) stringResource(R.string.chat_thinking) else stringResource(R.string.chat_thoughts)
                if (summary) {
                    Text(
                        thinkingHeadline(text).ifBlank { label } + if (e.streaming) " …" else "",
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = ChatFont),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!summary && text.isNotEmpty()) {
                ChatSelectionContainer {
                    Text(
                        text,
                        style = bodyStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (open) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = bodyIndent, top = 4.dp),
                    )
                }
            }
        }
    }
}

private fun fileParts(path: String): Pair<String, String> {
    val norm = path.replace('\\', '/')
    val name = norm.substringAfterLast('/')
    val dir = if (norm.contains('/')) norm.substringBeforeLast('/') else ""
    return name to dir
}

@Composable
fun DetailedToolCard(e: Entry, wrap: ToolWrapPreferences) {
    var open by rememberSaveable(e.id) { mutableStateOf(false) }
    val isEdit = e.tool in EDIT_TOOLS
    val isCommand = e.tool in COMMAND_TOOLS
    val isTodo = e.tool == "TodoWrite"
    val input = remember(e, open) { toolCardInput(e, showDetail = open || isTodo) }
    val files = rememberToolFiles(e)
    val multipleEdits = isEdit && files.size > 1
    val editPath = files.singleOrNull()
    val changedCount = if (multipleEdits) editFileCount(e, files).toString() else if (e.tool == "fileChange") changedFileSummaryCount(e.title) else null
    val x = Extra.colors
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().then(toolFileGesture(files) { open = !open }).animateContentSize(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(toolIcon(e.tool), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    if (isEdit && editPath != null) {
                        val (name, dir) = fileParts(editPath)
                        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (dir.isNotBlank()) Text(dir, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                    } else {
                        val heading = if (changedCount != null) stringResource(R.string.chat_chat_screen_050, changedCount)
                            else if (e.tool == "fileChange") localizedServerMessage(e.title.orEmpty()) else input.heading
                        if (heading != null) Text(
                            highlightedCode(heading, if (isCommand) commandCodeLanguage(e.tool, heading) else codeLanguage("plain.txt")),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                            softWrap = wrap.input,
                            modifier = if (wrap.input) Modifier else Modifier.horizontalScroll(rememberScrollState()),
                            maxLines = if (open) 8 else if (isCommand) 3 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (multipleEdits && !e.title.isNullOrBlank() && !isFileChangeSummary(e.title)) {
                            Text(e.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (input.description != null) {
                            Text(input.description, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (isEdit) {
                    val (add, del) = remember(e.detail, e.tool, e.title, e.files) { editStats(e) }
                    if (add + del > 0) {
                        Text("+$add", style = MaterialTheme.typography.labelMedium, color = x.diffAdd, fontFamily = Mono)
                        Spacer(Modifier.size(4.dp))
                        Text("−$del", style = MaterialTheme.typography.labelMedium, color = x.diffRemove, fontFamily = Mono)
                        Spacer(Modifier.size(8.dp))
                    }
                }
                StatusIcon(e.status)
            }
            when {
                multipleEdits || (isEdit && e.hasToolDetail) -> EditPreview(e, maxLines = if (open) Int.MAX_VALUE else 12, wrapLines = wrap.input, files = files)
                isTodo && input.detail != null -> Text(input.detail, style = MaterialTheme.typography.bodyMedium)
                isCommand -> {
                    if (input.detail != null) CodeBlock(input.detail, diff = false, language = commandCodeLanguage(e.tool, input.detail), wrapLines = wrap.input)
                    if (!e.output.isNullOrBlank()) CodeBlock(e.output, diff = false, maxLines = if (open) Int.MAX_VALUE else 6, tail = true, wrapLines = wrap.output)
                }
                open -> {
                    if (input.detail != null) CodeBlock(input.detail, diff = false, wrapLines = wrap.input)
                    if (!e.output.isNullOrBlank()) CodeBlock(e.output, diff = false, wrapLines = wrap.output)
                    if (e.detail.isNullOrBlank() && e.output.isNullOrBlank()) {
                        Text(stringResource(R.string.chat_entry_extras_013), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!e.media.isNullOrEmpty() || !e.videos.isNullOrEmpty()) EntryMedia(e.media, e.videos)
        }
    }
}

private fun agentStatusLabel(s: String?, streaming: Boolean = false) = when {
    s == "waiting" || s == "awaiting_input" || s == "awaiting_approval" -> appString(R.string.chat_waiting)
    isActiveAgent(s, streaming) -> appString(R.string.chat_working)
    s == "error" || s == "failed" -> appString(R.string.chat_failed)
    s in setOf("declined", "stopped", "cancelled", "canceled", "interrupted") -> appString(R.string.chat_entry_extras_012)
    else -> appString(R.string.chat_completed)
}

@Composable
fun AgentCard(e: Entry, children: List<Entry>, display: Display, respond: Responder, allChildren: Map<String, List<Entry>>) {
    var open by rememberSaveable(e.id) { mutableStateOf(false) }
    var shownChildren by rememberSaveable(e.id) { mutableIntStateOf(40) }
    val running = isActiveAgent(e.status, e.streaming)
    val tools = children.filter { it.kind == "tool" }
    Card(
        onClick = { open = !open },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(MaterialShapes.Pentagon.toShape()).background(MaterialTheme.colorScheme.secondary),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.SmartToy, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondary) }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.chat_entry_extras_011, agentStatusLabel(e.status, e.streaming)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(e.title ?: stringResource(R.string.chat_subagent), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    e.text?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                StatusIcon(if (running) "running" else e.status)
            }
            e.progress?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = ChatFont, fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (open) 12 else 2, overflow = TextOverflow.Ellipsis)
            }
            if (!open && tools.isNotEmpty()) {
                Text(
                    stringResource(R.string.chat_entry_extras_010, tools.size) + tools.takeLast(3).joinToString(" › ") { (it.tool ?: "") + " " + (it.title ?: "").take(30) },
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (open) {
                if (!e.detail.isNullOrBlank()) {
                    Text(stringResource(R.string.chat_entry_extras_009), style = MaterialTheme.typography.labelMedium)
                    CodeBlock(e.detail, diff = false, maxHeight = 200, wrapLines = display.toolWrap.input)
                }
                if (children.isEmpty()) {
                    Text(if (running) stringResource(R.string.chat_entry_extras_008) else stringResource(R.string.chat_entry_extras_007), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.padding(start = 4.dp)) {
                    if (children.size > shownChildren) {
                        TextButton(onClick = { shownChildren += 40 }) {
                            Text(stringResource(R.string.chat_entry_extras_006, children.size - shownChildren))
                        }
                    }
                    children.takeLast(shownChildren).forEach { c ->
                        key(c.id) { EntryView(c, respond, display = display, children = allChildren) }
                    }
                }
                if (!e.output.isNullOrBlank()) {
                    Text(stringResource(R.string.chat_entry_extras_005), style = MaterialTheme.typography.labelMedium)
                    CodeBlock(e.output, diff = false, maxHeight = 260, wrapLines = display.toolWrap.output)
                }
            }
        }
    }
}

@Composable
fun AgentsSheet(agents: List<Entry>, children: Map<String, List<Entry>>, display: Display, respond: Responder, onDismiss: () -> Unit) {
    val sections = remember(agents) { agentSections(agents) }
    ModalBottomSheet(modifier = dev.remotecode.ui.common.hapticArea(), onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(stringResource(R.string.chat_chat_screen_051, agents.size), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        if (agents.isEmpty()) {
            Text(stringResource(R.string.chat_entry_extras_004), modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.heightIn(max = 640.dp)) {
            item(key = "__agents_active") {
                Text(stringResource(R.string.chat_entry_extras_003, sections.active.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { heading() })
            }
            items(sections.active, key = { it.id }) { a ->
                EntryView(a, respond, display = display, children = children)
            }
            item(key = "__agents_completed") {
                Text(stringResource(R.string.chat_entry_extras_002, sections.completed.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { heading() })
            }
            items(sections.completed, key = { it.id }) { a ->
                EntryView(a, respond, display = display, children = children)
            }
        }
    }
}

@Composable
fun ChangedFilesSheet(edits: List<Entry>, wrap: ToolWrapPreferences, onDismiss: () -> Unit) {
    val x = Extra.colors
    ModalBottomSheet(modifier = dev.remotecode.ui.common.hapticArea(), onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val total = remember(edits) { edits.fold(0 to 0) { acc, e -> editStats(e).let { (a, d) -> acc.first + a to acc.second + d } } }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.chat_chat_screen_050, remember(edits) { changedFileCount(edits) }), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Text("+${total.first}", color = x.diffAdd, fontFamily = Mono)
            Spacer(Modifier.size(6.dp))
            Text("−${total.second}", color = x.diffRemove, fontFamily = Mono)
        }
        if (edits.isEmpty()) {
            Text(stringResource(R.string.chat_entry_extras_001), modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.heightIn(max = 640.dp)) {
            items(edits.asReversed(), key = { it.id }) { e ->
                Box(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) { DetailedToolCard(e, wrap) }
            }
        }
        Surface(Modifier.padding(8.dp)) { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_close)) } }
    }
}
