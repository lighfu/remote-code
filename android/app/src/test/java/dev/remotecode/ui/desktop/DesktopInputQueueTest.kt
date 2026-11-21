package dev.remotecode.ui.desktop

import dev.remotecode.data.DesktopInput
import dev.remotecode.data.DesktopPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DesktopInputQueueTest {
    @Test fun hotplugRefreshesMonitorBoundsOnceAndUnderstandsLegacyDisplayLists() {
        assertFalse(layoutNeedsMonitorRefresh(null, "A", "A"))
        assertTrue(layoutNeedsMonitorRefresh(null, "B", "A"))
        assertTrue(layoutNeedsMonitorRefresh("A", "B", "A"))
        assertFalse(layoutNeedsMonitorRefresh("B", "B", "A"))
        assertFalse(layoutNeedsMonitorRefresh("A", "B", "B"))
        assertFalse(layoutNeedsMonitorRefresh(null, "A", null))
        assertTrue(layoutNeedsMonitorRefresh("A", "B", null))
    }

    @Test fun adjacentMotionAccumulatesAndClicksRetainTheirOrder() = runBlocking {
        val queue = DesktopInputQueue()
        fun move(x: Double) = DesktopCommand(DesktopInput("moveBy", DesktopPoint(x, 0.0)), "layout", 100) {}
        queue.offer(move(0.125))
        queue.offer(move(0.25))
        queue.offer(DesktopCommand(DesktopInput("cursorRightClick"), "layout", 100) {})
        queue.offer(move(-0.5))
        assertEquals(0.375, queue.next()!!.input.point!!.x, 0.0)
        assertEquals("cursorRightClick", queue.next()!!.input.type)
        assertEquals(-0.5, queue.next()!!.input.point!!.x, 0.0)
        queue.offer(move(0.25))
        queue.close()
        assertNull(queue.next())
        assertFalse(queue.offer(move(0.25)))
    }

    @Test fun wheelTicksBatchWithoutCrossingDirectionClicksOrLayouts() = runBlocking {
        val queue = DesktopInputQueue()
        var accepted = 0
        fun wheel(ticks: Int, layout: String = "A") =
            DesktopCommand(DesktopInput("cursorScroll", delta = ticks), layout, 100) { accepted++ }
        queue.offer(wheel(5))
        queue.offer(wheel(7))
        queue.offer(wheel(2)) // A separate request once twelve notches are pending.
        queue.offer(wheel(-3))
        queue.offer(DesktopCommand(DesktopInput("cursorClick"), "A", 100) {})
        queue.offer(wheel(-4))
        queue.offer(wheel(-3, "B"))
        val commands = List(6) { queue.next()!! }
        assertEquals(listOf(12, 2, -3, null, -4, -3), commands.map { it.input.delta })
        assertEquals("cursorClick", commands[3].input.type)
        commands.forEach { it.accepted() }
        assertEquals(6, accepted)
        queue.close()
    }

    @Test fun dragMovesBatchWithinOneGestureAndPreserveReversals() = runBlocking {
        val queue = DesktopInputQueue()
        fun offer(type: String, id: String, x: Double? = null) = queue.offer(DesktopCommand(
            DesktopInput(type, point = x?.let { DesktopPoint(it, 0.0) }, dragId = id), "A", 100,
        ) {})
        offer("cursorDragStart", "first")
        offer("cursorDragMove", "first", 0.25)
        offer("cursorDragMove", "first", 0.125)
        offer("cursorDragMove", "first", -0.25)
        offer("cursorDragEnd", "first")
        offer("cursorDragStart", "second")
        assertEquals("cursorDragStart", queue.next()!!.input.type)
        assertEquals(0.375, queue.next()!!.input.point!!.x, 0.0)
        assertEquals(-0.25, queue.next()!!.input.point!!.x, 0.0)
        assertEquals("cursorDragEnd", queue.next()!!.input.type)
        assertEquals("second", queue.next()!!.input.dragId)
        queue.close()
    }

    @Test fun fullQueueHasRoomToReleaseAHeldButton() {
        val queue = DesktopInputQueue()
        repeat(16) { assertTrue(queue.offer(DesktopCommand(DesktopInput("cursorClick"), "A", 100) {})) }
        assertFalse(queue.offer(DesktopCommand(DesktopInput("cursorClick"), "A", 100) {}))
        assertTrue(queue.offer(DesktopCommand(DesktopInput("cursorDragEnd", dragId = "held"), "A", 100) {}))
        queue.close()
    }
}
