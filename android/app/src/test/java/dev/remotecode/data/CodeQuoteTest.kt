package dev.remotecode.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CodeQuoteTest {
    private fun selection(path: String, text: String, start: Int, end: Int, isDraft: Boolean = false) =
        codeQuoteFromSelection(path, text, start, end, isDraft, testAppStrings)
    private fun lines(path: String, text: String, start: Int, end: Int, isDraft: Boolean = false) =
        codeQuoteFromLines(path, text, start, end, isDraft, testAppStrings)
    private fun buildPrompt(input: String, quotes: List<CodeQuote>) = codeQuotePrompt(input, quotes, testAppStrings)
    private fun addDraft(server: ConnectionIdentity, session: String, quote: CodeQuote) =
        CodeQuoteDrafts.add(server, session, quote, testAppStrings)

    @Test fun reversedSelectionUsesInclusiveSourceLinesAndKeepsWhitespace() {
        val text = "before\n  val answer = 42\nnext\n"
        val start = text.indexOf("  val")
        val end = text.indexOf("next")
        val quote = selection("C:\\work\\Main.kt", text, end, start)
        assertEquals(2, quote.startLine)
        assertEquals(2, quote.endLine)
        assertEquals("  val answer = 42\n", quote.code)
        assertFalse(quote.isDraft)
    }

    @Test fun originalCrLfOffsetsAndCrLinesNormalizeWithoutChangingTheSelection() {
        val text = "日本語\r\n  two\rthree\n"
        val quote = selection("/work/file.ts", text, text.indexOf("  two"), text.indexOf("three"), isDraft = true)
        assertEquals(2, quote.startLine)
        assertEquals(2, quote.endLine)
        assertEquals("  two\n", quote.code)
        assertTrue(quote.isDraft)
        val all = selection("/work/file.ts", text, 0, text.length)
        assertEquals(3, all.endLine)
        assertEquals("日本語\n  two\nthree\n", all.code)
    }

    @Test fun lineRangesIncludeWholeLinesAndNeverTheFollowingLine() {
        val quote = lines("C:\\work\\code.py", "first\r\n  second\r\nthird\r\nfourth", 2, 3)
        assertEquals(2, quote.startLine)
        assertEquals(3, quote.endLine)
        assertEquals("  second\nthird\n", quote.code)
        val last = lines("/file", "first\nlast", 2, 2)
        assertEquals("last", last.code)
    }

    @Test fun emptyStaleAndOversizedSelectionsFailWithoutTruncating() {
        assertThrows(IllegalArgumentException::class.java) { selection("/file", "abc", 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { selection("/file", "abc", 0, 4) }
        assertThrows(IllegalArgumentException::class.java) { lines("/file", "abc\n", 2, 2) }
        assertThrows(IllegalArgumentException::class.java) { lines("/file", "abc", 0, 1) }
        val text = "x".repeat(MAX_CODE_QUOTE_CHARS)
        assertEquals(text, selection("/file", text, 0, text.length).code)
        assertThrows(IllegalArgumentException::class.java) { selection("/file", text + "x", 0, text.length + 1) }
    }

    @Test fun promptKeepsTheRequestPathLinesDraftStateAndEmbeddedFences() {
        val code = "value = \"```\"\n~~~\n"
        val quote = CodeQuote("C:\\work\\my`file.kt", 4, 5, code, isDraft = true)
        val prompt = buildPrompt("  この条件を修正して  ", listOf(quote))
        assertTrue(prompt.startsWith("この条件を修正して\n\n"))
        assertTrue(prompt.contains("source-marker: C:\\work\\my`file.kt\nline-marker: 4–5"))
        assertTrue(prompt.contains("draft-marker"))
        assertTrue(prompt.contains("\n````\n$code````"))
        assertEquals("existing request", buildPrompt(" existing request ", emptyList()))
    }

    @Test fun draftSnapshotsStayIsolatedByServerAndSessionAndRemovalKeepsNewQuotes() {
        val id = UUID.randomUUID().toString()
        val first = ConnectionIdentity("https://pc-one", "test", "", "")
        val second = first.copy(url = "https://pc-two")
        val quote = CodeQuote("/file", 1, 1, "old")
        val later = CodeQuote("/file", 1, 1, "new")
        try {
            val original = CodeQuoteDrafts.quotes(first, id)
            addDraft(first, id, quote)
            assertEquals(listOf(quote), original.value)
            assertTrue(CodeQuoteDrafts.quotes(second, id).value.isEmpty())
            assertTrue(CodeQuoteDrafts.quotes(first, id + "other").value.isEmpty())
            addDraft(first, id, later)
            CodeQuoteDrafts.remove(first, id, quote.id)
            assertEquals(listOf(later), original.value)
        } finally {
            CodeQuoteDrafts.clear(first, id)
            CodeQuoteDrafts.clear(second, id)
            CodeQuoteDrafts.clear(first, id + "other")
        }
    }

    @Test fun quoteLimitsRejectTheAdditionWithoutLosingExistingDrafts() {
        val server = ConnectionIdentity("https://test", "test", "", "")
        val session = UUID.randomUUID().toString()
        try {
            repeat(MAX_CODE_QUOTES) { addDraft(server, session, CodeQuote("/file", 1, 1, "$it")) }
            val full = CodeQuoteDrafts.quotes(server, session).value
            assertThrows(IllegalArgumentException::class.java) { addDraft(server, session, CodeQuote("/file", 1, 1, "extra")) }
            assertEquals(full, CodeQuoteDrafts.quotes(server, session).value)
            CodeQuoteDrafts.clear(server, session)
            val large = "x".repeat(MAX_CODE_QUOTE_CHARS)
            repeat(2) { addDraft(server, session, CodeQuote("/file", 1, 1, large)) }
            assertThrows(IllegalArgumentException::class.java) { addDraft(server, session, CodeQuote("/file", 1, 1, "extra")) }
            assertEquals(2, CodeQuoteDrafts.quotes(server, session).value.size)
        } finally { CodeQuoteDrafts.clear(server, session) }
    }
}
