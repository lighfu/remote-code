package dev.remotecode.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotecode.R
import dev.remotecode.data.ServerConfig
import dev.remotecode.i18n.appString
import dev.remotecode.ui.common.hapticArea
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun ServerConfig.pickerHost(): String? = candidates.asReversed().firstNotNullOfOrNull { url ->
    val uri = runCatching { URI(url) }.getOrNull()
    uri?.host?.let { host -> if (uri.port < 0) host else "$host:${uri.port}" }
}

@Composable
internal fun ServerPickerDialog(
    servers: List<ServerConfig>,
    active: ServerConfig,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onSelect: suspend (ServerConfig) -> Boolean,
    onForget: suspend (ServerConfig) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val genericError = stringResource(R.string.server_picker_failed)
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmForget by remember { mutableStateOf<ServerConfig?>(null) }
    fun change(action: suspend () -> Boolean, after: () -> Unit = {}) {
        working = true
        error = null
        scope.launch {
            try {
                if (action()) after() else error = genericError
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = genericError
            } finally {
                working = false
            }
        }
    }

    AlertDialog(
        modifier = hapticArea(),
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.server_picker_title)) },
        text = {
            Column {
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                    for (server in servers) {
                        val current = server.token == active.token
                        val host = server.pickerHost()
                        val name = server.name.ifBlank { host ?: appString(R.string.server_picker_default_pc) }
                        ListItem(
                            headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = if (host != null && host != name) ({ Text(host, maxLines = 1, overflow = TextOverflow.Ellipsis) }) else null,
                            leadingContent = { RadioButton(selected = current, onClick = null) },
                            trailingContent = if (!current) ({
                                IconButton(onClick = { confirmForget = server }, enabled = !working) {
                                    Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.server_picker_forget, name))
                                }
                            }) else null,
                            modifier = Modifier.selectable(
                                selected = current,
                                enabled = !working && !current,
                                role = Role.RadioButton,
                                onClick = { change({ onSelect(server) }, after = onDismiss) },
                            ),
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = onAdd, enabled = !working) { Text(stringResource(R.string.server_picker_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) { Text(stringResource(R.string.core_close)) }
        },
    )

    confirmForget?.let { server ->
        AlertDialog(
            modifier = hapticArea(),
            onDismissRequest = { confirmForget = null },
            title = { Text(stringResource(R.string.server_picker_forget_title)) },
            text = { Text(stringResource(R.string.server_picker_forget, server.name.ifBlank { server.pickerHost() ?: appString(R.string.server_picker_default_pc) })) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = null
                    change({ onForget(server) })
                }) { Text(stringResource(R.string.server_picker_forget_title)) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = null }) { Text(stringResource(R.string.core_cancel)) } },
        )
    }
}
