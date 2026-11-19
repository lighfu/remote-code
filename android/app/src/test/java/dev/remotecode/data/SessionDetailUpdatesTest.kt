package dev.remotecode.data

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDetailUpdatesTest {
    private val session = SessionInfo("s1", "codex", "Project", "C:\\work\\project")
    private val all = SessionDetailField.entries.toSet()

    @Test fun changesInAnotherChatOnlyRefreshGitForTheSameDirectory() {
        val signal = DetailChange("s2", "c:/work/project/", all)
        assertTrue(signal.affects(session, SessionDetailField.Git))
        assertFalse(signal.affects(session, SessionDetailField.Cache))
        assertFalse(signal.affects(session, SessionDetailField.Context))
        assertFalse(signal.copy(cwd = "C:/work/project-copy").affects(session, SessionDetailField.Git))
        assertFalse(signal.copy(cwd = null).affects(session, SessionDetailField.Git))
        assertFalse(signal.copy(id = session.id, cwd = "C:/old").affects(session, SessionDetailField.Context))
        assertTrue(signal.copy(id = session.id, cwd = null).affects(session, SessionDetailField.Context))
        assertFalse(signal.copy(id = session.id, fields = setOf(SessionDetailField.Cache)).affects(session, SessionDetailField.Context))
    }

    @Test fun legacyEventsDistinguishUsageAndEditsFromStreamingAndReadOnlyTools() {
        assertEquals(emptySet<SessionDetailField>(), changedDetails(session, session.copy(preview = "next token", updatedAt = 4)))
        assertEquals(setOf(SessionDetailField.Context), changedDetails(session, session.copy(context = ContextUsage(16, 128))))
        assertEquals(setOf(SessionDetailField.Cache), changedDetails(session, session.copy(stats = SessionStats(cache = CacheSummary(100)))))
        assertEquals(setOf(SessionDetailField.Context, SessionDetailField.Cache), changedDetails(session, session.copy(stats = SessionStats(tokens = TokenTotals(input = 40)))))
        assertEquals(all, changedDetails(session.copy(status = "running"), session))
        assertEquals(all, entryDetails(Entry("r", kind = "result")))
        assertEquals(emptySet<SessionDetailField>(), entryDetails(Entry("t", kind = "tool", tool = "Edit", status = "running")))
        assertEquals(setOf(SessionDetailField.Git), entryDetails(Entry("t", kind = "tool", tool = "Edit", status = "done")))
        assertEquals(emptySet<SessionDetailField>(), entryDetails(Entry("t", kind = "tool", tool = "Read", status = "done")))
    }

    @Test fun manySignalsDuringASlowReadCauseOnlyOneSerialFollowupPerBurst() = runBlocking {
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 200)
        val started = Channel<Boolean>(Channel.UNLIMITED)
        val release = Channel<Unit>(Channel.UNLIMITED)
        val poll = launch {
            events.refreshOnChanges({ 60_000L }, minIntervalMs = 0) { changed ->
                started.send(changed)
                release.receive()
            }
        }
        try {
            withTimeout(2_000) {
                assertFalse(started.receive())
                repeat(2) {
                    repeat(100) { events.emit(Unit) }
                    yield()
                    assertTrue(started.tryReceive().isFailure)
                    release.send(Unit)
                    assertTrue(started.receive())
                }
                release.send(Unit)
                yield()
                assertTrue(started.tryReceive().isFailure)
            }
        } finally { poll.cancelAndJoin() }
    }
}
