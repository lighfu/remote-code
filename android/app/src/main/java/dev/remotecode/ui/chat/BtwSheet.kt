package dev.remotecode.ui.chat

import dev.remotecode.R
import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.BtwItem
import dev.remotecode.ui.common.hapticArea
import dev.remotecode.ui.common.rememberHaptics
import kotlinx.coroutines.launch

private fun quickQuestions() = listOf(
    appString(R.string.chat_btw_sheet_020),
    appString(R.string.chat_btw_sheet_019),
    appString(R.string.chat_btw_sheet_018),
    appString(R.string.chat_btw_sheet_017),
)

/** initial は開いた時に送信し、onUseInChat は入力欄へ戻す。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BtwSheet(sessionId: String, initial: String?, onDismiss: () -> Unit, onUseInChat: (String) -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val all by repo.btw.collectAsState()
    val items = all[sessionId].orEmpty()
    val pending = items.any { it.status == "pending" }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val context = LocalContext.current
    var text by dev.remotecode.ui.common.rememberDraft("btw:$sessionId")
    var error by remember { mutableStateOf<String?>(null) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()

    fun ask(q: String) {
        val question = q.trim()
        if (question.isEmpty()) return
        haptics.confirm()
        error = null
        scope.launch {
            try {
                repo.askBtw(sessionId, question)
                if (text.trim() == question) text = ""
            } catch (e: Exception) {
                error = e.message ?: appString(R.string.chat_btw_sheet_016)
                if (text.isBlank()) text = question
            }
        }
    }

    LaunchedEffect(sessionId) { repo.loadBtw(sessionId) }
    LaunchedEffect(sessionId, initial) {
        if (!initial.isNullOrBlank() && !asked) {
            asked = true
            ask(initial)
        }
    }
    LaunchedEffect(items.size, items.lastOrNull()?.status) {
        if (items.isNotEmpty()) list.animateScrollToItem(items.size - 1)
    }

    ModalBottomSheet(
        modifier = hapticArea(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxHeight(0.92f).navigationBarsPadding().imePadding()) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.QuestionAnswer, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.chat_btw_sheet_015), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (items.isNotEmpty()) {
                    TextButton(onClick = haptics.tick { scope.launch { runCatching { repo.removeBtw(sessionId) } } }) { Text(stringResource(R.string.chat_btw_sheet_014)) }
                }
            }
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (items.isEmpty()) {
                    item(key = "__empty") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(4.dp)) {
                            Text(
                                stringResource(R.string.chat_btw_sheet_013),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                quickQuestions().forEach { q -> AssistChip(onClick = { ask(q) }, label = { Text(q) }) }
                            }
                        }
                    }
                }
                items(items, key = { it.id }) { item ->
                    BtwTurnView(
                        item,
                        onCopy = {
                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText(appString(R.string.chat_btw_sheet_012), item.response.orEmpty()))
                            haptics.tick()
                        },
                        onRetry = {
                            scope.launch { runCatching { repo.removeBtw(sessionId, item.id) } }
                            ask(item.question)
                        },
                        onUseInChat = { onUseInChat(item.question) },
                        onRemove = { haptics.tick(); scope.launch { runCatching { repo.removeBtw(sessionId, item.id) } } },
                    )
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(if (pending) stringResource(R.string.chat_btw_sheet_011) else stringResource(R.string.chat_btw_sheet_010)) },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (!pending) ask(text) }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                FilledIconButton(
                    onClick = { ask(text) },
                    enabled = text.isNotBlank() && !pending,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(48.dp),
                ) {
                    if (pending) LoadingIndicator(Modifier.size(26.dp)) else Icon(Icons.Rounded.ArrowUpward, stringResource(R.string.chat_btw_sheet_009))
                }
            }
        }
    }
}

@Composable
private fun BtwTurnView(item: BtwItem, onCopy: () -> Unit, onRetry: () -> Unit, onUseInChat: () -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                ChatSelectionContainer {
                    Text(item.question, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
        when (item.status) {
            "pending" -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                LoadingIndicator(Modifier.size(28.dp))
                Spacer(Modifier.size(8.dp))
                Box(Modifier.weight(1f)) {
                    if (item.response.isNullOrEmpty()) {
                        Text(stringResource(R.string.chat_btw_sheet_008), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else ChatSelectionContainer { Text(item.response, style = MaterialTheme.typography.bodyLarge) }
                }
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, stringResource(R.string.chat_btw_sheet_007)) }
            }
            "error" -> Column(Modifier.padding(start = 4.dp)) {
                item.response?.takeIf { it.isNotEmpty() }?.let { MarkdownText(it) }
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(8.dp))
                    Text(item.error ?: stringResource(R.string.chat_btw_sheet_006), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Row {
                    TextButton(onClick = onRetry) { Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.size(4.dp)); Text(stringResource(R.string.chat_btw_sheet_005)) }
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.chat_btw_sheet_001)) }
                }
            }
            else -> Column {
                if (item.synthetic) {
                    Text(
                        stringResource(R.string.chat_btw_sheet_004),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                } else {
                    MarkdownText(item.response.orEmpty())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!item.synthetic) IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.chat_btw_sheet_003), Modifier.size(20.dp)) }
                    TextButton(onClick = onUseInChat) {
                        Icon(Icons.AutoMirrored.Rounded.Reply, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.chat_btw_sheet_002))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onRemove) { Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.chat_btw_sheet_001), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
