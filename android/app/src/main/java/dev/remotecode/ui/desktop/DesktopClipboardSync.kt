package dev.remotecode.ui.desktop

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import android.view.inspector.WindowInspector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.remotecode.R
import dev.remotecode.data.ApiException
import dev.remotecode.data.DesktopApi
import dev.remotecode.data.ServerConfig
import dev.remotecode.data.connectionIdentity
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

private const val ClipLimit = 64 * 1024
private const val ClipOrigin = "dev.remotecode.DESKTOP_CLIP"
private class ClipboardSession {
    val origin = UUID.randomUUID().toString()
    var localStamp = System.currentTimeMillis()
    var remoteSequence: Long? = null
}

/** Keep revision metadata while paused. Read and write the clipboard only while a session window has focus. */
@Composable
internal fun DesktopClipboardSync(api: DesktopApi, target: ServerConfig?, enabled: Boolean, active: Boolean, onError: (String?) -> Unit) {
    val context = LocalContext.current
    val errors = rememberUpdatedState(onError)
    val tooLarge = rememberUpdatedState(stringResource(R.string.desktop_clipboard_too_large))
    val failed = rememberUpdatedState(stringResource(R.string.desktop_clipboard_error))
    val label = rememberUpdatedState(stringResource(R.string.desktop_title))
    val session = remember(target?.connectionIdentity(), enabled) { if (enabled) ClipboardSession() else null }
    LaunchedEffect(api, target, session, active) {
        if (target == null || session == null || !active) return@LaunchedEffect
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return@LaunchedEffect
        val wake = Channel<Unit>(Channel.CONFLATED)
        val listener = ClipboardManager.OnPrimaryClipChangedListener { wake.trySend(Unit) }
        // A Compose sheet has its own window. Check all app windows so copies
        // in the desktop keyboard sheet also sync.
        fun focused() = WindowInspector.getGlobalWindowViews().any { it.hasWindowFocus() }
        var reportedFailure = false
        suspend fun sync() {
            if (!focused()) return
            val seq = session.remoteSequence ?: api.clipboard(target).sequence.also { session.remoteSequence = it }
            if (!focused()) return
            val description = clipboard.primaryClipDescription
            val stamp = description?.timestamp ?: 0L
            if (description != null && stamp > session.localStamp) {
                val extras = description.extras
                val isLocalText = extras?.getString(ClipOrigin) != session.origin &&
                    extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) != true &&
                    description.hasMimeType("text/*")
                if (isLocalText) {
                    val clip = clipboard.primaryClip
                    if (clip?.description?.timestamp != stamp) return
                    val value = if (clip.itemCount == 1) clip.getItemAt(0).text?.toString() else null
                    if (value != null && value.indexOf('\u0000') < 0) {
                        if (value.length > ClipLimit || value.toByteArray().size > ClipLimit) {
                            errors.value(tooLarge.value)
                            reportedFailure = false
                        } else {
                            try {
                                session.remoteSequence = api.copyToPc(target, seq, value).sequence
                            } catch (e: ApiException) {
                                if (e.code != 409) throw e
                                // A newer PC copy arrived since the last poll. Fetch it next.
                            }
                        }
                    }
                }
                session.localStamp = stamp
            }
            val update = api.clipboard(target, session.remoteSequence)
            if (!focused()) return
            // A phone copy may have arrived while the network request was in flight.
            if ((clipboard.primaryClipDescription?.timestamp ?: 0L) > session.localStamp) return
            if (update.tooLarge && update.sequence != session.remoteSequence) {
                errors.value(tooLarge.value)
                reportedFailure = false
            }
            update.text?.let { value ->
                val clip = ClipData.newPlainText(label.value, value)
                clip.description.extras = PersistableBundle().apply {
                    putString(ClipOrigin, session.origin)
                    putBoolean(ClipDescription.EXTRA_IS_REMOTE_DEVICE, true)
                }
                clipboard.setPrimaryClip(clip)
            }
            session.remoteSequence = update.sequence
            if (reportedFailure) errors.value(null)
            reportedFailure = false
        }
        clipboard.addPrimaryClipChangedListener(listener)
        try {
            while (true) {
                try {
                    sync()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!reportedFailure) errors.value(failed.value)
                    reportedFailure = true
                    if (e is ApiException && e.code in setOf(401, 403, 404, 501)) break
                }
                withTimeoutOrNull(1200) { wake.receive() }
            }
        } finally {
            clipboard.removePrimaryClipChangedListener(listener)
            wake.close()
        }
    }
}
