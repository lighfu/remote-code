package dev.remotecode.ui.chat

internal class MinimapPalette(
    val text: Int, val syntax: IntArray, val added: Int, val removed: Int,
    val modified: Int, val metadata: Int,
)

internal data class MinimapRaster(val width: Int, val height: Int, val pixels: IntArray)

// Bound the preview independently of the file size and device density.
private const val MapColumns = 100
private const val MaxBands = 1024
private const val CodeStart = 4

internal fun <T> codeMinimapRaster(
    rows: List<T>, palette: MinimapPalette, markAt: (Int) -> Char?, lineOf: (T) -> CodeLine,
): MinimapRaster {
    val bands = minOf(rows.size, MaxBands)
    val pixels = IntArray(MapColumns * bands * 2)
    for (band in 0 until bands) {
        val first = (band.toLong() * rows.size / bands).toInt()
        val limit = ((band + 1L) * rows.size / bands).toInt()
        var added = false
        var removed = false
        var metadata = false
        for (index in first until limit) {
            when (markAt(index)) {
                '+' -> added = true
                '-' -> removed = true
                '±' -> { added = true; removed = true }
                '@' -> metadata = true
            }
            if (added && removed) break
        }
        val mark = when {
            added && removed -> palette.modified
            added -> palette.added
            removed -> palette.removed
            metadata -> palette.metadata
            else -> 0
        }
        val start = band * 2 * MapColumns
        if (mark != 0) {
            pixels.fill(mark, start, start + 2)
            pixels.fill(mark, start + MapColumns, start + MapColumns + 2)
        }
        val index = first + (limit - first - 1) / 2
        drawMinimapLine(pixels, start, lineOf(rows[index]), palette)
    }
    return MinimapRaster(MapColumns, bands * 2, pixels)
}

private fun drawMinimapLine(pixels: IntArray, start: Int, line: CodeLine, palette: MinimapPalette) {
    var column = 0
    var index = 0
    var tokenIndex = 0
    while (index < line.text.length && column < MapColumns - CodeStart) {
        val char = line.text.codePointAt(index)
        while (tokenIndex < line.tokens.size && line.tokens[tokenIndex].end <= index) tokenIndex++
        val token = line.tokens.getOrNull(tokenIndex)
        val width = when {
            char == '\t'.code -> 4 - column % 4
            char in 0x2e80..0xa4cf || char in 0xac00..0xd7a3 || char in 0xff01..0xff60 || char > 0xffff -> 2
            else -> 1
        }
        if (!Character.isWhitespace(char)) {
            val color = if (token != null && index >= token.start) palette.syntax[token.ink.ordinal] else palette.text
            val left = start + CodeStart + column
            val right = minOf(left + width, start + MapColumns)
            pixels.fill(color, left, right)
            pixels.fill((color and 0x00ffffff) or 0x30000000, left + MapColumns, right + MapColumns)
        }
        index += Character.charCount(char)
        column += width
    }
}
