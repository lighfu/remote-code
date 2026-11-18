package dev.remotecode.ui.chat

import org.junit.Assert.*
import org.junit.Test

class CodeTextTest {
    @Test fun lineCountsRespectEmptyFilesAndFinalNewlines() {
        assertEquals(emptyList<String>(), sourceLines(""))
        assertEquals(listOf("a", "b"), sourceLines("a\r\nb\r\n"))
        assertEquals(listOf(""), sourceLines("\n"))
        assertEquals(listOf("a", ""), sourceLines("a\n\n"))
    }

    @Test fun languagesAndTokensFollowTheFileType() {
        assertEquals("TypeScript", codeLanguage("C:\\src\\App.TS").label)
        assertEquals("Dockerfile", codeLanguage("Dockerfile").label)
        val python = highlightCode("def hello(): # note\n    return \"hi\"", codeLanguage("a.py"))
        assertEquals(CodeInk.Keyword, python[0].tokens.first().ink)
        assertEquals(CodeInk.Comment, python[0].tokens.last().ink)
        assertEquals(CodeInk.String, python[1].tokens.last().ink)
        val sql = highlightCode("SELECT count FROM items", codeLanguage("a.sql"))[0]
        assertEquals(2, sql.tokens.count { it.ink == CodeInk.Keyword })
        assertTrue(highlightCode("def hello # note", codeLanguage("a.txt"))[0].tokens.isEmpty())
    }

    @Test fun commentsAndStringsContinueAcrossLines() {
        val lines = highlightCode("/* first\nstill comment */ const value = 42;", codeLanguage("a.ts"))
        assertEquals(CodeInk.Comment, lines[1].tokens.first().ink)
        assertTrue(lines[1].tokens.any { it.ink == CodeInk.Keyword })
        assertEquals(CodeInk.Number, lines[1].tokens.last().ink)
        val python = highlightCode("s = \"\"\"first\nsecond\"\"\"", codeLanguage("a.py"))
        assertEquals(CodeInk.String, python[1].tokens.single().ink)
    }

    @Test fun unifiedDiffKeepsSeparateOldAndNewNumbers() {
        val lines = parseDiff("@@ -40,3 +40,4 @@\n same\n-old\n+new\n+extra\n tail")
        assertEquals(40, lines[1].old)
        assertEquals(41, lines[2].old)
        assertNull(lines[2].new)
        assertEquals(41, lines[3].new)
        assertNull(lines[3].old)
        assertEquals(42, lines[4].new)
        assertEquals(42, lines[5].old)
        assertEquals(43, lines[5].new)
    }

    @Test fun fileHeadersResetNumbersAndLegacyDiffDoesNotInventThem() {
        val lines = parseDiff("@@ -5 +5 @@\n-a\n+b\n── 変更 next.ts\n- old\n+ new")
        assertNull(lines[4].old)
        assertEquals("old", lines[4].text)
        assertNull(lines[5].new)
        val plus = parseDiff("--- a/a.ts\n+++ b/a.ts\n@@ -1 +1 @@\n---counter\n+++counter")
        assertEquals('-', plus[3].kind)
        assertEquals('+', plus[4].kind)
        assertEquals("++counter", plus[4].text)
    }

    @Test fun insertionAndDeletionHaveOnlyOneNumber() {
        val added = parseDiff("@@ -0,0 +1,1 @@\n+hello")[1]
        assertNull(added.old)
        assertEquals(1, added.new)
        val removed = parseDiff("@@ -1,1 +0,0 @@\n-hello")[1]
        assertEquals(1, removed.old)
        assertNull(removed.new)
    }

    @Test fun fileChangeHunksNumberEmptyAndDiffLikeSourceLinesAcrossFiles() {
        val diff = "── New first.kt\n@@ -0,0 +1,4 @@\n+++ header\n+\n+@@ -4 +4 @@\n+── Delete fake\n" +
            "── Delete second.kt\n@@ -1,2 +0,0 @@\n--- old\n-\n── Modify other.kt\n@@ -39 +40 @@\n-old\n+new"
        val rows = parseDiff(diff)
        assertEquals(listOf("++ header", "", "@@ -4 +4 @@", "── Delete fake"), rows.slice(2..5).map { it.text })
        assertEquals(listOf(1, 2, 3, 4), rows.slice(2..5).map { it.new })
        assertTrue(rows.slice(2..5).all { it.kind == '+' && it.old == null })
        assertEquals(listOf("-- old", ""), rows.slice(8..9).map { it.text })
        assertEquals(listOf(1, 2), rows.slice(8..9).map { it.old })
        assertEquals(39, rows[12].old)
        assertEquals(40, rows[13].new)
    }

    @Test fun numberedLegacyChangesUseExplicitFileKindsAndBoundaries() {
        val rows = parseDiff("── 新規 one.txt\n+ first\n+ \n── Created two.txt\n+ second\n── Deleted gone.txt\n- old\n── Modify more.txt\n+ unknown")
        assertEquals(1, rows[1].new)
        assertEquals(2, rows[2].new)
        assertEquals("", rows[2].text)
        assertEquals(1, rows[4].new)
        assertEquals(1, rows[6].old)
        assertNull(rows[8].new)
    }

    @Test fun diffHighlightsTheTwoVersionsIndependentlyAcrossCommentsAndRenames() {
        val text = """diff --git "a/old file.kt" "b/new file.py"
--- "a/old file.kt"
+++ "b/new file.py"
@@ -12,2 +1,2 @@
-/* deleted
+def replacement():
-comment */
+    return True # new"""
        val rows = highlightDiff(text, "").rows
        assertEquals(CodeInk.Comment, rows[4].code.tokens.single().ink)
        assertEquals(CodeInk.Keyword, rows[5].code.tokens.first().ink)
        assertEquals(CodeInk.Comment, rows[6].code.tokens.single().ink)
        assertEquals(CodeInk.Comment, rows[7].code.tokens.last().ink)
        assertEquals(13, rows[6].line.old)
        assertEquals(2, rows[7].line.new)
    }

    @Test fun diffHandlesAdjacentFileHeadersAndLegacyToolChangesWithoutDroppingRows() {
        val text = "--- a/first.ts\n+++ b/first.ts\n@@ -1 +1 @@\n-a\n+b\n" +
            "--- a/second.py\n+++ b/second.py\n@@ -7 +7 @@\n-# old\n+# new\n" +
            "── Modified status.sql\n- SELECT value\n+ SELECT count"
        val full = highlightDiff(text, "fallback.txt")
        assertEquals(text.lines().size, full.rows.size)
        assertEquals('@', full.rows[5].line.kind)
        assertEquals(CodeInk.Comment, full.rows[9].code.tokens.single().ink)
        assertEquals(CodeInk.Keyword, full.rows[12].code.tokens.first().ink)
        assertNull(full.rows[12].line.new)
        val preview = highlightDiff(text, "fallback.txt", maxLines = 7)
        assertEquals(full.rows.take(7), preview.rows)
        assertEquals(full.rows.size, preview.totalLines)
    }

    @Test fun markersDoNotResetCodeStateOrSwallowLongLines() {
        val payload = "x".repeat(8192)
        val text = "@@ -1,2 +1,2 @@\n-/* old\n+val message = 42\n\\ No newline at end of file\n-closed */\n+$payload"
        val rows = highlightDiff(text, "message.kt").rows
        assertTrue(rows[3].code.tokens.isEmpty())
        assertEquals(CodeInk.Comment, rows[4].code.tokens.single().ink)
        assertEquals(payload, rows.last().code.text)
    }
}
