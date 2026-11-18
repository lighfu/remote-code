package dev.remotecode.data

import dev.remotecode.R
import dev.remotecode.i18n.AppStringResolver
import dev.remotecode.i18n.applicationStrings
import dev.remotecode.i18n.text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal const val MAX_CODE_QUOTE_CHARS = 64 * 1024
internal const val MAX_CODE_QUOTES = 4
private const val MAX_CODE_QUOTE_TOTAL_CHARS = 128 * 1024

/** 引用は保存・送信を伴わないスナップショット。 */
data class CodeQuote(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val code: String,
    val isDraft: Boolean = false,
    val id: String = UUID.randomUUID().toString(),
)

private fun normalizedCode(text: String) = text.replace("\r\n", "\n").replace('\r', '\n')

/** 選択位置は CRLF を含む元テキストの UTF-16 オフセット。 */
private fun normalizedOffset(text: String, offset: Int): Int {
    var result = offset
    for (index in 1 until offset) if (text[index] == '\n' && text[index - 1] == '\r') result--
    return result
}

private fun validateCodeQuote(quote: CodeQuote, strings: AppStringResolver) {
    require(quote.path.isNotBlank() && quote.path.length <= 8192 && quote.path.none { it.code < 32 }) { strings.text(R.string.core_quote_invalid_path) }
    require(quote.startLine >= 1 && quote.endLine >= quote.startLine) { strings.text(R.string.core_quote_invalid_lines) }
    require(quote.code.isNotEmpty()) { strings.text(R.string.core_quote_select_code) }
    require(quote.code.length <= MAX_CODE_QUOTE_CHARS) { strings.text(R.string.core_quote_single_limit) }
}

fun codeQuoteFromSelection(path: String, text: String, startOffset: Int, endOffset: Int, isDraft: Boolean = false, strings: AppStringResolver = applicationStrings): CodeQuote {
    require(startOffset in 0..text.length && endOffset in 0..text.length) { strings.text(R.string.core_quote_selection_changed) }
    val normalized = normalizedCode(text)
    val start = normalizedOffset(text, minOf(startOffset, endOffset))
    val end = normalizedOffset(text, maxOf(startOffset, endOffset))
    require(start < end) { strings.text(R.string.core_quote_select_code) }
    val firstLine = normalized.take(start).count { it == '\n' } + 1
    // 次行先頭の排他的終端は、その行を含めない。
    val lastLine = normalized.take(end - 1).count { it == '\n' } + 1
    return CodeQuote(path, firstLine, lastLine, normalized.substring(start, end), isDraft).also { validateCodeQuote(it, strings) }
}

fun codeQuoteFromLines(path: String, text: String, startLine: Int, endLine: Int, isDraft: Boolean = false, strings: AppStringResolver = applicationStrings): CodeQuote {
    val normalized = normalizedCode(text)
    val starts = projectLineStarts(normalized)
    require(startLine >= 1 && endLine >= startLine && endLine <= starts.size) { strings.text(R.string.core_quote_invalid_lines) }
    val start = starts[startLine - 1]
    val end = if (endLine < starts.size) starts[endLine] else normalized.length
    return codeQuoteFromSelection(path, normalized, start, end, isDraft, strings)
}

private data class CodeQuoteKey(val server: ConnectionIdentity, val session: String)

/** 引用は接続先・セッション別にメモリ内だけで保持する。 */
internal object CodeQuoteDrafts {
    private val drafts = ConcurrentHashMap<CodeQuoteKey, MutableStateFlow<List<CodeQuote>>>()

    fun quotes(server: ConnectionIdentity, sessionId: String): StateFlow<List<CodeQuote>> = state(server, sessionId)

    private fun state(server: ConnectionIdentity, sessionId: String): MutableStateFlow<List<CodeQuote>> =
        drafts.computeIfAbsent(CodeQuoteKey(server, sessionId)) { MutableStateFlow(emptyList()) }

    fun add(server: ConnectionIdentity, sessionId: String, quote: CodeQuote, strings: AppStringResolver = applicationStrings) {
        validateCodeQuote(quote, strings)
        val state = state(server, sessionId)
        synchronized(state) {
            val current = state.value
            if (current.any { it.id == quote.id }) return
            require(current.size < MAX_CODE_QUOTES) { strings.text(R.string.core_quote_count_limit, MAX_CODE_QUOTES) }
            require(current.sumOf { it.code.length } + quote.code.length <= MAX_CODE_QUOTE_TOTAL_CHARS) { strings.text(R.string.core_quote_total_limit) }
            state.value = current + quote
        }
    }

    fun remove(server: ConnectionIdentity, sessionId: String, id: String) {
        val state = state(server, sessionId)
        synchronized(state) { state.value = state.value.filterNot { it.id == id } }
    }

    fun clear(server: ConnectionIdentity, sessionId: String) {
        val state = state(server, sessionId)
        synchronized(state) { state.value = emptyList() }
    }
}

internal fun codeQuotePrompt(input: String, quotes: List<CodeQuote>, strings: AppStringResolver = applicationStrings): String {
    if (quotes.isEmpty()) return input.trim()
    fun longestRun(text: String, char: Char): Int {
        var longest = 0
        var run = 0
        text.forEach { if (it == char) { run++; longest = maxOf(longest, run) } else run = 0 }
        return longest
    }
    return buildString {
        append(input.trim().ifBlank { strings.text(R.string.core_quote_default_request) })
        quotes.forEach { quote ->
            validateCodeQuote(quote, strings)
            val ticks = longestRun(quote.code, '`')
            val tildes = longestRun(quote.code, '~')
            val marker = if (ticks <= tildes) '`' else '~'
            val fence = marker.toString().repeat(maxOf(3, minOf(ticks, tildes) + 1))
            append(strings.text(R.string.core_quote_source, quote.path))
            append(strings.text(R.string.core_quote_lines, quote.startLine))
            if (quote.endLine != quote.startLine) append('–').append(quote.endLine)
            if (quote.isDraft) append(strings.text(R.string.core_quote_unsaved_note))
            append('\n').append(fence).append('\n').append(quote.code)
            if (!quote.code.endsWith('\n')) append('\n')
            append(fence)
        }
    }
}
