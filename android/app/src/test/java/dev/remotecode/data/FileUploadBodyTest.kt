package dev.remotecode.data

import kotlinx.coroutines.CancellationException
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class FileUploadBodyTest {
    @Test
    fun streamsBytesWithoutDependingOnReportedSizeAndClosesInput() {
        val bytes = ByteArray(40_000) { (it % 251).toByte() }
        var closed = false
        val input = object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } }
        val progress = mutableListOf<Long>()
        val body = FileUploadBody(strings = testAppStrings, open = { input }, onProgress = { progress.add(it) })
        val sink = Buffer()
        body.writeTo(sink)
        assertEquals(-1L, body.contentLength())
        assertTrue(body.isOneShot())
        assertTrue(bytes.contentEquals(sink.readByteArray()))
        assertEquals(bytes.size.toLong(), progress.last())
        assertTrue(closed)
    }

    @Test
    fun rejectsAnOversizedUnknownLengthSourceBeforeWritingBeyondLimit() {
        var closed = false
        val input = object : ByteArrayInputStream(ByteArray(12)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, minOf(4, length))
            override fun close() { closed = true; super.close() }
        }
        val sink = Buffer()
        val body = FileUploadBody(strings = testAppStrings, open = { input }, onProgress = {}, maximum = 8)
        assertThrows(IOException::class.java) { body.writeTo(sink) }
        assertEquals(8L, sink.size)
        assertTrue(closed)
    }

    @Test
    fun cancellationStopsBeforeReadingTheNextChunkAndReleasesTheFile() {
        var closed = false
        var checks = 0
        val input = object : ByteArrayInputStream(ByteArray(40_000)) { override fun close() { closed = true; super.close() } }
        val body = FileUploadBody(strings = testAppStrings, open = { input }, onProgress = {}, checkActive = {
            if (++checks > 1) throw CancellationException("cancelled")
        })
        val sink = Buffer()
        assertThrows(CancellationException::class.java) { body.writeTo(sink) }
        assertEquals(16L * 1024, sink.size)
        assertTrue(closed)
    }
}
