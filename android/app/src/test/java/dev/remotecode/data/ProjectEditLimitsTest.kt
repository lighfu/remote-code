package dev.remotecode.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ProjectEditLimitsTest {
    private fun check(
        text: CharSequence,
        start: Int,
        end: Int,
        inserted: CharSequence,
        lines: Int = text.count { it == '\n' } + 1,
        columns: Int = 100,
        maxCharacters: Int = 2 * 1024 * 1024,
        maxLines: Int = 50_000,
    ) = checkProjectEdit(text, start, end, inserted, 0, inserted.length, lines, columns, maxCharacters, maxLines)

    @Test fun splittingAndJoiningLinesUsesTheReplacementSliceAndKeepsLineCounts() {
        val split = checkProjectEdit("alpha\nomega", 2, 3, "ignore\nX\nignore", 6, 9, 2, 100)
        assertTrue(split.allowed)
        assertEquals(4, split.lineCount)
        val join = check("ab\ncd\nef", 2, 6, "")
        assertTrue(join.allowed)
        assertEquals(1, join.lineCount)
        assertTrue(check("", 0, 0, "").allowed)
    }

    @Test fun prefixReplacementAndSuffixShareTheSameWidthLimit() {
        assertTrue(check("ab\ncd", 2, 3, "", columns = 4).allowed)
        assertFalse(check("ab\ncd", 2, 3, "", columns = 3).allowed)
        assertTrue(check("ab--cd", 2, 4, "\n", columns = 2).allowed)
        assertTrue(check("aZb", 1, 2, "\t漢", columns = 8).allowed)
        assertFalse(check("aZb", 1, 2, "\t漢", columns = 7).allowed)
        assertFalse(check("ab", 1, 1, "\nXXXXX\n", columns = 4).allowed)
    }

    @Test fun characterAndLineLimitsAllowDeletionsThatMakeRoomForNewContent() {
        assertTrue(check("abcd", 1, 3, "XY", maxCharacters = 4).allowed)
        assertFalse(check("abcd", 1, 3, "XYZ", maxCharacters = 4).allowed)
        assertTrue(check("a\nb\nc", 1, 4, "\nx\n", maxLines = 3).allowed)
        assertFalse(check("a\nb\nc", 1, 4, "\nx\ny\n", maxLines = 3).allowed)
        val fullLines = "\n".repeat(49_999)
        assertTrue(check(fullLines, 0, fullLines.length, "", lines = 50_000).allowed)
        assertFalse(check(fullLines, fullLines.length, fullLines.length, "\n", lines = 50_000).allowed)
    }

    @Test fun invalidRangesAndOversizedPastesFailBeforeAccessingCharacters() {
        val original = CountingText("old")
        val replacement = CountingText("large")
        assertFalse(checkProjectEdit(original, -1, 0, replacement, 0, 1, 1, 100).allowed)
        assertFalse(checkProjectEdit(original, 2, 1, replacement, 0, 1, 1, 100).allowed)
        assertFalse(checkProjectEdit(original, 0, 4, replacement, 0, 1, 1, 100).allowed)
        assertFalse(checkProjectEdit(original, 0, 1, replacement, 3, 2, 1, 100).allowed)
        assertFalse(checkProjectEdit(original, 0, 1, replacement, 0, 6, 1, 100).allowed)
        val result = checkProjectEdit(original, 0, 0, replacement, 0, replacement.length, 1, 100, maxCharacters = 7)
        assertFalse(result.allowed)
        assertEquals(1, result.lineCount)
        assertEquals(0, original.reads)
        assertEquals(0, replacement.reads)
    }

    @Test fun editingOneLineDoesNotReadOrCopyUnrelatedDocumentText() {
        val original = CountingText("line\n".repeat(40_000))
        val replacement = CountingText("X")
        val offset = 20_000 * 5 + 2
        val result = checkProjectEdit(original, offset, offset + 1, replacement, 0, 1, 40_001, 100)
        assertTrue(result.allowed)
        assertEquals(40_001, result.lineCount)
        assertTrue("A local edit must only inspect its own line", original.reads < 16)
        assertEquals(1, replacement.reads)
    }

    @Test fun aHugeSingleLinePasteStopsAtTheWidthLimit() {
        val replacement = CountingText("x".repeat(1_000_000))
        assertFalse(checkProjectEdit("", 0, 0, replacement, 0, replacement.length, 1, 128).allowed)
        assertEquals(129, replacement.reads)
    }

    @Test fun randomizedEditsMatchFullDocumentValidation() {
        val random = Random(90210)
        val alphabet = "abc \t漢\n"
        val maxCharacters = 120
        val maxLines = 12
        val maxColumns = 18
        var text = "first\nsecond\n"
        repeat(2_000) {
            val start = random.nextInt(text.length + 1)
            val end = random.nextInt(start, text.length + 1)
            val inserted = buildString { repeat(random.nextInt(25)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            val candidate = text.replaceRange(start, end, inserted)
            val expected = candidate.length <= maxCharacters && projectEditorFits(candidate, 1f, maxColumns.toFloat(), maxLines)
            val result = check(text, start, end, inserted, columns = maxColumns, maxCharacters = maxCharacters, maxLines = maxLines)
            assertEquals("Mismatch for edit $start..$end with ${inserted.length} chars", expected, result.allowed)
            if (result.allowed) {
                assertEquals(candidate.count { it == '\n' } + 1, result.lineCount)
                text = candidate
            } else assertEquals(text.count { it == '\n' } + 1, result.lineCount)
        }
    }

    private class CountingText(private val value: String) : CharSequence {
        var reads = 0
            private set
        override val length: Int get() = value.length
        override fun get(index: Int): Char { reads++; return value[index] }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("Document slices must not be copied during validation")
        override fun toString(): String = error("Document text must not be materialized during validation")
    }
}
