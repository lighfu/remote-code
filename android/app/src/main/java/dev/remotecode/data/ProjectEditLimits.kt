package dev.remotecode.data

internal data class ProjectEditCheck(val allowed: Boolean, val lineCount: Int)

/** 検証済み文書に対し、変更範囲と隣接行だけを検証する。 */
internal fun checkProjectEdit(
    destination: CharSequence,
    start: Int,
    end: Int,
    replacement: CharSequence,
    replacementStart: Int,
    replacementEnd: Int,
    currentLineCount: Int,
    maxColumns: Int,
    maxCharacters: Int = 2 * 1024 * 1024,
    maxLines: Int = 50_000,
): ProjectEditCheck {
    fun rejected() = ProjectEditCheck(false, currentLineCount)
    if (start < 0 || end < start || end > destination.length ||
        replacementStart < 0 || replacementEnd < replacementStart || replacementEnd > replacement.length ||
        currentLineCount < 1 || maxColumns < 0 || maxCharacters < 0 || maxLines < 1
    ) return rejected()

    val insertedLength = replacementEnd - replacementStart
    val newLength = destination.length.toLong() - (end - start) + insertedLength
    if (newLength > maxCharacters) return rejected()

    var removedBreaks = 0
    if (start == 0 && end == destination.length) removedBreaks = currentLineCount - 1
    else for (index in start until end) if (destination[index] == '\n') removedBreaks++
    var lines = currentLineCount - removedBreaks
    if (lines < 1 || lines > maxLines) return rejected()

    fun width(char: Char) = when {
        char == '\t' -> 4
        char.code >= 0x2e80 -> 2
        else -> 1
    }

    var columns = 0L
    var index = start - 1
    while (index >= 0) {
        val char = destination[index--]
        if (char == '\n') break
        columns += width(char)
        if (columns > maxColumns) return rejected()
    }

    for (inserted in replacementStart until replacementEnd) {
        val char = replacement[inserted]
        if (char == '\n') {
            if (++lines > maxLines) return rejected()
            columns = 0
        } else {
            columns += width(char)
            if (columns > maxColumns) return rejected()
        }
    }

    index = end
    while (index < destination.length) {
        val char = destination[index++]
        if (char == '\n') break
        columns += width(char)
        if (columns > maxColumns) return rejected()
    }
    return ProjectEditCheck(true, lines)
}
