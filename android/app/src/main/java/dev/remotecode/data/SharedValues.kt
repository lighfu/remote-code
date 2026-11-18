package dev.remotecode.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

/** 同じ読み取りの通信・デコード結果を共有する。 */
internal class SharedValues<K, V>(private val scope: CoroutineScope) {
    private val active = ConcurrentHashMap<K, Deferred<V>>()

    suspend fun get(key: K, load: suspend () -> V): V {
        val transfer = synchronized(active) {
            active[key]?.takeUnless { it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) { load() }.also { created ->
                active[key] = created
                created.invokeOnCompletion { active.remove(key, created) }
            }
        }
        transfer.start()
        return transfer.await()
    }
}
