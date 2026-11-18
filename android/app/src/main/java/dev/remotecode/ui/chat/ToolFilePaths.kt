package dev.remotecode.ui.chat

private val fileChangePrefixes = listOf("新規 ", "変更 ", "削除 ", "New ", "Modify ", "Delete ", "Created ", "Modified ", "Deleted ")
private val fileChangeSummary = Regex("""^(\d+)\s+(?:ファイルを変更|files? modified)$""")

internal fun fileChangePath(title: String): String {
    val prefix = fileChangePrefixes.firstOrNull { title.startsWith(it) }
    return (if (prefix == null) title else title.removePrefix(prefix)).trim()
}

internal fun changedFileSummaryCount(title: String?): String? =
    title?.trim()?.let { fileChangeSummary.matchEntire(it)?.groupValues?.get(1) }

internal fun isFileChangeSummary(title: String): Boolean =
    changedFileSummaryCount(title) != null || title.trim() in setOf("ファイルの変更", "File changes")
