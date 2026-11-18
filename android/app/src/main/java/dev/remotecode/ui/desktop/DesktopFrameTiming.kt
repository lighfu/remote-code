package dev.remotecode.ui.desktop

internal data class RejectedDesktopFrame(val layout: String?, val receivedAt: Long, val frameAt: Long?) {
    fun supersededBy(nextLayout: String, nextReceivedAt: Long, nextFrameAt: Long?): Boolean {
        if (layout != nextLayout) return true
        return if (frameAt != null && nextFrameAt != null) nextFrameAt > frameAt else nextReceivedAt > receivedAt
    }
}

/** Poll single frames until another stream attempt is due. */
internal class DesktopStreamRetry(supported: Boolean) {
    private var nextAt = if (supported) 0L else Long.MAX_VALUE
    private var backoffMs = 1000L

    fun due(now: Long) = now >= nextAt
    fun waitSeconds(now: Long) = ((nextAt - now).coerceAtLeast(0) + 999L) / 1000L
    fun connected() { backoffMs = 1000L }
    fun unsupported() { nextAt = Long.MAX_VALUE }
    fun interrupted(now: Long) {
        nextAt = now + backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
    }
}

/** Request-to-request pacing, including the capture, transfer and decode time. */
internal fun nextFrameDelay(startMs: Long, nowMs: Long, fps: Int): Long {
    val period = (1000L + fps - 1) / fps
    return (period - (nowMs - startMs)).coerceAtLeast(0)
}

/** A two-second rolling count of decoded frames. Both methods run on the UI dispatcher. */
internal class ReceivedFrameRate(private val startMs: Long) {
    private val arrivals = ArrayDeque<Long>()

    fun record(nowMs: Long) {
        prune(nowMs)
        arrivals.addLast(nowMs)
    }

    fun fps(nowMs: Long): Float? {
        prune(nowMs)
        val window = (nowMs - startMs).coerceAtMost(2000L)
        return if (window < 1000L) null else arrivals.size * 1000f / window
    }

    private fun prune(nowMs: Long) {
        while (arrivals.isNotEmpty() && arrivals.first() <= nowMs - 2000L) arrivals.removeFirst()
    }
}
