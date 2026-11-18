package dev.remotecode.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.remotecode.R
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.LinkFileInfo
import dev.remotecode.data.ServerConfig
import dev.remotecode.data.connectionIdentity
import dev.remotecode.ui.theme.Mono
import kotlinx.coroutines.CancellationException

@Composable
internal fun HexViewer(file: LinkFileInfo, sessionId: String, server: ServerConfig, onReload: () -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val active by repo.activeServerIdentity.collectAsState()
    if (active != server.connectionIdentity()) {
        Text(stringResource(R.string.chat_code_viewer_024), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        return
    }
    if (file.size == 0L) {
        Text(stringResource(R.string.hex_empty), Modifier.padding(16.dp))
        return
    }
    var address by rememberSaveable(file.path, file.size, file.mtime) { mutableLongStateOf(0L) }
    var jumpNumber by remember { mutableIntStateOf(0) }
    var showJump by remember { mutableStateOf(false) }
    var etag by remember(file, server) { mutableStateOf<String?>(null) }
    val page = hexPageStart(address)
    val count = minOf(HEX_PAGE_BYTES.toLong(), file.size - page).toInt()
    var data by remember(file, server, page) { mutableStateOf<ByteArray?>(null) }
    var error by remember(file, server, page) { mutableStateOf<String?>(null) }
    val failureText = stringResource(R.string.chat_link_sheet_013)
    LaunchedEffect(file, server, page) {
        try {
            val result = repo.api.fileHexPage(file.path, sessionId, page, count, file.size, server, etag)
            etag = result.etag
            data = result.bytes
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.localizedMessage ?: failureText
        }
    }
    val digits = maxOf(8, (file.size - 1).toString(16).length)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = page > 0, onClick = { address = page - HEX_PAGE_BYTES }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.hex_previous))
            }
            Text(stringResource(R.string.hex_page_range, hexAddress(page, digits), hexAddress(page + (count - 1), digits), file.size),
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { showJump = true }) {
                Icon(Icons.Rounded.Search, stringResource(R.string.hex_jump))
            }
            IconButton(enabled = file.size - page > count, onClick = { address = page + count }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.hex_next))
            }
        }
        HorizontalDivider()
        val loaded = data
        when {
            error != null -> Column(Modifier.padding(16.dp)) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onReload) { Text(stringResource(R.string.hex_reload)) }
            }
            loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = if (maxWidth >= 570.dp) 16 else 8
                val list = rememberLazyListState()
                LaunchedEffect(page, address, columns, jumpNumber) {
                    list.scrollToItem(((address - page) / columns).toInt())
                }
                val style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 12.sp, letterSpacing = 0.sp)
                val measure = rememberTextMeasurer()
                val density = LocalDensity.current
                val cell = remember(measure, style, density) { with(density) { measure.measure("0", style).size.width.toDp() } }
                val offsetWidth = cell * digits + 12.dp
                val bytesWidth = cell * (columns * 3 - 1) + 12.dp
                val asciiWidth = cell * columns + 12.dp
                val totalWidth = maxOf(maxWidth, offsetWidth + bytesWidth + asciiWidth + 16.dp)
                val accent = MaterialTheme.colorScheme.primaryContainer
                val ink = MaterialTheme.colorScheme.onPrimaryContainer
                Column(Modifier.horizontalScroll(rememberScrollState()).width(totalWidth).fillMaxHeight().padding(horizontal = 8.dp)) {
                    Row(Modifier.padding(vertical = 6.dp)) {
                        Text(stringResource(R.string.hex_column_offset), Modifier.width(offsetWidth), style = MaterialTheme.typography.labelSmall)
                        Text(stringResource(R.string.hex_column_bytes), Modifier.width(bytesWidth), style = MaterialTheme.typography.labelSmall)
                        Text("ASCII", style = MaterialTheme.typography.labelSmall)
                    }
                    SelectionContainer {
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                            items((loaded.size + columns - 1) / columns) { index ->
                                val start = index * columns
                                val row = remember(loaded, start, columns) { hexRow(loaded, start, columns) }
                                val position = (address - page).toInt() - start
                                val selected = jumpNumber > 0 && position in row.ascii.indices
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                    Text(hexAddress(page + start, digits), Modifier.width(offsetWidth), style = style, softWrap = false,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(buildAnnotatedString {
                                        append(row.bytes)
                                        if (selected) addStyle(SpanStyle(color = ink, background = accent), position * 3, position * 3 + 2)
                                    }, Modifier.width(bytesWidth), style = style, softWrap = false)
                                    Text(buildAnnotatedString {
                                        append(row.ascii)
                                        if (selected) addStyle(SpanStyle(color = ink, background = accent), position, position + 1)
                                    }, style = style, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showJump) {
        var input by rememberSaveable { mutableStateOf("") }
        val target = parseHexOffset(input, file.size)
        AlertDialog(onDismissRequest = { showJump = false }, title = { Text(stringResource(R.string.hex_jump)) },
            text = {
                OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
                    label = { Text(stringResource(R.string.hex_offset)) },
                    supportingText = { Text(stringResource(R.string.hex_offset_hint, "0x" + hexAddress(file.size - 1, 1))) },
                    isError = input.isNotBlank() && target == null)
            },
            confirmButton = { TextButton(enabled = target != null, onClick = {
                if (target != null) { address = target; jumpNumber++; showJump = false }
            }) { Text(stringResource(R.string.hex_jump)) } },
            dismissButton = { TextButton(onClick = { showJump = false }) { Text(stringResource(R.string.cross_session_cancel)) } })
    }
}
