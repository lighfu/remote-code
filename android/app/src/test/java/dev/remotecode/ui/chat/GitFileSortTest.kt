package dev.remotecode.ui.chat

import dev.remotecode.data.AppJson
import dev.remotecode.data.GitFile
import org.junit.Assert.assertEquals
import org.junit.Test

class GitFileSortTest {
    private fun paths(sort: GitFileSort, vararg files: GitFile, ascending: Boolean = true) =
        sort.sorted(files.toList(), ascending).map { it.path }

    @Test fun namesUseTheBasenameWithDeterministicPathTies() {
        val files = arrayOf(GitFile("a/Z.kt"), GitFile("z/A.kt"), GitFile("b/a.kt"))
        assertEquals(listOf("b/a.kt", "z/A.kt", "a/Z.kt"), paths(GitFileSort.Name, *files))
        assertEquals(listOf("a/Z.kt", "z/A.kt", "b/a.kt"), paths(GitFileSort.Name, *files, ascending = false))
    }

    @Test fun oldServerAndDeletedFilesSortLastEvenWithDescendingDatesAndSizes() {
        val old = AppJson.decodeFromString<GitFile>("""{"path":"deleted","indexStatus":"D","staged":true}""")
        val a = GitFile("a", sizeBytes = 0, modifiedAt = 30)
        val b = GitFile("b", sizeBytes = Long.MAX_VALUE, modifiedAt = 20)
        assertEquals(listOf("a", "b", "deleted"), paths(GitFileSort.Size, old, b, a))
        assertEquals(listOf("b", "a", "deleted"), paths(GitFileSort.Size, old, b, a, ascending = false))
        assertEquals(listOf("b", "a", "deleted"), paths(GitFileSort.Modified, old, b, a))
        assertEquals(listOf("a", "b", "deleted"), paths(GitFileSort.Modified, old, b, a, ascending = false))
    }

    @Test fun changedLinesCountBothSidesAndSeparatePartialAndBinaryCountsFromKnownZero() {
        val files = arrayOf(GitFile("z", binary = true), GitFile("partial", lineStatsPartial = true, additions = 40),
            GitFile("zero"), GitFile("large", additions = Int.MAX_VALUE, deletions = 2), GitFile("removed", deletions = 3))
        assertEquals(listOf("zero", "removed", "large", "partial", "z"), paths(GitFileSort.Lines, *files))
        assertEquals(listOf("large", "removed", "zero", "partial", "z"), paths(GitFileSort.Lines, *files, ascending = false))
    }

    @Test fun combinedAndConflictingStatusesHaveIndependentGroups() {
        val files = arrayOf(GitFile("untracked", untracked = true, unstaged = true), GitFile("working", unstaged = true),
            GitFile("staged", staged = true), GitFile("both", staged = true, unstaged = true),
            GitFile("conflict", conflict = true, unstaged = true), GitFile("unknown"))
        val order = listOf("conflict", "both", "staged", "working", "untracked")
        assertEquals(order + "unknown", paths(GitFileSort.Status, *files))
        assertEquals(order.reversed() + "unknown", paths(GitFileSort.Status, *files, ascending = false))
    }
}
