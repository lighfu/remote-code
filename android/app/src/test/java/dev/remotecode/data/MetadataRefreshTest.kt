package dev.remotecode.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MetadataRefreshTest {
    @Test fun installationCompletionStartsAFreshFetchAndRejectsTheLatePreinstallResponse() = runBlocking {
        withTimeout(5_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val reads = SharedRequests<Long>(scope)
                val refresh = MetadataRefresh()
                val calls = AtomicInteger()
                val providersAvailable = AtomicReference<Boolean?>()
                val oldStarted = CompletableDeferred<Unit>()
                val releaseOld = CompletableDeferred<Unit>()
                val oldTicket = refresh.ticket()
                val old = async {
                    reads.run(oldTicket) {
                        calls.incrementAndGet()
                        oldStarted.complete(Unit)
                        releaseOld.await()
                        refresh.publish(oldTicket) { providersAvailable.set(false) }
                    }
                }
                oldStarted.await()
                // Normal readers still share the preinstall fetch.
                val shared = async(start = CoroutineStart.UNDISPATCHED) {
                    reads.run(refresh.ticket()) { calls.incrementAndGet() }
                }
                val completedTicket = refresh.ticket(fresh = true)
                reads.run(completedTicket) {
                    calls.incrementAndGet()
                    refresh.publish(completedTicket) { providersAvailable.set(true) }
                }
                assertEquals(true, providersAvailable.get())
                releaseOld.complete(Unit)
                old.await()
                shared.await()
                assertEquals("The old meta request must not replace installed availability", true, providersAvailable.get())
                assertEquals("Completion needs a new request rather than the old shared flight", 2, calls.get())
            } finally {
                scope.cancel()
            }
        }
    }

    @Test fun onlySuccessfulTransitionsRequestAnInstallRefresh() {
        fun state(status: String, installed: Boolean) = ServerDependencies(dependencies = listOf(ServerDependency("codex", status = status, installed = installed)))
        val before = state("Not installed", false)
        val working = state("Installing...", false)
        val failedWithExecutable = state("Failed", true)
        val completed = state("Installed", true)
        assertFalse(completed.completedInstallation(null))
        assertFalse(working.completedInstallation(before))
        assertFalse(failedWithExecutable.completedInstallation(working))
        assertTrue(completed.completedInstallation(working))
        assertTrue(completed.completedInstallation(before))
        assertTrue(completed.completedInstallation(failedWithExecutable))
        assertFalse(completed.completedInstallation(completed))
    }
}
