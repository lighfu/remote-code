package dev.remotecode.ui.sessions

import dev.remotecode.R

import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ConnState
import dev.remotecode.data.Providers
import dev.remotecode.data.routeLabel
import dev.remotecode.data.SessionInfo
import dev.remotecode.ui.common.ProviderBadge
import dev.remotecode.ui.common.relativeTime
import dev.remotecode.ui.common.shortPath
import dev.remotecode.ui.common.statusLabel
import kotlinx.coroutines.launch

private var askedNotificationPermission = false

@Composable
fun SessionsScreen(onOpen: (String) -> Unit, onHistory: () -> Unit, onSettings: () -> Unit, onPairing: () -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val sessions by repo.sessions.collectAsState()
    val conn by repo.connection.collectAsState()
    val usage by repo.usage.collectAsState()
    val route by repo.route.collectAsState()
    val settings by repo.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var fabOpen by rememberSaveable { mutableStateOf(false) }
    var newFor by rememberSaveable { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<SessionInfo?>(null) }
    var renameFor by remember { mutableStateOf<SessionInfo?>(null) }
    var deleteFor by remember { mutableStateOf<SessionInfo?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val context = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !askedNotificationPermission &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotificationPermission = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(fabOpen) { fabOpen = false }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure { snackbar.showSnackbar(it.message ?: appString(R.string.screen_sessions_001)) }
        }
    }

    // 取り消し猶予中の削除を一覧から隠す。
    val pendingDelete = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val swipeHaptics = dev.remotecode.ui.common.rememberHaptics()

    fun swipeArchive(s: SessionInfo) {
        val to = !s.archived
        swipeHaptics.confirm()
        scope.launch {
            try {
                repo.api.patch(s.id, archived = to)
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: appString(R.string.screen_sessions_001))
                return@launch
            }
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(
                if (to) appString(R.string.screen_sessions_002, s.title) else appString(R.string.screen_sessions_003, s.title),
                actionLabel = appString(R.string.screen_sessions_004),
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                runCatching { repo.api.patch(s.id, archived = !to) }.onFailure { snackbar.showSnackbar(it.message ?: appString(R.string.screen_sessions_005)) }
            }
        }
    }

    /** 取り消し猶予が終わったら削除し、画面離脱でも確定する。 */
    fun swipeDelete(s: SessionInfo) {
        swipeHaptics.reject()
        pendingDelete += s.id
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val undone = try {
                snackbar.showSnackbar(
                    appString(R.string.screen_sessions_006, s.title),
                    actionLabel = appString(R.string.screen_sessions_004),
                    duration = androidx.compose.material3.SnackbarDuration.Long,
                ) == androidx.compose.material3.SnackbarResult.ActionPerformed
            } catch (e: kotlinx.coroutines.CancellationException) {
                false
            }
            if (undone) {
                pendingDelete -= s.id
                return@launch
            }
            // 画面離脱でキャンセルされても削除を完了させる。
            val err = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { repo.api.delete(s.id) }.exceptionOrNull()
            }
            if (err != null) {
                pendingDelete -= s.id
                snackbar.showSnackbar(err.message ?: appString(R.string.screen_sessions_007))
            }
        }
    }

    val visible = sessions.filter { it.archived == showArchived && it.id !in pendingDelete }
    val waiting = visible.filter { it.isWaiting }
    val running = visible.filter { it.isRunning && !it.isWaiting }
    val rest = visible.filter { !it.isWaiting && !it.isRunning }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(if (showArchived) appString(R.string.screen_sessions_008) else "Remote Code") },
                subtitle = {
                    val name = settings?.server?.name?.ifBlank { null } ?: "PC"
                    Text(
                        when (val c = conn) {
                            ConnState.Connected -> appString(R.string.screen_sessions_009, name, routeLabel(route))
                            ConnState.Connecting -> appString(R.string.screen_sessions_010, name)
                            is ConnState.Failed -> appString(R.string.screen_sessions_011, c.message)
                            ConnState.Idle -> appString(R.string.screen_sessions_012)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = { showArchived = !showArchived }) {
                        Icon(if (showArchived) Icons.Rounded.Unarchive else Icons.Rounded.Archive, if (showArchived) appString(R.string.screen_sessions_013) else appString(R.string.screen_sessions_014))
                    }
                    IconButton(onClick = onHistory) { Icon(Icons.Rounded.History, stringResource(R.string.screen_sessions_015)) }
                    IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, stringResource(R.string.screen_sessions_016)) }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            FloatingActionButtonMenu(
                expanded = fabOpen,
                button = {
                    ToggleFloatingActionButton(checked = fabOpen, onCheckedChange = { fabOpen = it }) {
                        val painter = rememberVectorPainter(if (checkedProgress > 0.5f) Icons.Rounded.Close else Icons.Rounded.Add)
                        Icon(painter, contentDescription = appString(R.string.screen_sessions_017), modifier = Modifier.animateIcon({ checkedProgress }))
                    }
                },
            ) {
                FloatingActionButtonMenuItem(
                    onClick = { fabOpen = false; newFor = Providers.CLAUDE },
                    text = { Text(stringResource(R.string.screen_sessions_018)) },
                    icon = { dev.remotecode.ui.common.BrandIcon(Providers.CLAUDE, 24.dp) },
                )
                FloatingActionButtonMenuItem(
                    onClick = { fabOpen = false; newFor = Providers.CODEX },
                    text = { Text(stringResource(R.string.screen_sessions_019)) },
                    icon = { dev.remotecode.ui.common.BrandIcon(Providers.CODEX, 24.dp) },
                )
                FloatingActionButtonMenuItem(
                    onClick = { fabOpen = false; newFor = Providers.ANTIGRAVITY },
                    text = { Text(stringResource(R.string.screen_sessions_020)) },
                    icon = { dev.remotecode.ui.common.BrandIcon(Providers.ANTIGRAVITY, 24.dp) },
                )
                FloatingActionButtonMenuItem(
                    onClick = { fabOpen = false; onHistory() },
                    text = { Text(stringResource(R.string.screen_sessions_015)) },
                    icon = { Icon(Icons.Rounded.History, null) },
                )
            }
        },
    ) { pad ->
        val prefs = remember { context.getSharedPreferences("home_view", android.content.Context.MODE_PRIVATE) }
        var tab by rememberSaveable { mutableStateOf(prefs.getString("tab", "history") ?: "history") }
        val showHistory = tab == "history" && !showArchived
        val usageHeader: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
            if (!showArchived && conn is ConnState.Connected) {
                item(key = "__usage") {
                    UsageCards(usage, onRefresh = { scope.launch { repo.refreshUsage(force = true) } }, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
        Column(Modifier.padding(pad).fillMaxSize()) {
            dev.remotecode.ui.common.AppUpdateItem(
                connected = conn is ConnState.Connected,
                onlyWhenAvailable = true,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            if (conn is ConnState.Failed || conn is ConnState.Idle) {
                PairingCard(conn, onPairing = onPairing, onRetry = { repo.reconnectNow() })
            }
            if (!showArchived) {
                HomeTabs(tab, attention = waiting.size + running.size) {
                    tab = it
                    prefs.edit().putString("tab", it).apply()
                }
            }
            if (showHistory) {
                dev.remotecode.ui.history.HistoryContent(
                    onOpen = onOpen,
                    modifier = Modifier.weight(1f),
                    reloadKey = conn is ConnState.Connected,
                    header = {
                        usageHeader()
                        item(key = "__pc_history_heading") {
                            Text(
                                stringResource(R.string.screen_sessions_021),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp).semantics { heading() },
                            )
                        }
                    },
                )
            } else {
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        refreshing = true
                        scope.launch {
                            runCatching { repo.refreshSessions() }.onFailure { snackbar.showSnackbar(it.message ?: appString(R.string.screen_sessions_022)) }
                            if (conn !is ConnState.Connected) repo.reconnectNow()
                            refreshing = false
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxSize(),
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        usageHeader()
                        if (!showArchived) {
                            item(key = "__app_conversations_heading") {
                                Text(
                                    stringResource(R.string.screen_sessions_023),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp).semantics { heading() },
                                )
                            }
                        }
                        if (visible.isEmpty()) {
                            item(key = "__empty") { EmptyState(conn, showArchived) }
                        } else {
                            section(appString(R.string.screen_sessions_024), waiting, onOpen, ::swipeArchive, ::swipeDelete) { menuFor = it }
                            section(appString(R.string.screen_sessions_025), running, onOpen, ::swipeArchive, ::swipeDelete) { menuFor = it }
                            section(if (waiting.isEmpty() && running.isEmpty()) null else appString(R.string.screen_sessions_026), rest, onOpen, ::swipeArchive, ::swipeDelete) { menuFor = it }
                        }
                    }
                }
            }
        }
    }

    newFor?.let { provider ->
        NewSessionSheet(
            provider = provider,
            onDismiss = { newFor = null },
            onCreated = { s ->
                newFor = null
                onOpen(s.id)
            },
        )
    }

    menuFor?.let { s ->
        SessionMenu(
            s,
            onDismiss = { menuFor = null },
            onRename = { menuFor = null; renameFor = s },
            onArchive = { menuFor = null; act { repo.api.patch(s.id, archived = !s.archived) } },
            onDetach = { menuFor = null; act { repo.api.detach(s.id) } },
            onDelete = { menuFor = null; deleteFor = s },
        )
    }
    renameFor?.let { s ->
        var title by remember(s.id) { mutableStateOf(s.title) }
        AlertDialog(modifier = dev.remotecode.ui.common.hapticArea(), 
            onDismissRequest = { renameFor = null },
            title = { Text(stringResource(R.string.screen_sessions_027)) },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = { Button(onClick = { renameFor = null; act { repo.api.patch(s.id, title = title) } }) { Text(stringResource(R.string.screen_sessions_028)) } },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text(stringResource(R.string.screen_sessions_029)) } },
        )
    }
    deleteFor?.let { s ->
        AlertDialog(modifier = dev.remotecode.ui.common.hapticArea(), 
            onDismissRequest = { deleteFor = null },
            title = { Text(stringResource(R.string.screen_sessions_030)) },
            text = { Text(stringResource(R.string.screen_sessions_031, s.title, Providers.label(s.provider))) },
            confirmButton = { Button(onClick = { deleteFor = null; act { repo.api.delete(s.id) } }) { Text(stringResource(R.string.screen_sessions_032)) } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text(stringResource(R.string.screen_sessions_029)) } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String?,
    list: List<SessionInfo>,
    onOpen: (String) -> Unit,
    onArchive: (SessionInfo) -> Unit,
    onDelete: (SessionInfo) -> Unit,
    onLongPress: (SessionInfo) -> Unit,
) {
    if (list.isEmpty()) return
    if (title != null) {
        item(key = "h-$title") {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 6.dp),
            )
        }
    }
    itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
        SwipeableSessionRow(s, i, list.size, onOpen = { onOpen(s.id) }, onLongPress = { onLongPress(s) }, onArchive = { onArchive(s) }, onDelete = { onDelete(s) }, modifier = Modifier.animateItem())
    }
}

@Composable
private fun SwipeableSessionRow(
    s: SessionInfo,
    index: Int,
    count: Int,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) {
        when (state.currentValue) {
            androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> { onArchive(); state.snapTo(androidx.compose.material3.SwipeToDismissBoxValue.Settled) }
            androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> { onDelete(); state.snapTo(androidx.compose.material3.SwipeToDismissBoxValue.Settled) }
            else -> Unit
        }
    }
    androidx.compose.material3.SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val dir = state.dismissDirection
            val archive = dir == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd
            val del = dir == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart
            val bg = when {
                archive -> MaterialTheme.colorScheme.tertiaryContainer
                del -> MaterialTheme.colorScheme.errorContainer
                else -> androidx.compose.ui.graphics.Color.Transparent
            }
            val fg = if (del) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
            Row(
                Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.large)
                    .background(bg)
                    .padding(horizontal = 24.dp),
                horizontalArrangement = if (del) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (archive || del) {
                    val label = when {
                        del -> appString(R.string.screen_sessions_032)
                        s.archived -> appString(R.string.screen_sessions_033)
                        else -> appString(R.string.screen_sessions_008)
                    }
                    val icon = when {
                        del -> Icons.Rounded.Delete
                        s.archived -> Icons.Rounded.Unarchive
                        else -> Icons.Rounded.Archive
                    }
                    if (del) Text(label, color = fg, style = MaterialTheme.typography.labelLarge)
                    if (del) Spacer(Modifier.size(8.dp))
                    Icon(icon, null, tint = fg)
                    if (!del) Spacer(Modifier.size(8.dp))
                    if (!del) Text(label, color = fg, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) {
        SessionRow(s, index, count, onClick = onOpen, onLongClick = onLongPress)
    }
}

@Composable
private fun SessionRow(s: SessionInfo, index: Int, count: Int, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    SegmentedListItem(
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = appString(R.string.screen_sessions_034),
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier,
        leadingContent = { ProviderBadge(s.provider, running = s.isRunning, waiting = s.isWaiting) },
        overlineContent = {
            Text(
                "${Providers.label(s.provider)} · ${shortPath(s.cwd)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            if (s.preview.isNotBlank()) Text(s.preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(relativeTime(s.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    s.pending > 0 -> Badge(containerColor = MaterialTheme.colorScheme.error) { Text("${s.pending}") }
                    s.isRunning || s.status == "error" -> Text(
                        statusLabel(s.status, s.pending),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (s.status == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    ) {
        Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SessionMenu(
    s: SessionInfo,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDetach: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(modifier = dev.remotecode.ui.common.hapticArea(), onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ProviderBadge(s.provider, running = s.isRunning, waiting = s.isWaiting, size = 40.dp)
                Spacer(Modifier.size(14.dp))
                Column {
                    Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.cwd, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            MenuRow(Icons.Rounded.Edit, appString(R.string.screen_sessions_027), onClick = onRename)
            MenuRow(if (s.archived) Icons.Rounded.Unarchive else Icons.Rounded.Archive, if (s.archived) appString(R.string.screen_sessions_033) else appString(R.string.screen_sessions_008), onClick = onArchive)
            if (s.live) MenuRow(Icons.Rounded.PowerSettingsNew, appString(R.string.screen_sessions_035), onClick = onDetach)
            MenuRow(Icons.Rounded.Delete, appString(R.string.screen_sessions_036), danger = true, onClick = onDelete)
        }
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        onClick = onClick,
        leadingContent = { Icon(icon, null, tint = color) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    ) { Text(label, color = color) }
}

@Composable
private fun EmptyState(conn: ConnState, archived: Boolean) {
    run {
        run {
            Column(
                Modifier.fillMaxWidth().padding(top = 40.dp, start = 32.dp, end = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    Modifier
                        .size(120.dp)
                        .clip((if (conn is ConnState.Failed) MaterialShapes.Puffy else MaterialShapes.Sunny).toShape())
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (conn is ConnState.Failed) Icons.Rounded.CloudOff else Icons.Rounded.AutoAwesome,
                        null,
                        Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Text(
                    when {
                        conn is ConnState.Failed -> appString(R.string.screen_sessions_037)
                        archived -> appString(R.string.screen_sessions_038)
                        else -> appString(R.string.screen_sessions_039)
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    when {
                        conn is ConnState.Failed -> appString(R.string.screen_sessions_040)
                        archived -> ""
                        else -> appString(R.string.screen_sessions_041)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row {}
            }
        }
    }
}

@Composable
private fun PairingCard(conn: ConnState, onPairing: () -> Unit, onRetry: () -> Unit) {
    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(androidx.compose.material.icons.Icons.Rounded.LinkOff, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                Column {
                    Text(stringResource(R.string.screen_sessions_037), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    if (conn is ConnState.Failed) {
                        Text(conn.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPairing, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.screen_sessions_042)) }
                androidx.compose.material3.OutlinedButton(onClick = onRetry, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.screen_sessions_043)) }
            }
        }
    }
}

@Composable
private fun HomeTabs(tab: String, attention: Int, onSelect: (String) -> Unit) {
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(androidx.compose.material3.ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        listOf("history" to appString(R.string.screen_sessions_021), "sessions" to appString(R.string.screen_sessions_023)).forEachIndexed { i, (id, label) ->
            androidx.compose.material3.ToggleButton(
                checked = tab == id,
                onCheckedChange = { if (tab != id) { haptics.tick(); onSelect(id) } },
                shapes = if (i == 0) androidx.compose.material3.ButtonGroupDefaults.connectedLeadingButtonShapes() else androidx.compose.material3.ButtonGroupDefaults.connectedTrailingButtonShapes(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    if (id == "history") androidx.compose.material.icons.Icons.Rounded.History else androidx.compose.material.icons.Icons.AutoMirrored.Rounded.Chat,
                    null,
                    Modifier.size(18.dp),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(androidx.compose.material3.ButtonDefaults.IconSpacing))
                Text(label)
                if (id == "sessions" && attention > 0) {
                    androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                    Badge { Text("$attention") }
                }
            }
        }
    }
}
