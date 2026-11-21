package dev.remotecode.data

import dev.remotecode.R
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.blackholeSink
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

class AttachmentUploadTest {
    private val server = ServerConfig("https://pc.example", "token", "PC")
    private val uploadId = "ec15f555-962d-46a8-9da9-2237ab7cf7a2"
    private val chunkPath = "/api/uploads/chunked"

    @Test fun sendsMoreThanFiftyMiBInFixedLengthChunksAndPublishesAtEof() = runBlocking {
        val size = LEGACY_ATTACHMENT_BYTES + 137
        val offsets = mutableListOf<Long>()
        val counts = mutableListOf<Long>()
        val progress = mutableListOf<Long>()
        val requests = mutableListOf<Request>()
        val uploader = uploadWith { request ->
            requests.add(request)
            if (request.method == "POST") {
                reply(request, 200, """{"id":"$uploadId"}""")
            } else {
                val offset = request.url.queryParameter("offset")!!.toLong()
                val count = request.body!!.contentLength()
                offsets.add(offset)
                counts.add(count)
                blackholeSink().buffer().use { request.body!!.writeTo(it) }
                if (request.url.queryParameter("final") == "true") {
                    reply(request, 200, """{"id":"$uploadId","size":$offset}""")
                } else {
                    reply(request, 200, """{"offset":${offset + count}}""")
                }
            }
        }

        val uploaded = uploader.upload(server, "large file.bin", "application/octet-stream", size, { generated(size) }) { progress.add(it) }
        assertEquals(uploadId, uploaded.id)
        assertEquals(size, uploaded.size)
        assertEquals(chunkPath, requests.first().url.encodedPath)
        assertEquals(size.toString(), requests.first().url.queryParameter("size"))
        assertEquals("large file.bin", requests.first().url.queryParameter("name"))
        assertEquals("$chunkPath/$uploadId", requests.last().url.encodedPath)
        assertEquals("true", requests.last().url.queryParameter("final"))
        assertEquals(0L, counts.last())
        assertTrue(counts.dropLast(1).all { it in 1L..ATTACHMENT_CHUNK_BYTES.toLong() })
        assertEquals(size, counts.sum())
        assertEquals(counts.runningFold(0L, Long::plus).dropLast(1), offsets)
        assertTrue(progress.zipWithNext().all { (previous, current) -> previous <= current })
        assertEquals(size, progress.last())
    }

    @Test fun unknownSizeFallsBackToLegacyAfterAnOldServerRejectsChunkedInit() = runBlocking {
        val requests = mutableListOf<Request>()
        val content = byteArrayOf(1, 2, 3)
        var sourceOpens = 0
        val uploader = uploadWith { request ->
            requests.add(request)
            if (request.url.encodedPath == chunkPath) reply(request, 404, "")
            else {
                blackholeSink().buffer().use { request.body!!.writeTo(it) }
                reply(request, 200, """{"id":"$uploadId","size":3}""")
            }
        }

        val result = uploader.upload(server, "small.bin", "application/octet-stream", null, {
            sourceOpens++
            ByteArrayInputStream(content)
        }, {})
        assertEquals(3L, result.size)
        assertEquals(1, sourceOpens)
        assertEquals(listOf(chunkPath, "/api/uploads"), requests.map { it.url.encodedPath })
        assertEquals(null, requests.first().url.queryParameter("size"))
    }

    @Test fun aKnownLargeFileRequiresServerUpdateRatherThanPostingToLegacy() = runBlocking {
        val requests = mutableListOf<Request>()
        val uploader = uploadWith { request -> requests.add(request); reply(request, 405, "") }
        val error = runCatching {
            uploader.upload(server, "large.bin", "application/octet-stream", LEGACY_ATTACHMENT_BYTES + 1, { error("opened") }, {})
        }.exceptionOrNull() as ApiException

        assertEquals(405, error.code)
        assertEquals(testAppStrings(R.string.core_large_upload_server_update_required, emptyArray()), error.message)
        assertEquals(listOf(chunkPath), requests.map { it.url.encodedPath })
    }

    @Test fun switchingServersAbortsThePartialUploadOnlyOnItsOriginalServer() = runBlocking {
        val active = AtomicReference(server)
        val requests = mutableListOf<Request>()
        val uploader = uploadWith(active::get) { request ->
            requests.add(request)
            if (request.method == "POST") {
                active.set(ServerConfig("https://other.example", "other-token", "Other PC"))
                reply(request, 200, """{"id":"$uploadId"}""")
            } else reply(request, 204, "")
        }

        val error = runCatching {
            uploader.upload(server, "file.bin", "application/octet-stream", null, { generated(1) }, {})
        }.exceptionOrNull() as ApiException
        assertEquals(testAppStrings(R.string.core_send_server_changed, emptyArray()), error.message)
        assertEquals(listOf("POST", "DELETE"), requests.map { it.method })
        assertEquals(listOf("pc.example", "pc.example"), requests.map { it.url.host })
        assertEquals("$chunkPath/$uploadId", requests.last().url.encodedPath)
    }

    private fun uploadWith(active: () -> ServerConfig? = { server }, respond: (Request) -> Response): AttachmentUpload {
        val client = OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build()
        return AttachmentUpload(client, testAppStrings, active)
    }

    private fun reply(request: Request, status: Int, body: String): Response =
        Response.Builder().request(request).code(status).message("Test").protocol(Protocol.HTTP_1_1)
            .body(body.toResponseBody("application/json".toMediaType())).build()

    private fun generated(size: Long): InputStream = object : InputStream() {
        private var remaining = size
        override fun read(): Int {
            if (remaining == 0L) return -1
            remaining--
            return 7
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) return -1
            val count = minOf(length.toLong(), remaining).toInt()
            bytes.fill(7, offset, offset + count)
            remaining -= count
            return count
        }
    }
}
