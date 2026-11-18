package dev.remotecode.ui.chat

import dev.remotecode.R
import dev.remotecode.data.GitDiff
import dev.remotecode.data.GitFile
import dev.remotecode.i18n.AppStringResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitCommitDraftTest {
    private val labels: AppStringResolver = { id, args -> when (id) {
        R.string.git_draft_rename -> "Rename ${args[0]} to ${args[1]}"
        R.string.git_draft_add -> "Add ${args[0]}"
        R.string.git_draft_update -> "Update ${args[0]}"
        R.string.git_draft_subject -> "${args[0]} and ${args[1]} more"
        else -> "Changes unavailable"
    } }

    @Test fun stagedIndexWinsEvenWhenOnlyAnUnstagedFileIsSelected() = runBlocking {
        val partial = GitFile("config.json", staged = true, unstaged = true)
        val other = GitFile("scratch", untracked = true)
        val target = gitDraftTarget(listOf(partial, other), setOf(other.path))
        val draft = generateGitDraft(target, { path, staged ->
            assertEquals(partial.path, path)
            assertTrue(staged)
            GitDiff(path, staged, "@@ -1 +1 @@\n-\"retryLimit\": 1\n+\"retryLimit\": 2")
        }, labels)
        assertEquals("Update config.json (retryLimit)", draft)
    }

    @Test fun unstagedDraftUsesSelectionOrAllChangesAndDescribesTheDiff() = runBlocking {
        val added = GitFile("src/Cache.go", untracked = true)
        val renamed = GitFile("icon.png", originalPath = "old.png", unstaged = true, worktreeStatus = "R")
        val files = listOf(added, renamed)
        assertEquals(GitDraftTarget(DraftScope.Selected, listOf(renamed)), gitDraftTarget(files, setOf(renamed.path)))
        val target = gitDraftTarget(files, emptySet())
        assertEquals(DraftScope.WorkingTree, target.scope)
        val message = generateGitDraft(target, { path, staged ->
            assertFalse(staged)
            if (path == added.path) GitDiff(path, text = "@@ -0,0 +1 @@\n+func (c *Cache) Clear() {}")
            else GitDiff(path, binary = true)
        }, labels)
        assertTrue(message.contains("Add src/Cache.go (Clear)"))
        assertTrue(message.contains("Rename old.png to icon.png"))
    }

    @Test fun emptyDiffAfterIndexChangedDoesNotCreateAMisleadingMessage() = runBlocking {
        val file = GitFile("config.json", staged = true)
        val result = runCatching { generateGitDraft(gitDraftTarget(listOf(file), emptySet()), { path, staged -> GitDiff(path, staged) }, labels) }
        assertTrue(result.isFailure)
    }

    @Test fun stagedMethodChangeNewBinaryAndRenameDescribeEachActualPatch() = runBlocking {
        val files = listOf(
            GitFile("after.txt", originalPath = "before.txt", staged = true, indexStatus = "R"),
            GitFile("icon.bin", staged = true, indexStatus = "A", binary = true),
            GitFile("retry.kt", staged = true, indexStatus = "M"),
        )
        val diffs = listOf(
            GitDiff("after.txt", true, "diff --git a/before.txt b/after.txt\nsimilarity index 100%\nrename from before.txt\nrename to after.txt"),
            GitDiff("icon.bin", true, "Binary files /dev/null and b/icon.bin differ", binary = true),
            GitDiff("retry.kt", true, "@@ -1,3 +1,3 @@\n fun retry() {\n-    reconnect()\n+    reconnectWithBackoff()\n }"),
        ).associateBy { it.path }
        val message = generateGitDraft(gitDraftTarget(files, emptySet()), { path, _ -> diffs.getValue(path) }, labels)
        assertEquals(
            listOf("- Rename before.txt to after.txt", "- Add icon.bin", "- Update retry.kt (reconnect → reconnectWithBackoff)"),
            message.lines().drop(2),
        )
    }
}
