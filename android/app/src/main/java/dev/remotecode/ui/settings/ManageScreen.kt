package dev.remotecode.ui.settings

import dev.remotecode.R

import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ConfirmCommand
import dev.remotecode.data.McpDetail
import dev.remotecode.data.McpServerInfo
import dev.remotecode.data.PluginHit
import dev.remotecode.data.PluginsOverview
import dev.remotecode.data.SkillInfo
import dev.remotecode.data.SkillText
import dev.remotecode.ui.common.hapticArea
import dev.remotecode.ui.common.rememberHaptics
import dev.remotecode.ui.theme.Mono
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class Confirm(val title: String, val text: String, val action: String, val run: suspend () -> String)

@Composable
fun ManageScreen(onBack: () -> Unit) {
    ManageScaffold(
        title = stringResource(R.string.screen_manage_006),
        tabs = listOf(appString(R.string.screen_manage_002), "MCP", appString(R.string.screen_manage_003), appString(R.string.screen_manage_004), appString(R.string.screen_manage_005)),
        scrollableTabs = true,
        errorLabel = R.string.screen_manage_001,
        dismissLabel = R.string.screen_manage_009,
        introLabel = R.string.screen_manage_010,
        onBack = onBack,
    ) { tab, reload, act, ask ->
        when (tab) {
            0 -> SettingsTab("claude", reload, act)
            1 -> McpTab(reload, act, ask)
            2 -> PluginsTab(reload, act, ask)
            3 -> MarketTab(reload, act, ask)
            else -> SkillsTab(reload, act, ask)
        }
    }
}

@Composable
internal fun ManageScaffold(
    title: String,
    tabs: List<String>,
    scrollableTabs: Boolean = false,
    errorLabel: Int,
    dismissLabel: Int,
    introLabel: Int,
    onBack: () -> Unit,
    content: @Composable (Int, Int, (suspend () -> String) -> Unit, (Confirm) -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val haptics = rememberHaptics()
    var tab by rememberSaveable { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var reload by remember { mutableStateOf(0) }

    fun act(block: suspend () -> String) {
        busy = true
        scope.launch {
            try {
                val m = block()
                haptics.confirm()
                snackbar.showSnackbar(m)
            } catch (e: Exception) {
                haptics.reject()
                snackbar.showSnackbar(e.message ?: appString(errorLabel))
            }
            busy = false
            reload++
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.screen_manage_007)) } },
                    actions = { IconButton(onClick = { reload++ }) { Icon(Icons.Rounded.Refresh, stringResource(R.string.screen_manage_008)) } },
                )
                val tabItems: @Composable () -> Unit = {
                    tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { haptics.tick(); tab = i }, text = { Text(t) }) }
                }
                if (scrollableTabs) PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp, tabs = tabItems)
                else PrimaryTabRow(selectedTabIndex = tab, tabs = tabItems)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            content(tab, reload, ::act) { confirm = it }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            modifier = hapticArea(),
            onDismissRequest = { confirm = null },
            title = { Text(c.title) },
            text = { Text(c.text) },
            confirmButton = { Button(onClick = { confirm = null; act(c.run) }) { Text(c.action) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(dismissLabel)) } },
        )
    }
    LaunchedEffect(Unit) { snackbar.showSnackbar(appString(introLabel)) }
}

@Composable
internal fun <T> Loaded(reload: Int, key: Any? = null, load: suspend () -> T, content: @Composable (T) -> Unit) {
    var data by remember { mutableStateOf<T?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload, key) {
        error = null
        try {
            data = load()
        } catch (e: Exception) {
            error = e.message ?: appString(R.string.screen_manage_011)
        }
    }
    val d = data
    when {
        d != null -> content(d)
        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
        else -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
    }
}

@Composable
internal fun ItemCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val shape = RoundedCornerShape(20.dp)
    if (onClick != null) Card(onClick = onClick, colors = colors, shape = shape, modifier = Modifier.fillMaxWidth()) { content() }
    else Card(colors = colors, shape = shape, modifier = Modifier.fillMaxWidth()) { content() }
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
}


@Composable
private fun McpTab(reload: Int, act: (suspend () -> String) -> Unit, ask: (Confirm) -> Unit) {
    val api = RemoteCodeApp.instance.repository.api
    var detailOf by remember { mutableStateOf<McpServerInfo?>(null) }
    var adding by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Loaded(reload, load = { api.mcpList() }) { list ->
            LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Hint(appString(R.string.screen_manage_012)) }
                items(list, key = { it.name }) { s ->
                    ItemCard(onClick = { detailOf = s }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            val dot = when {
                                s.ok -> MaterialTheme.colorScheme.primary
                                s.disabled -> MaterialTheme.colorScheme.outline
                                else -> MaterialTheme.colorScheme.error
                            }
                            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.titleSmall)
                                Text(s.target, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.size(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(s.status, style = MaterialTheme.typography.labelSmall, color = dot)
                                if (s.connector) Text("claude.ai", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text(stringResource(R.string.screen_manage_013)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    detailOf?.let { s ->
        McpDetailSheet(
            name = s.name,
            load = { api.mcpGet(s.name) },
            hint = R.string.screen_manage_019,
            readOnlyHint = R.string.screen_manage_020.takeIf { s.connector },
            onDismiss = { detailOf = null },
        ) { scopeName ->
            detailOf = null
            ask(Confirm(appString(R.string.screen_manage_014, s.name), appString(R.string.screen_manage_015), appString(R.string.screen_manage_016)) {
                api.mcpRemove(s.name, scopeName); appString(R.string.screen_manage_017, s.name)
            })
        }
    }
    if (adding) McpAddDialog(onDismiss = { adding = false }) { body ->
        adding = false
        act { api.mcpAdd(body); appString(R.string.screen_manage_018) }
    }
}

@Composable
internal fun McpDetailSheet(
    name: String,
    load: suspend () -> McpDetail,
    hint: Int,
    onDismiss: () -> Unit,
    readOnlyHint: Int? = null,
    onRemove: (String?) -> Unit,
) {
    var detail by remember { mutableStateOf<McpDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(name) { runCatching { load() }.onSuccess { detail = it }.onFailure { error = it.message } }
    ModalBottomSheet(modifier = hapticArea(), onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(name, style = MaterialTheme.typography.titleLarge)
            when {
                detail != null -> SelectionContainer {
                    Text(detail!!.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
                }
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                else -> LoadingIndicator()
            }
            Hint(appString(hint))
            if (readOnlyHint != null) {
                Hint(appString(readOnlyHint))
            } else {
                OutlinedButton(onClick = { onRemove(detail?.scope) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.screen_manage_016))
                }
            }
        }
    }
}

@Composable
internal fun McpAddDialog(onDismiss: () -> Unit, scopeHint: String = appString(R.string.screen_manage_021), sse: Boolean = true, onAdd: (kotlinx.serialization.json.JsonObject) -> Unit) {
    var name by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("stdio") }
    var command by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }
    var env by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var headers by remember { mutableStateOf("") }
    fun pairs(text: String, sep: Regex) = text.lines().mapNotNull { l ->
        val m = sep.find(l) ?: return@mapNotNull null
        l.substring(0, m.range.first).trim().takeIf { it.isNotEmpty() }?.let { it to l.substring(m.range.last + 1).trim() }
    }
    AlertDialog(
        modifier = hapticArea(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_manage_013)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.trim() }, label = { Text(stringResource(R.string.screen_manage_022)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOfNotNull("stdio" to appString(R.string.screen_manage_023), "http" to "HTTP", if (sse) "sse" to "SSE" else null).forEach { (id, label) ->
                        FilterChip(selected = transport == id, onClick = { transport = id }, label = { Text(label) })
                    }
                }
                if (transport == "stdio") {
                    OutlinedTextField(command, { command = it }, label = { Text(stringResource(R.string.screen_manage_024)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(args, { args = it }, label = { Text(stringResource(R.string.screen_manage_025)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(env, { env = it }, label = { Text(stringResource(R.string.screen_manage_026)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(url, { url = it.trim() }, label = { Text("URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(headers, { headers = it }, label = { Text(stringResource(R.string.screen_manage_027)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                }
                Hint(scopeHint)
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && (if (transport == "stdio") command.isNotBlank() else url.isNotBlank()),
                onClick = {
                    onAdd(buildJsonObject {
                        put("name", name)
                        put("transport", transport)
                        put("scope", "user")
                        if (transport == "stdio") {
                            put("command", command.trim())
                            put("args", JsonArray(args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { JsonPrimitive(it) }))
                            put("env", kotlinx.serialization.json.JsonObject(pairs(env, Regex("=")).associate { (k, v) -> k to JsonPrimitive(v) }))
                        } else {
                            put("url", url)
                            put("headers", kotlinx.serialization.json.JsonObject(pairs(headers, Regex(":")).associate { (k, v) -> k to JsonPrimitive(v) }))
                        }
                    })
                },
            ) { Text(stringResource(R.string.screen_manage_028)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.screen_manage_009)) } },
    )
}


@Composable
private fun PluginsTab(reload: Int, act: (suspend () -> String) -> Unit, ask: (Confirm) -> Unit) {
    val api = RemoteCodeApp.instance.repository.api
    val haptics = rememberHaptics()
    var query by rememberSaveable { mutableStateOf("") }
    var searched by remember { mutableStateOf<String?>(null) }
    var needConfirm by remember { mutableStateOf<Pair<PluginHit, ConfirmCommand>?>(null) }

    fun install(hit: PluginHit, accept: String? = null) = act {
        val r = api.pluginInstall(hit.id, accept)
        val c = r.needsConfirm
        if (c != null) {
            needConfirm = hit to c
            r.message
        } else appString(R.string.screen_manage_029, hit.name.ifBlank { hit.id })
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            placeholder = { Text(stringResource(R.string.screen_manage_030)) },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { searched = query.trim() }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            trailingIcon = { if (query.isNotBlank()) TextButton(onClick = { searched = query.trim() }) { Text(stringResource(R.string.screen_manage_031)) } },
            modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp),
        )
        val s = searched
        if (!s.isNullOrBlank()) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.screen_manage_032, s), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                TextButton(onClick = { searched = null; query = "" }) { Text(stringResource(R.string.screen_manage_033)) }
            }
            Loaded(reload, key = s, load = { api.pluginSearch(s) }) { hits ->
                LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hits.isEmpty()) item { Hint(appString(R.string.screen_manage_034)) }
                    items(hits, key = { it.id }) { h ->
                        ItemCard {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(h.name.ifBlank { h.id }, style = MaterialTheme.typography.titleSmall)
                                        Text(h.marketplace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (h.installed) Text(stringResource(R.string.screen_manage_035), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    else Button(onClick = { install(h) }) { Text(stringResource(R.string.screen_manage_036)) }
                                }
                                if (h.description.isNotBlank()) Text(h.description, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        } else {
            Loaded(reload, load = { api.plugins() }) { o: PluginsOverview ->
                LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text(stringResource(R.string.screen_manage_037, o.installed.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    items(o.installed, key = { it.id }) { p ->
                        ItemCard {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(p.id.substringBefore('@'), style = MaterialTheme.typography.titleSmall)
                                        Text("${p.id.substringAfter('@', "")} · ${p.version.take(12)} · ${p.scope}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = p.enabled, onCheckedChange = { on ->
                                        haptics.toggle(on)
                                        act { api.pluginAction(p.id, if (on) "enable" else "disable", p.scope); if (on) appString(R.string.screen_manage_038) else appString(R.string.screen_manage_039) }
                                    })
                                }
                                if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { act { api.pluginAction(p.id, "update"); appString(R.string.screen_manage_040) } }) {
                                        Icon(Icons.Rounded.SystemUpdateAlt, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.screen_manage_041))
                                    }
                                    TextButton(onClick = {
                                        ask(Confirm(appString(R.string.screen_manage_042, p.id), appString(R.string.screen_manage_043), appString(R.string.screen_manage_044)) {
                                            api.pluginAction(p.id, "uninstall", p.scope); appString(R.string.screen_manage_045)
                                        })
                                    }) { Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.screen_manage_044)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    needConfirm?.let { (hit, c) ->
        AlertDialog(
            modifier = hapticArea(),
            onDismissRequest = { needConfirm = null },
            title = { Text(stringResource(R.string.screen_manage_046)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.screen_manage_047, hit.name.ifBlank { hit.id }))
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(10.dp)) {
                        SelectionContainer { Text(c.command, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.padding(10.dp).heightIn(max = 200.dp).verticalScroll(rememberScrollState())) }
                    }
                }
            },
            confirmButton = { Button(onClick = { needConfirm = null; install(hit, c.sha256) }) { Text(stringResource(R.string.screen_manage_048)) } },
            dismissButton = { TextButton(onClick = { needConfirm = null }) { Text(stringResource(R.string.screen_manage_009)) } },
        )
    }
}


@Composable
private fun MarketTab(reload: Int, act: (suspend () -> String) -> Unit, ask: (Confirm) -> Unit) {
    val api = RemoteCodeApp.instance.repository.api
    var source by rememberSaveable { mutableStateOf("") }
    Loaded(reload, load = { api.plugins() }) { o ->
        LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                ItemCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.screen_manage_049), style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(source, { source = it.trim() }, placeholder = { Text(stringResource(R.string.screen_manage_050)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(enabled = source.isNotBlank(), onClick = { val s = source; source = ""; act { api.marketplaceAdd(s) } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.screen_manage_028)) }
                    }
                }
            }
            item { Text(stringResource(R.string.screen_manage_051, o.marketplaces.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
            items(o.marketplaces, key = { it.name }) { m ->
                ItemCard {
                    Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.titleSmall)
                            Text(m.source, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { act { api.marketplaceAction(m.name, "update") } }) { Icon(Icons.Rounded.Refresh, stringResource(R.string.screen_manage_041)) }
                        IconButton(onClick = {
                            ask(Confirm(appString(R.string.screen_manage_014, m.name), appString(R.string.screen_manage_052), appString(R.string.screen_manage_016)) { api.marketplaceAction(m.name, "remove") })
                        }) { Icon(Icons.Rounded.Delete, stringResource(R.string.screen_manage_053)) }
                    }
                }
            }
        }
    }
}


@Composable
private fun SkillsTab(reload: Int, act: (suspend () -> String) -> Unit, ask: (Confirm) -> Unit) {
    val api = RemoteCodeApp.instance.repository.api
    var open by remember { mutableStateOf<SkillInfo?>(null) }
    var creating by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Loaded(reload, load = { api.skills() }) { list ->
            LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.path }) { s ->
                    ItemCard(onClick = { open = s }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Text(s.source, style = MaterialTheme.typography.labelSmall, color = if (s.editable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (s.description.isNotBlank()) Text(s.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text(stringResource(R.string.screen_manage_054)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    open?.let { s ->
        SkillEditorSheet(s.name, s.source, s.path, load = { api.skillRead(s.path) }, onDismiss = { open = null }, onSave = { text -> open = null; act { api.skillWrite(s.path, text); appString(R.string.screen_manage_055) } }) {
            open = null
            ask(Confirm(appString(R.string.screen_manage_014, s.name), appString(R.string.screen_manage_056, s.name), appString(R.string.screen_manage_016)) { api.skillDelete(s.path); appString(R.string.screen_manage_057) })
        }
    }
    if (creating) SkillCreateDialog(onDismiss = { creating = false }) { name, desc, body ->
        creating = false
        act { api.skillCreate(name, desc, body); appString(R.string.screen_manage_058, name) }
    }
}

@Composable
internal fun SkillEditorSheet(
    name: String,
    source: String,
    path: String,
    load: suspend () -> SkillText,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    cancelLabel: Int = R.string.screen_manage_009,
    editLabel: Int = R.string.screen_manage_060,
    readOnlyHint: Int = R.string.screen_manage_061,
    onDelete: () -> Unit,
) {
    var loaded by remember { mutableStateOf<SkillText?>(null) }
    var text by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) { runCatching { load() }.onSuccess { loaded = it; text = it.text }.onFailure { error = it.message } }
    ModalBottomSheet(modifier = hapticArea(), onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(source, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val l = loaded
            when {
                l == null && error == null -> LoadingIndicator()
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                editing -> OutlinedTextField(text, { text = it }, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 460.dp))
                else -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
                    SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.padding(12.dp).heightIn(max = 460.dp).verticalScroll(rememberScrollState())) }
                }
            }
            if (l?.editable == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editing) {
                        Button(onClick = { onSave(text) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.screen_manage_059)) }
                        OutlinedButton(onClick = { editing = false; text = l.text }) { Text(stringResource(cancelLabel)) }
                    } else {
                        Button(onClick = { editing = true }, modifier = Modifier.weight(1f)) { Text(stringResource(editLabel)) }
                        OutlinedButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.screen_manage_053)) }
                    }
                }
            } else if (l != null) {
                Hint(appString(readOnlyHint))
            }
        }
    }
}

@Composable
internal fun SkillCreateDialog(onDismiss: () -> Unit, where: String = appString(R.string.screen_manage_062), onCreate: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    AlertDialog(
        modifier = hapticArea(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_manage_054)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.trim().lowercase() }, label = { Text(stringResource(R.string.screen_manage_063)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(desc, { desc = it }, label = { Text(stringResource(R.string.screen_manage_064)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.screen_manage_065)) }, minLines = 5, modifier = Modifier.fillMaxWidth())
                Hint(appString(R.string.screen_manage_066, where))
            }
        },
        confirmButton = { Button(enabled = name.isNotBlank() && desc.isNotBlank(), onClick = { onCreate(name, desc.trim(), body) }) { Text(stringResource(R.string.screen_manage_067)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.screen_manage_009)) } },
    )
}
