package dev.remotecode.data

import dev.remotecode.R
import dev.remotecode.i18n.appString
import kotlinx.serialization.Serializable

@Serializable
data class GitStatus(
    val isRepo: Boolean = false,
    val root: String? = null,
    val branch: String? = null,
    val head: String? = null,
    val detached: Boolean = false,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val files: List<GitFile> = emptyList(),
    val changedFiles: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
    val stagedFiles: Int = 0,
    val unstagedFiles: Int = 0,
    val untrackedFiles: Int = 0,
    val conflictedFiles: Int = 0,
    val lineStatsPartial: Boolean = false,
    val remotes: List<GitRemote> = emptyList(),
    val at: Long = 0,
    val error: String? = null,
)

@Serializable
data class GitFile(
    val path: String = "",
    val originalPath: String? = null,
    val indexStatus: String = "",
    val worktreeStatus: String = "",
    val staged: Boolean = false,
    val unstaged: Boolean = false,
    val untracked: Boolean = false,
    val conflict: Boolean = false,
    val additions: Int = 0,
    val deletions: Int = 0,
    val binary: Boolean = false,
)

@Serializable
data class GitRemote(val name: String = "", val url: String = "")

@Serializable
data class GitDiff(val path: String = "", val staged: Boolean = false, val text: String = "", val binary: Boolean = false, val truncated: Boolean = false)

@Serializable
data class GitLogEntry(val hash: String = "", val shortHash: String = "", val subject: String = "", val author: String = "", val date: String = "")

@Serializable
data class GitBranch(val name: String = "", val current: Boolean = false, val remote: Boolean = false)

@Serializable
data class GitActionResult(val message: String = "")

fun GitStatus.branchLabel(beforeCommit: () -> String = { appString(R.string.core_git_before_commit) }): String = when {
    detached -> "HEAD ${head?.take(8).orEmpty()}".trim()
    !branch.isNullOrBlank() -> branch
    head == null -> beforeCommit()
    else -> "HEAD ${head.take(8)}"
}
