package dev.remotecode.ui.chat

import dev.remotecode.R
import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Commit
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ConnState
import dev.remotecode.data.GitBranch
import dev.remotecode.data.GitDiff
import dev.remotecode.data.GitFile
import dev.remotecode.data.GitLogEntry
import dev.remotecode.data.GitStatus
import dev.remotecode.data.branchLabel
import dev.remotecode.data.connectionIdentity
import dev.remotecode.data.projectFileSize
import dev.remotecode.ui.common.AppDropdownMenu
import java.text.DateFormat
import java.util.Date
import dev.remotecode.ui.theme.Mono
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 閲覧だけでは作業ツリーやリモートを変更しない。 */
@Composable
fun GitSheet(sessionId: String, onDismiss: () -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val scope = rememberCoroutineScope()
    val states by repo.git.collectAsState()
    val settings by repo.settings.collectAsState()
    val connection by repo.connection.collectAsState()
    val route by repo.route.collectAsState()
    val sessions by repo.sessions.collectAsState()
    val openedServer = remember(sessionId) { repo.api.server?.connectionIdentity() }
    val openedCwd = remember(sessionId, openedServer) { repo.session(sessionId)?.cwd }
    val activeServer = remember(settings?.server, route, connection) { repo.api.server?.connectionIdentity() }
    val sameServer = openedServer != null && activeServer == openedServer
    val sameProject = openedCwd != null && sessions.firstOrNull { it.id == sessionId }?.cwd == openedCwd
    val status = states[sessionId]
    var fileSort by rememberSaveable(sessionId) { mutableStateOf(GitFileSort.Name) }
    var ascending by rememberSaveable(sessionId) { mutableStateOf(true) }
    val sortedFiles = remember(status?.files, fileSort, ascending) { fileSort.sorted(status?.files.orEmpty(), ascending) }
    val connected = connection is ConnState.Connected && sameServer && sameProject
    var tab by remember(sessionId, openedServer) { mutableIntStateOf(0) }
    var epoch by remember(sessionId, openedServer) { mutableIntStateOf(0) }
    var busy by remember(sessionId, openedServer) { mutableStateOf(false) }
    var error by remember(sessionId, openedServer) { mutableStateOf<String?>(null) }
    var selected by remember(sessionId, openedServer) { mutableStateOf(emptySet<String>()) }
    var message by remember(sessionId, openedServer) { mutableStateOf("") }
    var logs by remember(sessionId, openedServer) { mutableStateOf<List<GitLogEntry>?>(null) }
    var branches by remember(sessionId, openedServer) { mutableStateOf<List<GitBranch>?>(null) }
    var loadingTab by remember(sessionId, openedServer) { mutableStateOf(false) }
    var diff by remember(sessionId, openedServer) { mutableStateOf<Pair<String, Boolean>?>(null) }
    var createBranch by remember(sessionId, openedServer) { mutableStateOf(false) }
    var chooseRemote by remember(sessionId, openedServer) { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val canOperate = connected && status?.isRepo == true && status.error == null && !busy
    val draftTarget = remember(status?.files, selected) { gitDraftTarget(status?.files.orEmpty(), selected) }
    var generating by remember(sessionId, openedServer, connected, status?.root, status?.head, status?.branch, draftTarget) { mutableStateOf(false) }
    var candidate by remember(sessionId, openedServer, connected, status?.root, status?.head, status?.branch, draftTarget) { mutableStateOf<String?>(null) }

    fun validConnection(): Boolean = openedServer != null && openedCwd != null && repo.api.server?.connectionIdentity() == openedServer &&
        repo.connection.value is ConnState.Connected && repo.session(sessionId)?.cwd == openedCwd

    fun requestDraft() {
        if (!canOperate || generating || draftTarget.files.isEmpty()) return
        val target = repo.api.server?.takeIf { it.connectionIdentity() == openedServer } ?: return
        val snapshot = status ?: return
        fun verifyTarget() {
            val current = repo.git.value[sessionId]
            check(validConnection() && current?.isRepo == true && current.error == null &&
                current.root == snapshot.root && current.branch == snapshot.branch && current.head == snapshot.head &&
                gitDraftTarget(current.files, selected) == draftTarget) { appString(R.string.git_draft_changed) }
        }
        generating = true
        error = null
        scope.launch {
            try {
                repo.refreshGit(sessionId, force = true)
                verifyTarget()
                val draft = generateGitDraft(draftTarget, fetch = { path, staged ->
                    check(validConnection()) { appString(R.string.chat_git_sheet_005) }
                    repo.api.gitDiff(sessionId, path, staged, target)
                })
                verifyTarget()
                candidate = draft
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: appString(R.string.git_draft_changed) }
            finally { generating = false }
        }
    }

    fun refresh() {
        if (busy || !validConnection()) return
        busy = true
        error = null
        scope.launch {
            try { repo.refreshGit(sessionId, force = true); epoch++ }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: appString(R.string.chat_git_sheet_056) }
            finally { busy = false }
        }
    }

    fun action(
        name: String, paths: List<String> = emptyList(), commitMessage: String = "", branch: String = "",
        remote: String? = null, setUpstream: Boolean = false,
    ) {
        if (busy || !validConnection() || status?.error != null || status?.isRepo != true) return
        busy = true
        error = null
        scope.launch {
            try {
                val result = repo.gitAction(sessionId, name, paths = paths, message = commitMessage, branch = branch, remote = remote, setUpstream = setUpstream)
                if (!validConnection()) return@launch
                selected = emptySet()
                if (name == "commit") message = ""
                epoch++
                scope.launch { snackbar.showSnackbar(result.message.ifBlank { appString(R.string.chat_git_sheet_058) }) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: appString(R.string.chat_git_sheet_057) }
            finally { busy = false }
        }
    }

    LaunchedEffect(sessionId, openedServer) {
        if (validConnection()) {
            try { repo.refreshGit(sessionId) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: appString(R.string.chat_git_sheet_056) }
        }
    }
    LaunchedEffect(sessionId, tab, epoch, connected) {
        if (!connected || status?.isRepo != true || tab == 0) return@LaunchedEffect
        loadingTab = true
        try {
            if (tab == 1) logs = repo.api.gitLog(sessionId) else branches = repo.api.gitBranches(sessionId)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: appString(R.string.chat_git_sheet_055) }
        finally { loadingTab = false }
    }
    LaunchedEffect(status?.files) {
        val present = status?.files?.map { it.path }?.toSet().orEmpty()
        selected = selected.intersect(present)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        candidate?.let { draft ->
            AlertDialog(
                onDismissRequest = { candidate = null },
                title = { Text(stringResource(R.string.git_draft_title)) },
                text = { CodeBlock(draft, diff = false, maxHeight = 280, wrapLines = true) },
                confirmButton = {
                    Button(onClick = { if (validConnection()) message = draft; candidate = null }, enabled = canOperate) {
                        Text(stringResource(if (message.isBlank()) R.string.git_draft_use else R.string.git_draft_replace))
                    }
                },
                dismissButton = { TextButton(onClick = { candidate = null }) { Text(stringResource(R.string.chat_cancel_short)) } },
            )
        }
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.systemBarsPadding(),
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    TopAppBar(
                        title = { Column { Text("Git"); if (sameServer && status?.isRepo == true) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) { Icon(Icons.Rounded.AccountTree, null, Modifier.size(14.dp)); Text(status.branchLabel(), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) } } },
                        navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.chat_close)) } },
                        actions = { IconButton(onClick = ::refresh, enabled = connected && !busy) { if (busy) LoadingIndicator(Modifier.size(22.dp)) else Icon(Icons.Rounded.Refresh, stringResource(R.string.chat_git_sheet_054)) } },
                    )
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (!sameServer || !sameProject) {
                        Text(if (!sameServer) stringResource(R.string.chat_git_sheet_053) else stringResource(R.string.chat_git_sheet_052), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
                    } else {
                        val failure = error ?: status?.error
                        if (!connected) Text(stringResource(R.string.chat_git_sheet_051), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error)
                        if (failure != null) Text(failure + if (status?.isRepo == true && status.error != null) stringResource(R.string.chat_git_sheet_050) else "", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error)
                        when {
                            status == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                            !status.isRepo -> Text(if (failure == null) stringResource(R.string.chat_git_sheet_049) else stringResource(R.string.chat_git_sheet_048), Modifier.padding(20.dp))
                            else -> {
                                GitOverview(status)
                                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    FilledTonalButton(onClick = { action("fetch") }, enabled = canOperate && status.remotes.isNotEmpty(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) { GitActionLabel(Icons.Rounded.CloudDownload, appString(R.string.chat_git_sheet_047)) }
                                    FilledTonalButton(onClick = { action("pull") }, enabled = canOperate && !status.detached && status.upstream != null, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) { GitActionLabel(Icons.Rounded.ArrowDownward, appString(R.string.chat_git_sheet_046)) }
                                    Button(onClick = { if (status.upstream.isNullOrBlank()) chooseRemote = true else action("push") }, enabled = canOperate && !status.detached && status.head != null && status.remotes.isNotEmpty(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) { GitActionLabel(Icons.Rounded.CloudUpload, appString(R.string.chat_git_sheet_026)) }
                                }
                                TabRow(selectedTabIndex = tab) {
                                    listOf(appString(R.string.chat_git_sheet_045, status.changedFiles), appString(R.string.chat_git_sheet_044), appString(R.string.chat_git_sheet_043)).forEachIndexed { index, label ->
                                        val icon = when (index) { 0 -> Icons.Rounded.EditNote; 1 -> Icons.Rounded.History; else -> Icons.Rounded.AccountTree }
                                        Tab(selected = tab == index, onClick = { tab = index; error = null }, icon = { Icon(icon, null, Modifier.size(20.dp)) }, text = { Text(label) })
                                    }
                                }
                                if (loadingTab) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(28.dp)) }
                                when (tab) {
                                    0 -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        item("commit") {
                                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Rounded.Commit, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary); Text(stringResource(R.string.chat_git_sheet_042, status.stagedFiles), style = MaterialTheme.typography.titleSmall) }
                                                OutlinedTextField(value = message, onValueChange = { message = it }, label = { Text(stringResource(R.string.chat_git_sheet_041)) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, enabled = !busy)
                                                OutlinedButton(onClick = ::requestDraft, enabled = canOperate && !generating && draftTarget.files.isNotEmpty()) {
                                                    if (generating) LoadingIndicator(Modifier.size(18.dp)) else Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp))
                                                    Text(stringResource(draftTarget.scope.label, draftTarget.files.size), Modifier.padding(start = 8.dp))
                                                }
                                                if (status.conflictedFiles > 0) Text(stringResource(R.string.chat_git_sheet_040), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                                Button(onClick = { action("commit", commitMessage = message.trim()) }, enabled = canOperate && !status.detached && message.isNotBlank() && status.stagedFiles > 0 && status.conflictedFiles == 0) { GitActionLabel(Icons.Rounded.Commit, appString(R.string.chat_git_sheet_039, status.stagedFiles)) }
                                            }
                                        }
                                        if (status.files.isNotEmpty()) item("selection") {
                                            val chosen = status.files.filter { it.path in selected }
                                            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Checkbox(checked = selected.size == status.files.size, onCheckedChange = { selected = if (it) status.files.map { file -> file.path }.toSet() else emptySet() }, enabled = !busy)
                                                    Text(stringResource(R.string.chat_git_sheet_037, selected.size), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                                }
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                                    OutlinedButton(onClick = { action("stage", paths = chosen.filter { it.unstaged || it.untracked || it.conflict }.map { it.path }) }, enabled = canOperate && chosen.any { it.unstaged || it.untracked || it.conflict }) { GitActionLabel(Icons.Rounded.PlaylistAdd, appString(R.string.chat_git_sheet_036)) }
                                                    OutlinedButton(onClick = { action("unstage", paths = chosen.filter { it.staged }.map { it.path }) }, enabled = canOperate && chosen.any { it.staged }) { GitActionLabel(Icons.Rounded.PlaylistRemove, appString(R.string.chat_git_sheet_035)) }
                                                    GitSortControls(fileSort, ascending, { fileSort = it }, { ascending = !ascending })
                                                }
                                            }
                                        }
                                        items(sortedFiles, key = { it.path }) { file ->
                                            GitFileRow(file, fileSort, file.path in selected, !busy, { checked -> selected = if (checked) selected + file.path else selected - file.path }, { staged -> diff = file.path to staged })
                                        }
                                        if (status.files.isEmpty()) item("clean") { Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary); Text(stringResource(R.string.chat_git_sheet_034), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                                        item("space") { Box(Modifier.padding(bottom = 14.dp)) }
                                    }
                                    1 -> GitHistory(logs, Modifier.weight(1f))
                                    else -> Column(Modifier.weight(1f)) {
                                        TextButton(onClick = { createBranch = true }, enabled = canOperate) { GitActionLabel(Icons.Rounded.Add, appString(R.string.chat_git_sheet_033)) }
                                        LazyColumn(Modifier.fillMaxSize()) {
                                            items(branches.orEmpty(), key = { "${it.remote}:${it.name}" }) { branch ->
                                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(if (branch.current) Icons.Rounded.CheckCircle else if (branch.remote) Icons.Rounded.Cloud else Icons.Rounded.AccountTree, null, Modifier.padding(end = 10.dp).size(20.dp), tint = if (branch.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                                    Column(Modifier.weight(1f)) {
                                                        Text(branch.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                        Text(if (branch.current) stringResource(R.string.chat_git_sheet_032) else if (branch.remote) stringResource(R.string.chat_git_sheet_031) else stringResource(R.string.chat_git_sheet_030), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                    if (!branch.current && !branch.remote) TextButton(onClick = { action("checkout", branch = branch.name) }, enabled = canOperate) { GitActionLabel(Icons.Rounded.SwapHoriz, appString(R.string.chat_git_sheet_029)) }
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
        }
    }
    if (chooseRemote && sameServer && sameProject && status != null) {
        var remote by remember { mutableStateOf(status.remotes.firstOrNull { it.name == "origin" }?.name ?: status.remotes.firstOrNull()?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { chooseRemote = false }, title = { Text(stringResource(R.string.chat_git_sheet_028)) }, icon = { Icon(Icons.Rounded.CloudUpload, null) },
            text = { Column { Text(stringResource(R.string.chat_git_sheet_027, status.branchLabel())); status.remotes.forEach { item -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = remote == item.name, onClick = { remote = item.name }); Text(item.name) } } } },
            confirmButton = { Button(onClick = { chooseRemote = false; action("push", remote = remote, setUpstream = true) }, enabled = canOperate && remote.isNotBlank()) { GitActionLabel(Icons.Rounded.CloudUpload, appString(R.string.chat_git_sheet_026)) } },
            dismissButton = { TextButton(onClick = { chooseRemote = false }) { Text(stringResource(R.string.chat_cancel_short)) } },
        )
    }
    if (createBranch && sameServer && sameProject) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { createBranch = false }, title = { Text(stringResource(R.string.chat_git_sheet_025)) }, icon = { Icon(Icons.Rounded.AccountTree, null) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.chat_git_sheet_024)) }, singleLine = true) },
            confirmButton = { Button(onClick = { createBranch = false; action("branch", branch = name.trim()) }, enabled = canOperate && name.isNotBlank()) { GitActionLabel(Icons.Rounded.Add, appString(R.string.chat_git_sheet_023)) } },
            dismissButton = { TextButton(onClick = { createBranch = false }) { Text(stringResource(R.string.chat_cancel_short)) } },
        )
    }
    diff?.let { target -> GitDiffDialog(sessionId, target.first, target.second, openedServer, openedCwd) { diff = null } }
}

@Composable
private fun GitOverview(status: GitStatus) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SelectionContainer { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Icon(Icons.Rounded.Folder, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Text(status.root.orEmpty(), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) { Icon(Icons.Rounded.InsertDriveFile, null, Modifier.size(18.dp)); Text(stringResource(R.string.chat_git_sheet_022, status.changedFiles), style = MaterialTheme.typography.labelLarge) }
            Text("+${status.additions}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text("−${status.deletions}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Icon(Icons.Rounded.Cloud, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Text(status.upstream?.let { "$it  ↑${status.ahead} ↓${status.behind}" } ?: stringResource(R.string.chat_git_sheet_021), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (status.detached) Text(stringResource(R.string.chat_git_sheet_019), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (status.lineStatsPartial) Text(stringResource(R.string.chat_git_sheet_018), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (status.remotes.isEmpty()) Text(stringResource(R.string.chat_git_sheet_017), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GitSortControls(sort: GitFileSort, ascending: Boolean, onSort: (GitFileSort) -> Unit, onReverse: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            GitActionLabel(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.git_file_sort_by, stringResource(sort.label)))
        }
        AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GitFileSort.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(stringResource(choice.label)) },
                    trailingIcon = { if (choice == sort) Icon(Icons.Rounded.Check, null) },
                    onClick = { expanded = false; onSort(choice) },
                )
            }
        }
    }
    TextButton(onClick = onReverse) {
        GitActionLabel(if (ascending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
            stringResource(if (ascending) R.string.git_file_sort_ascending else R.string.git_file_sort_descending))
    }
}

@Composable
private fun GitFileRow(file: GitFile, sort: GitFileSort, selected: Boolean, enabled: Boolean, onSelect: (Boolean) -> Unit, onDiff: (Boolean) -> Unit) {
    val path = file.path.replace('\\', '/')
    val name = path.substringAfterLast('/')
    val directory = path.substringBeforeLast('/', "")
    val unstaged = file.unstaged || file.untracked || file.conflict
    val labels = listOfNotNull(
        if (file.conflict) stringResource(R.string.chat_git_sheet_016) else null,
        if (file.untracked) stringResource(R.string.chat_git_sheet_015) else null,
        if (file.staged) stringResource(R.string.chat_git_sheet_014) else null,
        if (file.unstaged) stringResource(R.string.chat_git_sheet_013) else null,
        if (file.binary) stringResource(R.string.chat_git_sheet_012) else null,
    ).joinToString(" · ")
    Surface(onClick = { onDiff(!unstaged && file.staged) }, enabled = enabled && (unstaged || file.staged), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = onSelect, enabled = enabled)
            Icon(if (file.conflict) Icons.Rounded.WarningAmber else Icons.Rounded.InsertDriveFile, null, Modifier.padding(end = 6.dp).size(18.dp), tint = if (file.conflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val location = file.originalPath?.let { "← $it" } ?: directory
                if (location.isNotBlank()) Text(location, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                if (sort == GitFileSort.Size || sort == GitFileSort.Modified) {
                    val value = if (sort == GitFileSort.Size) file.sizeBytes?.let(::projectFileSize)
                    else file.modifiedAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
                    Text("${stringResource(sort.label)} · ${value ?: "—"}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(labels, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (file.conflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("+${file.additions}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text("−${file.deletions}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (unstaged) IconButton(onClick = { onDiff(false) }, enabled = enabled) { Icon(Icons.Rounded.Difference, stringResource(R.string.chat_git_sheet_006), Modifier.size(20.dp)) }
            if (file.staged) IconButton(onClick = { onDiff(true) }, enabled = enabled) { Icon(Icons.Rounded.CheckCircle, stringResource(R.string.chat_git_sheet_007), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun GitHistory(logs: List<GitLogEntry>?, modifier: Modifier) {
    val context = LocalContext.current
    LazyColumn(modifier) {
        if (logs?.isEmpty() == true) item { Text(stringResource(R.string.chat_git_sheet_011), Modifier.padding(20.dp)) }
        items(logs.orEmpty(), key = { it.hash }) { commit ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Commit, null, Modifier.padding(end = 10.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(commit.subject, style = MaterialTheme.typography.bodyMedium)
                    Text("${commit.shortHash} · ${commit.author}", style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(commit.date.replace('T', ' ').take(19), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(appString(R.string.chat_git_sheet_010), commit.hash)) }) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.chat_git_sheet_009), Modifier.size(18.dp)) }
            }
        }
    }
}

/** ラベルで読み上げるため、アイコンは装飾扱い。 */
@Composable
private fun GitActionLabel(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, Modifier.size(18.dp))
        Text(text, maxLines = 1)
    }
}

@Composable
internal fun GitDiffDialog(sessionId: String, path: String, staged: Boolean, openedServer: dev.remotecode.data.ConnectionIdentity?, openedCwd: String?, onDismiss: () -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val settings by repo.settings.collectAsState()
    val route by repo.route.collectAsState()
    val connection by repo.connection.collectAsState()
    val sessions by repo.sessions.collectAsState()
    val activeServer = remember(settings?.server, route, connection) { repo.api.server?.connectionIdentity() }
    val sameServer = openedServer != null && activeServer == openedServer
    val sameProject = openedCwd != null && sessions.firstOrNull { it.id == sessionId }?.cwd == openedCwd
    var result by remember(sessionId, path, staged, openedServer) { mutableStateOf<GitDiff?>(null) }
    var error by remember(sessionId, path, staged, openedServer) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val wrapLines = settings?.codeWrap ?: true
    LaunchedEffect(sessionId, path, staged, openedServer, sameServer, sameProject, retry) {
        val target = repo.api.server?.takeIf { it.connectionIdentity() == openedServer } ?: return@LaunchedEffect
        if (!sameServer || !sameProject || repo.session(sessionId)?.cwd != openedCwd) return@LaunchedEffect
        result = null; error = null
        try { result = repo.api.gitDiff(sessionId, path, staged, expectedServer = target) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: appString(R.string.chat_git_sheet_008) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(modifier = Modifier.systemBarsPadding(), topBar = {
                TopAppBar(title = { Text(if (staged) stringResource(R.string.chat_git_sheet_007) else stringResource(R.string.chat_git_sheet_006), style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.chat_back)) } },
                    actions = {
                        IconToggleButton(checked = wrapLines, onCheckedChange = { on -> scope.launch { repo.settingsStore.setCodeWrap(on) } }) {
                            Icon(Icons.AutoMirrored.Rounded.WrapText, stringResource(R.string.chat_code_viewer_018, if (wrapLines) "ON" else "OFF"))
                        }
                    })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
                    SelectionContainer { Text(path, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
                    when {
                        !sameServer || !sameProject -> Text(stringResource(R.string.chat_git_sheet_005), color = MaterialTheme.colorScheme.error)
                        error != null -> Column { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error); TextButton(onClick = { retry++ }) { Text(stringResource(R.string.chat_git_sheet_004)) } }
                        result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                        else -> {
                            if (result!!.binary) Text(stringResource(R.string.chat_git_sheet_003), Modifier.padding(vertical = 8.dp))
                            if (result!!.truncated) Text(stringResource(R.string.chat_git_sheet_002), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            if (result!!.text.isBlank() && !result!!.binary) Text(stringResource(R.string.chat_git_sheet_001))
                            else DiffCodeView(result!!.text, path, wrapLines, Modifier.weight(1f).padding(bottom = 12.dp))
                        }
                    }
                }
            }
        }
    }
}
