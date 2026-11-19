package dev.remotecode.ui.common

import dev.remotecode.R

import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ConnectionIdentity
import dev.remotecode.data.MediaPlaybackSource
import dev.remotecode.data.connectionIdentity
import dev.remotecode.data.videoFileName
import dev.remotecode.data.videoMime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 本文やツールの折りたたみに関係なく表示する。 */
@Composable
fun EntryMedia(images: List<String>?, videos: List<String>?, modifier: Modifier = Modifier) {
    if (images.isNullOrEmpty() && videos.isNullOrEmpty()) return
    var opened by remember { mutableStateOf<String?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!images.isNullOrEmpty()) MediaStrip(images)
        videos.orEmpty().distinct().forEach { id ->
            Card(onClick = { opened = id }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlayCircle, stringResource(R.string.screen_video_media_001), Modifier.size(36.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(remember(id) { videoFileName(id) }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.screen_video_media_002), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    opened?.let { VideoViewer(it) { opened = null } }
}

@Composable
fun VideoViewer(id: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        VideoPlayerContent(id, onClose = onClose)
    }
}

/** 認証は MediaPlayer のヘッダーで渡す。 */
@Composable
fun VideoPlayerContent(id: String, name: String = videoFileName(id), writeOriginal: (suspend (java.io.OutputStream) -> Unit)? = null,
                       onViewHex: (() -> Unit)? = null, onClose: () -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val settings by repo.settings.collectAsState()
    val route by repo.route.collectAsState()
    val identity = remember(settings?.server, route) { repo.api.server?.connectionIdentity() }
    val source = remember(id, identity) { runCatching { repo.api.mediaPlayback(id) }.getOrNull() }
    VideoPlaybackContent(source, id, name, identity, writeOriginal, onViewHex, onClose)
}

@Composable
internal fun LocalVideoPreview(uri: Uri) {
    val source = remember(uri) { MediaPlaybackSource(uri.toString(), emptyMap()) }
    VideoPlaybackContent(source, source.url)
}

@Composable
private fun VideoPlaybackContent(
    source: MediaPlaybackSource?, id: String, name: String = "", identity: ConnectionIdentity? = null,
    writeOriginal: (suspend (java.io.OutputStream) -> Unit)? = null, onViewHex: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    var attempt by remember(id, identity) { mutableIntStateOf(0) }
    var loading by remember(id, identity, attempt) { mutableStateOf(true) }
    var error by remember(id, identity, attempt) { mutableStateOf<String?>(null) }
    var position by rememberSaveable(id, identity) { mutableIntStateOf(0) }
    var resumePlaying by rememberSaveable(id, identity) { mutableStateOf(true) }
    var player by remember(id, identity, attempt) { mutableStateOf<VideoView?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var saving by remember(id) { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize()) {
        Column(if (onClose == null) Modifier.fillMaxSize() else Modifier.fillMaxSize().systemBarsPadding()) {
            if (onClose != null) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.screen_video_media_003)) }
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(enabled = !saving, onClick = {
                    saving = true
                    scope.launch {
                        try {
                            saveVideoToDevice(id, name, writeOriginal)
                            Toast.makeText(context, appString(R.string.screen_video_media_004, name), Toast.LENGTH_LONG).show()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Toast.makeText(context, appString(R.string.screen_video_media_005, e.message), Toast.LENGTH_LONG).show()
                        } finally { saving = false }
                    }
                }) {
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Download, stringResource(R.string.screen_video_media_006))
                }
                if (onViewHex != null) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.chat_more)) }
                        AppDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.file_view_as_hex)) }, onClick = {
                                menu = false
                                if (!loading) player?.let { position = it.currentPosition; resumePlaying = it.isPlaying }
                                onViewHex()
                            })
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (source != null && error == null && lifecycle.isAtLeast(Lifecycle.State.STARTED)) {
                    key(id, identity, attempt) {
                        var controller by remember { mutableStateOf<MediaController?>(null) }
                        DisposableEffect(owner, player) {
                            val view = player
                            val controls = controller
                            val observer = LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_PAUSE && view != null) {
                                    position = view.currentPosition
                                    resumePlaying = view.isPlaying
                                    view.pause()
                                } else if (event == Lifecycle.Event.ON_RESUME && resumePlaying) view?.start()
                            }
                            owner.lifecycle.addObserver(observer)
                            onDispose {
                                owner.lifecycle.removeObserver(observer)
                                if (view != null) {
                                    if (view.currentPosition > 0) position = view.currentPosition
                                    controls?.hide()
                                    view.setOnPreparedListener(null)
                                    view.setOnErrorListener(null)
                                    view.stopPlayback()
                                }
                            }
                        }
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    val controls = MediaController(ctx)
                                    controls.setAnchorView(this)
                                    setMediaController(controls)
                                    controller = controls
                                    player = this
                                    loading = true
                                    setOnPreparedListener {
                                        loading = false
                                        if (position > 0) seekTo(position)
                                        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                            if (resumePlaying) start()
                                            controls.show(3_000)
                                        }
                                    }
                                    setOnErrorListener { _, _, _ ->
                                        loading = false
                                        error = appString(if (onClose == null) R.string.chat_attachment_video_error else R.string.screen_video_media_007)
                                        true
                                    }
                                    setVideoURI(Uri.parse(source.url), source.headers)
                                    requestFocus()
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                            onRelease = { it.stopPlayback() },
                        )
                    }
                }
                if (source == null || error != null) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(error ?: appString(R.string.screen_video_media_008), color = MaterialTheme.colorScheme.error)
                        if (source != null) Button(onClick = { attempt++ }) { Text(stringResource(R.string.screen_video_media_009)) }
                    }
                } else if (loading) LoadingIndicator()
            }
        }
    }
}

/** 原本をストリーム保存し、全体をメモリに載せない。 */
private suspend fun saveVideoToDevice(id: String, name: String, writeOriginal: (suspend (java.io.OutputStream) -> Unit)?) {
    writeDeviceMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES, name, videoMime(name)) { output ->
        if (writeOriginal != null) writeOriginal(output) else RemoteCodeApp.instance.repository.api.copyMediaTo(id, output)
    }
}
