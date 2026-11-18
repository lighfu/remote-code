package dev.remotecode.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class DesktopAudioStreamTest {
    @Test fun preservesShortFinalPacketAndRejectsPartialSamplesOrUnexpectedAudioFormat() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pcm = ByteArray(3840 + 124) { (it % 127).toByte() }
        val raw = "audio/x-raw; format=S16LE; rate=48000; channels=2"
        val response = AtomicReference(raw to pcm)
        server.createContext("/api/desktop/audio") { exchange ->
            val (type, payload) = response.get()
            exchange.responseHeaders.add("Content-Type", type)
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val target = ServerConfig("http://127.0.0.1:${server.address.port}", "test", "fixture")
            val api = DesktopApi(OkHttpClient(), { _, _ -> "failure" }) { target }
            fun receive() = runBlocking { withTimeout(5_000) { api.audio(target).toList() } }
            val packets = receive()
            assertEquals(listOf(3840, 124), packets.map { it.size })
            assertArrayEquals(pcm, packets[0] + packets[1])
            response.set(raw to pcm.copyOf(11))
            assertThrows(IOException::class.java) { receive() }
            response.set("audio/mpeg" to pcm)
            assertThrows(DesktopStreamUnsupported::class.java) { receive() }
        } finally {
            server.stop(0)
        }
    }
}
