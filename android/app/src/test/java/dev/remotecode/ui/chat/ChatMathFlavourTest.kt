package dev.remotecode.ui.chat

import org.intellij.markdown.IElementType
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes.BLOCK_MATH
import org.intellij.markdown.flavours.gfm.GFMElementTypes.INLINE_MATH
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMathFlavourTest {
    private fun formulas(markdown: String): List<Pair<IElementType, String>> = buildList {
        fun visit(node: ASTNode) {
            if (node.type == INLINE_MATH || node.type == BLOCK_MATH) {
                add(node.type to markdown.substring(node.startOffset, node.endOffset))
            } else {
                node.children.forEach(::visit)
            }
        }
        val parser = MarkdownParser(ChatMathFlavour, cancellationToken = CancellationToken.NonCancellable)
        visit(parser.buildMarkdownTreeFromString(markdown as CharSequence))
    }

    @Test fun escapedInlineAndDisplayDelimitersKeepTheirOriginalSource() {
        val inline = """\(25\pi\ \mathrm{cm}^2\)"""
        val block = """\[
            |\boxed{25\pi\ \mathrm{cm}^2}
            |\]""".trimMargin()
        assertEquals(listOf(INLINE_MATH to inline, BLOCK_MATH to block),
            formulas("答えは" + inline + "です。\n\n" + block))
    }

    @Test fun dollarSpansInsideJapaneseBulletsDoNotNeedSpaces() {
        val inline = """$25\pi\ \text{cm}^2$"""
        val block = "\$\$S=\\pi r^2\$\$"
        assertEquals(listOf(INLINE_MATH to inline, BLOCK_MATH to block),
            formulas("- 面積は" + inline + "です。\n- 結果" + block + "以上。"))
    }

    @Test fun prosePricesCannotSwallowALaterFormula() {
        val formula = "\$x^2\$"
        assertEquals(listOf(INLINE_MATH to formula), formulas("料金は \$5 と \$10、面積は" + formula + "です。"))
        assertEquals(emptyList<Pair<IElementType, String>>(), formulas("Paid \$10 and \$25.50. (regular) [普通] \\(open"))
    }

    @Test fun escapedSlashesCodeImageAndLinkDestinationsAreNotMath() {
        val code = "内訳 \u0060\\(x\\)\u0060 と \u0060\$y\$\u0060\n\n\u0060\u0060\u0060tex\n\\[q\\]\n\u0060\u0060\u0060\n\n"
        val links = """[式 \(x\)](https://example.org) ![画像](https://example.org/$5$/foo)"""
        assertEquals(emptyList<Pair<IElementType, String>>(),
            formulas(code + links + "\n\n" + """\\(ordinary\\) \\[index\\] \$5"""))
    }
}
