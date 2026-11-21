package dev.remotecode.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.remotecode.data.Api
import dev.remotecode.data.ConnectionIdentity
import dev.remotecode.data.Entry
import dev.remotecode.data.connectionIdentity
import dev.remotecode.data.isActiveAgent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
internal fun SessionClaudeWorkflowSheet(
    api: Api,
    sessionId: String,
    serverIdentity: ConnectionIdentity?,
    connected: Boolean,
    running: Boolean,
    timeline: List<Entry>,
    onDismiss: () -> Unit,
    canJumpToEntry: (String) -> Boolean,
    onJumpToEntry: (String) -> Unit,
    onOpenAgent: (Entry) -> Unit,
) {
    var snapshot by remember(sessionId, serverIdentity) { mutableStateOf<List<Entry>?>(null) }
    var unavailable by remember(sessionId, serverIdentity) { mutableStateOf(false) }
    val latestWorkflow = remember(timeline) { timeline.lastOrNull { it.tool == "Workflow" }?.id }
    LaunchedEffect(sessionId, serverIdentity, connected, running, latestWorkflow) {
        if (!connected || serverIdentity == null) return@LaunchedEffect
        val target = api.server?.takeIf { it.connectionIdentity() == serverIdentity } ?: return@LaunchedEffect
        unavailable = false
        do {
            val updated = try {
                api.claudeWorkflow(sessionId, target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                unavailable = true
                return@LaunchedEffect
            }
            snapshot = updated
            val active = updated.any { it.kind == "tool" && isActiveAgent(it.status, it.streaming) }
            if (active || running) delay(2_500) else break
        } while (true)
    }

    val entries = remember(snapshot, timeline) {
        snapshot?.let { stored ->
            val liveById = timeline.associateBy(Entry::id)
            val storedIds = stored.mapTo(hashSetOf()) { it.id }
            val merged = stored.map { entry ->
                val live = liveById[entry.id] ?: return@map entry
                val newerActivity = !live.progress.isNullOrBlank() && (live.activityAt ?: 0) > (entry.activityAt ?: 0)
                entry.copy(
                    detail = entry.detail?.takeIf { it.isNotBlank() } ?: live.detail,
                    progress = if (newerActivity) live.progress else entry.progress?.takeIf { it.isNotBlank() } ?: live.progress,
                    output = entry.output?.takeIf { it.isNotBlank() } ?: live.output,
                    activityAt = if (newerActivity) live.activityAt else entry.activityAt,
                )
            }
            val newEntries = timeline.filter { it.id !in storedIds }
            if (newEntries.isEmpty()) merged else (merged + newEntries).sortedBy { it.ts }
        } ?: timeline
    }
    ClaudeWorkflowSheet(
        entries = entries,
        onDismiss = onDismiss,
        onJumpToEntry = onJumpToEntry,
        canJumpToEntry = canJumpToEntry,
        loading = connected && serverIdentity != null && snapshot == null && !unavailable,
        refreshError = unavailable,
        onOpenAgent = onOpenAgent,
    )
}
