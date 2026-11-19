package dev.remotecode.ui.chat

import dev.remotecode.R
import dev.remotecode.i18n.appString
import dev.remotecode.i18n.localizedServerMessage
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.CacheRequest
import dev.remotecode.data.CacheSummary
import dev.remotecode.data.SessionDetailField
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ReadColor = Color(0xFF2EB67D)
private val WriteColor = Color(0xFFE0A526)
private val UncachedColor = Color(0xFFE5677B)

@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** 残り時間（ms）。期限切れは 0 以下。 */
fun cacheRemaining(c: CacheSummary, now: Long): Long = c.lastAt + c.ttlMs - now

fun mmss(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun CachePill(cache: CacheSummary?, onClick: () -> Unit, compact: Boolean = false) {
    val now = rememberNow()
    val left = cache?.let { cacheRemaining(it, now) }
    val warm = left != null && left > 0
    val color = when {
        cache == null -> MaterialTheme.colorScheme.onSurfaceVariant
        !warm -> MaterialTheme.colorScheme.error
        left!! < 60_000 -> MaterialTheme.colorScheme.tertiary
        else -> ReadColor
    }
    val ring = if (compact) 13.dp else 18.dp
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (compact) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            Modifier.padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (cache != null && warm) {
                CircularProgressIndicator(
                    progress = { (left!!.toFloat() / cache.ttlMs).coerceIn(0f, 1f) },
                    color = color,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    strokeWidth = if (compact) 2.dp else 3.dp,
                    modifier = Modifier.size(ring),
                )
            } else {
                Icon(Icons.Rounded.Bolt, null, Modifier.size(ring), tint = color)
            }
            Spacer(Modifier.size(5.dp))
            Text(
                when {
                    cache == null -> stringResource(R.string.chat_cache_sheet_023)
                    warm -> stringResource(R.string.chat_cache_sheet_022) + mmss(left!!)
                    else -> stringResource(R.string.chat_cache_sheet_021)
                },
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                color = color,
            )
        }
    }
}

@Composable
fun CacheSheet(sessionId: String, onDismiss: () -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val meta by repo.meta.collectAsState()
    val money = rememberUsageMoney(meta)
    val state = rememberLiveSessionDetail(sessionId, SessionDetailField.Cache, 5_000) { target ->
        repo.api.cacheDetail(sessionId, expectedServer = target)
    }
    val now = rememberNow()
    ModalBottomSheet(modifier = dev.remotecode.ui.common.hapticArea(), onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.chat_cache_sheet_020), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = state::refresh) { Icon(Icons.Rounded.Refresh, stringResource(R.string.chat_refresh)) }
        }
        val d = state.detail
        val error = state.error
        when {
            d == null && error != null -> Text(localizedServerMessage(error), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
            d == null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            else -> LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { LiveCard(d.summary, now) }
                item {
                    val t = d.totals
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.chat_cache_sheet_019, t.requests), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.chat_cache_sheet_018).format(t.hitRate * 100), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = ReadColor)
                            StackBar(t.read, t.write5m + t.write1h, t.uncached)
                            Legend(appString(R.string.chat_cache_sheet_017), t.read, ReadColor)
                            Legend(appString(R.string.chat_cache_sheet_016, tokensText(t.write1h), tokensText(t.write5m)), t.write5m + t.write1h, WriteColor)
                            Legend(appString(R.string.chat_cache_sheet_015), t.uncached, UncachedColor)
                            Text(
                                stringResource(
                                    if (d.savedPartial && d.savedKnown != false && d.savedUsd != null) R.string.chat_cache_savings_partial else R.string.chat_cache_sheet_014,
                                    money.savings(d.savedUsd.takeUnless { d.savedKnown == false }),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                if (d.expiredCount > 0) stringResource(R.string.chat_cache_sheet_013, d.expiredCount) else stringResource(R.string.chat_cache_sheet_012),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item { Text(stringResource(R.string.chat_cache_sheet_011), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
                items(d.recent) { q -> RequestRow(q, d.summary?.ttlMs ?: 300_000) }
            }
        }
    }
}

@Composable
private fun LiveCard(s: CacheSummary?, now: Long) {
    val left = s?.let { cacheRemaining(it, now) }
    val warm = left != null && left > 0
    Card(colors = CardDefaults.cardColors(containerColor = if (warm) ReadColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { if (s == null || !warm) 0f else (left!!.toFloat() / s.ttlMs).coerceIn(0f, 1f) },
                    color = ReadColor,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    strokeWidth = 6.dp,
                    modifier = Modifier.size(72.dp),
                )
                Text(if (warm) mmss(left!!) else stringResource(R.string.chat_cache_sheet_010), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (warm) stringResource(R.string.chat_cache_sheet_009) else stringResource(R.string.chat_cache_sheet_008), style = MaterialTheme.typography.titleMedium)
                if (s != null) {
                    Text(stringResource(R.string.chat_cache_sheet_007, if (s.ttlMs >= 3_600_000) appString(R.string.chat_cache_sheet_006) else appString(R.string.chat_cache_sheet_005), clock(s.lastAt)), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.chat_cache_sheet_004).format(s.lastHit * 100), style = MaterialTheme.typography.bodySmall)
                    if (!warm) Text(
                        stringResource(R.string.chat_cache_sheet_003),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(stringResource(R.string.chat_cache_sheet_002), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun RequestRow(q: CacheRequest, ttlMs: Long) {
    val all = q.read + q.write5m + q.write1h + q.uncached
    val hit = if (all > 0) q.read * 100.0 / all else 0.0
    val expired = q.gapMs != null && q.gapMs > ttlMs && q.read == 0L
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clock(q.at), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
            Text("%.1f%%".format(hit), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = if (hit >= 80) ReadColor else UncachedColor, modifier = Modifier.width(56.dp))
            Text(
                stringResource(R.string.chat_cache_sheet_001, tokensText(q.read), tokensText(q.write5m + q.write1h), tokensText(q.uncached)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            q.gapMs?.let {
                Text("+" + mmss(it), style = MaterialTheme.typography.labelSmall, color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        StackBar(q.read, q.write5m + q.write1h, q.uncached, height = 6)
    }
}

@Composable
private fun StackBar(read: Long, write: Long, uncached: Long, height: Int = 12) {
    val total = (read + write + uncached).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(height.dp / 2)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        listOf(read to ReadColor, write to WriteColor, uncached to UncachedColor).forEach { (v, c) ->
            if (v > 0) Box(Modifier.weight(v.toFloat() / total).fillMaxHeight().background(c))
        }
    }
}

@Composable
private fun Legend(label: String, tokens: Long, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(tokensText(tokens), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun clock(ms: Long): String = if (ms <= 0) "-" else SimpleDateFormat("H:mm:ss", Locale.JAPAN).format(Date(ms))
