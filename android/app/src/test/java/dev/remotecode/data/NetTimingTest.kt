package dev.remotecode.data

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class NetTimingTest {
    @Test fun recordsRuntimeAndCompressedTransferSizeWithoutChangingTheElapsedPhases() {
        val request = Request.Builder().url("http://127.0.0.1/test").build()
        val network = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Content-Length", "42")
            .header("Server-Timing", "app;dur=3.5")
            .header("X-Remote-Code-Version", "0.9.2")
            .header("X-Remote-Code-Started-At", "1700000000000")
            .header("X-Remote-Code-Instance", "instance").build()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Server-Timing", "app;dur=3.5")
            .header("X-Remote-Code-Version", "0.9.2")
            .header("X-Remote-Code-Started-At", "1700000000000")
            .header("X-Remote-Code-Instance", "instance")
            .networkResponse(network).body("uncompressed contents".toResponseBody()).build()
        response.use {
            val timing = NetTiming.fromResponse(it, 120, 15)
            assertEquals(120L, timing.headersMs)
            assertEquals(15L, timing.bodyMs)
            assertEquals(3.5, timing.serverMs!!, 0.0)
            assertEquals(42L, timing.wireBytes)
            assertEquals("0.9.2", timing.serverVersion)
            assertEquals(1700000000000L, timing.serverStartedAt)
            assertEquals("instance", timing.serverInstance)
        }
    }

    @Test fun localCacheHitsDoNotReuseThePreviousServersTimingAsANewMeasurement() {
        val request = Request.Builder().url("http://127.0.0.1/test").build()
        val cached = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").build()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Server-Timing", "app;dur=5000,handler;dur=100,json;dur=2,gzip;dur=9").header("X-Remote-Code-Version", "old")
            .cacheResponse(cached).body("cached contents".toResponseBody()).build()
        response.use {
            val timing = NetTiming.fromResponse(it, 2, 1)
            assertTrue(timing.cacheHit)
            assertNull(timing.serverMs)
            assertNull(timing.serverHandlerMs)
            assertNull(timing.serverJsonMs)
            assertNull(timing.serverGzipMs)
            assertNull(timing.serverVersion)
            assertEquals(0L, timing.wireBytes)
        }
    }

    @Test fun serverTimeIsIndependentOfOtherMetricsAndQuotedDescriptions() {
        assertEquals(12.5, serverDurationMs("db;dur=4, app;dur=12.5")!!, 0.001)
        assertEquals(31.25, serverDurationMs("app;desc=\"lookup, render; send\";dur=31.25, cache;desc=\"hit\"")!!, 0.001)
        assertEquals(2.0, serverDurationMs("APP; DUR = 2")!!, 0.001)
    }

    @Test fun oldServersOrInvalidDurationsAreNotInventedAsZero() {
        for (header in listOf(null, "", "db;dur=4", "app", "app;dur=", "app;dur=bad", "app;dur=-1", "app;dur=NaN", "app;dur=Infinity")) {
            assertNull(header, serverDurationMs(header))
        }
        assertEquals(0.0, serverDurationMs("app;dur=0")!!, 0.0)
    }

    @Test fun fileLinksHaveTheirOwnLogCategory() {
        assertEquals("file", NetLog.featureOf("GET", "/api/file?link=a.ts"))
        assertEquals("file", NetLog.featureOf("GET", "/api/file/raw?link=a.ts"))
        assertEquals("media", NetLog.featureOf("GET", "/api/media/image"))
        assertEquals("terminals", NetLog.featureOf("GET", "/api/terminals?cwd=project"))
    }

    @Test fun serverPhasesAreParsedIndependentlyWithoutReusingCachedMeasurements() {
        val header = "gzip;dur=10.5, app;desc=\"read, build; send\";dur=14.5, handler;dur=3, json;dur=1"
        assertEquals(14.5, serverDurationMs(header)!!, 0.0)
        assertEquals(3.0, serverDurationMs(header, "handler")!!, 0.0)
        assertEquals(1.0, serverDurationMs(header, "json")!!, 0.0)
        assertEquals(10.5, serverDurationMs(header, "gzip")!!, 0.0)
        assertNull(serverDurationMs("app;dur=5", "gzip"))
        assertNull(serverDurationMs("json;dur=-1, app;dur=2", "json"))
        val request = Request.Builder().url("http://127.0.0.1/test").build()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Server-Timing", header).body("{}".toResponseBody()).build()
        response.use {
            val timing = NetTiming.fromResponse(it, 30, 1)
            assertEquals(3.0, timing.serverHandlerMs!!, 0.0)
            assertEquals(1.0, timing.serverJsonMs!!, 0.0)
            assertEquals(10.5, timing.serverGzipMs!!, 0.0)
        }
    }
}
