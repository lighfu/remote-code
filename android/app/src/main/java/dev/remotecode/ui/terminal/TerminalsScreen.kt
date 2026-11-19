package dev.remotecode.ui.terminal

import dev.remotecode.R

import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.TerminalInfo
import dev.remotecode.data.connectionIdentity
import dev.remotecode.ui.common.hapticArea
import dev.remotecode.ui.common.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener



private fun ago(ms: Long): String {
    if (ms <= 0) return ""
    val s = (System.currentTimeMillis() - ms) / 1000
    return when {
        s < 60 -> appString(R.string.screen_terminals_001)
        s < 3600 -> appString(R.string.screen_terminals_002, s / 60)
        s < 86400 -> appString(R.string.screen_terminals_003, s / 3600)
        else -> appString(R.string.screen_terminals_004, s / 86400)
    }
}

/** cwd が空ならホームに新規作成する。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalsScreen(cwd: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val api = repo.api
    val identity by repo.activeServerIdentity.collectAsState()
    val target = api.server?.takeIf { it.connectionIdentity() == identity }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    var list by remember(identity) { mutableStateOf<List<TerminalInfo>?>(null) }
    var error by remember(identity) { mutableStateOf<String?>(null) }
    var closing by remember(identity) { mutableStateOf<TerminalInfo?>(null) }
    var stopping by remember(identity) { mutableStateOf(emptySet<String>()) }
    var creating by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(identity, tick) {
        if (target == null) return@LaunchedEffect
        while (true) {
            try {
                val rows = api.terminals()
                stopping = stopping.intersect(rows.map { it.id }.toSet())
                list = rows
                error = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            }
            delay(3000)
        }
    }

    fun create() {
        if (creating) return
        creating = true
        scope.launch {
            runCatching { api.terminalCreate(cwd.ifBlank { "~" }) }
                .onSuccess { haptics.confirm(); onOpen(it.id) }
                .onFailure { haptics.reject(); snackbar.showSnackbar(it.message ?: appString(R.string.screen_terminals_005)) }
            creating = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.screen_terminals_006))
                        if (cwd.isNotBlank()) Text(cwd, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.screen_terminals_007)) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = ::create, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(if (creating) appString(R.string.screen_terminals_008) else appString(R.string.screen_terminals_009)) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
            val l = list
            if (l != null && l.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Terminal, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.screen_terminals_012), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = ::create) { Text(stringResource(R.string.screen_terminals_013)) }
                    }
                }
            }
            val others = l.orEmpty().filter { it.kind == "external" }
            val own = l.orEmpty().filter { it.kind != "external" }
            if (others.isNotEmpty()) {
                item { SectionTitle(appString(R.string.screen_terminals_014)) }
                items(others, key = { it.id }) { t ->
                    TermCard(t, onOpen = { haptics.tick(); onOpen(t.id) }, closeEnabled = !t.self && t.id !in stopping, onClose = { closing = t })
                }
            }
            if (own.isNotEmpty()) {
                item { SectionTitle(appString(R.string.screen_terminals_015)) }
                items(own, key = { it.id }) { t ->
                    TermCard(t, onOpen = { haptics.tick(); onOpen(t.id) }, closeEnabled = t.id !in stopping, onClose = { closing = t })
                }
            }
        }
    }

    closing?.let { t ->
        AlertDialog(
            modifier = hapticArea(),
            onDismissRequest = { closing = null },
            title = { Text(stringResource(R.string.screen_terminals_016, t.title), maxLines = 3, overflow = TextOverflow.Ellipsis) },
            text = { Text(if (t.kind == "external") appString(R.string.screen_terminal_031) else if (t.alive) appString(R.string.screen_terminals_017) else appString(R.string.screen_terminals_018)) },
            confirmButton = {
                Button(onClick = {
                    closing = null
                    if (target == null || t.id in stopping) return@Button
                    stopping = stopping + t.id
                    scope.launch {
                        try {
                            if (t.kind == "external") api.terminalKill(t.id, expectedServer = target)
                            else api.terminalRemove(t.id, expectedServer = target)
                            tick++
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            stopping = stopping - t.id
                            snackbar.showSnackbar(e.message ?: appString(R.string.screen_terminals_019))
                        }
                    }
                }) { Text(stringResource(R.string.screen_terminals_020)) }
            },
            dismissButton = { TextButton(onClick = { closing = null }) { Text(stringResource(R.string.screen_terminals_021)) } },
        )
    }
}


@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

@Composable
private fun TermCard(t: TerminalInfo, onOpen: () -> Unit, closeEnabled: Boolean, onClose: () -> Unit) {
    val external = t.kind == "external"
    val title = t.running.ifBlank { t.title }
    var expanded by rememberSaveable(t.id) { mutableStateOf(false) }
    var truncated by remember(title) { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (external) {
                dev.remotecode.ui.common.BrandIcon(t.source.lowercase(), 28.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!external) Box(Modifier.size(8.dp).background(if (t.alive) Color(0xFF3FB950) else MaterialTheme.colorScheme.outline, CircleShape))
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow })
                    val time = if (!t.alive) appString(R.string.screen_terminals_022, t.exitCode ?: "?") else ago(t.lastOutputAt)
                    if (time.isNotEmpty()) Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (expanded || truncated) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(stringResource(if (expanded) R.string.cross_session_collapse else R.string.cross_session_expand))
                    }
                }
                val sub = if (external) "${t.source} · ${t.shell.removeSuffix(".exe")}" else t.cwd
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                if (t.self) Text(stringResource(R.string.screen_terminals_023), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                if (t.lastLine.isNotBlank()) {
                    Text(t.lastLine, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onClose, enabled = closeEnabled) { Icon(Icons.Rounded.Close, stringResource(R.string.screen_terminal_030)) }
        }
    }
}
