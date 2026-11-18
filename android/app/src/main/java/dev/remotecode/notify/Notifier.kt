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
import dev.remotecode.R
import dev.remotecode.i18n.appString
import dev.remotecode.data.Entry
import dev.remotecode.data.Providers
import dev.remotecode.data.Repository
import dev.remotecode.i18n.localizedApprovalLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class Notifier(private val context: Context, private val repo: Repository, private val scope: CoroutineScope) {
    private val nm = NotificationManagerCompat.from(context)
    private val notifiedRequests = mutableSetOf<String>()

    fun start() {
        refreshChannels()
        scope.launch {
            repo.events.collect { ev ->
                val viewing = repo.appForeground.value && repo.visibleSession.value == ev.sessionId
                if (ev.entry.isPendingAsk) {
                    val req = ev.entry.requestId ?: ev.entry.id
                    if (notifiedRequests.add(req) && !viewing) notifyAsk(ev.sessionId, ev.entry)
                } else if (ev.entry.kind == "result" && !viewing) {
                    notifyDone(ev.sessionId, ev.entry)
                }
                if ((ev.entry.kind == "approval" || ev.entry.kind == "question") && ev.entry.status != "pending") {
                    nm.cancel(ev.entry.requestId ?: ev.entry.id, ID_ASK)
                }
            }
        }
        scope.launch {
            repo.visibleSession.collect { sid -> if (sid != null) cancelFor(sid) }
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

    fun openSessionIntent(sessionId: String?): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (sessionId != null) putExtra(MainActivity.EXTRA_SESSION, sessionId)
        }
        return PendingIntent.getActivity(context, sessionId?.hashCode() ?: 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun titleOf(sessionId: String): String {
        val s = repo.session(sessionId) ?: return "Remote Code"
        return "${Providers.label(s.provider)} · ${s.title}"
    }

    private fun notifyAsk(sessionId: String, e: Entry) {
        if (!canPost()) return
        val tag = e.requestId ?: e.id
        val body = if (e.kind == "question") {
            e.questions?.firstOrNull()?.question ?: appString(R.string.core_question_awaiting_answer)
        } else {
            listOfNotNull(e.text?.lineSequence()?.firstOrNull(), e.title).joinToString("\n")
        }
        val b = NotificationCompat.Builder(context, CH_ASK)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle(if (e.kind == "question") appString(R.string.core_notification_question_title, titleOf(sessionId)) else appString(R.string.core_notification_approval_title, titleOf(sessionId)))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openSessionIntent(sessionId))
        if (e.kind == "approval") {
            val allow = e.options?.firstOrNull { it.kind == "allow" }
            val deny = e.options?.firstOrNull { it.kind == "deny" }
            if (allow != null) b.addAction(0, localizedApprovalLabel(allow), ApprovalActionReceiver.intent(context, sessionId, tag, allow.id))
            if (deny != null) b.addAction(0, localizedApprovalLabel(deny), ApprovalActionReceiver.intent(context, sessionId, tag, deny.id))
        }
        try {
            nm.notify(tag, ID_ASK, b.build())
        } catch (_: SecurityException) {
            // 権限取消後も購読を継続する。
        }
    }

    private fun notifyDone(sessionId: String, e: Entry) {
        if (!canPost()) return
        val last = repo.session(sessionId)?.preview?.takeIf { it.isNotBlank() } ?: e.text ?: ""
        val b = NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle(if (e.ok == true) appString(R.string.core_notification_done_title, titleOf(sessionId)) else appString(R.string.core_notification_stopped_title, titleOf(sessionId)))
            .setContentText(last)
            .setStyle(NotificationCompat.BigTextStyle().bigText(last))
            .setAutoCancel(true)
            .setContentIntent(openSessionIntent(sessionId))
        try {
            nm.notify(sessionId, ID_DONE, b.build())
        } catch (_: SecurityException) {
            // 権限取消後も購読を継続する。
        }
    }

    fun cancelFor(sessionId: String) {
        nm.cancel(sessionId, ID_DONE)
        val tl = repo.timeline(sessionId).value
        for (e in tl.entries) if (e.isPendingAsk) nm.cancel(e.requestId ?: e.id, ID_ASK)
    }

    fun cancelAsk(tag: String) = nm.cancel(tag, ID_ASK)

    companion object {
        const val CH_ASK = "ask"
        const val CH_DONE = "done"
        const val CH_SERVICE = "service"
        const val ID_ASK = 10
        const val ID_DONE = 11
        const val ID_SERVICE = 1
    }
}
