package dev.remotecode.ui.common

import dev.remotecode.R

import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.AppUpdateInfo
import dev.remotecode.data.ConnectionIdentity
import dev.remotecode.data.ServerConfig
import dev.remotecode.data.connectionIdentity
import dev.remotecode.update.AppUpdater
import dev.remotecode.update.updateAvailable
import dev.remotecode.update.usableUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

private sealed interface UpdateState {
    data object Checking : UpdateState
    data object Latest : UpdateState
    data object NoApk : UpdateState
    data class Available(val info: AppUpdateInfo) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Error(val message: String, val userAction: Boolean) : UpdateState
}

private data class UpdateSnapshot(val source: ConnectionIdentity? = null, val state: UpdateState = UpdateState.Checking)

/** 画面間で更新処理を共有する。 */
private class AppUpdateController(private val app: RemoteCodeApp) {
    val snapshot = MutableStateFlow(UpdateSnapshot())
    private var target: ServerConfig? = null
    private var checkJob: Job? = null
    private var updateJob: Job? = null
    @Volatile private var generation = 0

    fun select(server: ServerConfig?) {
        val identity = server?.connectionIdentity()
        target = server
        if (snapshot.value.source == identity) return
        generation++
        checkJob?.cancel()
        updateJob?.cancel()
        snapshot.value = UpdateSnapshot(identity)
    }

    private fun publish(ticket: Int, state: UpdateState) {
        snapshot.update { current -> if (generation == ticket) current.copy(state = state) else current }
    }

    fun check(userAction: Boolean = false) {
        if (checkJob?.isActive == true || updateJob?.isActive == true) return
        val server = target ?: return
        val ticket = generation
        val previous = snapshot.value.state
        if (previous !is UpdateState.Available && !(previous is UpdateState.Error && previous.userAction)) publish(ticket, UpdateState.Checking)
        checkJob = app.scope.launch(Dispatchers.Main.immediate) {
            try {
                val info = app.repository.api.appUpdate(expectedServer = server)
                val installedSha = AppUpdater.installedSha256(app)
                val installedCode = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
                publish(ticket, when {
                    !usableUpdate(info) -> UpdateState.NoApk
                    updateAvailable(info, installedSha, installedCode) -> UpdateState.Available(info)
                    else -> UpdateState.Latest
                })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // 通信失敗でも既存の更新案内を残す。
                if (previous !is UpdateState.Available) publish(ticket, UpdateState.Error(e.message ?: appString(R.string.screen_app_update_item_001), userAction || previous is UpdateState.Error && previous.userAction))
            }
        }
    }

    fun update() {
        if (updateJob?.isActive == true) return
        val info = (snapshot.value.state as? UpdateState.Available)?.info ?: return
        val server = target ?: return
        if (app.repository.api.server?.connectionIdentity() != server.connectionIdentity()) return
        checkJob?.cancel()
        val ticket = generation
        publish(ticket, UpdateState.Downloading(0f))
        updateJob = app.scope.launch(Dispatchers.Main.immediate) {
            // 接続先ごとに APK の保存先を分ける。
            var file: File? = null
            try {
                val temporary = File.createTempFile("app-update-", ".apk", app.cacheDir)
                file = temporary
                val hash = app.repository.api.downloadApk(temporary, { progress -> publish(ticket, UpdateState.Downloading(progress.coerceIn(0f, 1f))) }, expectedServer = server)
                currentCoroutineContext().ensureActive()
                if (generation != ticket || app.repository.api.server?.connectionIdentity() != server.connectionIdentity()) return@launch
                if (!hash.equals(info.sha256, ignoreCase = true)) throw IllegalStateException(appString(R.string.screen_app_update_item_002))
                publish(ticket, UpdateState.Installing)
                AppUpdater.install(app, temporary)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { publish(ticket, UpdateState.Error(e.message ?: appString(R.string.screen_app_update_item_003), true)) }
            finally { file?.delete() }
        }
    }
}

private val updateController by lazy { AppUpdateController(RemoteCodeApp.instance) }

@Composable
fun AppUpdateItem(connected: Boolean, onlyWhenAvailable: Boolean = false, modifier: Modifier = Modifier) {
    val repo = RemoteCodeApp.instance.repository
    val settings by repo.settings.collectAsState()
    val route by repo.route.collectAsState()
    val server = remember(settings?.server, route, connected) { repo.api.server }
    val identity = server?.connectionIdentity()
    val snapshot by updateController.snapshot.collectAsState()
    val state = if (snapshot.source == identity) snapshot.state else UpdateState.Checking
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(identity, connected, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (connected && event == Lifecycle.Event.ON_RESUME) {
                updateController.select(server)
                updateController.check()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(identity, connected, lifecycleOwner) {
        updateController.select(server)
        if (connected) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                updateController.check()
                delay(60_000)
            }
        }
    }

    val active = state is UpdateState.Downloading || state == UpdateState.Installing
    if (onlyWhenAvailable && (!connected && !active || state !is UpdateState.Available && !active && !(state is UpdateState.Error && state.userAction))) return

    SegmentedListItem(
        onClick = {
            when (state) {
                is UpdateState.Available -> {
                    if (!AppUpdater.canInstall(context)) {
                        AppUpdater.openInstallPermission(context)
                        Toast.makeText(context, appString(R.string.screen_app_update_item_004), Toast.LENGTH_LONG).show()
                    } else updateController.update()
                }
                is UpdateState.Downloading, UpdateState.Installing, UpdateState.Checking -> Unit
                else -> updateController.check(userAction = true)
            }
        },
        enabled = connected || active,
        modifier = modifier,
        shapes = ListItemDefaults.segmentedShapes(0, 1),
        colors = ListItemDefaults.segmentedColors(containerColor = if (state is UpdateState.Available || active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
        leadingContent = { Icon(Icons.Rounded.SystemUpdate, null) },
        supportingContent = {
            when (state) {
                UpdateState.Checking -> Text(if (connected) appString(R.string.screen_app_update_item_005) else appString(R.string.screen_app_update_item_006))
                UpdateState.Latest -> Text(stringResource(R.string.screen_app_update_item_007))
                UpdateState.NoApk -> Text(stringResource(R.string.screen_app_update_item_008))
                is UpdateState.Available -> Text(if (onlyWhenAvailable) appString(R.string.screen_app_update_item_009, state.info.versionName?.let { " $it" }.orEmpty()) else appString(R.string.screen_app_update_item_010, state.info.versionName?.let { " $it" }.orEmpty(), appVersion(context), state.info.size / 1_048_576f))
                is UpdateState.Downloading -> Column {
                    Text(stringResource(R.string.screen_app_update_item_011, (state.progress * 100).toInt()))
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.padding(top = 6.dp))
                }
                UpdateState.Installing -> Text(stringResource(R.string.screen_app_update_item_012))
                is UpdateState.Error -> Text(stringResource(R.string.screen_app_update_item_013, state.message), color = MaterialTheme.colorScheme.error)
            }
        },
    ) { Text(if (onlyWhenAvailable) appString(R.string.screen_app_update_item_014) else appString(R.string.screen_app_update_item_015)) }
}

private fun appVersion(context: Context): String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
