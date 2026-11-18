package dev.remotecode.ui.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopFrameTimingTest {
    @Test fun expiredInputNoticeClearsOnlyAfterTheHostRevalidatesTheFrame() {
        val rejected = RejectedDesktopFrame("layout-a", 100, 3000)
        assertFalse(rejected.supersededBy("layout-a", 200, 3000))
        assertFalse(rejected.supersededBy("layout-a", 210, 2999))
        assertTrue(rejected.supersededBy("layout-a", 220, 3001))
        assertTrue(rejected.supersededBy("layout-b", 230, 2000))
        val legacy = RejectedDesktopFrame("layout", 100, null)
        assertFalse(legacy.supersededBy("layout", 100, null))
        assertTrue(legacy.supersededBy("layout", 200, null))
        assertTrue(RejectedDesktopFrame(null, 0, null).supersededBy("layout", 1, null))
    }

    @Test fun pollingRecoversStreamingWithBoundedBackoffAndSkipsUnsupportedHosts() {
        val retry = DesktopStreamRetry(true)
        var now = 100L
        assertTrue(retry.due(now))
        for (wait in listOf(1000, 2000, 4000, 8000, 16000, 30000, 30000)) {
            retry.interrupted(now)
            now += wait
            assertFalse(retry.due(now - 1))
            assertTrue(retry.due(now))
        }
        retry.connected()
        retry.interrupted(now)
        assertTrue(retry.due(now + 1000))
        retry.unsupported()
        assertFalse(retry.due(now + 2_000_000))
        assertFalse(DesktopStreamRetry(false).due(now))
    }
    @Test fun captureAndTransferTimeArePartOfTheFpsBudget() {
        assertEquals(76L, nextFrameDelay(1000, 1024, 10))
        assertEquals(0L, nextFrameDelay(1000, 1100, 10))
        assertEquals(0L, nextFrameDelay(1000, 1199, 10))
        assertEquals(34L, nextFrameDelay(1000, 1000, 30))
        assertEquals(17L, nextFrameDelay(1000, 1000, 60))
    }

    @Test fun receivedFpsCountsCompletedFramesAndFallsToZeroDuringAStall() {
        val rate = ReceivedFrameRate(0)
        for (arrival in 100L..2000L step 100) rate.record(arrival)
        assertEquals(10f, rate.fps(2000)!!, 0f)
        assertEquals(5f, rate.fps(3000)!!, 0f)
        assertEquals(0f, rate.fps(4000)!!, 0f)
        assertNull(ReceivedFrameRate(1000).fps(1500))
    }
}
