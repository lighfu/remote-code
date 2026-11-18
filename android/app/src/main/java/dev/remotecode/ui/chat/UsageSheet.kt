package dev.remotecode.ui.chat

import dev.remotecode.R
import dev.remotecode.i18n.appString
import dev.remotecode.i18n.localizedUsageNote
import dev.remotecode.i18n.localizedTicketTitle
import dev.remotecode.i18n.localizedUsageLabel
import dev.remotecode.i18n.weekly
import dev.remotecode.i18n.allModels
import dev.remotecode.i18n.displayScope
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Savings
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ProviderUsage
import dev.remotecode.data.UsageWindow
import dev.remotecode.ui.common.BrandIcon
import dev.remotecode.ui.common.hapticArea
import dev.remotecode.ui.common.relativeTime
import dev.remotecode.ui.sessions.UsageWindowRow
import dev.remotecode.ui.sessions.dateText
import dev.remotecode.ui.sessions.usageColor
import kotlinx.coroutines.launch

private fun pillWindows(u: ProviderUsage?): List<Pair<String, UsageWindow>> {
    val ws = u?.windows.orEmpty()
    val week = ws.firstOrNull { it.weekly && it.allModels } ?: ws.firstOrNull { it.weekly && it.displayScope == null }
    val models = ws.filter { it.weekly && it.displayScope != null && it != week }
    // 表示枠を絞り、Fable を優先する。
    val byModel = (models.filter { it.label.contains("Fable", ignoreCase = true) } + models.filterNot { it.label.contains("Fable", ignoreCase = true) })
        .take(if (week != null) 1 else 2)
        .map { it.displayScope.orEmpty() to it }
    return listOfNotNull(week?.let { appString(R.string.chat_usage_sheet_016) to it }) + byModel
}

@Composable
fun UsagePill(provider: String, onClick: () -> Unit) {
    val usage by RemoteCodeApp.instance.repository.usage.collectAsState()
    val u = usage?.of(provider)
    val shown = pillWindows(u).ifEmpty { u?.windows.orEmpty().take(1).map { localizedUsageLabel(it) to it } }
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (shown.isEmpty()) {
                Icon(Icons.Rounded.DataUsage, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(5.dp))
                Text(stringResource(R.string.chat_usage_sheet_015), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            shown.forEachIndexed { i, (name, w) ->
                if (i > 0) Spacer(Modifier.size(8.dp))
                val color = usageColor(w.usedPercent)
                CircularProgressIndicator(
                    progress = { (w.usedPercent / 100f).coerceIn(0f, 1f) },
                    color = color,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text("$name ${w.usedPercent}%", style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
    }
}

@Composable
fun UsageSheet(provider: String, onDismiss: () -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val usage by repo.usage.collectAsState()
    val u = usage?.of(provider)
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val now = rememberNow()
    fun refresh(force: Boolean) {
        refreshing = true
        scope.launch {
            repo.refreshUsage(force)
            refreshing = false
        }
    }
    LaunchedEffect(Unit) { if (u == null || now - u.at > 60_000) refresh(true) }

    ModalBottomSheet(modifier = hapticArea(), onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandIcon(provider, 28.dp)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.chat_usage_sheet_014, dev.remotecode.data.Providers.label(provider)), style = MaterialTheme.typography.titleLarge)
                    val sub = listOfNotNull(
                        u?.plan?.replaceFirstChar { it.uppercase() } ?: localizedUsageNote(provider, u),
                        u?.at?.takeIf { it > 0 }?.let { relativeTime(it, now).let { r -> if (now - it < 60_000) appString(R.string.chat_usage_sheet_012) else appString(R.string.chat_usage_sheet_011, r) } },
                    ).joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (refreshing) LoadingIndicator(Modifier.size(32.dp))
                else IconButton(onClick = { refresh(true) }) { Icon(Icons.Rounded.Refresh, stringResource(R.string.chat_refresh)) }
            }
            when {
                u == null -> Text(stringResource(R.string.chat_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                u.windows.isEmpty() -> Text(u.error ?: stringResource(R.string.chat_usage_sheet_010), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        u.windows.forEach { UsageWindowRow(it) }
                    }
                }
            }
            val tickets = u?.tickets
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ConfirmationNumber, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            when {
                                tickets == null -> stringResource(R.string.chat_usage_sheet_009)
                                tickets.available > 0 -> stringResource(R.string.chat_usage_sheet_008, tickets.available)
                                else -> stringResource(R.string.chat_usage_sheet_007)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    tickets?.items.orEmpty().forEach { t ->
                        Text(
                            stringResource(R.string.chat_usage_sheet_006, localizedTicketTitle(provider, t).ifBlank { appString(R.string.chat_usage_sheet_005) }) + (t.expiresAt?.let { stringResource(R.string.chat_usage_sheet_004, dateText(it)) } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 28.dp),
                        )
                    }
                    if (provider == "claude" && tickets != null && tickets.available > 0) {
                        Text(
                            stringResource(R.string.chat_usage_sheet_003),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 28.dp),
                        )
                    }
                }
            }
            val c = u?.credits
            if (c != null && (c.hasCredits || c.unlimited)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                    Icon(Icons.Rounded.Savings, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (c.unlimited) stringResource(R.string.chat_usage_sheet_002) else stringResource(R.string.chat_usage_sheet_001, c.balance?.toDoubleOrNull()?.let { "%,.0f".format(it) } ?: c.balance ?: "-"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
