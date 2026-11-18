package dev.remotecode.data

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class HttpTraceTest {
    @Test fun executionQueueTimeDoesNotBecomeDnsOrConnectionTime() {
        var clock = 1_000_000L
        val trace = HttpTrace { clock }
        clock += 87_000_000L
        trace.dispatched()
        val timing = trace.snapshot()
        assertEquals(87L, timing.queueMs)
        assertNull(timing.dnsMs)
        assertNull(timing.connectMs)
        assertNull(timing.tlsMs)
        assertNull(timing.connectionWaitMs)
        assertNull(timing.reusedConnection)
    }

    @Test fun separateRequestsReportNewAndReusedConnectionsWithoutReusingOldMeasurements() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/trace") { exchange ->
            exchange.responseHeaders.add("Server-Timing", "app;dur=1.25")
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        server.start()
        val client = OkHttpClient.Builder()
            .eventListenerFactory(HttpTraceFactory)
            .dns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1")) })
            .build()
        fun read(): NetTiming {
            val trace = HttpTrace().apply { dispatched() }
            val request = Request.Builder().url("http://trace.test:${server.address.port}/trace").tag(HttpTrace::class.java, trace).build()
            return client.newCall(request).execute().use { response ->
                response.body.string()
                NetTiming.fromResponse(response, 100, 2)
            }
        }
        try {
            val first = read()
            val second = read()
            assertEquals(false, first.reusedConnection)
            assertNotNull(first.dnsMs)
            assertNotNull(first.connectMs)
            assertNull(first.tlsMs)
            assertEquals(1.25, first.serverMs!!, 0.0)
            assertEquals(true, second.reusedConnection)
            assertNull(second.dnsMs)
            assertNull(second.connectMs)
            assertNotNull(second.connectionWaitMs)
            assertNotNull(second.queueMs)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.stop(0)
        }
    }
}
