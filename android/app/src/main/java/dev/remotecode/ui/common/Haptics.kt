package dev.remotecode.ui.common

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback

/** 個別振動の直後は全体振動を抑え、二重発火を防ぐ。 */
private object HapticGate {
    @Volatile var last = 0L
}

class Haptics(private val h: HapticFeedback) {
    private fun perform(t: HapticFeedbackType) {
        HapticGate.last = SystemClock.uptimeMillis()
        h.performHapticFeedback(t)
    }

    fun confirm() = perform(HapticFeedbackType.Confirm)

    fun reject() = perform(HapticFeedbackType.Reject)

    fun tick() = perform(HapticFeedbackType.SegmentTick)

    fun fineTick() = perform(HapticFeedbackType.SegmentFrequentTick)

    fun toggle(on: Boolean) = perform(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    fun context() = perform(HapticFeedbackType.ContextClick)

    fun edge() = perform(HapticFeedbackType.GestureEnd)

    fun confirm(block: () -> Unit): () -> Unit = { confirm(); block() }
    fun reject(block: () -> Unit): () -> Unit = { reject(); block() }
    fun tick(block: () -> Unit): () -> Unit = { tick(); block() }
    fun context(block: () -> Unit): () -> Unit = { context(); block() }
}

@Composable
fun rememberHaptics(): Haptics {
    val h = LocalHapticFeedback.current
    return remember(h) { Haptics(h) }
}

/** 子が処理したタップだけ振動し、ドラッグでは振動しない。 */
fun Modifier.hapticTaps(): Modifier = composed {
    val h = LocalHapticFeedback.current
    pointerInput(h) { detectHandledTaps { h.performHapticFeedback(HapticFeedbackType.VirtualKey) } }
}

fun hapticArea(): Modifier = Modifier.hapticTaps()

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectHandledTaps(onTap: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        var moved = false
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Final)
            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
            if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
            if (!c.pressed) {
                val now = SystemClock.uptimeMillis()
                if (!moved && c.isConsumed && now - HapticGate.last > 120 && c.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                    onTap()
                }
                break
            }
        }
    }
}
