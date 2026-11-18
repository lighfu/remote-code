package dev.remotecode.ui.terminal

import dev.remotecode.R

import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import dev.remotecode.ui.common.AppDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.AppJson
import dev.remotecode.data.TerminalInfo
import dev.remotecode.ui.common.hapticArea
import dev.remotecode.ui.common.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** 色: -1 は既定、0〜255 は色番号、0x1000000 以上は RGB。 */
private data class Run(val text: String, val fg: Int = -1, val bg: Int = -1, val flags: Int = 0)

private typealias Line = List<Run>

private fun parseLine(a: JsonArray): Line = a.map { r ->
    val x = r.jsonArray
    if (x.size == 1) Run(x[0].jsonPrimitive.content) else Run(x[0].jsonPrimitive.content, x[1].jsonPrimitive.int, x[2].jsonPrimitive.int, x[3].jsonPrimitive.int)
}

private val DarkAnsi = longArrayOf(
    0xFF3B3B3B, 0xFFF2777A, 0xFF8CCF7E, 0xFFE5C76B, 0xFF6CB6FF, 0xFFD69CF0, 0xFF6CD4CF, 0xFFCFCFCF,
    0xFF7A7A7A, 0xFFFF9C9F, 0xFFA8E890, 0xFFFFE08A, 0xFF96CCFF, 0xFFE8B8FF, 0xFF8EEAE5, 0xFFFFFFFF,
)
private val LightAnsi = longArrayOf(
    0xFF2E2E2E, 0xFFC4262B, 0xFF2F7D32, 0xFF8A6A00, 0xFF1F5FBF, 0xFF8A3FB0, 0xFF0F7B7B, 0xFF6E6E6E,
    0xFF5A5A5A, 0xFFE0383E, 0xFF3C9A40, 0xFFA88200, 0xFF2F76E0, 0xFFA552CC, 0xFF149494, 0xFF1A1A1A,
)

private fun xtermColor(n: Int, dark: Boolean): Color {
    if (n >= 0x1000000) return Color(0xFF000000 or (n and 0xFFFFFF).toLong())
    if (n < 16) return Color((if (dark) DarkAnsi else LightAnsi)[n])
    if (n < 232) {
        val i = n - 16
        val step = intArrayOf(0, 95, 135, 175, 215, 255)
        return Color(step[i / 36], step[(i / 6) % 6], step[i % 6])
    }
    val g = 8 + (n - 232) * 10
    return Color(g, g, g)
}

private class Palette(val dark: Boolean, val fg: Color, val bg: Color, val cursorFg: Color, val cursorBg: Color)

private fun annotate(line: Line, cursorX: Int?, p: Palette): AnnotatedString = buildAnnotatedString {
    var col = 0
    fun styleOf(r: Run, cursor: Boolean): SpanStyle {
        var fg = if (r.fg == -1) p.fg else xtermColor(r.fg, p.dark)
        var bg = if (r.bg == -1) Color.Unspecified else xtermColor(r.bg, p.dark)
        if (r.flags and 8 != 0) {
            val f = fg
            fg = if (bg == Color.Unspecified) p.bg else bg
            bg = f
        }
        if (r.flags and 16 != 0) fg = fg.copy(alpha = 0.6f)
        if (cursor) {
            fg = p.cursorFg
            bg = p.cursorBg
        }
        return SpanStyle(
            color = fg,
            background = bg,
            fontWeight = if (r.flags and 1 != 0) FontWeight.Bold else null,
            fontStyle = if (r.flags and 2 != 0) FontStyle.Italic else null,
            textDecoration = if (r.flags and 4 != 0) TextDecoration.Underline else null,
        )
    }
    for (r in line) {
        val cx = cursorX
        if (cx != null && cx >= col && cx < col + r.text.length) {
            val k = cx - col
            withStyle(styleOf(r, false)) { append(r.text.substring(0, k)) }
            withStyle(styleOf(r, true)) { append(r.text[k]) }
            withStyle(styleOf(r, false)) { append(r.text.substring(k + 1)) }
        } else {
            withStyle(styleOf(r, false)) { append(r.text) }
        }
        col += r.text.length
    }
    if (cursorX != null && cursorX >= col) {
        append(" ".repeat(cursorX - col))
        withStyle(SpanStyle(color = p.cursorFg, background = p.cursorBg)) { append(' ') }
    }
}

/** appSeq はアプリケーションカーソルモード用。 */
private data class Key(val label: String, val seq: String, val appSeq: String = seq)

private val Keys = listOf(
    Key("Esc", "\u001b"),
    Key("Tab", "\t"),
    Key("Ctrl+C", "\u0003"),
    Key("↑", "\u001b[A", "\u001bOA"),
    Key("↓", "\u001b[B", "\u001bOB"),
    Key("←", "\u001b[D", "\u001bOD"),
    Key("→", "\u001b[C", "\u001bOC"),
    Key("Enter", "\r"),
    Key("Ctrl+D", "\u0004"),
    Key("Ctrl+Z", "\u001a"),
    Key("Ctrl+L", "\u000c"),
    Key("Home", "\u001b[H", "\u001bOH"),
    Key("End", "\u001b[F", "\u001bOF"),
    Key("PgUp", "\u001b[5~"),
    Key("PgDn", "\u001b[6~"),
    Key("y", "y"),
    Key("n", "n"),
)

@Composable
fun TerminalScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val api = RemoteCodeApp.instance.repository.api
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    val prefs = remember { context.getSharedPreferences("terminal", Context.MODE_PRIVATE) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val scheme = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val palette = remember(scheme, dark) { Palette(dark, scheme.onSurface, scheme.surfaceContainerLowest, scheme.onPrimary, scheme.primary) }

    val lines = remember { mutableStateListOf<Line>() }
    var cursor by remember { mutableStateOf(0 to 0) }
    var appCursor by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<TerminalInfo?>(null) }
    var connected by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var exited by remember { mutableStateOf<Int?>(null) }
    var fontSize by remember { mutableIntStateOf(prefs.getInt("fontSize", 12)) }
    var input by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmKill by remember { mutableStateOf(false) }
    var serverCols by remember { mutableIntStateOf(0) }
    // ホスト端末の停止キーは確認する。
    var confirmKey by remember { mutableStateOf<Key?>(null) }
    val external = info?.kind == "external" || id.startsWith("ext-")
    val list = rememberLazyListState()
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(atBottom, list.isScrollInProgress) { if (list.isScrollInProgress) follow = atBottom }

    val holder = remember { object { var ws: WebSocket? = null; var disposed = false; var size: Pair<Int, Int>? = null } }

    fun sendJson(o: JsonObject): Boolean = holder.ws?.send(o.toString()) == true

    fun send(data: String) {
        if (!sendJson(buildJsonObject { put("type", "input"); put("data", data) })) {
            scope.launch { runCatching { api.terminalWrite(id, data) }.onFailure { snackbar.showSnackbar(it.message ?: appString(R.string.screen_terminal_001)) } }
        }
    }

    fun sendSize() {
        val (c, r) = holder.size ?: return
        sendJson(buildJsonObject { put("type", "resize"); put("cols", c); put("rows", r) })
    }

    fun applyScreen(m: JsonObject) {
        val reset = m["reset"]?.jsonPrimitive?.booleanOrNull == true
        val drop = m["drop"]?.jsonPrimitive?.intOrNull ?: 0
        val from = m["from"]?.jsonPrimitive?.intOrNull ?: 0
        val newLines = m["lines"]?.jsonArray?.map { parseLine(it.jsonArray) }.orEmpty()
        val cur = m["cursor"]?.jsonArray?.let { it[0].jsonPrimitive.int to it[1].jsonPrimitive.int }
        val app = m["appCursor"]?.jsonPrimitive?.booleanOrNull == true
        val cols = m["cols"]?.jsonPrimitive?.intOrNull ?: 0
        main.post {
            if (reset) lines.clear()
            if (drop > 0) lines.removeRange(0, drop.coerceAtMost(lines.size))
            if (from < lines.size) lines.removeRange(from, lines.size)
            if (from > lines.size) {
                sendJson(buildJsonObject { put("type", "resync") })
                return@post
            }
            lines.addAll(newLines)
            if (cur != null) cursor = cur
            appCursor = app
            serverCols = cols
            loaded = true
        }
    }

    fun connect() {
        if (holder.disposed) return
        holder.ws = api.terminalSocket(id, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post { connected = true; sendSize() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                when (m["type"]?.jsonPrimitive?.contentOrNull) {
                    "screen" -> applyScreen(m)
                    "info" -> {
                        val i = m["info"]?.let { runCatching { AppJson.decodeFromJsonElement(TerminalInfo.serializer(), it) }.getOrNull() }
                        main.post { info = i }
                    }
                    "exit" -> main.post { exited = m["code"]?.jsonPrimitive?.intOrNull ?: -1 }
                    "error" -> main.post { scope.launch { snackbar.showSnackbar(m["message"]?.jsonPrimitive?.contentOrNull ?: appString(R.string.screen_terminal_002)) } }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost()

            private fun lost() {
                main.post {
                    connected = false
                    if (!holder.disposed && exited == null) main.postDelayed({ connect() }, 2000)
                }
            }
        })
    }

    DisposableEffect(id) {
        holder.disposed = false
        connect()
        onDispose {
            holder.disposed = true
            holder.ws?.close(1000, null)
            holder.ws = null
        }
    }

    LaunchedEffect(lines.size, cursor) {
        if (follow && lines.isNotEmpty()) list.scrollToItem(lines.size - 1)
    }

    fun submit() {
        haptics.tick()
        send(input.replace("\r\n", "\n").replace("\n", "\r") + "\r")
        input = ""
        follow = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(info?.title ?: appString(R.string.screen_terminal_003), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val status = when {
                            exited != null -> appString(R.string.screen_terminal_004, exited)
                            !connected -> appString(R.string.screen_terminal_005)
                            else -> info?.cwd.orEmpty()
                        }
                        Text(status, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.screen_terminal_006)) } },
                actions = {
                    IconButton(onClick = { confirmKill = true }, enabled = exited == null) { Icon(Icons.Rounded.PowerSettingsNew, stringResource(R.string.screen_terminal_007), tint = if (exited == null) scheme.error else scheme.onSurfaceVariant) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.screen_terminal_008)) }
                        DropdownMenu(modifier = hapticArea(), expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.screen_terminal_009)) }, onClick = {
                                fontSize = (fontSize + 1).coerceAtMost(22); prefs.edit().putInt("fontSize", fontSize).apply()
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.screen_terminal_010)) }, onClick = {
                                fontSize = (fontSize - 1).coerceAtLeast(7); prefs.edit().putInt("fontSize", fontSize).apply()
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.screen_terminal_011)) }, onClick = {
                                menu = false
                                val text = lines.joinToString("\n") { l -> l.joinToString("") { it.text }.trimEnd() }.trimEnd()
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("terminal", text))
                                Toast.makeText(context, appString(R.string.screen_terminal_012), Toast.LENGTH_SHORT).show()
                            })
                            if (!external) DropdownMenuItem(text = { Text(stringResource(R.string.screen_terminal_013)) }, onClick = { menu = false; renaming = true })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            if (info?.self == true) {
                Text(
                    stringResource(R.string.screen_terminal_014),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onTertiaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 6.dp)
                        .background(scheme.tertiaryContainer, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Surface(
                color = palette.bg,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                val measurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = (fontSize * 1.25f).sp, color = palette.fg)
                BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
                    val hScroll = rememberScrollState()
                    val cell = remember(fontSize) { measurer.measure("MMMMMMMMMM", style).size.width / 10f }
                    val lineH = with(density) { (fontSize * 1.25f).sp.toPx() }
                    val cols = (constraints.maxWidth / cell).toInt().coerceAtLeast(20)
                    val rows = (constraints.maxHeight / lineH).toInt().coerceAtLeast(5)
                    LaunchedEffect(cols, rows) {
                        delay(150)
                        holder.size = cols to rows
                        sendSize()
                    }
                    if (!loaded) {
                        LoadingIndicator(Modifier.align(Alignment.Center))
                    } else {
                        // 外部端末はリサイズできないため横スクロールする。
                        val contentW = with(density) { maxOf(constraints.maxWidth.toFloat(), serverCols * cell).toDp() }
                        SelectionContainer {
                            LazyColumn(state = list, modifier = Modifier.fillMaxHeight().horizontalScroll(hScroll).width(contentW)) {
                                items(lines.size) { i ->
                                    val (cx, cy) = cursor
                                    Text(
                                        annotate(lines[i], if (i == cy && exited == null) cx else null, palette),
                                        style = style,
                                        softWrap = false,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    if (!atBottom && loaded) {
                        SmallFloatingActionButton(
                            onClick = { follow = true; scope.launch { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size - 1) } },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                        ) { Icon(Icons.Rounded.KeyboardDoubleArrowDown, stringResource(R.string.screen_terminal_015)) }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Keys.forEach { k ->
                    FilledTonalButton(
                        onClick = {
                            haptics.tick(); follow = true
                            if (info?.self == true && k.label in listOf("Ctrl+C", "Ctrl+D", "Ctrl+Z")) confirmKey = k else send(if (appCursor) k.appSeq else k.seq)
                        },
                        enabled = exited == null,
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.height(36.dp),
                    ) { Text(k.label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 8.dp).navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.screen_terminal_016)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    enabled = exited == null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                FilledIconButton(
                    onClick = ::submit,
                    enabled = exited == null,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(52.dp),
                ) { Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.screen_terminal_017)) }
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(info?.title.orEmpty()) }
        AlertDialog(
            modifier = hapticArea(),
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.screen_terminal_013)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                Button(enabled = name.isNotBlank(), onClick = {
                    renaming = false
                    val n = name.trim()
                    scope.launch {
                        runCatching { api.terminalRename(id, n) }.onSuccess { info = info?.copy(title = n) }.onFailure { snackbar.showSnackbar(it.message ?: appString(R.string.screen_terminal_018)) }
                    }
                }) { Text(stringResource(R.string.screen_terminal_019)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.screen_terminal_020)) } },
        )
    }
    confirmKey?.let { k ->
        AlertDialog(
            modifier = hapticArea(),
            onDismissRequest = { confirmKey = null },
            title = { Text(stringResource(R.string.screen_terminal_021, k.label)) },
            text = { Text(stringResource(R.string.screen_terminal_022)) },
            confirmButton = { Button(onClick = { confirmKey = null; send(k.seq) }) { Text(stringResource(R.string.screen_terminal_017)) } },
            dismissButton = { TextButton(onClick = { confirmKey = null }) { Text(stringResource(R.string.screen_terminal_020)) } },
        )
    }
    if (confirmKill) {
        fun kill(scopeName: String) {
            confirmKill = false
            scope.launch {
                runCatching { api.terminalKill(id, scopeName) }
                    .onSuccess { n ->
                        haptics.confirm()
                        snackbar.showSnackbar(
                            when {
                                scopeName == "terminal" -> appString(R.string.screen_terminal_023)
                                n == 0 -> appString(R.string.screen_terminal_024)
                                else -> appString(R.string.screen_terminal_025)
                            },
                        )
                    }
                    .onFailure { haptics.reject(); snackbar.showSnackbar(it.message ?: appString(R.string.screen_terminal_026)) }
            }
        }
        AlertDialog(
            modifier = hapticArea(),
            onDismissRequest = { confirmKill = false },
            title = { Text(stringResource(R.string.screen_terminal_007)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.screen_terminal_027))
                    if (info?.self == true) {
                        Text(
                            stringResource(R.string.screen_terminal_028),
                            color = scheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    FilledTonalButton(onClick = { kill("command") }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.screen_terminal_029))
                    }
                    Button(
                        onClick = { kill("terminal") },
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.screen_terminal_030)) }
                    if (external) {
                        Text(
                            stringResource(R.string.screen_terminal_031),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { confirmKill = false }) { Text(stringResource(R.string.screen_terminal_020)) } },
        )
    }
}
