package dev.remotecode.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive

/** null は停止。対象変更時は前の待機を中断する。 */
internal suspend fun <T : Any> Flow<T?>.pollForegroundSession(intervalMs: Long, read: suspend (T) -> Unit) {
    require(intervalMs > 0)
    distinctUntilChanged().collectLatest { target ->
        if (target == null) return@collectLatest
        while (currentCoroutineContext().isActive) {
            try { read(target) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 失敗時は値を保持し、次周期で再試行する。 */ }
            delay(intervalMs)
        }
    }
}
