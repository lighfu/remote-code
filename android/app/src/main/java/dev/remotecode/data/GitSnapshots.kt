package dev.remotecode.data

import dev.remotecode.R
import dev.remotecode.i18n.appString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 変更後の状態を古い読み取りで上書きしない。 */
internal class GitSnapshots(
    scope: CoroutineScope,
    private val failureMessage: () -> String = { appString(R.string.core_git_status_error) },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Key(val server: ConnectionIdentity, val id: String, val cwd: String, val reset: Long, val revision: Long)
    private data class Loaded(val key: Key, val at: Long)
    private val requests = SharedRequests<Key>(scope)
    private val _values = MutableStateFlow<Map<String, GitStatus>>(emptyMap())
    val values: StateFlow<Map<String, GitStatus>> = _values.asStateFlow()
    private var reset = 0L
    private val revisions = mutableMapOf<String, Long>()
    private val loaded = mutableMapOf<String, Loaded>()

    @Synchronized private fun key(id: String, server: ConnectionIdentity, cwd: String) = Key(server, id, cwd, reset, revisions[id] ?: 0)
    @Synchronized private fun current(key: Key) = key.reset == reset && key.revision == (revisions[key.id] ?: 0)
    @Synchronized private fun fresh(key: Key): Boolean {
        val stamp = loaded[key.id] ?: return false
        val status = _values.value[key.id] ?: return false
        val ttl = if (status.error != null) 30_000 else if (status.isRepo) 8_000 else 60_000
        return stamp.key == key && now() - stamp.at < ttl
    }

    suspend fun load(id: String, server: ConnectionIdentity, cwd: String, force: Boolean, valid: () -> Boolean, fetch: suspend () -> GitStatus) {
        val key = key(id, server, cwd)
        if (!force && fresh(key)) return
        requests.run(key) {
            if (!current(key) || !valid() || (!force && fresh(key))) return@run
            val status = try { fetch() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val previous = synchronized(this) { _values.value[id]?.takeIf { loaded[id]?.key?.let { it.server == server && it.cwd == cwd } == true } }
                (previous ?: GitStatus()).copy(error = error.message ?: failureMessage())
            }
            synchronized(this) {
                if (current(key) && valid()) {
                    _values.update { it + (id to status) }
                    loaded[id] = Loaded(key, now())
                }
            }
        }
    }

    @Synchronized fun invalidate(id: String) { revisions[id] = (revisions[id] ?: 0) + 1 }
    @Synchronized fun forget(id: String) { invalidate(id); loaded.remove(id); _values.update { it - id } }
    @Synchronized fun reconnected() { reset++; loaded.clear() }
    @Synchronized fun clear() { reset++; revisions.clear(); loaded.clear(); _values.value = emptyMap() }
}
