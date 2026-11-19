package dev.remotecode.data

import dev.remotecode.i18n.resolveAppLanguageTag
import java.util.Currency
import java.util.Locale

internal fun monetaryCurrency(code: String): Currency? =
    runCatching { Currency.getInstance(code) }.getOrNull()?.takeIf { it.defaultFractionDigits >= 0 }

internal fun isMonetaryCurrency(code: String): Boolean = monetaryCurrency(code) != null

internal fun inferRegionalCurrency(locale: Locale): String = when (resolveAppLanguageTag(locale.toLanguageTag())) {
    "en" -> runCatching { Currency.getInstance(locale) }.getOrNull()?.currencyCode
        ?.takeIf(::isMonetaryCurrency) ?: "USD"
    "ko" -> "KRW"
    "zh-CN" -> "CNY"
    "zh-Hant" -> when (locale.country) {
        "HK" -> "HKD"
        "MO" -> "MOP"
        else -> "TWD"
    }
    else -> "JPY"
}

internal fun regionalCurrencyRate(code: String, meta: Meta?): Double? = when {
    code == "USD" -> 1.0
    meta?.usdRates != null -> meta.usdRates[code]
    code == "JPY" -> meta?.usdJpy
    else -> null
}?.takeIf { it.isFinite() && it > 0 }
