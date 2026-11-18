package dev.remotecode.ui.chat

import dev.remotecode.R
import dev.remotecode.i18n.appString
import dev.remotecode.i18n.localizedApprovalLabel
import dev.remotecode.i18n.localizedServerMessage
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.remotecode.data.ApprovalOption
import dev.remotecode.data.Entry
import dev.remotecode.data.ToolWrapPreferences
import dev.remotecode.data.crossSessionMessage
import dev.remotecode.data.attachedFileText
import dev.remotecode.data.Question
import dev.remotecode.ui.theme.Extra
import dev.remotecode.ui.theme.CodeScale
import dev.remotecode.ui.theme.ChatFont
import dev.remotecode.ui.theme.Mono

/** choice と answers はどちらか一方を渡す。 */
typealias Responder = (requestId: String, choice: String?, answers: Map<String, List<String>>?) -> Unit

data class Display(
    val thinking: String = "full",
    val toolDetail: Boolean = true,
    val toolWrap: ToolWrapPreferences = ToolWrapPreferences(),
)

/** null なら巻き戻し不可。 */
val LocalRewind = androidx.compose.runtime.staticCompositionLocalOf<((Entry) -> Unit)?> { null }

val LocalDeliverNow = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/** 最後の未回答の箱だけ返信できる。 */
class NextStep(val activeId: String?, val reply: (String) -> Unit)

val LocalNextStep = androidx.compose.runtime.compositionLocalOf<NextStep?> { null }

@Composable
fun EntryView(
    e: Entry,
    respond: Responder,
    modifier: Modifier = Modifier,
    display: Display = Display(),
    children: Map<String, List<Entry>> = emptyMap(),
) {
    if (!e.isVisibleInChat(display.thinking)) return
    Box(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        when (e.kind) {
            "user" -> {
                val cross = e.crossSessionMessage()
                if (cross != null) CrossSessionCard(cross) else UserBubble(e)
            }
            "assistant" -> AssistantText(e)
            "skill" -> SkillContentCard(e)
            "thinking" -> ThinkingView(e, display.thinking)
            "tool" -> when {
                e.tool == "wrap_up" -> NextStepCard(e)
                e.tool == "Widget" && !e.html.isNullOrBlank() -> WidgetCard(e)
                e.tool == "Artifact" -> ArtifactCard(e)
                e.tool == "Agent" -> AgentCard(e, children[e.id].orEmpty(), display, respond, children)
                display.toolDetail -> DetailedToolCard(e, display.toolWrap)
                else -> ToolCard(e, display.toolWrap)
            }
            "approval" -> ApprovalCard(e, respond, wrapInput = display.toolWrap.input)
            "question" -> QuestionCard(e, respond)
            "result" -> ResultChip(e)
            else -> SystemLine(e)
        }
    }
}

@Composable
private fun SkillContentCard(e: Entry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Extension, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(stringResource(R.string.chat_skill_loaded), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    e.title?.takeIf { it.isNotBlank() }?.let { title ->
                        Text(title, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            MarkdownText(e.text.orEmpty())
            if (!e.media.isNullOrEmpty() || !e.videos.isNullOrEmpty()) {
                dev.remotecode.ui.common.EntryMedia(e.media, e.videos)
            }
        }
    }
}

@Composable
private fun UserBubble(e: Entry) {
    val content = remember(e.text) { attachedFileText(e.text.orEmpty()) }
    val uriHandler = LocalUriHandler.current
    val queued = e.status == "queued"
    val rewind = if (queued) null else LocalRewind.current
    val deliverNow = if (queued) LocalDeliverNow.current else null
    var actions by remember { mutableStateOf(false) }
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 8.dp),
            modifier = Modifier.widthIn(max = 330.dp)
                .then(if (queued) Modifier.alpha(0.6f) else Modifier)
                .then(if (rewind != null || deliverNow != null) Modifier.clickable { haptics.context(); actions = !actions } else Modifier),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!e.title.isNullOrBlank()) {
                    Text(localizedServerMessage(e.title), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                }
                if (!e.media.isNullOrEmpty() || !e.videos.isNullOrEmpty()) {
                    dev.remotecode.ui.common.EntryMedia(e.media, e.videos)
                } else if ((e.images ?: 0) > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Image, null, Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.chat_entry_views_032, e.images ?: 0), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (content.text.isNotBlank()) {
                    ChatSelectionContainer { Text(content.text, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = ChatFont)) }
                }
                content.files.forEach { file ->
                    Surface(
                        onClick = { uriHandler.openUri("file://" + (if (file.path.startsWith('/')) "" else "/") + android.net.Uri.encode(file.path, "/:")) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Description, null, Modifier.size(22.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when {
                                        file.size >= 1_048_576 -> "%.1f MB".format(file.size / 1_048_576.0)
                                        file.size >= 1024 -> "%.1f KB".format(file.size / 1024.0)
                                        else -> "${file.size} B"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (actions && rewind != null) {
            androidx.compose.material3.AssistChip(
                onClick = { actions = false; rewind(e) },
                label = { Text(stringResource(R.string.chat_entry_views_031)) },
                leadingIcon = { Icon(Icons.Rounded.History, null, Modifier.size(18.dp)) },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (actions && deliverNow != null) {
            androidx.compose.material3.AssistChip(
                onClick = { actions = false; haptics.reject(); deliverNow() },
                label = { Text(stringResource(R.string.chat_entry_views_030)) },
                leadingIcon = { Icon(Icons.Rounded.Bolt, null, Modifier.size(18.dp)) },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun AssistantText(e: Entry) {
    val text = e.text.orEmpty()
    if (text.isBlank() && !e.streaming && e.media.isNullOrEmpty() && e.videos.isNullOrEmpty()) return
    val t = MaterialTheme.typography
    val md = remember(text, e.streaming, e.media) {
        // 画像を別表示するため、本文から重複する画像記法を除く。
        val body = if (e.media.isNullOrEmpty()) text.replace(Regex("""!\[[^\]]*]\((?!\s*<?https?://)[^)]*\)"""), "")
            else text.replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "")
        keepLineBreaks(if (e.streaming) "$body ▍" else body)
    }
    val state = rememberMarkdownState(content = md, retainState = true)
    if (!e.media.isNullOrEmpty() || !e.videos.isNullOrEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MarkdownBody(state)
            dev.remotecode.ui.common.EntryMedia(e.media, e.videos, Modifier.padding(horizontal = 4.dp))
        }
        return
    }
    MarkdownBody(state)
}

@Composable
fun MarkdownText(text: String) {
    val md = remember(text) { keepLineBreaks(text) }
    MarkdownBody(rememberMarkdownState(content = md, retainState = true))
}

@Composable
private fun MarkdownBody(state: com.mikepenz.markdown.model.MarkdownState) {
    val t = MaterialTheme.typography
    ChatSelectionContainer {
        Markdown(
            markdownState = state,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).animateContentSize(),
            typography = markdownTypography(
                h1 = t.titleLarge.copy(fontFamily = ChatFont),
                h2 = t.titleMedium.copy(fontFamily = ChatFont),
                h3 = t.titleSmall.copy(fontFamily = ChatFont),
                h4 = t.titleSmall.copy(fontFamily = ChatFont),
                h5 = t.titleSmall.copy(fontFamily = ChatFont),
                h6 = t.titleSmall.copy(fontFamily = ChatFont),
                text = t.bodyLarge.copy(fontFamily = ChatFont),
                paragraph = t.bodyLarge.copy(fontFamily = ChatFont),
                code = t.bodySmall.copy(fontFamily = Mono),
            ),
            components = com.mikepenz.markdown.compose.components.markdownComponents(
                table = { model -> WrappingMarkdownTable(model.content, model.node, t.bodyMedium.copy(fontFamily = ChatFont)) },
            ),
        )
    }
}

/** コードブロック以外の改行を Markdown の強制改行に変換する。 */
private fun keepLineBreaks(text: String): String {
    var inFence = false
    return text.lineSequence().joinToString("\n") { line ->
        if (line.trimStart().startsWith("```")) {
            inFence = !inFence
            line
        } else if (!inFence && line.isNotBlank() && !line.endsWith("  ")) {
            "$line  "
        } else {
            line
        }
    }
}

@Composable
private fun Thinking(e: Entry) {
    var open by rememberSaveable(e.id) { mutableStateOf(false) }
    val text = e.text.orEmpty().trim()
    if (text.isEmpty() && !e.streaming) return
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .animateContentSize()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lightbulb, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(6.dp))
            Text(
                if (e.streaming) stringResource(R.string.chat_thinking) else stringResource(R.string.chat_thoughts),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (text.isNotEmpty()) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = ChatFont, fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (open) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 22.dp, top = 2.dp),
            )
        }
    }
}

fun toolIcon(tool: String?): ImageVector = when (tool) {
    "Bash", "PowerShell", "command" -> Icons.Rounded.Terminal
    "Read" -> Icons.Rounded.Description
    "Write", "Edit", "MultiEdit", "NotebookEdit", "fileChange" -> Icons.Rounded.EditNote
    "Glob", "Grep" -> Icons.Rounded.Search
    "WebFetch", "WebSearch" -> Icons.Rounded.Language
    "Agent", "AgentControl" -> Icons.Rounded.SmartToy
    "TodoWrite" -> Icons.Rounded.Checklist
    "mcp" -> Icons.Rounded.Extension
    "Skill" -> Icons.Rounded.Bolt
    "permissions" -> Icons.Rounded.Shield
    else -> Icons.Rounded.Build
}

@Composable
internal fun StatusIcon(status: String?) {
    val x = Extra.colors
    when (status) {
        "running" -> LoadingIndicator(Modifier.size(26.dp))
        "error" -> Icon(Icons.Rounded.Error, stringResource(R.string.chat_failed), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
        "declined" -> Icon(Icons.Rounded.Block, stringResource(R.string.chat_entry_views_029), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
        else -> Icon(Icons.Rounded.CheckCircle, stringResource(R.string.chat_completed), Modifier.size(20.dp), tint = x.success)
    }
}

@Composable
private fun ToolCard(e: Entry, wrap: ToolWrapPreferences) {
    var open by rememberSaveable(e.id) { mutableStateOf(false) }
    val files = rememberToolFiles(e)
    val multipleEdits = e.tool in EDIT_TOOLS && files.size > 1
    val title = if (multipleEdits) stringResource(R.string.chat_chat_screen_050, editFileCount(e, files)) else e.title ?: e.tool ?: ""
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().then(toolFileGesture(files) { open = !open }).animateContentSize(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(toolIcon(e.tool), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    highlightedCode(title, if (e.tool in setOf("Bash", "PowerShell", "command")) commandCodeLanguage(e.tool, e.title.orEmpty()) else codeLanguage("plain.txt")),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    softWrap = wrap.input,
                    modifier = if (wrap.input) Modifier else Modifier.horizontalScroll(rememberScrollState()),
                    maxLines = if (open) 6 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (multipleEdits && !e.title.isNullOrBlank() && !isFileChangeSummary(e.title)) {
                    Text(e.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!e.text.isNullOrBlank()) {
                    Text(e.text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.size(8.dp))
            StatusIcon(e.status)
        }
        if (!e.media.isNullOrEmpty() || !e.videos.isNullOrEmpty()) {
            dev.remotecode.ui.common.EntryMedia(e.media, e.videos, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp))
        }
        if (open || multipleEdits) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (multipleEdits || (e.tool in EDIT_TOOLS && e.hasToolDetail)) {
                    EditPreview(e, maxLines = if (open) Int.MAX_VALUE else 12, wrapLines = wrap.input, files = files)
                } else if (!e.detail.isNullOrBlank()) {
                    CodeBlock(e.detail, diff = false, language = if (e.tool in setOf("Bash", "PowerShell", "command")) commandCodeLanguage(e.tool, e.detail) else null, wrapLines = wrap.input)
                }
                if (open && !e.output.isNullOrBlank()) {
                    Text(stringResource(R.string.chat_entry_views_028), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CodeBlock(e.output, diff = false, wrapLines = wrap.output)
                }
                if (open && !e.hasToolDetail && e.output.isNullOrBlank()) {
                    Text(stringResource(R.string.chat_entry_extras_013), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun CodeBlock(text: String, diff: Boolean, maxHeight: Int = 360, maxLines: Int = Int.MAX_VALUE, tail: Boolean = false, language: CodeLanguage? = null, wrapLines: Boolean = false) {
    if (diff) { DiffBlock(text, maxHeight, maxLines, wrapLines = wrapLines); return }
    val colored = highlightedCode(text, language)
    val shownText = remember(colored, maxLines, tail) {
        val lines = colored.text.lines()
        when {
            lines.size <= maxLines -> colored
            tail -> {
                val start = lines.take(lines.size - maxLines).sumOf { it.length + 1 }
                buildAnnotatedString { append("…\n"); append(colored.subSequence(start, colored.length)) }
            }
            else -> {
                val end = lines.take(maxLines).sumOf { it.length + 1 }.minus(1).coerceAtLeast(0)
                buildAnnotatedString { append(colored.subSequence(0, end)); append("\n…") }
            }
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ChatSelectionContainer {
            Text(
                shownText,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 12.sp * CodeScale, lineHeight = 17.sp * CodeScale),
                softWrap = wrapLines,
                modifier = Modifier
                    .heightIn(max = maxHeight.dp)
                    .verticalScroll(rememberScrollState())
                    .then(if (wrapLines) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                    .padding(10.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApprovalCard(e: Entry, respond: Responder, wrapInput: Boolean) {
    val pending = e.status == "pending"
    var sent by remember(e.id) { mutableStateOf(false) }
    val container = if (pending) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val onContainer = if (pending) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (pending) Icons.Rounded.Shield else toolIcon(e.tool), null, Modifier.size(22.dp))
                Spacer(Modifier.size(10.dp))
                Text(
                    e.text?.lineSequence()?.firstOrNull() ?: stringResource(R.string.chat_entry_views_027),
                    style = if (pending) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            e.text?.lineSequence()?.drop(1)?.joinToString("\n")?.takeIf { it.isNotBlank() && pending }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (!e.title.isNullOrBlank()) {
                Text(
                    e.title, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    maxLines = if (pending) 8 else 1, overflow = TextOverflow.Ellipsis,
                    softWrap = wrapInput,
                    modifier = if (wrapInput) Modifier else Modifier.horizontalScroll(rememberScrollState()),
                )
            }
            if (pending && e.tool in EDIT_TOOLS) {
                EditPreview(e, wrapLines = wrapInput)
            } else if (pending && !e.detail.isNullOrBlank()) {
                CodeBlock(e.detail, diff = false, maxHeight = 280, wrapLines = wrapInput)
            }
            if (pending) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    e.options.orEmpty().forEach { o ->
                        OptionButton(o, enabled = !sent) {
                            sent = true
                            respond(e.requestId ?: "", o.id, null)
                        }
                    }
                }
            } else {
                Text(
                    when (e.status) {
                        "cancelled" -> stringResource(R.string.chat_entry_views_003)
                        else -> stringResource(R.string.chat_entry_views_026, e.choice ?: appString(R.string.chat_entry_views_025))
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** デスクトップ専用操作は同じ依頼文に変換する。 */
private fun appActions() = mapOf(
    "commit" to (appString(R.string.chat_entry_views_024) to appString(R.string.chat_entry_views_023)),
    "create_pr" to (appString(R.string.chat_entry_views_022) to appString(R.string.chat_entry_views_021)),
    "merge" to (appString(R.string.chat_entry_views_020) to appString(R.string.chat_entry_views_019)),
    "fix_ci" to (appString(R.string.chat_entry_views_018) to appString(R.string.chat_entry_views_017)),
    "address_comments" to (appString(R.string.chat_entry_views_016) to appString(R.string.chat_entry_views_015)),
    "resolve_conflicts" to (appString(R.string.chat_entry_views_014) to appString(R.string.chat_entry_views_013)),
    "verify" to (appString(R.string.chat_entry_views_012) to appString(R.string.chat_entry_views_011)),
    "archive" to (appString(R.string.chat_entry_views_010) to appString(R.string.chat_entry_views_009)),
    "new_session" to (appString(R.string.chat_entry_views_008) to appString(R.string.chat_entry_views_007)),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NextStepCard(e: Entry) {
    val next = LocalNextStep.current
    val ask = e.title?.takeIf { it.isNotBlank() }
    val recap = e.text?.takeIf { it.isNotBlank() }
    if (ask == null && recap == null) return
    val active = next != null && next.activeId == e.id && ask != null
    var sent by remember(e.id) { mutableStateOf(false) }
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            recap?.let {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.TaskAlt, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(10.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (ask != null) {
                Text(ask, style = if (active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
            }
            val options = e.options.orEmpty()
            if (active && options.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    options.forEachIndexed { k, o ->
                        val app = appActions()[o.id]
                        val label = app?.first ?: o.label.ifBlank { o.id }
                        val message = app?.second ?: o.label.ifBlank { o.id }
                        val send = { sent = true; next?.reply?.invoke(message); Unit }
                        if (k == 0) {
                            Button(onClick = haptics.confirm(send), enabled = !sent, shapes = ButtonDefaults.shapes()) { Text(label) }
                        } else {
                            OutlinedButton(onClick = haptics.tick(send), enabled = !sent, shapes = ButtonDefaults.shapes()) { Text(label) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionButton(o: ApprovalOption, enabled: Boolean, onClickRaw: () -> Unit) {
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    val onClick: () -> Unit = when (o.kind) {
        "allow", "allowAlways" -> haptics.confirm(onClickRaw)
        "deny" -> haptics.reject(onClickRaw)
        else -> haptics.tick(onClickRaw)
    }
    when (o.kind) {
        "allow" -> Button(onClick = onClick, enabled = enabled, shapes = ButtonDefaults.shapes()) { Text(localizedApprovalLabel(o)) }
        "allowAlways" -> FilledTonalButton(onClick = onClick, enabled = enabled, shapes = ButtonDefaults.shapes()) { Text(localizedApprovalLabel(o)) }
        "deny" -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(localizedApprovalLabel(o)) }
        else -> TextButton(onClick = onClick, enabled = enabled, shapes = ButtonDefaults.shapes()) { Text(localizedApprovalLabel(o)) }
    }
}

@Composable
private fun QuestionCard(e: Entry, respond: Responder) {
    val pending = e.status == "pending"
    val questions = e.questions.orEmpty()
    val picked = remember(e.id) { mutableStateMapOf<String, List<String>>() }
    val other = remember(e.id) { mutableStateMapOf<String, String>() }
    var sent by remember(e.id) { mutableStateOf(false) }
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (pending) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.HelpOutline, null, Modifier.size(22.dp))
                Spacer(Modifier.size(10.dp))
                Text(if (pending) stringResource(R.string.chat_entry_views_006) else stringResource(R.string.chat_entry_views_005), style = MaterialTheme.typography.titleMedium)
            }
            questions.forEach { q -> QuestionBlock(q, pending, picked, other, e.answers?.get(q.id)) }
            if (pending) {
                Button(
                    onClick = {
                        haptics.confirm()
                        sent = true
                        val answers = questions.associate { q ->
                            val o = other[q.id]?.trim().orEmpty()
                            q.id to (picked[q.id].orEmpty() + listOfNotNull(o.ifBlank { null }))
                        }
                        respond(e.requestId ?: "", null, answers)
                    },
                    enabled = !sent && questions.all { q -> picked[q.id].orEmpty().isNotEmpty() || !other[q.id].isNullOrBlank() },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.chat_entry_views_004)) }
            } else if (e.status == "cancelled") {
                Text(stringResource(R.string.chat_entry_views_003), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun QuestionBlock(
    q: Question,
    pending: Boolean,
    picked: MutableMap<String, List<String>>,
    other: MutableMap<String, String>,
    answered: List<String>?,
) {
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (q.header.isNotBlank()) Text(q.header, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(q.question, style = MaterialTheme.typography.bodyLarge)
        if (!pending) {
            Text("→ ${answered?.joinToString(", ") ?: appString(R.string.chat_entry_views_002)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        q.options.forEach { o ->
            val sel = picked[q.id].orEmpty()
            val checked = o.label in sel
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.tick()
                        picked[q.id] = if (q.multiSelect) (if (checked) sel - o.label else sel + o.label) else listOf(o.label)
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (q.multiSelect) Checkbox(checked = checked, onCheckedChange = null) else RadioButton(selected = checked, onClick = null)
                Spacer(Modifier.size(10.dp))
                Column {
                    Text(o.label, style = MaterialTheme.typography.bodyLarge)
                    if (o.description.isNotBlank()) {
                        Text(o.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (q.allowOther) {
            OutlinedTextField(
                value = other[q.id].orEmpty(),
                onValueChange = { other[q.id] = it },
                label = { Text(stringResource(R.string.chat_entry_views_001)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ResultChip(e: Entry) {
    var open by remember(e.id) { mutableStateOf(false) }
    val ok = e.ok == true
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = { if (!e.detail.isNullOrBlank()) open = !open },
            shape = RoundedCornerShape(50),
            color = if (ok) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.errorContainer,
            contentColor = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(e.text.orEmpty(), style = MaterialTheme.typography.labelMedium)
            }
        }
        if (open && !e.detail.isNullOrBlank()) {
            Spacer(Modifier.size(6.dp))
            CodeBlock(e.detail, diff = false, maxHeight = 240)
        }
    }
}

@Composable
private fun SystemLine(e: Entry) {
    val err = e.level == "error"
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (err) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (err) Icons.Rounded.ErrorOutline else Icons.Rounded.Info,
            null,
            Modifier.size(16.dp),
            tint = if (err) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            e.text.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = if (err) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
