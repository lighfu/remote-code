package dev.remotecode.ui.common

import dev.remotecode.R

import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ConnectionIdentity
import dev.remotecode.data.SharedValues
import dev.remotecode.data.connectionIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object ImageCache {
    private data class Key(val server: ConnectionIdentity?, val id: String, val maxSide: Int)
    private data class Cached(val bitmap: Bitmap, val at: Long)
    private val cache = object : LruCache<Key, Cached>(48 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Cached) = value.bitmap.byteCount
    }
    private val decoding by lazy { SharedValues<Key, Bitmap?>(RemoteCodeApp.instance.scope) }

    suspend fun load(id: String, maxSide: Int, server: ConnectionIdentity?): Bitmap? {
        val key = Key(server, id, maxSide)
        cache.get(key)?.takeIf { System.currentTimeMillis() - it.at < 60_000 }?.let { return it.bitmap }
        return decoding.get(key) {
            val api = RemoteCodeApp.instance.repository.api
            if (api.server?.connectionIdentity() != server) return@get null
            val bytes = runCatching { api.media(id) }.getOrNull() ?: return@get null
            val bmp = withContext(Dispatchers.Default) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@get null
            if (api.server?.connectionIdentity() != server) return@get null
            cache.put(key, Cached(bmp, System.currentTimeMillis()))
            bmp
        }
    }
}

/** 画像 ID は PC の絶対パスを base64url 化したもの。 */
private fun mediaFileName(id: String): String {
    val path = runCatching { String(Base64.decode(id, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull()
    val name = path?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
    return name.ifEmpty { "image_${System.currentTimeMillis()}.png" }
}

private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "svg" -> "image/svg+xml"
    else -> "image/png"
}

suspend fun saveMediaToDevice(id: String, writeOriginal: (suspend (java.io.OutputStream) -> Unit)? = null): String {
    val name = mediaFileName(id)
    writeDeviceMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES, name, mimeOf(name)) { output ->
        if (writeOriginal != null) writeOriginal(output) else output.write(RemoteCodeApp.instance.repository.api.media(id, fresh = true))
    }
    return name
}

@Composable
fun RemoteImage(id: String, modifier: Modifier = Modifier, maxSide: Int = 720, contentScale: ContentScale = ContentScale.Fit) {
    val repository = RemoteCodeApp.instance.repository
    val settings by repository.settings.collectAsState()
    val route by repository.route.collectAsState()
    val server = remember(settings?.server, route) { repository.api.server?.connectionIdentity() }
    var bmp by remember(id, maxSide, server) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(id, maxSide, server) { mutableStateOf(false) }
    var attempt by remember(id, maxSide, server) { mutableIntStateOf(0) }
    LaunchedEffect(id, maxSide, server, attempt) {
        failed = false
        val b = ImageCache.load(id, maxSide, server)
        if (b == null) failed = true else bmp = b
    }
    val b = bmp
    when {
        b != null -> Image(b.asImageBitmap(), contentDescription = appString(R.string.screen_remote_image_003), contentScale = contentScale, modifier = modifier)
        failed -> Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            TextButton(onClick = { attempt++ }) {
                Icon(Icons.Rounded.BrokenImage, null, Modifier.size(18.dp))
                Text(stringResource(R.string.screen_remote_image_004), Modifier.padding(start = 6.dp))
            }
        }
        else -> Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            LoadingIndicator(Modifier.size(32.dp))
        }
    }
}

@Composable
fun MediaStrip(ids: List<String>, modifier: Modifier = Modifier, writeOriginal: (suspend (java.io.OutputStream) -> Unit)? = null) {
    var open by remember { mutableStateOf<String?>(null) }
    if (ids.size == 1) {
        RemoteImage(
            ids[0],
            modifier = modifier
                .widthIn(max = 320.dp)
                .heightIn(max = 260.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable { open = ids[0] },
        )
    } else {
        Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ids.forEach { id ->
                RemoteImage(
                    id,
                    maxSide = 360,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(140.dp).clip(RoundedCornerShape(16.dp)).clickable { open = id },
                )
            }
        }
    }
    open?.let { ImageViewer(it, writeOriginal) { open = null } }
}

@Composable
internal fun ZoomableImage(content: @Composable (Modifier) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 8f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(
        Modifier.fillMaxSize().clip(androidx.compose.ui.graphics.RectangleShape)
            .transformable(state = transform, canPan = { scale > 1f }),
    ) {
        content(
            Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        )
    }
}

@Composable
fun ImageViewer(id: String, writeOriginal: (suspend (java.io.OutputStream) -> Unit)? = null, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ZoomableImage { modifier -> RemoteImage(id, maxSide = 2400, modifier = modifier) }
            FilledTonalIconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)) {
                Icon(Icons.Rounded.Close, stringResource(R.string.screen_remote_image_005))
            }
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var saving by remember { mutableStateOf(false) }
            FilledTonalButton(
                onClick = {
                    saving = true
                    scope.launch {
                        val r = runCatching { saveMediaToDevice(id, writeOriginal) }
                        saving = false
                        val msg = r.fold({ appString(R.string.screen_remote_image_006, it) }, { appString(R.string.screen_remote_image_007, it.message) })
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                },
                enabled = !saving,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp),
            ) {
                if (saving) LoadingIndicator(Modifier.size(18.dp)) else Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                Text(if (saving) appString(R.string.screen_remote_image_008) else appString(R.string.screen_remote_image_009), maxLines = 1)
            }
        }
    }
}
