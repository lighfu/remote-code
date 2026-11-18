package dev.remotecode.ui.chat

data class TerminalSpan(val start: Int, val end: Int, val foreground: Int?, val background: Int?, val bold: Boolean)
data class TerminalText(val text: String, val spans: List<TerminalSpan>)

private val ansiColors = intArrayOf(0x242424, 0xD74B4B, 0x62AA55, 0xC69C3B, 0x5A8FDE, 0xB36ED5, 0x42A8AD, 0xC8C8C8, 0x808080, 0xFF7777, 0x9DD78C, 0xF2D27A, 0x91B7F2, 0xD5A0F0, 0x88D6D9, 0xFFFFFF)
private fun terminalColor(index: Int): Int? = when (index) {
    in 0..15 -> ansiColors[index]
    in 16..231 -> {
        val levels = intArrayOf(0, 95, 135, 175, 215, 255); val n = index - 16
        (levels[n / 36] shl 16) or (levels[n / 6 % 6] shl 8) or levels[n % 6]
    }
    in 232..255 -> (8 + (index - 232) * 10).let { (it shl 16) or (it shl 8) or it }
    else -> null
}

/** SGR の色だけ残し、カーソル操作・OSC は除く。 */
fun terminalText(value: String): TerminalText {
    if ('\u001b' !in value) return TerminalText(value.replace("\r\n", "\n").replace('\r', '\n'), emptyList())
    val out = StringBuilder()
    val spans = mutableListOf<TerminalSpan>()
    var foreground: Int? = null; var background: Int? = null; var bold = false
    var from = 0
    fun flush() {
        if (out.length > from && (foreground != null || background != null || bold)) spans += TerminalSpan(from, out.length, foreground, background, bold)
        from = out.length
    }
    var i = 0
    while (i < value.length) {
        if (value[i] == '\u001b') {
            if (value.getOrNull(i + 1) == '[') {
                var end = i + 2
                while (end < value.length && value[end] !in '@'..'~') end++
                if (end < value.length && value[end] == 'm') {
                    flush()
                    val params = value.substring(i + 2, end).split(';').map { it.toIntOrNull() ?: 0 }
                    var p = 0
                    while (p < params.size) {
                        when (val code = params[p++]) {
                            0 -> { foreground = null; background = null; bold = false }
                            1 -> bold = true
                            22 -> bold = false
                            39 -> foreground = null
                            49 -> background = null
                            in 30..37 -> foreground = terminalColor(code - 30)
                            in 90..97 -> foreground = terminalColor(code - 90 + 8)
                            in 40..47 -> background = terminalColor(code - 40)
                            in 100..107 -> background = terminalColor(code - 100 + 8)
                            38, 48 -> {
                                val mode = params.getOrNull(p++)
                                val color = when {
                                    mode == 5 -> params.getOrNull(p++)?.let(::terminalColor)
                                    mode == 2 && p + 2 < params.size -> {
                                        val r = params[p++].coerceIn(0, 255); val g = params[p++].coerceIn(0, 255); val b = params[p++].coerceIn(0, 255)
                                        (r shl 16) or (g shl 8) or b
                                    }
                                    else -> null
                                }
                                if (code == 38) foreground = color else background = color
                            }
                        }
                    }
                }
                i = if (end < value.length) end + 1 else value.length
            } else if (value.getOrNull(i + 1) == ']') {
                i += 2
                while (i < value.length && value[i] != '\u0007' && !(value[i] == '\u001b' && value.getOrNull(i + 1) == '\\')) i++
                if (i < value.length) i += if (value[i] == '\u0007') 1 else 2
            } else i = (i + 2).coerceAtMost(value.length)
        } else if (value[i] == '\r') { if (value.getOrNull(i + 1) != '\n') out.append('\n'); i++ }
        else { out.append(value[i++]) }
    }
    flush()
    return TerminalText(out.toString(), spans)
}
