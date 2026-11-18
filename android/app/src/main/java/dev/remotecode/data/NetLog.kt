package dev.remotecode.data

import dev.remotecode.R
import dev.remotecode.i18n.appString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

enum class NetKind { API, SOCKET, EVENT }

/** feature で表示色とアイコンを選ぶ。 */
data class NetLogEntry(
    val id: Long,
    val at: Long,
    val kind: NetKind,
    val feature: String,
    val ok: Boolean = true,
    val method: String? = null,
    val path: String? = null,
    val status: Int? = null,
    val ms: Long? = null,
    val bytes: Long? = null,
    val note: String? = null,
    val count: Int = 1,
    /** JSON 本文のみ。認証ヘッダーは保存しない。 */
    val request: String? = null,
    val response: String? = null,
    val timing: NetTiming? = null,
)

object NetLog {
    private const val MAX = 400
    private val seq = AtomicLong()
    private val _entries = MutableStateFlow<List<NetLogEntry>>(emptyList())
    val entries: StateFlow<List<NetLogEntry>> = _entries.asStateFlow()

    @Volatile var paused = false

    private val uuid = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

    private const val MAX_BODY = 6_000
    private val longData = Regex(""""([A-Za-z0-9+/=_-]{300,})"""")

    /** 長いデータはサイズ表示に置換し、本文を切り詰める。 */
    fun clip(s: String?): String? {
        if (s.isNullOrEmpty()) return null
        val t = s.replace(longData) { appString(R.string.core_log_data_omitted, it.groupValues[1].length * 3 / 4 / 1024) }
        return if (t.length > MAX_BODY) t.take(MAX_BODY) + appString(R.string.core_log_truncated, t.length - MAX_BODY) else t
    }

    fun shortPath(path: String): String = path.substringBefore('?').replace(uuid) { it.value.take(8) }

    fun featureOf(method: String, path: String): String {
        val p = path.substringBefore('?')
        return when {
            p.endsWith("/messages") -> "send"
            p.endsWith("/btw") || p.contains("/btw/") -> "btw"
            p.endsWith("/interrupt") -> "stop"
            p.endsWith("/rewind") -> "rewind"
            p.endsWith("/context") -> "context"
            p.endsWith("/cache") -> "cache"
            p.endsWith("/respond") -> "respond"
            p.endsWith("/takeover") -> "takeover"
            p.endsWith("/detach") -> "detach"
            p.endsWith("/command") -> "command"
            p.startsWith("/api/sessions/") && method == "GET" -> "load"
            p.startsWith("/api/sessions/") && method == "DELETE" -> "delete"
            p.startsWith("/api/sessions/") -> "update"
            p == "/api/sessions" && method == "POST" -> "create"
            p == "/api/sessions" -> "list"
            p.startsWith("/api/history") -> "history"
            p.startsWith("/api/usage") -> "usage"
            p.startsWith("/api/meta") -> "meta"
            p.startsWith("/api/models") -> "models"
            p.startsWith("/api/commands") -> "commands"
            p.startsWith("/api/terminals") -> "terminals"
            p.startsWith("/api/claude/") -> "manage"
            p.startsWith("/api/app") -> "appUpdate"
            p.startsWith("/api/media") -> "media"
            p.startsWith("/api/file") -> "file"
            p.startsWith("/api/fs") || p.startsWith("/api/dirs") || p.startsWith("/api/recent-dirs") || p.startsWith("/api/favorites") -> "files"
            else -> "other"
        }
    }

    fun add(e: NetLogEntry) {
        if (paused) return
        _entries.update { list ->
            val last = list.lastOrNull()
            if (e.kind == NetKind.EVENT && last != null && last.kind == NetKind.EVENT && last.feature == e.feature && last.note == e.note && e.at - last.at < 3_000) {
                list.dropLast(1) + last.copy(at = e.at, count = last.count + 1, bytes = (last.bytes ?: 0) + (e.bytes ?: 0), response = e.response ?: last.response)
            } else {
                (list + e.copy(id = seq.incrementAndGet())).takeLast(MAX)
            }
        }
    }

    fun api(method: String, path: String, status: Int?, ms: Long, bytes: Long?, error: String? = null, request: String? = null, response: String? = null, timing: NetTiming? = null) {
        if (paused) return
        add(
            NetLogEntry(
                0, System.currentTimeMillis(), NetKind.API, featureOf(method, path), ok = error == null && (status ?: 0) in 200..399,
                method = method, path = shortPath(path), status = status, ms = ms, bytes = bytes, note = error,
                request = clip(request), response = clip(response), timing = timing,
            ),
        )
    }

    fun socket(feature: String, ok: Boolean, note: String? = null) =
        add(NetLogEntry(0, System.currentTimeMillis(), NetKind.SOCKET, feature, ok = ok, note = note))

    fun event(type: String, bytes: Long, note: String? = null, body: String? = null) {
        if (paused) return
        add(NetLogEntry(0, System.currentTimeMillis(), NetKind.EVENT, "ev:$type", bytes = bytes, note = note, response = clip(body)))
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
