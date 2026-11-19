package dev.remotecode.ui.chat

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementType
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.flavours.gfm.lexer._GFMLexer
import org.intellij.markdown.lexer.GeneratedLexer
import org.intellij.markdown.lexer.MarkdownLexer
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParserManager
import org.intellij.markdown.parser.sequentialparsers.TokensCache
import org.intellij.markdown.parser.sequentialparsers.impl.InlineLinkParser
import org.intellij.markdown.parser.sequentialparsers.impl.LinkParserUtil
import org.intellij.markdown.parser.sequentialparsers.impl.MathParser
import org.intellij.markdown.parser.sequentialparsers.impl.ReferenceLinkParser

internal val ChatMathDelimiter = MarkdownElementType("TEX_DELIMITER", isToken = true)

internal object ChatMathFlavour : GFMFlavourDescriptor() {
    private val gfmParsers = super.sequentialParserManager
    override val sequentialParserManager = object : SequentialParserManager() {
        override fun getParserSequence() = gfmParsers.getParserSequence().map {
            if (it is MathParser) ChatMathParser else it
        }
    }

    override fun createInlinesLexer() = MarkdownLexer(TexLexer())
}

// Preserve escaped delimiters as individual tokens before MarkdownLexer merges adjacent TEXT.
// GFM already tokenizes a pair of backslashes separately from a subsequent ordinary bracket.
private class TexLexer(private val gfm: GeneratedLexer = _GFMLexer()) : GeneratedLexer by gfm {
    private var text: CharSequence = ""

    override fun reset(buffer: CharSequence, start: Int, end: Int, initialState: Int) {
        text = buffer
        gfm.reset(buffer, start, end, initialState)
    }

    override fun advance(): IElementType? {
        val type = gfm.advance()
        return if (type == MarkdownTokenTypes.TEXT && tokenEnd - tokenStart == 2 &&
            text[tokenStart] == '\\' && text[tokenStart + 1] in "()[]"
        ) ChatMathDelimiter else type
    }
}

private object ChatMathParser : SequentialParser {
    private val delimiters = setOf(GFMTokenTypes.DOLLAR, ChatMathDelimiter)

    override fun parse(tokens: TokensCache, rangesToGlue: List<IntRange>): SequentialParser.ParsingResult {
        val result = SequentialParser.ParsingResultBuilder()
        val remaining = mutableListOf<IntRange>()
        for (range in rangesToGlue) {
            if (range.none { tokens.filteredTokens[it].type in delimiters }) {
                remaining.add(range)
                continue
            }
            var start = range.first
            var open = -1
            var closer = ""
            val ranges = listOf(range)
            val links by lazy { LinkParserUtil.ScanIndex(tokens, ranges) }
            var token = tokens.RangesListIterator(ranges)
            while (token.type != null) {
                if (token.type == MarkdownTokenTypes.LBRACKET) {
                    val link = InlineLinkParser.parseInlineLink(token, scanIndex = links)
                        ?: ReferenceLinkParser.parseReferenceLink(token, scanIndex = links)
                    if (link != null) {
                        while (token.type != null && token.index <= link.iteratorPosition.index) token = token.advance()
                        continue
                    }
                }
                if (token.type == ChatMathDelimiter || token.type == GFMTokenTypes.DOLLAR && token.length <= 2) {
                    val marker = tokens.originalText.subSequence(token.start, token.end).toString()
                    val next = token.charLookup(1)
                    val previous = token.charLookup(-1)
                    val dollar = marker.first() == '$'
                    val singleDollar = marker == "$"
                    val canClose = !dollar || (!next.joinsWord() && (!singleDollar || !previous.isWhitespace()))
                    if (open >= 0 && marker == closer && canClose && token.index > open + 1) {
                        val kind = if (marker == "$$" || marker == "\\]") GFMElementTypes.BLOCK_MATH else GFMElementTypes.INLINE_MATH
                        result.withNode(SequentialParser.Node(open..token.index + 1, kind))
                        if (start < open) remaining.add(start until open)
                        start = token.index + 1
                        open = -1
                    } else {
                        // A later opening '$' supersedes an unmatched price such as "$5 and $10".
                        val expected = when (marker) {
                            "\\(" -> "\\)"
                            "\\[" -> "\\]"
                            "$", "$$" -> marker.takeIf { !previous.joinsWord() && (!singleDollar || !next.isWhitespace()) }
                            else -> null
                        }
                        if (expected != null && (open < 0 || closer == "$" || expected == closer)) {
                            open = token.index
                            closer = expected
                        }
                    }
                }
                token = token.advance()
            }
            if (start <= range.last) remaining.add(start..range.last)
        }
        return result.withFurtherProcessing(remaining)
    }

    // Japanese, Chinese and Korean prose routinely has no spaces around embedded math.
    private fun Char.joinsWord(): Boolean {
        if (this == '_') return true
        if (!isLetterOrDigit()) return false
        return when (Character.UnicodeScript.of(code)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> false
            else -> true
        }
    }
}
