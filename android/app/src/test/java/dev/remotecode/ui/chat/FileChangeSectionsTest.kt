package dev.remotecode.ui.chat

import dev.remotecode.data.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileChangeSectionsTest {
    @Test fun changedFileCountsUsePathsAndHistoricalTotals() {
        val entries = listOf(
            Entry(id = "1", kind = "tool", tool = "fileChange", files = listOf("a/main.kt", "b/main.kt")),
            Entry(id = "2", kind = "tool", tool = "Edit", path = "a\\main.kt"),
            Entry(id = "3", kind = "tool", tool = "fileChange", title = "3 files modified"),
        )
        assertEquals(5, changedFileCount(entries))
    }

    @Test fun namedSectionsKeepSameBasenamesAndMarkedSourceTextSeparate() {
        val files = listOf("src/main.kt", "test/main.kt")
        val first = "── Modify src/main.kt\r\n@@ -1 +1 @@\r\n-a\r\n+── not a heading\r\n"
        val second = "── New test/main.kt\n@@ -0,0 +1 @@\n+b"
        assertEquals(mapOf(files[0] to first, files[1] to second), fileChangeSections(files, first + second))
    }

    @Test fun unidentifiedOrPartialBoundariesKeepTheCombinedPatch() {
        val files = listOf("a.kt", "b.kt")
        for (detail in listOf(
            "@@ -1 +1 @@\n-a\n+b\n@@ -1 +1 @@\n-c\n+d",
            "── Modify a.kt\n-a\n+b",
            "prefix text\n── Modify a.kt\n-a\n── Modify b.kt\n+b",
            "── Modify unknown.kt\n-a\n── Modify b.kt\n+b",
        )) assertTrue(fileChangeSections(files, detail).isEmpty())
    }

    @Test fun groupedRepeatedAndStatusLikeNamesPreserveEverySection() {
        val files = listOf("Modify notes.txt", "b.kt")
        val first = "── Modify notes.txt\n+1\n"
        val next = "── New b.kt\n+2\n"
        val last = "── Modify Modify notes.txt\n-3"
        assertEquals(mapOf(files[0] to first + last, files[1] to next), fileChangeSections(files, first + next + last))
    }
}
