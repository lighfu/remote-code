package dev.remotecode.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.remotecode.R
import com.hrm.latex.parser.LatexParser
import com.hrm.latex.parser.model.LatexNode
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.MarkdownInlineContent
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.markdownInlineContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

private val chatAnnotatorConfig = markdownAnnotatorConfig(eolAsNewLine = true)
private val ASTNode.isMath get() = type == GFMElementTypes.INLINE_MATH || type == GFMElementTypes.BLOCK_MATH

/** Keep the delimited Markdown separately from the expression passed to the typesetter. */
internal data class ChatFormula(val source: String, val expression: String)

internal fun chatFormulas(root: ASTNode, markdown: String): List<ChatFormula> {
    if ('$' !in markdown && "\\(" !in markdown && "\\[" !in markdown) return emptyList()
    val formulas = mutableMapOf<String, ChatFormula>()
    val parser = LatexParser()
    fun visit(node: ASTNode) {
        if (!node.isMath) {
            node.children.forEach(::visit)
            return
        }
        val raw = node.getTextInNode(markdown).toString()
        if (raw in formulas) return
        val delimiter = if (raw.startsWith("$$") || raw.startsWith('\\')) 2 else 1
        if (raw.length <= 2 * delimiter || raw.length > 16_384) return
        val body = raw.substring(delimiter, raw.length - delimiter).trim()
        if (body.isEmpty()) return
        // Monetary amounts can share a dollar pair in prose (for example "$100 and $50").
        if (raw[0] == '$' && delimiter == 1 && markdown.getOrNull(node.endOffset)?.isDigit() == true) return
        val expression = if (node.type == GFMElementTypes.BLOCK_MATH) "\\displaystyle " + body else body
        val result = try { parser.parseWithDiagnostics(expression) } catch (_: Exception) { return }
        if (!result.hasDiagnostics && result.document.children.isNotEmpty() && isTypesettable(result.document)) {
            formulas[raw] = ChatFormula(raw, expression)
        }
    }
    visit(root)
    return formulas.values.toList()
}

private fun isTypesettable(node: LatexNode): Boolean =
    node !is LatexNode.Command && node !is LatexNode.Error && node.children().all(::isTypesettable)

internal class ChatMath(val content: MarkdownInlineContent, val annotator: MarkdownAnnotator)

@Composable
internal fun rememberChatMath(state: MarkdownState, fontSize: TextUnit): ChatMath {
    val markdown by state.state.collectAsState()
    val parsed = markdown as? State.Success
    val formulas by produceState(emptyList<ChatFormula>(), parsed) {
        value = if (parsed == null) emptyList() else withContext(Dispatchers.Default) { chatFormulas(parsed.node, parsed.content) }
    }
    val content = if (formulas.isEmpty()) markdownInlineContent() else {
        val color = LocalContentColor.current
        val config = remember(fontSize, color) { LatexConfig(fontSize = fontSize, theme = LatexTheme.light(color), accessibilityEnabled = true) }
        val measurer = rememberLatexMeasurer(config)
        val inline = remember(formulas, config, measurer) {
            buildMap<String, InlineTextContent> {
                for (formula in formulas) {
                    measurer.inlineContent(formula.expression, config)?.let { put(formula.source, it) }
                }
            }
        }
        markdownInlineContent(inline)
    }
    return remember(content) {
        val annotator = markdownAnnotator(chatAnnotatorConfig) { source, node ->
            if (node.type == ChatMathDelimiter || node.type == GFMTokenTypes.DOLLAR) {
                append(node.getTextInNode(source))
                return@markdownAnnotator true
            }
            if (!node.isMath) return@markdownAnnotator false
            val raw = node.getTextInNode(source).toString()
            if (raw !in content.inlineContent) return@markdownAnnotator false
            // Android drops a placeholder that straddles paragraphs. Keep its selection on one line.
            appendInlineContent(raw, alternateText = raw.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' '))
            true
        }
        ChatMath(content, annotator)
    }
}

@Composable
internal fun ChatMathParagraph(model: MarkdownComponentModel, math: ChatMath) {
    val display = model.node.children.singleOrNull()?.takeIf { it.type == GFMElementTypes.BLOCK_MATH }
    val raw = display?.getTextInNode(model.content)?.toString()
    if (raw != null && raw in math.content.inlineContent) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                MarkdownParagraph(model.content, model.node, Modifier.horizontalScroll(rememberScrollState()), style = model.typography.paragraph)
            }
            if ('\n' in raw || '\r' in raw) {
                val context = LocalContext.current
                IconButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("LaTeX", raw))
                }) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.chat_copy), Modifier.size(18.dp)) }
            }
        }
    } else {
        MarkdownParagraph(model.content, model.node, style = model.typography.paragraph)
    }
}
