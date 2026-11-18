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

class SharedRequestsTest {
    @Test fun publicUrlAndDisplayNameDoNotInvalidateTheActiveConnection() {
        val server = ServerConfig("http://127.0.0.1:8787", "token", "PC")
        val announced = server.copy(name = "自宅 PC", publicUrl = "https://example.trycloudflare.com")
        assertEquals(server.connectionIdentity(), announced.connectionIdentity())
        assertEquals(server.connectionIdentity(), server.copy(url = server.url + "/").connectionIdentity())
    }

    @Test fun endpointOrCredentialsIdentifyADifferentConnection() {
        val server = ServerConfig("https://remote.example", "token", "PC", "access-id", "access-secret")
        for (changed in listOf(
            server.copy(url = "https://another.example"),
            server.copy(token = "different-token"),
            server.copy(accessId = "different-id"),
            server.copy(accessSecret = "different-secret"),
        )) assertNotEquals(server.connectionIdentity(), changed.connectionIdentity())
    }

    @Test fun overlappingRequestsShareOneFetchAndLaterRequestsRefresh() = runBlocking {
        withTimeout(5_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val reads = SharedRequests<String>(scope)
                val calls = AtomicInteger()
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val first = async { reads.run("session") { calls.incrementAndGet(); started.complete(Unit); release.await() } }
                started.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) { reads.run("session") { calls.incrementAndGet() } }
                release.complete(Unit)
                first.await()
                second.await()
                assertEquals(1, calls.get())
                reads.run("session") { calls.incrementAndGet() }
                assertEquals(2, calls.get())
            } finally { scope.cancel() }
        }
    }

    @Test fun closingOneScreenDoesNotCancelAnotherScreensFetch() = runBlocking {
        withTimeout(5_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val reads = SharedRequests<String>(scope)
                val calls = AtomicInteger()
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val first = async { reads.run("models") { calls.incrementAndGet(); started.complete(Unit); release.await() } }
                started.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) { reads.run("models") { calls.incrementAndGet() } }
                first.cancel()
                release.complete(Unit)
                second.await()
                assertEquals(1, calls.get())
            } finally { scope.cancel() }
        }
    }

    @Test fun failedRequestsCanBeRetried() = runBlocking {
        withTimeout(5_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val reads = SharedRequests<String>(scope)
                assertTrue(runCatching { reads.run("session") { error("offline") } }.isFailure)
                var retried = false
                reads.run("session") { retried = true }
                assertTrue(retried)
            } finally { scope.cancel() }
        }
    }

    @Test fun usageSnapshotsCannotReplaceNewerEventsOrEraseOtherProviders() {
        val live = UsageInfo(claude = ProviderUsage(at = 200, plan = "current"), codex = ProviderUsage(at = 300))
        val snapshot = UsageInfo(claude = ProviderUsage(at = 0), antigravity = ProviderUsage(at = 100))
        val merged = mergeUsage(live, snapshot)
        assertEquals("current", merged.claude?.plan)
        assertEquals(300L, merged.codex?.at)
        assertEquals(100L, merged.antigravity?.at)
        assertEquals(400L, mergeUsage(merged, UsageInfo(claude = ProviderUsage(at = 400))).claude?.at)
    }
}
