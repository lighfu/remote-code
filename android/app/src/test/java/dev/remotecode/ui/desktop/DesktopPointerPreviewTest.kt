package dev.remotecode.ui.desktop

import dev.remotecode.data.DesktopCursor
import dev.remotecode.data.DesktopDisplay
import dev.remotecode.data.DesktopInput
import dev.remotecode.data.DesktopPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopPointerPreviewTest {
    private val first = DesktopDisplay("one", "One", -100, -100, 101, 101)
    private val second = DesktopDisplay("two", "Two", 1, 1, 100, 100)
    private val preview = DesktopPointerPreview().apply { setDisplays("one", listOf(first, second)) }
    private fun point(x: Double, y: Double = 0.5) = DesktopPoint(x, y)
    private fun move(dx: Double, dy: Double = 0.0) = DesktopInput("moveBy", point(dx, dy))
    private fun sample(x: Double, revision: Long) = preview.sample(DesktopCursor(point(x), revision = revision))

    @Test fun pointerMovesImmediatelyAndOlderCapturedFramesCannotSnapItBack() {
        sample(0.3, 3)
        preview.queued(move(0.2))
        assertEquals(point(0.5), preview.position)
        sample(0.3, 3)
        preview.settle(4, false)
        assertEquals(point(0.5), preview.position)
        sample(0.52, 4)
        assertEquals(point(0.52), preview.position)
        sample(0.3, 3)
        assertEquals(point(0.52), preview.position)
    }

    @Test fun queuedMotionSurvivesIntermediateAcknowledgementsAndFramesThatArriveBeforeTheReply() {
        sample(0.2, 2)
        preview.queued(move(0.2))
        preview.queued(move(0.1))
        preview.settle(3, true)
        sample(0.4, 3)
        assertEquals(point(0.5), preview.position)
        sample(0.48, 4)
        assertEquals(point(0.5), preview.position)
        preview.settle(4, false)
        assertEquals(point(0.48), preview.position)
    }

    @Test fun tinyDeltasAccumulateAsPhysicalPixelsEvenAcrossAcknowledgements() {
        sample(0.5, 1)
        assertNull(preview.quantize(move(0.002)))
        assertNull(preview.quantize(move(0.002)))
        val right = preview.quantize(move(0.002))!!
        assertEquals(point(0.01, 0.0), right.point)
        preview.queued(right)
        assertEquals(point(0.51), preview.position)
        preview.settle(2, false)
        sample(0.51, 2)
        val left = preview.quantize(move(-0.002))!!
        assertEquals(-0.01, left.point!!.x, 0.0)
        preview.queued(left)
        assertEquals(point(0.5), preview.position)
    }

    @Test fun virtualDesktopGapsUseTheClosestMonitorAndASelectionChangeDropsTheOldPosition() {
        preview.setDisplays("all", listOf(first, second))
        preview.sample(DesktopCursor(point(0.45, 0.25), revision = 1))
        preview.queued(move(0.1)) // (10,-50) lies in the empty top-right quadrant.
        assertEquals(point(0.5, 0.25), preview.position)
        preview.setDisplays("two", listOf(first, second))
        assertNull(preview.position)
        sample(0.9, 2)
        preview.queued(move(0.5))
        assertEquals(1.0, preview.position!!.x, 0.0)
    }

    @Test fun directGesturesUseTheirEndpointAndAFailedInputRestoresTheLastWindowsCursor() {
        sample(0.25, 3)
        preview.queued(DesktopInput("drag", point(0.2), point(0.75)))
        assertEquals(point(0.75), preview.position)
        preview.settle(null, false)
        assertEquals(point(0.25), preview.position)
        preview.queued(DesktopInput("click", point(0.8)))
        assertEquals(point(0.8), preview.position)
        preview.reset()
        assertEquals(point(0.25), preview.position)
    }

    @Test fun embeddedCursorShowsLocalFeedbackOnlyUntilThePcShowsTheInput() {
        preview.sample(DesktopCursor(point(0.4), embedded = true, revision = 5))
        assertNull(preview.position)
        assertEquals(point(0.4), preview.location)
        assertEquals(point(0.7), preview.relativePosition(point(0.3, 0.0)))
        assertEquals(point(0.4), preview.location) // A drag preview has not moved the PC pointer.
        preview.queued(move(0.2))
        assertEquals(point(0.6), preview.position)
        preview.settle(6, false)
        preview.sample(DesktopCursor(point(0.6), embedded = true, revision = 6))
        assertNull(preview.position)
        assertEquals(point(0.6), preview.location)
    }

    @Test fun hiddenOrLegacyFramesDoNotInventAPointer() {
        sample(0.5, 1)
        preview.queued(move(0.1))
        preview.sample(DesktopCursor(revision = 1))
        preview.queued(move(0.1))
        assertNull(preview.position)
        preview.sample(DesktopCursor(embedded = true))
        preview.queued(DesktopInput("click", point(0.25)))
        assertNull(preview.position)
        assertNull(preview.location)
    }
}
