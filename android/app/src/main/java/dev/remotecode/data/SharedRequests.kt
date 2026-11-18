package dev.remotecode.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

/** 表示名や代替 URL は通信先の識別に含めない。 */
internal data class ConnectionIdentity(val url: String, val token: String, val accessId: String, val accessSecret: String)

internal fun ServerConfig.connectionIdentity() = ConnectionIdentity(url.trimEnd('/'), token, accessId, accessSecret)

/** 進行中の同じ読み取りだけ共有し、結果はキャッシュしない。 */
internal class SharedRequests<K>(private val scope: CoroutineScope) {
    private val active = ConcurrentHashMap<K, Deferred<Unit>>()

    suspend fun run(key: K, block: suspend () -> Unit) {
        val request = synchronized(active) {
            active[key]?.takeUnless { it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) { block() }.also { created ->
                active[key] = created
                created.invokeOnCompletion { active.remove(key, created) }
            }
        }
        request.start()
        request.await()
    }
}

/** WS の新しい利用状況を古い HTTP 応答で上書きしない。 */
internal fun mergeUsage(current: UsageInfo?, incoming: UsageInfo): UsageInfo {
    fun newer(old: ProviderUsage?, next: ProviderUsage?): ProviderUsage? = when {
        next == null -> old
        old != null && old.at > next.at -> old
        else -> next
    }
    return UsageInfo(
        claude = newer(current?.claude, incoming.claude),
        codex = newer(current?.codex, incoming.codex),
        antigravity = newer(current?.antigravity, incoming.antigravity),
    )
}
