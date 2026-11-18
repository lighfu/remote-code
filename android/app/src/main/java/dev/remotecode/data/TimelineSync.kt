package dev.remotecode.data

internal sealed interface TimelineChange {
    val id: String

    data class Put(val entry: Entry) : TimelineChange {
        override val id get() = entry.id
    }

    data class Append(override val id: String, val field: String, val text: String) : TimelineChange

    fun applyTo(entries: MutableList<Entry>) {
        val index = entries.indexOfLast { it.id == id }
        when (this) {
            is Put -> if (index < 0) entries.add(entry) else entries[index] = entry
            is Append -> if (index >= 0) {
                val old = entries[index]
                entries[index] = when (field) {
                    "output" -> old.copy(output = old.output.orEmpty() + text)
                    else -> old.copy(text = old.text.orEmpty() + text)
                }
            }
        }
    }
}

/** Repository のロック内で HTTP の履歴と WS イベントを順序付ける。 */
internal class TimelineSync {
    data class Update(val revision: Long?, val change: TimelineChange)
    class Read(val id: String, val updates: MutableList<Update> = mutableListOf())

    private val current = mutableSetOf<String>()
    private val revisions = mutableMapOf<String, Long>()
    private val reads = mutableMapOf<String, Read>()

    fun reconnected() {
        current.clear()
        revisions.clear()
        reads.clear()
    }

    fun isCurrent(id: String): Boolean = id in current

    fun begin(id: String): Read = Read(id).also { reads[id] = it }

    fun end(read: Read) {
        if (reads[read.id] === read) reads.remove(read.id)
    }

    fun receive(id: String, revision: Long?, change: TimelineChange): Boolean {
        if (!accept(id, revision)) return false
        reads[id]?.updates?.add(Update(revision, change))
        return true
    }

    fun install(read: Read, entries: List<Entry>, revision: Long?): List<Entry>? {
        val id = read.id
        if (reads[id] !== read) return null
        val merged = entries.toMutableList()
        if (revision != null) {
            for (update in read.updates) {
                if (update.revision == null || update.revision > revision) update.change.applyTo(merged)
            }
            revisions[id] = maxOf(revision, revisions[id] ?: revision)
            current.add(id)
        } else {
            // 旧サーバーでは、完全な行を受け取った後の差分だけ安全に再適用できる。
            val complete = mutableSetOf<String>()
            val incomplete = mutableSetOf<String>()
            for ((_, change) in read.updates) {
                if (change is TimelineChange.Put) {
                    complete.add(change.id)
                    incomplete.remove(change.id)
                }
                if (change.id in complete) change.applyTo(merged) else incomplete.add(change.id)
            }
            if (incomplete.isEmpty()) current.add(id) else current.remove(id)
        }
        return merged
    }

    fun invalidate(id: String, revision: Long?): Boolean {
        if (!accept(id, revision)) return false
        current.remove(id)
        reads.remove(id)
        return true
    }

    fun forget(id: String) {
        current.remove(id)
        revisions.remove(id)
        reads.remove(id)
    }

    private fun accept(id: String, revision: Long?): Boolean {
        if (revision == null) return true
        if (revision <= (revisions[id] ?: -1)) return false
        revisions[id] = revision
        return true
    }
}
