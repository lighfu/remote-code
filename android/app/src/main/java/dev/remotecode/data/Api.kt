package dev.remotecode.data

import dev.remotecode.R
import dev.remotecode.i18n.AppStringResolver
import dev.remotecode.i18n.applicationStrings
import dev.remotecode.i18n.localizedServerMessage
import dev.remotecode.i18n.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class ApiException(message: String, val code: Int = 0) : Exception(message)

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    // 不正な null は既定値へ補正する。
    coerceInputValues = true
    encodeDefaults = true
}

class Api(private val client: OkHttpClient, private val strings: AppStringResolver = applicationStrings) {
    private fun localString(id: Int, vararg args: Any) = strings.text(id, *args)

    @Volatile
    var server: ServerConfig? = null

    internal val desktop by lazy { DesktopApi(client, strings) { server } }

    private data class MediaRequest(val server: ConnectionIdentity, val id: String, val fresh: Boolean)
    private val mediaTransfers = SharedValues<MediaRequest, ByteArray>(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    private data class ContextRequest(val server: ConnectionIdentity, val id: String, val cached: Boolean)
    private val contextTransfers = SharedValues<ContextRequest, ContextDetail>(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    private val mediaClient: OkHttpClient by lazy {
        mediaHttpClient(client, java.io.File(dev.remotecode.RemoteCodeApp.instance.cacheDir, "media-http"))
    }

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun base(): ServerConfig = server ?: throw ApiException(localString(R.string.core_server_not_configured))

    /** 時間のかかる管理操作用。 */
    private val slowClient: OkHttpClient by lazy { client.newBuilder().readTimeout(6, java.util.concurrent.TimeUnit.MINUTES).build() }
    /** 接続先を固定し、リダイレクトと自動再送を行わない。 */
    private val directClient: OkHttpClient by lazy {
        client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    }
    private val projectActionClient: OkHttpClient by lazy {
        directClient.newBuilder().readTimeout(6, java.util.concurrent.TimeUnit.MINUTES).callTimeout(6, java.util.concurrent.TimeUnit.MINUTES).build()
    }
    private val fileUploadClient: OkHttpClient by lazy {
        client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .writeTimeout(2, java.util.concurrent.TimeUnit.MINUTES).readTimeout(2, java.util.concurrent.TimeUnit.MINUTES)
            .callTimeout(5, java.util.concurrent.TimeUnit.MINUTES).build()
    }

    private suspend fun <T> callAs(
        method: String, path: String, body: JsonElement? = null, cfg: ServerConfig = base(), privatePayload: Boolean = false, decode: (String) -> T,
    ): T {
        val started = System.nanoTime()
        val trace = HttpTrace()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        return withContext(Dispatchers.IO) {
            trace.dispatched()
            val activeClient = when {
                method == "POST" && path.startsWith("/api/dependencies/") && path.endsWith("/install") -> directClient
                method == "POST" && path.endsWith("/project/action") -> projectActionClient
                method == "POST" && path.startsWith("/api/sessions/") && (path.contains("/git/") || path.contains("/project/")) -> directClient
                method == "POST" && path.endsWith("/messages") && body is JsonObject && (body["files"] as? JsonArray)?.isNotEmpty() == true -> fileUploadClient
                method == "POST" && path.endsWith("/messages") && privatePayload -> directClient
                path.startsWith("/api/claude/") -> slowClient
                else -> client
            }
            val sent = body?.toString()
            // ソースや設定値をログへ残さない。
            val logBodies = !privatePayload && !(path.startsWith("/api/sessions/") && path.substringBefore('?').endsWith("/project/file"))
            val loggedRequest = if (logBodies) sent else null
            val rb = sent?.toRequestBody(jsonType)
            val req = Request.Builder()
                .url(cfg.url.trimEnd('/') + path)
                .auth(cfg)
                .tag(HttpTrace::class.java, trace)
                .method(method, rb ?: if (method == "GET" || method == "DELETE") null else "{}".toRequestBody(jsonType))
                .build()
            val res = try {
                activeClient.newCall(req).execute()
            } catch (e: IOException) {
                NetLog.api(method, path, null, elapsed(), null, localString(R.string.core_pc_connection_error, e.message ?: e.javaClass.simpleName), request = loggedRequest)
                throw ApiException(localString(R.string.core_pc_connection_error, e.message ?: e.javaClass.simpleName))
            }
            val headersMs = elapsed()
            res.use {
                if (it.code == 403 && it.header("cf-ray") != null && !it.isSuccessful) {
                    NetLog.api(method, path, 403, elapsed(), null, localString(R.string.core_access_denied), request = loggedRequest, timing = NetTiming.fromResponse(it, headersMs, 0))
                    throw ApiException(if (cfg.hasAccess) localString(R.string.core_access_token_rejected) else localString(R.string.core_access_token_required), 403)
                }
                val text = it.body.string()
                val loggedResponse = if (logBodies) text else null
                val timing = NetTiming.fromResponse(it, headersMs, elapsed() - headersMs)
                if (!it.isSuccessful) {
                    val parsed = runCatching { AppJson.parseToJsonElement(text) }.getOrNull()
                    val msg = (parsed as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull?.let { message -> localizedServerMessage(message, strings) }
                    NetLog.api(method, path, it.code, elapsed(), text.length.toLong(), msg ?: localString(R.string.core_http_error, it.code), request = loggedRequest, response = loggedResponse, timing = timing)
                    throw ApiException(msg ?: localString(R.string.core_http_error, it.code), it.code)
                }
                val decoded = try {
                    withContext(Dispatchers.Default) { decode(text) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    NetLog.api(method, path, it.code, elapsed(), text.toByteArray().size.toLong(), if (logBodies) localString(R.string.core_response_decode_error_details, e.message.orEmpty()) else localString(R.string.core_response_decode_error), request = loggedRequest, response = loggedResponse, timing = timing)
                    throw e
                }
                NetLog.api(method, path, it.code, elapsed(), text.toByteArray().size.toLong(), request = loggedRequest, response = loggedResponse, timing = timing)
                decoded
            }
        }
    }

    private suspend fun call(method: String, path: String, body: JsonElement? = null, cfg: ServerConfig = base(), privatePayload: Boolean = false): JsonElement =
        callAs(method, path, body, cfg, privatePayload) { runCatching { AppJson.parseToJsonElement(it) }.getOrElse { JsonObject(emptyMap()) } }

    /** 中間の JsonElement を作らず復元する。 */
    private suspend fun <T> get(path: String, s: KSerializer<T>): T = callAs("GET", path) { AppJson.decodeFromString(s, it) }

    suspend fun check(cfg: ServerConfig): Meta {
        withContext(Dispatchers.IO) { cfg.url.trimEnd('/').toHttpUrl() }
        return AppJson.decodeFromJsonElement(Meta.serializer(), call("GET", "/api/meta", cfg = cfg))
    }

    suspend fun meta(): Meta = get("/api/meta", Meta.serializer())

    suspend fun controlPaths(): ControlPathsSnapshot = get("/api/control-paths", ControlPathsSnapshot.serializer())

    private fun dependencyTarget(expectedServer: ServerConfig?): ServerConfig {
        val current = base()
        if (expectedServer != null && current.connectionIdentity() != expectedServer.connectionIdentity()) {
            throw ApiException(localString(R.string.server_dependencies_changed), 409)
        }
        return expectedServer ?: current
    }

    suspend fun dependencies(expectedServer: ServerConfig? = null): ServerDependencies {
        val target = dependencyTarget(expectedServer)
        val result = callAs("GET", "/api/dependencies", cfg = target) { AppJson.decodeFromString(ServerDependencies.serializer(), it) }
        dependencyTarget(target)
        return result
    }

    suspend fun installDependency(dependency: String, expectedServer: ServerConfig? = null): ServerDependencies {
        if (dependency !in serverDependencyTargets) throw ApiException(localString(R.string.server_dependencies_unknown), 400)
        val target = dependencyTarget(expectedServer)
        val result = callAs("POST", "/api/dependencies/$dependency/install", buildJsonObject {}, cfg = target) {
            AppJson.decodeFromString(ServerDependencies.serializer(), it)
        }
        dependencyTarget(target)
        return result
    }

    suspend fun sessions(): List<SessionInfo> = get("/api/sessions", ListSerializer(SessionInfo.serializer()))

    suspend fun session(id: String): SessionDetail = get("/api/sessions/$id?limit=600", SessionDetail.serializer())

    private fun projectTarget(expectedServer: ServerConfig?): ServerConfig {
        val current = base()
        if (expectedServer != null && current.connectionIdentity() != expectedServer.connectionIdentity()) {
            throw ApiException(localString(R.string.core_project_server_changed), 409)
        }
        return expectedServer ?: current
    }

    private suspend fun <T> projectGet(path: String, serializer: KSerializer<T>, expectedServer: ServerConfig?): T {
        val target = projectTarget(expectedServer)
        val result = callAs("GET", path, cfg = target) { AppJson.decodeFromString(serializer, it) }
        projectTarget(target)
        return result
    }

    suspend fun projectFiles(sessionId: String, path: String = "", expectedServer: ServerConfig? = null, query: String = ""): ProjectDirectory =
        projectGet("/api/sessions/$sessionId/project/files?path=" + java.net.URLEncoder.encode(path, "UTF-8") + "&query=" + java.net.URLEncoder.encode(query, "UTF-8"), ProjectDirectory.serializer(), expectedServer)

    suspend fun projectFile(sessionId: String, path: String, expectedServer: ServerConfig? = null): ProjectDocument =
        projectGet("/api/sessions/$sessionId/project/file?path=" + java.net.URLEncoder.encode(path, "UTF-8"), ProjectDocument.serializer(), expectedServer)

    suspend fun saveProjectFile(sessionId: String, document: ProjectDocument, text: String, expectedServer: ServerConfig? = null): ProjectDocument {
        val target = projectTarget(expectedServer)
        return callAs("POST", "/api/sessions/$sessionId/project/file", buildJsonObject {
            put("root", document.root)
            put("path", document.path)
            put("text", text)
            put("revision", document.revision)
        }, cfg = target) { AppJson.decodeFromString(ProjectDocument.serializer(), it) }
    }

    suspend fun createProjectEntry(sessionId: String, root: String, path: String, kind: String, expectedServer: ServerConfig? = null): ProjectCreateResult {
        val target = projectTarget(expectedServer)
        return callAs("POST", "/api/sessions/$sessionId/project/create", buildJsonObject {
            put("root", root)
            put("path", path)
            put("kind", kind)
        }, cfg = target) { AppJson.decodeFromString(ProjectCreateResult.serializer(), it) }
    }

    suspend fun projectEntry(sessionId: String, path: String, expectedServer: ServerConfig? = null): ProjectEntryInfo =
        projectGet("/api/sessions/$sessionId/project/entry?path=" + java.net.URLEncoder.encode(path, "UTF-8"), ProjectEntryInfo.serializer(), expectedServer)

    suspend fun projectAction(sessionId: String, entry: ProjectEntryInfo, action: String, destination: String? = null, expectedServer: ServerConfig? = null): ProjectActionResult {
        val target = projectTarget(expectedServer)
        return callAs("POST", "/api/sessions/$sessionId/project/action", buildJsonObject {
            put("root", entry.root)
            put("path", entry.path)
            put("version", entry.version)
            put("action", action)
            if (destination != null) put("destination", destination)
            if (action == "delete") put("confirm", true)
        }, cfg = target) { AppJson.decodeFromString(ProjectActionResult.serializer(), it) }
    }

    suspend fun gitStatus(id: String, force: Boolean = false): GitStatus =
        get("/api/sessions/$id/git" + if (force) "?force=1" else "", GitStatus.serializer())

    suspend fun gitDiff(id: String, path: String, staged: Boolean = false, expectedServer: ServerConfig? = null): GitDiff =
        projectGet("/api/sessions/$id/git/diff?path=" + java.net.URLEncoder.encode(path, "UTF-8") + if (staged) "&staged=1" else "",
            GitDiff.serializer(), expectedServer)

    suspend fun gitLog(id: String): List<GitLogEntry> = get("/api/sessions/$id/git/log", ListSerializer(GitLogEntry.serializer()))

    suspend fun gitBranches(id: String): List<GitBranch> = get("/api/sessions/$id/git/branches", ListSerializer(GitBranch.serializer()))

    suspend fun gitAction(
        id: String, action: String, paths: List<String> = emptyList(), message: String = "",
        branch: String = "", remote: String? = null, setUpstream: Boolean = false, cwd: String? = null,
    ): GitActionResult {
        require(action in setOf("stage", "unstage", "commit", "push", "fetch", "pull", "checkout", "branch"))
        val body = buildJsonObject {
            if (cwd != null) put("cwd", cwd)
            if (action == "stage" || action == "unstage") put("paths", AppJson.encodeToJsonElement(paths))
            if (action == "commit") put("message", message)
            if (action == "checkout") put("branch", branch)
            if (action == "branch") put("name", branch)
            if (action == "push") {
                if (remote != null) put("remote", remote)
                put("setUpstream", setUpstream)
            }
        }
        return AppJson.decodeFromJsonElement(GitActionResult.serializer(), call("POST", "/api/sessions/$id/git/$action", body))
    }

    suspend fun createSession(
        provider: String,
        cwd: String,
        mode: String,
        text: String,
        images: List<ImagePayload>,
        thinking: String = "full",
        model: String? = null,
        effort: String? = null,
        fastMode: Boolean? = null,
    ): SessionInfo {
        val body = buildJsonObject {
            put("provider", provider)
            put("cwd", cwd)
            put("mode", mode)
            put("thinking", thinking)
            if (model != null) put("model", model)
            if (effort != null) put("effort", effort)
            if (fastMode != null) put("fastMode", fastMode)
            put("text", text)
            put("images", AppJson.encodeToJsonElement(images))
        }
        return AppJson.decodeFromJsonElement(SessionInfo.serializer(), call("POST", "/api/sessions", body))
    }

    /** now: 実行中の作業を中断して送る。 */
    suspend fun send(
        id: String,
        text: String,
        images: List<ImagePayload>,
        now: Boolean = false,
        files: List<String> = emptyList(),
        expectedServer: ServerConfig? = null,
        quotedCode: Boolean = false,
        clientMessageId: String? = null,
    ) {
        if (expectedServer != null && server?.connectionIdentity() != expectedServer.connectionIdentity()) throw ApiException(localString(R.string.core_send_server_changed_retry))
        call("POST", "/api/sessions/$id/messages", buildJsonObject {
            put("text", text)
            put("images", AppJson.encodeToJsonElement(images))
            if (now) put("now", true)
            if (files.isNotEmpty()) put("files", AppJson.encodeToJsonElement(files))
            if (clientMessageId != null) put("clientMessageId", clientMessageId)
        }, cfg = expectedServer ?: base(), privatePayload = quotedCode)
    }

    internal suspend fun uploadFile(context: android.content.Context, file: SelectedFile, cfg: ServerConfig, onProgress: (Long) -> Unit): UploadedFile = withContext(Dispatchers.IO) {
        if (server?.connectionIdentity() != cfg.connectionIdentity()) throw ApiException(localString(R.string.core_send_server_changed))
        val coroutineContext = currentCoroutineContext()
        val path = "/api/uploads"
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        val trace = HttpTrace()
        trace.dispatched()
        val body = FileUploadBody(
            open = { context.contentResolver.openInputStream(file.uri) ?: throw IOException(localString(R.string.core_open_file_error, file.name)) },
            onProgress = onProgress,
            checkActive = { coroutineContext.ensureActive() },
        )
        val url = (cfg.url.trimEnd('/') + path).toHttpUrl().newBuilder().addQueryParameter("name", file.name).addQueryParameter("mime", file.mime).build()
        val request = Request.Builder().url(url).auth(cfg).tag(HttpTrace::class.java, trace).post(body).build()
        try {
            fileUploadClient.newCall(request).execute().use { response ->
                val headersMs = elapsed()
                val text = response.body.string()
                val timing = NetTiming.fromResponse(response, headersMs, elapsed() - headersMs)
                if (!response.isSuccessful) {
                    val parsed = runCatching { AppJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
                    val message = when {
                        response.code == 404 -> localString(R.string.core_upload_server_update_required)
                        response.code == 403 && response.header("cf-ray") != null -> localString(R.string.core_upload_access_denied)
                        else -> parsed?.get("error")?.jsonPrimitive?.contentOrNull?.let { localizedServerMessage(it, strings) } ?: localString(R.string.core_upload_http_error, response.code)
                    }
                    NetLog.api("POST", path, response.code, elapsed(), text.toByteArray().size.toLong(), message, response = text, timing = timing)
                    throw ApiException(message, response.code)
                }
                val uploaded = AppJson.decodeFromString(UploadedFile.serializer(), text)
                if (uploaded.id.isBlank() || uploaded.size < 0 || uploaded.size > MAX_ATTACHMENT_BYTES) throw ApiException(localString(R.string.core_upload_decode_error))
                NetLog.api("POST", path, response.code, elapsed(), text.toByteArray().size.toLong(), response = text, timing = timing)
                coroutineContext.ensureActive()
                uploaded
            }
        } catch (e: IOException) {
            NetLog.api("POST", path, null, elapsed(), null, e.message ?: localString(R.string.core_upload_error))
            throw ApiException(e.message ?: localString(R.string.core_upload_error))
        } catch (_: SecurityException) {
            throw ApiException(localString(R.string.core_file_access_revoked))
        }
    }

    /** cached: 保存済み内訳を返し、古ければ裏で更新する。 */
    suspend fun contextDetail(id: String, cached: Boolean = false, expectedServer: ServerConfig? = null): ContextDetail {
        val target = projectTarget(expectedServer)
        return contextTransfers.get(ContextRequest(target.connectionIdentity(), id, cached)) {
            projectGet("/api/sessions/$id/context" + if (cached) "?cached=1" else "", ContextDetail.serializer(), target)
        }
    }

    suspend fun cacheDetail(id: String): CacheDetail = get("/api/sessions/$id/cache", CacheDetail.serializer())

    /** null はモデル既定の長さ。 */
    suspend fun setCompactWindow(id: String, tokens: Long?) {
        call("POST", "/api/sessions/$id/compact-window", buildJsonObject {
            put("tokens", tokens?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        })
    }

    suspend fun compact(id: String, instructions: String) {
        call("POST", "/api/sessions/$id/compact", buildJsonObject { put("instructions", instructions) })
    }

    suspend fun setUltra(id: String, on: Boolean): SessionInfo =
        AppJson.decodeFromJsonElement(SessionInfo.serializer(), call("PATCH", "/api/sessions/$id", buildJsonObject { put("ultra", on) }))

    /** 会話に残らない /btw の問答。 */
    suspend fun btwList(id: String): List<BtwItem> = get("/api/sessions/$id/btw", ListSerializer(BtwItem.serializer()))

    /** pending を返し、回答は WebSocket で受け取る。 */
    suspend fun btwAsk(id: String, question: String): BtwItem =
        AppJson.decodeFromJsonElement(BtwItem.serializer(), call("POST", "/api/sessions/$id/btw", buildJsonObject { put("question", question) }))

    /** itemId が null なら全件削除。 */
    suspend fun btwRemove(id: String, itemId: String? = null) {
        call("DELETE", "/api/sessions/$id/btw" + (itemId?.let { "/$it" } ?: ""))
    }

    suspend fun rewind(id: String, entryId: String, files: Boolean): RewindResult =
        AppJson.decodeFromJsonElement(RewindResult.serializer(), call("POST", "/api/sessions/$id/rewind", buildJsonObject {
            put("entryId", entryId)
            put("files", files)
        }))

    suspend fun interrupt(id: String) {
        call("POST", "/api/sessions/$id/interrupt")
    }

    suspend fun takeover(id: String) {
        call("POST", "/api/sessions/$id/takeover")
    }

    suspend fun detach(id: String) {
        call("POST", "/api/sessions/$id/detach")
    }

    suspend fun respond(id: String, requestId: String, choice: String? = null, answers: Map<String, List<String>>? = null, message: String? = null) {
        call("POST", "/api/sessions/$id/respond", buildJsonObject {
            put("requestId", requestId)
            if (choice != null) put("choice", choice)
            if (message != null) put("message", message)
            if (answers != null) putJsonObject("answers") {
                for ((k, v) in answers) putJsonArray(k) { v.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            }
        })
    }

    suspend fun patch(id: String, title: String? = null, mode: String? = null, archived: Boolean? = null, fastMode: Boolean? = null): SessionInfo {
        val body = buildJsonObject {
            if (title != null) put("title", title)
            if (mode != null) put("mode", mode)
            if (archived != null) put("archived", archived)
            if (fastMode != null) put("fastMode", fastMode)
        }
        return AppJson.decodeFromJsonElement(SessionInfo.serializer(), call("PATCH", "/api/sessions/$id", body))
    }

    suspend fun setThinking(id: String, thinking: String): SessionInfo =
        AppJson.decodeFromJsonElement(SessionInfo.serializer(), call("PATCH", "/api/sessions/$id", buildJsonObject { put("thinking", thinking) }))

    /** null は PC の設定に従う。 */
    suspend fun setModel(id: String, model: String?, effort: String?, fastMode: Boolean? = null): SessionInfo {
        val body = buildJsonObject {
            put("model", model?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("effort", effort?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            if (fastMode != null) put("fastMode", fastMode)
        }
        return AppJson.decodeFromJsonElement(SessionInfo.serializer(), call("PATCH", "/api/sessions/$id", body))
    }

    suspend fun models(provider: String): List<ModelOption> = get("/api/models/$provider", ListSerializer(ModelOption.serializer()))

    suspend fun commands(provider: String, sessionId: String?): List<CommandInfo> =
        get("/api/commands/$provider" + (sessionId?.let { "?session=$it" } ?: ""), ListSerializer(CommandInfo.serializer()))

    suspend fun runCommand(id: String, name: String, args: String) {
        call("POST", "/api/sessions/$id/command", buildJsonObject {
            put("name", name)
            put("args", args)
        })
    }

    suspend fun usage(force: Boolean): UsageInfo = get("/api/usage" + if (force) "?force=1" else "", UsageInfo.serializer())

    suspend fun dirs(): DirsInfo = get("/api/dirs", DirsInfo.serializer())

    suspend fun favorite(path: String, on: Boolean) {
        call("POST", "/api/favorites", buildJsonObject {
            put("path", path)
            put("on", on)
        })
    }

    suspend fun mkdir(parent: String, name: String): String {
        val r = call("POST", "/api/fs/mkdir", buildJsonObject {
            put("parent", parent)
            put("name", name)
        })
        return r.str("path") ?: ""
    }

    suspend fun media(id: String, fresh: Boolean = false, expectedServer: ServerConfig? = null): ByteArray {
        val cfg = projectTarget(expectedServer)
        return mediaTransfers.get(MediaRequest(cfg.connectionIdentity(), id, fresh)) {
            val req = Request.Builder().url(cfg.url.trimEnd('/') + "/api/media/$id").auth(cfg)
                .apply { if (fresh) cacheControl(CacheControl.FORCE_NETWORK) }
                .build()
            val started = System.nanoTime()
            fun elapsed() = (System.nanoTime() - started) / 1_000_000
            mediaClient.newCall(req).execute().use {
                val headersMs = elapsed()
                if (!it.isSuccessful) {
                    NetLog.api("GET", "/api/media/…", it.code, headersMs, null, localString(R.string.core_image_http_error, it.code), timing = NetTiming.fromResponse(it, headersMs, 0))
                    throw ApiException(localString(R.string.core_image_http_error, it.code), it.code)
                }
                val bytes = it.body.bytes()
                val total = elapsed()
                NetLog.api("GET", "/api/media/…", it.code, total, bytes.size.toLong(), timing = NetTiming.fromResponse(it, headersMs, total - headersMs))
                bytes
            }
        }
    }

    internal fun mediaPlayback(id: String): MediaPlaybackSource = mediaPlaybackSource(base(), id)

    /** 動画をメモリへ展開せず保存し、画面終了時に通信を中断する。 */
    internal suspend fun copyMediaTo(id: String, destination: java.io.OutputStream): Long =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val source = mediaPlayback(id)
            val request = Request.Builder().url(source.url).apply { source.headers.forEach { (name, value) -> header(name, value) } }
                .cacheControl(CacheControl.FORCE_NETWORK).build()
            val call = client.newCall(request)
            val started = System.nanoTime()
            fun elapsed() = (System.nanoTime() - started) / 1_000_000
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(ApiException(localString(R.string.core_video_save_error_details, e.message.orEmpty()))))
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use { res ->
                        val headersMs = elapsed()
                        try {
                            if (!res.isSuccessful) throw ApiException(localString(R.string.core_video_save_http_error, res.code), res.code)
                            var bytes = 0L
                            res.body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (continuation.isActive) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    destination.write(buffer, 0, read)
                                    bytes += read
                                }
                            }
                            if (continuation.isActive) {
                                val total = elapsed()
                                NetLog.api("GET", "/api/media/…", res.code, total, bytes, timing = NetTiming.fromResponse(res, headersMs, total - headersMs))
                                continuation.resumeWith(Result.success(bytes))
                            }
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                        }
                    }
                }
            })
        }

    suspend fun delete(id: String) {
        call("DELETE", "/api/sessions/$id")
    }


    private fun msg(e: JsonElement): String = (e as? JsonObject)?.str("message") ?: localString(R.string.core_done)

    suspend fun mcpList(): List<McpServerInfo> = get("/api/claude/mcp", ListSerializer(McpServerInfo.serializer()))

    suspend fun mcpGet(name: String): McpDetail =
        AppJson.decodeFromJsonElement(McpDetail.serializer(), call("POST", "/api/claude/mcp/get", buildJsonObject { put("name", name) }))

    suspend fun mcpAdd(body: JsonObject) {
        call("POST", "/api/claude/mcp/add", body)
    }

    suspend fun mcpRemove(name: String, scope: String?) {
        call("POST", "/api/claude/mcp/remove", buildJsonObject {
            put("name", name)
            if (scope != null) put("scope", scope)
        })
    }

    suspend fun plugins(): PluginsOverview = get("/api/claude/plugins", PluginsOverview.serializer())

    suspend fun pluginSearch(q: String): List<PluginHit> =
        get("/api/claude/plugins/search?q=" + java.net.URLEncoder.encode(q, "UTF-8"), ListSerializer(PluginHit.serializer()))

    suspend fun pluginInstall(id: String, acceptCommand: String? = null): InstallResult =
        AppJson.decodeFromJsonElement(InstallResult.serializer(), call("POST", "/api/claude/plugins/install", buildJsonObject {
            put("id", id)
            if (acceptCommand != null) put("acceptCommand", acceptCommand)
        }))

    suspend fun pluginAction(id: String, action: String, scope: String? = null): String = msg(call("POST", "/api/claude/plugins/action", buildJsonObject {
        put("id", id)
        put("action", action)
        if (scope != null) put("scope", scope)
    }))

    suspend fun marketplaceAdd(source: String): String = msg(call("POST", "/api/claude/marketplaces/add", buildJsonObject { put("source", source) }))

    suspend fun marketplaceAction(name: String, action: String): String = msg(call("POST", "/api/claude/marketplaces/action", buildJsonObject {
        put("name", name)
        put("action", action)
    }))

    private fun mg(p: String, path: String) = "/api/manage/$p$path"

    suspend fun agentSettings(p: String): AgentSettings = get(mg(p, "/settings"), AgentSettings.serializer())

    suspend fun agentSettingSet(p: String, key: String, value: String?) {
        call("POST", mg(p, "/settings/set"), buildJsonObject { put("key", key); put("value", value) })
    }

    suspend fun agentFileRead(p: String, id: String): SettingFileText =
        AppJson.decodeFromJsonElement(SettingFileText.serializer(), call("POST", mg(p, "/file/read"), buildJsonObject { put("id", id) }))

    suspend fun agentFileWrite(p: String, id: String, text: String) {
        call("POST", mg(p, "/file/write"), buildJsonObject { put("id", id); put("text", text) })
    }

    suspend fun agentMcpList(p: String): List<McpServerInfo> = get(mg(p, "/mcp"), ListSerializer(McpServerInfo.serializer()))

    suspend fun agentMcpGet(p: String, name: String): McpDetail =
        AppJson.decodeFromJsonElement(McpDetail.serializer(), call("POST", mg(p, "/mcp/get"), buildJsonObject { put("name", name) }))

    suspend fun agentMcpAdd(p: String, body: JsonObject) {
        call("POST", mg(p, "/mcp/add"), body)
    }

    suspend fun agentMcpRemove(p: String, name: String) {
        call("POST", mg(p, "/mcp/remove"), buildJsonObject { put("name", name) })
    }

    suspend fun agentMcpEnable(p: String, name: String, on: Boolean) {
        call("POST", mg(p, "/mcp/enable"), buildJsonObject { put("name", name); put("enabled", on) })
    }

    suspend fun agentSkills(p: String): List<AgentSkill> = get(mg(p, "/skills"), ListSerializer(AgentSkill.serializer()))

    suspend fun agentSkillRead(p: String, path: String): SkillText =
        AppJson.decodeFromJsonElement(SkillText.serializer(), call("POST", mg(p, "/skills/read"), buildJsonObject { put("path", path) }))

    suspend fun agentSkillCreate(p: String, name: String, description: String, body: String) {
        call("POST", mg(p, "/skills/create"), buildJsonObject { put("name", name); put("description", description); put("body", body) })
    }

    suspend fun agentSkillWrite(p: String, path: String, text: String) {
        call("POST", mg(p, "/skills/write"), buildJsonObject { put("path", path); put("text", text) })
    }

    suspend fun agentSkillDelete(p: String, path: String) {
        call("POST", mg(p, "/skills/delete"), buildJsonObject { put("path", path) })
    }

    suspend fun agentSkillEnable(p: String, path: String, on: Boolean) {
        call("POST", mg(p, "/skills/enable"), buildJsonObject { put("path", path); put("enabled", on) })
    }

    suspend fun agentPlugins(p: String): List<AgentPlugin> = get(mg(p, "/plugins"), ListSerializer(AgentPlugin.serializer()))

    suspend fun agentPluginAction(p: String, id: String, action: String): String =
        msg(call("POST", mg(p, "/plugins/action"), buildJsonObject { put("id", id); put("action", action) }))

    suspend fun skills(): List<SkillInfo> = get("/api/claude/skills", ListSerializer(SkillInfo.serializer()))

    suspend fun skillRead(path: String): SkillText =
        AppJson.decodeFromJsonElement(SkillText.serializer(), call("POST", "/api/claude/skills/read", buildJsonObject { put("path", path) }))

    suspend fun skillCreate(name: String, description: String, body: String) {
        call("POST", "/api/claude/skills/create", buildJsonObject {
            put("name", name)
            put("description", description)
            put("body", body)
        })
    }

    suspend fun skillWrite(path: String, text: String) {
        call("POST", "/api/claude/skills/write", buildJsonObject {
            put("path", path)
            put("text", text)
        })
    }

    suspend fun skillDelete(path: String) {
        call("POST", "/api/claude/skills/delete", buildJsonObject { put("path", path) })
    }

    suspend fun reportCrash(text: String) {
        call("POST", "/api/client/crash", buildJsonObject { put("text", text.take(60_000)) })
    }

    suspend fun appUpdate(expectedServer: ServerConfig? = null): AppUpdateInfo {
        val target = updateTarget(expectedServer)
        val info = callAs("GET", "/api/app", cfg = target) { AppJson.decodeFromString(AppUpdateInfo.serializer(), it) }
        updateTarget(target)
        return info
    }

    private fun updateTarget(expectedServer: ServerConfig?): ServerConfig {
        val current = base()
        if (expectedServer != null && current.connectionIdentity() != expectedServer.connectionIdentity()) {
            throw ApiException(localString(R.string.core_update_server_changed))
        }
        return expectedServer ?: current
    }

    /** onProgress は 0〜1。 */
    suspend fun downloadApk(dest: java.io.File, onProgress: (Float) -> Unit): String = downloadApk(dest, onProgress, base())

    suspend fun downloadApk(dest: java.io.File, onProgress: (Float) -> Unit, expectedServer: ServerConfig): String = withContext(Dispatchers.IO) {
        val cfg = updateTarget(expectedServer)
        val coroutineContext = currentCoroutineContext()
        val req = Request.Builder().url(cfg.url.trimEnd('/') + "/api/app/apk").auth(cfg).build()
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        val res = try {
            client.newCall(req).execute()
        } catch (e: IOException) {
            NetLog.api("GET", "/api/app/apk", null, elapsed(), null, localString(R.string.core_pc_connection_error, e.message ?: e.javaClass.simpleName))
            throw ApiException(localString(R.string.core_pc_connection_error, e.message ?: e.javaClass.simpleName))
        }
        val headersMs = elapsed()
        res.use {
            if (!it.isSuccessful) {
                val msg = runCatching { AppJson.parseToJsonElement(it.body.string()).str("error") }.getOrNull()?.let { message -> localizedServerMessage(message, strings) }
                NetLog.api("GET", "/api/app/apk", it.code, elapsed(), null, msg ?: localString(R.string.core_app_download_http_error, it.code), timing = NetTiming.fromResponse(it, headersMs, elapsed() - headersMs))
                throw ApiException(msg ?: localString(R.string.core_app_download_http_error, it.code), it.code)
            }
            val total = it.body.contentLength().takeIf { n -> n > 0 } ?: -1L
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            it.body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            NetLog.api("GET", "/api/app/apk", it.code, elapsed(), dest.length(), timing = NetTiming.fromResponse(it, headersMs, elapsed() - headersMs))
            val got = digest.digest().joinToString("") { b -> "%02x".format(b) }
            coroutineContext.ensureActive()
            updateTarget(cfg)
            val expected = it.header("x-apk-sha256")
            if (expected != null && !expected.equals(got, ignoreCase = true)) {
                dest.delete()
                throw ApiException(localString(R.string.core_app_download_corrupt))
            }
            got
        }
    }

    suspend fun history(provider: String): List<HistoryItem> = get("/api/history/$provider", ListSerializer(HistoryItem.serializer()))

    suspend fun import(provider: String, item: HistoryItem): SessionInfo {
        val body = buildJsonObject {
            put("externalId", item.externalId)
            put("title", item.title)
            put("cwd", item.cwd)
        }
        return AppJson.decodeFromJsonElement(SessionInfo.serializer(), call("POST", "/api/history/$provider/import", body))
    }

    suspend fun fs(path: String): FsListing {
        val q = java.net.URLEncoder.encode(path, "UTF-8")
        return get("/api/fs?path=$q", FsListing.serializer())
    }

    suspend fun fileInfo(link: String, sessionId: String, expectedServer: ServerConfig? = null): LinkFileInfo {
        val q = java.net.URLEncoder.encode(link, "UTF-8")
        return projectGet("/api/file?link=$q&session=$sessionId", LinkFileInfo.serializer(), expectedServer)
    }

    private fun fileDownloadRequest(target: ServerConfig, link: String, sessionId: String): Request.Builder {
        val url = (target.url.trimEnd('/') + "/api/file/raw").toHttpUrl().newBuilder()
            .addQueryParameter("link", link).addQueryParameter("session", sessionId).build()
        return Request.Builder().url(url).auth(target).header("Accept-Encoding", "identity")
    }

    internal suspend fun fileHexPage(
        link: String, sessionId: String, offset: Long, length: Int, size: Long,
        expectedServer: ServerConfig?, etag: String? = null,
    ): FileHexPage {
        val target = projectTarget(expectedServer)
        require(size >= 0 && offset >= 0 && (offset < size || size == 0L && offset == 0L))
        if (size == 0L) return FileHexPage(ByteArray(0), etag)
        require(length in 1..(1024 * 1024))
        val count = minOf(size - offset, length.toLong())
        val end = offset + (count - 1)
        val request = fileDownloadRequest(target, link, sessionId).header("Range", "bytes=$offset-$end")
            .apply { if (etag != null) header("If-Match", etag) }.build()
        return directClient.newCall(request).readHexPage(offset, end, size, etag, strings)
            .also { projectTarget(target) }
    }

    internal suspend fun downloadFileTo(
        link: String, sessionId: String, size: Long, expectedServer: ServerConfig, output: java.io.OutputStream,
    ): Unit = withContext(Dispatchers.IO) {
        val target = projectTarget(expectedServer)
        require(size >= 0)
        if (size > 60L * 1024 * 1024) {
            var offset = 0L
            var etag: String? = null
            while (offset < size) {
                currentCoroutineContext().ensureActive()
                val page = fileHexPage(link, sessionId, offset, 1024 * 1024, size, target, etag)
                // A changed snapshot must never be stitched onto the preceding bytes.
                etag = page.etag?.takeIf { it.startsWith('"') && it.endsWith('"') }
                    ?: throw ApiException(localString(R.string.core_file_http_error, 412), 412)
                output.write(page.bytes)
                offset += page.bytes.size
            }
        } else {
            val request = fileDownloadRequest(target, link, sessionId).build()
            val started = System.nanoTime()
            projectActionClient.newCall(request).execute().use { response ->
                val headersMs = (System.nanoTime() - started) / 1_000_000
                if (response.code != 200) {
                    val detail = runCatching { AppJson.parseToJsonElement(response.peekBody(2048).string()).str("error") }.getOrNull()
                    val message = detail?.let { localizedServerMessage(it, strings) } ?: localString(R.string.core_file_http_error, response.code)
                    NetLog.api("GET", "/api/file/raw", response.code, headersMs, null, message)
                    throw ApiException(message, response.code)
                }
                val length = response.body.contentLength()
                if (length >= 0 && length != size) throw ApiException(localString(R.string.core_file_http_error, 412), 412)
                val input = response.body.byteStream()
                val buffer = ByteArray(64 * 1024)
                var received = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    projectTarget(target)
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    if (received > size) throw ApiException(localString(R.string.core_file_http_error, 412), 412)
                    output.write(buffer, 0, count)
                }
                if (received != size) throw ApiException(localString(R.string.core_file_http_error, 412), 412)
                val elapsed = (System.nanoTime() - started) / 1_000_000
                NetLog.api("GET", "/api/file/raw", response.code, elapsed, received, timing = NetTiming.fromResponse(response, headersMs, elapsed - headersMs))
            }
        }
        currentCoroutineContext().ensureActive()
        projectTarget(target)
        Unit
    }

    suspend fun fileRaw(link: String, sessionId: String): ByteArray = withContext(Dispatchers.IO) {
        val cfg = base()
        val q = java.net.URLEncoder.encode(link, "UTF-8")
        val req = Request.Builder().url(cfg.url.trimEnd('/') + "/api/file/raw?link=$q&session=$sessionId").auth(cfg).build()
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        slowClient.newCall(req).execute().use {
            val headersMs = elapsed()
            if (!it.isSuccessful) {
                val msg = runCatching { (AppJson.parseToJsonElement(it.body.string()) as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull }.getOrNull()?.let { message -> localizedServerMessage(message, strings) }
                NetLog.api("GET", "/api/file/raw", it.code, elapsed(), null, msg ?: localString(R.string.core_file_http_error, it.code), timing = NetTiming.fromResponse(it, headersMs, elapsed() - headersMs))
                throw ApiException(msg ?: localString(R.string.core_file_http_error, it.code), it.code)
            }
            val bytes = it.body.bytes()
            NetLog.api("GET", "/api/file/raw", it.code, elapsed(), bytes.size.toLong(), timing = NetTiming.fromResponse(it, headersMs, elapsed() - headersMs))
            bytes
        }
    }

    /** PC の認証情報を付けず、先頭 256 KB だけ読む。 */
    suspend fun webPreview(url: String): WebPreview = withContext(Dispatchers.IO) {
        val plain = client.newBuilder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android) RemoteCodeLinkPreview").header("Accept", "text/html").build()
        plain.newCall(req).execute().use { res ->
            val finalUrl = res.request.url.toString()
            val ct = res.header("content-type").orEmpty()
            if (!res.isSuccessful || !ct.contains("html", true)) return@use WebPreview(null, null, res.request.url.host, finalUrl)
            val src = res.body.source()
            src.request(256 * 1024)
            val head = src.buffer.snapshot().utf8()
            fun meta(name: String): String? =
                Regex("""<meta[^>]+(?:property|name)\s*=\s*["']$name["'][^>]*>""", RegexOption.IGNORE_CASE).find(head)?.value
                    ?.let { Regex("""content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            fun clean(s: String?) = s?.let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim() }?.takeIf { it.isNotEmpty() }
            val title = clean(meta("og:title") ?: Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1))
            val desc = clean(meta("og:description") ?: meta("description"))
            WebPreview(title?.take(200), desc?.take(400), clean(meta("og:site_name")) ?: res.request.url.host, finalUrl)
        }
    }

    suspend fun recentDirs(): List<String> = get("/api/recent-dirs", ListSerializer(String.serializer()))


    suspend fun terminals(): List<TerminalInfo> = get("/api/terminals", ListSerializer(TerminalInfo.serializer()))

    suspend fun terminalCreate(cwd: String, title: String? = null): TerminalInfo =
        AppJson.decodeFromJsonElement(TerminalInfo.serializer(), call("POST", "/api/terminals", buildJsonObject { put("cwd", cwd); if (title != null) put("title", title) }))

    suspend fun terminalResolve(tab: String, sessionId: String): String {
        val path = "/api/terminals/resolve?tab=" + java.net.URLEncoder.encode(tab, "UTF-8") + "&session=" + java.net.URLEncoder.encode(sessionId, "UTF-8")
        return call("GET", path).jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: throw ApiException(localString(R.string.core_terminal_not_found))
    }

    suspend fun terminalWrite(id: String, data: String) {
        call("POST", "/api/terminals/$id/write", buildJsonObject { put("data", data) })
    }

    suspend fun terminalRename(id: String, title: String) {
        call("POST", "/api/terminals/$id/rename", buildJsonObject { put("title", title) })
    }

    /** scope: command はコマンド、terminal はシェルを終了。 */
    suspend fun terminalKill(id: String, scope: String = "terminal"): Int =
        call("POST", "/api/terminals/$id/kill", buildJsonObject { put("scope", scope) }).jsonObject["killed"]?.jsonPrimitive?.intOrNull ?: 0

    suspend fun terminalRemove(id: String) {
        call("POST", "/api/terminals/$id/remove")
    }

    fun terminalSocket(id: String, listener: okhttp3.WebSocketListener): okhttp3.WebSocket {
        val cfg = base()
        val req = Request.Builder().url(cfg.url.trimEnd('/').replaceFirst("http", "ws") + "/api/terminals/$id/ws").auth(cfg).build()
        return client.newWebSocket(req, listener)
    }

    fun eventsRequest(cfg: ServerConfig): Request =
        Request.Builder()
            .url(cfg.url.trimEnd('/').replaceFirst("http", "ws") + "/api/events")
            .auth(cfg)
            .build()
}

internal fun Request.Builder.auth(cfg: ServerConfig): Request.Builder = apply {
    header("Authorization", "Bearer ${cfg.token}")
    if (cfg.hasAccess) {
        header("CF-Access-Client-Id", cfg.accessId)
        header("CF-Access-Client-Secret", cfg.accessSecret)
    }
}

internal fun JsonElement.str(key: String): String? = jsonObject[key]?.jsonPrimitive?.contentOrNull
