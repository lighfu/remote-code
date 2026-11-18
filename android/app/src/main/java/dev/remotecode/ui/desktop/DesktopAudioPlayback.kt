package dev.remotecode.ui.desktop

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.remotecode.R
import dev.remotecode.data.DesktopApi
import dev.remotecode.data.DesktopStreamHttpError
import dev.remotecode.data.DesktopStreamUnsupported
import dev.remotecode.data.ServerConfig
import dev.remotecode.i18n.appString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

private val playbackAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()

@Composable
internal fun desktopAudioPlayback(api: DesktopApi, target: ServerConfig?, enabled: Boolean): String? {
    val manager = LocalContext.current.getSystemService(AudioManager::class.java)
    var focused by remember(target, enabled) { mutableStateOf(false) }
    var error by remember(target, enabled) { mutableStateOf<String?>(null) }
    var unavailable by remember(target, enabled) { mutableStateOf(false) }
    DisposableEffect(manager, target, enabled, unavailable) {
        if (!enabled || target == null || unavailable) return@DisposableEffect onDispose { }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(playbackAttributes).setWillPauseWhenDucked(true)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { focus -> focused = focus == AudioManager.AUDIOFOCUS_GAIN }
            .build()
        focused = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        onDispose {
            manager.abandonAudioFocusRequest(request)
            focused = false
        }
    }
    LaunchedEffect(api, target, enabled, focused) {
        if (!enabled || target == null || !focused) return@LaunchedEffect
        var retryMs = 1000L
        while (true) {
            try {
                withContext(Dispatchers.IO) {
                    playDesktopAudio(api, target) {
                        error = null
                        retryMs = 1000L
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = if (e is DesktopStreamHttpError) appString(R.string.desktop_audio_http_error, e.status)
                    else appString(R.string.desktop_audio_error)
                if (e is DesktopStreamUnsupported || e is DesktopStreamHttpError && e.status in listOf(401, 403, 404, 501)) {
                    unavailable = true
                    break
                }
            }
            delay(retryMs)
            retryMs = (retryMs * 2).coerceAtMost(8000L)
        }
    }
    return if (enabled && target != null && !focused && !unavailable) appString(R.string.desktop_audio_focus) else error
}

private suspend fun playDesktopAudio(api: DesktopApi, target: ServerConfig, onPlaying: () -> Unit) {
    val channels = AudioFormat.CHANNEL_OUT_STEREO
    val pcm = AudioFormat.ENCODING_PCM_16BIT
    val minBuffer = AudioTrack.getMinBufferSize(48000, channels, pcm)
    check(minBuffer > 0)
    val format = AudioFormat.Builder().setSampleRate(48000).setEncoding(pcm).setChannelMask(channels).build()
    val track = AudioTrack.Builder().setAudioAttributes(playbackAttributes).setAudioFormat(format)
        .setBufferSizeInBytes(maxOf(minBuffer, 7680)).setTransferMode(AudioTrack.MODE_STREAM).build()
    try {
        check(track.state == AudioTrack.STATE_INITIALIZED)
        api.audio(target).collect { packet ->
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
                onPlaying()
            }
            var offset = 0
            while (offset < packet.size) {
                currentCoroutineContext().ensureActive()
                val written = track.write(packet, offset, packet.size - offset, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) throw IOException("AudioTrack write failed ($written)")
                offset += written
            }
        }
    } finally {
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
                track.flush()
            }
        } finally {
            track.release()
        }
    }
}
