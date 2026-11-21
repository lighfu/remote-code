package dev.remotecode.ui.settings

import dev.remotecode.R

import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.AgentSkill
import dev.remotecode.data.McpServerInfo
import dev.remotecode.data.SettingField
import dev.remotecode.data.SettingFile
import dev.remotecode.ui.common.hapticArea
import dev.remotecode.ui.common.rememberHaptics
import dev.remotecode.ui.theme.Mono

@Composable
fun AgentManageScreen(provider: String, onBack: () -> Unit) {
    val label = if (provider == "codex") "Codex" else "Antigravity"
    ManageScaffold(
        title = stringResource(R.string.screen_agent_manage_005, label),
        tabs = listOf(appString(R.string.screen_agent_manage_002), "MCP", appString(R.string.screen_agent_manage_003), appString(R.string.screen_agent_manage_004)),
        errorLabel = R.string.screen_agent_manage_001,
        dismissLabel = R.string.screen_agent_manage_008,
        introLabel = R.string.screen_agent_manage_009,
        onBack = onBack,
    ) { tab, reload, act, ask ->
        when (tab) {
            0 -> SettingsTab(provider, reload, act)
            1 -> AgentMcpTab(provider, reload, act, ask)
            2 -> AgentSkillsTab(provider, reload, act, ask)
            else -> AgentPluginsTab(provider, reload, act, ask)
        }
    }
}


@Composable
internal fun SettingsTab(provider: String, reload: Int, act: (suspend () -> String) -> Unit) {
    val api = RemoteCodeApp.instance.repository.api
    var editing by remember { mutableStateOf<SettingField?>(null) }
    var file by remember { mutableStateOf<SettingFile?>(null) }
    Loaded(reload, load = { api.agentSettings(provider) }) { s ->
        LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(R.string.screen_agent_manage_010), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            items(s.fields, key = { it.key }) { f ->
                ItemCard(onClick = if (f.type == "bool") null else ({ editing = f })) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.label, style = MaterialTheme.typography.titleSmall)
                            if (f.description.isNotBlank()) Text(f.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.size(8.dp))
                        if (f.type == "bool") {
                            Switch(checked = f.value == "true", onCheckedChange = { on -> act { api.agentSettingSet(provider, f.key, on.toString()); appString(R.string.screen_agent_manage_011) } })
                        } else {
                            val shown = f.options.firstOrNull { it.id == f.value }?.label ?: f.value ?: appString(R.string.screen_agent_manage_012)
                            Text(shown, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.6f, fill = false))
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.screen_agent_manage_013), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp)) }
            items(s.files, key = { it.id }) { f ->
                ItemCard(onClick = { file = f }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Description, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.label, style = MaterialTheme.typography.titleSmall)
                            Text(f.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (f.exists) f.path else appString(R.string.screen_agent_manage_014, f.path), style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    editing?.let { f ->
        SettingDialog(f, onDismiss = { editing = null }) { v ->
            editing = null
            act { api.agentSettingSet(provider, f.key, v); appString(R.string.screen_agent_manage_015, f.label) }
        }
    }
    file?.let { f ->
        FileSheet(provider, f, onDismiss = { file = null }) { text ->
            file = null
            act { api.agentFileWrite(provider, f.id, text); appString(R.string.screen_agent_manage_016, f.label) }
        }
    }
}

@Composable
private fun SettingDialog(f: SettingField, onDismiss: () -> Unit, onSet: (String?) -> Unit) {
    var text by remember { mutableStateOf(f.value.orEmpty()) }
    AlertDialog(
        modifier = hapticArea(),
        onDismissRequest = onDismiss,
        title = { Text(f.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (f.description.isNotBlank()) Hint(f.description)
                if (f.type == "choice") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        f.options.forEach { o -> FilterChip(selected = f.value == o.id, onClick = { onSet(o.id) }, label = { Text(o.label) }) }
                    }
                } else {
                    OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (f.type != "choice") Button(onClick = { onSet(text.trim().ifEmpty { null }) }) { Text(stringResource(R.string.screen_agent_manage_017)) }
        },
        dismissButton = {
            Row {
                if (f.value != null) TextButton(onClick = { onSet(null) }) { Text(stringResource(R.string.screen_agent_manage_018)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.screen_agent_manage_008)) }
            }
        },
    )
}

@Composable
private fun FileSheet(provider: String, f: SettingFile, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val api = RemoteCodeApp.instance.repository.api
    var loaded by remember { mutableStateOf<dev.remotecode.data.SettingFileText?>(null) }
    var text by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(f.id) { runCatching { api.agentFileRead(provider, f.id) }.onSuccess { loaded = it; text = it.text }.onFailure { error = it.message } }
    ModalBottomSheet(modifier = hapticArea(), onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(f.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(f.path, style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val l = loaded
            when {
                l == null && error == null -> LoadingIndicator()
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                editing -> OutlinedTextField(text, { text = it }, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 460.dp))
                else -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
                    SelectionContainer {
                        Text(text.ifEmpty { appString(R.string.screen_agent_manage_019) }, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.padding(12.dp).fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()))
                    }
                }
            }
            if (l != null) {
                if (l.masked) {
                    Hint(appString(R.string.screen_agent_manage_020))
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (editing) {
                            Button(onClick = { onSave(text) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.screen_agent_manage_017)) }
                            OutlinedButton(onClick = { editing = false; text = l.text }) { Text(stringResource(R.string.screen_agent_manage_008)) }
                        } else {
                            Button(onClick = { editing = true }, modifier = Modifier.weight(1f)) { Text(if (l.text.isEmpty()) appString(R.string.screen_agent_manage_021) else appString(R.string.screen_agent_manage_022)) }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun AgentMcpTab(provider: String, reload: Int, act: (suspend () -> String) -> Unit, ask: (Confirm) -> Unit) {
    val api = RemoteCodeApp.instance.repository.api
    val haptics = rememberHaptics()
    var detailOf by remember { mutableStateOf<McpServerInfo?>(null) }
    var adding by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Loaded(reload, load = { api.agentMcpList(provider) }) { list ->
            LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (list.isEmpty()) item { Hint(appString(R.string.screen_agent_manage_023)) }
                items(list, key = { it.name }) { s ->
                    ItemCard(onClick = { detailOf = s }) {
                        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            val dot = if (s.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.titleSmall)
                                Text(s.target, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Switch(checked = s.ok, onCheckedChange = { on ->
                                haptics.toggle(on)
                                act { api.agentMcpEnable(provider, s.name, on); if (on) appString(R.string.screen_agent_manage_024, s.name) else appString(R.string.screen_agent_manage_025, s.name) }
                            })
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text(stringResource(R.string.screen_agent_manage_026)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    detailOf?.let { s ->
        McpDetailSheet(s.name, load = { api.agentMcpGet(provider, s.name) }, hint = R.string.screen_agent_manage_034, onDismiss = { detailOf = null }) {
            detailOf = null
            ask(Confirm(appString(R.string.screen_agent_manage_027, s.name), appString(R.string.screen_agent_manage_028), appString(R.string.screen_agent_manage_029)) { api.agentMcpRemove(provider, s.name); appString(R.string.screen_agent_manage_030, s.name) })
        }
    }
    if (adding) McpAddDialog(
        onDismiss = { adding = false },
        scopeHint = if (provider == "codex") appString(R.string.screen_agent_manage_031) else appString(R.string.screen_agent_manage_032),
        sse = false,
    ) { body ->
        adding = false
        act { api.agentMcpAdd(provider, body); appString(R.string.screen_agent_manage_033) }
    }
}

@Composable
private fun AgentSkillsTab(provider: String, reload: Int, act: (suspend () -> String) -> Unit, ask: (Confirm) -> Unit) {
    val api = RemoteCodeApp.instance.repository.api
    val haptics = rememberHaptics()
    var open by remember { mutableStateOf<AgentSkill?>(null) }
    var creating by remember { mutableStateOf(false) }
    val where = if (provider == "codex") appString(R.string.screen_agent_manage_035) else appString(R.string.screen_agent_manage_036)
    Box(Modifier.fillMaxSize()) {
        Loaded(reload, load = { api.agentSkills(provider) }) { list ->
            LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.path }) { s ->
                    ItemCard(onClick = { open = s }) {
                        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                                    Spacer(Modifier.size(8.dp))
                                    Text(s.source, style = MaterialTheme.typography.labelSmall, color = if (s.editable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                if (s.description.isNotBlank()) Text(s.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            if (s.enabled != null) {
                                Switch(checked = s.enabled, onCheckedChange = { on ->
                                    haptics.toggle(on)
                                    act { api.agentSkillEnable(provider, s.path, on); if (on) appString(R.string.screen_agent_manage_024, s.name) else appString(R.string.screen_agent_manage_025, s.name) }
                                })
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text(stringResource(R.string.screen_agent_manage_037)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    open?.let { s ->
        SkillEditorSheet(
            s.name, s.source, s.path,
            load = { api.agentSkillRead(provider, s.path) },
            onDismiss = { open = null },
            onSave = { text -> open = null; act { api.agentSkillWrite(provider, s.path, text); appString(R.string.screen_agent_manage_038) } },
            cancelLabel = R.string.screen_agent_manage_008,
            editLabel = R.string.screen_agent_manage_022,
            readOnlyHint = R.string.screen_agent_manage_043,
        ) {
            open = null
            ask(Confirm(appString(R.string.screen_agent_manage_027, s.name), appString(R.string.screen_agent_manage_039), appString(R.string.screen_agent_manage_029)) { api.agentSkillDelete(provider, s.path); appString(R.string.screen_agent_manage_040) })
        }
    }
    if (creating) SkillCreateDialog(onDismiss = { creating = false }, where = where) { name, desc, body ->
        creating = false
        act { api.agentSkillCreate(provider, name, desc, body); appString(R.string.screen_agent_manage_041, name) }
    }
}

@Composable
private fun AgentPluginsTab(provider: String, reload: Int, act: (suspend () -> String) -> Unit, ask: (Confirm) -> Unit) {
    val api = RemoteCodeApp.instance.repository.api
    val haptics = rememberHaptics()
    var target by rememberSaveable { mutableStateOf("") }
    Loaded(reload, load = { api.agentPlugins(provider) }) { list ->
        LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                ItemCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.screen_agent_manage_044), style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(target, { target = it.trim() }, placeholder = { Text(stringResource(R.string.screen_agent_manage_045)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(enabled = target.isNotBlank(), onClick = { val t = target; target = ""; act { api.agentPluginAction(provider, t, "install") } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.screen_agent_manage_046)) }
                    }
                }
            }
            item { Text(stringResource(R.string.screen_agent_manage_047, list.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
            if (list.isEmpty()) item { Hint(appString(R.string.screen_agent_manage_048)) }
            items(list, key = { it.id }) { p ->
                ItemCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name.ifBlank { p.id }, style = MaterialTheme.typography.titleSmall)
                                Text(listOf(p.source, p.version.take(16)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (p.canToggle) {
                                Switch(checked = p.enabled, onCheckedChange = { on ->
                                    haptics.toggle(on)
                                    act { api.agentPluginAction(provider, p.id, if (on) "enable" else "disable") }
                                })
                            } else if (!p.enabled) {
                                Text(stringResource(R.string.screen_agent_manage_049), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                        if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = {
                            ask(Confirm(appString(R.string.screen_agent_manage_050, p.name.ifBlank { p.id }), appString(R.string.screen_agent_manage_051), appString(R.string.screen_agent_manage_052)) {
                                api.agentPluginAction(provider, p.id, "uninstall")
                            })
                        }) { Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.screen_agent_manage_052)) }
                    }
                }
            }
        }
    }
}
