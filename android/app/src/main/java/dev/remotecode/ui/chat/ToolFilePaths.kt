package dev.remotecode.ui.chat

private val fileChangePrefixes = listOf("新規 ", "変更 ", "削除 ", "New ", "Modify ", "Delete ", "Created ", "Modified ", "Deleted ")
private val fileChangeSummary = Regex("""^(\d+)\s+(?:ファイルを変更|files? modified)$""")

internal fun legacyFileChangeSign(title: String): Char? = when (title.substringBefore(' ')) {
    "新規", "New", "Created" -> '+'
    "削除", "Delete", "Deleted" -> '-'
    else -> null
}

/** Earlier Go hosts stored whole file bodies for add/delete and unified patches for update.
 * Only the single-path shape retains a trustworthy boundary. The original change kind is unknown.
 */
internal fun isLegacyFileSnapshot(title: String?, files: List<String>?, detail: String): Boolean {
    if (title.isNullOrBlank() || files?.singleOrNull() != title) return false
    val first = detail.lineSequence().firstOrNull { it.isNotBlank() } ?: return detail.isNotEmpty()
    if (first.startsWith("@@") || first.startsWith("diff --git ") || first.startsWith("── ") ||
        first.startsWith("--- ") || first.startsWith("+++ ") || first.startsWith("*** Begin Patch") ||
        first.startsWith("...(beginning truncated)...") || first.startsWith("⋯")) return false
    // A hunk without coordinates is also a historical format. Do not assign it file line numbers.
    var hasChange = false
    for (line in detail.lineSequence()) {
        when (line.firstOrNull()) {
            '+', '-' -> hasChange = true
            ' ', '\\', null -> Unit
            else -> return true
        }
    }
    return !hasChange
}

/** A single-file legacy card could carry its operation in the title alone. */
internal fun fileChangePreviewDiff(title: String?, files: List<String>?, detail: String): String {
    if (title == null || !title.contains(' ') || files?.singleOrNull() == title) return detail
    val sign = legacyFileChangeSign(title) ?: return detail
    val rows = sourceLines(detail)
    return if (rows.isNotEmpty() && rows.all { it.startsWith(sign) }) "── $title\n$detail" else detail
}

internal fun fileChangePath(title: String): String {
    val prefix = fileChangePrefixes.firstOrNull { title.startsWith(it) }
    return (if (prefix == null) title else title.removePrefix(prefix)).trim()
}

internal fun changedFileSummaryCount(title: String?): String? =
    title?.trim()?.let { fileChangeSummary.matchEntire(it)?.groupValues?.get(1) }

internal fun isFileChangeSummary(title: String): Boolean =
    changedFileSummaryCount(title) != null || title.trim() in setOf("ファイルの変更", "File changes")
