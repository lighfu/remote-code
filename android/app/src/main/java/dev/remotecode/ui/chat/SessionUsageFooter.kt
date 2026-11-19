package dev.remotecode.ui.chat

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import dev.remotecode.R
import dev.remotecode.data.ChatFooterDisplay
import dev.remotecode.data.Meta
import dev.remotecode.data.SessionStats
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

internal data class UsageMetric(val label: Int, val value: String)

internal class UsageMoney(private val locale: Locale, region: Locale, meta: Meta?) {
    private val usd = Currency.getInstance("USD")
    private val local = runCatching { Currency.getInstance(region) }.getOrNull()?.takeIf { it.defaultFractionDigits >= 0 }
    private val rate = when (local?.currencyCode) {
        null -> null
        "USD" -> 1.0
        "JPY" -> if (meta?.usdRates == null) meta?.usdJpy else meta.usdRates["JPY"]
        else -> meta?.usdRates?.get(local.currencyCode)
    }?.takeIf { it.isFinite() && it > 0 }

    private fun amount(value: Double?, unit: Currency?, partial: Boolean): String {
        if (unit == null || value == null || !value.isFinite()) return "—"
        val formatter = NumberFormat.getCurrencyInstance(locale).apply {
            currency = unit
            minimumFractionDigits = unit.defaultFractionDigits
            maximumFractionDigits = if (unit == usd && value < 10) 3 else unit.defaultFractionDigits
        }
        val smallest = Math.pow(10.0, -formatter.maximumFractionDigits.toDouble())
        if (value != 0.0 && abs(value) < smallest / 2) {
            return if (value < 0) "> " + formatter.format(-smallest) else "< " + formatter.format(smallest)
        }
        return (if (partial) "≥ " else "") + formatter.format(value)
    }

    fun dollars(value: Double?, partial: Boolean) = amount(value, usd, partial)
    fun regional(value: Double?, partial: Boolean) = amount(if (value != null && rate != null) value * rate else null, local, partial)

    fun savings(value: Double?): String {
        val base = dollars(value, false)
        val converted = regional(value, false)
        return if (local == usd || converted == "—") base else "$base ($converted)"
    }
}

@Composable
internal fun rememberUsageMoney(meta: Meta?): UsageMoney {
    val locale = LocalConfiguration.current.locales[0]
    val region = Resources.getSystem().configuration.locales[0]
    return remember(locale, region, meta) { UsageMoney(locale, region, meta) }
}

internal fun usageMetrics(stats: SessionStats?, display: ChatFooterDisplay, money: UsageMoney): List<UsageMetric> = buildList {
    if (!display.stats) return@buildList
    val tokens = stats?.tokens
    fun count(n: Long?): String = n?.takeIf { it >= 0 }?.let(::shortTokens) ?: "—"
    if (display.inputTokens) add(UsageMetric(R.string.chat_footer_input_tokens, count(tokens?.input)))
    if (display.outputTokens) add(UsageMetric(R.string.chat_footer_output_tokens, count(tokens?.output)))
    if (display.cachedTokens) add(UsageMetric(R.string.chat_footer_cached_tokens, count(tokens?.cached)))
    if (display.cacheRate) {
        val hit = tokens?.takeIf { it.input > 0 && it.cached in 0..it.input }?.let {
            "${(it.cached.toDouble() / it.input * 100).toInt()}%"
        }
        add(UsageMetric(R.string.chat_footer_cache_rate, hit ?: "—"))
    }
    if (display.costUsd) add(UsageMetric(R.string.chat_footer_cost_usd, money.dollars(stats?.costUsd, stats?.partial == true)))
    if (display.costLocal) add(UsageMetric(R.string.chat_footer_cost_local, money.regional(stats?.costUsd, stats?.partial == true)))
}
