package dev.remotecode.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow

/** null は停止。対象変更時は前の待機を中断する。 */
internal suspend fun <T : Any> Flow<T?>.pollForegroundSession(
    intervalMs: Long,
    changes: (T) -> Flow<Unit> = { emptyFlow() },
    read: suspend (T) -> Unit,
) {
    require(intervalMs > 0)
    distinctUntilChanged().collectLatest { target ->
        if (target != null) changes(target).refreshOnChanges({ intervalMs }) { read(target) }
    }
}
