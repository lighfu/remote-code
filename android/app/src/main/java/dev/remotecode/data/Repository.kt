package dev.remotecode.data

import dev.remotecode.R
import dev.remotecode.i18n.appString
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed interface ConnState {
    data object Idle : ConnState
    data object Connecting : ConnState
    data object Connected : ConnState
    data class Failed(val message: String) : ConnState
}

data class Timeline(
    val entries: List<Entry> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

data class EntryEvent(val sessionId: String, val entry: Entry, val server: ServerConfig)

/** アプリ全体で接続と会話状態を共有する。 */
class Repository(context: Context, private val scope: CoroutineScope) {
    private data class ReadKey(val server: ConnectionIdentity?, val operation: String)
    private val reads = SharedRequests<ReadKey>(scope)
    private val timelineSync = TimelineSync()
    private val client = OkHttpClient.Builder()
        .dns(networkScopedDns(context))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .eventListenerFactory(HttpTraceFactory)
        .build()

    val api = Api(client)
    private val apiIdentity = MutableStateFlow<ConnectionIdentity?>(null)
    internal val activeServerIdentity = apiIdentity.asStateFlow()
    private val detailEvents = MutableSharedFlow<DetailChange>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal fun detailChanges(id: String, field: SessionDetailField) =
        detailEvents.filter { change -> session(id)?.let { change.affects(it, field) } == true }.map { Unit }

    private fun detailChanged(id: String, fields: Set<SessionDetailField>, cwd: String?) {
        if (fields.isNotEmpty()) detailEvents.tryEmit(DetailChange(id, cwd, fields))
    }

    private val gitSnapshots = GitSnapshots(scope)
    val git: StateFlow<Map<String, GitStatus>> = gitSnapshots.values
    val settingsStore = SettingsStore(context)

    private val _settings = MutableStateFlow<AppSettings?>(null)
    val settings: StateFlow<AppSettings?> = _settings.asStateFlow()

    private val sessionSnapshots = SessionSnapshots(gitSnapshots::forget)
    val sessions: StateFlow<List<SessionInfo>> = sessionSnapshots.values

    private val _conn = MutableStateFlow<ConnState>(ConnState.Idle)
    val connection: StateFlow<ConnState> = _conn.asStateFlow()

    private val _route = MutableStateFlow<String?>(null)
    val route: StateFlow<String?> = _route.asStateFlow()

    private val _meta = MutableStateFlow<Meta?>(null)
    val meta: StateFlow<Meta?> = _meta.asStateFlow()
    private val metadataRefresh = MetadataRefresh()

    /** インストール後も再接続せず、利用可能なプロバイダーを更新する。 */
    suspend fun refreshMeta(expectedServer: ServerConfig? = null, fresh: Boolean = false) {
        val target = expectedServer ?: api.server ?: return
        val identity = target.connectionIdentity()
        if (api.server?.connectionIdentity() != identity) return
        val ticket = metadataRefresh.ticket(fresh)
        reads.run(ReadKey(identity, "meta:$ticket")) {
            val next = api.check(target)
            var accepted = false
            metadataRefresh.publish(ticket) {
                synchronized(this@Repository) {
                    if (api.server?.connectionIdentity() == identity) {
                        _meta.value = next
                        accepted = true
                    }
                }
            }
            if (accepted) next.publicUrl?.let { rememberPublicUrl(target, it) }
        }
    }

    private val _usage = MutableStateFlow<UsageInfo?>(null)
    val usage: StateFlow<UsageInfo?> = _usage.asStateFlow()

    suspend fun refreshUsage(force: Boolean = false, expectedServer: ServerConfig? = null) {
        val target = expectedServer ?: api.server ?: return
        val server = target.connectionIdentity()
        reads.run(ReadKey(server, "usage:$force")) {
            runCatching { api.usage(force, expectedServer = target) }.onSuccess { usage ->
                synchronized(this@Repository) {
                    if (api.server?.connectionIdentity() == server) _usage.update { mergeUsage(it, usage) }
                }
            }
        }
    }

    /** 選択画面用の永続キャッシュ。 */
    private val modelPrefs = context.getSharedPreferences("models_cache", Context.MODE_PRIVATE)
    private val _models = MutableStateFlow<Map<String, List<ModelOption>>>(emptyMap())
    val models: StateFlow<Map<String, List<ModelOption>>> = _models.asStateFlow()
    private val modelRefresh = ModelRefresh()
    private val modelSerializer = ListSerializer(ModelOption.serializer())

    private fun loadCachedModels(server: ServerConfig?): Map<String, List<ModelOption>> {
        val id = server?.pairedServerId() ?: return emptyMap()
        return Providers.all.mapNotNull { provider ->
            val saved = modelPrefs.getString("$id:$provider", null) ?: return@mapNotNull null
            runCatching { provider to AppJson.decodeFromString(modelSerializer, saved) }.getOrNull()
        }.toMap()
    }

    /** 接続先別に 10 分再利用。force は再取得。 */
    suspend fun refreshModels(provider: String, force: Boolean = false) {
        val target = api.server ?: return
        val server = target.connectionIdentity()
        if (!modelRefresh.needed(server, provider, force)) return
        reads.run(ReadKey(server, "models:$provider:$force")) {
            if (!modelRefresh.needed(server, provider, force)) return@run
            val list = api.models(provider, expectedServer = target)
            synchronized(this@Repository) {
                if (api.server?.connectionIdentity() != server) return@run
                modelRefresh.fetched(server, provider)
                _models.update { it + (provider to list) }
                modelPrefs.edit().putString("${target.pairedServerId()}:$provider", AppJson.encodeToString(modelSerializer, list)).apply()
            }
        }
    }

    private val btwSnapshots = BtwSnapshots(scope)
    val btw: StateFlow<Map<String, List<BtwItem>>> = btwSnapshots.values

    suspend fun loadBtw(sessionId: String, force: Boolean = false) {
        val server = api.server?.connectionIdentity()
        try {
            btwSnapshots.load(sessionId, server, { connection.value is ConnState.Connected }, force) {
                val list = api.btwList(sessionId)
                if (api.server?.connectionIdentity() != server) throw CancellationException(appString(R.string.core_server_changed))
                list
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* 失敗時は前回値を保持する。 */ }
    }

    suspend fun askBtw(sessionId: String, question: String) {
        val server = api.server?.connectionIdentity()
        val item = api.btwAsk(sessionId, question)
        if (api.server?.connectionIdentity() == server) btwSnapshots.receive(sessionId, item)
    }

    /** 削除後、配信待ちの古い問答を再表示しない。 */
    suspend fun removeBtw(sessionId: String, itemId: String? = null) {
        val server = api.server?.connectionIdentity()
        api.btwRemove(sessionId, itemId)
        if (api.server?.connectionIdentity() == server) btwSnapshots.remove(sessionId, itemId)
    }

    private val _events = MutableSharedFlow<EntryEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<EntryEvent> = _events

    /** 通知の抑制対象。 */
    val visibleSession = MutableStateFlow<String?>(null)
    val appForeground = MutableStateFlow(false)
    private data class GitWatch(val id: String?, val cwd: String?, val connected: Boolean, val foreground: Boolean, val server: ConnectionIdentity?)
    private data class ContextWatch(val id: String, val server: ConnectionIdentity)

    private val timelines = ConcurrentHashMap<String, MutableStateFlow<Timeline>>()
    private val holders = ConcurrentHashMap.newKeySet<String>()
    private var socket: WebSocket? = null
    private var socketCfg: ServerConfig? = null
    private var retryJob: Job? = null
    private var attempt = 0
    private var candidateIndex = 0

    init {
        scope.launch {
            settingsStore.settings.collect(::applySettings)
        }
        scope.launch {
            combine(visibleSession, appForeground, _conn, sessions, apiIdentity) { id, foreground, connection, sessions, server ->
                val session = sessions.firstOrNull { it.id == id }
                GitWatch(id, session?.cwd, connection is ConnState.Connected, foreground, server)
            }.distinctUntilChanged().collectLatest { watch ->
                if (watch.id == null || watch.cwd == null || watch.server == null || !watch.connected || !watch.foreground) return@collectLatest
                val interval = { if (session(watch.id)?.isRunning == true) 10_000L else 30_000L }
                detailChanges(watch.id, SessionDetailField.Git).refreshOnChanges(interval) { changed ->
                    if (changed) gitSnapshots.invalidate(watch.id)
                    refreshGit(watch.id, force = changed)
                }
            }
        }
        scope.launch {
            combine(visibleSession, appForeground, _conn, sessions, apiIdentity) { id, foreground, connection, sessions, server ->
                val session = sessions.firstOrNull { it.id == id }
                if (id != null && session?.provider == "codex" && server != null && foreground && connection is ConnState.Connected) ContextWatch(id, server) else null
            }.pollForegroundSession(intervalMs = 30_000, changes = { detailChanges(it.id, SessionDetailField.Context) }) { watch ->
                val target = api.server?.takeIf { it.connectionIdentity() == watch.server } ?: return@pollForegroundSession
                // ローカル記録の増分を読み、利用量・キャッシュを session 配信で受け取る。
                api.contextDetail(watch.id, expectedServer = target)
            }
        }
        scope.launch {
            combine(appForeground, _conn, apiIdentity, _meta) { foreground, connection, server, meta ->
                val rates = meta?.usdRates
                if (foreground && connection is ConnState.Connected && server != null && rates != null) {
                    server to (rates.size > 2)
                } else null
            }.distinctUntilChanged().collectLatest { watch ->
                val (server, hasRateTable) = watch ?: return@collectLatest
                // The PC can finish its first exchange-rate fetch after the WebSocket connects.
                val interval = if (hasRateTable) 30 * 60_000L else 5 * 60_000L
                delay(if (hasRateTable) interval else 15_000L)
                while (true) {
                    val target = api.server?.takeIf { it.connectionIdentity() == server } ?: return@collectLatest
                    try {
                        refreshMeta(target)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { /* Preserve the last table during transient connection failures. */ }
                    delay(interval)
                }
            }
        }
    }

    @Synchronized
    private fun applySettings(settings: AppSettings) {
        val previous = _settings.value?.server
        val next = settings.server
        val serverChanged = previous?.token != next?.token || previous?.url != next?.url
        if (serverChanged) {
            closeSocket()
            candidateIndex = 0
            attempt = 0
            if (previous?.token != next?.token) {
                visibleSession.value = null
                _models.value = loadCachedModels(next)
            }
            sessionSnapshots.clear()
            _meta.value = null
            _usage.value = null
            btwSnapshots.clear()
            modelRefresh.clear()
            timelines.clear()
            timelineSync.reconnected()
            gitSnapshots.clear()
        }
        _settings.value = settings
        if (previous != next) {
            applyServer()
            if (holders.isNotEmpty() && (serverChanged || _conn.value !is ConnState.Connected)) reconnectNow()
        }
    }

    private fun applyServer() {
        val s = _settings.value?.server
        api.server = s?.let {
            val c = it.candidates
            it.copy(url = c[candidateIndex % c.size])
        }
        apiIdentity.value = api.server?.connectionIdentity()
    }

    private suspend fun rememberPublicUrl(source: ServerConfig, url: String) {
        val current = _settings.value?.server ?: return
        val normalized = url.trimEnd('/')
        if (current.token == source.token && isPublicUrl(normalized) && normalized != current.publicUrl.trimEnd('/')) {
            settingsStore.savePublicUrl(source.token, normalized)
        }
    }

    fun acquire(reason: String) {
        holders.add(reason)
        connect()
    }

    fun release(reason: String) {
        holders.remove(reason)
        if (holders.isEmpty()) {
            scope.launch {
                // 短い画面離脱では切断しない。
                delay(30_000)
                if (holders.isEmpty()) closeSocket()
            }
        }
    }

    @Synchronized
    fun reconnectNow() {
        attempt = 0
        closeSocket()
        connect()
    }

    @Synchronized
    private fun connect() {
        val cfg = api.server ?: return
        if (socket != null && socketCfg == cfg) return
        retryJob?.cancel()
        _conn.value = ConnState.Connecting
        socketCfg = cfg
        NetLog.socket("connecting", true, "${routeLabel(cfg.url)} · ${cfg.url}")
        socket = client.newWebSocket(api.eventsRequest(cfg), Listener(cfg))
    }

    @Synchronized
    private fun closeSocket() {
        retryJob?.cancel()
        socket?.cancel()
        socket = null
        socketCfg = null
        _route.value = null
        _conn.value = ConnState.Idle
    }

    private fun scheduleRetry(soon: Boolean = false) {
        if (holders.isEmpty() || api.server == null) return
        retryJob?.cancel()
        // サーバー復帰を検知できるよう、再試行間隔を制限する。
        val wait = if (soon) 300L else listOf(1_000L, 2_000L, 3_000L, 5_000L, 8_000L)[attempt.coerceAtMost(4)]
        if (!soon) attempt++
        retryJob = scope.launch {
            delay(wait)
            connect()
        }
    }

    private inner class Listener(private val cfg: ServerConfig) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@Repository) {
                if (socket !== webSocket) { webSocket.cancel(); return }
                sessionSnapshots.reconnected()
                timelineSync.reconnected()
                btwSnapshots.reconnected()
                gitSnapshots.reconnected()
                attempt = 0
                _route.value = cfg.url
                _conn.value = ConnState.Connected
            }
            NetLog.socket("connected", true, routeLabel(cfg.url))
            scope.launch { refreshAfterConnect(cfg) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (synchronized(this@Repository) { socket !== webSocket }) return
            runCatching {
                val obj = AppJson.parseToJsonElement(text).jsonObject
                val type = obj.str("type") ?: "?"
                val note = if (type == "entry") (obj["entry"] as? JsonObject)?.str("kind") else null
                NetLog.event(type, text.length.toLong(), note, body = text)
                synchronized(this@Repository) {
                    if (socket === webSocket) handle(obj, cfg)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(this@Repository) {
                if (socket !== webSocket) return
                socket = null
                socketCfg = null
                val msg = when {
                    response?.code == 401 -> appString(R.string.core_token_invalid)
                    response?.code == 403 && response.header("cf-ray") != null -> appString(R.string.core_access_denied)
                    cfg.url.contains("trycloudflare.com") -> appString(R.string.core_public_url_unreachable)
                    else -> appString(R.string.core_route_connection_error, routeLabel(cfg.url), t.message ?: appString(R.string.core_connection_lost))
                }
                _conn.value = ConnState.Failed(msg)
                _route.value = null
                NetLog.socket("failed", false, msg)
                if (response?.code == 401) return
                val count = _settings.value?.server?.candidates?.size ?: 1
                candidateIndex++
                applyServer()
                scheduleRetry(soon = candidateIndex % count != 0)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(this@Repository) {
                if (socket !== webSocket) return
                socket = null
                socketCfg = null
                _conn.value = ConnState.Failed(appString(R.string.core_connection_closed))
                _route.value = null
                NetLog.socket("closed", false, appString(R.string.core_connection_closed_code, code))
                scheduleRetry()
            }
        }
    }

    private suspend fun refreshAfterConnect(source: ServerConfig) = coroutineScope {
        if (api.server?.connectionIdentity() != source.connectionIdentity()) return@coroutineScope
        launch { runCatching { refreshMeta(source) } }
        launch { refreshUsage(expectedServer = source) }
        // 表示中の会話を先に復元する。
        visibleSession.value?.let { id ->
            launch { loadTimeline(id, force = true) }
            if (btw.value.containsKey(id)) launch { loadBtw(id) }
        }
        launch {
            val crash = dev.remotecode.RemoteCodeApp.instance.crashFile
            if (crash.exists()) runCatching { api.reportCrash(crash.readText()) }.onSuccess { crash.delete() }
        }
    }

    private fun handle(obj: JsonObject, source: ServerConfig) {
        val timelineRevision = (obj["timelineRevision"] as? JsonPrimitive)?.longOrNull
        when (obj.str("type")) {
            "hello" -> {
                val list = AppJson.decodeFromJsonElement(ListSerializer(SessionInfo.serializer()), obj["sessions"]!!)
                sessionSnapshots.replaceFromSocket(list)
            }
            "session" -> {
                val s = AppJson.decodeFromJsonElement(SessionInfo.serializer(), obj["session"]!!)
                val previous = if (s.id == visibleSession.value) session(s.id) else null
                sessionSnapshots.receive(s)
                if (previous != null) detailChanged(s.id, changedDetails(previous, s), s.cwd)
            }
            "session_details" -> {
                val id = obj.str("sessionId") ?: return
                val names = (obj["fields"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                detailChanged(id, SessionDetailField.entries.filterTo(mutableSetOf()) { it.wireName in names }, obj.str("cwd"))
            }
            "usage" -> {
                val p = obj.str("provider") ?: return
                val u = AppJson.decodeFromJsonElement(ProviderUsage.serializer(), obj["usage"]!!)
                val incoming = when (p) {
                    "codex" -> UsageInfo(codex = u)
                    "antigravity" -> UsageInfo(antigravity = u)
                    else -> UsageInfo(claude = u)
                }
                _usage.update { mergeUsage(it, incoming) }
            }
            "url" -> {
                val url = obj.str("url") ?: return
                scope.launch { rememberPublicUrl(source, url) }
            }
            "session_removed" -> {
                val id = obj.str("id") ?: return
                sessionSnapshots.remove(id)
                timelines.remove(id)
                timelineSync.forget(id)
                btwSnapshots.forget(id)
            }
            "btw" -> {
                val sid = obj.str("sessionId") ?: return
                val item = AppJson.decodeFromJsonElement(BtwItem.serializer(), obj["item"]!!)
                btwSnapshots.receive(sid, item)
            }
            "btw_removed" -> {
                val sid = obj.str("sessionId") ?: return
                val itemId = obj.str("itemId")
                btwSnapshots.remove(sid, itemId)
            }
            "entries_removed" -> {
                val sid = obj.str("sessionId") ?: return
                val fromId = obj.str("fromId")
                if (!timelineSync.invalidate(sid, timelineRevision)) return
                timelines[sid]?.update { t ->
                    val i = if (fromId == null) -1 else t.entries.indexOfFirst { it.id == fromId }
                    if (i >= 0) t.copy(entries = t.entries.subList(0, i).toList()) else t
                }
                if (timelines[sid]?.value?.loaded == true) scope.launch { loadTimeline(sid, force = true) }
            }
            "entry" -> {
                val sid = obj.str("sessionId") ?: return
                val e = AppJson.decodeFromJsonElement(Entry.serializer(), obj["entry"]!!)
                receiveTimelineChange(sid, timelineRevision, TimelineChange.Put(e))
                if (visibleSession.value == sid) detailChanged(sid, entryDetails(e), session(sid)?.cwd)
                if (e.isPendingAsk || e.kind == "result" || e.kind == "user" && e.clientMessageId != null) {
                    _events.tryEmit(EntryEvent(sid, e, source))
                }
            }
            "delta" -> {
                val sid = obj.str("sessionId") ?: return
                val eid = obj.str("entryId") ?: return
                val field = obj.str("field") ?: "text"
                val text = obj.str("text") ?: return
                receiveTimelineChange(sid, timelineRevision, TimelineChange.Append(eid, field, text))
            }
        }
    }

    private fun receiveTimelineChange(id: String, revision: Long?, change: TimelineChange) {
        if (!timelineSync.receive(id, revision, change)) return
        timelines[id]?.update { timeline ->
            if (!timeline.loaded) timeline
            else timeline.copy(entries = timeline.entries.toMutableList().also(change::applyTo))
        }
    }

    fun session(id: String): SessionInfo? = sessions.value.firstOrNull { it.id == id }

    suspend fun refreshGit(id: String, force: Boolean = false) {
        val server = api.server?.connectionIdentity() ?: return
        val cwd = session(id)?.cwd ?: return
        if (connection.value !is ConnState.Connected) return
        gitSnapshots.load(id, server, cwd, force,
            valid = { api.server?.connectionIdentity() == server && session(id)?.cwd == cwd },
            fetch = { api.gitStatus(id, force) },
        )
    }

    suspend fun gitAction(
        id: String, action: String, paths: List<String> = emptyList(), message: String = "", branch: String = "",
        remote: String? = null, setUpstream: Boolean = false,
    ): GitActionResult {
        if (action !in setOf("stage", "unstage", "commit", "push", "fetch", "pull", "checkout", "branch")) throw ApiException(appString(R.string.core_git_action_invalid))
        val server = api.server?.connectionIdentity() ?: throw ApiException(appString(R.string.core_server_not_configured))
        val cwd = session(id)?.cwd ?: throw ApiException(appString(R.string.core_session_not_found))
        if (connection.value !is ConnState.Connected) throw ApiException(appString(R.string.core_connect_before_action))
        gitSnapshots.invalidate(id)
        try {
            // 書き込みは共有・再送しない。
            return api.gitAction(id, action, paths, message, branch, remote, setUpstream, cwd = cwd)
        } finally {
            if (api.server?.connectionIdentity() == server && session(id)?.cwd == cwd) {
                gitSnapshots.invalidate(id)
                refreshGit(id, force = true)
            }
        }
    }

    fun timeline(id: String): StateFlow<Timeline> = timelines.getOrPut(id) { MutableStateFlow(Timeline()) }

    suspend fun loadTimeline(id: String, force: Boolean = false) {
        val server = api.server?.connectionIdentity()
        val flow = synchronized(this) { timelines.getOrPut(id) { MutableStateFlow(Timeline()) } }
        repeat(2) { pass ->
            reads.run(ReadKey(server, "timeline:$id")) {
                val (read, sessionRead) = synchronized(this@Repository) {
                    if (api.server?.connectionIdentity() != server || timelines[id] !== flow) return@run
                    if ((!force || pass > 0) && flow.value.loaded && flow.value.error == null &&
                        connection.value is ConnState.Connected && timelineSync.isCurrent(id)) return@run
                    flow.update { it.copy(loading = true, error = null) }
                    timelineSync.begin(id) to sessionSnapshots.beginRead()
                }
                try {
                    val d = api.session(id)
                    synchronized(this@Repository) {
                        if (api.server?.connectionIdentity() == server && timelines[id] === flow) {
                            val entries = timelineSync.install(read, d.entries, d.timelineRevision)
                            if (entries != null && (timelineSync.isCurrent(id) || !flow.value.loaded)) {
                                flow.value = Timeline(entries = entries, loaded = true)
                            }
                            sessionSnapshots.applyRead(sessionRead, d.session)
                        }
                        flow.update { it.copy(loading = false) }
                    }
                } catch (e: CancellationException) {
                    flow.update { it.copy(loading = false) }
                    throw e
                } catch (e: Exception) {
                    flow.update { it.copy(loading = false, error = e.message) }
                } finally {
                    synchronized(this@Repository) { timelineSync.end(read) }
                }
            }
            val retry = synchronized(this) {
                api.server?.connectionIdentity() == server && timelines[id] === flow && flow.value.error == null &&
                    connection.value is ConnState.Connected && !timelineSync.isCurrent(id)
            }
            if (!retry) return
        }
    }

    suspend fun refreshSessions() {
        val server = api.server?.connectionIdentity()
        val started = sessionSnapshots.beginRead()
        val snapshot = api.sessions()
        if (api.server?.connectionIdentity() == server) sessionSnapshots.applyRead(started, snapshot)
    }
}
