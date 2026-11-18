package dev.remotecode.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundSessionPollTest {
    @Test fun unchangedSessionUpdatesDoNotRestartOrOverlapARead(): Unit = runBlocking {
        val targets = MutableSharedFlow<String?>(replay = 1)
        targets.emit("pc/session")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var cancellations = 0
        val poll = launch {
            targets.pollForegroundSession(60_000) {
                calls++
                started.complete(Unit)
                try { release.await() }
                catch (cancelled: CancellationException) { cancellations++; throw cancelled }
            }
        }
        try {
            withTimeout(2_000) { started.await() }
            repeat(5) { targets.emit("pc/session"); yield() }
            assertEquals(1, calls)
            assertEquals(0, cancellations)
            release.complete(Unit)
        } finally { poll.cancel(); poll.join() }
    }

    @Test fun leavingForegroundCancelsItsReadAndResumeStartsImmediately() = runBlocking {
        val targets = MutableStateFlow<String?>("pc/session")
        val started = Channel<String>(Channel.UNLIMITED)
        val stopped = CompletableDeferred<Unit>()
        val poll = launch {
            targets.pollForegroundSession(60_000) { target ->
                started.send(target)
                try { awaitCancellation() }
                finally { stopped.complete(Unit) }
            }
        }
        try {
            assertEquals("pc/session", withTimeout(2_000) { started.receive() })
            targets.value = null
            withTimeout(2_000) { stopped.await() }
            targets.value = "pc/session"
            assertEquals("pc/session", withTimeout(2_000) { started.receive() })
        } finally { poll.cancel(); poll.join() }
    }

    @Test fun switchingSessionOrServerInterruptsTheInterval() = runBlocking {
        val targets = MutableStateFlow<String?>("pc-a/session-a")
        val reads = Channel<String>(Channel.UNLIMITED)
        val poll = launch { targets.pollForegroundSession(60_000) { reads.send(it) } }
        try {
            assertEquals("pc-a/session-a", withTimeout(2_000) { reads.receive() })
            targets.value = "pc-a/session-b"
            assertEquals("pc-a/session-b", withTimeout(2_000) { reads.receive() })
            targets.value = "pc-b/session-b"
            assertEquals("pc-b/session-b", withTimeout(2_000) { reads.receive() })
        } finally { poll.cancel(); poll.join() }
    }

    @Test fun aFailedReadRetriesWithoutEndingTheWatcher() = runBlocking {
        val targets = MutableStateFlow<String?>("pc/session")
        val recovered = CompletableDeferred<Unit>()
        var calls = 0
        val poll = launch {
            targets.pollForegroundSession(1) {
                calls++
                if (calls == 1) throw IllegalStateException("offline")
                recovered.complete(Unit)
            }
        }
        try {
            withTimeout(2_000) { recovered.await() }
            assertEquals(2, calls)
        } finally { poll.cancel(); poll.join() }
    }
}
