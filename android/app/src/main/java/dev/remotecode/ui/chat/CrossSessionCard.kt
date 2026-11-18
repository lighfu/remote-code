package dev.remotecode.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotecode.R
import dev.remotecode.data.CrossSessionMessage
import dev.remotecode.i18n.appString
import dev.remotecode.ui.common.AppDropdownMenu
import dev.remotecode.ui.theme.ChatFont

val LocalCrossSessionReply = staticCompositionLocalOf<((String) -> Unit)?> { null }

@Composable
fun CrossSessionCard(message: CrossSessionMessage) {
    val context = LocalContext.current
    val replyToDraft = LocalCrossSessionReply.current
    val sender = message.senderName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.cross_session_unknown_sender)
    var expanded by rememberSaveable(message.senderSessionId, message.text) { mutableStateOf(false) }
    var overflow by remember(message.text) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var showReply by remember { mutableStateOf(false) }
    var reply by rememberSaveable(message.senderSessionId) { mutableStateOf("") }
    fun copy(value: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(sender, value))
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Forum, null)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.cross_session_title), style = MaterialTheme.typography.labelSmall)
                    Text(sender, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Column {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.cross_session_title)) }
                    AppDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.cross_session_copy_body)) }, onClick = { menu = false; copy(message.text) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.cross_session_copy_sender)) }, onClick = {
                            menu = false
                            copy(listOfNotNull(message.senderName, message.senderSessionId, message.senderAddress).joinToString("\n"))
                        })
                    }
                }
            }
            message.senderSessionId?.takeIf { it.isNotBlank() }?.let { id ->
                Text(stringResource(R.string.cross_session_sender_id, id), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ChatSelectionContainer {
                Text(
                    text = if (expanded) message.text else message.text.take(4000),
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = ChatFont),
                    maxLines = if (expanded) Int.MAX_VALUE else 5,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow || message.text.length > 4000 },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (overflow || expanded) TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.cross_session_collapse else R.string.cross_session_expand))
                }
                if (replyToDraft != null && !message.senderAddress.isNullOrBlank()) TextButton(onClick = { showReply = true }) {
                    Icon(Icons.AutoMirrored.Rounded.Reply, null)
                    Text(stringResource(R.string.cross_session_reply))
                }
            }
        }
    }

    if (showReply && replyToDraft != null) AlertDialog(
        onDismissRequest = { showReply = false },
        title = { Text(stringResource(R.string.cross_session_reply_title)) },
        text = {
            OutlinedTextField(value = reply, onValueChange = { reply = it }, label = { Text(stringResource(R.string.cross_session_reply_hint)) }, minLines = 3,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = ChatFont))
        },
        confirmButton = {
            TextButton(enabled = reply.isNotBlank(), onClick = {
                replyToDraft(appString(R.string.cross_session_reply_draft, sender, message.senderAddress.orEmpty(), reply.trim()))
                showReply = false
                reply = ""
            }) { Text(stringResource(R.string.cross_session_add_draft)) }
        },
        dismissButton = { TextButton(onClick = { showReply = false }) { Text(stringResource(R.string.cross_session_cancel)) } },
    )
}
