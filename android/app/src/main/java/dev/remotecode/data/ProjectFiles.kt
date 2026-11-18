package dev.remotecode.data

import kotlinx.serialization.Serializable

@Serializable
data class ProjectDirectory(
    val root: String = "",
    val path: String = "",
    val parent: String? = null,
    val entries: List<ProjectFileEntry> = emptyList(),
    val truncated: Boolean = false,
    val fileCount: Int = -1,
    val folderCount: Int = -1,
    val matchingCount: Int = -1,
)

@Serializable
data class ProjectFileEntry(
    val path: String = "",
    val name: String = "",
    val kind: String = "file",
    val size: Long = 0,
    val mtime: Long = 0,
)

@Serializable
data class ProjectDocument(
    val root: String = "",
    val path: String = "",
    val absolutePath: String = "",
    val name: String = "",
    val text: String? = null,
    val editable: Boolean = false,
    val reason: String? = null,
    val revision: String = "",
    val size: Long = 0,
    val mtime: Long = 0,
    val encoding: String = "unknown",
    val lineEnding: String = "unknown",
)

@Serializable
data class ProjectCreateResult(val path: String = "")

@Serializable
data class ProjectEntryInfo(
    val root: String = "",
    val path: String = "",
    val name: String = "",
    val kind: String = "file",
    val version: String = "",
    val size: Long = 0,
    val fileCount: Int? = null,
    val folderCount: Int? = null,
    val symlink: Boolean = false,
)

@Serializable
data class ProjectActionResult(val path: String = "", val action: String = "")

internal data class ProjectDraftKey(val server: ConnectionIdentity, val session: String, val root: String, val path: String)

/** サーバーの正規化パスで同じファイルの別名を統合する。 */
internal fun ProjectDocument.projectDraftKey(server: ConnectionIdentity, session: String): ProjectDraftKey =
    ProjectDraftKey(server, session, root, path)

internal fun projectFileSize(size: Long): String = when {
    size >= 1_048_576 -> "%.1f MB".format(size / 1_048_576.0)
    size >= 1024 -> "%.1f KB".format(size / 1024.0)
    else -> "$size B"
}

internal fun projectChildPath(parent: String, name: String): String =
    listOf(parent.trim('/'), name.trim()).filter { it.isNotEmpty() }.joinToString("/")

internal fun validProjectEntryName(name: String): Boolean = name.trim().let {
    it.isNotEmpty() && it != "." && it != ".." && it.none { c -> c == '/' || c == '\\' || c.code < 32 }
}

/** 末尾改行の後も空行として数える。 */
internal fun projectLineStarts(text: String): IntArray {
    val result = IntArray(text.count { it == '\n' } + 1)
    var line = 1
    text.forEachIndexed { index, c -> if (c == '\n') result[line++] = index + 1 }
    return result
}

/** ネイティブレイアウトの上限を超える文書はビューアーへ回す。 */
internal fun projectEditorFits(text: String, glyphWidthPx: Float, maxLineWidthPx: Float = 100_000f, maxLines: Int = 50_000): Boolean {
    var lines = 1
    var columns = 0
    val width = glyphWidthPx.coerceAtLeast(1f)
    for (c in text) {
        if (c == '\n') { lines++; columns = 0; if (lines > maxLines) return false }
        else {
            columns += when { c == '\t' -> 4; c.code >= 0x2e80 -> 2; else -> 1 }
            if (columns * width > maxLineWidthPx) return false
        }
    }
    return true
}
