package dev.remotecode.ui.chat

import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.remotecode.R

val LocalConversationQuote = staticCompositionLocalOf<((String) -> Unit)?> { null }
private data object QuoteSelectionKey

@Composable
fun ChatSelectionContainer(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val quote = LocalConversationQuote.current
    if (quote == null) {
        SelectionContainer(modifier, content)
        return
    }
    val selection = rememberSelectionState()
    val label = stringResource(R.string.screen_project_editor_015)
    SelectionContainer(
        state = selection,
        modifier = modifier.appendTextContextMenuComponents {
            if (selection.selectedTexts.any { it.text.isNotBlank() }) {
                item(key = QuoteSelectionKey, label = label) {
                    val text = selection.selectedTexts.joinToString("\n") { it.text }
                    close()
                    selection.clear()
                    if (text.isNotBlank()) quote(text)
                }
            }
        },
        content = content,
    )
}
