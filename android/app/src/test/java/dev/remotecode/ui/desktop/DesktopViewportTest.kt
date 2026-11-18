package dev.remotecode.ui.desktop

import dev.remotecode.data.DesktopDisplay
import dev.remotecode.data.DesktopPoint
import dev.remotecode.data.defaultScrollPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopViewportTest {
    @Test fun mapsTouchesThroughTheImageRectangleOnly() {
        val fit = DesktopViewport.fit(300, 800, 1600, 800)
        assertEquals(DesktopViewport(0f, 325f, 300f, 150f), fit)
        assertNull(fit.point(150f, 100f))
        assertNull(fit.point(150f, 476f))
        assertEquals(DesktopPoint(0.25, 0.5), fit.point(75f, 400f))
        assertEquals(DesktopPoint(1.0, 1.0), fit.point(300f, 475f))
    }

    @Test fun twoFingerTranslationMovesTheSameContentEvenWithUnchangedSpan() {
        val before = DesktopViewport(-100f, -100f, 800f, 600f)
        val initial = FingerPair.of(50f, 50f, 150f, 50f)
        val moved = FingerPair.of(73f, 68f, 173f, 68f)
        val after = before.transform(initial, moved, moved.span / initial.span, null, 400, 300)
        assertEquals(DesktopViewport(-77f, -82f, 800f, 600f), after)
        assertEquals(before.point(initial.centerX, initial.centerY), after.point(moved.centerX, moved.centerY))
    }

    @Test fun touchpadFollowsThePointerUntilEachAxisReachesTheDesktopEdge() {
        val zoomed = DesktopViewport(-50f, -120f, 1000f, 800f)
        val centered = zoomed.centeredOn(DesktopPoint(0.6, 0.5), 400, 300)
        assertEquals(DesktopViewport(-400f, -250f, 1000f, 800f), centered)
        assertEquals(0.6, centered.point(200f, 150f)!!.x, 0.000001)
        assertEquals(0.5, centered.point(200f, 150f)!!.y, 0.000001)
        val right = zoomed.centeredOn(DesktopPoint(0.95, 0.5), 400, 300)
        assertEquals(-600f, right.left, 0f)
        assertEquals(right, zoomed.centeredOn(DesktopPoint(1.0, 0.5), 400, 300))
        assertEquals(DesktopPoint(1.0, 0.5), right.point(400f, 150f))
        val topLeft = zoomed.centeredOn(DesktopPoint(0.05, 0.05), 400, 300)
        assertEquals(0f, topLeft.left, 0f)
        assertEquals(0f, topLeft.top, 0f)
        val bottom = zoomed.centeredOn(DesktopPoint(0.5, 0.99), 400, 300)
        assertEquals(-500f, bottom.top, 0f)
    }

    @Test fun fittedAndLetterboxedAxesRemainCenteredWhenThePointerMoves() {
        val fit = DesktopViewport.fit(360, 800, 5760, 2160)
        assertEquals(fit, fit.centeredOn(DesktopPoint(1.0, 0.0), 360, 800))
        val wide = DesktopViewport(0f, 0f, 1440f, 540f)
        assertEquals(DesktopViewport(-1080f, 130f, 1440f, 540f),
            wide.centeredOn(DesktopPoint(1.0, 1.0), 360, 800))
    }

    @Test fun zoomCentersOnTheCursorEvenWithDistantFingersOrAfterManualPan() {
        val before = DesktopViewport(-20f, -40f, 800f, 600f)
        val cursor = DesktopPoint(0.75, 0.625)
        val fingers = FingerPair(40f, 30f, 50f)
        val moved = FingerPair(95f, 90f, 100f)
        val bigger = before.transform(fingers, moved, 2f, cursor, 400, 300)
        assertEquals(cursor, bigger.point(200f, 150f))
        val smaller = bigger.transform(moved, fingers, 0.5f, cursor, 400, 300)
        assertEquals(cursor, smaller.point(200f, 150f))
        val fit = smaller.transform(fingers, fingers, 0.5f, cursor, 400, 300)
        assertEquals(DesktopViewport(0f, 0f, 400f, 300f), fit)
    }

    @Test fun zoomNearTheCornerStopsAtTheScreenEdgeAndPreservesLetterboxing() {
        val fit = DesktopViewport.fit(360, 800, 5760, 2160)
        val center = FingerPair(180f, 400f, 1f)
        val cursor = DesktopPoint(1.0, 1.0)
        val bigger = fit.transform(center, center, 2f, cursor, 360, 800)
        assertEquals(DesktopViewport(-360f, 265f, 720f, 270f), bigger)
        assertEquals(cursor, bigger.point(360f, 535f))
    }

    @Test fun pinchWithoutACursorKeepsTheContentBetweenTheMovingFingers() {
        val before = DesktopViewport(0f, 25f, 400f, 200f)
        val initial = FingerPair.of(100f, 125f, 200f, 125f)
        val moved = FingerPair.of(130f, 130f, 330f, 130f)
        val after = before.transform(initial, moved, moved.span / initial.span, null, 400, 300)
        assertEquals(before.point(initial.centerX, initial.centerY), after.point(moved.centerX, moved.centerY))
    }

    @Test fun scrollAnchorInsidePrimaryWhenVirtualCenterIsInMonitorGap() {
        val monitors = listOf(
            DesktopDisplay("left", "Left", -1200, -1000, 900, 500),
            DesktopDisplay("primary", "Primary", 0, 0, 1000, 1000, primary = true),
        )
        val p = defaultScrollPoint("all", monitors)
        assertEquals(500.0, p.x * 2200 - 1200, 0.01)
        assertEquals(500.0, p.y * 2000 - 1000, 0.01)
        assertEquals(DesktopPoint(0.5, 0.5), defaultScrollPoint("left", monitors))
    }
}
