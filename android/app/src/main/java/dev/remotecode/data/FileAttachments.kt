package dev.remotecode.data

import dev.remotecode.R
import dev.remotecode.i18n.appString
import dev.remotecode.i18n.AppStringResolver
import dev.remotecode.i18n.applicationStrings
import dev.remotecode.i18n.text
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream

internal const val MAX_ATTACHMENT_BYTES = 50L * 1024 * 1024
internal const val MAX_FILE_ATTACHMENTS = 10

@Serializable
data class UploadedFile(val id: String = "", val name: String = "", val size: Long = 0, val mime: String = "application/octet-stream")

/** 本体は送信時に読み取る。 */
internal class SelectedFile(val uri: Uri, val name: String, val size: Long?, val mime: String) {
    var uploadedFor: ConnectionIdentity? = null
    var uploaded: UploadedFile? = null
}

internal suspend fun readSelectedFile(context: Context, uri: Uri): SelectedFile = withContext(Dispatchers.IO) {
    var name: String? = null
    var size: Long? = null
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { column ->
                if (!cursor.isNull(column)) name = cursor.getString(column)
            }
            cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { column ->
                if (!cursor.isNull(column)) size = cursor.getLong(column).takeIf { it >= 0 }
            }
        }
    }
    val displayName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: appString(R.string.core_file)
    if ((size ?: 0L) > MAX_ATTACHMENT_BYTES) throw IOException(appString(R.string.core_attachment_named_too_large, displayName))
    SelectedFile(uri, displayName, size, context.contentResolver.getType(uri)?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
}

/** サイズの申告値に頼らず、読み取り中に上限を検証する。 */
internal class FileUploadBody(
    private val open: () -> InputStream,
    private val onProgress: (Long) -> Unit,
    private val checkActive: () -> Unit = {},
    private val maximum: Long = MAX_ATTACHMENT_BYTES,
    private val strings: AppStringResolver = applicationStrings,
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength(): Long = -1
    override fun isOneShot() = true

    override fun writeTo(sink: BufferedSink) {
        var total = 0L
        var lastProgress = 0L
        open().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (total + count > maximum) throw IOException(strings.text(R.string.core_attachment_too_large))
                sink.write(buffer, 0, count)
                total += count
                val now = System.nanoTime()
                if (now - lastProgress >= 100_000_000L) { onProgress(total); lastProgress = now }
            }
        }
        onProgress(total)
    }
}
