package dev.remotecode.ui.chat

import dev.remotecode.R
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotecode.data.Entry

@Composable
fun ArtifactCard(e: Entry) {
    var open by rememberSaveable(e.id) { mutableStateOf(false) }
    var sent by rememberSaveable(e.id) { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val next = LocalNextStep.current
    val scheme = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Description, null, tint = scheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.chat_artifact_card_004), style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                    Text(e.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (e.status == "running") LoadingIndicator(Modifier.size(24.dp))
            }
            e.text?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant) }
            if (open && !e.detail.isNullOrBlank()) {
                HorizontalDivider()
                MarkdownText(e.detail)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!e.detail.isNullOrBlank()) TextButton(onClick = { open = !open }) { Text(if (open) stringResource(R.string.chat_close) else stringResource(R.string.chat_artifact_card_003)) }
                e.path?.let { p -> TextButton(onClick = { uri.openUri("file:///" + p.replace('\\', '/')) }) { Text(stringResource(R.string.chat_open_file)) } }
            }
            if (e.requestFeedback && next != null) {
                FilledTonalButton(
                    onClick = { sent = true; next.reply("Proceed") },
                    enabled = !sent,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (sent) stringResource(R.string.chat_artifact_card_002) else stringResource(R.string.chat_artifact_card_001)) }
            }
        }
    }
}
