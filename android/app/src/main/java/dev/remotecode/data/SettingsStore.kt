package dev.remotecode.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
)

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
