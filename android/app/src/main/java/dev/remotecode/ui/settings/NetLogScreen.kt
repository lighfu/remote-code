package dev.remotecode.ui.settings

import dev.remotecode.R

import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ShortText
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ConnState
import dev.remotecode.data.NetKind
import dev.remotecode.data.NetLog
import dev.remotecode.data.NetLogEntry
import dev.remotecode.data.Meta
import dev.remotecode.data.routeLabel
import dev.remotecode.ui.theme.Mono
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Look(val label: String, val icon: ImageVector, val color: Color)

private val Blue = Color(0xFF4F8EF7)
private val Green = Color(0xFF2EB67D)
private val Red = Color(0xFFE5484D)
private val Amber = Color(0xFFE0A526)
private val Purple = Color(0xFF9E7BF0)
private val Teal = Color(0xFF14A6B8)
private val Gray = Color(0xFF8A9099)
private val Orange = Color(0xFFF0883E)

private fun lookOf(e: NetLogEntry): Look = when (e.feature) {
    "send" -> Look(appString(R.string.screen_net_log_001), Icons.AutoMirrored.Rounded.Send, Blue)
    "stop" -> Look(appString(R.string.screen_net_log_002), Icons.Rounded.Stop, Red)
    "btw" -> Look(appString(R.string.screen_net_log_003), Icons.Rounded.QuestionAnswer, Teal)
    "rewind" -> Look(appString(R.string.screen_net_log_004), Icons.Rounded.History, Amber)
    "context" -> Look(appString(R.string.screen_net_log_005), Icons.Rounded.DonutLarge, Purple)
    "cache" -> Look(appString(R.string.screen_net_log_006), Icons.Rounded.Bolt, Green)
    "respond" -> Look(appString(R.string.screen_net_log_007), Icons.Rounded.Shield, Teal)
    "takeover" -> Look(appString(R.string.screen_net_log_008), Icons.Rounded.TouchApp, Amber)
    "detach" -> Look(appString(R.string.screen_net_log_009), Icons.Rounded.Stop, Red)
    "command" -> Look(appString(R.string.screen_net_log_010), Icons.Rounded.Terminal, Teal)
    "load" -> Look(appString(R.string.screen_net_log_011), Icons.AutoMirrored.Rounded.Chat, Blue)
    "update" -> Look(appString(R.string.screen_net_log_012), Icons.Rounded.Edit, Teal)
    "delete" -> Look(appString(R.string.screen_net_log_013), Icons.Rounded.Delete, Red)
    "create" -> Look(appString(R.string.screen_net_log_014), Icons.Rounded.AddComment, Green)
    "list" -> Look(appString(R.string.screen_net_log_015), Icons.Rounded.List, Gray)
    "history" -> Look(appString(R.string.screen_net_log_016), Icons.Rounded.History, Gray)
    "usage" -> Look(appString(R.string.screen_net_log_017), Icons.Rounded.BarChart, Orange)
    "meta" -> Look(appString(R.string.screen_net_log_018), Icons.Rounded.Info, Gray)
    "models" -> Look(appString(R.string.screen_net_log_019), Icons.Rounded.Memory, Purple)
    "commands" -> Look(appString(R.string.screen_net_log_020), Icons.Rounded.Terminal, Gray)
    "terminals" -> Look(appString(R.string.screen_net_log_021), Icons.Rounded.Terminal, Teal)
    "manage" -> Look(appString(R.string.screen_net_log_022), Icons.Rounded.Extension, Orange)
    "appUpdate" -> Look(appString(R.string.screen_net_log_023), Icons.Rounded.SystemUpdate, Teal)
    "media" -> Look(appString(R.string.screen_net_log_024), Icons.Rounded.Image, Purple)
    "file" -> Look(appString(R.string.screen_net_log_025), Icons.Rounded.Folder, Amber)
    "files" -> Look(appString(R.string.screen_net_log_026), Icons.Rounded.Folder, Amber)
    "connecting" -> Look(appString(R.string.screen_net_log_027), Icons.Rounded.Sync, Amber)
    "connected" -> Look(appString(R.string.screen_net_log_028), Icons.Rounded.Link, Green)
    "failed" -> Look(appString(R.string.screen_net_log_029), Icons.Rounded.LinkOff, Red)
    "closed" -> Look(appString(R.string.screen_net_log_030), Icons.Rounded.LinkOff, Red)
    "ev:hello" -> Look(appString(R.string.screen_net_log_015), Icons.Rounded.List, Green)
    "ev:session" -> Look(appString(R.string.screen_net_log_031), Icons.Rounded.Update, Teal)
    "ev:entry" -> Look(entryLabel(e.note), Icons.Rounded.CallReceived, Blue)
    "ev:delta" -> Look(appString(R.string.screen_net_log_032), Icons.Rounded.ShortText, Gray)
    "ev:usage" -> Look(appString(R.string.screen_net_log_033), Icons.Rounded.BarChart, Orange)
    "ev:url" -> Look(appString(R.string.screen_net_log_034), Icons.Rounded.Public, Amber)
    "ev:btw" -> Look(appString(R.string.screen_net_log_035), Icons.Rounded.QuestionAnswer, Teal)
    "ev:btw_removed" -> Look(appString(R.string.screen_net_log_036), Icons.Rounded.QuestionAnswer, Gray)
    else -> Look(e.feature.removePrefix("ev:"), Icons.Rounded.HelpOutline, Gray)
}

private fun entryLabel(kind: String?): String = when (kind) {
    "user" -> appString(R.string.screen_net_log_037)
    "assistant" -> appString(R.string.screen_net_log_038)
    "thinking" -> appString(R.string.screen_net_log_039)
    "tool" -> appString(R.string.screen_net_log_040)
    "approval" -> appString(R.string.screen_net_log_041)
    "question" -> appString(R.string.screen_net_log_042)
    "result" -> appString(R.string.screen_net_log_043)
    "system" -> appString(R.string.screen_net_log_044)
    else -> appString(R.string.screen_net_log_045)
}

private enum class Filter(private val labelResource: Int) {
    ALL(R.string.screen_net_log_046), API(R.string.screen_net_log_api), LIVE(R.string.screen_net_log_047), ERROR(R.string.screen_net_log_048);

    val label: String get() = appString(labelResource)
}

private fun sizeText(n: Long): String = when {
    n >= 1_048_576 -> "%.1f MB".format(n / 1_048_576.0)
    n >= 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "$n B"
}

@Composable
fun NetLogScreen(onBack: () -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val entries by NetLog.entries.collectAsState()
    val conn by repo.connection.collectAsState()
    val route by repo.route.collectAsState()
    val meta by repo.meta.collectAsState()
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var paused by remember { mutableStateOf(NetLog.paused) }
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    val shown = entries.asReversed().filter {
        when (filter) {
            Filter.ALL -> true
            Filter.API -> it.kind == NetKind.API
            Filter.LIVE -> it.kind != NetKind.API
            Filter.ERROR -> !it.ok
        }
    }
    val list = rememberLazyListState()
    LaunchedEffect(entries.size) { if (list.firstVisibleItemIndex <= 1) list.scrollToItem(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_net_log_049)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.screen_net_log_050)) } },
                actions = {
                    IconButton(onClick = { haptics.toggle(!paused); paused = !paused; NetLog.paused = paused }) {
                        Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) appString(R.string.screen_net_log_051) else appString(R.string.screen_net_log_052))
                    }
                    IconButton(onClick = { haptics.reject(); NetLog.clear() }) { Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.screen_net_log_053)) }
                    IconButton(onClick = { haptics.tick(); repo.reconnectNow() }) { Icon(Icons.Rounded.Refresh, stringResource(R.string.screen_net_log_054)) }
                },
            )
        },
    ) { pad ->
        LazyColumn(state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 32.dp), modifier = Modifier.padding(pad).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Summary(entries, conn, route, paused, meta) }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Filter.entries.forEach { f ->
                        val n = when (f) {
                            Filter.ALL -> entries.size
                            Filter.API -> entries.count { it.kind == NetKind.API }
                            Filter.LIVE -> entries.count { it.kind != NetKind.API }
                            Filter.ERROR -> entries.count { !it.ok }
                        }
                        FilterChip(selected = filter == f, onClick = { haptics.tick(); filter = f }, label = { Text("${f.label} $n") })
                    }
                }
            }
            if (shown.isEmpty()) {
                item { Text(stringResource(R.string.screen_net_log_055), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
            }
            items(shown, key = { it.id }) { e -> LogRow(e) }
        }
    }
}

@Composable
private fun Summary(entries: List<NetLogEntry>, conn: ConnState, route: String?, paused: Boolean, meta: Meta?) {
    val apis = entries.filter { it.kind == NetKind.API }
    val avg = apis.mapNotNull { it.ms }.takeIf { it.isNotEmpty() }?.average()?.toLong()
    val errors = entries.count { !it.ok }
    val (stateText, stateColor) = when (conn) {
        ConnState.Connected -> appString(R.string.screen_net_log_056, routeLabel(route)) to Green
        ConnState.Connecting -> appString(R.string.screen_net_log_057) to Amber
        is ConnState.Failed -> conn.message to Red
        ConnState.Idle -> appString(R.string.screen_net_log_058) to Gray
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(stateColor))
                Spacer(Modifier.width(10.dp))
                Text(stateText, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("API", appString(R.string.screen_net_log_059, apis.size), Blue, Modifier.weight(1f))
                Stat(appString(R.string.screen_net_log_060), avg?.let { "$it ms" } ?: "–", Purple, Modifier.weight(1f))
                Stat(appString(R.string.screen_net_log_048), appString(R.string.screen_net_log_061, errors), if (errors > 0) Red else Green, Modifier.weight(1f))
            }
            meta?.let { server ->
                Text(
                    listOfNotNull(appString(R.string.screen_net_log_062, server.version), server.startedAt?.let { appString(R.string.screen_net_log_063, startedText(it)) }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (paused) Text(stringResource(R.string.screen_net_log_064), style = MaterialTheme.typography.labelMedium, color = Amber)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(14.dp), modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

@Composable
private fun LogRow(e: NetLogEntry) {
    val look = lookOf(e)
    val color = if (e.ok) look.color else Red
    var open by remember { mutableStateOf(false) }
    val time = remember(e.at) { SimpleDateFormat("H:mm:ss", Locale.JAPAN).format(Date(e.at)) }
    Surface(
        color = if (e.ok) MaterialTheme.colorScheme.surfaceContainerLow else Red.copy(alpha = 0.10f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize().clickable { open = !open },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(look.icon, null, Modifier.size(18.dp), tint = color)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(look.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (e.count > 1) {
                            Spacer(Modifier.width(6.dp))
                            Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(50)) {
                                Text("×${e.count}", style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                            }
                        }
                    }
                    Text(
                        subtitle(e),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (e.ok) MaterialTheme.colorScheme.onSurfaceVariant else Red,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    e.ms?.takeIf { e.kind == NetKind.API && it > 0 }?.let { ms ->
                        Text("$ms ms", style = MaterialTheme.typography.labelSmall, color = if (ms > 1500) Amber else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    e.timing?.serverMs?.let { ms ->
                        Text("PC ${durationText(ms)} ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (open) {
                Column(Modifier.padding(start = 46.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectionContainer {
                        Text(
                            listOfNotNull(
                                e.method?.let { "${it} ${e.path.orEmpty()}" },
                                e.status?.let { appString(R.string.screen_net_log_065, it) },
                                e.bytes?.let { appString(R.string.screen_net_log_066, sizeText(it)) },
                                e.note?.let { (if (e.ok) "" else appString(R.string.screen_net_log_067)) + it },
                                e.timing?.let { timing ->
                                    listOfNotNull(
                                        if (timing.cacheHit) appString(R.string.screen_net_log_068) else null,
                                        appString(R.string.screen_net_log_069, timing.headersMs),
                                        timing.queueMs?.let { appString(R.string.screen_net_log_070, it) },
                                        timing.dnsMs?.let { "DNS: $it ms" },
                                        timing.connectMs?.let { appString(R.string.screen_net_log_071, it) },
                                        timing.tlsMs?.let { appString(R.string.screen_net_log_072, it) },
                                        timing.connectionWaitMs?.let { appString(R.string.screen_net_log_073, it) },
                                        timing.reusedConnection?.let { if (it) appString(R.string.screen_net_log_074) else appString(R.string.screen_net_log_075) },
                                        timing.serverMs?.let { appString(R.string.screen_net_log_076, durationText(it)) },
                                        timing.serverHandlerMs?.takeIf { it > 0 }?.let { appString(R.string.screen_net_log_077, durationText(it)) },
                                        timing.serverJsonMs?.takeIf { it > 0 }?.let { appString(R.string.screen_net_log_078, durationText(it)) },
                                        timing.serverGzipMs?.takeIf { it > 0 }?.let { appString(R.string.screen_net_log_079, durationText(it)) },
                                        appString(R.string.screen_net_log_080, timing.bodyMs),
                                        e.ms?.let { (it - timing.headersMs - timing.bodyMs).coerceAtLeast(0) }?.takeIf { it > 0 }?.let { appString(R.string.screen_net_log_081, it) },
                                        timing.wireBytes?.let { appString(R.string.screen_net_log_082, sizeText(it)) },
                                        timing.serverVersion?.let { appString(R.string.screen_net_log_083, it) },
                                        timing.serverStartedAt?.let { appString(R.string.screen_net_log_084, startedText(it)) },
                                        timing.serverInstance?.let { appString(R.string.screen_net_log_085, it.take(8)) },
                                    ).joinToString("\n")
                                },
                            ).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                        )
                    }
                    e.request?.let { BodyBlock(appString(R.string.screen_net_log_086), it) }
                    e.response?.let { BodyBlock(if (e.kind == NetKind.API) appString(R.string.screen_net_log_087) else appString(R.string.screen_net_log_088), it) }
                    if (e.kind == NetKind.API && e.request == null && e.response == null && e.path?.let { it.startsWith("/api/media") || it.startsWith("/api/app/apk") } == true) {
                        Text(stringResource(R.string.screen_net_log_089), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun durationText(ms: Double): String = if (ms < 10) "%.1f".format(Locale.JAPAN, ms) else "%.0f".format(Locale.JAPAN, ms)

private fun startedText(at: Long): String = SimpleDateFormat("M/d H:mm:ss", Locale.JAPAN).format(Date(at))

private val PrettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

private fun prettyBody(s: String): String = runCatching {
    PrettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), kotlinx.serialization.json.Json.parseToJsonElement(s))
}.getOrDefault(s)

@Composable
private fun BodyBlock(title: String, body: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    val text = remember(body) { prettyBody(body) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText(title, text))
                    haptics.tick()
                },
                modifier = Modifier.size(32.dp),
            ) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.screen_net_log_090), Modifier.size(16.dp)) }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp),
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                )
            }
        }
    }
}

private fun subtitle(e: NetLogEntry): String = when (e.kind) {
    NetKind.API -> listOfNotNull(
        "${e.method} ${e.path}",
        e.status?.toString() ?: appString(R.string.screen_net_log_091),
        e.bytes?.let { sizeText(it) },
    ).joinToString(" · ")
    NetKind.SOCKET -> e.note ?: appString(R.string.screen_net_log_092)
    NetKind.EVENT -> listOfNotNull(appString(R.string.screen_net_log_093), e.bytes?.let { sizeText(it) }).joinToString(" · ")
}
