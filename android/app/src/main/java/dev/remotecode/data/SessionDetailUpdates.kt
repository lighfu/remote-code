package dev.remotecode.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

internal enum class SessionDetailField(val wireName: String) { Context("context"), Cache("cache"), Git("git") }

internal data class DetailChange(val id: String, val cwd: String?, val fields: Set<SessionDetailField>)

/** Usage belongs to a session; Git belongs to the working directory. */
internal fun DetailChange.affects(visible: SessionInfo, field: SessionDetailField): Boolean {
    if (field !in fields) return false
    val sameSession = visible.id == id
    if (cwd.isNullOrBlank()) {
        return sameSession
    } else {
        val directory = cwd.replace('\\', '/').trimEnd('/')
        val current = visible.cwd.replace('\\', '/').trimEnd('/')
        val windows = directory.getOrNull(1) == ':' || directory.startsWith("//")
        if (!directory.equals(current, ignoreCase = windows)) return false
    }
    return sameSession || field == SessionDetailField.Git
}

internal fun changedDetails(previous: SessionInfo?, next: SessionInfo): Set<SessionDetailField> = buildSet {
    if (previous == null) return@buildSet
    if (previous.context != next.context || previous.model != next.model || previous.compactWindow != next.compactWindow ||
        previous.stats?.tokens != next.stats?.tokens) add(SessionDetailField.Context)
    if (previous.stats?.cache != next.stats?.cache || previous.stats?.tokens != next.stats?.tokens) add(SessionDetailField.Cache)
    if (previous.cwd != next.cwd) addAll(SessionDetailField.entries)
    if (previous.isRunning && !next.isRunning) addAll(SessionDetailField.entries)
}

private val gitTools = setOf("filechange", "edit", "write", "multiedit", "notebookedit", "apply_patch", "command", "bash", "powershell")
private val finishedToolStates = setOf("done", "completed", "failed", "error")

internal fun entryDetails(entry: Entry): Set<SessionDetailField> = when (entry.kind) {
    "result", "compaction", "compact" -> SessionDetailField.entries.toSet()
    "edit" -> setOf(SessionDetailField.Git)
    "tool" -> if (entry.status in finishedToolStates && entry.tool?.lowercase() in gitTools) setOf(SessionDetailField.Git) else emptySet()
    else -> emptySet()
}

/** Subscribe before the first read. A burst during a slow read queues one follow-up. */
internal suspend fun Flow<Unit>.refreshOnChanges(
    intervalMs: () -> Long,
    minIntervalMs: Long = 1_000,
    read: suspend (changed: Boolean) -> Unit,
): Unit = coroutineScope {
    val pending = Channel<Unit>(Channel.CONFLATED)
    val collector = launch(start = CoroutineStart.UNDISPATCHED) { collect { pending.trySend(Unit) } }
    var changed = false
    try {
        while (true) {
            val started = TimeSource.Monotonic.markNow()
            try { read(changed) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep the last successful snapshot and retry on the next signal or poll. */ }
            changed = withTimeoutOrNull(intervalMs()) { pending.receive(); true } ?: false
            if (changed) {
                delay((minIntervalMs - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(0))
                pending.tryReceive()
            }
        }
    } finally {
        collector.cancel()
        pending.cancel()
    }
}
