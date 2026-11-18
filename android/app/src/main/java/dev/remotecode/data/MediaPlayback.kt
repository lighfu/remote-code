package dev.remotecode.data

import dev.remotecode.R
import dev.remotecode.i18n.appString
import java.util.Base64

/** トークンは URL に含めずヘッダーで渡す。 */
internal class MediaPlaybackSource(val url: String, val headers: Map<String, String>)

internal fun mediaPlaybackSource(server: ServerConfig, id: String, invalidIdMessage: () -> String = { appString(R.string.core_video_id_invalid) }): MediaPlaybackSource {
    require(id.isNotBlank() && id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) { invalidIdMessage() }
    return MediaPlaybackSource(
        server.url.trimEnd('/') + "/api/media/$id",
        buildMap {
            put("Authorization", "Bearer ${server.token}")
            if (server.hasAccess) {
                put("CF-Access-Client-Id", server.accessId)
                put("CF-Access-Client-Secret", server.accessSecret)
            }
        },
    )
}

internal fun videoFileName(id: String): String {
    val path = runCatching { String(Base64.getUrlDecoder().decode(id), Charsets.UTF_8) }.getOrNull()
    return path?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()?.takeIf { it.isNotBlank() } ?: "video.mp4"
}

internal fun videoMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "webm" -> "video/webm"
    "mov" -> "video/quicktime"
    "mkv" -> "video/x-matroska"
    "avi" -> "video/x-msvideo"
    "3gp", "3gpp" -> "video/3gpp"
    "ogv", "ogg" -> "video/ogg"
    else -> "video/mp4"
}
