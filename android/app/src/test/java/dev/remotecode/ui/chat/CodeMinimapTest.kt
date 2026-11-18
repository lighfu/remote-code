package dev.remotecode.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeMinimapTest {
    private val palette = MinimapPalette(
        0xff111111.toInt(), IntArray(CodeInk.entries.size) { 0xff225500.toInt() },
        0xff113311.toInt(), 0xff554444.toInt(), 0xff226677.toInt(), 0xff222299.toInt(),
    )

    @Test fun scrollWithinSingleWrappedLineMovesTheThumb() {
        val middle = minimapWindow(1, 0, -400, 1000, 0, -400, 1000, 0, 200)
        assertEquals(0.4f, middle.start, 0.0001f)
        assertEquals(0.6f, middle.end, 0.0001f)
        assertEquals(0.5f, middle.progress, 0.0001f)
        val bottom = minimapWindow(1, 0, -800, 1000, 0, -800, 1000, 0, 200)
        assertEquals(1f, bottom.progress, 0.0001f)
        assertEquals(20f, bottom.thumb(50f, 20f), 0.0001f)
    }

    @Test fun fileThatFitsHasAFullHeightThumbEvenWithPadding() {
        val window = minimapWindow(2, 0, 0, 24, 1, 24, 24, -8, 100)
        assertEquals(0f, window.progress, 0f)
        assertEquals(100f, window.thumb(100f, 20f), 0f)
    }

    @Test fun rasterReflectsTabsTokensAndEmptyLines() {
        val code = listOf(CodeLine("\tval x", listOf(CodeToken(1, 4, CodeInk.Keyword))), CodeLine("", emptyList()))
        val map = codeMinimapRaster(code, palette, { null }, { it })
        val keyword = palette.syntax[CodeInk.Keyword.ordinal]
        assertTrue((0..7).all { map.pixels[it] == 0 })
        assertTrue((8..10).all { map.pixels[it] == keyword })
        assertEquals(0, map.pixels[11])
        assertEquals(palette.text, map.pixels[12])
        assertTrue((map.width * 2 until map.pixels.size).all { map.pixels[it] == 0 })
    }

    @Test fun largeFilesBoundTheRasterAndKeepSparseDiffMarkers() {
        val row = CodeLine("source", emptyList())
        val count = 100_000
        var samples = 0
        val rows = object : AbstractList<CodeLine>() {
            override val size = count
            override fun get(index: Int): CodeLine { samples++; return row }
        }
        val map = codeMinimapRaster(rows, palette, { index ->
            when (index) {
                2 -> '+'
                3 -> '-'
                count - 1 -> '+'
                else -> null
            }
        }, { it })
        assertTrue(samples <= 1024)
        assertEquals(map.width * 2048, map.pixels.size)
        assertEquals(palette.modified, map.pixels[0])
        assertEquals(palette.added, map.pixels[(map.height - 1) * map.width])
    }
}
