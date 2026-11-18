package dev.remotecode.data

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class MediaHttpCacheTest {
    @Test fun thumbnailAndExpandedViewReuseTheSameOriginalWithoutNetwork() {
        withServer { url, client, calls ->
            fun fetch() = client.newCall(Request.Builder().url(url).header("Authorization", "Bearer one").build()).execute()
            fetch().use { assertEquals(180_000, it.body.bytes().size); assertNotNull(it.networkResponse) }
            repeat(4) { fetch().use { assertEquals(180_000, it.body.bytes().size); assertNull(it.networkResponse); assertNotNull(it.cacheResponse) } }
            assertEquals("one original download instead of five", 1, calls.get())
        }
    }

    @Test fun authorizationVariantsDoNotReuseAnotherConnectionsImage() {
        withServer { url, client, calls ->
            fun fetch(token: String) = client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { it.body.bytes() }
            fetch("one"); fetch("two"); fetch("two")
            assertEquals(2, calls.get())
        }
    }

    @Test fun explicitSaveFetchesTheCurrentOriginalAndLongServerTtlIsBounded() {
        withServer { url, client, calls ->
            val request = Request.Builder().url(url).header("Authorization", "Bearer one").build()
            client.newCall(request).execute().use { assertEquals(60, it.cacheControl.maxAgeSeconds); it.body.bytes() }
            client.newCall(request.newBuilder().cacheControl(CacheControl.FORCE_NETWORK).build()).execute().use { it.body.bytes() }
            assertEquals(2, calls.get())
        }
    }

    private fun withServer(block: (String, OkHttpClient, AtomicInteger) -> Unit) {
        val directory = Files.createTempDirectory("remote-media-cache-").toFile()
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/media/image") { exchange ->
            calls.incrementAndGet()
            val bytes = ByteArray(180_000) { (it % 251).toByte() }
            exchange.responseHeaders.add("Cache-Control", "private, max-age=86400")
            exchange.responseHeaders.add("Content-Type", "image/jpeg")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val client = mediaHttpClient(OkHttpClient(), directory)
        try { block("http://127.0.0.1:${server.address.port}/api/media/image", client, calls) }
        finally {
            client.cache?.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.stop(0)
            directory.deleteRecursively()
        }
    }
}
