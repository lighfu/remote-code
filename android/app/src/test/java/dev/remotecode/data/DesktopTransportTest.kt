package dev.remotecode.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.remotecode.R
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class DesktopTransportTest {
    private fun withPc(
        handler: (HttpExchange) -> Unit,
        block: suspend (Api, ServerConfig) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { it.use(handler) }
        server.start()
        val client = OkHttpClient()
        val pc = ServerConfig("http://127.0.0.1:${server.address.port}", "test-token", "PC", "access-id", "access-secret")
        val api = Api(client, testAppStrings).apply { this.server = pc }
        try {
            runBlocking { block(api, pc) }
        } finally {
            server.stop(0)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun displaySelectionAndInputUseThePairedPcAndFrameLayout() {
        val display = "Side & portrait"
        val requests = CopyOnWriteArrayList<String>()
        val headers = CopyOnWriteArrayList<List<String?>>()
        val bodies = CopyOnWriteArrayList<String>()
        withPc({ exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI}"
            headers += listOf("Authorization", "CF-Access-Client-Id", "CF-Access-Client-Secret", "Cache-Control")
                .map { exchange.requestHeaders.getFirst(it) }
            when (exchange.requestURI.path) {
                "/api/desktop/displays" -> {
                    val json = """{"displays":[{"id":"$display","name":"Portrait","x":-1080,"y":0,"width":1080,"height":1920}]}""".toByteArray()
                    exchange.sendResponseHeaders(200, json.size.toLong())
                    exchange.responseBody.write(json)
                }
                "/api/desktop/frame" -> exchange.sendResponseHeaders(409, -1)
                "/api/desktop/input" -> {
                    bodies += exchange.requestBody.reader(Charsets.UTF_8).readText()
                    exchange.sendResponseHeaders(204, -1)
                }
                else -> exchange.sendResponseHeaders(404, -1)
            }
        }) { api, pc ->
            val monitor = api.desktop.displays(pc).single()
            assertEquals(display, monitor.id)
            assertEquals(-1080, monitor.x)
            val moved = runCatching { api.desktop.frame(pc, monitor.id, DesktopResolution.High) }.exceptionOrNull() as ApiException
            assertEquals(409, moved.code)
            api.desktop.input(pc, monitor.id, "current-layout", DesktopInput("text", text = "日本語🚀\nline"))
            assertEquals(listOf("GET", "GET", "POST"), requests.map { it.substringBefore(' ') })
            val frameUrl = (pc.url + requests[1].substringAfter("GET ")).toHttpUrl()
            assertEquals("/api/desktop/frame", frameUrl.encodedPath)
            assertEquals(display, frameUrl.queryParameter("display"))
            assertEquals("high", frameUrl.queryParameter("detail"))
            for (sent in headers) {
                assertEquals(listOf("Bearer test-token", "access-id", "access-secret"), sent.take(3))
                assertTrue(sent[3].orEmpty().contains("no-store"))
            }
            val expected = """{"display":"$display","layout":"current-layout","type":"text","text":"日本語🚀\nline"}"""
            assertEquals(AppJson.parseToJsonElement(expected), AppJson.parseToJsonElement(bodies.single()))
        }
    }

    @Test fun aRedirectOrRetryAfterCannotReplayAnInput() {
        val attempts = CopyOnWriteArrayList<String>()
        for (status in listOf(307, 503)) {
            attempts.clear()
            withPc({ exchange ->
                attempts += exchange.requestURI.path
                exchange.requestBody.readBytes()
                exchange.responseHeaders.set("Location", "/unexpected-target")
                exchange.responseHeaders.set("Retry-After", "0")
                exchange.sendResponseHeaders(status, -1)
            }) { api, pc ->
                val error = runCatching { api.desktop.input(pc, "all", "layout", DesktopInput("key", key = "enter")) }
                    .exceptionOrNull() as ApiException
                assertEquals(status, error.code)
                assertEquals(listOf("/api/desktop/input"), attempts.toList())
            }
        }
    }

    @Test fun inputUsesTheTimestampOfItsQueuedFrameAndExpiredActionsAreNeverReplayed() {
        val attempts = CopyOnWriteArrayList<String>()
        withPc({ exchange ->
            attempts += exchange.requestBody.reader().readText()
            val body = """{"error":"Desktop frame has expired. Wait for a fresh frame before sending input."}""".toByteArray()
            exchange.sendResponseHeaders(409, body.size.toLong())
            exchange.responseBody.write(body)
        }) { api, pc ->
            val failure = runCatching { api.desktop.input(pc, "all", "layout", DesktopInput("cursorClick"), frameAt = 4567) }.exceptionOrNull()
            assertTrue(failure is DesktopFrameExpired)
            assertEquals(AppJson.parseToJsonElement("""{"type":"cursorClick","display":"all","layout":"layout","frameAt":4567}"""),
                AppJson.parseToJsonElement(attempts.single()))
        }
    }

    @Test fun missingStreamEndpointIsDistinguishedFromATemporaryServerFailure() {
        for (status in listOf(404, 429, 503)) {
            withPc({ it.sendResponseHeaders(status, -1) }) { api, pc ->
                val error = runCatching {
                    api.desktop.stream(pc, "all", DesktopResolution.Native, "auto", "jpeg", true, 30).first()
                }.exceptionOrNull()
                assertTrue(error is java.io.IOException)
                assertEquals(status == 404, error is DesktopStreamUnsupported)
                if (status != 404) assertEquals(status, (error as DesktopStreamHttpError).status)
            }
        }
    }

    @Test fun bitrateIsSentOnlyWhenRequestedAndOlderServersKeepTheirStreamUrl() {
        val requests = CopyOnWriteArrayList<String>()
        withPc({ exchange ->
            requests += exchange.requestURI.toString()
            exchange.sendResponseHeaders(404, -1)
        }) { api, pc ->
            for (budget in listOf(null, 12_000)) {
                val error = runCatching {
                    api.desktop.stream(pc, "all", DesktopResolution.Native, "auto", "jpeg", true, 60,
                        regions = true, bitrateKbps = budget).first()
                }.exceptionOrNull()
                assertTrue(error is DesktopStreamUnsupported)
            }
            val queries = requests.map { (pc.url + it).toHttpUrl() }
            assertEquals(listOf(null, "12000"), queries.map { it.queryParameter("bitrateKbps") })
            assertTrue(queries.all { it.queryParameter("regions") == "1" && it.queryParameter("fps") == "60" })
        }
    }

    @Test fun clipboardStartsWithRevisionOnlyAndSendsUnicodeWithExpectedRevision() {
        val requests = CopyOnWriteArrayList<String>()
        var posted = ""
        withPc({ exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI}"
            assertEquals("Bearer test-token", exchange.requestHeaders.getFirst("Authorization"))
            val data = when {
                exchange.requestMethod == "POST" -> {
                    posted = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                    """{"sequence":13}"""
                }
                exchange.requestURI.query != null -> """{"sequence":12,"text":"PC 🌐"}"""
                else -> """{"sequence":11}"""
            }.toByteArray()
            exchange.sendResponseHeaders(200, data.size.toLong())
            exchange.responseBody.write(data)
        }) { api, pc ->
            val first = api.desktop.clipboard(pc)
            assertEquals(11L, first.sequence)
            assertNull(first.text)
            val changed = api.desktop.clipboard(pc, first.sequence)
            assertEquals("PC 🌐", changed.text)
            assertEquals(13L, api.desktop.copyToPc(pc, changed.sequence, "スマホ📋").sequence)
            assertEquals(AppJson.parseToJsonElement("""{"sequence":12,"text":"スマホ📋"}"""), AppJson.parseToJsonElement(posted))
            assertEquals(listOf("GET /api/desktop/clipboard", "GET /api/desktop/clipboard?after=11", "POST /api/desktop/clipboard"), requests.toList())
        }
    }

    @Test fun reconnectingWithNewCredentialsInvalidatesTheOldFrameForInput() {
        val attempts = AtomicInteger()
        withPc({ exchange ->
            attempts.incrementAndGet()
            exchange.sendResponseHeaders(204, -1)
        }) { api, pc ->
            api.server = pc.copy(token = "new-pairing")
            val error = runCatching { api.desktop.input(pc, "all", "old-layout", DesktopInput("click", DesktopPoint(0.4, 0.6))) }
                .exceptionOrNull() as ApiException
            assertEquals(testAppStrings(R.string.core_server_changed, emptyArray()), error.message)
            assertEquals(0, attempts.get())
        }
    }
}
