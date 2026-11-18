package dev.remotecode.ui.common

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import dev.remotecode.R
import dev.remotecode.RemoteCodeApp
import dev.remotecode.i18n.appString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** Publish a download only after the writer and the output stream have finished successfully. */
internal suspend fun writeDeviceMedia(
    collection: Uri, directory: String, name: String, mime: String, write: suspend (OutputStream) -> Unit,
): Unit = withContext(Dispatchers.IO) {
    val resolver = RemoteCodeApp.instance.contentResolver
    val metadata = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/Remote Code")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(collection, metadata) ?: error(appString(R.string.chat_link_sheet_002))
    try {
        val stream = resolver.openOutputStream(uri) ?: error(appString(R.string.chat_link_sheet_001))
        stream.use { write(it) }
        currentCoroutineContext().ensureActive()
        val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        check(resolver.update(uri, published, null, null) == 1) { appString(R.string.chat_link_sheet_001) }
    } catch (failure: Throwable) {
        try { resolver.delete(uri, null, null) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
        throw failure
    }
}
