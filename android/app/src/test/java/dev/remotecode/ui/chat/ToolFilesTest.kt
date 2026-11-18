package dev.remotecode.ui.chat

import dev.remotecode.data.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolFilesTest {
    @Test fun structuredFilesKeepTheirNamesAndOverrideGeneratedText() {
        val files = listOf("Modify notes.txt", "C:\\project\\ファイルを変更.txt")
        val entry = Entry(
            id = "files", kind = "tool", tool = "fileChange", title = "2 files modified",
            files = listOf(files[0], "", files[1], files[0]), path = "other.txt",
            detail = "── New wrong.txt",
        )
        assertEquals(files, toolFiles(entry))
    }

    @Test fun structuredPathKeepsAStatusLikeFilename() {
        val entry = Entry(
            id = "path", kind = "tool", tool = "fileChange", title = "2 ファイルを変更",
            files = listOf(" "), path = "New notes.txt", detail = "── Delete other.txt",
        )
        assertEquals(listOf("New notes.txt"), toolFiles(entry))
    }

    @Test fun historicalAndEnglishFileChangeTitlesResolveTheSamePath() {
        listOf("新規", "変更", "削除", "New", "Modify", "Delete", "Created", "Modified", "Deleted").forEach { prefix ->
            val entry = Entry(id = "title", kind = "tool", tool = "fileChange", title = "$prefix src/main.kt")
            assertEquals(prefix, listOf("src/main.kt"), toolFiles(entry))
        }
    }

    @Test fun headingsResolveMultipleFilesWithoutReadingDiffContentAsPaths() {
        val entry = Entry(
            id = "diff", kind = "tool", tool = "fileChange", title = "3 files modified",
            detail = "── New src/new.kt\n+Modify content\n── 変更 C:\\project\\old.kt\n── Delete removed.kt\n── New src/new.kt",
        )
        assertEquals(listOf("src/new.kt", "C:\\project\\old.kt", "removed.kt"), toolFiles(entry))
    }

    @Test fun fileCountSummariesAreNotOpenedAsPaths() {
        listOf("2 ファイルを変更", "2 files modified", "1 file modified", "File changes", "ファイルの変更").forEach { title ->
            assertTrue(title, toolFiles(Entry(id = "summary", kind = "tool", tool = "fileChange", title = title)).isEmpty())
        }
        assertEquals("2", changedFileSummaryCount("2 files modified"))
    }

    @Test fun ordinaryFileToolsPreserveStatusLikeFilenames() {
        listOf("Read", "Write", "Edit").forEach { tool ->
            listOf("New notes.txt", "変更 notes.txt", "2 files modified").forEach { title ->
                assertEquals(listOf(title), toolFiles(Entry(id = "filename", kind = "tool", tool = tool, title = title)))
            }
        }
    }

    @Test fun onlyOneGeneratedStatusPrefixIsRemoved() {
        val entry = Entry(id = "prefix", kind = "tool", tool = "fileChange", title = "New Modify notes.txt")
        assertEquals(listOf("Modify notes.txt"), toolFiles(entry))
    }

    @Test fun singleFileSnapshotFallbackKeepsCodeIntactAndDoesNotNumberPartialPatches() {
        val path = "C:\\src\\New thing.kt"
        val files = listOf(path)
        assertTrue(isLegacyFileSnapshot(path, files, "package demo\n\n- counter\n@@ heading in text"))
        assertTrue(isLegacyFileSnapshot(path, files, "\n\n"))
        assertEquals(false, isLegacyFileSnapshot(path, listOf(path, "other"), "package demo"))
        for (patch in listOf("@@ -5 +5 @@\n-a\n+b", "── New x\n+a", " context\n- old\n+ new")) {
            assertEquals(false, isLegacyFileSnapshot(path, files, patch))
        }
    }

    @Test fun legacySingleFileTitlesMaySupplyTheMissingOperation() {
        assertEquals(1, parseDiff(fileChangePreviewDiff("Created a.kt", null, "+ val a = 1"))[1].new)
        assertEquals(2, parseDiff(fileChangePreviewDiff("削除 a.kt", listOf("a.kt"), "- x\n- y"))[2].old)
        assertEquals("+ example", fileChangePreviewDiff("New real name.txt", listOf("New real name.txt"), "+ example"))
        assertEquals("@@ -9 +9 @@\n-x\n+y", fileChangePreviewDiff("Created a.kt", null, "@@ -9 +9 @@\n-x\n+y"))
    }
}
