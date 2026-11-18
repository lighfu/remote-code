package dev.remotecode.ui.settings

import dev.remotecode.R

import dev.remotecode.i18n.appString
import dev.remotecode.i18n.LanguageSettingsItem
import androidx.compose.ui.res.stringResource

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import dev.remotecode.ui.common.AppDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ConnState
import dev.remotecode.data.ThinkingModes
import dev.remotecode.notify.ConnectionService
import dev.remotecode.ui.common.AppUpdateItem
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit, onChangeServer: () -> Unit, onAppearance: () -> Unit, onManage: (String) -> Unit, onNetLog: () -> Unit, onTerminals: () -> Unit = {}, onControlPaths: () -> Unit = {}) {
    val repo = RemoteCodeApp.instance.repository
    val settings by repo.settings.collectAsState()
    val conn by repo.connection.collectAsState()
    val meta by repo.meta.collectAsState()
    val route by repo.route.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var thinkingMenu by remember { mutableStateOf(false) }
    val s = settings ?: return
    val dependencyServer = remember(s.server, route, conn) { repo.api.server }
    val haptics = dev.remotecode.ui.common.rememberHaptics()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_settings_001)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.screen_settings_002)) } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            Text(stringResource(R.string.screen_settings_003), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp))
            SegmentedListItem(
                onClick = onChangeServer,
                shapes = ListItemDefaults.segmentedShapes(0, 4),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                leadingContent = { Icon(Icons.Rounded.Computer, null) },
                supportingContent = {
                    val server = s.server
                    // 保存済みの公開 URL があれば、古い一時 URL を隠す。
                    val urls = server?.candidates.orEmpty().filter { u ->
                        !(u.contains("trycloudflare.com") && server!!.publicUrl.isNotBlank() && u != server.publicUrl.trimEnd('/'))
                    }
                    Text(
                        if (urls.isEmpty()) appString(R.string.screen_settings_004) else urls.joinToString("\n") { "${dev.remotecode.data.routeLabel(it)}: $it" },
                    )
                },
            ) { Text(s.server?.name?.ifBlank { null } ?: meta?.host ?: appString(R.string.screen_settings_005)) }
            SegmentedListItem(
                onClick = { repo.reconnectNow() },
                shapes = ListItemDefaults.segmentedShapes(1, 4),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                leadingContent = { Icon(Icons.Rounded.Sync, null) },
            ) { Text(stringResource(R.string.screen_settings_006)) }
            SegmentedListItem(
                onClick = onNetLog,
                shapes = ListItemDefaults.segmentedShapes(2, 4),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                leadingContent = { Icon(Icons.Rounded.NetworkCheck, null) },
                supportingContent = { Text(stringResource(R.string.screen_settings_007)) },
            ) { Text(stringResource(R.string.screen_settings_008)) }
            SegmentedListItem(
                onClick = onControlPaths,
                shapes = ListItemDefaults.segmentedShapes(3, 4),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                leadingContent = { Icon(Icons.Rounded.AccountTree, null) },
                supportingContent = { Text(stringResource(R.string.screen_settings_009)) },
            ) { Text(stringResource(R.string.screen_settings_010)) }

            Text("PC", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp))
            SegmentedListItem(
                onClick = onTerminals,
                shapes = ListItemDefaults.segmentedShapes(0, 2),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                leadingContent = { Icon(Icons.Rounded.Terminal, null) },
                supportingContent = { Text(stringResource(R.string.screen_settings_011)) },
            ) { Text(stringResource(R.string.screen_settings_012)) }
            ServerDependenciesSection(api = repo.api, server = dependencyServer, connected = conn is ConnState.Connected, refreshAvailability = { expected, fresh -> repo.refreshMeta(expected, fresh) })

            Text(stringResource(R.string.screen_settings_013), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp))
            listOf(
                Triple("claude", appString(R.string.screen_settings_014), appString(R.string.screen_settings_015)),
                Triple("codex", appString(R.string.screen_settings_016), appString(R.string.screen_settings_017)),
                Triple("antigravity", appString(R.string.screen_settings_018), appString(R.string.screen_settings_017)),
            ).forEachIndexed { i, (id, title, sub) ->
                SegmentedListItem(
                    onClick = { onManage(id) },
                    shapes = ListItemDefaults.segmentedShapes(i, 3),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    leadingContent = { dev.remotecode.ui.common.BrandIcon(id, 24.dp) },
                    supportingContent = { Text(sub) },
                ) { Text(title) }
            }

            Text(stringResource(R.string.screen_settings_019), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp))
            SegmentedListItem(
                onClick = {
                    val on = !s.backgroundNotify
                    haptics.toggle(on)
                    scope.launch { repo.settingsStore.setBackgroundNotify(on) }
                    if (on) ConnectionService.start(context) else ConnectionService.stop(context)
                },
                shapes = ListItemDefaults.segmentedShapes(0, 2),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                leadingContent = { Icon(Icons.Rounded.NotificationsActive, null) },
                supportingContent = { Text(stringResource(R.string.screen_settings_020)) },
                trailingContent = { Switch(checked = s.backgroundNotify, onCheckedChange = null) },
            ) { Text(stringResource(R.string.screen_settings_021)) }
            SegmentedListItem(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                shapes = ListItemDefaults.segmentedShapes(1, 2),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                leadingContent = { Icon(Icons.Rounded.Notifications, null) },
                supportingContent = { Text(stringResource(R.string.screen_settings_022)) },
            ) { Text(stringResource(R.string.screen_settings_023)) }

            Text(stringResource(R.string.screen_settings_024), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp))
            SegmentedListItem(
                onClick = onAppearance,
                shapes = ListItemDefaults.segmentedShapes(0, 1),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                leadingContent = { Icon(Icons.Rounded.Palette, null) },
                supportingContent = { Text(stringResource(R.string.screen_settings_025)) },
            ) { Text(stringResource(R.string.screen_settings_026)) }
            LanguageSettingsItem()
            Spacer(Modifier.height(8.dp))
            Box {
                SegmentedListItem(
                    onClick = { thinkingMenu = true },
                    shapes = ListItemDefaults.segmentedShapes(0, 2),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    leadingContent = { Icon(Icons.Rounded.Lightbulb, null) },
                    supportingContent = { Text(ThinkingModes.description(s.thinking)) },
                    trailingContent = { Text(ThinkingModes.label(s.thinking), color = MaterialTheme.colorScheme.primary) },
                ) { Text(stringResource(R.string.screen_settings_027)) }
                DropdownMenu(
                    expanded = thinkingMenu,
                    onDismissRequest = { thinkingMenu = false },
                    modifier = dev.remotecode.ui.common.hapticArea(),
                    offset = androidx.compose.ui.unit.DpOffset(56.dp, 0.dp),
                ) {
                    ThinkingModes.all.forEach { m ->
                        DropdownMenuItem(
                            leadingIcon = { RadioButton(selected = s.thinking == m, onClick = null) },
                            text = {
                                Column {
                                    Text(ThinkingModes.label(m))
                                    Text(ThinkingModes.description(m), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                thinkingMenu = false
                                haptics.tick()
                                scope.launch { repo.settingsStore.setThinking(m) }
                            },
                        )
                    }
                }
            }
            SegmentedListItem(
                onClick = { haptics.toggle(!s.toolDetail); scope.launch { repo.settingsStore.setToolDetail(!s.toolDetail) } },
                shapes = ListItemDefaults.segmentedShapes(1, 2),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                leadingContent = { Icon(Icons.Rounded.Build, null) },
                supportingContent = { Text(stringResource(R.string.screen_settings_028)) },
                trailingContent = { Switch(checked = s.toolDetail, onCheckedChange = null) },
            ) { Text(stringResource(R.string.screen_settings_029)) }

            Spacer(Modifier.height(8.dp))
            WrapSetting(R.string.settings_wrap_tool_input, s.toolWrap.input, 0) { on ->
                scope.launch { repo.settingsStore.setToolInputWrap(on) }
            }
            WrapSetting(R.string.settings_wrap_tool_output, s.toolWrap.output, 1) { on ->
                scope.launch { repo.settingsStore.setToolOutputWrap(on) }
            }
            WrapSetting(R.string.settings_wrap_files_and_diffs, s.codeWrap, 2) { on ->
                scope.launch { repo.settingsStore.setCodeWrap(on) }
            }

            Text(stringResource(R.string.screen_settings_030), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp))
            AppUpdateItem(connected = conn is ConnState.Connected)

            run {
                val server = meta?.let {
                    appString(R.string.screen_settings_031, it.version, it.host) +
                        (it.startedAt?.let { at -> appString(R.string.screen_settings_032) + java.text.SimpleDateFormat("M/d H:mm:ss", java.util.Locale.JAPAN).format(java.util.Date(at)) } ?: "")
                } ?: ""
                Text(
                    stringResource(R.string.screen_settings_033, appVersion(context), server),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun WrapSetting(title: Int, checked: Boolean, index: Int, onChange: (Boolean) -> Unit) {
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    SegmentedListItem(
        onClick = { haptics.toggle(!checked); onChange(!checked) },
        shapes = ListItemDefaults.segmentedShapes(index, 3),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        leadingContent = { Icon(Icons.AutoMirrored.Rounded.WrapText, null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    ) { Text(stringResource(title)) }
}

private fun appVersion(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
