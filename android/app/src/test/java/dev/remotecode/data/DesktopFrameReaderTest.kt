package dev.remotecode.data

import java.io.IOException
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class DesktopFrameReaderTest {
    private fun part(body: String, tag: String, cursor: String = "hidden", position: String? = null,
                     layout: String = "one", length: Int = body.length, frameAt: Long = 1000, extra: String = ""): String =
        "--rc-frame\r\nContent-Type: " + (if (body.isEmpty()) "application/octet-stream" else "image/jpeg") +
            "\r\nContent-Length: $length\r\nETag: \"$tag\"\r\nX-Remote-Code-Desktop-Layout: $layout" +
            "\r\nX-Remote-Code-Desktop-Cursor: $cursor\r\nX-Remote-Code-Desktop-Cursor-Revision: 9" +
            "\r\nX-Remote-Code-Desktop-Frame-At: $frameAt" +
            (position?.let { "\r\nX-Remote-Code-Desktop-Cursor-Position: $it" } ?: "") + extra + "\r\n\r\n$body"

    private fun reader(vararg parts: String, regions: Boolean = false, onPayloadBytes: (Int) -> Unit = {}) =
        DesktopFrameReader(Buffer().writeUtf8(parts.joinToString("\r\n")), "rc-frame", "jpeg", 1024, regions, onPayloadBytes)

    private val region = "\r\nX-Remote-Code-Desktop-Region: 512,0,256,128"
    private val base = "\r\nX-Remote-Code-Desktop-Base-ETag: \"1\""

    @Test fun validatesPatchesAgainstThePreviousImageAndAcceptsAtomicAtlasUpdates() {
        val atlas = "\r\nX-Remote-Code-Desktop-Regions: 0,0,128,64,0,0;512,512,128,64,0,64"
        val wire = reader(part("key", "1"), part("patch", "2", extra = region + base), part("", "2"),
            part("atlas", "3", extra = atlas + base.replace("\"1\"", "\"2\"")), part("new-key", "4"), regions = true)
        assertTrue(wire.next { 0 }.regions.isEmpty())
        val patch = wire.next { 1 }
        assertEquals(listOf(DesktopRegion(512, 0, 256, 128)), patch.regions)
        assertSame(patch.bytes, wire.next { 2 }.bytes)
        assertEquals(listOf(DesktopRegion(0, 0, 128, 64), DesktopRegion(512, 512, 128, 64, 0, 64)), wire.next { 3 }.regions)
        assertTrue(wire.next { 4 }.regions.isEmpty())
    }

    @Test fun rejectsUnnegotiatedOrDiscontinuousDesktopPatches() {
        val patch = part("patch", "2", extra = region + base)
        assertThrows(IOException::class.java) { reader(patch, regions = true).next { 0 } }
        for ((first, update, allow) in listOf(
            Triple(part("key", "1"), patch, false),
            Triple(part("key", "3"), patch, true),
            Triple(part("key", "2"), part("patch", "2", extra = region + base.replace("\"1\"", "\"2\"")), true),
            Triple(part("key", "1", layout = "old"), patch, true),
            Triple(part("key", "1"), part("", "1", extra = region + base), true),
            Triple(part("key", "1"), part("patch", "2", extra = region), true),
        )) {
            val wire = reader(first, update, regions = allow)
            wire.next { 0 }
            assertThrows(IOException::class.java) { wire.next { 1 } }
        }
    }

    @Test fun rejectsOverflowAmbiguityAndOverlappingAtlasRectangles() {
        for (value in listOf("-1,0,1,1", "0,0,0,1", "2147483647,0,100,1", "1,2,3,4,5")) {
            assertThrows(IOException::class.java) { DesktopRegion.parse(value, null) }
        }
        for (value in listOf("0,0,8,8,0,0", "0,0,8,8,0,0;7,7,8,8,8,0", "0,0,8,8,0,0;8,0,8,8,7,7")) {
            assertThrows(IOException::class.java) { DesktopRegion.parse(null, value) }
        }
        assertThrows(IOException::class.java) { DesktopRegion.parse("0,0,1,1", "0,0,1,1,0,0;1,0,1,1,1,0") }
        val region = DesktopRegion(500, 700, 50, 10, 0, 30)
        assertTrue(region.fits(550, 710))
        assertFalse(region.fits(549, 710))
        assertTrue(region.fitsSource(50, 40))
        assertFalse(region.fitsSource(50, 39))
    }

    @Test fun overlayReplacementUsesDesktopCoordinatesAndRequiresFullCoverage() {
        val previous = DesktopRegion(512, 398, 256, 128, 128, 0)
        assertTrue(DesktopRegion(512, 398, 256, 128, 0, 64).covers(previous))
        assertTrue(DesktopRegion(511, 397, 260, 130).covers(previous))
        assertFalse(DesktopRegion(511, 398, 256, 128).covers(previous))
        assertFalse(DesktopRegion(512, 400, 256, 128).covers(previous))
    }

    @Test fun partialOverlapPreservesExactlyTheVisibleAtlasPixelsAcrossChangingRectangleShapes() {
        val original = DesktopRegion(31, 19, 120, 80, 7, 13)
        val random = java.util.Random(2834)
        repeat(80) {
            val cuts = List(8) { DesktopRegion(random.nextInt(160), random.nextInt(120),
                1 + random.nextInt(80), 1 + random.nextInt(55)) }
            val visible = original.uncoveredBy(cuts)
            val pixels = BooleanArray(original.width * original.height)
            for (piece in visible) {
                assertTrue(original.covers(piece))
                assertEquals(original.sourceX + piece.x - original.x, piece.sourceX)
                assertEquals(original.sourceY + piece.y - original.y, piece.sourceY)
                for (y in piece.y until piece.y + piece.height) for (x in piece.x until piece.x + piece.width) {
                    val index = (y - original.y) * original.width + x - original.x
                    assertFalse(pixels[index])
                    pixels[index] = true
                }
            }
            for (y in 0 until original.height) for (x in 0 until original.width) {
                val covered = cuts.any { cut ->
                    original.x + x in cut.x until cut.x + cut.width && original.y + y in cut.y until cut.y + cut.height
                }
                assertEquals(!covered, pixels[y * original.width + x])
            }
        }
    }

    @Test fun congestedDecoderSkipsOnlySupersededPixelsAndIncludesEveryBodyInThroughput() {
        val other = "\r\nX-Remote-Code-Desktop-Region: 0,0,128,128"
        fun basedOn(n: Int) = "\r\nX-Remote-Code-Desktop-Base-ETag: \"$n\""
        val arrivals = mutableListOf<Int>()
        val wire = reader(part("full", "1"), part("skip", "2", extra = region + basedOn(1)),
            part("", "2"), part("latest", "3", extra = region + basedOn(2)),
            part("other", "4", extra = other + basedOn(3)), part("", "4"), regions = true,
            onPayloadBytes = { arrivals.add(it) })
        val slots = mutableListOf<DesktopPacketSlot>()
        repeat(6) { tick ->
            val packet = wire.next { tick.toLong() }
            if (slots.lastOrNull()?.replace(packet) != true) slots.add(DesktopPacketSlot(packet))
        }
        assertEquals(listOf(4, 4, 6, 5), arrivals)
        val frames = slots.map { it.take() }
        assertEquals(listOf("full", "latest", "other"), frames.map { it.bytes.decodeToString() })
        assertEquals(listOf(null, "\"1\"", "\"3\""), frames.map { it.baseETag })
        assertEquals(listOf(4L, 14L, 19L), frames.map { it.receivedPayloadBytes })
        assertEquals(5L, frames.last().receivedAtMs)
    }

    @Test fun readerCanDrainAFullBurstWhileDecoderIsOccupiedWithoutOverwritingAClaimedPacket() {
        val updates = (2..101).map { tag -> part("j", "$tag", extra = region + base.replace("\"1\"", "\"${tag - 1}\"")) }
        val wire = reader(part("key", "1"), *updates.toTypedArray(), regions = true)
        val key = DesktopPacketSlot(wire.next { 0 })
        val original = wire.next { 1 }
        val queued = DesktopPacketSlot(original)
        for (index in 1 until updates.size) assertTrue(queued.replace(wire.next { index.toLong() + 1 }))
        val newest = queued.take()
        assertEquals(key.take().headers.etag, newest.baseETag)
        assertEquals("\"101\"", newest.headers.etag)
        assertEquals(103L, newest.receivedPayloadBytes)
        assertFalse(queued.replace(original))
    }

    @Test fun congestedDecoderKeepsPartiallyReplacedImagesAndMaySkipThroughAnIndependentKeyframe() {
        val wire = reader(part("a", "1"), part("old", "2", extra = region + base),
            part("new", "3", extra = region.replace("512,0,256,128", "512,0,128,128") + base.replace("\"1\"", "\"2\"")),
            part("reset", "4"), regions = true)
        val key = wire.next { 0 }
        val prior = wire.next { 1 }
        val partial = wire.next { 2 }
        val reset = wire.next { 3 }
        assertNull(key.replacedBy(prior))
        assertNull(prior.replacedBy(partial))
        assertSame(reset, partial.replacedBy(reset))
        assertEquals(12L, reset.receivedPayloadBytes)
    }

    @Test fun idleAndCursorOnlyUpdatesKeepTheMostRecentCompleteImage() {
        val wire = reader(part("one", "1"), part("", "1", "0.25,0.75", frameAt = 1100), part("two", "2", "embedded", "0.5,0.5"), part("", "2"))
        val first = wire.next { 1 }
        val pointer = wire.next { 2 }
        assertSame(first.bytes, pointer.bytes)
        assertEquals(1100L, pointer.headers.frameAt)
        assertEquals(DesktopCursor(DesktopPoint(.25, .75), revision = 9), pointer.headers.cursor)
        val changed = wire.next { 3 }
        assertEquals("two", changed.bytes.decodeToString())
        assertEquals(DesktopCursor(DesktopPoint(.5, .5), embedded = true, revision = 9), changed.headers.cursor)
        val idle = wire.next { 4 }
        assertSame(changed.bytes, idle.bytes)
        assertEquals(4L, idle.receivedAtMs)
        assertEquals(DesktopCursor(revision = 9), idle.headers.cursor)
    }

    @Test fun cannotApplyUnchangedToAnUnseenImageOrDifferentLayout() {
        assertThrows(IOException::class.java) { reader(part("", "unknown")).next { 0 } }
        for (validation in listOf(part("", "2"), part("", "1", layout = "two"))) {
            val wire = reader(part("one", "1"), validation)
            wire.next { 0 }
            assertThrows(IOException::class.java) { wire.next { 0 } }
        }
    }

    @Test fun rejectsUnboundedPayloadsAndMalformedCoordinates() {
        assertThrows(IOException::class.java) { reader(part("x", "1", length = Int.MAX_VALUE)).next { 0 } }
        assertThrows(IOException::class.java) { reader(part("x", "1", "NaN,1")).next { 0 } }
    }

    @Test fun nativeFrameBoundsUseTheAreaOfTheVirtualDesktop() {
        assertTrue(supportsFrameDimensions(7680, 2160, true))
        assertTrue(supportsFrameDimensions(16_384, 2048, true))
        assertFalse(supportsFrameDimensions(16_384, 2049, true))
        assertFalse(supportsFrameDimensions(Int.MAX_VALUE, 2, true))
        assertFalse(supportsFrameDimensions(7680, 2160, false))
    }

    @Test fun existingQualitySelectionsSurviveTheUpgrade() {
        assertEquals(DesktopResolution.High, DesktopResolution.restore(null, true))
        assertEquals(DesktopResolution.Standard, DesktopResolution.restore(null, false))
        assertEquals(DesktopResolution.Native, DesktopResolution.restore("native", true))
    }

    @Test fun decodeColorPreservesPngAndSelectsJpegPrecisionAtEveryResolution() {
        for (resolution in DesktopResolution.entries) {
            for (mode in DesktopDecodeColor.entries) assertTrue(mode.uses32Bit("png", resolution))
            assertTrue(DesktopDecodeColor.Full.uses32Bit("jpeg", resolution))
            assertFalse(DesktopDecodeColor.Compact.uses32Bit("jpeg", resolution))
            assertEquals(resolution != DesktopResolution.Standard, DesktopDecodeColor.Auto.uses32Bit("jpeg", resolution))
        }
    }
}
