package dev.remotecode.ui.sessions

import dev.remotecode.R
import dev.remotecode.i18n.appString
import dev.remotecode.i18n.localizedUsageLabel
import dev.remotecode.i18n.localizedUsageNote
import dev.remotecode.i18n.localizedTicketTitle
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.remotecode.data.ProviderUsage
import dev.remotecode.data.UsageInfo
import dev.remotecode.data.UsageWindow
import dev.remotecode.ui.common.ProviderBadge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun untilText(ms: Long?, now: Long = System.currentTimeMillis(), includeReset: Boolean = true): String? {
    if (ms == null || ms <= 0) return null
    val d = (ms - now) / 60_000
    if (d <= 0) return appString(if (includeReset) R.string.screen_usage_card_001 else R.string.screen_usage_remaining_soon)
    val days = d / 1440
    val hours = (d % 1440) / 60
    val mins = d % 60
    return when {
        days > 0 -> appString(if (includeReset) R.string.screen_usage_card_003 else R.string.screen_usage_remaining_days, days, hours)
        hours > 0 -> appString(if (includeReset) R.string.screen_usage_card_004 else R.string.screen_usage_remaining_hours, hours, mins)
        else -> appString(if (includeReset) R.string.screen_usage_card_005 else R.string.screen_usage_remaining_minutes, mins)
    }
}

fun dateText(ms: Long): String = SimpleDateFormat("M/d H:mm", Locale.JAPAN).format(Date(ms))

@Composable
fun UsageCards(usage: UsageInfo?, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.screen_usage_card_007), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).padding(start = 8.dp))
            IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, stringResource(R.string.screen_usage_card_008), Modifier.size(20.dp)) }
        }
        ProviderUsageCard("claude", "Claude", usage?.claude)
        ProviderUsageCard("codex", "Codex", usage?.codex)
        ProviderUsageCard("antigravity", "Antigravity", usage?.antigravity)
    }
}

@Composable
private fun ProviderUsageCard(provider: String, name: String, u: ProviderUsage?) {
    var open by remember { mutableStateOf(false) }
    Card(
        onClick = { open = !open },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderBadge(provider, running = false, waiting = false, size = 32.dp)
                Spacer(Modifier.size(10.dp))
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                val plan = u?.plan?.replaceFirstChar { it.uppercase() } ?: localizedUsageNote(provider, u)
                if (plan != null) Text(plan, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                u == null -> Text(stringResource(R.string.screen_usage_card_009), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                u.windows.isEmpty() -> Text(u.error ?: appString(R.string.screen_usage_card_010), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> u.windows.forEach { UsageWindowRow(it) }
            }
            val tickets = u?.tickets
            if (tickets != null && tickets.available > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ConfirmationNumber, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.screen_usage_card_011, tickets.available), style = MaterialTheme.typography.bodyMedium)
                }
                if (open) tickets.items.forEach { t ->
                    Text(
                        "・${localizedTicketTitle(provider, t)}" + (t.expiresAt?.let { appString(R.string.screen_usage_card_012, dateText(it)) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp),
                    )
                }
            }
            val c = u?.credits
            if (c != null && (c.hasCredits || c.unlimited)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Savings, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (c.unlimited) appString(R.string.screen_usage_card_013) else appString(R.string.screen_usage_card_014, c.balance?.toDoubleOrNull()?.let { "%,.0f".format(it) } ?: c.balance ?: "-"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
fun usageColor(usedPercent: Int): Color = when {
    usedPercent >= 90 -> MaterialTheme.colorScheme.error
    usedPercent >= 70 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

fun resetText(w: UsageWindow): String? {
    val at = w.resetsAt?.takeIf { it > 0 }
    if (at != null) return appString(R.string.screen_usage_card_015, dateText(at)) + (untilText(at, includeReset = false) ?: "")
    return w.resetsText?.let { appString(R.string.screen_usage_card_016, it) }
}

@Composable
fun UsageWindowRow(w: UsageWindow) {
    val left = (100 - w.usedPercent).coerceIn(0, 100)
    val color = usageColor(w.usedPercent)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(localizedUsageLabel(w), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.screen_usage_card_017, left), style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.SemiBold)
        }
        LinearWavyProgressIndicator(
            progress = { w.usedPercent / 100f },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth().height(10.dp),
        )
        val reset = resetText(w)
        Text(stringResource(R.string.screen_usage_card_018, w.usedPercent) + (reset?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
