package dev.remotecode.ui

import dev.remotecode.R
import dev.remotecode.i18n.appString
import androidx.compose.animation.core.tween
import androidx.activity.BackEventCompat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.navigation.NavDestination.Companion.hasRoute
import dev.remotecode.data.AppSettings
import dev.remotecode.data.CodeQuoteDrafts
import dev.remotecode.data.ServerConfig
import dev.remotecode.data.connectionIdentity
import dev.remotecode.data.isActiveAgent
import dev.remotecode.MainActivity
import dev.remotecode.RemoteCodeApp
import dev.remotecode.notificationServerId
import dev.remotecode.ui.chat.ChatScreen
import dev.remotecode.ui.chat.WorkflowAgentScreen
import dev.remotecode.ui.history.HistoryScreen
import dev.remotecode.ui.history.LocalOpenServerPicker
import dev.remotecode.ui.history.ServerPickerDialog
import dev.remotecode.ui.sessions.SessionsScreen
import dev.remotecode.ui.settings.SettingsScreen
import dev.remotecode.ui.setup.SetupScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data class SetupRoute(val addServer: Boolean = false)
@Serializable object SessionsRoute
@Serializable object DesktopRoute
@Serializable data class ChatRoute(val id: String)
@Serializable data class WorkflowAgentRoute(val sessionId: String, val entryId: String, val title: String, val initiallyActive: Boolean)
@Serializable object HistoryRoute
@Serializable object SettingsRoute
@Serializable object AppearanceRoute
@Serializable object ManageRoute

@Serializable data class AgentManageRoute(val provider: String)
@Serializable object NetLogRoute
@Serializable object ControlPathsRoute
@Serializable data class TerminalsRoute(val cwd: String = "")
@Serializable data class TerminalRoute(val id: String)
@Serializable data class ProjectFilesRoute(val sessionId: String)
@Serializable data class ProjectEditorRoute(val sessionId: String, val path: String)

@Composable
fun AppNav(activity: MainActivity) {
    val repo = RemoteCodeApp.instance.repository
    val settings by repo.settings.collectAsState()
    val activeIdentity by repo.activeServerIdentity.collectAsState()
    var showHistoryOnServer by remember { mutableStateOf<String?>(null) }

    val s = settings
    val serverToken = s?.server?.token
    if (s == null || serverToken != activeIdentity?.token) {
        Surface(Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) { LoadingIndicator() }
        }
        return
    }

    key(serverToken) {
        ServerNav(activity, s, showHistoryOnServer, onShowHistoryOnServer = { showHistoryOnServer = it })
    }
}

@Composable
private fun ServerNav(activity: MainActivity, s: AppSettings, showHistoryOnServer: String?, onShowHistoryOnServer: (String?) -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val activeIdentity by repo.activeServerIdentity.collectAsState()
    val pair by activity.pairRequest.collectAsState()
    val openSession by activity.openSession.collectAsState()
    val scope = rememberCoroutineScope()
    val nav = rememberNavController()
    val serverToken = s.server?.token
    var showServerPicker by remember { mutableStateOf(false) }
    var pairingFailure by remember { mutableStateOf(false) }

    fun showHome() = nav.navigate(SessionsRoute) {
        popUpTo(nav.graph.id) { inclusive = false }
        launchSingleTop = true
    }

    suspend fun acceptPair(target: ServerConfig, adding: Boolean) {
        val newHost = serverToken != target.token
        if (adding && newHost) onShowHistoryOnServer(target.token)
        try {
            repo.settingsStore.mergePair(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (adding && newHost) onShowHistoryOnServer(null)
            throw e
        }
        if (!newHost) {
            if (adding) onShowHistoryOnServer(target.token) else showHome()
        }
    }

    LaunchedEffect(showHistoryOnServer) {
        if (serverToken != null && showHistoryOnServer == serverToken) {
            onShowHistoryOnServer(null)
            showHome()
            nav.navigate(HistoryRoute)
        }
    }

    CompositionLocalProvider(
        LocalOpenServerPicker provides { showServerPicker = true },
        dev.remotecode.ui.chat.LocalProjectEditor provides { sessionId, path -> nav.navigate(ProjectEditorRoute(sessionId, path)) },
        dev.remotecode.ui.chat.LocalCodeQuote provides { sessionId, source, quote ->
            check(repo.api.server?.connectionIdentity() == source) { appString(R.string.core_quote_server_changed) }
            check(repo.sessions.value.any { it.id == sessionId }) { appString(R.string.core_quote_session_not_found) }
            CodeQuoteDrafts.add(source, sessionId, quote)
            val current = nav.currentBackStackEntry
            val sameChat = current != null && current.destination.hasRoute<ChatRoute>() && current.toRoute<ChatRoute>().id == sessionId
            if (!sameChat && !nav.popBackStack(ChatRoute(sessionId), inclusive = false)) {
                nav.navigate(ChatRoute(sessionId)) { launchSingleTop = true; popUpTo(SessionsRoute) }
            }
        },
    ) {
    Surface(Modifier.fillMaxSize().then(dev.remotecode.ui.common.hapticArea()), color = MaterialTheme.colorScheme.surface) {
        NavHost(
            navController = nav,
            startDestination = if (s.server == null) SetupRoute() else SessionsRoute,
            enterTransition = { slideInHorizontally(tween(320)) { it / 3 } + fadeIn(tween(220)) },
            exitTransition = { slideOutHorizontally(tween(320)) { -it / 6 } + fadeOut(tween(160)) },
            popEnterTransition = { slideInHorizontally(tween(200, easing = FastOutSlowInEasing)) { -it / 4 } },
            popExitTransition = { slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { it } },
            // 既定の縮小を避け、戻るジェスチャーに追従する。
            predictivePopEnterTransition = { edge -> slideInHorizontally(tween(200, easing = LinearEasing)) { w -> if (edge == BackEventCompat.EDGE_RIGHT) w / 4 else -w / 4 } },
            predictivePopExitTransition = { edge -> slideOutHorizontally(tween(200, easing = LinearEasing)) { w -> if (edge == BackEventCompat.EDGE_RIGHT) -w else w } },
        ) {
            composable<SetupRoute> { entry ->
                val adding = entry.toRoute<SetupRoute>().addServer
                SetupScreen(
                    initial = if (adding) null else s.server,
                    adding = adding,
                    canGoBack = s.server != null && nav.previousBackStackEntry != null,
                    onBack = { nav.popBackStack() },
                    onPair = { target -> acceptPair(target, adding) },
                )
            }
            composable<SessionsRoute> {
                SessionsScreen(
                    onOpen = { nav.navigate(ChatRoute(it)) },
                    onHistory = { nav.navigate(HistoryRoute) },
                    onDesktop = { nav.navigate(DesktopRoute) },
                    onSettings = { nav.navigate(SettingsRoute) },
                    onPairing = { nav.navigate(SetupRoute()) },
                )
            }
            composable<DesktopRoute> {
                dev.remotecode.ui.desktop.DesktopScreen(onBack = { nav.popBackStack() })
            }
            composable<ChatRoute> { entry ->
                val route = entry.toRoute<ChatRoute>()
                val current by nav.currentBackStackEntryAsState()
                val lifecycleState by entry.lifecycle.currentStateFlow.collectAsState()
                ChatScreen(
                    sessionId = route.id,
                    onBack = { nav.popBackStack() },
                    onTerminals = { cwd -> nav.navigate(TerminalsRoute(cwd)) },
                    onOpenTerminal = { id -> nav.navigate(TerminalRoute(id)) },
                    onProjectFiles = { nav.navigate(ProjectFilesRoute(route.id)) },
                    showWorkflowInForeground = current == entry && lifecycleState == Lifecycle.State.RESUMED,
                    onOpenWorkflowAgent = { agent ->
                        if (nav.currentBackStackEntry == entry) {
                            nav.navigate(WorkflowAgentRoute(route.id, agent.id, agent.title.orEmpty(), isActiveAgent(agent.status, agent.streaming)))
                        }
                    },
                )
            }
            composable<WorkflowAgentRoute> { entry ->
                val route = entry.toRoute<WorkflowAgentRoute>()
                WorkflowAgentScreen(route.sessionId, route.entryId, route.title, route.initiallyActive, onBack = { nav.popBackStack() })
            }
            composable<ProjectFilesRoute> { entry ->
                val route = entry.toRoute<ProjectFilesRoute>()
                dev.remotecode.ui.files.ProjectFilesScreen(route.sessionId, onBack = { nav.popBackStack() }, onEdit = { path -> nav.navigate(ProjectEditorRoute(route.sessionId, path)) })
            }
            composable<ProjectEditorRoute> { entry ->
                val route = entry.toRoute<ProjectEditorRoute>()
                dev.remotecode.ui.files.ProjectEditorScreen(route.sessionId, route.path, onBack = { nav.popBackStack() })
            }
            composable<HistoryRoute> {
                HistoryScreen(
                    onBack = { nav.popBackStack() },
                    onOpen = { id -> nav.navigate(ChatRoute(id)) { popUpTo(SessionsRoute) } },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onChangeServer = { nav.navigate(SetupRoute()) },
                    onAppearance = { nav.navigate(AppearanceRoute) },
                    onManage = { p -> if (p == "claude") nav.navigate(ManageRoute) else nav.navigate(AgentManageRoute(p)) },
                    onNetLog = { nav.navigate(NetLogRoute) },
                    onTerminals = { nav.navigate(TerminalsRoute()) },
                    onControlPaths = { nav.navigate(ControlPathsRoute) },
                )
            }
            composable<NetLogRoute> {
                dev.remotecode.ui.settings.NetLogScreen(onBack = { nav.popBackStack() })
            }
            composable<ControlPathsRoute> {
                dev.remotecode.ui.settings.ControlPathsScreen(onBack = { nav.popBackStack() })
            }
            composable<ManageRoute> {
                dev.remotecode.ui.settings.ManageScreen(onBack = { nav.popBackStack() })
            }
            composable<AgentManageRoute> { entry ->
                val r = entry.toRoute<AgentManageRoute>()
                dev.remotecode.ui.settings.AgentManageScreen(r.provider, onBack = { nav.popBackStack() })
            }
            composable<TerminalsRoute> { entry ->
                val r = entry.toRoute<TerminalsRoute>()
                dev.remotecode.ui.terminal.TerminalsScreen(r.cwd, onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate(TerminalRoute(id)) })
            }
            composable<TerminalRoute> { entry ->
                val r = entry.toRoute<TerminalRoute>()
                dev.remotecode.ui.terminal.TerminalScreen(r.id, onBack = { nav.popBackStack() })
            }
            composable<AppearanceRoute> {
                dev.remotecode.ui.settings.AppearanceScreen(onBack = { nav.popBackStack() })
            }
        }
    }
    }

    if (showServerPicker && s.server != null) {
        ServerPickerDialog(
            servers = s.savedServers,
            active = s.server,
            onDismiss = { showServerPicker = false },
            onAdd = {
                showServerPicker = false
                nav.navigate(SetupRoute(addServer = true))
            },
            onSelect = { target ->
                onShowHistoryOnServer(target.token)
                try {
                    repo.settingsStore.selectServer(target.token).also { selected ->
                        if (!selected) onShowHistoryOnServer(null)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onShowHistoryOnServer(null)
                    throw e
                }
            },
            onForget = { repo.settingsStore.forgetServer(it.token) },
        )
    }

    LaunchedEffect(openSession, s.server, activeIdentity) {
        val target = openSession ?: return@LaunchedEffect
        val current = repo.settings.value?.server ?: return@LaunchedEffect
        if (current.token != serverToken || repo.api.server?.token != current.token) return@LaunchedEffect
        if (activity.openSession.compareAndSet(target, null) && current.notificationServerId() == target.serverId) {
            nav.navigate(ChatRoute(target.sessionId)) { popUpTo(SessionsRoute) }
        }
    }

    val p = pair
    if (p != null && dev.remotecode.data.cleartextBlocked(p.url)) {
        // 平文 HTTP は USB 接続だけ許可する。
        AlertDialog(modifier = dev.remotecode.ui.common.hapticArea(),
            onDismissRequest = { activity.pairRequest.value = null },
            title = { Text(stringResource(R.string.core_pair_server_blocked)) },
            text = { Text("${p.url}\n\n${dev.remotecode.data.CLEARTEXT_BLOCKED_MESSAGE}") },
            confirmButton = { TextButton(onClick = { activity.pairRequest.value = null }) { Text(stringResource(R.string.core_close)) } },
        )
    } else if (p != null) {
        AlertDialog(modifier = dev.remotecode.ui.common.hapticArea(),
            onDismissRequest = { activity.pairRequest.value = null },
            title = { Text(stringResource(R.string.core_pair_confirm)) },
            text = { Text("${p.name.ifBlank { "PC" }}\n${p.url}" + if (p.hasAccess) stringResource(R.string.core_pair_access_token_included) else "") },
            confirmButton = {
                Button(onClick = {
                    val entry = nav.currentBackStackEntry
                    val adding = entry?.takeIf { it.destination.hasRoute<SetupRoute>() }?.toRoute<SetupRoute>()?.addServer == true
                    activity.pairRequest.value = null
                    scope.launch {
                        try {
                            acceptPair(p, adding)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            pairingFailure = true
                        }
                    }
                }) { Text(stringResource(R.string.core_pair_connect)) }
            },
            dismissButton = { TextButton(onClick = { activity.pairRequest.value = null }) { Text(stringResource(R.string.core_cancel)) } },
        )
    }
    if (pairingFailure) {
        AlertDialog(modifier = dev.remotecode.ui.common.hapticArea(),
            onDismissRequest = { pairingFailure = false },
            text = { Text(stringResource(R.string.server_picker_failed)) },
            confirmButton = { TextButton(onClick = { pairingFailure = false }) { Text(stringResource(R.string.core_close)) } },
        )
    }
}
