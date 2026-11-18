package dev.remotecode.i18n

import java.util.Locale

internal val supportedLanguages = listOf("ja", "en", "zh-CN", "zh-Hant", "ko")

internal fun resolveAppLanguageTag(languageTags: String): String {
    val tag = languageTags.substringBefore(',').trim()
    if (tag.isEmpty()) return ""
    val locale = Locale.forLanguageTag(tag)
    return when (locale.language) {
        "zh" -> when (locale.script) {
            "Hant" -> "zh-Hant"
            "Hans" -> "zh-CN"
            else -> if (locale.country in setOf("TW", "HK", "MO")) "zh-Hant" else "zh-CN"
        }
        "ja", "en", "ko" -> locale.language
        else -> ""
    }
}
