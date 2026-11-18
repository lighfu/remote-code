package dev.remotecode.ui.chat

data class CodeLanguage(val label: String, val family: String, val keywords: Set<String>) {
    val displayLabel: String get() = when (label) {
        "設定" -> dev.remotecode.i18n.appString(dev.remotecode.R.string.chat_language_config)
        "テキスト" -> dev.remotecode.i18n.appString(dev.remotecode.R.string.chat_language_text)
        else -> label
    }
}

private fun words(s: String) = s.split(' ').toSet()
private val cWords = words("abstract as async await base break case catch class const continue default delegate do else enum export extends false final finally for from function get if implements import in interface internal is let namespace native new null of operator override package private protected public readonly record return sealed set static struct super switch synchronized this throw throws true try type typeof using val var virtual void volatile when while yield")
private val pyWords = words("and as assert async await break class continue def del elif else except False finally for from global if import in is lambda None nonlocal not or pass raise return True try while with yield")
private val sqlWords = words("select from where join left right inner outer on as and or not null true false insert into values update set delete create alter drop table index primary key references distinct group by order asc desc having limit offset union all case when then else end begin commit rollback with")

fun codeLanguage(path: String): CodeLanguage {
    val name = path.replace('\\', '/').substringAfterLast('/').lowercase()
    val ext = name.substringAfterLast('.', "")
    val label = when (ext) {
        "ts", "mts", "cts" -> "TypeScript"
        "tsx" -> "TSX"
        "js", "mjs", "cjs" -> "JavaScript"
        "jsx" -> "JSX"
        "kt", "kts" -> "Kotlin"
        "java" -> "Java"
        "py", "pyw" -> "Python"
        "rb" -> "Ruby"
        "rs" -> "Rust"
        "go" -> "Go"
        "c", "h" -> "C"
        "cpp", "cc", "cxx", "hpp" -> "C++"
        "cs" -> "C#"
        "swift" -> "Swift"
        "dart" -> "Dart"
        "php" -> "PHP"
        "json", "jsonl", "ipynb" -> "JSON"
        "yaml", "yml" -> "YAML"
        "toml" -> "TOML"
        "html", "htm", "vue", "svelte" -> "HTML"
        "xml", "svg", "xaml" -> "XML"
        "css", "scss", "sass", "less" -> "CSS"
        "sh", "bash", "zsh" -> "Shell"
        "ps1", "psm1" -> "PowerShell"
        "bat", "cmd" -> "Batch"
        "sql" -> "SQL"
        "md", "markdown" -> "Markdown"
        "lua" -> "Lua"
        "r" -> "R"
        "ini", "cfg", "conf", "properties", "env" -> "設定"
        else -> when (name) { "dockerfile" -> "Dockerfile"; "makefile" -> "Makefile"; else -> "テキスト" }
    }
    val family = when (label) {
        "Shell", "PowerShell" -> "shell"
        "Python", "Ruby", "R", "YAML", "TOML", "設定", "Dockerfile", "Makefile" -> "hash"
        "HTML", "XML" -> "markup"
        "SQL", "Lua" -> "dash"
        "Markdown" -> "markdown"
        "JSON" -> "json"
        "テキスト" -> "plain"
        else -> "c"
    }
    return CodeLanguage(label, family, when (label) {
        "Python" -> pyWords
        "SQL" -> sqlWords
        "JSON" -> words("true false null")
        "Shell" -> words("if then else elif fi for in do done case esac function export local return echo")
        "PowerShell" -> words("function param if else elseif foreach in while return switch try catch finally true false null")
        "Ruby" -> pyWords + words("end module require unless until elsif nil true false")
        "Rust" -> cWords + words("fn mut pub impl trait match use mod crate self Self Some None")
        "Go" -> cWords + words("func defer chan range select go map nil")
        "Kotlin" -> cWords + words("fun object companion suspend data inline reified constructor init out")
        "Lua" -> words("and break do else elseif end false for function if in local nil not or repeat return then true until while")
        else -> cWords
    })
}

enum class CodeInk { Keyword, String, Comment, Number, Type, Property, Command, Variable, Flag, Operator }
data class CodeToken(val start: Int, val end: Int, val ink: CodeInk)
data class CodeLine(val text: String, val tokens: List<CodeToken>)

fun sourceLines(text: String): List<String> = text.replace("\r\n", "\n").replace('\r', '\n').let {
    if (it.isEmpty()) emptyList() else it.removeSuffix("\n").split('\n')
}

/** 行をまたぐコメント・文字列の状態を引き継ぐ。 */
fun highlightCode(text: String, language: CodeLanguage): List<CodeLine> {
    if (language.family == "shell") return highlightShell(text, language)
    var blockEnd: String? = null
    var blockInk = CodeInk.Comment
    return sourceLines(text).map { line ->
        val tokens = mutableListOf<CodeToken>()
        var i = 0
        fun token(end: Int, ink: CodeInk) { tokens += CodeToken(i, end, ink); i = end }
        while (i < line.length) {
            val close = blockEnd
            if (close != null) {
                val end = line.indexOf(close, i)
                if (end < 0) token(line.length, blockInk)
                else { token(end + close.length, blockInk); blockEnd = null }
                continue
            }
            if (language.family == "plain") break
            val c = line[i]
            val block = when {
                language.family == "markup" && line.startsWith("<!--", i) -> "-->"
                language.family == "c" && line.startsWith("/*", i) -> "*/"
                language.label == "PowerShell" && line.startsWith("<#", i) -> "#>"
                language.label == "Lua" && line.startsWith("--[[", i) -> "]]"
                else -> null
            }
            if (block != null) {
                val end = line.indexOf(block, i + 2)
                if (end < 0) { blockEnd = block; blockInk = CodeInk.Comment; token(line.length, CodeInk.Comment) }
                else token(end + block.length, CodeInk.Comment)
            } else if ((language.family == "hash" && c == '#') ||
                (language.family == "c" && line.startsWith("//", i)) ||
                (language.family == "dash" && line.startsWith("--", i))) {
                token(line.length, CodeInk.Comment)
            } else if (language.family == "markdown" && (c == '#' || line.startsWith("```", i))) {
                token(line.length, CodeInk.Keyword)
            } else if (c == '"' || c == '\'' || (c == '`' && language.family != "markup")) {
                val triple = (language.label == "Python" || language.label == "Kotlin") && line.startsWith("$c$c$c", i)
                val delimiter = if (triple) "$c$c$c" else c.toString()
                var end = i + delimiter.length
                var found = false
                while (end < line.length) {
                    if (line[end] == '\\') { end += 2; continue }
                    if (line.startsWith(delimiter, end)) { end += delimiter.length; found = true; break }
                    end++
                }
                if (!found && (triple || c == '`')) { blockEnd = delimiter; blockInk = CodeInk.String }
                token(end.coerceAtMost(line.length), CodeInk.String)
            } else if (c.isDigit() && (i == 0 || !line[i - 1].isLetterOrDigit())) {
                var end = i + 1
                while (end < line.length && (line[end].isLetterOrDigit() || line[end] in "._")) end++
                token(end, CodeInk.Number)
            } else if (c.isLetter() || c == '_' || c == '$') {
                var end = i + 1
                while (end < line.length && (line[end].isLetterOrDigit() || line[end] in "_$")) end++
                val word = line.substring(i, end)
                val ink = when {
                    word in language.keywords || (language.label == "SQL" && word.lowercase() in language.keywords) -> CodeInk.Keyword
                    language.family == "markup" -> CodeInk.Property
                    line.getOrNull((end until line.length).firstOrNull { !line[it].isWhitespace() } ?: line.length) == ':' -> CodeInk.Property
                    c.isUpperCase() -> CodeInk.Type
                    else -> null
                }
                if (ink != null) token(end, ink) else i = end
            } else i++
        }
        CodeLine(line, tokens)
    }
}

data class DiffLine(val text: String, val kind: Char, val old: Int? = null, val new: Int? = null)

internal data class DiffCodeRow(val line: DiffLine, val code: CodeLine)
internal data class DiffCodeDocument(val rows: List<DiffCodeRow>, val totalLines: Int)

/** Color each side separately so an opening comment on a deleted line cannot color an addition. */
internal fun highlightDiff(text: String, path: String, maxLines: Int = Int.MAX_VALUE): DiffCodeDocument {
    val parsed = parseDiff(text)
    val rows = parsed.take(maxLines).mapTo(ArrayList()) { DiffCodeRow(it, CodeLine(it.text, emptyList())) }
    var oldPath = path
    var newPath = path
    var start = 0
    fun colorSide(end: Int, addition: Boolean) {
        val language = codeLanguage(if (addition) newPath else oldPath)
        if (language.family == "plain") return
        val change = if (addition) '+' else '-'
        val indices = (start until end).filter { rows[it].line.kind == ' ' || rows[it].line.kind == change }
        if (indices.isEmpty()) return
        val side = indices.joinToString("\n", postfix = "\n") { rows[it].line.text }
        val code = highlightCode(side, language)
        for ((index, row) in indices.withIndex()) rows[row] = rows[row].copy(code = code[index])
    }
    fun flush(end: Int) {
        if (start < end) {
            colorSide(end, false)
            colorSide(end, true)
        }
    }
    for ((index, row) in rows.withIndex()) {
        if (row.line.kind != '@') continue
        val header = row.line.text
        // The marker describes the preceding line. Keep multiline syntax across it.
        if (header.startsWith("\\ ")) continue
        flush(index)
        when {
            header.startsWith("── ") -> {
                oldPath = fileChangePath(header.removePrefix("── "))
                newPath = oldPath
            }
            header.startsWith("diff --git ") -> {
                val paths = header.removePrefix("diff --git ")
                val split = Regex("""\s+"?b/""").find(paths)
                oldPath = split?.let { paths.take(it.range.first).trim('"') } ?: path
                newPath = split?.let { paths.drop(it.range.last + 1).trim('"') } ?: oldPath
            }
            header.startsWith("--- ") -> oldPath = header.drop(4).substringBefore('\t').trim('"')
            header.startsWith("+++ ") -> newPath = header.drop(4).substringBefore('\t').trim('"')
        }
        start = index + 1
    }
    flush(rows.size)
    return DiffCodeDocument(rows, parsed.size)
}

/** 位置情報のない差分には行番号を付けない。 */
fun parseDiff(text: String): List<DiffLine> {
    var old: Int? = null
    var new: Int? = null
    var inHunk = false
    var untilOld = 0L
    var untilNew = 0L
    val hunk = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*")
    return text.lineSequence().map { line ->
        val match = hunk.matchEntire(line)
        when {
            match != null -> {
                old = match.groupValues[1].toIntOrNull(); new = match.groupValues[3].toIntOrNull(); inHunk = true
                untilOld = (old ?: 0).toLong() + (match.groupValues[2].toIntOrNull() ?: 1)
                untilNew = (new ?: 0).toLong() + (match.groupValues[4].toIntOrNull() ?: 1)
                DiffLine(line, '@')
            }
            line.startsWith("──") || line == "⋯" || line.startsWith("diff --git") -> {
                old = null; new = null; inHunk = false; DiffLine(line, '@')
            }
            (!inHunk || (old?.toLong() == untilOld && new?.toLong() == untilNew)) &&
                (line.startsWith("+++ ") || line.startsWith("--- ")) -> {
                old = null; new = null; inHunk = false; DiffLine(line, '@')
            }
            line.startsWith("\\") -> DiffLine(line, '@')
            line.startsWith("+") -> DiffLine(line.drop(if (inHunk) 1 else if (line.startsWith("+ ")) 2 else 1), '+', new = new).also { new = new?.plus(1) }
            line.startsWith("-") -> DiffLine(line.drop(if (inHunk) 1 else if (line.startsWith("- ")) 2 else 1), '-', old = old).also { old = old?.plus(1) }
            line.startsWith(" ") -> DiffLine(line.drop(if (inHunk) 1 else if (line.startsWith("  ")) 2 else 1), ' ', old, new).also { old = old?.plus(1); new = new?.plus(1) }
            else -> { old = null; new = null; inHunk = false; DiffLine(line, '@') }
        }
    }.toList()
}
