package dev.remotecode

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dev.remotecode.data.ServerConfig
import dev.remotecode.data.pairedServerId
import dev.remotecode.notify.ConnectionService
import dev.remotecode.ui.AppNav
import dev.remotecode.ui.theme.RemoteCodeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal data class NotificationSessionTarget(val sessionId: String, val serverId: String)

internal fun ServerConfig.notificationServerId(): String = pairedServerId()

class MainActivity : ComponentActivity() {
    internal val openSession = MutableStateFlow<NotificationSessionTarget?>(null)
    val pairRequest = MutableStateFlow<ServerConfig?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            RemoteCodeTheme {
                AppNav(activity = this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val repo = (application as RemoteCodeApp).repository
        lifecycleScope.launch {
            val s = repo.settings.filterNotNull().first()
            if (s.server != null && s.backgroundNotify) ConnectionService.start(this@MainActivity)
        }
    }

    /** 同じリンクの再提示を防ぐ。 */
    private var offeredClip: String? = null

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || pairRequest.value != null) return
        val clip = runCatching {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull() ?: return
        val link = findPairLink(clip) ?: return
        if (link == offeredClip) return
        offeredClip = link
        val cfg = parsePairUri(Uri.parse(link)) ?: return
        val cur = (application as RemoteCodeApp).repository.settings.value?.server
        val same = cur != null && cur.token == cfg.token && (cur.url == cfg.url || cur.publicUrl == cfg.url)
        if (!same) pairRequest.value = cfg
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val sessionId = intent.getStringExtra(EXTRA_SESSION)
        val serverId = intent.getStringExtra(EXTRA_NOTIFICATION_SERVER)
        if (sessionId != null && serverId != null) openSession.value = NotificationSessionTarget(sessionId, serverId)
        parsePairUri(intent.data)?.let { pairRequest.value = it }
    }

    companion object {
        const val EXTRA_SESSION = "session"
        internal const val EXTRA_NOTIFICATION_SERVER = "notificationServer"

        fun findPairLink(text: String): String? =
            Regex("""remotecode://pair\?[A-Za-z0-9%&=._~+\-]+""").find(text)?.value

        fun parsePairUri(uri: Uri?): ServerConfig? {
            if (uri == null || uri.scheme != "remotecode" || uri.host != "pair") return null
            val url = uri.getQueryParameter("url")?.trim().orEmpty()
            val token = uri.getQueryParameter("token")?.trim().orEmpty()
            if (url.isEmpty() || token.isEmpty()) return null
            return ServerConfig(
                url = url,
                token = token,
                name = uri.getQueryParameter("name").orEmpty(),
                accessId = uri.getQueryParameter("cfid").orEmpty(),
                accessSecret = uri.getQueryParameter("cfsecret").orEmpty(),
            )
        }
    }
}
