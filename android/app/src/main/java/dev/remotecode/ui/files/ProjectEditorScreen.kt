package dev.remotecode.ui.files

import dev.remotecode.R

import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import dev.remotecode.ui.common.AppDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ApiException
import dev.remotecode.data.ConnState
import dev.remotecode.data.CodeQuote
import dev.remotecode.data.ProjectDraftKey
import dev.remotecode.data.ProjectDocument
import dev.remotecode.data.ServerConfig
import dev.remotecode.data.connectionIdentity
import dev.remotecode.data.codeQuoteFromSelection
import dev.remotecode.data.projectEditorFits
import dev.remotecode.data.isProjectMarkdown
import dev.remotecode.data.projectDraftKey
import dev.remotecode.ui.chat.CodeLine
import dev.remotecode.ui.chat.CodeMinimap
import dev.remotecode.ui.chat.CodeQuoteRangeDialog
import dev.remotecode.ui.chat.LinkSheet
import dev.remotecode.ui.chat.LocalCodeQuote
import dev.remotecode.ui.chat.codeLanguage
import dev.remotecode.ui.chat.coloredLine
import dev.remotecode.ui.chat.highlightCode
import dev.remotecode.ui.chat.saveToDownloads
import dev.remotecode.ui.theme.CodeScale
import dev.remotecode.ui.theme.Mono
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private data class EditorDraft(val original: ProjectDocument, val text: String)
// Bundle の容量制限を避け、本文はメモリに保持する。
private val editorDrafts = ConcurrentHashMap<ProjectDraftKey, EditorDraft>()

@Composable
fun ProjectEditorScreen(sessionId: String, path: String, onBack: () -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val settings by repo.settings.collectAsState()
    val wrapLines = settings?.codeWrap ?: true
    val route by repo.route.collectAsState()
    val connection by repo.connection.collectAsState()
    val currentIdentity = remember(settings?.server, route, connection) { repo.api.server?.connectionIdentity() }
    val connected = connection is ConnState.Connected
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val editor = remember(sessionId, path) { NativeCodeEditorState() }
    var document by remember(sessionId, path) { mutableStateOf<ProjectDocument?>(null) }
    var loadedServer by remember(sessionId, path) { mutableStateOf<ServerConfig?>(null) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var conflict by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var loadedGeneration by remember { mutableIntStateOf(-1) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmReload by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var markdownPreview by remember { mutableStateOf(false) }
    var previewText by remember(sessionId, path) { mutableStateOf("") }
    var quoteText by remember(sessionId, path) { mutableStateOf("") }
    var dirty by remember(sessionId, path) { mutableStateOf(false) }
    var cleanRevision by remember(sessionId, path) { mutableLongStateOf(0L) }
    var showFile by remember { mutableStateOf(false) }
    var quoteRange by remember(sessionId, path) { mutableStateOf(false) }
    var quoteInitialLine by remember(sessionId, path) { mutableIntStateOf(1) }
    val quoteAction = LocalCodeQuote.current
    val contentRevision = editor.contentRevision
    val changedServer = loadedServer != null && loadedServer?.connectionIdentity() != currentIdentity
    fun currentDraftKey() = loadedServer?.connectionIdentity()?.let { document?.projectDraftKey(it, sessionId) }

    fun stash() {
        val current = document
        val key = loadedServer?.connectionIdentity()?.let { current?.projectDraftKey(it, sessionId) }
        if (key != null && current != null) {
            val snapshot = editor.snapshot()
            if (current.text != null && snapshot != current.text) editorDrafts[key] = EditorDraft(current, snapshot) else editorDrafts.remove(key)
        }
    }
    fun leave() { if (dirty) confirmLeave = true else onBack() }
    fun submitQuote(quote: CodeQuote) {
        val target = loadedServer ?: throw IllegalStateException(appString(R.string.screen_project_editor_001))
        if (target.connectionIdentity() != repo.api.server?.connectionIdentity()) throw IllegalStateException(appString(R.string.screen_project_editor_002))
        val receive = quoteAction ?: throw IllegalStateException(appString(R.string.screen_project_editor_003))
        // 引用で下書きを保存・破棄しない。
        stash()
        receive(sessionId, target.connectionIdentity(), quote)
        focus.clearFocus()
        quoteRange = false
    }
    fun beginQuote() {
        val doc = document ?: return
        if (loading || saving || changedServer || doc.text == null) return
        val target = loadedServer?.connectionIdentity() ?: return
        val generation = loadedGeneration
        val snapshot = editor.snapshot()
        val start = editor.selectionStart
        val end = editor.selectionEnd
        val isDraft = snapshot != doc.text
        scope.launch {
            try {
                if (!preview && !markdownPreview && start != end) {
                    val quote = withContext(Dispatchers.Default) { codeQuoteFromSelection(doc.absolutePath, snapshot, start, end, isDraft = isDraft) }
                    check(document === doc && loadedGeneration == generation && loadedServer?.connectionIdentity() == target && repo.api.server?.connectionIdentity() == target) { appString(R.string.screen_project_editor_004) }
                    submitQuote(quote)
                } else {
                    val line = withContext(Dispatchers.Default) { snapshot.take(minOf(start, end).coerceIn(0, snapshot.length)).count { it == '\n' } + 1 }
                    check(document === doc && loadedGeneration == generation && loadedServer?.connectionIdentity() == target && repo.api.server?.connectionIdentity() == target) { appString(R.string.screen_project_editor_004) }
                    quoteText = snapshot; quoteInitialLine = line; quoteRange = true
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: appString(R.string.screen_project_editor_005) }
        }
    }
    fun openPreview(markdown: Boolean) {
        previewText = editor.snapshot(); focus.clearFocus()
        markdownPreview = markdown; preview = !markdown
    }
    BackHandler { if (!saving) leave() }
    val latestStash = rememberUpdatedState { stash() }
    DisposableEffect(sessionId, path) { onDispose { latestStash.value() } }

    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 13.sp * CodeScale, lineHeight = 20.sp * CodeScale)
    val density = LocalDensity.current
    val glyphPx = with(density) { style.fontSize.toPx() }
    var fits by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(sessionId, path, reload, connected) {
        if (!connected || document != null && loadedGeneration == reload) return@LaunchedEffect
        val target = repo.api.server ?: return@LaunchedEffect
        loading = true; error = null
        try {
            val value = repo.api.projectFile(sessionId, path, expectedServer = target)
            val draftKey = value.projectDraftKey(target.connectionIdentity(), sessionId)
            val draft = editorDrafts[draftKey]
            val original = draft?.original?.takeIf { it.root == value.root } ?: value
            val content = draft?.takeIf { it.original.root == value.root }?.text ?: value.text.orEmpty()
            val supported = withContext(Dispatchers.Default) { projectEditorFits(content, glyphPx) }
            document = original
            editor.setText(content)
            cleanRevision = editor.contentRevision
            dirty = content != original.text
            fits = supported
            conflict = draft != null && draft.original.root == value.root && draft.original.revision != value.revision
            loadedServer = target
            loadedGeneration = reload
            preview = !value.editable
            markdownPreview = false
            previewText = content
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: appString(R.string.screen_project_editor_006) }
        finally { loading = false }
    }

    // 選択・スクロールでは全文比較を再実行しない。
    LaunchedEffect(contentRevision, document?.revision, loadedGeneration) {
        val original = document?.text ?: return@LaunchedEffect
        if (contentRevision == cleanRevision || loading) return@LaunchedEffect
        delay(300)
        val snapshot = editor.snapshot()
        val changed = withContext(Dispatchers.Default) { snapshot != original }
        if (editor.contentRevision == contentRevision) dirty = changed
    }
    val canEdit = document?.editable == true && fits == true && !changedServer && !saving && !loading

    fun copyDraft() {
        try {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(document?.name ?: appString(R.string.screen_project_editor_007), editor.snapshot()))
            scope.launch { snackbar.showSnackbar(appString(R.string.screen_project_editor_008)) }
        } catch (_: Exception) { scope.launch { snackbar.showSnackbar(appString(R.string.screen_project_editor_009)) } }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Column { Text((document?.name ?: path.substringAfterLast('/')) + if (dirty) " *" else "", maxLines = 1, overflow = TextOverflow.Ellipsis); Text(if (dirty) appString(R.string.screen_project_editor_010) else if (document != null) appString(R.string.screen_project_editor_011) else appString(R.string.screen_project_editor_012), style = MaterialTheme.typography.labelSmall) } },
                navigationIcon = { IconButton(onClick = ::leave, enabled = !saving) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.screen_project_editor_013)) } },
                actions = {
                    if (quoteAction != null) TextButton(enabled = document?.text != null && !loading && !saving && !changedServer, onClick = ::beginQuote) {
                        Icon(Icons.Rounded.FormatQuote, stringResource(R.string.screen_project_editor_014), Modifier.size(18.dp))
                        Text(stringResource(R.string.screen_project_editor_015))
                    }
                    TextButton(enabled = canEdit && dirty && !conflict && connected, onClick = {
                        val original = document ?: return@TextButton
                        val target = loadedServer ?: return@TextButton
                        val writtenText = editor.snapshot()
                        val writtenKey = original.projectDraftKey(target.connectionIdentity(), sessionId)
                        saving = true; error = null
                        scope.launch {
                            try {
                                val saved = repo.api.saveProjectFile(sessionId, original, writtenText, expectedServer = target)
                                document = saved
                                editor.setText(saved.text.orEmpty(), resetSelection = false)
                                cleanRevision = editor.contentRevision; dirty = false
                                editorDrafts.remove(writtenKey)
                                conflict = false
                                scope.launch { runCatching { repo.refreshGit(sessionId, force = true) } }
                                saving = false
                                scope.launch { snackbar.showSnackbar(appString(R.string.screen_project_editor_016)) }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { conflict = e is ApiException && e.code == 409; error = e.message ?: appString(R.string.screen_project_editor_017) }
                            finally { saving = false }
                        }
                    }) { Text(if (saving) appString(R.string.screen_project_editor_018) else appString(R.string.screen_project_editor_019)) }
                    if (!markdownPreview) IconToggleButton(
                        checked = wrapLines,
                        enabled = document?.text != null,
                        onCheckedChange = { on -> scope.launch { repo.settingsStore.setCodeWrap(on) } },
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.WrapText, stringResource(R.string.screen_project_editor_020, if (wrapLines) "ON" else "OFF"))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.screen_project_editor_021)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(if (preview || markdownPreview) appString(R.string.screen_project_editor_022) else appString(R.string.screen_project_editor_023)) }, leadingIcon = { Icon(if (preview || markdownPreview) Icons.Rounded.Edit else Icons.Rounded.Code, null) }, enabled = document?.text != null && fits == true, onClick = { menu = false; if (preview || markdownPreview) { preview = false; markdownPreview = false } else openPreview(false) })
                            if (isProjectMarkdown(document?.name ?: path)) DropdownMenuItem(text = { Text(stringResource(R.string.screen_project_editor_024)) }, leadingIcon = { Icon(Icons.Rounded.Description, null) }, enabled = document?.text != null, onClick = { menu = false; openPreview(true) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.screen_project_editor_025)) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, enabled = document?.text != null, onClick = { menu = false; copyDraft() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.screen_project_editor_026)) }, leadingIcon = { Icon(Icons.Rounded.Download, null) }, enabled = document?.text != null, onClick = {
                                menu = false
                                scope.launch {
                                    try { saveToDownloads(document!!.name, "text/plain", editor.snapshot().toByteArray(Charsets.UTF_8)); snackbar.showSnackbar(appString(R.string.screen_project_editor_027)) }
                                    catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { snackbar.showSnackbar(appString(R.string.screen_project_editor_028)) }
                                }
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.screen_project_editor_029)) }, leadingIcon = { Icon(Icons.Rounded.Refresh, null) }, enabled = connected && !saving, onClick = { menu = false; if (dirty) confirmReload = true else reload++ })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            document?.let { doc ->
                Text(doc.absolutePath, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.screen_project_editor_030, codeLanguage(doc.name).displayLabel, editor.lineCount, doc.encoding.uppercase(), doc.lineEnding.uppercase()), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isProjectMarkdown(doc.name)) Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { preview = false; markdownPreview = false }) { Text(stringResource(R.string.screen_project_editor_031)) }
                    TextButton(onClick = { openPreview(true) }, enabled = doc.text != null && !loading) { Text(stringResource(R.string.screen_project_editor_032)) }
                }
            }
            if (changedServer) Text(stringResource(R.string.screen_project_editor_033), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (conflict) {
                Text(stringResource(R.string.screen_project_editor_034), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { confirmReload = true }, enabled = connected && !saving) { Text(stringResource(R.string.screen_project_editor_035)) }
            }
            error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            val doc = document
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                doc == null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(if (!connected) appString(R.string.screen_project_editor_036) else appString(R.string.screen_project_editor_037)); Button(onClick = { reload++ }, enabled = connected) { Text(stringResource(R.string.screen_project_editor_038)) } }
                doc.text != null && fits == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                markdownPreview && doc.text != null -> ProjectMarkdownPreview(previewText, doc.absolutePath, sessionId, Modifier.weight(1f), expectedServer = loadedServer)
                doc.text == null || fits == false -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (fits == false) appString(R.string.screen_project_editor_039) else doc.reason ?: appString(R.string.screen_project_editor_040))
                    Button(onClick = { showFile = true }, enabled = connected && !changedServer && !saving) { Text(stringResource(R.string.screen_project_editor_041)) }
                }
                preview -> ProjectCodePreview(previewText, doc.name, Modifier.weight(1f), wrapLines)
                else -> NativeProjectCodeEditor(
                    state = editor, editable = canEdit && !conflict, wrapLines = wrapLines,
                    modifier = Modifier.weight(1f), textSizeSp = 13f * CodeScale, lineHeightSp = 20f * CodeScale,
                    onChanged = { dirty = true },
                    onRejected = { error = appString(R.string.screen_project_editor_042) },
                )
            }
            if (doc?.text != null && !doc.editable) Text(doc.reason ?: appString(R.string.screen_project_editor_043), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false }, title = { Text(stringResource(R.string.screen_project_editor_044)) }, text = { Text(stringResource(R.string.screen_project_editor_045)) },
        confirmButton = { TextButton(onClick = { stash(); confirmLeave = false; onBack() }) { Text(stringResource(R.string.screen_project_editor_046)) } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.screen_project_editor_047)) } },
    )
    if (confirmReload) AlertDialog(
        onDismissRequest = { confirmReload = false }, title = { Text(stringResource(R.string.screen_project_editor_048)) }, text = { Text(stringResource(R.string.screen_project_editor_049)) },
        confirmButton = { TextButton(onClick = { currentDraftKey()?.let { editorDrafts.remove(it) }; confirmReload = false; reload++ }) { Text(stringResource(R.string.screen_project_editor_050)) } },
        dismissButton = { TextButton(onClick = { confirmReload = false }) { Text(stringResource(R.string.screen_project_editor_051)) } },
    )
    LaunchedEffect(changedServer) { if (changedServer) { showFile = false; quoteRange = false } }
    if (quoteRange && document?.text != null && !changedServer) CodeQuoteRangeDialog(
        path = document!!.absolutePath, text = quoteText, isDraft = quoteText != document!!.text,
        initialStartLine = quoteInitialLine, initialEndLine = quoteInitialLine,
        onDismiss = { quoteRange = false }, onQuote = ::submitQuote,
    )
    if (showFile && document != null && !changedServer) LinkSheet(document!!.absolutePath, sessionId) { showFile = false }
}

@Composable
private fun ProjectCodePreview(text: String, path: String, modifier: Modifier, wrapLines: Boolean) {
    var lines by remember { mutableStateOf<List<CodeLine>?>(null) }
    val language = remember(path) { codeLanguage(path) }
    LaunchedEffect(text, language) { lines = withContext(Dispatchers.Default) { highlightCode(text, language) } }
    val loaded = lines
    if (loaded == null) { Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LoadingIndicator() }; return }
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 13.sp * CodeScale, lineHeight = 20.sp * CodeScale)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val gutter = with(density) { measurer.measure("9".repeat(loaded.size.toString().length), style).size.width.toDp() } + 20.dp
    val list = rememberLazyListState()
    val horizontal = rememberScrollState()
    var previousWrap by remember { mutableStateOf(wrapLines) }
    val wrapAnchor = remember(wrapLines) { list.firstVisibleItemIndex }
    LaunchedEffect(wrapLines) {
        if (previousWrap != wrapLines) {
            if (loaded.isNotEmpty()) list.scrollToItem(wrapAnchor.coerceIn(0, loaded.lastIndex))
            previousWrap = wrapLines
        }
    }
    Row(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val columns = remember(loaded, wrapLines) {
                if (wrapLines) 0 else loaded.maxOfOrNull { line ->
                    (0 until minOf(line.text.length, 4000)).sumOf { index ->
                        when { line.text[index] == '\t' -> 4; line.text[index] >= '\u2e80' -> 2; else -> 1 }
                    }
                } ?: 0
            }
            val width = if (wrapLines) maxWidth else with(density) { maxOf(maxWidth.toPx(), (columns + 8) * style.fontSize.toPx()).coerceAtMost(100_000f).toDp() }
            Box(Modifier.fillMaxSize().then(if (wrapLines) Modifier else Modifier.horizontalScroll(horizontal))) {
                LazyColumn(state = list, modifier = Modifier.width(width).fillMaxHeight(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(loaded) { index, line ->
                        Row(Modifier.fillMaxWidth()) {
                            Text("${index + 1}", Modifier.width(gutter).padding(end = 10.dp), textAlign = TextAlign.End, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(coloredLine(line, limit = 4000), style = style, softWrap = wrapLines, modifier = Modifier.then(if (wrapLines) Modifier.weight(1f) else Modifier).padding(end = 12.dp))
                        }
                    }
                }
            }
        }
        CodeMinimap(loaded, list, stringResource(R.string.code_navigation_minimap), Modifier.fillMaxHeight()) { it }
    }
}
