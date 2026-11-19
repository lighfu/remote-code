package dev.remotecode.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GitSnapshotsTest {
    private val pc = ConnectionIdentity("http://pc", "token", "", "")

    @Test fun aFailedRefreshKeepsTheLastFilesOnlyForTheSameWorkingDirectory() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val snapshots = GitSnapshots(scope, failureMessage = { "offline" })
            val files = listOf(GitFile(path = "demo.kt", staged = true))
            snapshots.load("id", pc, "project", false, { true }) { GitStatus(isRepo = true, files = files) }
            snapshots.invalidate("id")
            snapshots.load("id", pc, "project", false, { true }) { error("retry") }
            assertEquals(files, snapshots.values.value["id"]?.files)
            assertEquals("retry", snapshots.values.value["id"]?.error)
            snapshots.load("id", pc, "other", false, { true }) { error("different project") }
            assertTrue(snapshots.values.value["id"]!!.files.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun mutationRejectsAnOlderInflightReadAndDoesNotShareItsRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val snapshots = GitSnapshots(scope)
            val started = CompletableDeferred<Unit>()
            val old = CompletableDeferred<GitStatus>()
            val read = async { snapshots.load("id", pc, "project", true, { true }) { started.complete(Unit); old.await() } }
            started.await()
            snapshots.invalidate("id")
            snapshots.load("id", pc, "project", true, { true }) { GitStatus(isRepo = true, branch = "new", stagedFiles = 0) }
            old.complete(GitStatus(isRepo = true, branch = "old", stagedFiles = 4)); read.await()
            assertEquals("new", snapshots.values.value["id"]?.branch)
            assertEquals(0, snapshots.values.value["id"]?.stagedFiles)
        } finally { scope.cancel() }
    }

    @Test fun duplicateReadsShareOneRequestAndNonreposUseLongerCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var clock = 0L
            val snapshots = GitSnapshots(scope) { clock }
            var calls = 0
            suspend fun load(force: Boolean = false) = snapshots.load("id", pc, "project", force, { true }) { calls++; GitStatus(isRepo = false) }
            load(); clock = 30_000; load(); assertEquals(1, calls)
            clock = 60_000; load(); assertEquals(2, calls)
            load(true); assertEquals(3, calls)
        } finally { scope.cancel() }
    }

    @Test fun switchingPcOrDeletingSessionCannotApplyALateReply() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val snapshots = GitSnapshots(scope)
            for (remove in listOf(false, true)) {
                val started = CompletableDeferred<Unit>(); val response = CompletableDeferred<GitStatus>()
                val read = async { snapshots.load("id", pc, "project", true, { true }) { started.complete(Unit); response.await() } }
                started.await()
                if (remove) snapshots.forget("id") else snapshots.clear()
                response.complete(GitStatus(isRepo = true, branch = "stale")); read.await()
                assertFalse(snapshots.values.value.containsKey("id"))
            }
        } finally { scope.cancel() }
    }
}
