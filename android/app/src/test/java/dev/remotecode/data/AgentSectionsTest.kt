package dev.remotecode.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSectionsTest {
    @Test fun runningAndWaitingStatesStayActive() {
        listOf("running", "waiting", "pending", "queued", "initializing", "awaiting_input", "IN-PROGRESS").forEach {
            assertTrue(it, isActiveAgent(it))
        }
        assertTrue(isActiveAgent(null, streaming = true))
    }

    @Test fun terminalStatusWinsOverStaleStreamingAndUnknownLegacyStatesStayFinished() {
        listOf("done", "completed", "error", "failed", "stopped", "declined", "cancelled", "interrupted").forEach {
            assertFalse(it, isActiveAgent(it, streaming = true))
        }
        assertFalse(isActiveAgent(null))
        assertFalse(isActiveAgent("unknown"))
    }

    @Test fun groupingKeepsTheExistingNewestFirstOrderWithinEachSection() {
        fun agent(id: String, status: String) = Entry(id = id, kind = "tool", tool = "Agent", status = status)
        val groups = agentSections(listOf(agent("done-old", "done"), agent("active-old", "running"), agent("done-new", "failed"), agent("active-new", "waiting")))
        assertEquals(listOf("active-new", "active-old"), groups.active.map { it.id })
        assertEquals(listOf("done-new", "done-old"), groups.completed.map { it.id })
    }
}
