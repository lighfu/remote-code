package dev.remotecode.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ServerDependenciesTest {
    private val pc = ServerConfig("https://remote.example", "secret", "My PC")
    private val idle = """{"supported":true,"installing":false,"dependencies":[{"target":"claude","name":"Claude Code","path":"","status":"Not installed","detail":"","installed":false},{"target":"codex","name":"Codex","path":"C:\\Tools\\codex.exe","status":"Failed","detail":"Version check failed","installed":true},{"target":"antigravity","name":"Antigravity","status":"Installed","installed":true},{"target":"cloudflared","name":"cloudflared","status":"Not installed","installed":false}]}"""

    private fun response(request: Request, text: String, code: Int = 200, location: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Response")
            .apply { if (location != null) header("Location", location) }
            .body(text.toResponseBody("application/json".toMediaType())).build()

    @Test fun readingStatusNeverInstallsAndExplicitInstallAcceptsAsyncProgress() = runBlocking {
        val calls = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            calls += "${req.method} ${req.url.encodedPath}"
            assertEquals("Bearer secret", req.header("Authorization"))
            if (req.method == "POST") {
                assertEquals("{}", Buffer().also { req.body!!.writeTo(it) }.readUtf8())
                response(req, """{"supported":true,"installing":true,"dependencies":[{"target":"claude","name":"Claude Code","status":"Installing...","detail":"Downloading with winget","installed":false}]}""", 202)
            } else response(req, idle)
        }.build()
        val api = Api(client, testAppStrings).apply { server = pc }
        val snapshot = api.dependencies(pc)
        assertEquals(listOf("GET /api/dependencies"), calls)
        assertEquals(4, snapshot.dependencies.size)
        assertTrue(snapshot.dependencies.first { it.target == "codex" }.canInstall)
        assertFalse(snapshot.dependencies.first { it.target == "antigravity" }.canInstall)
        val progress = api.installDependency("claude", pc)
        assertEquals(listOf("GET /api/dependencies", "POST /api/dependencies/claude/install"), calls)
        assertTrue(progress.installing)
        assertEquals("Downloading with winget", progress.dependencies.single().detail)
        assertFalse(progress.dependencies.single().canInstall)
    }

    @Test fun unsafeTargetsAndChangedPcAreRejectedBeforeAnyWrite() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            response(chain.request(), idle)
        }.build()
        val api = Api(client, testAppStrings).apply { server = pc }
        val unknown = runCatching { api.installDependency("../restart", pc) }.exceptionOrNull()
        assertEquals(400, (unknown as ApiException).code)
        api.server = ServerConfig("https://other.example", "other-secret", "Other PC")
        val moved = runCatching { api.installDependency("claude", pc) }.exceptionOrNull()
        assertEquals(409, (moved as ApiException).code)
        assertEquals(0, requests)
    }

    @Test fun oldStatusResponseCannotBeUsedAfterSwitchingServers() = runBlocking {
        lateinit var api: Api
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            api.server = ServerConfig("https://other.example", "other-secret", "Other PC")
            response(chain.request(), idle)
        }.build()
        api = Api(client, testAppStrings).apply { server = pc }
        val error = runCatching { api.dependencies(pc) }.exceptionOrNull()
        assertEquals(409, (error as ApiException).code)
    }

    @Test fun installDoesNotFollowRedirectsOrRetryBusyServer() = runBlocking {
        for (status in listOf(302, 409)) {
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                response(chain.request(), """{"error":"Another installation is already running"}""", status, "https://other.example/api/dependencies/claude/install")
            }.build()
            val api = Api(client, testAppStrings).apply { server = pc }
            val error = runCatching { api.installDependency("claude", pc) }.exceptionOrNull()
            assertEquals(status, (error as ApiException).code)
            assertEquals("Installation must require another explicit tap after HTTP $status", 1, requests)
        }
    }
}
