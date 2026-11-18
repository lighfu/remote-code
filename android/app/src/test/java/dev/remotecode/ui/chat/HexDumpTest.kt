package dev.remotecode.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HexDumpTest {
    @Test fun showsUnsignedBytesPrintableAsciiAndFinalPartialRow() {
        val bytes = byteArrayOf(0, 9, 32, 65, 126, 127, -128, -1, 1)
        assertEquals(HexRow("00 09 20 41 7E 7F 80 FF", ".. A~..."), hexRow(bytes, 0, 8))
        assertEquals(HexRow("01", "."), hexRow(bytes, 8, 8))
    }

    @Test fun addressesAreAbsoluteAndCanExceedFourGiB() {
        val size = 0x1_0000_1235L
        val offset = parseHexOffset(" 0X100001234 ", size)!!
        assertEquals(size - 1, offset)
        assertEquals(0x1_0000_1000L, hexPageStart(offset))
        assertEquals("100001234", hexAddress(offset))
        assertEquals("0000000A", hexAddress(10))
        assertEquals(0L, parseHexOffset("0", 1))
        for (bad in listOf("", "+1", "-1", "G", "0x", "100001235", "8000000000000000")) {
            assertNull(parseHexOffset(bad, size))
        }
        assertNull(parseHexOffset("0", 0))
    }
}
