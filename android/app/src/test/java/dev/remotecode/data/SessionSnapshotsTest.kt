package dev.remotecode.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSnapshotsTest {
    @Test fun completedTurnCanStillHaveAnActiveShellCommand() {
        val snapshots = SessionSnapshots()
        val sessionFields = """ "id":"agy", "provider":"antigravity", "title":"Test", "cwd":"C:/work" """
        val idle = AppJson.decodeFromString(SessionInfo.serializer(), "{$sessionFields}")
        val background = AppJson.decodeFromString(SessionInfo.serializer(), """{$sessionFields, "backgroundCommandCount":1}""")
        assertFalse(idle.isBackgroundCommandOnly)
        assertTrue(background.isBackgroundCommandOnly)
        assertFalse(background.isRunning)
        assertFalse(background.isWaiting)
        assertFalse(background.copy(status = "running").isBackgroundCommandOnly)
        assertFalse(background.copy(pending = 1).isBackgroundCommandOnly)

        snapshots.replaceFromSocket(listOf(background))
        val beforeExit = snapshots.beginRead()
        snapshots.receive(idle)
        snapshots.applyRead(beforeExit, background)
        assertFalse(snapshots.values.value.single().isBackgroundCommandOnly)
    }

    @Test fun timelineResponseCannotRevertWorkingOrCompletedState() {
        val snapshots = SessionSnapshots()
        val idle = session("current")
        val running = idle.copy(status = "running")
        snapshots.replaceFromSocket(listOf(idle))

        val beforeStart = snapshots.beginRead()
        snapshots.receive(running)
        snapshots.applyRead(beforeStart, idle)
        assertEquals(running, snapshots.values.value.single())

        val beforeCompletion = snapshots.beginRead()
        snapshots.receive(idle)
        snapshots.applyRead(beforeCompletion, running)
        assertEquals(idle, snapshots.values.value.single())
    }

    @Test fun sessionListKeepsConcurrentSocketChangesAndAcceptsTheRest() {
        val snapshots = SessionSnapshots()
        val first = session("first")
        val removed = session("removed")
        val other = session("other")
        val added = session("added")
        snapshots.replaceFromSocket(listOf(first, removed, other))

        val started = snapshots.beginRead()
        val running = first.copy(status = "running")
        snapshots.receive(running)
        snapshots.remove(removed.id)
        snapshots.receive(added)
        val updated = other.copy(title = "New title")
        snapshots.applyRead(started, listOf(first, removed, updated))

        assertEquals(setOf(running, updated, added), snapshots.values.value.toSet())
    }

    @Test fun helloAndClearingInvalidateOutstandingReads() {
        val snapshots = SessionSnapshots()
        val idle = session("current")
        val running = idle.copy(status = "running")

        val disconnected = snapshots.beginRead()
        snapshots.reconnected()
        snapshots.applyRead(disconnected, idle)
        assertTrue(snapshots.values.value.isEmpty())

        val beforeHello = snapshots.beginRead()
        snapshots.replaceFromSocket(listOf(running))
        snapshots.applyRead(beforeHello, listOf(idle))
        assertEquals(running, snapshots.values.value.single())

        val beforeClear = snapshots.beginRead()
        snapshots.clear()
        snapshots.applyRead(beforeClear, listOf(idle))
        assertTrue(snapshots.values.value.isEmpty())
    }

    @Test fun delayedReadsCannotOverrideANewerHttpResponse() {
        val snapshots = SessionSnapshots()
        val idle = session("current")
        val running = idle.copy(status = "running")
        val older = snapshots.beginRead()
        val newer = snapshots.beginRead()
        snapshots.applyRead(newer, running)
        snapshots.applyRead(older, listOf(idle))
        assertEquals(running, snapshots.values.value.single())

        val beforeRemoval = snapshots.beginRead()
        snapshots.applyRead(snapshots.beginRead(), emptyList())
        snapshots.applyRead(beforeRemoval, running)
        assertTrue(snapshots.values.value.isEmpty())
    }

    private fun session(id: String) = SessionInfo(id = id, provider = "codex", title = id, cwd = "C:/work")
}
