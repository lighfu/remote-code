package dev.remotecode.ui.chat

import dev.remotecode.data.GitFile
import dev.remotecode.data.GitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileChangesTest {
    @Test fun fileLookupUsesRepositoryRootAndReturnsGitsExactSpelling() {
        val file = GitFile(path = "src/ui/画面.kt", staged = true)
        val windows = GitStatus(isRepo = true, root = "C:\\Work\\Repo", files = listOf(file))
        assertEquals(file, windows.changedFile("c:/work/repo/SRC/UI/画面.kt"))
        assertNull(windows.changedFile("C:/Work/Repo-other/src/ui/画面.kt"))
        val posix = windows.copy(root = "/work/repo")
        assertEquals(file, posix.changedFile("/work/repo/src/ui/画面.kt"))
        assertNull(posix.changedFile("/work/repo/SRC/ui/画面.kt"))
    }

    @Test fun marksOnlyCurrentLinesAndAnchorsDeletionsWithoutInventingLines() {
        val patch = "@@ -1,4 +1,4 @@\n before\n-old\n+new\n+extra\n after\n-gone\n"
        assertEquals(mapOf(2 to SourceChange.Modified, 3 to SourceChange.Added, 4 to SourceChange.Removed),
            sourceChanges(patch, listOf("before", "new", "extra", "after")))
        assertEquals(mapOf(1 to SourceChange.Removed),
            sourceChanges("@@ -1 +0,0 @@\n-first", listOf("kept")))
        assertEquals(emptyMap<Int, SourceChange>(),
            sourceChanges("@@ -1 +1 @@\n-old\n+different text", listOf("current text")))
        assertEquals(emptyMap<Int, SourceChange>(),
            sourceChanges("@@ -1 +0,0 @@\n-last", emptyList()))
    }
}
