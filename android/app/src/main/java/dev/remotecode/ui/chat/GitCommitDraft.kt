package dev.remotecode.ui.chat

import dev.remotecode.R
import dev.remotecode.data.GitDiff
import dev.remotecode.data.GitFile
import dev.remotecode.i18n.AppStringResolver
import dev.remotecode.i18n.applicationStrings
import dev.remotecode.i18n.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

internal enum class DraftScope(val label: Int) {
    Staged(R.string.git_draft_staged), Selected(R.string.git_draft_selected), WorkingTree(R.string.git_draft_working),
}

internal data class GitDraftTarget(val scope: DraftScope, val files: List<GitFile>)

internal fun gitDraftTarget(files: List<GitFile>, selected: Set<String>): GitDraftTarget {
    val staged = files.filter { it.staged }
    if (staged.isNotEmpty()) return GitDraftTarget(DraftScope.Staged, staged)
    val changes = files.filter { it.unstaged || it.untracked || it.conflict }
    return if (selected.isEmpty()) GitDraftTarget(DraftScope.WorkingTree, changes)
    else GitDraftTarget(DraftScope.Selected, changes.filter { it.path in selected })
}

private val declaration = Regex("""\b(?:class|interface|struct|enum(?:\s+class)?|object|type(?:alias)?|fun|function|func(?:\s*\([^)]*\))?|fn|def|val|const(?:\s+val)?|var|let)\s+([\p{L}_$][\w$]*)""")
private val setting = Regex("""^\s*(?:["']([\w.-]+)["']\s*:|([\w.-]+)\s*[=:]|<\w+\s+name="([\w.-]+)")""")
private val heading = Regex("""^\s*#{1,6}\s+(.+?)\s*$""")
private val call = Regex("""\b([A-Za-z_][\w.]*)\s*\(""")
private val controlWords = setOf("if", "for", "while", "switch", "when", "catch", "return", "function")

/** Keep names of changed declarations, settings and sections, without copying assigned values into a commit. */
private fun draftNames(diff: String, path: String): List<String> {
    val names = linkedSetOf<String>()
    val contexts = linkedSetOf<String>()
    val addedCalls = linkedSetOf<String>()
    val removedCalls = linkedSetOf<String>()
    val markdown = path.endsWith(".md", ignoreCase = true)
    var inHunk = false
    for (line in diff.lineSequence()) {
        if (line.startsWith("@@")) {
            inHunk = true
            val context = line.substringAfter("@@", "").substringAfter("@@", "")
            val name = if (markdown) heading.matchEntire(context.trim())?.groupValues?.get(1)
                else declaration.find(context)?.groupValues?.get(1)
            if (name != null && contexts.size < 3) contexts.add(name.take(64))
        } else if (inHunk && (line.startsWith('+') || line.startsWith('-'))) {
            val code = line.substring(1)
            val name = if (markdown) heading.matchEntire(code)?.groupValues?.get(1)
            else declaration.find(code)?.groupValues?.get(1)
                ?: setting.find(code)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
            if (name != null) names.add(name.take(64))
            else if (!markdown) {
                val calls = if (line.startsWith('+')) addedCalls else removedCalls
                if (calls.size < 3) {
                    call.findAll(code).map { it.groupValues[1] }.firstOrNull { it !in controlWords }?.let { calls.add(it.take(64)) }
                }
            }
        }
        if (names.size >= 3) break
    }
    val removed = removedCalls - addedCalls
    val added = addedCalls - removedCalls
    if (names.isEmpty() && removed.size == 1 && added.size == 1) names.add("${removed.first()} → ${added.first()}")
    return (names + contexts).ifEmpty { addedCalls + removedCalls }.take(3)
}

internal suspend fun generateGitDraft(
    target: GitDraftTarget,
    fetch: suspend (String, Boolean) -> GitDiff,
    strings: AppStringResolver = applicationStrings,
): String = withContext(Dispatchers.Default) {
    val summaries = mutableListOf<String>()
    val staged = target.scope == DraftScope.Staged
    // Release large patches after extracting the short description for each file.
    for (batch in target.files.chunked(3)) coroutineScope {
        summaries += batch.map { file -> async {
            val diff = fetch(file.path, staged)
            check(diff.path == file.path && diff.staged == staged && (diff.binary || diff.text.isNotBlank())) {
                strings.text(R.string.git_draft_changed)
            }
            val status = if (staged) file.indexStatus else file.worktreeStatus
            val operation = when {
                status == "R" && file.originalPath != null -> strings.text(R.string.git_draft_rename, file.originalPath, file.path)
                status == "D" -> strings.text(R.string.git_draft_delete, file.path)
                status == "A" || file.untracked -> strings.text(R.string.git_draft_add, file.path)
                else -> strings.text(R.string.git_draft_update, file.path)
            }
            val names = if (diff.binary) emptyList() else draftNames(diff.text, file.path)
            if (names.isEmpty()) operation else "$operation (" + names.joinToString(", ") + ")"
        } }.awaitAll()
    }
    require(summaries.isNotEmpty()) { strings.text(R.string.git_draft_changed) }
    val message = if (summaries.size == 1) summaries.first()
    else buildString {
        append(strings.text(R.string.git_draft_subject, summaries.first(), summaries.size - 1))
        append("\n\n")
        append(summaries.joinToString("\n") { "- $it" })
    }
    // The commit endpoint accepts up to 16 KiB of UTF-8. Prefer whole bullet points.
    val bytes = message.encodeToByteArray()
    if (bytes.size <= 16_384) message else {
        val prefix = bytes.decodeToString(endIndex = 16_384 - "\n…".encodeToByteArray().size).trimEnd('\uFFFD')
        prefix.substringBeforeLast('\n', prefix) + "\n…"
    }
}
