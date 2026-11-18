package dev.remotecode.ui.files

import dev.remotecode.R

import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import dev.remotecode.ui.common.AppDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ConnState
import dev.remotecode.data.ProjectDirectory
import dev.remotecode.data.ProjectEntryInfo
import dev.remotecode.data.ProjectFileEntry
import dev.remotecode.data.ServerConfig
import dev.remotecode.data.connectionIdentity
import dev.remotecode.data.projectChildPath
import dev.remotecode.data.projectFileSize
import dev.remotecode.data.validProjectEntryName
import dev.remotecode.ui.common.FileTypeIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ProjectFilesScreen(sessionId: String, onBack: () -> Unit, onEdit: (String) -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val settings by repo.settings.collectAsState()
    val route by repo.route.collectAsState()
    val connection by repo.connection.collectAsState()
    val targetIdentity = remember(settings?.server, route, connection) { repo.api.server?.connectionIdentity() }
    val connected = connection is ConnState.Connected
    var path by remember(sessionId, targetIdentity) { mutableStateOf("") }
    var directory by remember(sessionId, targetIdentity) { mutableStateOf<ProjectDirectory?>(null) }
    var loadedServer by remember(sessionId, targetIdentity) { mutableStateOf<ServerConfig?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember(sessionId, targetIdentity) { mutableStateOf("") }
    var addMenu by remember(sessionId, targetIdentity) { mutableStateOf(false) }
    var creating by remember(sessionId, targetIdentity) { mutableStateOf<String?>(null) }
    var createDirectory by remember(sessionId, targetIdentity) { mutableStateOf<ProjectDirectory?>(null) }
    var createServer by remember(sessionId, targetIdentity) { mutableStateOf<ServerConfig?>(null) }
    var newName by remember(sessionId, targetIdentity) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var createError by remember(sessionId, targetIdentity) { mutableStateOf<String?>(null) }
    var selected by remember(sessionId, targetIdentity) { mutableStateOf<ProjectFileEntry?>(null) }
    var selectedInfo by remember(sessionId, targetIdentity) { mutableStateOf<ProjectEntryInfo?>(null) }
    var selectedServer by remember(sessionId, targetIdentity) { mutableStateOf<ServerConfig?>(null) }
    var selectedRoot by remember(sessionId, targetIdentity) { mutableStateOf<String?>(null) }
    var menuOpen by remember(sessionId, targetIdentity) { mutableStateOf(false) }
    var readingEntry by remember(sessionId, targetIdentity) { mutableStateOf(false) }
    var entryError by remember(sessionId, targetIdentity) { mutableStateOf<String?>(null) }
    var entryReload by remember { mutableIntStateOf(0) }
    var action by remember(sessionId, targetIdentity) { mutableStateOf<String?>(null) }
    var destination by remember { mutableStateOf("") }
    var actionError by remember { mutableStateOf<String?>(null) }
    var pickingFolder by remember(sessionId, targetIdentity) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val haptics = dev.remotecode.ui.common.rememberHaptics()

    fun clearSelection() { menuOpen = false; selected = null; selectedInfo = null; selectedServer = null; selectedRoot = null; action = null; pickingFolder = false }
    fun go(next: String) { if (!saving) { clearSelection(); path = next; query = ""; directory = null; loadedServer = null } }
    fun back() { if (!saving) { if (path.isNotEmpty()) go(path.substringBeforeLast('/', "")) else onBack() } }
    fun select(entry: ProjectFileEntry) { haptics.context(); selected = entry; selectedInfo = null; selectedServer = loadedServer; selectedRoot = directory?.root; entryError = null; entryReload++; menuOpen = true }
    fun open(entry: ProjectFileEntry) { if (entry.kind == "dir") go(entry.path) else onEdit(entry.path) }
    fun startCreate(kind: String) {
        val sourceDirectory = directory ?: return
        val sourceServer = loadedServer ?: return
        addMenu = false; newName = ""; createError = null
        createDirectory = sourceDirectory; createServer = sourceServer; creating = kind
    }
    fun startAction(value: String) {
        val info = selectedInfo ?: return
        menuOpen = false; actionError = null; action = value
        destination = if (value == "rename") info.name else info.path
    }
    BackHandler { if (action != null && !saving) action = null else back() }

    LaunchedEffect(sessionId, targetIdentity, path, refresh, connected, query) {
        if (!connected) { loading = false; return@LaunchedEffect }
        val target = repo.api.server ?: return@LaunchedEffect
        loading = true; error = null
        try { if (query.isNotBlank()) delay(300); directory = repo.api.projectFiles(sessionId, path, expectedServer = target, query = query); loadedServer = target }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: appString(R.string.screen_project_files_001) }
        finally { loading = false }
    }
    LaunchedEffect(selected?.path, selectedServer, selectedRoot, entryReload, connected) {
        val entry = selected ?: return@LaunchedEffect
        val target = selectedServer ?: return@LaunchedEffect
        val root = selectedRoot ?: return@LaunchedEffect
        if (!connected) return@LaunchedEffect
        readingEntry = true; entryError = null
        try {
            val info = repo.api.projectEntry(sessionId, entry.path, expectedServer = target)
            if (info.root != root) throw IllegalStateException(appString(R.string.screen_project_files_002))
            selectedInfo = info
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { entryError = e.message ?: appString(R.string.screen_project_files_003) }
        finally { readingEntry = false }
    }

    Scaffold(
        modifier = Modifier.imePadding(), snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_project_files_004), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = ::back, enabled = !saving) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.screen_project_files_005)) } },
                actions = {
                    IconButton(onClick = { refresh++ }, enabled = connected && !loading && !saving) { Icon(Icons.Rounded.Refresh, stringResource(R.string.screen_project_files_006)) }
                    Box {
                        IconButton(onClick = { addMenu = true }, enabled = connected && directory != null && !loading && !saving) { Icon(Icons.Rounded.Add, stringResource(R.string.screen_project_files_007)) }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.screen_project_files_008)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.NoteAdd, null) }, enabled = connected && !loading && !saving, onClick = { startCreate("file") })
                            DropdownMenuItem(text = { Text(stringResource(R.string.screen_project_files_009)) }, leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) }, enabled = connected && !loading && !saving, onClick = { startCreate("dir") })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            directory?.let { dir ->
                Text(dir.root, Modifier.padding(horizontal = 12.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ProjectBreadcrumbs(dir.path, !saving) { go(it) }
            }
            CompactFileSearch(query, enabled = !saving) { query = it.take(200) }
            directory?.let { dir ->
                val counts = buildList {
                    if (dir.folderCount >= 0) add(appString(R.string.screen_project_files_010, dir.folderCount))
                    if (dir.fileCount >= 0) add(appString(R.string.screen_project_files_011, dir.fileCount))
                    if (query.isNotBlank()) add(appString(R.string.screen_project_files_012, if (dir.matchingCount >= 0) dir.matchingCount.toString() else "${dir.entries.size}${if (dir.truncated) "+" else ""}"))
                }
                if (counts.isNotEmpty()) Text(counts.joinToString(" · "), Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!connected) Text(stringResource(R.string.screen_project_files_013), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let {
                Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                Button(onClick = { refresh++ }, enabled = connected, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.screen_project_files_014)) }
            }
            if (loading) Box(Modifier.fillMaxWidth().padding(4.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(24.dp)) }
            val dir = directory
            if (dir != null && error == null) {
                val entries = dir.entries
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                    if (dir.truncated) item { Text(stringResource(R.string.screen_project_files_015), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (entries.isEmpty()) item { Text(if (query.isBlank()) appString(R.string.screen_project_files_016) else appString(R.string.screen_project_files_017), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(entries, key = { it.path }) { entry ->
                        Box {
                            Row(
                                Modifier.fillMaxWidth().combinedClickable(
                                    enabled = connected && !loading && !saving, role = Role.Button,
                                    onClick = { open(entry) }, onLongClick = { select(entry) }, onLongClickLabel = appString(R.string.screen_project_files_018),
                                ).heightIn(min = 48.dp).padding(start = 12.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                FileTypeIcon(entry.name, Modifier.size(20.dp), directory = entry.kind == "dir")
                                Text(entry.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (entry.kind != "dir") Text(projectFileSize(entry.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                                    IconButton(onClick = { select(entry) }, enabled = connected && !loading && !saving, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.screen_project_files_019, entry.name), Modifier.size(18.dp)) }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            DropdownMenu(expanded = menuOpen && selected?.path == entry.path, onDismissRequest = { menuOpen = false }) {
                                Text(entry.name, Modifier.padding(horizontal = 16.dp, vertical = 6.dp).widthIn(max = 260.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val info = selectedInfo
                                if (readingEntry) Text(stringResource(R.string.screen_project_files_020), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                                if (info?.kind == "dir" && (info.fileCount != null || info.folderCount != null)) Text(stringResource(R.string.screen_project_files_021, info.folderCount ?: 0, info.fileCount ?: 0), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                                entryError?.let { message ->
                                    Text(message, Modifier.padding(horizontal = 16.dp, vertical = 4.dp).widthIn(max = 260.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    DropdownMenuItem(text = { Text(stringResource(R.string.screen_project_files_014)) }, leadingIcon = { Icon(Icons.Rounded.Refresh, null) }, onClick = { entryReload++ })
                                }
                                DropdownMenuItem(text = { Text(if (entry.kind == "dir") appString(R.string.screen_project_files_022) else appString(R.string.screen_project_files_023)) }, leadingIcon = { Icon(if (entry.kind == "dir") Icons.Rounded.Folder else Icons.Rounded.Edit, null) }, enabled = connected && !loading && !saving, onClick = { menuOpen = false; open(entry) })
                                val ready = connected && info != null && !readingEntry && entryError == null && !saving
                                DropdownMenuItem(text = { Text(stringResource(R.string.screen_project_files_024)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, enabled = ready, onClick = { startAction("rename") })
                                DropdownMenuItem(text = { Text(stringResource(R.string.screen_project_files_025)) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, enabled = ready, onClick = { startAction("copy") })
                                DropdownMenuItem(text = { Text(stringResource(R.string.screen_project_files_026)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, null) }, enabled = ready, onClick = { startAction("move") })
                                DropdownMenuItem(text = { Text(stringResource(R.string.screen_project_files_027), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) }, enabled = ready, onClick = { startAction("delete") })
                            }
                        }
                    }
                }
            }
        }
    }

    creating?.let { kind ->
        AlertDialog(
            onDismissRequest = { if (!saving) creating = null }, title = { Text(if (kind == "dir") appString(R.string.screen_project_files_009) else appString(R.string.screen_project_files_008)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text(stringResource(R.string.screen_project_files_028)) }, singleLine = true, enabled = !saving, isError = newName.isNotBlank() && !validProjectEntryName(newName))
                createError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            } },
            confirmButton = { TextButton(enabled = connected && !saving && validProjectEntryName(newName), onClick = {
                val dir = createDirectory ?: return@TextButton; val target = createServer ?: return@TextButton
                saving = true; createError = null
                scope.launch {
                    try {
                        val result = repo.api.createProjectEntry(sessionId, dir.root, projectChildPath(dir.path, newName), kind, expectedServer = target)
                        if (repo.api.server?.connectionIdentity() != target.connectionIdentity()) return@launch
                        creating = null; query = ""
                        scope.launch { runCatching { repo.refreshGit(sessionId, force = true) } }
                        saving = false
                        if (kind == "dir") go(result.path) else { refresh++; onEdit(result.path) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { createError = e.message ?: appString(R.string.screen_project_files_029) }
                    finally { saving = false }
                }
            }) { Text(if (saving) appString(R.string.screen_project_files_030) else appString(R.string.screen_project_files_031)) } },
            dismissButton = { TextButton(onClick = { creating = null }, enabled = !saving) { Text(stringResource(R.string.screen_project_files_032)) } },
        )
    }

    val info = selectedInfo
    val operation = action
    if (operation != null && info != null) {
        val title = when (operation) { "rename" -> appString(R.string.screen_project_files_024); "copy" -> appString(R.string.screen_project_files_025); "move" -> appString(R.string.screen_project_files_026); else -> appString(R.string.screen_project_files_027) }
        val valid = if (operation == "rename") validProjectEntryName(destination) else destination.isNotBlank() && destination.none { it.code < 32 }
        AlertDialog(
            onDismissRequest = { if (!saving) action = null }, icon = { Icon(if (operation == "delete") Icons.Rounded.Delete else if (operation == "copy") Icons.Rounded.ContentCopy else if (operation == "move") Icons.AutoMirrored.Rounded.DriveFileMove else Icons.Rounded.Edit, null) },
            title = { Text(if (operation == "rename") appString(R.string.screen_project_files_024) else appString(R.string.screen_project_files_033, info.name, title), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(info.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (operation == "delete") {
                    Text(if (info.symlink) appString(R.string.screen_project_files_034) else if (info.kind == "dir") appString(R.string.screen_project_files_035) else appString(R.string.screen_project_files_036))
                    if (info.kind == "dir" && !info.symlink && (info.fileCount != null || info.folderCount != null)) Text(stringResource(R.string.screen_project_files_021, info.folderCount ?: 0, info.fileCount ?: 0), style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedTextField(value = destination, onValueChange = { destination = it }, label = { Text(if (operation == "rename") appString(R.string.screen_project_files_037) else appString(R.string.screen_project_files_038)) }, singleLine = true, enabled = !saving, isError = !valid)
                    if (operation != "rename") {
                        Text(stringResource(R.string.screen_project_files_039), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { pickingFolder = true }, enabled = connected && !saving) { Icon(Icons.Rounded.Folder, null, Modifier.size(18.dp)); Text(stringResource(R.string.screen_project_files_040)) }
                    }
                }
                actionError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = connected && !saving && (operation == "delete" || valid), onClick = {
                val target = selectedServer ?: return@TextButton
                val to = if (operation == "delete") null else if (operation == "rename") projectChildPath(info.path.substringBeforeLast('/', ""), destination) else destination.trim()
                saving = true; actionError = null
                scope.launch {
                    try {
                        repo.api.projectAction(sessionId, info, operation, destination = to, expectedServer = target)
                        if (repo.api.server?.connectionIdentity() != target.connectionIdentity()) return@launch
                        action = null; clearSelection(); query = ""; refresh++
                        scope.launch { runCatching { repo.refreshGit(sessionId, force = true) } }
                        scope.launch { snackbar.showSnackbar(when (operation) { "rename" -> appString(R.string.screen_project_files_041); "copy" -> appString(R.string.screen_project_files_042); "move" -> appString(R.string.screen_project_files_043); else -> appString(R.string.screen_project_files_044) }) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { actionError = e.message ?: appString(R.string.screen_project_files_045) }
                    finally { saving = false }
                }
            }) { Text(if (saving) appString(R.string.screen_project_files_046) else title, color = if (operation == "delete") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } },
            dismissButton = { TextButton(onClick = { action = null }, enabled = !saving) { Text(stringResource(R.string.screen_project_files_032)) } },
        )
        if (pickingFolder) ProjectDestinationPicker(sessionId, info.root, selectedServer, info.path.substringBeforeLast('/', ""), onDismiss = { pickingFolder = false }) { folder ->
            val name = destination.replace('\\', '/').substringAfterLast('/').takeIf { validProjectEntryName(it) } ?: info.name
            destination = projectChildPath(folder, name); pickingFolder = false
        }
    }
}

@Composable
private fun CompactFileSearch(value: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline), color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(
                value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.weight(1f).padding(vertical = 8.dp).onFocusChanged { focused = it.isFocused },
                decorationBox = { inner -> Box { if (value.isEmpty()) Text(stringResource(R.string.screen_project_files_047), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis); inner() } },
            )
            if (value.isNotEmpty()) CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                IconButton(onClick = { onChange("") }, enabled = enabled, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, stringResource(R.string.screen_project_files_048), Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
private fun ProjectBreadcrumbs(path: String, enabled: Boolean, onGo: (String) -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onGo("") }, enabled = enabled, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp), modifier = Modifier.heightIn(min = 32.dp)) { Icon(Icons.Rounded.Folder, null, Modifier.size(16.dp)); Text(stringResource(R.string.screen_project_files_049), style = MaterialTheme.typography.labelMedium) }
            var prefix = ""
            path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
                prefix = projectChildPath(prefix, segment); val target = prefix
                Text("/", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onGo(target) }, enabled = enabled, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp), modifier = Modifier.heightIn(min = 32.dp)) { Text(segment, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun ProjectDestinationPicker(sessionId: String, root: String, server: ServerConfig?, initialPath: String, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val connection by repo.connection.collectAsState()
    val connected = connection is ConnState.Connected
    var path by remember { mutableStateOf(initialPath) }
    var query by remember { mutableStateOf("") }
    var directory by remember { mutableStateOf<ProjectDirectory?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(path, query, retry, connected) {
        if (!connected || server == null) { loading = false; return@LaunchedEffect }
        loading = true; error = null
        try {
            if (query.isNotBlank()) delay(300)
            val value = repo.api.projectFiles(sessionId, path, expectedServer = server, query = query)
            if (value.root != root) throw IllegalStateException(appString(R.string.screen_project_files_050))
            directory = value
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: appString(R.string.screen_project_files_001) }
        finally { loading = false }
    }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.screen_project_files_051)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ProjectBreadcrumbs(path, !loading) { path = it; query = ""; directory = null }
                CompactFileSearch(query) { query = it.take(200) }
                if (loading) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(24.dp)) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall); TextButton(onClick = { retry++ }) { Text(stringResource(R.string.screen_project_files_052)) } }
                val folders = directory?.entries.orEmpty().filter { it.kind == "dir" }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    if (directory?.truncated == true) item { Text(stringResource(R.string.screen_project_files_053), style = MaterialTheme.typography.bodySmall) }
                    if (!loading && folders.isEmpty() && error == null) item { Text(stringResource(R.string.screen_project_files_054), Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall) }
                    items(folders, key = { it.path }) { folder ->
                        Row(Modifier.fillMaxWidth().combinedClickable(enabled = !loading, onClick = { path = folder.path; query = ""; directory = null }).heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Folder, null, Modifier.size(20.dp)); Text(folder.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = connected && directory != null && !loading && error == null, onClick = { onChoose(directory!!.path) }) { Text(stringResource(R.string.screen_project_files_055)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.screen_project_files_032)) } },
    )
}
