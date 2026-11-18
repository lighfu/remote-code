package dev.remotecode.ui.chat

import dev.remotecode.R
import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotecode.data.CodeQuote
import dev.remotecode.data.codeQuoteFromLines
import dev.remotecode.ui.theme.Mono

/** 省略・ハイライト前の原文から選択する。 */
@Composable
fun CodeQuoteRangeDialog(
    path: String,
    text: String,
    isDraft: Boolean = false,
    initialStartLine: Int = 1,
    initialEndLine: Int = initialStartLine,
    onDismiss: () -> Unit,
    onQuote: (CodeQuote) -> Unit,
) {
    val source = remember(text) { text.replace("\r\n", "\n").replace('\r', '\n') }
    val lineCount = remember(source) { source.count { it == '\n' } + 1 }
    var start by remember(path, source) { mutableStateOf(initialStartLine.coerceIn(1, lineCount).toString()) }
    var end by remember(path, source) { mutableStateOf(initialEndLine.coerceIn(1, lineCount).toString()) }
    var operationError by remember { mutableStateOf<String?>(null) }
    val quote = remember(path, source, start, end, isDraft) {
        runCatching {
            codeQuoteFromLines(path, source, start.toIntOrNull() ?: 0, end.toIntOrNull() ?: 0, isDraft)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.FormatQuote, null) },
        title = { Text(stringResource(R.string.chat_code_quote_range_dialog_009)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(path, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.chat_code_quote_range_dialog_008, lineCount), style = MaterialTheme.typography.bodySmall)
                if (isDraft) Text(stringResource(R.string.chat_code_quote_range_dialog_007), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it.filter(Char::isDigit).take(7); operationError = null }, Modifier.weight(1f), label = { Text(stringResource(R.string.chat_code_quote_range_dialog_006)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(end, { end = it.filter(Char::isDigit).take(7); operationError = null }, Modifier.weight(1f), label = { Text(stringResource(R.string.chat_code_quote_range_dialog_005)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                quote.getOrNull()?.let { value ->
                    Text(stringResource(R.string.chat_code_quote_range_dialog_004, value.startLine, value.endLine, value.code.length), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
                        Text(value.code.take(2_000), Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, maxLines = 8, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                    if (value.code.length > 2_000 || value.code.count { it == '\n' } >= 8) Text(stringResource(R.string.chat_code_quote_range_dialog_003), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val message = operationError ?: quote.exceptionOrNull()?.message
                if (message != null) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = quote.isSuccess, onClick = {
                try { onQuote(quote.getOrThrow()); onDismiss() }
                catch (error: Exception) { operationError = error.message ?: appString(R.string.chat_code_quote_range_dialog_002) }
            }) { Text(stringResource(R.string.chat_code_quote_range_dialog_001)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) } },
    )
}
