package dev.remotecode.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class FileHexPageTest {
    private val pc = ServerConfig("https://remote.example", "secret", "PC")
    private val path = "src/日本語 #1&file.bin"
    private val validator = "\"version1\""

    private fun api(reply: (Request) -> Response): Api {
        val client = OkHttpClient.Builder().addInterceptor { reply(it.request()) }.build()
        return Api(client, testAppStrings).apply { server = pc }
    }

    private fun reply(req: Request, range: String, bytes: ByteArray, status: Int = 206): Response =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).message("Test").code(status)
            .header("Content-Range", range).header("ETag", validator).body(bytes.toResponseBody()).build()

    @Test fun boundedTailSupportsLongOffsetsAndEscapedPaths() = runBlocking {
        val pos = Long.MAX_VALUE - 2
        val api = api { req ->
            assertEquals(path, req.url.queryParameter("link"))
            assertEquals("s&1", req.url.queryParameter("session"))
            assertEquals("Bearer secret", req.header("Authorization"))
            assertEquals("bytes=$pos-${pos + 1}", req.header("Range"))
            assertEquals(validator, req.header("If-Match"))
            reply(req, "bytes $pos-${pos + 1}/${Long.MAX_VALUE}", byteArrayOf(-1, 0))
        }
        val result = api.fileHexPage(path, "s&1", pos, 4096, Long.MAX_VALUE, pc, validator)
        assertArrayEquals(byteArrayOf(-1, 0), result.bytes)
        assertEquals(validator, result.etag)
    }

    @Test fun neverTrustsWholeFileWrongRangesExtraBytesOrStaleMetadata() = runBlocking {
        val responses = listOf(
            Triple(200, "", byteArrayOf(0, 1)),
            Triple(206, "bytes 1-2/3", byteArrayOf(0, 1)),
            Triple(206, "bytes 0-1/3", byteArrayOf(0, 1)),
            Triple(206, "bytes 0-1/2", byteArrayOf(0)),
            Triple(206, "bytes 0-1/2", byteArrayOf(0, 1, 2)),
            Triple(412, "", byteArrayOf()),
        )
        for ((status, range, bytes) in responses) {
            val api = api { reply(it, range, bytes, status) }
            val failure = runCatching { api.fileHexPage(path, "s", 0, 2, 2L, pc) }.exceptionOrNull()
            assertTrue("HTTP $status, $range, ${bytes.size} bytes", failure is ApiException)
        }
    }

    @Test fun oldServersWithoutEtagAndEmptyFilesAreReadable() = runBlocking {
        var calls = 0
        val api = api {
            calls++
            reply(it, "bytes 0-1/2", byteArrayOf(0, 1)).newBuilder().removeHeader("ETag").build()
        }
        assertArrayEquals(byteArrayOf(), api.fileHexPage(path, "s", 0, 16, 0L, pc).bytes)
        val page = api.fileHexPage(path, "s", 0, 16, 2L, pc)
        assertArrayEquals(byteArrayOf(0, 1), page.bytes)
        assertNull(page.etag)
        assertEquals(1, calls)
    }

    @Test fun serverSwitchDuringPageReadRejectsOldBytes() = runBlocking {
        lateinit var subject: Api
        subject = api {
            subject.server = ServerConfig("https://other.example", "other", "Other PC")
            reply(it, "bytes 0-1/2", byteArrayOf(0, 1))
        }
        val failure = runCatching { subject.fileHexPage(path, "s", 0, 16, 2L, pc) }.exceptionOrNull()
        assertEquals(409, (failure as ApiException).code)
    }

    @Test fun smallDownloadsUseOneRequestAndRejectIncompleteOrSwitchedFiles() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        var calls = 0
        val subject = api { req ->
            assertNull(req.header("Range"))
            assertEquals(path, req.url.queryParameter("link"))
            calls++
            reply(req, "", bytes, 200)
        }
        val out = java.io.ByteArrayOutputStream()
        subject.downloadFileTo(path, "s", bytes.size.toLong(), pc, out)
        assertArrayEquals(bytes, out.toByteArray())
        assertEquals(1, calls)
        val short = runCatching { subject.downloadFileTo(path, "s", 4L, pc, out) }.exceptionOrNull()
        assertEquals(412, (short as ApiException).code)
        subject.server = ServerConfig("https://other.example", "other", "Other")
        val changed = runCatching { subject.downloadFileTo(path, "s", 3L, pc, out) }.exceptionOrNull()
        assertEquals(409, (changed as ApiException).code)
        assertEquals(2, calls)
    }

    @Test fun largeDownloadsPinARevisionAndWriteBoundedChunksThroughTheTail() = runBlocking {
        val chunk = ByteArray(1024 * 1024)
        val total = 60L * chunk.size + 3
        var seen = 0L
        var written = 0L
        val subject = api { req ->
            val count = minOf(chunk.size.toLong(), total - seen).toInt()
            val end = seen + count - 1
            assertEquals("bytes=$seen-$end", req.header("Range"))
            assertEquals(if (seen == 0L) null else validator, req.header("If-Match"))
            val range = "bytes $seen-$end/$total"
            seen += count
            reply(req, range, if (count == chunk.size) chunk else chunk.copyOf(count))
        }
        val sink = object : java.io.OutputStream() {
            override fun write(value: Int) = error("Expected a bounded buffer")
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                assertTrue(length <= chunk.size)
                written += length
            }
        }
        subject.downloadFileTo(path, "s", total, pc, sink)
        assertEquals(total, written)
        assertEquals(total, seen)
    }
}
