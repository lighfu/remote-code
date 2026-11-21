package dev.remotecode.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import dev.remotecode.MainActivity
import dev.remotecode.NotificationSessionTarget
import dev.remotecode.R
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ApiException
import dev.remotecode.i18n.appString
import dev.remotecode.notificationServerId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(MainActivity.EXTRA_SESSION) ?: return
        val serverId = intent.getStringExtra(MainActivity.EXTRA_NOTIFICATION_SERVER) ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST) ?: return
        val choice = intent.getStringExtra(EXTRA_CHOICE) ?: return
        val target = NotificationSessionTarget(sessionId, serverId)
        val app = context.applicationContext as RemoteCodeApp
        val pending = goAsync()
        app.scope.launch {
            try {
                val repo = app.repository
                val result = runCatching {
                    val server = withTimeoutOrNull(3_000) {
                        val selected = repo.settings.filterNotNull().first().server
                        if (selected == null || selected.notificationServerId() != serverId) return@withTimeoutOrNull null
                        repo.activeServerIdentity.first { it?.token == selected.token }
                        repo.api.server?.takeIf { it.token == selected.token && repo.settings.value?.server?.token == selected.token }
                    } ?: throw ApiException(appString(R.string.core_server_changed))
                    repo.api.respond(sessionId, requestId, choice = choice, expectedServer = server)
                }
                withContext(Dispatchers.Main) {
                    app.notifier.cancelAsk(target, requestId)
                    result.exceptionOrNull()?.let { Toast.makeText(context, appString(R.string.core_notification_send_error, it.message.orEmpty()), Toast.LENGTH_LONG).show() }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_REQUEST = "request"
        private const val EXTRA_CHOICE = "choice"

        internal fun intent(context: Context, target: NotificationSessionTarget, requestId: String, choice: String): PendingIntent {
            val i = Intent(context, ApprovalActionReceiver::class.java).apply {
                action = "${context.packageName}.RESPOND.${target.serverId}.${target.sessionId}.$requestId.$choice"
                putExtra(MainActivity.EXTRA_SESSION, target.sessionId)
                putExtra(MainActivity.EXTRA_NOTIFICATION_SERVER, target.serverId)
                putExtra(EXTRA_REQUEST, requestId)
                putExtra(EXTRA_CHOICE, choice)
            }
            return PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
