package dev.remotecode.data

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class MediaPlaybackTest {
    @Test fun playbackKeepsCredentialsInHeadersAndDecodesWindowsVideoNames() {
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString("C:\\録画\\sample.mp4".toByteArray())
        val source = mediaPlaybackSource(ServerConfig("https://remote.example/", "private-token", "PC", "client", "secret"), id)
        assertEquals("https://remote.example/api/media/$id", source.url)
        assertFalse(source.url.contains("private-token"))
        assertEquals("Bearer private-token", source.headers["Authorization"])
        assertEquals("client", source.headers["CF-Access-Client-Id"])
        assertEquals("secret", source.headers["CF-Access-Client-Secret"])
        assertEquals("sample.mp4", videoFileName(id))
        assertEquals("video/mp4", videoMime(videoFileName(id)))
        assertFalse(mediaPlaybackSource(ServerConfig("http://127.0.0.1:8787", "usb", "PC"), id).headers.containsKey("CF-Access-Client-Id"))
        assertTrue(runCatching { mediaPlaybackSource(ServerConfig("https://remote.example", "token", "PC"), "../file", invalidIdMessage = { "invalid-id" }) }.isFailure)
    }

    @Test fun videoPlaybackCopyUsesTheOriginalConnectionAndPreservesHttpErrors() = runBlocking {
        val requests = ConcurrentLinkedQueue<List<String?>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/media/") { exchange ->
            requests.add(listOf(
                exchange.requestMethod,
                exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestHeaders.getFirst("CF-Access-Client-Id"),
                exchange.requestHeaders.getFirst("CF-Access-Client-Secret"),
            ))
            val status = when (exchange.requestURI.path.substringAfterLast('/')) {
                "missing" -> 404
                "available" -> 200
                else -> 503
            }
            val payload = if (status == 200) byteArrayOf(10, 20, 30) else byteArrayOf()
            exchange.sendResponseHeaders(status, if (payload.isEmpty()) -1L else payload.size.toLong())
            if (payload.isNotEmpty()) exchange.responseBody.write(payload)
            exchange.close()
        }
        server.start()
        val client = OkHttpClient()
        val api = Api(strings = testAppStrings, client = client).apply {
            this.server = ServerConfig("http://127.0.0.1:${server.address.port}", "video-token", "PC", "client", "secret")
        }
        try {
            val missing = api.mediaPlayback("missing")
            val available = api.mediaPlayback("available")
            val unavailable = api.mediaPlayback("unavailable")
            api.server = null
            withTimeout(5_000) {
                val output = ByteArrayOutputStream()
                assertEquals(3L, api.copyMediaTo(available, output))
                assertArrayEquals(byteArrayOf(10, 20, 30), output.toByteArray())
                assertEquals(404, (runCatching { api.copyMediaTo(missing, output) }.exceptionOrNull() as? ApiException)?.code)
                assertEquals(503, (runCatching { api.copyMediaTo(unavailable, output) }.exceptionOrNull() as? ApiException)?.code)
            }
            assertEquals(List(3) { listOf("GET", "Bearer video-token", "client", "secret") }, requests.toList())
        } finally {
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); server.stop(0)
        }
    }

    @Test fun videoDownloadStreamsTheOriginalWithAuthentication() = runBlocking {
        val bytes = ByteArray(350_000) { (it % 251).toByte() }
        var authorization: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/media/video") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val client = OkHttpClient()
        val api = Api(strings = testAppStrings, client = client).apply { this.server = ServerConfig("http://127.0.0.1:${server.address.port}", "video-token", "PC") }
        val output = ByteArrayOutputStream()
        try {
            assertEquals(bytes.size.toLong(), withTimeout(5_000) { api.copyMediaTo("video", output) })
            assertArrayEquals(bytes, output.toByteArray())
            assertEquals("Bearer video-token", authorization)
        } finally {
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); server.stop(0)
        }
    }

    @Test fun closingTheViewerCancelsAnInProgressVideoDownload() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/media/video") { exchange ->
            runCatching {
                exchange.sendResponseHeaders(200, 1_000_000)
                exchange.responseBody.use { output ->
                    repeat(250) { output.write(ByteArray(4_000)); output.flush(); Thread.sleep(5) }
                }
            }
            exchange.close()
        }
        server.start()
        val client = OkHttpClient()
        val api = Api(strings = testAppStrings, client = client).apply { this.server = ServerConfig("http://127.0.0.1:${server.address.port}", "video-token", "PC") }
        val firstChunk = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        val output = object : java.io.OutputStream() {
            override fun write(value: Int) { count.incrementAndGet(); firstChunk.complete(Unit) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { count.addAndGet(length); firstChunk.complete(Unit) }
        }
        try {
            val download = launch { api.copyMediaTo("video", output) }
            withTimeout(5_000) { firstChunk.await() }
            download.cancelAndJoin()
            withTimeout(3_000) { while (client.dispatcher.runningCallsCount() > 0) delay(10) }
            assertTrue("cancel should stop before the entire video is downloaded", count.get() in 1 until 1_000_000)
        } finally {
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); server.stop(0)
        }
    }
}
