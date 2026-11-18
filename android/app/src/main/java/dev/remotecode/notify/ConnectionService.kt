package dev.remotecode.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.remotecode.R
import dev.remotecode.i18n.appString
import dev.remotecode.RemoteCodeApp

class ConnectionService : Service() {
    private val app get() = application as RemoteCodeApp
    private val actionStop get() = "$packageName.STOP"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == actionStop) {
            stopSelf()
            return START_NOT_STICKY
        }
        showConnectionNotification()
        app.repository.acquire("service")
        return START_STICKY
    }

    private fun showConnectionNotification() {
        val n = NotificationCompat.Builder(this, Notifier.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle(appString(R.string.core_notification_connected))
            .setContentText(appString(R.string.core_notification_connection_description))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(app.notifier.openSessionIntent(null))
            .addAction(0, appString(R.string.core_notification_disconnect), android.app.PendingIntent.getService(
                this, 1, Intent(this, ConnectionService::class.java).setAction(actionStop),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            ))
            .build()
        ServiceCompat.startForeground(this, Notifier.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        showConnectionNotification()
    }

    override fun onDestroy() {
        app.repository.release("service")
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, ConnectionService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
