package dev.remotecode.ui.desktop

import dev.remotecode.data.DesktopInput
import dev.remotecode.data.DesktopPoint
import kotlinx.coroutines.channels.Channel

// The Windows input API accepts at most twelve wheel notches per request.
internal const val MaxDesktopWheelTicks = 12

internal data class DesktopCommand(val input: DesktopInput, val layout: String, val capturedAt: Long, val frameAt: Long? = null, val accepted: () -> Unit)

/** Main-thread queue. Combine adjacent touchpad moves while a previous HTTP action is in flight. */
internal class DesktopInputQueue {
    private val pending = ArrayDeque<DesktopCommand>()
    private val available = Channel<Unit>(Channel.CONFLATED)
    private var closed = false
    val hasPending: Boolean get() = pending.isNotEmpty()

    fun offer(cmd: DesktopCommand): Boolean {
        if (closed) return false
        val last = pending.lastOrNull()
        if (last != null && cmd.layout == last.layout && last.input.type == cmd.input.type) {
            when (cmd.input.type) {
                "moveBy" -> {
                    val a = requireNotNull(last.input.point)
                    val b = requireNotNull(cmd.input.point)
                    val delta = DesktopPoint((a.x + b.x).coerceIn(-1.0, 1.0), (a.y + b.y).coerceIn(-1.0, 1.0))
                    pending[pending.lastIndex] = last.copy(input = last.input.copy(point = delta))
                    return true
                }
                "cursorScroll" -> {
                    val a = requireNotNull(last.input.delta)
                    val b = requireNotNull(cmd.input.delta)
                    val sum = a + b
                    if ((a > 0) == (b > 0) && sum in -MaxDesktopWheelTicks..MaxDesktopWheelTicks) {
                        pending[pending.lastIndex] = last.copy(input = last.input.copy(delta = sum),
                            accepted = { last.accepted(); cmd.accepted() })
                        return true
                    }
                }
            }
        }
        if (pending.size >= 16) return false
        pending.addLast(cmd)
        available.trySend(Unit)
        return true
    }

    suspend fun next(): DesktopCommand? {
        while (pending.isEmpty()) {
            if (available.receiveCatching().isClosed) return null
        }
        return pending.removeFirst()
    }

    fun close() {
        closed = true
        pending.clear()
        available.close()
    }
}
