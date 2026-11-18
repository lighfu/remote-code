package dev.remotecode.ui.chat

import dev.remotecode.R
import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.remotecode.ui.theme.Mono
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.LinkFileInfo
import dev.remotecode.data.connectionIdentity
import dev.remotecode.data.WebPreview
import dev.remotecode.ui.common.MediaStrip
import dev.remotecode.ui.common.VideoPlayerContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun isWeb(link: String) = link.startsWith("http://", true) || link.startsWith("https://", true)

private fun isOtherScheme(link: String) = Regex("^[a-zA-Z][a-zA-Z0-9+.-]{1,}:(?![\\\\/])").containsMatchIn(link) &&
    !Regex("^[a-zA-Z]:[\\\\/]").containsMatchIn(link) && !link.startsWith("file:", true)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkSheet(link: String, sessionId: String, onDismiss: () -> Unit) {
    if (!isWeb(link) && !isOtherScheme(link)) {
        FileViewer(link, sessionId, onDismiss)
        return
    }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (isWeb(link)) WebLink(link, onDismiss) else OtherLink(link, onDismiss)
        }
    }
}

@Composable
private fun Header(icon: ImageVector, title: String, sub: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp)) {
            Icon(icon, null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) {
                Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Address(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WebLink(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var preview by remember(url) { mutableStateOf<WebPreview?>(null) }
    var loading by remember(url) { mutableStateOf(true) }
    LaunchedEffect(url) {
        preview = runCatching { RemoteCodeApp.instance.repository.api.webPreview(url) }.getOrNull()
        loading = false
    }
    val host = runCatching { Uri.parse(url).host }.getOrNull()
    val p = preview
    Header(Icons.Rounded.Language, p?.title ?: host ?: appString(R.string.chat_link_sheet_021), p?.site ?: host)
    when {
        loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(Modifier.size(24.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.chat_link_sheet_020), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        !p?.description.isNullOrBlank() -> Text(p!!.description!!, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)
        p == null -> Text(stringResource(R.string.chat_link_sheet_019), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Address(url)
    if (p != null && p.finalUrl.trimEnd('/') != url.trimEnd('/')) {
        Text(stringResource(R.string.chat_link_sheet_018, p.finalUrl), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Actions(
        primary = appString(R.string.chat_link_sheet_017) to Icons.AutoMirrored.Rounded.OpenInNew,
        onPrimary = { openExternal(context, url); onDismiss() },
        copyText = url,
        shareText = url,
    )
}

@Composable
private fun OtherLink(link: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Header(Icons.AutoMirrored.Rounded.OpenInNew, appString(R.string.chat_link_sheet_016), link.substringBefore(':'))
    Address(link)
    Actions(
        primary = appString(R.string.chat_link_sheet_015) to Icons.AutoMirrored.Rounded.OpenInNew,
        onPrimary = { openExternal(context, link); onDismiss() },
        copyText = link,
        shareText = link,
    )
}

/** 読み込み中も全画面を保ち、シートを挟まない。 */
@Composable
private fun FileViewer(link: String, sessionId: String, onDismiss: () -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val sessions by repo.sessions.collectAsState()
    val launchEditor = LocalProjectEditor.current
    var info by remember(link, sessionId) { mutableStateOf<LinkFileInfo?>(null) }
    var source by remember(link, sessionId) { mutableStateOf<dev.remotecode.data.ConnectionIdentity?>(null) }
    var error by remember(link, sessionId) { mutableStateOf<String?>(null) }
    var retry by remember(link, sessionId) { mutableStateOf(0) }
    LaunchedEffect(link, sessionId, retry) {
        error = null; info = null; source = null
        try {
            val api = RemoteCodeApp.instance.repository.api
            val target = api.server
                ?: throw IllegalStateException(appString(R.string.chat_link_sheet_014))
            val expected = target.connectionIdentity()
            val result = api.fileInfo(link, sessionId, expectedServer = target)
            if (api.server?.connectionIdentity() != expected) throw IllegalStateException(appString(R.string.chat_code_viewer_024))
            source = expected
            info = result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: appString(R.string.chat_link_sheet_013)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val file = info
        if (file?.kind == "text") {
            val root = sessions.find { it.id == sessionId }?.cwd?.replace('\\', '/')?.trimEnd('/')
            val path = file.path.replace('\\', '/')
            val windows = root != null && ((root.length >= 2 && root[1] == ':') || root.startsWith("//"))
            val inProject = !root.isNullOrBlank() && (path.equals(root, ignoreCase = windows) || path.startsWith(root + "/", ignoreCase = windows))
            CodeViewerContent(file, link, sessionId, onDismiss, onEdit = if (inProject && !file.truncated && launchEditor != null) ({ onDismiss(); launchEditor(sessionId, file.path) }) else null, source = source)
        } else if (file?.kind == "video" && file.mediaId != null) {
            VideoPlayerContent(file.mediaId, file.name, onDismiss)
        } else {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.chat_close)) }
                        Text(file?.name ?: link.substringAfterLast('/').substringAfterLast('\\'), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (file != null) {
                            FileDetails(file, link, sessionId)
                        } else if (error != null) {
                            Text(error!!, color = MaterialTheme.colorScheme.error)
                            Button(onClick = { retry++ }) { Text(stringResource(R.string.chat_link_sheet_012)) }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LoadingIndicator(Modifier.size(24.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.chat_link_sheet_011), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileDetails(i: LinkFileInfo, link: String, sessionId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val icon = when (i.kind) {
        "image" -> Icons.Rounded.Image
        "text" -> Icons.Rounded.Description
        "dir" -> Icons.Rounded.Folder
        else -> Icons.Rounded.InsertDriveFile
    }
    val sub = when (i.kind) {
        "missing" -> appString(R.string.chat_link_sheet_010)
        "dir" -> appString(R.string.chat_link_sheet_009)
        else -> listOfNotNull(sizeText(i.size), i.line?.let { appString(R.string.chat_link_sheet_008, it) }).joinToString(" · ")
    }
    Header(icon, i.name, sub)
    if (i.kind == "image" && i.mediaId != null) MediaStrip(listOf(i.mediaId))
    Address(i.path)
    val canSave = i.exists && i.kind != "dir"
    Actions(
        primary = if (canSave) (if (saving) appString(R.string.chat_footer_display_dialog_011) else appString(R.string.chat_link_sheet_007)) to Icons.Rounded.Download else null,
        primaryEnabled = !saving,
        onPrimary = {
            saving = true
            scope.launch {
                val r = runCatching {
                    val bytes = RemoteCodeApp.instance.repository.api.fileRaw(link, sessionId)
                    saveToDownloads(i.name, i.mime, bytes)
                }
                saving = false
                Toast.makeText(context, r.fold({ appString(R.string.chat_code_viewer_015, i.name) }, { appString(R.string.chat_code_viewer_014, it.message) }), Toast.LENGTH_LONG).show()
            }
        },
        copyText = i.path,
        copyLabel = appString(R.string.chat_link_sheet_006),
    )
}

@Composable
private fun Actions(
    primary: Pair<String, ImageVector>? = null,
    primaryEnabled: Boolean = true,
    onPrimary: () -> Unit = {},
    copyText: String? = null,
    copyLabel: String = appString(R.string.chat_copy),
    shareText: String? = null,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (primary != null) {
            Button(onClick = onPrimary, enabled = primaryEnabled, modifier = Modifier.fillMaxWidth(), contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(primary.second, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(primary.first)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (copyText != null) {
                FilledTonalButton(onClick = {
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("link", copyText))
                    Toast.makeText(context, appString(R.string.chat_link_sheet_005), Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(copyLabel, maxLines = 1)
                }
            }
            if (shareText != null) {
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.chat_link_sheet_004), maxLines = 1)
                }
            }
        }
    }
}

private fun sizeText(n: Long): String = when {
    n >= 1024 * 1024 -> "%.1f MB".format(n / 1024.0 / 1024.0)
    n >= 1024 -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
}

private fun openExternal(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(ctx, appString(R.string.chat_link_sheet_003), Toast.LENGTH_SHORT).show() }
}

suspend fun saveToDownloads(name: String, mime: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
    val resolver = RemoteCodeApp.instance.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Remote Code")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error(appString(R.string.chat_link_sheet_002))
    try {
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error(appString(R.string.chat_link_sheet_001))
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
}
