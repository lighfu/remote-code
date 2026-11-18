package dev.remotecode.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CodeLineIndexTest {
    private fun assertLines(index: CodeLineIndex, text: String) {
        val expected = mutableListOf(0)
        text.forEachIndexed { offset, char -> if (char == '\n') expected += offset + 1 }
        assertEquals(text.length, index.length)
        assertEquals(expected.size, index.lineCount)
        expected.forEachIndexed { line, offset -> assertEquals(offset, index.startOfLine(line)) }
        for (offset in 0..text.length) {
            assertEquals(expected.indexOfLast { it <= offset }, index.lineForOffset(offset))
        }
    }

    @Test fun emptyTrailingAndConsecutiveLinesKeepTheirLogicalPositions() {
        val index = CodeLineIndex()
        for (text in listOf("", "\n", "a\n", "a\n\nb", "😀\n漢字\n", "a\r\nb")) {
            index.reset(text)
            assertLines(index, text)
            assertEquals(0, index.lineForOffset(-1))
            assertEquals(index.lineCount - 1, index.lineForOffset(text.length + 10))
        }
    }

    @Test fun splittingJoiningAndFullReplacementUseOnlyTheInsertedSlice() {
        var text = "first\nsecond\nthird\n"
        val index = CodeLineIndex(text)
        fun edit(start: Int, end: Int, value: String) {
            val source = "unused\n$value\nunused"
            index.replace(start, end, source, 7, 7 + value.length)
            text = text.replaceRange(start, end, value)
            assertLines(index, text)
        }
        edit(6, 6, "prefix\n")
        edit(5, 6, "")
        edit(0, 0, "\n\n")
        edit(text.length, text.length, "\n")
        edit(0, text.length, "😀\nZ")
        edit(0, text.length, "")
        edit(0, 0, "")
    }

    @Test fun randomizedEditsMatchFullParsingAtEveryOffset() {
        val random = Random(7481)
        val alphabet = charArrayOf('a', 'b', '漢', '\t', '\n', '\uD83D', '\uDE00')
        var text = "first\nsecond\n"
        val index = CodeLineIndex(text)
        repeat(4_000) {
            val start = random.nextInt(text.length + 1)
            val end = random.nextInt(start, text.length + 1)
            val replacement = buildString { repeat(random.nextInt(30)) { append(alphabet[random.nextInt(alphabet.size)]) } }
            index.replace(start, end, replacement)
            text = text.replaceRange(start, end, replacement)
            assertLines(index, text)
        }
    }

    @Test fun aLocalEditDoesNotScanOrCopyTheDocument() {
        val text = "line\n".repeat(40_000)
        val index = CodeLineIndex(text)
        val offset = 20_000 * 5 + 2
        val updated = text.replaceRange(offset, offset, "X")
        val source = CountingText(updated)
        index.replace(offset, offset, source, offset, offset + 1)
        assertEquals(2, source.reads)
        assertEquals(40_001, index.lineCount)
        assertEquals(20_000, index.lineForOffset(offset))
        assertEquals(20_001 * 5 + 1, index.startOfLine(20_001))
        assertEquals(updated.length, index.startOfLine(40_000))
    }

    @Test fun invalidEditsLeaveTheIndexUnchanged() {
        val index = CodeLineIndex("a\nb")
        for (edit in listOf<() -> Unit>(
            { index.replace(-1, 0, "") },
            { index.replace(2, 1, "") },
            { index.replace(0, 4, "") },
            { index.replace(0, 0, "x", -1, 0) },
            { index.replace(0, 0, "x", 1, 0) },
            { index.replace(0, 0, "x", 0, 2) },
        )) {
            var rejected = false
            try { edit() } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertLines(index, "a\nb")
        }
    }

    private class CountingText(private val value: String) : CharSequence {
        var reads = 0
        override val length: Int get() = value.length
        override fun get(index: Int): Char { reads++; return value[index] }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("Unexpected copy")
        override fun toString(): String = error("Unexpected copy")
    }
}
