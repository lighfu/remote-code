package dev.remotecode.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 全件の更新・削除が配信されるため、同じ接続では再取得しない。 */
internal class BtwSnapshots(scope: CoroutineScope) {
    private data class LoadKey(val server: ConnectionIdentity?, val sessionId: String, val generation: Long)
    private data class Stamp(val generation: Long, val lifetime: Long, val revision: Long, val reset: Long)
    private data class Loaded(val server: ConnectionIdentity?, val generation: Long)
    private data class Change(val revision: Long, val item: BtwItem?)
    private val requests = SharedRequests<LoadKey>(scope)
    private val _values = MutableStateFlow<Map<String, List<BtwItem>>>(emptyMap())
    val values: StateFlow<Map<String, List<BtwItem>>> = _values.asStateFlow()
    private var generation = 0L
    private var revision = 0L
    private var reset = 0L
    private val lifetimes = mutableMapOf<String, Long>()
    private val loaded = mutableMapOf<String, Loaded>()
    private val changes = mutableMapOf<String, MutableMap<String, Change>>()
    private val cleared = mutableMapOf<String, Long>()
    private val forgotten = mutableSetOf<String>()
    private val deletedItems = mutableMapOf<String, MutableSet<String>>()

    @Synchronized fun reconnected() { generation++ }

    @Synchronized private fun stamp(id: String) = Stamp(generation, lifetimes[id] ?: 0, revision, reset)
    @Synchronized private fun current(id: String, server: ConnectionIdentity?) = loaded[id] == Loaded(server, generation)

    suspend fun load(id: String, server: ConnectionIdentity?, connected: () -> Boolean, force: Boolean = false, fetch: suspend () -> List<BtwItem>) {
        repeat(2) {
            if (!force && connected() && current(id, server)) return
            val before = stamp(id)
            requests.run(LoadKey(server, id, before.generation)) {
                if (!force && connected() && current(id, server)) return@run
                val request = stamp(id)
                val snapshot = fetch()
                apply(id, server, request, snapshot, connected())
            }
            // 再接続と競合した取得だけ補う。
            val after = stamp(id)
            if (after.generation == before.generation || after.lifetime != before.lifetime || after.reset != before.reset) return
        }
    }

    @Synchronized private fun apply(id: String, server: ConnectionIdentity?, stamp: Stamp, snapshot: List<BtwItem>, connected: Boolean) {
        if (id in forgotten || stamp.reset != reset || stamp.generation != generation || stamp.lifetime != (lifetimes[id] ?: 0)) return
        val result = linkedMapOf<String, BtwItem>()
        if ((cleared[id] ?: 0) <= stamp.revision) for (item in snapshot) if (item.id !in deletedItems[id].orEmpty()) result[item.id] = item
        for ((itemId, change) in changes[id].orEmpty()) if (change.revision > stamp.revision) {
            if (change.item == null) result.remove(itemId)
            else result[itemId] = newerBtw(result[itemId], change.item)
        }
        _values.update { it + (id to result.values.toList()) }
        if (connected) loaded[id] = Loaded(server, generation) else loaded.remove(id)
        changes.remove(id)
        cleared.remove(id)
    }

    @Synchronized fun receive(id: String, item: BtwItem) {
        if (id in forgotten || item.id in deletedItems[id].orEmpty()) return
        val before = _values.value[id].orEmpty().firstOrNull { it.id == item.id }
        val latest = newerBtw(before, item)
        changes.getOrPut(id) { linkedMapOf() }[item.id] = Change(++revision, latest)
        _values.update { all ->
            val list = all[id].orEmpty()
            val index = list.indexOfFirst { it.id == item.id }
            all + (id to if (index < 0) list + latest else list.toMutableList().also { it[index] = latest })
        }
    }

    @Synchronized fun remove(id: String, itemId: String?) {
        if (id in forgotten) return
        revision++
        if (itemId == null) {
            deletedItems.getOrPut(id) { mutableSetOf() }.addAll(_values.value[id].orEmpty().map { it.id })
            cleared[id] = revision
            changes.remove(id)
        } else {
            deletedItems.getOrPut(id) { mutableSetOf() }.add(itemId)
            changes.getOrPut(id) { linkedMapOf() }[itemId] = Change(revision, null)
        }
        _values.update { all -> all + (id to if (itemId == null) emptyList() else all[id].orEmpty().filter { it.id != itemId }) }
    }

    @Synchronized fun forget(id: String) {
        forgotten.add(id)
        lifetimes[id] = (lifetimes[id] ?: 0) + 1
        loaded.remove(id)
        changes.remove(id)
        cleared.remove(id)
        deletedItems.remove(id)
        _values.update { it - id }
    }

    @Synchronized fun clear() {
        generation++; reset++
        loaded.clear(); changes.clear(); cleared.clear(); lifetimes.clear(); forgotten.clear(); deletedItems.clear()
        _values.value = emptyMap()
    }
}

/** 遅れた pending で回答済みの状態を戻さない。 */
internal fun newerBtw(previous: BtwItem?, incoming: BtwItem): BtwItem {
    if (previous == null) return incoming
    if (previous.status != "pending" && incoming.status == "pending") return previous
    return if ((previous.doneAt ?: previous.at) > (incoming.doneAt ?: incoming.at)) previous else incoming
}
