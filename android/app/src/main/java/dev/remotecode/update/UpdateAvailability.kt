package dev.remotecode.update

import dev.remotecode.data.AppUpdateInfo

internal fun usableUpdate(info: AppUpdateInfo): Boolean = info.available && info.size > 0 &&
    info.sha256?.let { it.length == 64 && it.all { c -> c in "0123456789abcdefABCDEF" } } == true

/** 同じバージョンの再ビルドは許可し、versionCode の降格は除外する。 */
internal fun updateAvailable(info: AppUpdateInfo, installedSha: String, installedCode: Long): Boolean =
    usableUpdate(info) && (info.versionCode == null || info.versionCode >= installedCode) &&
        !info.sha256.equals(installedSha, ignoreCase = true)
