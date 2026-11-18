package dev.remotecode.i18n

import androidx.annotation.StringRes
import dev.remotecode.RemoteCodeApp

fun appString(@StringRes id: Int, vararg args: Any?): String =
    if (args.isEmpty()) RemoteCodeApp.instance.getString(id) else RemoteCodeApp.instance.getString(id, *args)

typealias AppStringResolver = (Int, Array<out Any?>) -> String

val applicationStrings: AppStringResolver = { id, args -> appString(id, *args) }

fun AppStringResolver.text(@StringRes id: Int, vararg args: Any?): String = invoke(id, args)
