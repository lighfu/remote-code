package dev.remotecode.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

const val DEFAULT_BODY_FONT = "Noto Serif JP"
const val DEFAULT_CODE_FONT = "JetBrains Mono"

data class AppSettings(
    val server: ServerConfig?,
    val backgroundNotify: Boolean,
    val lastProvider: String,
    val lastCwd: String,
    /** full / summary / off */
    val thinking: String = "full",
    val toolDetail: Boolean = true,
    val appearance: Appearance = Appearance(),
    val footerDisplay: ChatFooterDisplay = ChatFooterDisplay(),
    val codeWrap: Boolean = true,
    val desktop: DesktopPreferences = DesktopPreferences(),
    val toolWrap: ToolWrapPreferences = ToolWrapPreferences(),
)

data class ToolWrapPreferences(val input: Boolean = true, val output: Boolean = false)

enum class DesktopResolution(val wire: String) {
    Standard("normal"), High("high"), Native("native");

    companion object {
        fun restore(value: String?, highDetail: Boolean) =
            entries.find { it.wire == value } ?: if (highDetail) High else Standard
    }
}

sealed interface DesktopCompression {
    data class Jpeg(val quality: Int) : DesktopCompression

    enum class Png(val wire: String) : DesktopCompression {
        None("none"), Speed("speed"), Balanced("balanced"), Best("best"),
    }
}

data class DesktopPreferences(
    val fps: Int = 10,
    val resolution: DesktopResolution = DesktopResolution.Standard,
    val debugOverlay: Boolean = false,
    val capture: String = "auto",
    val codec: String = "jpeg",
    val touchpad: Boolean = true,
    val pointerSpeed: Float = 1f,
    val control: Boolean = true,
    val clipboard: Boolean = false,
    val bitrateMbps: Int = 0,
    val graphMask: Int = 3,
    val manualBitrateMbps: Int = 32,
    val decodeColor: DesktopDecodeColor = DesktopDecodeColor.Auto,
    val decodeLatest: Boolean = true,
    val pixelated: Boolean = false,
    val fixedJpegQuality: Boolean = false,
    val jpegQuality: Int = defaultJpegQuality,
    val pngCompression: DesktopCompression.Png? = null,
    val audio: Boolean = false,
) {
    fun compressionFor(codec: String, supported: Boolean): DesktopCompression? = when {
        !supported -> null
        codec == "png" -> pngCompression
        codec == "jpeg" && fixedJpegQuality -> DesktopCompression.Jpeg(jpegQuality)
        else -> null
    }

    companion object {
        const val defaultJpegQuality = 85
        val jpegQualities = 1..100
        val frameRates = 1..60
        val manualBitrates = 1..150
        val pointerSpeeds = listOf(0.5f, 1f, 1.5f, 2f)
    }
}

data class Appearance(
    /** system / light / dark */
    val themeMode: String = "system",
    val dynamicColor: Boolean = true,
    /** dynamicColor 無効時の色。 */
    val accent: String = "violet",
    val amoled: Boolean = false,
    /** Google Fonts 名。空ならシステム標準。 */
    val bodyFont: String = DEFAULT_BODY_FONT,
    /** 空ならシステムの等幅。 */
    val codeFont: String = DEFAULT_CODE_FONT,
    val textScale: Float = 1f,
    val codeScale: Float = 1f,
    val uiFont: String = "",
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val url = stringPreferencesKey("server_url")
        val token = stringPreferencesKey("server_token")
        val name = stringPreferencesKey("server_name")
        val accessId = stringPreferencesKey("cf_access_id")
        val accessSecret = stringPreferencesKey("cf_access_secret")
        val publicUrl = stringPreferencesKey("public_url")
        val backgroundNotify = booleanPreferencesKey("background_notify")
        val lastProvider = stringPreferencesKey("last_provider")
        val lastCwd = stringPreferencesKey("last_cwd")
        val thinking = stringPreferencesKey("thinking_mode")
        val toolDetail = booleanPreferencesKey("tool_detail")
        val codeWrap = booleanPreferencesKey("code_wrap")
        val toolInputWrap = booleanPreferencesKey("tool_input_wrap")
        val toolOutputWrap = booleanPreferencesKey("tool_output_wrap")
        val footerContext = booleanPreferencesKey("chat_footer_context")
        val footerCache = booleanPreferencesKey("chat_footer_cache")
        val footerUsage = booleanPreferencesKey("chat_footer_usage")
        val footerStats = booleanPreferencesKey("chat_footer_stats")
        val footerGit = booleanPreferencesKey("chat_footer_git")
        val footerOrder = stringPreferencesKey("chat_footer_order")
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val accent = stringPreferencesKey("accent")
        val amoled = booleanPreferencesKey("amoled")
        val uiFont = stringPreferencesKey("ui_font")
        val bodyFont = stringPreferencesKey("body_font")
        val codeFont = stringPreferencesKey("code_font")
        val textScale = floatPreferencesKey("text_scale")
        val codeScale = floatPreferencesKey("code_scale")
        val desktopFps = intPreferencesKey("desktop_fps")
        val desktopHighDetail = booleanPreferencesKey("desktop_high_detail")
        val desktopResolution = stringPreferencesKey("desktop_resolution")
        val desktopDebugOverlay = booleanPreferencesKey("desktop_debug_overlay")
        val desktopBitrateMbps = intPreferencesKey("desktop_bitrate_mbps")
        val desktopManualBitrateMbps = intPreferencesKey("desktop_manual_bitrate_mbps")
        val desktopDecodeColor = stringPreferencesKey("desktop_decode_color")
        val desktopDecodeLatest = booleanPreferencesKey("desktop_decode_latest")
        val desktopPixelated = booleanPreferencesKey("desktop_pixelated")
        val desktopGraphMask = intPreferencesKey("desktop_graph_mask")
        val desktopCapture = stringPreferencesKey("desktop_capture")
        val desktopCodec = stringPreferencesKey("desktop_codec")
        val desktopFixedJpegQuality = booleanPreferencesKey("desktop_fixed_jpeg_quality")
        val desktopJpegQuality = intPreferencesKey("desktop_jpeg_quality")
        val desktopPngCompression = stringPreferencesKey("desktop_png_compression")
        val desktopTouchpad = booleanPreferencesKey("desktop_touchpad")
        val desktopPointerSpeed = floatPreferencesKey("desktop_pointer_speed")
        val desktopControl = booleanPreferencesKey("desktop_control")
        val desktopClipboard = booleanPreferencesKey("desktop_clipboard")
        val desktopAudio = booleanPreferencesKey("desktop_audio")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val url = p[Keys.url]
        val token = p[Keys.token]
        AppSettings(
            server = if (!url.isNullOrBlank() && !token.isNullOrBlank()) {
                ServerConfig(url, token, p[Keys.name] ?: "", p[Keys.accessId] ?: "", p[Keys.accessSecret] ?: "", p[Keys.publicUrl] ?: "")
            } else {
                null
            },
            backgroundNotify = p[Keys.backgroundNotify] ?: true,
            lastProvider = p[Keys.lastProvider] ?: Providers.CLAUDE,
            lastCwd = p[Keys.lastCwd] ?: "",
            thinking = p[Keys.thinking] ?: "full",
            toolDetail = p[Keys.toolDetail] ?: true,
            codeWrap = p[Keys.codeWrap] ?: true,
            toolWrap = ToolWrapPreferences(
                input = p[Keys.toolInputWrap] ?: true,
                output = p[Keys.toolOutputWrap] ?: false,
            ),
            desktop = DesktopPreferences(
                fps = p[Keys.desktopFps]?.takeIf { it in DesktopPreferences.frameRates } ?: 10,
                resolution = DesktopResolution.restore(p[Keys.desktopResolution], p[Keys.desktopHighDetail] ?: false),
                debugOverlay = p[Keys.desktopDebugOverlay] ?: false,
                bitrateMbps = p[Keys.desktopBitrateMbps]?.takeIf { it in -1..DesktopPreferences.manualBitrates.last } ?: 0,
                manualBitrateMbps = (p[Keys.desktopManualBitrateMbps] ?: p[Keys.desktopBitrateMbps])
                    ?.takeIf { it in DesktopPreferences.manualBitrates } ?: 32,
                decodeColor = DesktopDecodeColor.entries.find { it.name.equals(p[Keys.desktopDecodeColor], ignoreCase = true) } ?: DesktopDecodeColor.Auto,
                decodeLatest = p[Keys.desktopDecodeLatest] ?: true,
                pixelated = p[Keys.desktopPixelated] ?: false,
                graphMask = p[Keys.desktopGraphMask]?.and(7) ?: 3,
                capture = p[Keys.desktopCapture] ?: "auto",
                codec = p[Keys.desktopCodec] ?: "jpeg",
                fixedJpegQuality = p[Keys.desktopFixedJpegQuality] ?: false,
                jpegQuality = p[Keys.desktopJpegQuality]?.takeIf { it in DesktopPreferences.jpegQualities }
                    ?: DesktopPreferences.defaultJpegQuality,
                pngCompression = DesktopCompression.Png.entries.find { it.wire == p[Keys.desktopPngCompression] },
                touchpad = p[Keys.desktopTouchpad] ?: true,
                pointerSpeed = p[Keys.desktopPointerSpeed]?.takeIf { it in DesktopPreferences.pointerSpeeds } ?: 1f,
                control = p[Keys.desktopControl] ?: true,
                clipboard = p[Keys.desktopClipboard] ?: false,
                audio = p[Keys.desktopAudio] ?: false,
            ),
            footerDisplay = ChatFooterDisplay(
                context = p[Keys.footerContext] ?: true,
                cache = p[Keys.footerCache] ?: true,
                usage = p[Keys.footerUsage] ?: true,
                stats = p[Keys.footerStats] ?: true,
                git = p[Keys.footerGit] ?: true,
                order = p[Keys.footerOrder]?.split(',') ?: ChatFooterItem.entries.map { it.id },
            ),
            appearance = Appearance(
                themeMode = p[Keys.themeMode] ?: "system",
                dynamicColor = p[Keys.dynamicColor] ?: true,
                accent = p[Keys.accent] ?: "violet",
                amoled = p[Keys.amoled] ?: false,
                uiFont = p[Keys.uiFont] ?: "",
                // 空文字は利用者によるシステム標準の選択。
                bodyFont = p[Keys.bodyFont] ?: DEFAULT_BODY_FONT,
                codeFont = p[Keys.codeFont] ?: DEFAULT_CODE_FONT,
                textScale = p[Keys.textScale] ?: 1f,
                codeScale = p[Keys.codeScale] ?: 1f,
            ),
        )
    }

    suspend fun saveServer(cfg: ServerConfig?) {
        context.dataStore.edit {
            if (cfg?.token != it[Keys.token]) {
                it.remove(Keys.desktopClipboard)
                it.remove(Keys.desktopAudio)
            }
            if (cfg == null) {
                it.remove(Keys.url); it.remove(Keys.token); it.remove(Keys.name)
                it.remove(Keys.accessId); it.remove(Keys.accessSecret); it.remove(Keys.publicUrl)
            } else {
                it[Keys.url] = cfg.url
                it[Keys.token] = cfg.token
                it[Keys.name] = cfg.name
                it[Keys.accessId] = cfg.accessId
                it[Keys.accessSecret] = cfg.accessSecret
                it[Keys.publicUrl] = cfg.publicUrl
            }
        }
    }

    suspend fun savePublicUrl(url: String) {
        context.dataStore.edit { it[Keys.publicUrl] = url }
    }

    /** 同じトークンの HTTPS は公開 URL へ追加し、USB / LAN は差し替える。 */
    suspend fun mergePair(current: ServerConfig?, incoming: ServerConfig) {
        val next = if (current != null && current.token == incoming.token) {
            if (isPublicUrl(incoming.url)) {
                current.copy(
                    name = incoming.name.ifBlank { current.name },
                    publicUrl = incoming.url,
                    accessId = incoming.accessId.ifBlank { current.accessId },
                    accessSecret = incoming.accessSecret.ifBlank { current.accessSecret },
                )
            } else {
                current.copy(url = incoming.url, name = incoming.name.ifBlank { current.name })
            }
        } else {
            incoming.copy(publicUrl = if (isPublicUrl(incoming.url)) incoming.url else "")
        }
        saveServer(next)
    }

    suspend fun setThinking(mode: String) {
        context.dataStore.edit { it[Keys.thinking] = mode }
    }

    suspend fun setAppearance(a: Appearance) {
        context.dataStore.edit {
            it[Keys.themeMode] = a.themeMode
            it[Keys.dynamicColor] = a.dynamicColor
            it[Keys.accent] = a.accent
            it[Keys.amoled] = a.amoled
            it[Keys.uiFont] = a.uiFont
            it[Keys.bodyFont] = a.bodyFont
            it[Keys.codeFont] = a.codeFont
            it[Keys.textScale] = a.textScale
            it[Keys.codeScale] = a.codeScale
        }
    }

    suspend fun setToolDetail(on: Boolean) {
        context.dataStore.edit { it[Keys.toolDetail] = on }
    }

    suspend fun setCodeWrap(on: Boolean) {
        context.dataStore.edit { it[Keys.codeWrap] = on }
    }

    suspend fun setToolInputWrap(on: Boolean) {
        context.dataStore.edit { it[Keys.toolInputWrap] = on }
    }

    suspend fun setToolOutputWrap(on: Boolean) {
        context.dataStore.edit { it[Keys.toolOutputWrap] = on }
    }

    suspend fun setDesktop(prefs: DesktopPreferences, pairedToken: String?) {
        context.dataStore.edit {
            it[Keys.desktopFps] = prefs.fps
            it[Keys.desktopResolution] = prefs.resolution.wire
            it[Keys.desktopDebugOverlay] = prefs.debugOverlay
            it[Keys.desktopBitrateMbps] = prefs.bitrateMbps
            it[Keys.desktopManualBitrateMbps] = prefs.manualBitrateMbps
            it[Keys.desktopDecodeColor] = prefs.decodeColor.name.lowercase()
            it[Keys.desktopDecodeLatest] = prefs.decodeLatest
            it[Keys.desktopPixelated] = prefs.pixelated
            it[Keys.desktopGraphMask] = prefs.graphMask
            it[Keys.desktopCapture] = prefs.capture
            it[Keys.desktopCodec] = prefs.codec
            it[Keys.desktopFixedJpegQuality] = prefs.fixedJpegQuality
            it[Keys.desktopJpegQuality] = prefs.jpegQuality
            if (prefs.pngCompression == null) it.remove(Keys.desktopPngCompression)
            else it[Keys.desktopPngCompression] = prefs.pngCompression.wire
            it[Keys.desktopTouchpad] = prefs.touchpad
            it[Keys.desktopPointerSpeed] = prefs.pointerSpeed
            it[Keys.desktopControl] = prefs.control
            if (pairedToken != null && it[Keys.token] == pairedToken) {
                it[Keys.desktopClipboard] = prefs.clipboard
                it[Keys.desktopAudio] = prefs.audio
            }
        }
    }

    suspend fun setFooterDisplay(display: ChatFooterDisplay) {
        context.dataStore.edit {
            it[Keys.footerContext] = display.context
            it[Keys.footerCache] = display.cache
            it[Keys.footerUsage] = display.usage
            it[Keys.footerStats] = display.stats
            it[Keys.footerGit] = display.git
            it[Keys.footerOrder] = display.orderedItems.joinToString(",") { item -> item.id }
        }
    }

    suspend fun setBackgroundNotify(on: Boolean) {
        context.dataStore.edit { it[Keys.backgroundNotify] = on }
    }

    suspend fun rememberNewSession(provider: String, cwd: String) {
        context.dataStore.edit {
            it[Keys.lastProvider] = provider
            it[Keys.lastCwd] = cwd
        }
    }
}
