package dev.remotecode.ui.desktop

import dev.remotecode.data.DesktopInput
import dev.remotecode.data.DesktopPoint
import org.junit.Assert.*
import org.junit.Test

class DesktopTouchGesturesTest {
    private val inputs = mutableListOf<DesktopInput>()
    private val transforms = mutableListOf<Float?>()
    private val previews = mutableListOf<DesktopPoint?>()
    private val gestures = DesktopTouchGestures(4f, 40f, 300, 500,
        viewport = { DesktopViewport(0f, 0f, 200f, 100f) },
        emit = { inputs.add(it) }, transform = { _, _, scale -> transforms.add(scale) }, previewDrag = { previews.add(it) })
    private fun finger(x: Int, y: Int, id: Int = 0) = DesktopFinger(id, x.toFloat(), y.toFloat())

    @Test fun fingerMovesCursorBeforeLiftingAndCannotProduceAClick() {
        gestures.down(finger(50, 50), 0)
        gestures.move(finger(52, 50))
        assertTrue(inputs.isEmpty())
        gestures.move(finger(75, 50))
        assertEquals(listOf(DesktopInput("moveBy", DesktopPoint(0.125, 0.0))), inputs)
        gestures.move(finger(75, 25))
        gestures.up(finger(75, 25), 100)
        gestures.advance(800)
        assertEquals(listOf("moveBy", "moveBy"), inputs.map { it.type })
        assertEquals(DesktopPoint(0.0, -0.25), inputs.last().point)
    }

    @Test fun oneAndTwoFingerTapsClickAtTheCurrentCursor() {
        val a = finger(11, 12)
        val b = finger(31, 14, 7)
        gestures.down(a, 10)
        gestures.up(a, 50)
        gestures.advance(351)
        gestures.down(a, 400)
        gestures.secondDown(a, b, 420)
        gestures.move(b, a) // pointer index order can change
        gestures.move(finger(8, 12), finger(34, 14, 7)) // Both contacts remain within tap slop.
        gestures.up(b, 510)
        gestures.advance(850)
        assertEquals(listOf(DesktopInput("cursorClick"), DesktopInput("cursorRightClick")), inputs)
        assertTrue(transforms.isEmpty())
    }

    @Test fun directTouchUsesTheLocationOfTheFirstFingerForTwoFingerTap() {
        gestures.touchpad = false
        val a = finger(50, 25)
        val b = finger(105, 35, 1)
        gestures.down(a, 0)
        gestures.secondDown(a, b, 15)
        gestures.up(b, 60)
        assertEquals(listOf(DesktopInput("rightClick", DesktopPoint(0.25, 0.25))), inputs)
    }

    @Test fun pinchesPansAndThirdFingersNeverBecomeClicks() {
        val a = finger(10, 10)
        val b = finger(30, 30, 1)
        gestures.down(a, 0)
        gestures.secondDown(a, b, 10)
        gestures.move(finger(0, 0), finger(40, 40, 1))
        gestures.move(a, b)
        gestures.up(a, 150)
        assertEquals(2, transforms.size)
        gestures.down(a, 500)
        gestures.secondDown(a, b, 505)
        gestures.move(finger(20, 20), finger(40, 40, 1))
        gestures.up(b, 550)
        assertEquals(3, transforms.size)
        gestures.down(a, 600)
        gestures.secondDown(a, b, 620)
        gestures.cancel() // third pointer or Android ACTION_CANCEL
        gestures.up(a, 650)
        gestures.advance(1500)
        assertTrue(inputs.isEmpty())
    }

    @Test fun driftingRemainingFingerAndSlowTwoFingerHoldsAreNotRightClicks() {
        val a = finger(20, 25)
        val b = finger(55, 20, 1)
        gestures.down(a, 0)
        gestures.secondDown(a, b, 10)
        gestures.move(b, a)
        gestures.move(finger(75, 20, 1))
        gestures.up(b, 100)
        gestures.down(a, 400)
        gestures.secondDown(a, b, 410)
        gestures.up(b, 800)
        assertTrue(inputs.isEmpty())
    }

    @Test fun parallelPanKeepsScaleAndDeliberatePinchIncludesTheInitialSpanChange() {
        val one = finger(10, 20)
        val two = finger(90, 20, 1)
        gestures.down(one, 0)
        gestures.secondDown(one, two, 10)
        gestures.move(finger(22, 20), finger(104, 20, 1))
        gestures.move(finger(25, 20), finger(109, 20, 1))
        assertEquals(listOf(null, null), transforms)
        gestures.move(finger(10, 20), finger(110, 20, 1))
        gestures.move(finger(20, 20), finger(100, 20, 1))
        assertEquals(100f / 80f, transforms[2]!!, 0.0001f)
        assertEquals(80f / 100f, transforms[3]!!, 0.0001f)
        gestures.up(one, 200)
        assertTrue(inputs.isEmpty())
    }

    @Test fun twoFingerVerticalDragAccumulatesWheelTicksAndLocksOutPinchAndRightClick() {
        val a = finger(10, 20)
        val b = finger(80, 20, 1)
        gestures.down(a, 0)
        gestures.secondDown(a, b, 10)
        gestures.move(finger(11, 28), finger(82, 26, 1))
        assertTrue(inputs.isEmpty())
        gestures.move(finger(12, 34), finger(82, 32, 1))
        gestures.move(finger(12, 59), finger(82, 57, 1))
        // Still scrolling even if the fingers spread apart near the end.
        gestures.move(finger(0, 19), finger(130, 17, 1))
        gestures.up(a, 210)
        gestures.advance(600)
        assertEquals(listOf(1, 2, -3), inputs.map { it.delta })
        assertTrue(inputs.all { it.type == "cursorScroll" })
        assertTrue(transforms.isEmpty())
    }

    @Test fun pinchNeverEmitsWheelAndDirectModeCanPanVertically() {
        val a = finger(25, 20)
        val b = finger(75, 20, 1)
        gestures.down(a, 0)
        gestures.secondDown(a, b, 10)
        gestures.move(finger(15, 25), finger(85, 28, 1))
        gestures.move(finger(15, 75), finger(85, 78, 1))
        gestures.up(b, 150)
        assertTrue(transforms.all { it != null })
        gestures.touchpad = false
        gestures.down(a, 500)
        gestures.secondDown(a, b, 505)
        gestures.move(finger(25, 70), finger(75, 70, 1))
        gestures.up(b, 550)
        assertEquals(3, transforms.size)
        assertNull(transforms.last())
        assertTrue(inputs.isEmpty())
    }

    @Test fun fastWheelSwipeSplitsIntoValidWindowsInputBatches() {
        val a = finger(10, 20)
        val b = finger(60, 20, 1)
        gestures.down(a, 0)
        gestures.secondDown(a, b, 5)
        gestures.move(finger(10, 332), finger(60, 332, 1))
        assertEquals(listOf(12, 12, 2), inputs.map { it.delta })
    }

    @Test fun doubleTapAndDragAreMutuallyExclusiveAndCancellationDropsPendingInput() {
        val start = finger(30, 30)
        gestures.down(start, 0)
        gestures.up(start, 50)
        gestures.down(start, 100)
        gestures.up(start, 120)
        assertEquals(listOf(DesktopInput("cursorDoubleClick")), inputs)
        gestures.down(start, 500)
        gestures.up(start, 510)
        gestures.down(start, 560)
        gestures.move(finger(55, 55))
        assertEquals(DesktopPoint(0.125, 0.25), previews.last())
        assertEquals(1, inputs.size)
        gestures.up(finger(80, 80), 1100)
        assertNull(previews.last())
        assertEquals(DesktopInput("cursorDragBy", DesktopPoint(0.25, 0.5)), inputs.last())
        gestures.down(start, 1200)
        gestures.up(start, 1240)
        gestures.cancel()
        gestures.advance(1800)
        assertEquals(2, inputs.size)
    }

    @Test fun directTouchKeepsDragAndLongPress() {
        val start = finger(20, 20)
        gestures.touchpad = false
        gestures.down(start, 0)
        gestures.advance(500)
        gestures.up(start, 700)
        gestures.down(start, 1000)
        gestures.move(finger(90, 55))
        gestures.up(finger(110, 75), 1400)
        assertEquals(listOf("rightClick", "drag"), inputs.map { it.type })
        assertEquals(DesktopPoint(0.55f.toDouble(), 0.75), inputs.last().end)
    }
}
