package dev.remotecode.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore

private const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
private const val LENS_APP = "com.google.ar.lens"

/** Lens → カメラの順に試し、起動種別か null を返す。 */
fun openQrReader(context: Context): String? {
    val candidates = buildList {
        add("lens" to Intent(Intent.ACTION_MAIN)
            .setClassName(GOOGLE_APP, "com.google.android.apps.search.lens.LensExportedActivity")
            .putExtra("LensHomescreenShortcut", true))
        context.packageManager.getLaunchIntentForPackage(LENS_APP)?.let { add("lens" to it) }
        add("lens" to Intent(Intent.ACTION_VIEW, Uri.parse("googleapp://lens")).setPackage(GOOGLE_APP))
        add("camera" to Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
    }
    val fromActivity = context.findActivity() != null
    for ((kind, intent) in candidates) {
        if (!fromActivity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return kind
    }
    return null
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
