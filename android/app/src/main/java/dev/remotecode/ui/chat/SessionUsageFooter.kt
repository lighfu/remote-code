package dev.remotecode.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import dev.remotecode.R
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ChatFooterDisplay
import dev.remotecode.data.Meta
import dev.remotecode.data.SessionStats
import dev.remotecode.data.inferRegionalCurrency
import dev.remotecode.data.monetaryCurrency
import dev.remotecode.data.regionalCurrencyRate
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

internal data class UsageMetric(val label: Int, val value: String)

internal class UsageMoney(private val locale: Locale, currencyCode: String?, meta: Meta?) {
    private val usd = Currency.getInstance("USD")
    private val local = currencyCode?.let(::monetaryCurrency) ?: Currency.getInstance(inferRegionalCurrency(locale))
    private val rate = regionalCurrencyRate(local.currencyCode, meta)

    private fun amount(value: Double, unit: Currency): String {
        val formatter = NumberFormat.getCurrencyInstance(locale).apply {
            currency = unit
            minimumFractionDigits = unit.defaultFractionDigits
            maximumFractionDigits = if (unit == usd && abs(value) < 10) 3 else unit.defaultFractionDigits
        }
        val smallest = Math.pow(10.0, -formatter.maximumFractionDigits.toDouble())
        if (value != 0.0 && abs(value) < smallest / 2) {
            return if (value < 0) "> " + formatter.format(-smallest) else "< " + formatter.format(smallest)
        }
        return formatter.format(value)
    }

    fun format(value: Double?, showUsd: Boolean = true, showLocal: Boolean = true, partial: Boolean = false): String {
        if (value == null || !value.isFinite()) return "—"
        val dollars = value.takeIf { showUsd }?.let { amount(it, usd) }
        val regional = rate?.takeIf { showLocal && (!showUsd || local != usd) }
            ?.let { it * value }?.takeIf(Double::isFinite)?.let { amount(it, local) }
        val display = when {
            dollars != null && regional != null -> "$dollars ($regional)"
            else -> dollars ?: regional ?: return "—"
        }
        return if (partial) "≥ $display" else display
    }
}

@Composable
internal fun rememberUsageMoney(meta: Meta?): UsageMoney {
    val settings by RemoteCodeApp.instance.repository.settings.collectAsState()
    val currency = settings?.localCurrency
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale, currency, meta) { UsageMoney(locale, currency, meta) }
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
    if (display.costUsd || display.costLocal) {
        add(UsageMetric(R.string.chat_footer_cost, money.format(stats?.costUsd, display.costUsd, display.costLocal, stats?.partial == true)))
    }
}
