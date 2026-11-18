package dev.remotecode.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 取得中に受け取った会話の状態を、古い HTTP スナップショットで戻さない。 */
internal class SessionSnapshots(private val forgetGit: (String) -> Unit = {}) {
    private val _values = MutableStateFlow<List<SessionInfo>>(emptyList())
    val values = _values.asStateFlow()
    private var revision = 0L
    private var rosterRevision = 0L
    private val changes = mutableMapOf<String, Long>()

    @Synchronized fun beginRead(): Long = ++revision

    @Synchronized fun reconnected() {
        rosterRevision = ++revision
        changes.clear()
    }

    @Synchronized fun replaceFromSocket(sessions: List<SessionInfo>) {
        reconnected()
        publish(sessions.associateBy { it.id })
    }

    @Synchronized fun receive(session: SessionInfo) {
        val current = _values.value.associateBy { it.id }
        changes[session.id] = ++revision
        publish(current + (session.id to session))
    }

    @Synchronized fun remove(id: String) {
        changes[id] = ++revision
        publish(_values.value.associateBy { it.id } - id)
    }

    @Synchronized fun clear() {
        reconnected()
        _values.value = emptyList()
    }

    @Synchronized fun applyRead(started: Long, session: SessionInfo) {
        if (started <= rosterRevision || (changes[session.id] ?: 0) > started) return
        val current = _values.value.associateBy { it.id }
        changes[session.id] = started
        publish(current + (session.id to session))
    }

    @Synchronized fun applyRead(started: Long, sessions: List<SessionInfo>) {
        if (started <= rosterRevision) return
        val current = _values.value.associateBy { it.id }
        val merged = sessions.associateByTo(linkedMapOf()) { it.id }
        changes.values.removeAll { it <= started }
        for (id in changes.keys) {
            val existing = current[id]
            if (existing == null) merged.remove(id) else merged[id] = existing
        }
        rosterRevision = started
        publish(merged)
    }

    private fun publish(sessions: Map<String, SessionInfo>) {
        for (previous in _values.value) {
            if (sessions[previous.id]?.cwd != previous.cwd) forgetGit(previous.id)
        }
        _values.value = sessions.values.sortedByDescending { it.updatedAt }
    }
}
