package dev.remotecode.data

internal class CodeLineIndex(text: CharSequence = "") {
    private var starts = intArrayOf(0)
    var length: Int = 0
        private set
    val lineCount: Int get() = starts.size

    init { reset(text) }

    fun reset(text: CharSequence) {
        var count = 1
        for (index in text.indices) if (text[index] == '\n') count++
        starts = IntArray(count)
        var line = 1
        for (index in text.indices) if (text[index] == '\n') starts[line++] = index + 1
        length = text.length
    }

    fun lineForOffset(offset: Int): Int = upperBound(offset.coerceIn(0, length)) - 1

    fun startOfLine(line: Int): Int = starts[line]

    fun replace(start: Int, end: Int, inserted: CharSequence, insertedStart: Int = 0, insertedEnd: Int = inserted.length) {
        require(start in 0..end && end <= length && insertedStart in 0..insertedEnd && insertedEnd <= inserted.length)
        if (start == end && insertedStart == insertedEnd) return
        val prefix = upperBound(start)
        val suffix = upperBound(end)
        var added = 0
        for (index in insertedStart until insertedEnd) if (inserted[index] == '\n') added++
        val updated = if (prefix + added == suffix) starts else IntArray(prefix + added + starts.size - suffix)
        if (updated !== starts) starts.copyInto(updated, endIndex = prefix)
        var next = prefix
        for (index in insertedStart until insertedEnd) if (inserted[index] == '\n') updated[next++] = start + index - insertedStart + 1
        val delta = insertedEnd - insertedStart - (end - start)
        for (line in suffix until starts.size) updated[next++] = starts[line] + delta
        starts = updated
        length += delta
    }

    private fun upperBound(offset: Int): Int {
        var low = 0
        var high = starts.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (starts[middle] <= offset) low = middle + 1 else high = middle
        }
        return low
    }
}
