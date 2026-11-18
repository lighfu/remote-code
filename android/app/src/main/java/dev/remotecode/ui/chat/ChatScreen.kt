package dev.remotecode.ui.chat

import dev.remotecode.R
import dev.remotecode.i18n.appString
import dev.remotecode.i18n.localizedModeLabel
import dev.remotecode.i18n.localizedModeDescription
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import dev.remotecode.ui.theme.ChatFont
import androidx.compose.ui.res.stringResource

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import dev.remotecode.ui.common.AppDropdownMenu as DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ConnState
import dev.remotecode.data.ChatFooterDisplay
import dev.remotecode.data.ToolWrapPreferences
import dev.remotecode.data.ChatFooterItem
import dev.remotecode.data.CodeQuote
import dev.remotecode.data.CodeQuoteDrafts
import dev.remotecode.data.appendConversationQuote
import dev.remotecode.data.codeQuotePrompt
import dev.remotecode.data.CommandInfo
import dev.remotecode.data.ImagePayload
import dev.remotecode.data.ThinkingModes
import dev.remotecode.ui.common.ModelPicker
import dev.remotecode.ui.common.concreteModel
import dev.remotecode.ui.common.modelLabel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import dev.remotecode.data.Providers
import dev.remotecode.data.ApiException
import dev.remotecode.data.SelectedFile
import dev.remotecode.data.MAX_FILE_ATTACHMENTS
import dev.remotecode.data.connectionIdentity
import dev.remotecode.data.readSelectedFile
import dev.remotecode.ui.common.ProviderBadge
import dev.remotecode.ui.common.shortPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream

@Composable
private fun WrapMenuItem(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.WrapText, null) },
        trailingIcon = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
        onClick = { onChange(!checked) },
    )
}

private class Attachment(val payload: ImagePayload, val preview: Bitmap)
private data class ChatSubmission(
    val draft: ComposerSubmission<Attachment, SelectedFile>,
    val owner: dev.remotecode.data.ConnectionIdentity?,
    val quoteSource: dev.remotecode.data.ConnectionIdentity?,
    val quotes: List<CodeQuote>,
)

/** 添付の下書きはプロセス内だけ保持する。 */
private val attachmentDrafts = java.util.concurrent.ConcurrentHashMap<String, List<Attachment>>()
private val fileAttachmentDrafts = java.util.concurrent.ConcurrentHashMap<String, List<SelectedFile>>()

@Composable
fun ChatScreen(sessionId: String, onBack: () -> Unit, onTerminals: (String) -> Unit = {}, onOpenTerminal: (String) -> Unit = {}, onProjectFiles: () -> Unit = {}) {
    val app = RemoteCodeApp.instance
    val repo = app.repository
    val sessions by repo.sessions.collectAsState()
    val session = sessions.firstOrNull { it.id == sessionId }
    val timeline by repo.timeline(sessionId).collectAsState()
    val meta by repo.meta.collectAsState()
    val modelOptions by repo.models.collectAsState()
    val conn by repo.connection.collectAsState()
    val settings by repo.settings.collectAsState()
    val route by repo.route.collectAsState()
    val quoteIdentity = remember(settings?.server, route, conn) { repo.api.server?.connectionIdentity() }
    val quoteDrafts = remember(quoteIdentity, sessionId) { quoteIdentity?.let { CodeQuoteDrafts.quotes(it, sessionId) } ?: MutableStateFlow(emptyList<CodeQuote>()) }
    val codeQuotes by quoteDrafts.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val speechLanguage = LocalConfiguration.current.locales[0].toLanguageTag()
    val composerFocus = remember(sessionId) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    val list = rememberLazyListState()
    val inputState = dev.remotecode.ui.common.rememberDraft("chat:$sessionId")
    val input by inputState
    var inputRevision by remember(sessionId) { mutableStateOf(0L) }
    fun updateInput(text: String) {
        if (input != text) {
            inputRevision++
            inputState.value = text
        }
    }
    val attachments = remember(sessionId) { mutableStateListOf<Attachment>().apply { addAll(attachmentDrafts[sessionId].orEmpty()) } }
    val fileAttachments = remember(sessionId) { mutableStateListOf<SelectedFile>().apply { addAll(fileAttachmentDrafts[sessionId].orEmpty()) } }
    DisposableEffect(sessionId) {
        onDispose {
            if (attachments.isEmpty()) attachmentDrafts.remove(sessionId) else attachmentDrafts[sessionId] = attachments.toList()
            if (fileAttachments.isEmpty()) fileAttachmentDrafts.remove(sessionId) else fileAttachmentDrafts[sessionId] = fileAttachments.toList()
        }
    }
    var sending by remember(sessionId) { mutableStateOf(false) }
    var submission by remember(sessionId) { mutableStateOf<ChatSubmission?>(null) }
    var readingFiles by remember(sessionId) { mutableStateOf(false) }
    val fileTransfer = remember(sessionId) { MutableStateFlow<String?>(null) }
    val uploadStatus by fileTransfer.collectAsState()
    var follow by remember { mutableStateOf(true) }
    var modeMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var headerMenu by remember(sessionId) { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var pickingModel by remember { mutableStateOf(false) }
    var showContext by remember { mutableStateOf(false) }
    var showCache by remember { mutableStateOf(false) }
    var showUsage by remember { mutableStateOf(false) }
    var showGit by remember(sessionId) { mutableStateOf(false) }
    var showFooterDisplay by remember { mutableStateOf(false) }
    var savingFooterDisplay by remember { mutableStateOf(false) }
    var showBtw by remember { mutableStateOf(false) }
    var btwAsk by remember { mutableStateOf<String?>(null) }
    /** 未読判定用。空文字は未取得。 */
    var btwSeenId by rememberSaveable { mutableStateOf("") }
    var rewindTarget by remember { mutableStateOf<dev.remotecode.data.Entry?>(null) }
    var linkTarget by remember { mutableStateOf<String?>(null) }
    // Claude の #terminal タブを PC の端末に解決する。
    fun openTerminalTab(tab: String) {
        val notice = scope.launch { snackbar.showSnackbar(appString(R.string.chat_chat_screen_085)) }
        scope.launch {
            val r = runCatching { repo.api.terminalResolve(tab, sessionId) }
            notice.cancel()
            r.onSuccess { onOpenTerminal(it) }
                .onFailure {
                    snackbar.showSnackbar(it.message ?: appString(R.string.chat_chat_screen_084))
                    onTerminals(session?.cwd.orEmpty())
                }
        }
    }
    val linkHandler = remember {
        object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri: String) {
                if (uri.startsWith("#terminal/")) openTerminalTab(uri.removePrefix("#terminal/")) else linkTarget = uri
            }
        }
    }
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    var confirmTakeover by remember { mutableStateOf(false) }
    var showAgents by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    var commands by remember { mutableStateOf<List<CommandInfo>?>(null) }
    var paletteOpen by remember { mutableStateOf(false) }
    val footerDisplay = settings?.footerDisplay ?: ChatFooterDisplay()
    val display = Display(
        thinking = settings?.thinking ?: "full",
        toolDetail = settings?.toolDetail ?: true,
        toolWrap = settings?.toolWrap ?: ToolWrapPreferences(),
    )

    DisposableEffect(sessionId) {
        repo.visibleSession.value = sessionId
        onDispose { if (repo.visibleSession.value == sessionId) repo.visibleSession.value = null }
    }
    LaunchedEffect(sessionId) { repo.loadTimeline(sessionId) }
    LaunchedEffect(session?.provider) { session?.provider?.let { p -> runCatching { repo.refreshModels(p) } } }

    val allEntries = timeline.entries
    val (entries, children) = remember(allEntries, display.thinking) { chatTimeline(allEntries, display.thinking) }
    val agents = remember(allEntries) { allEntries.filter { it.kind == "tool" && it.tool == "Agent" } }
    val edits = remember(allEntries) { allEntries.filter { it.kind == "tool" && it.tool in EDIT_TOOLS } }
    val running = session?.isRunning == true || session?.isWaiting == true

    LaunchedEffect(session?.thinking, settings?.thinking) {
        val want = settings?.thinking ?: return@LaunchedEffect
        if (session != null && session.thinking != want) runCatching { repo.api.setThinking(sessionId, want) }
    }
    LaunchedEffect(input.startsWith("/") || input.startsWith("$") || paletteOpen, session?.provider) {
        if ((input.startsWith("/") || input.startsWith("$") || paletteOpen) && commands == null && session != null) {
            runCatching { repo.api.commands(session.provider, sessionId) }
                .onSuccess { commands = it }
                .onFailure { snackbar.showSnackbar(appString(R.string.chat_chat_screen_083, it.message)) }
        }
    }

    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 120 }
            .collect { atBottom -> if (list.isScrollInProgress || atBottom) follow = atBottom }
    }
    LaunchedEffect(allEntries.size, entries.lastOrNull()?.id, running) {
        if (follow && entries.isNotEmpty()) list.scrollToItem(0)
    }

    fun act(block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onFailure { snackbar.showSnackbar(it.message ?: appString(R.string.chat_chat_screen_082)) } }
    }

    val respond: Responder = { requestId, choice, answers ->
        act { repo.api.respond(sessionId, requestId, choice = choice, answers = answers) }
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) updateInput(if (input.isBlank()) text else "$input $text")
    }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        scope.launch {
            for (u in uris) loadAttachment(context, u)?.let { attachments.add(it) }
        }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            readingFiles = true
            val errors = mutableListOf<String>()
            try {
                val selected = uris.distinct().filter { uri -> fileAttachments.none { it.uri == uri } }
                val remaining = (MAX_FILE_ATTACHMENTS - fileAttachments.size).coerceAtLeast(0)
                if (selected.size > remaining) errors.add(appString(R.string.chat_chat_screen_081, MAX_FILE_ATTACHMENTS))
                for (uri in selected.take(remaining)) {
                    try { fileAttachments.add(readSelectedFile(context, uri)) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { errors.add(e.message ?: appString(R.string.chat_chat_screen_080)) }
                }
            } finally {
                readingFiles = false
            }
            if (errors.isNotEmpty()) snackbar.showSnackbar(errors.first())
        }
    }

    fun acknowledge(sent: ChatSubmission) {
        if (submission !== sent || sent.owner != settings?.server?.connectionIdentity()) return
        sent.draft.acknowledge(input, inputRevision, attachments, fileAttachments) { updateInput("") }
        sent.quoteSource?.let { source -> sent.quotes.forEach { CodeQuoteDrafts.remove(source, sessionId, it.id) } }
        submission = null
        follow = true
    }

    LaunchedEffect(timeline.entries, submission) {
        val sent = submission ?: return@LaunchedEffect
        if (sent.draft.hasReceipt(timeline.entries)) acknowledge(sent)
    }
    LaunchedEffect(sessionId) {
        repo.events.collect { event ->
            val sent = submission
            if (event.sessionId == sessionId && sent != null && sent.draft.matchesReceipt(event.entry)) {
                acknowledge(sent)
            }
        }
    }

    /** now は実行中の処理を止めて即時送信する。 */
    fun send(now: Boolean = false) {
        if (sending || readingFiles) return
        val typed = input.trim()
        val selectedQuotes = codeQuotes.toList()
        val quoteSource = quoteIdentity
        val text = codeQuotePrompt(typed, selectedQuotes)
        if (text.isEmpty() && attachments.isEmpty() && fileAttachments.isEmpty()) return
        if (now) haptics.reject() else haptics.confirm()
        val draft = ComposerSubmission(input, inputRevision, attachments.toList(), fileAttachments.toList())
        val sent = ChatSubmission(draft, settings?.server?.connectionIdentity(), quoteSource, selectedQuotes)
        val imgs = draft.images.map { it.payload }
        val selectedFiles = draft.files
        submission = sent
        sending = true
        scope.launch {
            var failure: String? = null
            try {
                val target = if (selectedFiles.isNotEmpty() || selectedQuotes.isNotEmpty()) repo.api.server ?: throw ApiException(appString(R.string.chat_chat_screen_079)) else null
                if (selectedQuotes.isNotEmpty() && target?.connectionIdentity() != quoteSource) throw ApiException(appString(R.string.chat_chat_screen_078))
                val fileIds = mutableListOf<String>()
                if (target != null) {
                    val identity = target.connectionIdentity()
                    selectedFiles.forEachIndexed { index, file ->
                        if (repo.api.server?.connectionIdentity() != identity) throw ApiException(appString(R.string.chat_chat_screen_075))
                        val uploaded = file.uploaded?.takeIf { file.uploadedFor == identity } ?: run {
                            fileTransfer.value = appString(R.string.chat_chat_screen_077, index + 1, selectedFiles.size)
                            repo.api.uploadFile(context, file, target) { bytes ->
                                val progress = file.size?.takeIf { it > 0 }?.let { " ${((bytes * 100 / it).coerceIn(0, 100))}%" }.orEmpty()
                                fileTransfer.value = appString(R.string.chat_chat_screen_076, index + 1, selectedFiles.size, progress)
                            }.also {
                                if (repo.api.server?.connectionIdentity() != identity) throw ApiException(appString(R.string.chat_chat_screen_075))
                                file.uploadedFor = identity
                                file.uploaded = it
                            }
                        }
                        fileIds.add(uploaded.id)
                    }
                    fileTransfer.value = appString(R.string.chat_chat_screen_074)
                }
                // Codex のコマンドは専用 API、Claude は本文で送る。
                val cmd = Regex("^[/\$]([^\\s]+)\\s*([\\s\\S]*)\$").find(text)
                val known = cmd?.let { m -> commands?.firstOrNull { it.name == m.groupValues[1] } }
                if (session?.provider == "codex" && known != null && imgs.isEmpty() && selectedFiles.isEmpty() && selectedQuotes.isEmpty()) {
                    repo.api.runCommand(sessionId, known.name, cmd.groupValues[2].trim())
                } else {
                    repo.api.send(
                        sessionId, text, imgs, now, files = fileIds, expectedServer = target,
                        quotedCode = selectedQuotes.isNotEmpty(), clientMessageId = draft.clientMessageId,
                    )
                }
                acknowledge(sent)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (draft.hasReceipt(repo.timeline(sessionId).value.entries)) acknowledge(sent)
                if (!draft.acknowledged) failure = e.message ?: appString(R.string.chat_chat_screen_073)
            } finally {
                fileTransfer.value = null
                sending = false
            }
            failure?.let { if (!draft.acknowledged) snackbar.showSnackbar(it) }
        }
    }

    val btwAll by repo.btw.collectAsState()
    val btwItems = btwAll[sessionId].orEmpty()
    val btwLastDone = btwItems.lastOrNull { it.status != "pending" }?.id
    LaunchedEffect(sessionId) {
        repo.loadBtw(sessionId)
        // 既存の回答は未読にしない。
        if (btwSeenId.isEmpty()) btwSeenId = repo.btw.value[sessionId].orEmpty().lastOrNull { it.status != "pending" }?.id ?: "-"
    }

    fun reply(text: String) {
        if (sending) return
        sending = true
        scope.launch {
            try {
                repo.api.send(sessionId, text, emptyList())
                follow = true
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: appString(R.string.chat_chat_screen_073))
            } finally {
                sending = false
            }
        }
    }

    val nextStepId = remember(entries) {
        val k = entries.indexOfLast { it.kind == "tool" && it.tool == "wrap_up" }
        if (k >= 0 && entries.subList(k + 1, entries.size).none { it.kind == "user" }) entries[k].id else null
    }

    val modes = meta?.providers?.get(session?.provider ?: "")?.modes.orEmpty()
    val defaults = dev.remotecode.ui.common.pcDefaults(session?.provider)
    val currentModelOptions = session?.provider?.let { modelOptions[it] }.orEmpty()
    val currentModelOption = dev.remotecode.ui.common.resolveModelOption(session?.model.concreteModel() ?: defaults?.model, currentModelOptions)
    val fastSupported = currentModelOption?.supportsFastMode != false
    val pcMode = defaults?.mode
    val effectiveMode = session?.mode?.ifBlank { null } ?: pcMode
    val modeLabel = modes.firstOrNull { it.id == effectiveMode && it.id.isNotBlank() }?.let(::localizedModeLabel)
        ?: effectiveMode
        ?: appString(R.string.core_mode_pc_default)
    // 空のモードは PC の既定値として表示する。
    val visibleModes = modes.filter { it.id.isNotBlank() }

    fun copySessionValue(label: String, value: String) {
        context.getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
        headerMenu = false
        scope.launch { snackbar.showSnackbar(appString(R.string.chat_chat_screen_072, label)) }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalConversationQuote provides if (session != null && !sending && !readingFiles) { selected ->
            updateInput(appendConversationQuote(input, selected))
            showAgents = false
            showFiles = false
            showBtw = false
            scope.launch {
                androidx.compose.runtime.withFrameNanos { }
                composerFocus.requestFocus()
                keyboard?.show()
            }
        } else null,
    ) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.chat_back)) } },
                title = {
                    Box {
                    Row(
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(
                            enabled = session != null,
                            onClickLabel = appString(R.string.chat_chat_screen_071),
                        ) { headerMenu = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (session != null) ProviderBadge(session.provider, running = session.isRunning, waiting = session.isWaiting, size = 34.dp)
                        Spacer(Modifier.size(10.dp))
                        Column {
                            Text(session?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(session?.provider?.let(Providers::label), session?.cwd?.let { shortPath(it) }).joinToString(" · "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    DropdownMenu(
                        modifier = dev.remotecode.ui.common.hapticArea(),
                        expanded = headerMenu,
                        onDismissRequest = { headerMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_rename_short)) },
                            leadingIcon = { Icon(Icons.Rounded.EditNote, null) },
                            onClick = { headerMenu = false; renaming = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_chat_screen_070)) },
                            leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                            enabled = !session?.cwd.isNullOrBlank(),
                            onClick = { copySessionValue(appString(R.string.chat_chat_screen_069), session?.cwd.orEmpty()) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_chat_screen_068)) },
                            leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                            onClick = { copySessionValue(appString(R.string.chat_chat_screen_067), sessionId) },
                        )
                        session?.externalId?.takeIf { it.isNotBlank() && it != sessionId }?.let { pcId ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_chat_screen_066)) },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                                onClick = { copySessionValue(appString(R.string.chat_chat_screen_065), pcId) },
                            )
                        }
                    }
                    }
                },
                actions = {
                    Box {
                        val cur = dev.remotecode.ui.common.modeVisual(effectiveMode)
                        IconButton(onClick = { modeMenu = true }, enabled = modes.isNotEmpty()) { Icon(cur.icon, stringResource(R.string.chat_chat_screen_064, modeLabel), tint = cur.color) }
                        DropdownMenu(modifier = dev.remotecode.ui.common.hapticArea(), expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                            Text(stringResource(R.string.chat_chat_screen_063), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            visibleModes.forEach { m ->
                                val v = dev.remotecode.ui.common.modeVisual(m.id)
                                val selected = m.id == effectiveMode
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Surface(shape = CircleShape, color = v.color.copy(alpha = if (selected) 1f else 0.18f), modifier = Modifier.size(34.dp)) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(v.icon, null, tint = if (selected) Color.White else v.color, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    },
                                    text = {
                                        Column {
                                            Text(localizedModeLabel(m), color = if (selected) v.color else Color.Unspecified, fontWeight = if (selected) FontWeight.SemiBold else null)
                                            Text(localizedModeDescription(m), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = {
                                        modeMenu = false
                                        haptics.tick()
                                        act { repo.api.patch(sessionId, mode = m.id) }
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { moreMenu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.chat_more)) }
                        DropdownMenu(modifier = dev.remotecode.ui.common.hapticArea(), expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_chat_screen_062, modelLabel(session?.model, session?.effort, defaults))) },
                                leadingIcon = { Icon(if (session?.fastModeActive == true) Icons.Rounded.Bolt else Icons.Rounded.Memory, if (session?.fastModeActive == true) stringResource(R.string.chat_fast_mode_on) else null) },
                                onClick = { moreMenu = false; pickingModel = true },
                            )
                            if (session != null && (session.provider == "claude" || session.provider == "codex")) {
                                val fastOn = session.fastModeActive
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(stringResource(R.string.chat_fast_mode_state, if (fastOn) stringResource(R.string.chat_on) else if (session.fastMode == null) stringResource(R.string.chat_chat_screen_061) else stringResource(R.string.chat_off)))
                                            Text(
                                                when {
                                                    !fastSupported -> stringResource(R.string.chat_chat_screen_060)
                                                    fastOn && session.fastModeState == "cooldown" -> stringResource(R.string.chat_chat_screen_059)
                                                    else -> stringResource(R.string.chat_chat_screen_058)
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.Bolt, null, tint = if (fastOn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant) },
                                    trailingIcon = { androidx.compose.material3.Switch(checked = fastOn, onCheckedChange = null, enabled = fastSupported || fastOn) },
                                    enabled = fastSupported || fastOn,
                                    onClick = { moreMenu = false; act { repo.api.patch(sessionId, fastMode = !fastOn) } },
                                )
                            }
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_chat_screen_057, ThinkingModes.label(display.thinking))) },
                                leadingIcon = { Icon(Icons.Rounded.Lightbulb, null) },
                                onClick = {
                                    val next = when (display.thinking) { "full" -> "summary"; "summary" -> "off"; else -> "full" }
                                    scope.launch { repo.settingsStore.setThinking(next) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (display.toolDetail) stringResource(R.string.chat_chat_screen_056) else stringResource(R.string.chat_chat_screen_055)) },
                                leadingIcon = { Icon(Icons.Rounded.Build, null) },
                                onClick = { scope.launch { repo.settingsStore.setToolDetail(!display.toolDetail) } },
                            )
                            WrapMenuItem(R.string.settings_wrap_tool_input, display.toolWrap.input) { on ->
                                scope.launch { repo.settingsStore.setToolInputWrap(on) }
                            }
                            WrapMenuItem(R.string.settings_wrap_tool_output, display.toolWrap.output) { on ->
                                scope.launch { repo.settingsStore.setToolOutputWrap(on) }
                            }
                            WrapMenuItem(R.string.settings_wrap_files_and_diffs, settings?.codeWrap ?: true) { on ->
                                scope.launch { repo.settingsStore.setCodeWrap(on) }
                            }
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(R.string.chat_chat_screen_054))
                                        Text(
                                            if (footerDisplay.visibleCount == 0) stringResource(R.string.chat_chat_screen_053) else stringResource(R.string.chat_chat_screen_052, footerDisplay.visibleCount),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = { Icon(Icons.Rounded.Visibility, null) },
                                onClick = { moreMenu = false; showFooterDisplay = true },
                            )
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_chat_screen_051, agents.size)) },
                                leadingIcon = { Icon(Icons.Rounded.SmartToy, null) },
                                onClick = { moreMenu = false; showAgents = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_chat_screen_050, remember(edits) { changedFileCount(edits) })) },
                                leadingIcon = { Icon(Icons.Rounded.EditNote, null) },
                                onClick = { moreMenu = false; showFiles = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_terminal)) },
                                leadingIcon = { Icon(Icons.Rounded.Terminal, null) },
                                onClick = { moreMenu = false; onTerminals(session?.cwd.orEmpty()) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_chat_screen_049)) },
                                leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) },
                                enabled = !session?.cwd.isNullOrBlank(),
                                onClick = { moreMenu = false; onProjectFiles() },
                            )
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            DropdownMenuItem(text = { Text(stringResource(R.string.chat_rename)) }, onClick = { moreMenu = false; renaming = true })
                            if (session?.watching == true) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.chat_chat_screen_048)) }, onClick = { moreMenu = false; confirmTakeover = true })
                            }
                            if (session?.live == true) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.chat_chat_screen_047)) }, onClick = { moreMenu = false; act { repo.api.detach(sessionId) } })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.chat_reload)) }, onClick = { moreMenu = false; scope.launch { repo.loadTimeline(sessionId, force = true) } })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                inputFocusRequester = composerFocus,
                statusBar = {
                    PlanPanel(session?.plan, running, sessionId)
                },
                session = session,
                footerDisplay = footerDisplay,
                onModel = { pickingModel = true },
                onContext = { showContext = true },
                onCache = { showCache = true },
                onGit = { showGit = true },
                onVoice = {
                    runCatching {
                        voice.launch(
                            android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, speechLanguage)
                                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, appString(R.string.chat_chat_screen_046)),
                        )
                    }.onFailure { scope.launch { snackbar.showSnackbar(appString(R.string.chat_chat_screen_045)) } }
                },
                input = input,
                onInput = ::updateInput,
                commands = commands,
                paletteOpen = paletteOpen,
                onPalette = { paletteOpen = it },
                onPickCommand = { c ->
                    paletteOpen = false
                    updateInput((if (c.kind == "skill" && session?.provider == "codex") "$" else "/") + c.name + " ")
                },
                attachments = attachments,
                fileAttachments = fileAttachments,
                codeQuotes = codeQuotes,
                onRemoveCodeQuote = { quote -> quoteIdentity?.let { CodeQuoteDrafts.remove(it, sessionId, quote.id) } },
                attachmentStatus = if (readingFiles) appString(R.string.chat_chat_screen_044) else uploadStatus,
                readingFiles = readingFiles,
                running = running,
                sending = sending,
                connected = conn is ConnState.Connected,
                onPick = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                onSend = { send() },
                onSendNow = { send(now = true) },
                onStop = { haptics.reject(); act { repo.api.interrupt(sessionId) } },
                onUsage = { showUsage = true },
                onBtw = if (session?.provider == "claude" || session?.provider == "codex") {
                    {
                        haptics.tick()
                        // 本文だけを /btw に渡し、画像は本題用に残す。
                        val typed = input.trim()
                        if (typed.isNotEmpty() && !typed.startsWith("/")) {
                            btwAsk = typed
                            updateInput("")
                        }
                        showBtw = true
                    }
                } else null,
                btwPending = btwItems.any { it.status == "pending" },
                btwUnread = !showBtw && btwSeenId.isNotEmpty() && btwLastDone != null && btwLastDone != btwSeenId,
            )
        },
    ) { pad ->
        val canRewind = session?.provider == "claude" && session.watching != true
        Box(Modifier.padding(pad).fillMaxSize()) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalRewind provides if (canRewind) { e -> rewindTarget = e } else null,
                LocalDeliverNow provides { act { repo.api.interrupt(sessionId) } },
                LocalNextStep provides NextStep(if (conn is ConnState.Connected) nextStepId else null, ::reply),
                LocalCrossSessionReply provides if (session != null && conn is ConnState.Connected && !sending && !readingFiles) { prompt ->
                    if (prompt.isNotBlank()) {
                        updateInput(if (input.isBlank()) prompt else "$input\n\n$prompt")
                        scope.launch {
                            androidx.compose.runtime.withFrameNanos { }
                            composerFocus.requestFocus()
                            keyboard?.show()
                        }
                    }
                } else null,
                androidx.compose.ui.platform.LocalUriHandler provides linkHandler,
            ) {
            LazyColumn(
                state = list,
                reverseLayout = true,
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (running) {
                    item(key = "__status") {
                        WorkingRow(waiting = session?.isWaiting == true, modifier = Modifier.animateItem())
                    }
                }
                items(entries.asReversed(), key = { it.id }, contentType = { it.kind }) { e ->
                    EntryView(e, respond, modifier = Modifier.animateItem(), display = display, children = children)
                }
                if (timeline.loading && entries.isEmpty()) {
                    item(key = "__loading") {
                        Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    }
                }
                timeline.error?.let { err ->
                    item(key = "__error") {
                        Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
                    }
                }
            }
            }
            val listOffset = if (running) 1 else 0
            ConversationRail(list, entries, listOffset, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            ScrollHaptics(list, entries, listOffset)
            AnimatedVisibility(
                visible = !follow && entries.isNotEmpty(),
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 36.dp, bottom = 16.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MessageJumpButtons(list, entries, listOffset)
                    SmallFloatingActionButton(onClick = {
                        follow = true
                        scope.launch { list.animateScrollToItem(0) }
                    }) { Icon(Icons.Rounded.KeyboardDoubleArrowDown, stringResource(R.string.chat_chat_screen_043)) }
                }
            }
        }
    }

    if (pickingModel && session != null) {
        ModelPicker(
            session.provider, session.model.concreteModel(), session.effort,
            onDismiss = { pickingModel = false },
            ultra = if (session.provider == "claude") session.ultra else null,
            fastMode = session.fastMode,
            fastModeState = session.fastModeState,
        ) { m, e, u, f ->
            haptics.confirm()
            pickingModel = false
            act {
                repo.api.setModel(sessionId, m, e, fastMode = f)
                // 強さの変更で Ultra が外れるため、最後に適用する。
                if (u != null && u != session.ultra) repo.api.setUltra(sessionId, u)
            }
        }
    }
    if (confirmTakeover) {
        AlertDialog(modifier = dev.remotecode.ui.common.hapticArea(), 
            onDismissRequest = { confirmTakeover = false },
            title = { Text(stringResource(R.string.chat_chat_screen_042)) },
            text = {
                Text(stringResource(R.string.chat_chat_screen_041))
            },
            confirmButton = { Button(onClick = { haptics.confirm(); confirmTakeover = false; act { repo.api.takeover(sessionId) } }) { Text(stringResource(R.string.chat_chat_screen_040)) } },
            dismissButton = { TextButton(onClick = { confirmTakeover = false }) { Text(stringResource(R.string.chat_chat_screen_039)) } },
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalUriHandler provides linkHandler) {
        if (showAgents) AgentsSheet(agents, children, display, respond) { showAgents = false }
        if (showFiles) ChangedFilesSheet(edits, display.toolWrap) { showFiles = false }
    }
    if (showContext) ContextSheet(sessionId) { showContext = false }
    if (showCache) CacheSheet(sessionId) { showCache = false }
    if (showUsage && session != null) UsageSheet(session.provider) { showUsage = false }
    if (showGit && session != null) GitSheet(sessionId) { showGit = false }
    if (showFooterDisplay) {
        FooterDisplayDialog(
            initial = footerDisplay,
            saving = savingFooterDisplay,
            onDismiss = { if (!savingFooterDisplay) showFooterDisplay = false },
            onApply = { value ->
                savingFooterDisplay = true
                scope.launch {
                    try {
                        repo.settingsStore.setFooterDisplay(value)
                        showFooterDisplay = false
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        snackbar.showSnackbar(appString(R.string.chat_chat_screen_038))
                    } finally {
                        savingFooterDisplay = false
                    }
                }
            },
        )
    }
    if (showBtw) {
        BtwSheet(
            sessionId = sessionId,
            initial = btwAsk,
            onDismiss = {
                showBtw = false
                btwAsk = null
                btwSeenId = btwLastDone ?: "-"
            },
            onUseInChat = { q ->
                updateInput(if (input.isBlank()) q else "$input\n$q")
                showBtw = false
                btwAsk = null
                btwSeenId = btwLastDone ?: "-"
            },
        )
    }
    linkTarget?.let { l -> LinkSheet(l, sessionId) { linkTarget = null } }
    rewindTarget?.let { target ->
        var files by remember(target.id) { mutableStateOf(false) }
        AlertDialog(modifier = dev.remotecode.ui.common.hapticArea(), 
            onDismissRequest = { rewindTarget = null },
            title = { Text(stringResource(R.string.chat_chat_screen_037)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.chat_chat_screen_036))
                    Text(
                        "「${target.text?.lineSequence()?.firstOrNull().orEmpty().take(60)}」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { files = !files },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(checked = files, onCheckedChange = { files = it })
                        Column {
                            Text(stringResource(R.string.chat_chat_screen_035))
                            Text(stringResource(R.string.chat_chat_screen_034), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    haptics.confirm()
                    rewindTarget = null
                    act {
                        val r = repo.api.rewind(sessionId, target.id, files)
                        repo.loadTimeline(sessionId, force = true)
                        if (input.isBlank()) updateInput(r.text)
                        when {
                            r.filesError != null -> snackbar.showSnackbar(appString(R.string.chat_chat_screen_033, r.filesError))
                            r.filesChanged != null -> snackbar.showSnackbar(appString(R.string.chat_chat_screen_032, r.filesChanged.size))
                            else -> snackbar.showSnackbar(appString(R.string.chat_chat_screen_031))
                        }
                    }
                }) { Text(stringResource(R.string.chat_chat_screen_030)) }
            },
            dismissButton = { TextButton(onClick = { rewindTarget = null }) { Text(stringResource(R.string.chat_cancel_short)) } },
        )
    }

    if (renaming && session != null) {
        var title by remember { mutableStateOf(session.title) }
        AlertDialog(modifier = dev.remotecode.ui.common.hapticArea(), 
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.chat_rename)) },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = { Button(onClick = { renaming = false; act { repo.api.patch(sessionId, title = title) } }) { Text(stringResource(R.string.chat_save)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.chat_cancel_short)) } },
        )
    }
    }
}

@Composable
private fun StatusStrip(session: dev.remotecode.data.SessionInfo, onModel: () -> Unit) {
    val ctx = session.context
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            onClick = onModel,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Memory, null, Modifier.size(15.dp))
                Spacer(Modifier.size(5.dp))
                if (session.fastModeActive) {
                    Icon(Icons.Rounded.Bolt, stringResource(R.string.chat_fast_mode_on), Modifier.size(15.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.size(3.dp))
                }
                Text(modelLabel(session.model, session.effort, dev.remotecode.ui.common.pcDefaults(session.provider)), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            if (ctx != null && ctx.max > 0) {
                val color = when {
                    ctx.percent >= 85 -> MaterialTheme.colorScheme.error
                    ctx.percent >= 65 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.chat_context), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(
                        "${ctx.percent}% · ${tokens(ctx.used)} / ${tokens(ctx.max)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                    )
                }
                Spacer(Modifier.size(3.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { ctx.percent / 100f },
                    color = color,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            } else {
                Text(stringResource(R.string.chat_chat_screen_029), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun tokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1000 -> "${n / 1000}k"
    else -> "$n"
}

@Composable
private fun WorkingRow(waiting: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LoadingIndicator(Modifier.size(36.dp))
        Spacer(Modifier.size(10.dp))
        Text(
            if (waiting) stringResource(R.string.chat_chat_screen_028) else stringResource(R.string.chat_chat_screen_027),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun prettyModel(model: String?): String {
    if (model.isNullOrBlank()) return appString(R.string.chat_chat_screen_026)
    val m = model.removePrefix("claude-")
    val parts = m.split("-")
    if (parts.size >= 2 && parts[0].all { it.isLetter() } && parts.drop(1).all { p -> p.all { it.isDigit() } }) {
        return parts[0].replaceFirstChar { it.uppercase() } + " " + parts.drop(1).joinToString(".")
    }
    if (m.startsWith("gpt", ignoreCase = true)) return "GPT" + m.drop(3)
    return m.replaceFirstChar { it.uppercase() }
}

@Composable
private fun Composer(
    inputFocusRequester: FocusRequester,
    statusBar: @Composable () -> Unit,
    session: dev.remotecode.data.SessionInfo?,
    footerDisplay: ChatFooterDisplay,
    onModel: () -> Unit,
    onContext: () -> Unit,
    onCache: () -> Unit,
    onGit: () -> Unit,
    onVoice: () -> Unit,
    input: String,
    onInput: (String) -> Unit,
    commands: List<CommandInfo>?,
    paletteOpen: Boolean,
    onPalette: (Boolean) -> Unit,
    onPickCommand: (CommandInfo) -> Unit,
    attachments: MutableList<Attachment>,
    fileAttachments: MutableList<SelectedFile>,
    codeQuotes: List<CodeQuote>,
    onRemoveCodeQuote: (CodeQuote) -> Unit,
    attachmentStatus: String?,
    readingFiles: Boolean,
    running: Boolean,
    sending: Boolean,
    connected: Boolean,
    onPick: () -> Unit,
    onPickFiles: () -> Unit,
    onSend: () -> Unit,
    onSendNow: () -> Unit,
    onStop: () -> Unit,
    onUsage: () -> Unit = {},
    /** null なら /btw を表示しない。 */
    onBtw: (() -> Unit)? = null,
    btwPending: Boolean = false,
    btwUnread: Boolean = false,
) {
    var plusMenu by remember { mutableStateOf(false) }
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val fieldColor = if (light) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainerHigh
    val pillColor = if (light) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(start = 12.dp, end = 12.dp, top = 4.dp)) {
            statusBar()
            if (!connected) {
                Text(
                    stringResource(R.string.chat_chat_screen_025),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                )
            }
            val typed = Regex("""^[/$]([^\s]*)$""").find(input)?.groupValues?.get(1)
            if (paletteOpen || typed != null) {
                CommandSuggestions(commands, typed ?: "", onPick = onPickCommand, onClose = { onPalette(false) })
            }
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = fieldColor,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)) {
                    CodeQuotePreviews(codeQuotes, enabled = !readingFiles, onRemove = onRemoveCodeQuote)
                    if (attachments.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 4.dp)) {
                            items(attachments.toList()) { a ->
                                Box {
                                    Image(
                                        a.preview.asImageBitmap(),
                                        contentDescription = appString(R.string.chat_chat_screen_024),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)),
                                    )
                                    FilledIconButton(
                                        onClick = { attachments.remove(a) },
                                        modifier = Modifier.size(22.dp).align(Alignment.TopEnd),
                                    ) { Icon(Icons.Rounded.Close, stringResource(R.string.chat_remove), Modifier.size(14.dp)) }
                                }
                            }
                        }
                    }
                    if (fileAttachments.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 4.dp)) {
                            items(fileAttachments.toList(), key = { it.uri.toString() }) { file ->
                                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.widthIn(min = 180.dp, max = 230.dp)) {
                                    Row(Modifier.padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Rounded.Description, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                            Text(file.size?.let(::bytesText) ?: stringResource(R.string.chat_chat_screen_023), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        IconButton(onClick = { fileAttachments.remove(file) }, enabled = !readingFiles, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, stringResource(R.string.chat_chat_screen_022, file.name), Modifier.size(18.dp)) }
                                    }
                                }
                            }
                        }
                    }
                    attachmentStatus?.let { status ->
                        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LoadingIndicator(Modifier.size(18.dp))
                            Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    TextField(
                        value = input,
                        onValueChange = onInput,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = ChatFont),
                        placeholder = { Text(if (codeQuotes.isNotEmpty()) stringResource(R.string.chat_chat_screen_021) else if (running) stringResource(R.string.chat_chat_screen_020) else stringResource(R.string.chat_chat_screen_019), style = MaterialTheme.typography.bodyLarge) },
                        minLines = 1,
                        maxLines = 8,
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(inputFocusRequester),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                        Box {
                            androidx.compose.material3.FilledTonalIconButton(
                                onClick = { plusMenu = true },
                                enabled = !readingFiles,
                                shapes = IconButtonDefaults.shapes(),
                                modifier = Modifier.size(40.dp),
                            ) { Icon(Icons.Rounded.Add, stringResource(R.string.chat_chat_screen_018)) }
                            DropdownMenu(modifier = dev.remotecode.ui.common.hapticArea(), expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_chat_screen_017)) },
                                    leadingIcon = { Icon(Icons.Rounded.AddPhotoAlternate, null) },
                                    onClick = { plusMenu = false; onPick() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_chat_screen_016)) },
                                    leadingIcon = { Icon(Icons.Rounded.AttachFile, null) },
                                    enabled = fileAttachments.size < MAX_FILE_ATTACHMENTS,
                                    onClick = { plusMenu = false; onPickFiles() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_chat_screen_015)) },
                                    leadingIcon = { Text("/", style = MaterialTheme.typography.titleMedium) },
                                    onClick = { plusMenu = false; onPalette(true) },
                                )
                            }
                        }
                        Spacer(Modifier.size(6.dp))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Surface(
                            onClick = onModel,
                            enabled = !sending,
                            shape = RoundedCornerShape(50),
                            color = pillColor,
                        ) {
                            val def = dev.remotecode.ui.common.pcDefaults(session?.provider)
                            val m = session?.model.concreteModel()?.let { prettyModel(it) } ?: def?.model ?: prettyModel(null)
                            val e = session?.effort ?: def?.effort
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                                if (session?.fastModeActive == true) {
                                    Icon(
                                        Icons.Rounded.Bolt,
                                        if (session.fastModeState == "cooldown") stringResource(R.string.chat_chat_screen_014) else stringResource(R.string.chat_fast_mode_on),
                                        Modifier.size(17.dp),
                                        tint = MaterialTheme.colorScheme.tertiary,
                                    )
                                    Spacer(Modifier.size(4.dp))
                                }
                                Text(
                                    m,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                val effortUltra = e.equals("ultra", ignoreCase = true)
                                if (e != null && !effortUltra) {
                                    Spacer(Modifier.size(8.dp))
                                    dev.remotecode.ui.common.EffortBars(e, height = 12.dp)
                                    Spacer(Modifier.size(4.dp))
                                    Text(
                                        dev.remotecode.ui.common.effortLabel(e),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = dev.remotecode.ui.common.effortColor(e),
                                        maxLines = 1,
                                    )
                                }
                                if (session?.ultra == true || effortUltra) {
                                    Spacer(Modifier.size(6.dp))
                                    dev.remotecode.ui.common.UltraText("ultra", MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                        }
                        if (onBtw != null) {
                            Spacer(Modifier.size(6.dp))
                            Box(contentAlignment = Alignment.Center) {
                                androidx.compose.material3.FilledTonalIconButton(
                                    onClick = onBtw,
                                    enabled = !sending,
                                    shapes = IconButtonDefaults.shapes(),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    ),
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    if (btwPending) {
                                        LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    } else {
                                        Icon(
                                            Icons.Rounded.QuestionAnswer,
                                            if (input.isNotBlank()) stringResource(R.string.chat_chat_screen_013) else stringResource(R.string.chat_chat_screen_012),
                                            Modifier.size(20.dp),
                                        )
                                    }
                                }
                                if (btwUnread && !btwPending) {
                                    androidx.compose.material3.Badge(Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp))
                                }
                            }
                        }
                        val canSend = input.isNotBlank() || attachments.isNotEmpty() || fileAttachments.isNotEmpty() || codeQuotes.isNotEmpty()
                        if (running && canSend) {
                            androidx.compose.material3.FilledTonalIconButton(
                                onClick = onSendNow,
                                enabled = !sending && !readingFiles,
                                shapes = IconButtonDefaults.shapes(),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                ),
                                modifier = Modifier.size(44.dp),
                            ) { Icon(Icons.Rounded.Bolt, stringResource(R.string.chat_chat_screen_011)) }
                            Spacer(Modifier.size(6.dp))
                        } else {
                            IconButton(onClick = onVoice, enabled = !sending && !readingFiles) { Icon(Icons.Rounded.Mic, stringResource(R.string.chat_chat_screen_010)) }
                        }
                        if (running && !canSend) {
                            FilledIconButton(
                                onClick = onStop,
                                shapes = IconButtonDefaults.shapes(),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                                modifier = Modifier.size(44.dp),
                            ) { Icon(Icons.Rounded.Stop, stringResource(R.string.chat_chat_screen_009)) }
                        } else {
                            FilledIconButton(
                                onClick = onSend,
                                enabled = canSend && !sending && !readingFiles,
                                shapes = IconButtonDefaults.shapes(),
                                modifier = Modifier.size(44.dp),
                            ) {
                                if (sending) LoadingIndicator(Modifier.size(24.dp)) else Icon(Icons.Rounded.ArrowUpward, stringResource(R.string.chat_send))
                            }
                        }
                    }
                }
            }
            ChatFooter(session, footerDisplay, onContext, onCache, onUsage, onGit)
        }
    }
}

@Composable
private fun ChatFooter(session: dev.remotecode.data.SessionInfo?, display: ChatFooterDisplay, onContext: () -> Unit, onCache: () -> Unit, onUsage: () -> Unit, onGit: () -> Unit) {
    if (session == null || display.visibleCount == 0) return
    val repo = RemoteCodeApp.instance.repository
    val meta by repo.meta.collectAsState()
    val gitStatuses by repo.git.collectAsState()
    val rate = meta?.usdJpy ?: 150.0
    val claude = session.provider == "claude"
    val parts = buildList {
        if (!display.stats) return@buildList
        session.stats?.costUsd?.let { usd ->
            val yen = usd * rate
            add("$" + (if (usd < 10) "%.3f" else "%.2f").format(usd) + "（¥" + "%,.0f".format(yen) + "）" + if (session.stats.partial) "+" else "")
        }
        // 単価不明時は料金の代わりにトークン数を出す。
        if (session.stats?.costUsd == null) session.stats?.tokens?.takeIf { it.input + it.output > 0 }?.let { t ->
            val hit = if (t.input > 0) appString(R.string.chat_chat_screen_008, (t.cached * 100 / t.input)) else ""
            add(appString(R.string.chat_chat_screen_007, shortTokens(t.input), hit, shortTokens(t.output)).replace("（ ", "（"))
        }
        session.stats?.takeIf { it.bytes > 0 }?.let { add(appString(R.string.chat_chat_screen_006, bytesText(it.bytes))) }
    }
    val hasContext = (session.context?.max ?: 0) > 0
    val canContext = session.provider in setOf("claude", "codex", "antigravity")
    val gitStatus = gitStatuses[session.id]
    val visible = display.orderedItems.filter { item ->
        display.isVisible(item) && when (item) {
            ChatFooterItem.CONTEXT -> canContext || hasContext
            ChatFooterItem.CACHE -> claude || session.stats?.cache != null
            ChatFooterItem.USAGE -> true
            ChatFooterItem.STATS -> parts.isNotEmpty()
            ChatFooterItem.GIT -> gitStatus?.isRepo == true || gitStatus?.error != null
        }
    }
    if (visible.isEmpty()) return
    // コンパクト項目は折り返し、統計と Git は一行ずつ表示する。
    val compact = setOf(ChatFooterItem.CONTEXT, ChatFooterItem.CACHE, ChatFooterItem.USAGE)
    val groups = mutableListOf<MutableList<ChatFooterItem>>()
    visible.forEach { item ->
        if (item in compact && groups.lastOrNull()?.lastOrNull() in compact) groups.last().add(item)
        else groups.add(mutableListOf(item))
    }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp) {
        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            groups.forEach { group ->
                androidx.compose.runtime.key(group.first().id) {
                    when (group.first()) {
                        ChatFooterItem.GIT -> GitStatusBar(session.id, session.cwd, onGit)
                        ChatFooterItem.STATS -> Text(
                            parts.joinToString("  ·  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        )
                        else -> androidx.compose.foundation.layout.FlowRow(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            group.forEach { item ->
                                androidx.compose.runtime.key(item.id) {
                                    when (item) {
                                        ChatFooterItem.CONTEXT -> ContextRing(session.context, if (canContext) onContext else ({}))
                                        ChatFooterItem.CACHE -> CachePill(session.stats?.cache, onCache, compact = true)
                                        ChatFooterItem.USAGE -> UsagePill(session.provider, onUsage)
                                        else -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun shortTokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1f".format(n / 1_000_000.0).removeSuffix(".0") + "M"
    n >= 1_000 -> "${n / 1000}k"
    else -> n.toString()
}

private fun bytesText(n: Long): String = when {
    n >= 1_048_576 -> "%.1f MB".format(n / 1_048_576.0)
    n >= 1024 -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
}

@Composable
private fun ContextRing(ctx: dev.remotecode.data.ContextUsage?, onClick: () -> Unit) {
    val known = ctx != null && ctx.max > 0
    val percent = if (known) ctx!!.percent else 0
    val color = when {
        !known -> MaterialTheme.colorScheme.onSurfaceVariant
        percent >= 85 -> MaterialTheme.colorScheme.error
        percent >= 65 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.CircularProgressIndicator(
                progress = { percent / 100f },
                color = color,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                strokeWidth = 2.dp,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.size(5.dp))
            Text(
                if (known) "$percent%  ${shortTokens(ctx!!.used)}/${shortTokens(ctx.max)}" else "–%",
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

@Composable
private fun CommandSuggestions(commands: List<CommandInfo>?, query: String, onPick: (CommandInfo) -> Unit, onClose: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    val q = (query.ifBlank { filter }).lowercase()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chat_chat_screen_005), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.chat_close), Modifier.size(18.dp)) }
            }
            if (query.isBlank()) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text(stringResource(R.string.chat_chat_screen_004)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
            when {
                commands == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(32.dp)) }
                else -> {
                    val shown = commands.filter { q.isBlank() || it.name.lowercase().contains(q) || it.description.lowercase().contains(q) }
                        .sortedBy { if (it.name.lowercase().startsWith(q)) 0 else 1 }
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(shown, key = { it.kind + it.name }) { c ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(c) }.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        (if (c.kind == "skill") "$" else "/") + c.name + if (c.argumentHint.isNotBlank()) " " + c.argumentHint else "",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = dev.remotecode.ui.theme.Mono),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (c.description.isNotBlank()) {
                                        Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Text(
                                    when (c.kind) { "skill" -> stringResource(R.string.chat_chat_screen_003); "action" -> stringResource(R.string.chat_chat_screen_002); else -> "" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                        if (shown.isEmpty()) item { Text(stringResource(R.string.chat_chat_screen_001), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

private suspend fun loadAttachment(context: Context, uri: Uri): Attachment? = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return@runCatching null
        val scale = 1600f / maxOf(raw.width, raw.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true) else raw
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val preview = Bitmap.createScaledBitmap(bmp, 160, (160f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
        Attachment(ImagePayload("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)), preview)
    }.getOrNull()
}
