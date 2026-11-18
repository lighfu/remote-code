package dev.remotecode.data

import org.junit.Assert.*
import org.junit.Test

class TimelineSyncTest {
    @Test fun loadedConversationsRemainCurrentUntilTheConnectionChanges() {
        val sync = TimelineSync()
        sync.reconnected()
        assertFalse(sync.isCurrent("chat"))
        sync.install(sync.begin("chat"), emptyList(), 50)
        assertTrue(sync.isCurrent("chat"))
        sync.reconnected()
        assertFalse(sync.isCurrent("chat"))
    }

    @Test fun finishingAnOldRequestAfterReconnectDoesNotValidateTheCache() {
        val sync = TimelineSync()
        val old = sync.begin("chat")
        sync.reconnected()
        assertNull(sync.install(old, emptyList(), 50))
        assertFalse(sync.isCurrent("chat"))

        val current = sync.begin("chat")
        assertTrue(sync.receive("chat", 1, TimelineChange.Put(Entry("reply", kind = "assistant"))))
        assertEquals(listOf("reply"), sync.install(current, emptyList(), 0)?.map { it.id })
    }

    @Test fun rewindingInvalidatesOnlyThatConversationAndRejectsItsInFlightRequest() {
        val sync = TimelineSync()
        val one = sync.begin("one")
        val two = sync.begin("two")
        sync.invalidate("one", 50)

        assertNull(sync.install(one, emptyList(), 49))
        assertNotNull(sync.install(two, emptyList(), 49))
        assertFalse(sync.isCurrent("one"))
        assertTrue(sync.isCurrent("two"))
    }

    @Test fun removingAConversationRejectsItsPendingRequest() {
        val sync = TimelineSync()
        val removedSession = sync.begin("chat")
        sync.forget("chat")
        assertNull(sync.install(removedSession, emptyList(), 47))
        assertFalse(sync.isCurrent("chat"))
    }

    @Test fun firstSnapshotKeepsNewRepliesAndApprovalsAndOnlyAppliesUncoveredText() {
        val sync = TimelineSync()
        val read = sync.begin("chat")
        val previous = Entry("prev", kind = "assistant", text = "partial")
        val reply = Entry("reply", kind = "assistant", text = "Final response")
        val approval = Entry("approval", kind = "approval", status = "pending")
        sync.receive("chat", 47, TimelineChange.Append("prev", "text", ""))
        sync.receive("chat", 48, TimelineChange.Append("prev", "text", " complete"))
        sync.receive("chat", 49, TimelineChange.Put(reply))
        sync.receive("chat", 50, TimelineChange.Put(approval))

        assertEquals(
            listOf(previous.copy(text = "partial complete"), reply, approval),
            sync.install(read, listOf(previous), 47),
        )
        assertTrue(sync.isCurrent("chat"))
        assertFalse(sync.receive("chat", 48, TimelineChange.Append("prev", "text", " complete")))
        assertFalse(sync.invalidate("chat", 48))
        assertTrue(sync.receive("chat", 51, TimelineChange.Append("reply", "text", "!")))
    }

    @Test fun snapshotRejectsDeltasAlreadyIncludedButDeliveredLater() {
        val sync = TimelineSync()
        val read = sync.begin("chat")
        val snapshot = listOf(Entry("prev", kind = "tool", output = "42"))
        assertEquals(snapshot, sync.install(read, snapshot, 40))
        sync.end(read)
        assertFalse(sync.receive("chat", 40, TimelineChange.Append("prev", "output", "42")))
        assertTrue(sync.receive("chat", 41, TimelineChange.Append("prev", "output", "43")))
    }

    @Test fun legacySnapshotPreservesFullRowsAndOnlyReplaysDeltasWithAnEventBaseline() {
        val sync = TimelineSync()
        val read = sync.begin("chat")
        val old = Entry("old", kind = "assistant", text = "previous")
        val streamed = Entry("new", kind = "assistant", streaming = true)
        sync.receive("chat", null, TimelineChange.Append("old", "text", "latest"))
        sync.receive("chat", null, TimelineChange.Put(streamed))
        sync.receive("chat", null, TimelineChange.Append("new", "text", "new reply"))

        assertEquals(listOf(old, streamed.copy(text = "new reply")), sync.install(read, listOf(old), null))
        assertFalse("an ambiguous legacy delta requires a fresh read", sync.isCurrent("chat"))
        sync.end(read)
        sync.install(sync.begin("chat"), listOf(old.copy(text = "previous latest")), null)
        assertTrue(sync.isCurrent("chat"))
    }
}
