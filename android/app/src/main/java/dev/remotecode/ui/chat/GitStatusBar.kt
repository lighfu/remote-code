package dev.remotecode.ui.chat

import dev.remotecode.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ConnState
import dev.remotecode.data.branchLabel
import dev.remotecode.data.connectionIdentity
import kotlinx.coroutines.CancellationException

/** 取得間隔は Repository が管理する。 */
@Composable
fun GitStatusBar(sessionId: String, cwd: String, onClick: () -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val statuses by repo.git.collectAsState()
    val connection by repo.connection.collectAsState()
    val settings by repo.settings.collectAsState()
    val server = settings?.server?.connectionIdentity()
    LaunchedEffect(sessionId, cwd, server, connection is ConnState.Connected) {
        try { repo.refreshGit(sessionId) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* 詳細画面から再試行できる。 */ }
    }
    val status = statuses[sessionId] ?: return
    if (!status.isRepo && status.error == null) return
    val connected = connection is ConnState.Connected
    // フッターでは最小タッチ領域の上下余白を省く。
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(if (status.error != null) Icons.Rounded.ErrorOutline else Icons.Rounded.AccountTree, stringResource(R.string.chat_git_status_bar_004), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                if (!status.isRepo) stringResource(R.string.chat_git_status_bar_003) else status.branchLabel(),
                Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
            if (status.isRepo) {
                Text(stringResource(R.string.chat_git_sheet_022, status.changedFiles), style = MaterialTheme.typography.labelSmall)
                Text("+${status.additions}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                Text("−${status.deletions}${if (status.lineStatsPartial) "*" else ""}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                if (status.ahead > 0 || status.behind > 0) Text("↑${status.ahead} ↓${status.behind}", style = MaterialTheme.typography.labelSmall)
            }
            if (!connected || status.error != null) Text(if (!connected) stringResource(R.string.chat_git_status_bar_002) else stringResource(R.string.chat_git_status_bar_001), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    }
}
