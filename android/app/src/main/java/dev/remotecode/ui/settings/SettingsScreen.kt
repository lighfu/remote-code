package dev.remotecode.ui.settings

import dev.remotecode.R

import dev.remotecode.i18n.appString
import dev.remotecode.i18n.LanguageSettingsItem
import androidx.compose.ui.res.stringResource

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.material.icons.rounded.Visibility
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ConnState
import dev.remotecode.data.ThinkingModes
import dev.remotecode.notify.ConnectionService
import dev.remotecode.ui.chat.FooterDisplayDialog
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
    var showFooterDisplay by remember { mutableStateOf(false) }
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
            SettingsHeading(stringResource(R.string.screen_settings_003), Modifier.padding(8.dp))
            val publicUrl = s.server?.publicUrl.orEmpty()
            // 保存済みの公開 URL があれば、古い一時 URL を隠す。
            val urls = s.server?.candidates.orEmpty().filterNot { url ->
                url.contains("trycloudflare.com") && publicUrl.isNotBlank() && url != publicUrl.trimEnd('/')
            }
            SettingsRow(
                s.server?.name?.ifBlank { null } ?: meta?.host ?: appString(R.string.screen_settings_005),
                Icons.Rounded.Computer, 0, 4,
                summary = if (urls.isEmpty()) appString(R.string.screen_settings_004)
                    else urls.joinToString("\n") { "${dev.remotecode.data.routeLabel(it)}: $it" },
                onClick = onChangeServer,
            )
            SettingsRow(stringResource(R.string.screen_settings_006), Icons.Rounded.Sync, 1, 4) { repo.reconnectNow() }
            SettingsRow(
                stringResource(R.string.screen_settings_008), Icons.Rounded.NetworkCheck, 2, 4,
                summary = stringResource(R.string.screen_settings_007), onClick = onNetLog,
            )
            SettingsRow(
                stringResource(R.string.screen_settings_010), Icons.Rounded.AccountTree, 3, 4,
                summary = stringResource(R.string.screen_settings_009), onClick = onControlPaths,
            )

            SettingsHeading("PC")
            SettingsRow(
                stringResource(R.string.screen_settings_012), Icons.Rounded.Terminal, 0, 2,
                summary = stringResource(R.string.screen_settings_011), onClick = onTerminals,
            )
            ServerDependenciesSection(api = repo.api, server = dependencyServer, connected = conn is ConnState.Connected, refreshAvailability = { expected, fresh -> repo.refreshMeta(expected, fresh) })

            SettingsHeading(stringResource(R.string.screen_settings_013))
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

            SettingsHeading(stringResource(R.string.screen_settings_019))
            SettingsRow(
                stringResource(R.string.screen_settings_021), Icons.Rounded.NotificationsActive, 0, 2,
                summary = stringResource(R.string.screen_settings_020), checked = s.backgroundNotify,
            ) {
                val on = !s.backgroundNotify
                haptics.toggle(on)
                scope.launch { repo.settingsStore.setBackgroundNotify(on) }
                if (on) ConnectionService.start(context) else ConnectionService.stop(context)
            }
            SettingsRow(
                stringResource(R.string.screen_settings_023), Icons.Rounded.Notifications, 1, 2,
                summary = stringResource(R.string.screen_settings_022),
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }

            SettingsHeading(stringResource(R.string.screen_settings_024))
            SettingsRow(
                stringResource(R.string.screen_settings_026), Icons.Rounded.Palette, 0, 1,
                summary = stringResource(R.string.screen_settings_025), prominent = true, onClick = onAppearance,
            )
            LanguageSettingsItem()
            RegionalCurrencySettingsItem(s.localCurrency, meta, repo.settingsStore::setLocalCurrency)
            Spacer(Modifier.height(8.dp))
            Box {
                SettingsRow(
                    stringResource(R.string.screen_settings_027), Icons.Rounded.Lightbulb, 0, 4,
                    summary = ThinkingModes.description(s.thinking), value = ThinkingModes.label(s.thinking),
                ) { thinkingMenu = true }
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
            SettingsRow(
                stringResource(R.string.screen_settings_029), Icons.Rounded.Build, 1, 4,
                summary = stringResource(R.string.screen_settings_028), checked = s.toolDetail,
            ) {
                haptics.toggle(!s.toolDetail)
                scope.launch { repo.settingsStore.setToolDetail(!s.toolDetail) }
            }
            SettingsRow(stringResource(R.string.chat_chat_screen_054), Icons.Rounded.Visibility, 2, 4) {
                haptics.tick()
                showFooterDisplay = true
            }
            SettingsRow(
                stringResource(R.string.settings_background_commands), Icons.Rounded.Terminal, 3, 4,
                summary = stringResource(R.string.settings_background_commands_description), checked = s.showBackgroundCommands,
            ) {
                val on = !s.showBackgroundCommands
                haptics.toggle(on)
                scope.launch { repo.settingsStore.setShowBackgroundCommands(on) }
            }

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

            SettingsHeading(stringResource(R.string.screen_settings_030))
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
    if (showFooterDisplay) {
        FooterDisplayDialog(
            initial = s.footerDisplay,
            onSave = repo.settingsStore::setFooterDisplay,
            onDismiss = { showFooterDisplay = false },
            onSaveError = { Toast.makeText(context, appString(R.string.chat_chat_screen_038), Toast.LENGTH_SHORT).show() },
        )
    }
}

@Composable
private fun WrapSetting(title: Int, checked: Boolean, index: Int, onChange: (Boolean) -> Unit) {
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    SettingsRow(stringResource(title), Icons.AutoMirrored.Rounded.WrapText, index, 3, checked = checked) {
        haptics.toggle(!checked)
        onChange(!checked)
    }
}

@Composable
private fun SettingsHeading(title: String, modifier: Modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp)) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = modifier)
}

@Composable
private fun SettingsRow(
    title: String,
    icon: ImageVector,
    index: Int,
    count: Int,
    summary: String? = null,
    checked: Boolean? = null,
    value: String? = null,
    prominent: Boolean = false,
    onClick: () -> Unit,
) {
    val supporting: (@Composable () -> Unit)? = if (summary != null) ({ Text(summary) }) else null
    val trailing: (@Composable () -> Unit)? = when {
        checked != null -> ({ Switch(checked = checked, onCheckedChange = null) })
        value != null -> ({ Text(value, color = MaterialTheme.colorScheme.primary) })
        else -> null
    }
    val colors = MaterialTheme.colorScheme
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(containerColor = if (prominent) colors.primaryContainer else colors.surfaceContainer),
        leadingContent = { Icon(icon, null) },
        supportingContent = supporting,
        trailingContent = trailing,
    ) { Text(title) }
}

private fun appVersion(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
