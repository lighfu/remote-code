package dev.remotecode.ui.chat

import dev.remotecode.R
import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotecode.data.CodeQuote

@Composable
internal fun CodeQuotePreviews(quotes: List<CodeQuote>, enabled: Boolean, onRemove: (CodeQuote) -> Unit) {
    if (quotes.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 4.dp)) {
        items(quotes, key = { it.id }) { quote ->
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.width(280.dp)) {
                Column(Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.FormatQuote, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        val name = quote.path.replace('\\', '/').substringAfterLast('/')
                        val lines = if (quote.startLine == quote.endLine) appString(R.string.chat_code_quote_previews_004, quote.startLine) else appString(R.string.chat_code_quote_previews_003, quote.startLine, quote.endLine)
                        Text("$name · $lines", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { onRemove(quote) }, enabled = enabled, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, stringResource(R.string.chat_code_quote_previews_002), Modifier.size(18.dp)) }
                    }
                    Text(quote.path, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (quote.isDraft) Text(stringResource(R.string.chat_code_quote_previews_001), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    Text(quote.code.take(1200), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
