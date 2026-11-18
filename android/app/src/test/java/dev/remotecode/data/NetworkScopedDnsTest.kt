package dev.remotecode.data

import com.sun.net.httpserver.HttpServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NetworkScopedDnsTest {
    private val loopback = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))

    @Test fun repeatRouteLookupsDoNotRepeatTheResolverDelay() {
        val server = HttpServer.create(InetSocketAddress(loopback.first(), 0), 0)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Connection", "close")
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("ok".toByteArray()) }
        }
        server.start()
        var elapsed = 0L
        val resolver = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                elapsed += 3_043
                return loopback
            }
        }
        fun requests(dns: Dns): Long {
            val http = OkHttpClient.Builder().dns(dns).build()
            val req = Request.Builder().url("http://remote.test:${server.address.port}/").build()
            val started = elapsed
            try {
                repeat(3) { http.newCall(req).execute().use { assertEquals("ok", it.body.string()) } }
                return elapsed - started
            } finally {
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
            }
        }
        try {
            assertEquals(9_129, requests(resolver))
            assertEquals(3_043, requests(RecentDns({ "wifi" }, { _, host -> resolver.lookup(host) }, { elapsed })))
        } finally {
            server.stop(0)
        }
    }

    @Test fun failedExpiredAndOtherNetworkAnswersAreResolvedAgain() {
        var network = "wifi"
        var elapsed = 0L
        var lookups = 0
        val dns = RecentDns({ network }, { _, host ->
            if (++lookups == 1) throw UnknownHostException(host)
            listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, lookups.toByte())))
        }, { elapsed })
        assertThrows(UnknownHostException::class.java) { dns.lookup("remote.test") }
        val initial = dns.lookup("remote.test")
        assertEquals(initial, dns.lookup("remote.test"))
        network = "vpn"
        val vpn = dns.lookup("remote.test")
        assertNotEquals(initial, vpn)
        network = "vpn-new-DNS"
        val changed = dns.lookup("remote.test")
        assertNotEquals(vpn, changed)
        assertNotEquals(changed, dns.lookup("other.test"))
        elapsed += 15_000
        assertNotEquals(changed, dns.lookup("remote.test"))
        assertEquals(6, lookups)
    }

    @Test fun slowLookupDoesNotBlockOtherHostsAndConcurrentRequestsShareTheResult() {
        val count = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dns = RecentDns({ "wifi" }, { _, host ->
            count.incrementAndGet()
            if (host == "slow.test") {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            loopback
        }, { 0L })
        val pool = Executors.newFixedThreadPool(3)
        try {
            val first = pool.submit<List<InetAddress>> { dns.lookup("slow.test") }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val second = pool.submit<List<InetAddress>> { dns.lookup("slow.test") }
            val other = pool.submit<List<InetAddress>> { dns.lookup("other.test") }
            assertEquals(loopback, other.get(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
            assertEquals(2, count.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
