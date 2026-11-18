package dev.remotecode.data

import java.util.concurrent.ConcurrentHashMap

/** プロバイダー・接続先別に検証。手動更新は制限しない。 */
internal class ModelRefresh(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Key(val server: ConnectionIdentity?, val provider: String)
    private val fetchedAt = ConcurrentHashMap<Key, Long>()
    fun needed(server: ConnectionIdentity?, provider: String, force: Boolean = false): Boolean {
        if (force) return true
        val previous = fetchedAt[Key(server, provider)] ?: return true
        return now() - previous >= 10 * 60_000
    }
    fun fetched(server: ConnectionIdentity?, provider: String) { fetchedAt[Key(server, provider)] = now() }
    fun clear() { fetchedAt.clear() }
}
