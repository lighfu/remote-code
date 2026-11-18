package dev.remotecode.notify

import dev.remotecode.R
import dev.remotecode.i18n.appString
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import dev.remotecode.RemoteCodeApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST) ?: return
        val choice = intent.getStringExtra(EXTRA_CHOICE) ?: return
        val app = context.applicationContext as RemoteCodeApp
        val pending = goAsync()
        app.scope.launch {
            val result = runCatching { app.repository.api.respond(sessionId, requestId, choice = choice) }
            withContext(Dispatchers.Main) {
                app.notifier.cancelAsk(requestId)
                result.exceptionOrNull()?.let { Toast.makeText(context, appString(R.string.core_notification_send_error, it.message.orEmpty()), Toast.LENGTH_LONG).show() }
            }
            pending.finish()
        }
    }

    companion object {
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_REQUEST = "request"
        private const val EXTRA_CHOICE = "choice"

        fun intent(context: Context, sessionId: String, requestId: String, choice: String): PendingIntent {
            val i = Intent(context, ApprovalActionReceiver::class.java).apply {
                action = "${context.packageName}.RESPOND.$requestId.$choice"
                putExtra(EXTRA_SESSION, sessionId)
                putExtra(EXTRA_REQUEST, requestId)
                putExtra(EXTRA_CHOICE, choice)
            }
            return PendingIntent.getBroadcast(context, (requestId + choice).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
