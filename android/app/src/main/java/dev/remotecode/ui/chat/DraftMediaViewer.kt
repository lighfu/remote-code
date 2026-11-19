package dev.remotecode.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.remotecode.R
import dev.remotecode.data.ImagePayload
import dev.remotecode.data.SelectedFile
import dev.remotecode.ui.common.LocalVideoPreview
import dev.remotecode.ui.common.ZoomableImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class DraftMedia(val uri: Uri, val name: String, val video: Boolean, val imagePayload: ImagePayload? = null)
internal fun SelectedFile.previewMedia(): DraftMedia? {
    val type = if (mime == "application/octet-stream") {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()).orEmpty()
    } else mime
    return when {
        type.startsWith("image/", ignoreCase = true) -> DraftMedia(uri, name, video = false)
        type.startsWith("video/", ignoreCase = true) -> DraftMedia(uri, name, video = true)
        else -> null
    }
}

@Composable
internal fun DraftMediaViewer(gallery: DraftMediaGallery<DraftMedia>, onClose: () -> Unit) {
    val pager = rememberPagerState(initialPage = gallery.initialPage) { gallery.items.size }
    val scope = rememberCoroutineScope()
    val page = pager.currentPage
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.screen_remote_image_005)) }
                    Text(gallery.items[page].name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (gallery.items.size > 1) {
                        IconButton(onClick = { scope.launch { pager.animateScrollToPage(page - 1) } }, enabled = page > 0) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.chat_previous_attachment))
                        }
                        Text("${page + 1} / ${gallery.items.size}", style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { scope.launch { pager.animateScrollToPage(page + 1) } }, enabled = page < gallery.items.lastIndex) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.chat_next_attachment))
                        }
                    }
                }
                HorizontalPager(pager, modifier = Modifier.fillMaxSize().background(Color.Black)) { index ->
                    val media = gallery.items[index]
                    if (!media.video) DraftImagePreview(media)
                    else if (index == pager.settledPage) LocalVideoPreview(media.uri)
                }
            }
        }
    }
}

@Composable
private fun DraftImagePreview(media: DraftMedia) {
    val context = LocalContext.current
    var bitmap by remember(media) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(media) { mutableStateOf(false) }
    var attempt by remember(media) { mutableIntStateOf(0) }
    LaunchedEffect(media, attempt) {
        failed = false
        try {
            bitmap = withContext(Dispatchers.IO) { decodeDraftImage(context, media) }
            failed = bitmap == null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val image = bitmap
        when {
            image != null -> ZoomableImage { modifier -> Image(image.asImageBitmap(), media.name, modifier, contentScale = ContentScale.Fit) }
            failed -> Button(onClick = { attempt++ }) { Text(stringResource(R.string.screen_remote_image_004)) }
            else -> CircularProgressIndicator()
        }
    }
}

private fun decodeDraftImage(context: Context, media: DraftMedia): Bitmap? {
    // Show the exact JPEG that will be sent for images resized by the visual picker.
    media.imagePayload?.let {
        val bytes = Base64.decode(it.data, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
    return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, media.uri)) { decoder, info, _ ->
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > 2400) {
            decoder.setTargetSize(
                (info.size.width * 2400L / longest).toInt().coerceAtLeast(1),
                (info.size.height * 2400L / longest).toInt().coerceAtLeast(1),
            )
        }
    }
}
