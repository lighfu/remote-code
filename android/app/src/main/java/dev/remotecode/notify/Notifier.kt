package dev.remotecode.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.remotecode.MainActivity
import dev.remotecode.NotificationSessionTarget
import dev.remotecode.R
import dev.remotecode.data.Entry
import dev.remotecode.data.Providers
import dev.remotecode.data.Repository
import dev.remotecode.i18n.appString
import dev.remotecode.i18n.localizedApprovalLabel
import dev.remotecode.notificationServerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class Notifier(private val context: Context, private val repo: Repository, private val scope: CoroutineScope) {
    private val nm = NotificationManagerCompat.from(context)
    private val notifiedRequests = mutableSetOf<String>()

    fun start() {
        refreshChannels()
        scope.launch {
            repo.events.collect { ev ->
                val entry = ev.entry
                if (entry.kind != "approval" && entry.kind != "question" && entry.kind != "result") return@collect
                val target = NotificationSessionTarget(ev.sessionId, ev.server.notificationServerId())
                val current = ev.server.token == repo.settings.value?.server?.token && ev.server.token == repo.api.server?.token
                val viewing = current && repo.appForeground.value && repo.visibleSession.value == target.sessionId
                when {
                    entry.kind == "result" -> if (!viewing) notifyDone(target, entry, current)
                    entry.isPendingAsk -> {
                        val requestId = entry.requestId ?: entry.id
                        if (notifiedRequests.add(notificationTag(target, requestId)) && !viewing) {
                            notifyAsk(target, entry, requestId, current)
                        }
                    }
                    else -> cancelAsk(target, entry.requestId ?: entry.id)
                }
            }
        }
        scope.launch {
            repo.visibleSession.collect { sessionId ->
                val server = repo.api.server
                if (sessionId != null && server != null && server.token == repo.settings.value?.server?.token) {
                    cancelFor(NotificationSessionTarget(sessionId, server.notificationServerId()))
                }
            }
        }
    }

    fun refreshChannels() {
        val m = context.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(
            NotificationChannel(CH_ASK, appString(R.string.core_channel_approval), NotificationManager.IMPORTANCE_HIGH).apply {
                description = appString(R.string.core_channel_approval_description)
            },
        )
        m.createNotificationChannel(
            NotificationChannel(CH_DONE, appString(R.string.core_channel_done), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = appString(R.string.core_channel_done_description)
            },
        )
        m.createNotificationChannel(
            NotificationChannel(CH_SERVICE, appString(R.string.core_channel_connection), NotificationManager.IMPORTANCE_MIN).apply {
                description = appString(R.string.core_channel_connection_description)
                setShowBadge(false)
            },
        )
    }

    private fun canPost() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun openSessionIntent(target: NotificationSessionTarget?): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (target != null) {
                action = "${context.packageName}.OPEN_SESSION.${target.serverId}.${target.sessionId}"
                putExtra(MainActivity.EXTRA_SESSION, target.sessionId)
                putExtra(MainActivity.EXTRA_NOTIFICATION_SERVER, target.serverId)
            }
        }
        return PendingIntent.getActivity(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun titleOf(target: NotificationSessionTarget, current: Boolean): String {
        val s = if (current) repo.session(target.sessionId) else null
        if (s == null) return "Remote Code"
        return "${Providers.label(s.provider)} · ${s.title}"
    }

    private fun notifyAsk(target: NotificationSessionTarget, e: Entry, requestId: String, current: Boolean) {
        if (!canPost()) return
        val body = if (e.kind == "question") {
            e.questions?.firstOrNull()?.question ?: appString(R.string.core_question_awaiting_answer)
        } else {
            listOfNotNull(e.text?.lineSequence()?.firstOrNull(), e.title).joinToString("\n")
        }
        val b = NotificationCompat.Builder(context, CH_ASK)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle(if (e.kind == "question") appString(R.string.core_notification_question_title, titleOf(target, current)) else appString(R.string.core_notification_approval_title, titleOf(target, current)))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openSessionIntent(target))
        if (e.kind == "approval") {
            val allow = e.options?.firstOrNull { it.kind == "allow" }
            val deny = e.options?.firstOrNull { it.kind == "deny" }
            if (allow != null) b.addAction(0, localizedApprovalLabel(allow), ApprovalActionReceiver.intent(context, target, requestId, allow.id))
            if (deny != null) b.addAction(0, localizedApprovalLabel(deny), ApprovalActionReceiver.intent(context, target, requestId, deny.id))
        }
        try {
            nm.notify(notificationTag(target, requestId), ID_ASK, b.build())
        } catch (_: SecurityException) {
            // 権限取消後も購読を継続する。
        }
    }

    private fun notifyDone(target: NotificationSessionTarget, e: Entry, current: Boolean) {
        if (!canPost()) return
        val preview = if (current) repo.session(target.sessionId)?.preview else null
        val last = preview?.takeIf { it.isNotBlank() } ?: e.text ?: ""
        val b = NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle(if (e.ok == true) appString(R.string.core_notification_done_title, titleOf(target, current)) else appString(R.string.core_notification_stopped_title, titleOf(target, current)))
            .setContentText(last)
            .setStyle(NotificationCompat.BigTextStyle().bigText(last))
            .setAutoCancel(true)
            .setContentIntent(openSessionIntent(target))
        try {
            nm.notify(notificationTag(target), ID_DONE, b.build())
        } catch (_: SecurityException) {
            // 権限取消後も購読を継続する。
        }
    }

    private fun cancelFor(target: NotificationSessionTarget) {
        nm.cancel(notificationTag(target), ID_DONE)
        val tl = repo.timeline(target.sessionId).value
        for (e in tl.entries) if (e.isPendingAsk) cancelAsk(target, e.requestId ?: e.id)
    }

    internal fun cancelAsk(target: NotificationSessionTarget, requestId: String) =
        nm.cancel(notificationTag(target, requestId), ID_ASK)

    private fun notificationTag(target: NotificationSessionTarget, requestId: String = "") =
        "${target.serverId}:${target.sessionId}:$requestId"

    companion object {
        const val CH_ASK = "ask"
        const val CH_DONE = "done"
        const val CH_SERVICE = "service"
        const val ID_ASK = 10
        const val ID_DONE = 11
        const val ID_SERVICE = 1
    }
}
