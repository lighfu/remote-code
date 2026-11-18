package dev.remotecode.ui.desktop

import java.util.concurrent.atomic.AtomicLong

/** Payload is counted on receipt; frame timings and sampling run on the UI dispatcher. */
internal class DesktopStreamTelemetry(startMs: Long) {
    private var start = startMs
    private val bytes = AtomicLong()
    private var captures = 0
    private var decoded = 0
    private var captureMs = 0L
    private var decodeMs = 0L

    fun addBytes(count: Long) {
        bytes.addAndGet(count)
    }

    fun record(capture: Long?, decode: Long) {
        if (capture != null) {
            captures++
            captureMs += capture
        }
        decoded++
        decodeMs += decode
    }

    fun sample(now: Long, fps: Float?): DesktopOverlaySample {
        val elapsed = now - start
        val result = DesktopOverlaySample(now, fps,
            if (elapsed > 0) bytes.getAndSet(0) * 0.008f / elapsed else null,
            if (captures > 0) captureMs / captures else null,
            if (decoded > 0) decodeMs / decoded else null)
        start = now
        captures = 0
        decoded = 0
        captureMs = 0
        decodeMs = 0
        return result
    }
}

/** Compare changes in transit time. The host and phone clocks need no common epoch. */
internal class DesktopAutoBitrate(val initialKbps: Int, private val maxKbps: Int) {
    private var bestOffset: Long? = null
    private var latestOffset: Long? = null
    private var queuedSamples = 0
    private var healthySamples = 0
    private var warmup = 0

    fun newStream() {
        bestOffset = null
        latestOffset = null
        queuedSamples = 0
        healthySamples = 0
        warmup = 2
    }

    fun received(nowMs: Long, hostMs: Long?) {
        if (hostMs == null) return
        val offset = nowMs - hostMs
        val best = bestOffset
        if (best == null || offset < best) bestOffset = offset
        latestOffset = offset
    }

    /** Called once per second. Require sustained pressure before reconnecting at a new budget. */
    fun nextKbps(current: Int, sample: DesktopOverlaySample): Int {
        val offset = latestOffset
        latestOffset = null
        val mbps = sample.mbps
        if (warmup > 0) {
            warmup--
            return current
        }
        if (offset == null || sample.decodeMs == null || mbps == null || mbps <= 0f) {
            queuedSamples = 0
            healthySamples = 0
            return current
        }
        val backlogMs = offset - (bestOffset ?: offset)
        // A host clock adjustment starts a new timing baseline.
        if (backlogMs > 60_000) {
            newStream()
            return current
        }
        queuedSamples = if (backlogMs >= 400) queuedSamples + 1 else 0
        healthySamples = if (backlogMs <= 120 && mbps * 1000 >= current * .8f) healthySamples + 1 else 0
        val next = when {
            queuedSamples >= 3 -> minOf(current * 3 / 4, (mbps * 850).toInt()).coerceAtLeast(1000)
            healthySamples >= 25 -> (current * 5 / 4).coerceAtMost(maxKbps)
            else -> current
        }
        if (next != current) newStream()
        return next
    }
}
