package dev.remotecode

import android.app.Application
import android.content.res.Configuration
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.remotecode.data.Repository
import dev.remotecode.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RemoteCodeApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var repository: Repository
        private set
    lateinit var notifier: Notifier
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashRecorder()
        repository = Repository(this, scope)
        notifier = Notifier(this, repository, scope)
        notifier.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                repository.appForeground.value = true
                repository.acquire("ui")
            }

            override fun onStop(owner: LifecycleOwner) {
                repository.appForeground.value = false
                repository.release("ui")
            }
        })
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::notifier.isInitialized) notifier.refreshChannels()
    }

    /** クラッシュ記録は次回接続時に送る。 */
    val crashFile: java.io.File get() = java.io.File(filesDir, "last-crash.txt")

    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val info = packageManager.getPackageInfo(packageName, 0)
                val text = buildString {
                    appendLine("time: ${java.time.Instant.now()}")
                    appendLine("app: ${info.versionName} (${info.longVersionCode})  android: ${android.os.Build.VERSION.RELEASE} (sdk ${android.os.Build.VERSION.SDK_INT})  device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    appendLine("thread: ${thread.name}")
                    append(e.stackTraceToString())
                }
                crashFile.writeText(text)
            }
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        lateinit var instance: RemoteCodeApp
            private set
    }
}
