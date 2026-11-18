package dev.remotecode.ui.chat

internal const val HEX_PAGE_BYTES = 4096
private const val DIGITS = "0123456789ABCDEF"

internal fun hexAddress(offset: Long, digits: Int = 8): String =
    offset.toString(16).uppercase().padStart(digits, '0')

internal fun parseHexOffset(input: String, size: Long): Long? {
    val hex = input.trim().removePrefix("0x").removePrefix("0X")
    if (hex.isEmpty() || hex.any { it.uppercaseChar() !in DIGITS }) return null
    return hex.toLongOrNull(16)?.takeIf { it in 0 until size }
}

internal fun hexPageStart(offset: Long): Long = offset - offset % HEX_PAGE_BYTES

internal data class HexRow(val bytes: String, val ascii: String)

internal fun hexRow(data: ByteArray, start: Int, columns: Int): HexRow {
    val end = minOf(start + columns, data.size)
    val hex = StringBuilder(columns * 3)
    val ascii = StringBuilder(columns)
    for (index in start until end) {
        val byte = data[index].toInt() and 0xff
        if (index > start) hex.append(' ')
        hex.append(DIGITS[byte ushr 4]).append(DIGITS[byte and 15])
        ascii.append(if (byte in 0x20..0x7e) byte.toChar() else '.')
    }
    return HexRow(hex.toString(), ascii.toString())
}
