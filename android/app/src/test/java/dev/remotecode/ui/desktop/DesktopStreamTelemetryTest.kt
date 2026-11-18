package dev.remotecode.ui.desktop

import org.junit.Assert.*
import org.junit.Test

class DesktopStreamTelemetryTest {
    @Test fun MbpsCountsEveryReceivedPayloadIndependentOfRenderedFrames() {
        val meter = DesktopStreamTelemetry(1000)
        meter.addBytes(600_000)
        meter.addBytes(400_000)
        meter.record(22, 12)
        meter.record(null, 4)
        val sample = meter.sample(3000, 1f)
        assertEquals(4f, sample.mbps!!, .001f)
        assertEquals(22L, sample.captureMs!!)
        assertEquals(8L, sample.decodeMs!!)
        val idle = meter.sample(4000, 0f)
        assertEquals(0f, idle.mbps!!, 0f)
        assertNull(idle.captureMs)
        assertNull(idle.decodeMs)
    }

    @Test fun bandwidthOnlyDecreasesAfterSustainedGrowthInTransitTime() {
        val controller = DesktopAutoBitrate(32_000, 64_000)
        controller.newStream()
        fun tick(n: Int, extraDelay: Int): Int {
            val received = n * 1000L
            controller.received(received, 1_700_000_000_000L + received - extraDelay)
            return controller.nextKbps(32_000, DesktopOverlaySample(received, 15f, 10f, 25L, 15L))
        }
        assertEquals(32_000, tick(1, 100))
        assertEquals(32_000, tick(2, 100))
        assertEquals(32_000, tick(3, 800))
        assertEquals(32_000, tick(4, 100))
        assertEquals(32_000, tick(5, 800))
        assertEquals(32_000, tick(6, 800))
        assertEquals(8500, tick(7, 800))
    }

    @Test fun idleDesktopDoesNotTriggerAReconnectAndBusyHealthyLinkRecoversGradually() {
        val controller = DesktopAutoBitrate(32_000, 64_000)
        controller.newStream()
        fun tick(n: Int, busy: Boolean): Int {
            controller.received(n * 1000L, n * 1000L + 6000)
            return controller.nextKbps(8000, DesktopOverlaySample(n * 1000L, if (busy) 20f else 0f,
                if (busy) 7.8f else 0f, null, if (busy) 5L else null))
        }
        for (n in 1..40) assertEquals(8000, tick(n, false))
        for (n in 41..64) assertEquals(8000, tick(n, true))
        assertEquals(10_000, tick(65, true))
    }

    @Test fun eventLogKeepsBoundedRecentHistoryAndCountsRepeatedEvents() {
        val log = DesktopEventLog()
        for (event in 1..34) log.add("event $event", event.toLong())
        log.add("event 34", 35)
        assertEquals(32, log.entries.size)
        assertEquals("event 3", log.entries.first().message)
        assertEquals(DesktopLogEntry(35, "event 34", 2), log.entries.last())
    }
}
