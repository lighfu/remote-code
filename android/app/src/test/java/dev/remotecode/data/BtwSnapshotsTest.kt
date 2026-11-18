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

class BtwSnapshotsTest {
    private val server = ServerConfig("https://remote.example", "token", "PC").connectionIdentity()
    private fun pending(id: String = "question") = BtwItem(id, "Question", "pending", at = 1)
    private fun answered(id: String = "question") = pending(id).copy(status = "done", response = "Answer", doneAt = 2)

    private fun exercise(block: suspend CoroutineScope.(BtwSnapshots) -> Unit) = runBlocking {
        withTimeout(5_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try { scope.block(BtwSnapshots(scope)) } finally { scope.cancel() }
        }
    }

    @Test fun emptySnapshotsAreLoadedOncePerConnectionAndManualRefreshStillFetches() = exercise { cache ->
        var calls = 0
        repeat(3) { cache.load("chat", server, { true }) { calls++; emptyList() } }
        assertEquals(1, calls)
        assertEquals(emptyList<BtwItem>(), cache.values.value["chat"])
        cache.load("chat", server, { true }, force = true) { calls++; listOf(answered()) }
        assertEquals(2, calls)
        assertEquals("Answer", cache.values.value["chat"]!!.single().response)
    }

    @Test fun openingChatAndQuestionSheetTogetherSharesOneRequest() = exercise { cache ->
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val first = async { cache.load("chat", server, { true }) { calls.incrementAndGet(); started.complete(Unit); release.await(); emptyList() } }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { cache.load("chat", server, { true }) { calls.incrementAndGet(); emptyList() } }
        release.complete(Unit)
        first.await(); second.await()
        assertEquals(1, calls.get())
    }

    @Test fun reconnectUsesANewRequestAndAnOldResponseCannotReplaceIt() = exercise { cache ->
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val old = async { cache.load("chat", server, { true }) { calls.incrementAndGet(); started.complete(Unit); release.await(); listOf(pending()) } }
        started.await()
        cache.reconnected()
        cache.load("chat", server, { true }) { calls.incrementAndGet(); listOf(answered()) }
        release.complete(Unit); old.await()
        assertEquals(2, calls.get())
        assertEquals("done", cache.values.value["chat"]!!.single().status)
    }

    @Test fun websocketAnswerDuringHttpWinsWithoutAnotherDownload() = exercise { cache ->
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val request = async { cache.load("chat", server, { true }) { started.complete(Unit); release.await(); listOf(pending()) } }
        started.await()
        cache.receive("chat", answered())
        release.complete(Unit); request.await()
        assertEquals("Answer", cache.values.value["chat"]!!.single().response)
        cache.load("chat", server, { true }) { error("WebSocket kept this snapshot current") }
        cache.receive("chat", pending())
        assertEquals("done", cache.values.value["chat"]!!.single().status)
    }

    @Test fun deletedRepliesAreNotResurrectedByAnInflightSnapshot() = exercise { cache ->
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val request = async { cache.load("chat", server, { true }) { started.complete(Unit); release.await(); listOf(answered("one"), answered("two")) } }
        started.await()
        cache.remove("chat", "one")
        release.complete(Unit); request.await()
        assertEquals(listOf("two"), cache.values.value["chat"]!!.map { it.id })
        cache.receive("chat", answered("one"))
        assertEquals(listOf("two"), cache.values.value["chat"]!!.map { it.id })
    }

    @Test fun clearingDuringHttpKeepsOnlyRepliesThatArriveAfterTheClear() = exercise { cache ->
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val request = async { cache.load("chat", server, { true }) { started.complete(Unit); release.await(); listOf(answered("old")) } }
        started.await()
        cache.remove("chat", null)
        cache.receive("chat", pending("new"))
        release.complete(Unit); request.await()
        assertEquals(listOf("new"), cache.values.value["chat"]!!.map { it.id })
    }

    @Test fun serverChangesAndDeletedSessionsRejectLateHttpAndWebsocketData() = exercise { cache ->
        for (deleted in listOf(false, true)) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val request = async { cache.load("chat", server, { true }) { started.complete(Unit); release.await(); listOf(answered()) } }
            started.await()
            if (deleted) cache.forget("chat") else cache.clear()
            release.complete(Unit); request.await()
            assertFalse(cache.values.value.containsKey("chat"))
            if (deleted) {
                cache.receive("chat", answered())
                assertFalse(cache.values.value.containsKey("chat"))
            }
        }
    }

    @Test fun anotherServerAndConnectionsWithoutWebsocketMustFetchAgain() = exercise { cache ->
        var calls = 0
        cache.load("chat", server, { true }) { calls++; emptyList() }
        val other = server.copy(token = "other-token")
        cache.load("chat", other, { true }) { calls++; emptyList() }
        repeat(2) { cache.load("chat", other, { false }) { calls++; emptyList() } }
        assertEquals(4, calls)
    }
}
