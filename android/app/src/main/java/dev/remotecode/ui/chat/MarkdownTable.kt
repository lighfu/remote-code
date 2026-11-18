package dev.remotecode.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/** 標準のセル省略を避け、折り返し表示する。 */
@Composable
fun WrappingMarkdownTable(content: String, node: ASTNode, style: TextStyle) {
    val rows = node.children.filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }
    val cols = rows.maxOfOrNull { r -> r.children.count { it.type == GFMTokenTypes.CELL } } ?: 0
    if (cols == 0) return
    val fit = cols <= 3
    val annotator = annotatorSettings()
    val line = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, line),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Box(if (fit) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState())) {
            Column(if (fit) Modifier.fillMaxWidth() else Modifier.width(IntrinsicSize.Max)) {
                rows.forEachIndexed { i, r ->
                    val header = r.type == GFMElementTypes.HEADER
                    val cells = r.children.filter { it.type == GFMTokenTypes.CELL }
                    Row(
                        (if (fit) Modifier.fillMaxWidth() else Modifier)
                            .height(IntrinsicSize.Min)
                            .background(if (header) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent),
                    ) {
                        for (c in 0 until cols) {
                            Box(
                                (if (fit) Modifier.weight(1f) else Modifier.width(if (c == 0) 120.dp else 180.dp))
                                    .fillMaxHeight()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                cells.getOrNull(c)?.let { cell ->
                                    MarkdownTableBasicText(
                                        content,
                                        cell,
                                        if (header) style.copy(fontWeight = FontWeight.SemiBold) else style,
                                        Int.MAX_VALUE,
                                        TextOverflow.Clip,
                                        annotator,
                                    )
                                }
                            }
                            if (c < cols - 1) VerticalDivider(color = line)
                        }
                    }
                    if (i < rows.lastIndex) HorizontalDivider(color = line)
                }
            }
        }
    }
}
