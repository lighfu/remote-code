package dev.remotecode.ui.chat

import dev.remotecode.R
import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.LinkFileInfo
import dev.remotecode.data.CodeQuote
import dev.remotecode.data.ConnectionIdentity
import dev.remotecode.data.connectionIdentity
import dev.remotecode.data.codeQuoteFromLines
import dev.remotecode.data.isProjectMarkdown
import dev.remotecode.ui.files.ProjectMarkdownPreview
import dev.remotecode.ui.theme.CodeScale
import dev.remotecode.ui.theme.Extra
import dev.remotecode.ui.theme.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_LINE_CHARS = 2000

val LocalProjectEditor = staticCompositionLocalOf<((String, String) -> Unit)?> { null }

/** 引用は元の接続先に固定する。 */
internal val LocalCodeQuote = staticCompositionLocalOf<((String, ConnectionIdentity, CodeQuote) -> Unit)?> { null }

/** 保存時は省略前の原本を取得する。 */
@Composable
internal fun CodeViewerContent(info: LinkFileInfo, link: String, sessionId: String, onDismiss: () -> Unit, onEdit: (() -> Unit)? = null, source: ConnectionIdentity? = null) {
    val repo = RemoteCodeApp.instance.repository
    val settings by repo.settings.collectAsState()
    val wrapLines = settings?.codeWrap ?: true
    val language = remember(info.path) { codeLanguage(info.path) }
    var lines by remember(info) { mutableStateOf<List<CodeLine>?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var saving by remember { mutableStateOf(false) }
    val quoteHandler = LocalCodeQuote.current
    var selecting by remember(info) { mutableStateOf(false) }
    var anchor by remember(info) { mutableStateOf<Int?>(null) }
    var selected by remember(info) { mutableStateOf<IntRange?>(null) }
    var quoteDialog by remember(info) { mutableStateOf(false) }
    var quoteError by remember(info) { mutableStateOf<String?>(null) }
    var markdownPreview by remember(info) { mutableStateOf(false) }
    val sourceServer = remember(info, source) { RemoteCodeApp.instance.repository.api.server?.takeIf { it.connectionIdentity() == source } }
    val changes = rememberFileChanges(info, sessionId, sourceServer)
    var showDiff by remember(info, sessionId, source) { mutableStateOf<Pair<FileChanges, Boolean>?>(null) }
    val canQuote = source != null && quoteHandler != null && info.text?.isNotEmpty() == true
    fun selectLine(line: Int) {
        selecting = true; quoteError = null
        val from = anchor
        selected = if (from == null) line..line else minOf(from, line)..maxOf(from, line)
        anchor = if (from == null) line else null
    }
    fun quote(value: CodeQuote) {
        val shownText = info.text.orEmpty()
        if (info.truncated && !shownText.endsWith('\n') && !shownText.endsWith('\r')) {
            require(value.endLine < sourceLines(shownText).size) { appString(R.string.chat_code_viewer_026) }
        }
        val expected = source ?: error(appString(R.string.chat_code_viewer_025))
        if (RemoteCodeApp.instance.repository.api.server?.connectionIdentity() != expected) error(appString(R.string.chat_code_viewer_024))
        val handler = quoteHandler ?: error(appString(R.string.chat_code_viewer_023))
        handler(sessionId, expected, value)
        onDismiss()
    }
    LaunchedEffect(info) {
        lines = withContext(Dispatchers.Default) { highlightCode(info.text.orEmpty(), language) }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.chat_close)) }
                Column(Modifier.weight(1f)) {
                    Text(info.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${language.displayLabel} · ${lines?.size?.let { stringResource(R.string.chat_code_viewer_022, it, if (info.truncated) appString(R.string.chat_code_viewer_021) else "") } ?: stringResource(R.string.chat_loading)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isProjectMarkdown(info.name) && sourceServer != null) IconButton(onClick = { markdownPreview = !markdownPreview; selecting = false; selected = null; anchor = null }) {
                    Icon(Icons.Rounded.Visibility, if (markdownPreview) stringResource(R.string.chat_code_viewer_020) else stringResource(R.string.chat_code_viewer_019), tint = if (markdownPreview) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                }
                if (!markdownPreview) IconToggleButton(checked = wrapLines, onCheckedChange = { on -> scope.launch { repo.settingsStore.setCodeWrap(on) } }) {
                    Icon(Icons.AutoMirrored.Rounded.WrapText, stringResource(R.string.chat_code_viewer_018, if (wrapLines) "ON" else "OFF"), tint = if (wrapLines) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                }
                if (canQuote) IconButton(onClick = { selecting = !selecting; markdownPreview = false; anchor = null; selected = null; quoteError = null }) {
                    Icon(Icons.Rounded.FormatQuote, stringResource(R.string.chat_code_viewer_017), tint = if (selecting) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                }
                if (onEdit != null) IconButton(onClick = onEdit, enabled = !saving) { Icon(Icons.Rounded.Edit, stringResource(R.string.chat_code_viewer_016)) }
                IconButton(enabled = !saving, onClick = {
                    saving = true
                    scope.launch {
                        val result = runCatching {
                            val bytes = RemoteCodeApp.instance.repository.api.fileRaw(link, sessionId)
                            saveToDownloads(info.name, info.mime, bytes)
                        }
                        saving = false
                        Toast.makeText(context, result.fold({ appString(R.string.chat_code_viewer_015, info.name) }, { appString(R.string.chat_code_viewer_014, it.message) }), Toast.LENGTH_LONG).show()
                    }
                }) {
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Download, stringResource(R.string.chat_code_viewer_013))
                }
            }
            Text(info.path, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (changes != null) FileChangeButtons(changes) { staged -> showDiff = changes to staged }
            if (info.truncated) Text(stringResource(R.string.chat_code_viewer_012), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            if (lines?.any { it.text.length > MAX_LINE_CHARS } == true) Text(stringResource(R.string.chat_code_viewer_011, MAX_LINE_CHARS), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
            if (selecting) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(if (selected == null) stringResource(R.string.chat_code_viewer_010) else stringResource(R.string.chat_code_viewer_009, selected!!.first, selected!!.last, if (anchor != null) appString(R.string.chat_code_viewer_008) else ""), style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { quoteDialog = true }) { Text(stringResource(R.string.chat_code_viewer_007)) }
                            TextButton(enabled = selected != null, onClick = {
                                try {
                                    val range = selected ?: return@TextButton
                                    quote(codeQuoteFromLines(info.path, info.text.orEmpty(), range.first, range.last))
                                } catch (error: Exception) { quoteError = error.message ?: appString(R.string.chat_code_quote_range_dialog_002) }
                            }) { Icon(Icons.Rounded.FormatQuote, null, Modifier.size(18.dp)); Text(stringResource(R.string.chat_code_quote_range_dialog_001)) }
                        }
                        quoteError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            } else if (canQuote) {
                Text(stringResource(R.string.chat_code_viewer_006), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            val loaded = lines
            if (markdownPreview) ProjectMarkdownPreview(info.text.orEmpty(), info.path, sessionId, Modifier.weight(1f), expectedServer = sourceServer)
            else if (loaded == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            else if (loaded.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.chat_code_viewer_005)) }
            else SourceCode(loaded, info.line, Modifier.weight(1f), selecting, selected, if (canQuote) ::selectLine else null, wrapLines, changes)
        }
    }
    showDiff?.let { (origin, staged) -> GitDiffDialog(sessionId, origin.file.path, staged, source, origin.cwd) { showDiff = null } }
    if (quoteDialog && canQuote) CodeQuoteRangeDialog(
        info.path, info.text.orEmpty(), initialStartLine = selected?.first ?: info.line ?: 1,
        initialEndLine = selected?.last ?: info.line ?: 1, onDismiss = { quoteDialog = false }, onQuote = ::quote,
    )
}

@Composable
private fun SourceCode(lines: List<CodeLine>, target: Int?, modifier: Modifier = Modifier, selecting: Boolean = false, selected: IntRange? = null, onSelectLine: ((Int) -> Unit)? = null, wrapLines: Boolean = true, changes: FileChanges? = null) {
    val list = rememberLazyListState()
    val horizontal = rememberScrollState()
    var renderedWrap by remember { mutableStateOf(wrapLines) }
    val wrapAnchor = remember(wrapLines) { list.firstVisibleItemIndex }
    LaunchedEffect(lines, target) { if (target != null) list.scrollToItem((target - 4).coerceIn(0, lines.lastIndex)) }
    LaunchedEffect(wrapLines) {
        if (renderedWrap != wrapLines) {
            list.scrollToItem(wrapAnchor.coerceIn(0, lines.lastIndex))
            renderedWrap = wrapLines
        }
    }
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 13.sp * CodeScale, lineHeight = 20.sp * CodeScale)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charWidth = with(density) { measurer.measure("M", style).size.width.toDp() }
    val longest = remember(lines, wrapLines) {
        if (wrapLines) 0 else lines.maxOf { line ->
            (0 until minOf(line.text.length, MAX_LINE_CHARS)).sumOf { index ->
                when { line.text[index] == '\t' -> 4; line.text[index] >= '\u2e80' -> 2; else -> 1 }
            }
        }
    }
    val gutter = charWidth * (lines.size.toString().length + if (changes == null) 2 else 4) + 12.dp
    val palette = Extra.colors
    fun changeAt(index: Int) = if (changes?.file?.untracked == true) SourceChange.Added else changes?.markers?.get(index + 1)
    Row(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val maxLayout = with(density) { 16_000.toDp() }
            val contentWidth = if (wrapLines) maxWidth else maxOf(maxWidth, minOf(maxLayout, charWidth * (longest + 5) + gutter + 24.dp))
            SelectionContainer {
                Box(Modifier.fillMaxSize().then(if (wrapLines) Modifier else Modifier.horizontalScroll(horizontal))) {
                    LazyColumn(state = list, modifier = Modifier.width(contentWidth).fillMaxHeight(), contentPadding = PaddingValues(vertical = 8.dp)) {
                        itemsIndexed(lines) { index, line ->
                            val focused = target == index + 1
                            val chosen = selected?.contains(index + 1) == true
                            val mark = changeAt(index)
                            val changeColor = when (mark) {
                                SourceChange.Added -> palette.diffAdd
                                SourceChange.Removed -> palette.diffRemove
                                SourceChange.Modified -> MaterialTheme.colorScheme.tertiary
                                null -> null
                            }
                            Row(Modifier.fillMaxWidth().background(when {
                                chosen -> MaterialTheme.colorScheme.secondaryContainer
                                focused -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                changeColor != null -> changeColor.copy(alpha = 0.12f)
                                else -> Color.Transparent
                            })
                                .then(if (selecting && onSelectLine != null) Modifier.clickable { onSelectLine(index + 1) } else Modifier)) {
                                val lineNumber = if (changes == null) "${index + 1}" else "${index + 1} ${mark?.sign ?: " "}"
                                Text(lineNumber, Modifier.width(gutter).then(if (onSelectLine != null && !selecting) Modifier.clickable(onClickLabel = stringResource(R.string.chat_code_viewer_004, index + 1)) { onSelectLine(index + 1) } else Modifier).padding(end = 12.dp), style = style, textAlign = TextAlign.End, color = changeColor ?: if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(coloredLine(line), Modifier.weight(1f).padding(end = 16.dp), style = style, softWrap = wrapLines || contentWidth >= maxLayout)
                            }
                        }
                    }
                }
            }
        }
        CodeMinimap(lines, list, stringResource(R.string.code_navigation_minimap), Modifier.fillMaxHeight(),
            markAt = { index -> changeAt(index)?.let { if (it == SourceChange.Removed) '-' else it.sign.single() } }) { it }
    }
}

private val darkCodeInk = listOf(0xFFC4A7FF, 0xFFA8D69D, 0xFF94A3AD, 0xFFF4BF88, 0xFF85CFE8,
    0xFFFFC88B, 0xFF85CFE8, 0xFFFFC88B, 0xFFC4A7FF, 0xFFD2D9E1).map { Color(it) }
private val lightCodeInk = listOf(0xFF7137A8, 0xFF28651B, 0xFF64716C, 0xFF955000, 0xFF006B80,
    0xFF904C16, 0xFF006B80, 0xFF904C16, 0xFF7137A8, 0xFF35495C).map { Color(it) }

@Composable
internal fun codeInkColors(): List<Color> {
    val surface = MaterialTheme.colorScheme.surface
    return if (surface.red + surface.green + surface.blue < 1.5f) darkCodeInk else lightCodeInk
}

@Composable
internal fun coloredLine(line: CodeLine, limit: Int = MAX_LINE_CHARS): AnnotatedString {
    val colors = codeInkColors()
    return remember(line, colors, limit) {
        buildAnnotatedString {
            val shown = line.text.take(limit)
            append(shown)
            for (token in line.tokens) {
                if (token.start >= shown.length) break
                addStyle(SpanStyle(color = colors[token.ink.ordinal]), token.start, token.end.coerceAtMost(shown.length))
            }
            if (line.text.length > limit) append(" …")
        }
    }
}

/** ANSI 色を構文色より優先する。 */
@Composable
fun highlightedCode(text: String, language: CodeLanguage? = null): AnnotatedString {
    val terminal = remember(text) { terminalText(text) }
    val selected = remember(terminal.text, language) { language ?: outputCodeLanguage(terminal.text) }
    val lines = remember(terminal.text, selected) { highlightCode(terminal.text, selected) }
    return buildAnnotatedString {
        lines.forEachIndexed { index, line ->
            if (index > 0) append('\n')
            append(coloredLine(line, Int.MAX_VALUE))
        }
        for (span in terminal.spans) {
            val end = span.end.coerceAtMost(length)
            if (span.start >= end) continue
            addStyle(SpanStyle(
                color = span.foreground?.let { Color(0xFF000000L or it.toLong()) } ?: Color.Unspecified,
                background = span.background?.let { Color(0xFF000000L or it.toLong()) } ?: Color.Unspecified,
                fontWeight = if (span.bold) FontWeight.Bold else null,
            ), span.start, end)
        }
    }
}

/** 横スクロールを全行で共有する。 */
@Composable
fun DiffBlock(text: String, maxHeight: Int = 360, maxLines: Int = Int.MAX_VALUE, path: String = "", wrapLines: Boolean = false) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()) {
        DiffCodeRows(text, path, wrapLines, Modifier.heightIn(max = maxHeight.dp).padding(vertical = 6.dp), maxLines)
    }
}

@Composable
fun NumberedCodeBlock(text: String, path: String, maxLines: Int = Int.MAX_VALUE, wrapLines: Boolean = false) {
    val lines = remember(text, path) { highlightCode(text, codeLanguage(path)) }
    val annotated = buildAnnotatedString {
        lines.take(maxLines).forEachIndexed { index, line ->
            if (index > 0) append('\n')
            append((index + 1).toString().padStart(lines.size.toString().length)); append("  ")
            append(coloredLine(line))
        }
        if (lines.size > maxLines) append(appString(R.string.chat_code_viewer_001, lines.size - maxLines))
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
        Text(annotated, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 12.sp * CodeScale, lineHeight = 18.sp * CodeScale), softWrap = wrapLines,
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                .then(if (wrapLines) Modifier else Modifier.horizontalScroll(rememberScrollState())).padding(10.dp))
    }
}
