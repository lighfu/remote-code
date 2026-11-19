package dev.remotecode.ui.chat

import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatFormulaTest {
    private fun formulas(markdown: String): List<ChatFormula> =
        chatFormulas(MarkdownParser(ChatMathFlavour).buildMarkdownTreeFromString(markdown), markdown)

    @Test fun areaAndBoxedUnitsRetainTheirExactCopySource() {
        val inline = "\\(25\\pi\\ \\mathrm{cm}^2\\)"
        val block = "\\[\n\\boxed{25\\pi\\ \\mathrm{cm}^2}\n\\]"
        assertEquals(
            listOf(
                ChatFormula(inline, "25\\pi\\ \\mathrm{cm}^2"),
                ChatFormula(block, "\\displaystyle \\boxed{25\\pi\\ \\mathrm{cm}^2}"),
            ),
            formulas("面積は" + inline + "です。\n\n" + block),
        )
    }

    @Test fun fractionsInsideListsAndRepeatedFormulasUseTheSamePlaceholder() {
        val inline = "$" + "\\frac{x_1}{2}" + "$"
        val display = "$$" + "S=\\pi r^2" + "$$"
        assertEquals(
            listOf(ChatFormula(inline, "\\frac{x_1}{2}"), ChatFormula(display, "\\displaystyle S=\\pi r^2")),
            formulas("- First " + inline + "\n- Again " + inline + "\n\n" + display),
        )
    }

    @Test fun codeCurrencyAndInvalidCommandsStayReadableAsSource() {
        val examples = listOf(
            "Price " + "$" + "100 and " + "$" + "50",
            "Source: " + '\u0060' + "\\(x^2\\)" + '\u0060',
            "~~~latex\n\\[\nx^2\n\\]\n~~~",
            "(ordinary parentheses) [normal brackets]",
            "\\(\\frac{1}{\\)",
            "\\(\\notARealLaTeXCommand{x}\\)",
        )
        examples.forEach { assertEquals(it, emptyList<ChatFormula>(), formulas(it)) }
    }
}
