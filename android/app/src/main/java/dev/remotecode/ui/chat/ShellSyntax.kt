package dev.remotecode.ui.chat

/** コードらしい出力だけを言語推定する。 */
fun outputCodeLanguage(text: String): CodeLanguage {
    val value = text.trimStart()
    return when {
        Regex("""^(?:\{\s*(?:"|\})|\[\s*(?:\{|\[|"|\]|-?\d|true\b|false\b|null\b))""").containsMatchIn(value) -> codeLanguage("output.json")
        value.startsWith("<?xml") || value.startsWith("<svg") || value.startsWith("<!DOCTYPE", true) -> codeLanguage("output.xml")
        value.startsWith("#!/") && (value.lineSequence().first().contains("sh")) -> codeLanguage("script.sh")
        Regex("""^(?:def\s+\w+\(|from\s+[\w.]+\s+import\s|import\s+[\w.]+\s*\n)""").containsMatchIn(value) -> codeLanguage("script.py")
        Regex("""^(?:using\s+System[.;]|public\s+(?:static\s+)?class\s)""").containsMatchIn(value) -> codeLanguage("script.cs")
        else -> codeLanguage("output.txt")
    }
}

fun commandCodeLanguage(tool: String?, command: String): CodeLanguage = when {
    tool == "PowerShell" -> codeLanguage("script.ps1")
    tool == "Bash" -> codeLanguage("script.sh")
    Regex("""(?i)(?:^\s*(?:pwsh|powershell)(?:\.exe)?\b|\${'$'}env:|\b(?:Get|Set|New|Write|Select|ForEach|Where)-[A-Za-z]+)""").containsMatchIn(command) -> codeLanguage("script.ps1")
    else -> codeLanguage("script.sh")
}

private fun variableEnd(line: String, start: Int): Int {
    if (line.getOrNull(start + 1) == '{') return line.indexOf('}', start + 2).let { if (it >= 0) it + 1 else line.length }
    var end = start + 1
    if (line.getOrNull(end) in listOf('?', '$', '#', '@', '*', '!', '-')) return end + 1
    while (end < line.length && (line[end].isLetterOrDigit() || line[end] in "_:")) end++
    return end
}

/** 字句解析だけ行い、展開式は評価しない。 */
fun highlightShell(text: String, language: CodeLanguage): List<CodeLine> {
    val powershell = language.label == "PowerShell"
    var quote: Char? = null
    var blockComment = false
    var hereEnd: String? = null
    var shellHereEnd: String? = null
    var shellHereLanguage: CodeLanguage? = null
    val herePattern = Regex("""<<-?\s*(['"]?)([A-Za-z_][A-Za-z0-9_]*)\1\s*${'$'}""")
    return sourceLines(text).map { line ->
        val tokens = mutableListOf<CodeToken>()
        if (shellHereEnd != null) {
            if (line.trimStart('\t') == shellHereEnd) {
                tokens += CodeToken(0, line.length, CodeInk.Operator)
                shellHereEnd = null; shellHereLanguage = null
            } else {
                val nested = shellHereLanguage
                if (nested != null) tokens += highlightCode(line, nested).firstOrNull()?.tokens.orEmpty()
                else if (line.isNotEmpty()) tokens += CodeToken(0, line.length, CodeInk.String)
            }
            return@map CodeLine(line, tokens)
        }
        if (hereEnd != null) {
            if (line.trimStart().startsWith(hereEnd!!)) hereEnd = null
            if (line.isNotEmpty()) tokens += CodeToken(0, line.length, CodeInk.String)
            return@map CodeLine(line, tokens)
        }
        var i = 0
        var expectsCommand = quote == null
        var assignmentValue = false
        fun add(from: Int, to: Int, ink: CodeInk) { if (to > from) tokens += CodeToken(from, to, ink) }
        while (i < line.length) {
            if (blockComment) {
                val end = line.indexOf("#>", i)
                if (end < 0) { add(i, line.length, CodeInk.Comment); i = line.length }
                else { add(i, end + 2, CodeInk.Comment); i = end + 2; blockComment = false }
                continue
            }
            if (quote != null || line[i] == '\'' || line[i] == '"') {
                val delimiter = quote ?: line[i]
                var part = i
                if (quote == null) { quote = delimiter; i++ }
                var closed = false
                while (i < line.length) {
                    if ((powershell && line[i] == '`') || (!powershell && delimiter == '"' && line[i] == '\\')) { i = (i + 2).coerceAtMost(line.length); continue }
                    if (line[i] == delimiter) {
                        if (powershell && delimiter == '\'' && line.getOrNull(i + 1) == '\'') { i += 2; continue }
                        i++; closed = true; break
                    }
                    if (delimiter == '"' && line[i] == '$' && line.getOrNull(i + 1) != '(') {
                        val end = variableEnd(line, i)
                        if (end > i + 1) { add(part, i, CodeInk.String); add(i, end, CodeInk.Variable); i = end; part = i; continue }
                    }
                    i++
                }
                add(part, i, CodeInk.String)
                if (closed) quote = null
                if (!assignmentValue) expectsCommand = false
                continue
            }
            val c = line[i]
            when {
                c.isWhitespace() -> { assignmentValue = false; i++ }
                powershell && line.startsWith("<#", i) -> {
                    val end = line.indexOf("#>", i + 2)
                    if (end < 0) { add(i, line.length, CodeInk.Comment); blockComment = true; i = line.length }
                    else { add(i, end + 2, CodeInk.Comment); i = end + 2 }
                }
                c == '#' && (i == 0 || line[i - 1].isWhitespace() || line[i - 1] in ";|&(") -> { add(i, line.length, CodeInk.Comment); i = line.length }
                powershell && c == '@' && line.getOrNull(i + 1) in listOf('"', '\'') && line.substring(i + 2).isBlank() -> {
                    hereEnd = "${line[i + 1]}@"; add(i, line.length, CodeInk.String); i = line.length
                }
                c == '$' && line.getOrNull(i + 1) != '(' -> {
                    val end = variableEnd(line, i)
                    add(i, end, CodeInk.Variable); i = end
                }
                c == '-' && (line.getOrNull(i + 1)?.isLetter() == true || line.getOrNull(i + 1) == '-') -> {
                    val start = i++
                    while (i < line.length && !line[i].isWhitespace() && line[i] !in "=;|&()<>\"'") i++
                    add(start, i, CodeInk.Flag)
                }
                c in ";|&=(){}<>" || c == '$' && line.getOrNull(i + 1) == '(' -> {
                    val start = i++
                    if (c == '$') i++
                    while (i < line.length && line[i] == c && c in "|&<>") i++
                    add(start, i, CodeInk.Operator)
                    if (c == '=') assignmentValue = true
                    if (c in ";|&(" || c == '$') { expectsCommand = true; assignmentValue = false }
                }
                c == '\\' && !powershell || c == '`' && powershell -> { add(i, (i + 2).coerceAtMost(line.length), CodeInk.String); i = (i + 2).coerceAtMost(line.length) }
                else -> {
                    val start = i++
                    while (i < line.length && !line[i].isWhitespace() && line[i] !in ";|&=(){}<>\"'${'$'}") i++
                    val word = line.substring(start, i)
                    val assignment = line.getOrNull(i) == '='
                    val ink = when {
                        word in language.keywords || powershell && word.lowercase() in language.keywords -> CodeInk.Keyword
                        assignment -> CodeInk.Variable
                        word.toDoubleOrNull() != null -> CodeInk.Number
                        assignmentValue -> CodeInk.String
                        expectsCommand -> CodeInk.Command
                        else -> null
                    }
                    if (ink != null) add(start, i, ink)
                    if (assignment) assignmentValue = true
                    if (!assignment && !assignmentValue && word !in setOf("then", "else", "do", "sudo", "env", "command", "exec")) expectsCommand = false
                }
            }
        }
        if (!powershell && quote == null) herePattern.find(line)?.let {
            shellHereEnd = it.groupValues[2]
            shellHereLanguage = if (Regex("""\bpython(?:3(?:\.\d+)?)?\b""").containsMatchIn(line)) codeLanguage("script.py") else null
        }
        CodeLine(line, tokens)
    }
}
