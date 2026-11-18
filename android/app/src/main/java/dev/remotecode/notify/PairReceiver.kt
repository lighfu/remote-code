package dev.remotecode.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dev.remotecode.MainActivity
import dev.remotecode.RemoteCodeApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 同じトークンの USB 通知で接続先を更新する。 */
class PairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val incoming = MainActivity.parsePairUri(intent.getStringExtra("link")?.let(Uri::parse)) ?: return
        val app = context.applicationContext as RemoteCodeApp
        val pending = goAsync()
        app.scope.launch {
            try {
                val current = app.repository.settingsStore.settings.first().server
                if (current != null && current.token == incoming.token) {
                    app.repository.settingsStore.mergePair(current, incoming)
                    Log.i("RemoteCode", "接続先を更新しました: ${incoming.url}")
                } else {
                    Log.i("RemoteCode", "トークンが違うので接続先の更新を受け入れませんでした")
                }
            } finally {
                pending.finish()
            }
        }
    }
}
