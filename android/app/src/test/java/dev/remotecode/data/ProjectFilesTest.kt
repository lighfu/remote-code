package dev.remotecode.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFilesTest {
    @Test fun editorLineNumbersIncludeTrailingEmptyLine() {
        assertArrayEquals(intArrayOf(0), projectLineStarts(""))
        assertArrayEquals(intArrayOf(0, 2, 4), projectLineStarts("a\nb\n"))
    }
    @Test fun hugeParagraphAndLineCountUseViewerBeforeCreatingNativeEditor() {
        assertFalse(projectEditorFits("a".repeat(10_001), 10f))
        assertFalse(projectEditorFits("\n".repeat(50_000), 10f))
        assertTrue(projectEditorFits("fun main() {}\n".repeat(3000), 30f))
    }
    @Test fun tabsAndWideCharactersCountConservativelyForWidthGuard() {
        assertFalse(projectEditorFits("\t漢", 10f, maxLineWidthPx = 59f))
        assertTrue(projectEditorFits("\t漢", 10f, maxLineWidthPx = 60f))
        assertFalse(validProjectEntryName("../outside"))
        assertFalse(validProjectEntryName(".."))
        assertTrue(validProjectEntryName("日本語.ts"))
    }
    @Test fun draftKeysSeparateProjectRootsAndUseCanonicalDocumentPath() {
        val server = ConnectionIdentity("https://pc.invalid", "key", "", "")
        val first = ProjectDocument(root = "C:/project-a", path = "src/code.kt", name = "code.kt")
        val firstKey = first.projectDraftKey(server, "session")
        assertNotEquals(firstKey, first.copy(root = "C:/project-b").projectDraftKey(server, "session"))
        assertNotEquals(firstKey, first.projectDraftKey(server.copy(token = "other-pc-key"), "session"))
        assertNotEquals(firstKey, first.projectDraftKey(server, "other-session"))
        assertEquals(firstKey, first.copy(name = "alias.kt", absolutePath = "C:/project-a/src/code.kt").projectDraftKey(server, "session"))
    }
}
