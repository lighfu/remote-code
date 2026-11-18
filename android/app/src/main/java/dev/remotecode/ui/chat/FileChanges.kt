package dev.remotecode.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.remotecode.R
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.GitFile
import dev.remotecode.data.GitStatus
import dev.remotecode.data.LinkFileInfo
import dev.remotecode.data.ServerConfig
import dev.remotecode.data.connectionIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun GitStatus.changedFile(absolutePath: String): GitFile? {
    val base = root?.replace('\\', '/')?.trimEnd('/') ?: return null
    val path = absolutePath.replace('\\', '/')
    val windows = base.startsWith("//") || base.getOrNull(1) == ':'
    if (!isRepo || !path.startsWith("$base/", ignoreCase = windows)) return null
    val relative = path.substring(base.length + 1)
    return files.firstOrNull { it.path == relative }
        ?: files.firstOrNull { windows && it.path.equals(relative, ignoreCase = true) }
}

internal enum class SourceChange(val sign: String) { Added("+"), Removed("−"), Modified("±") }

/** The source gutter refers to current-file lines. Deleted text is read in the full diff. */
internal fun sourceChanges(diff: String, source: List<String>, truncated: Boolean = false): Map<Int, SourceChange> {
    if (source.isEmpty()) return emptyMap()
    val changes = mutableMapOf<Int, SourceChange>()
    var pendingDeletes = 0
    var currentLine = 0
    fun markDeletion() {
        if (pendingDeletes > 0) {
            if (!truncated || currentLine <= source.size) {
                val anchor = currentLine.coerceIn(1, source.size)
                changes[anchor] = if (changes[anchor] == null) SourceChange.Removed else SourceChange.Modified
            }
            pendingDeletes = 0
        }
    }
    for (row in parseDiff(diff)) {
        when (row.kind) {
            '@' -> {
                if (row.text.startsWith("\\")) continue
                markDeletion()
                val start = hunkStart.find(row.text)
                currentLine = start?.groupValues?.get(1)?.toIntOrNull() ?: 0
                // For an empty new range Git numbers the line *before* the deletion.
                if (start?.groupValues?.get(2) == ",0") currentLine++
            }
            '-' -> if (currentLine > 0) pendingDeletes++
            '+' -> {
                val line = row.new ?: continue
                if (source.getOrNull(line - 1) == row.text)
                    changes[line] = if (pendingDeletes > 0) SourceChange.Modified else SourceChange.Added
                if (pendingDeletes > 0) pendingDeletes--
                currentLine = line + 1
            }
            ' ' -> {
                val line = row.new ?: continue
                if (source.getOrNull(line - 1) == row.text) markDeletion() else pendingDeletes = 0
                currentLine = line + 1
            }
        }
    }
    markDeletion()
    return changes
}

private val hunkStart = Regex("""^@@ -\d+(?:,\d+)? \+(\d+)(,\d+)? @@""")

internal data class FileChanges(
    val file: GitFile,
    val cwd: String,
    val markers: Map<Int, SourceChange>,
)

@Composable
internal fun rememberFileChanges(info: LinkFileInfo, sessionId: String, server: ServerConfig?): FileChanges? {
    val repo = RemoteCodeApp.instance.repository
    val active by repo.activeServerIdentity.collectAsState()
    val sessions by repo.sessions.collectAsState()
    val status by repo.git.collectAsState()
    val cwd = sessions.firstOrNull { it.id == sessionId }?.cwd
    var openedCwd by remember(info.path, sessionId, server) { mutableStateOf(cwd) }
    LaunchedEffect(cwd) { if (openedCwd == null) openedCwd = cwd }
    val valid = server != null && active == server.connectionIdentity() && openedCwd != null && cwd == openedCwd
    LaunchedEffect(valid, sessionId, info) {
        if (valid) repo.refreshGit(sessionId)
    }
    val file = remember(valid, status[sessionId], info.path) { status[sessionId]?.takeIf { valid }?.changedFile(info.path) }
    val staged = file?.let { it.staged && !it.unstaged && !it.untracked } ?: false
    val markers by produceState(emptyMap<Int, SourceChange>(), info, server, file?.path, staged, file?.untracked, valid) {
        value = emptyMap()
        if (file == null || !valid || file.binary || file.untracked) return@produceState
        try {
            val patch = repo.api.gitDiff(sessionId, file.path, staged, server)
            if (repo.session(sessionId)?.cwd == openedCwd && !patch.binary) {
                value = withContext(Dispatchers.Default) { sourceChanges(patch.text, sourceLines(info.text.orEmpty()), info.truncated) }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* The file stays readable. The diff dialog reports errors and offers retry. */ }
    }
    return openedCwd?.let { if (file == null) null else FileChanges(file, it, markers) }
}

@Composable
internal fun FileChangeButtons(changes: FileChanges, openDiff: (staged: Boolean) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (staged in listOf(false, true)) {
            val file = changes.file
            if (if (staged) !file.staged else !file.unstaged && !file.untracked) continue
            AssistChip(onClick = { openDiff(staged) },
                label = { Text(stringResource(if (staged) R.string.chat_git_sheet_007 else R.string.chat_git_sheet_006)) },
                leadingIcon = { Icon(Icons.Rounded.Difference, null, Modifier.size(18.dp)) })
        }
    }
}
