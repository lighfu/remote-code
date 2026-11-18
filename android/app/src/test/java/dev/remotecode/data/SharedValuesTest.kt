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

class SharedValuesTest {
    @Test fun simultaneousImageConsumersShareTheOriginalAndKeepItAliveWhenOneCloses() = runBlocking {
        withTimeout(5_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val shared = SharedValues<String, ByteArray>(scope)
                val started = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                var calls = 0
                val original = byteArrayOf(1, 2, 3)
                val first = async { shared.get("image") { calls++; started.complete(Unit); finish.await(); original } }
                started.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) { shared.get("image") { error("duplicate transfer") } }
                first.cancel()
                finish.complete(Unit)
                assertSame(original, second.await())
                assertEquals(1, calls)
            } finally { scope.cancel() }
        }
    }

    @Test fun failedImagesCanBeRequestedAgain() = runBlocking {
        withTimeout(5_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val shared = SharedValues<String, Int>(scope)
                assertTrue(runCatching { shared.get("image") { error("offline") } }.isFailure)
                assertEquals(42, shared.get("image") { 42 })
            } finally { scope.cancel() }
        }
    }
}
