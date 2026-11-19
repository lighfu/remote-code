package dev.remotecode.ui.chat

import dev.remotecode.R
import dev.remotecode.data.ChatFooterDisplay
import dev.remotecode.data.Meta
import dev.remotecode.data.SessionStats
import dev.remotecode.data.TokenTotals
import dev.remotecode.data.inferRegionalCurrency
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionUsageFooterTest {
    private val rates = Meta(usdRates = mapOf("JPY" to 150.0, "EUR" to 0.8))
    private val yen = UsageMoney(Locale.US, "JPY", rates)

    @Test fun costsShareOneLabelWhileKeepingTheVisibilityPreferences() {
        val stats = SessionStats(costUsd = 2.0, partial = true, tokens = TokenTotals(80, 60, 0))
        val all = usageMetrics(stats, ChatFooterDisplay(), yen)
        assertEquals(listOf("80", "0", "60", "75%", "≥ $2.00 (¥300)"), all.map { it.value })
        val onlyLocal = ChatFooterDisplay(inputTokens = false, outputTokens = false, cachedTokens = false, cacheRate = false, costUsd = false)
        assertEquals(listOf(UsageMetric(R.string.chat_footer_cost, "≥ ¥300")), usageMetrics(stats, onlyLocal, yen))
        assertEquals("≥ $2.00", usageMetrics(stats, onlyLocal.copy(costUsd = true, costLocal = false), yen).single().value)
        assertTrue(usageMetrics(stats, onlyLocal.copy(costLocal = false), yen).isEmpty())
        assertTrue(usageMetrics(stats, onlyLocal.copy(stats = false), yen).isEmpty())
    }

    @Test fun missingFiguresDifferFromReportedZeros() {
        assertTrue(usageMetrics(null, ChatFooterDisplay(), yen).all { it.value == "—" })
        val stats = SessionStats(costUsd = 0.0, tokens = TokenTotals())
        assertEquals(listOf("0", "0", "0", "—", "$0.00 (¥0)"), usageMetrics(stats, ChatFooterDisplay(), yen).map { it.value })
    }

    @Test fun automaticCurrencyUsesAppLanguageEvenWithoutACountry() {
        val cases = mapOf("ja" to "JPY", "ko" to "KRW", "zh-Hans" to "CNY", "zh-Hant" to "TWD",
            "zh-Hant-HK" to "HKD", "en" to "USD", "en-GB" to "GBP")
        cases.forEach { (tag, code) -> assertEquals(tag, code, inferRegionalCurrency(Locale.forLanguageTag(tag))) }
        assertEquals(UsageMoney(Locale.JAPANESE, "JPY", rates).format(2.0), UsageMoney(Locale.JAPANESE, null, rates).format(2.0))
        assertTrue(UsageMoney(Locale.JAPANESE, null, rates).format(2.0).contains("300"))
    }

    @Test fun manualCurrencyOverridesLanguageAndOnlyKnownRatesAreUsed() {
        val euro = UsageMoney(Locale.US, "EUR", rates)
        assertEquals("$2.00 (€1.60)", euro.format(2.0))
        assertEquals("$2.00 (¥200)", UsageMoney(Locale.US, "JPY", Meta(usdJpy = 100.0)).format(2.0))
        val missing = UsageMoney(Locale.US, "JPY", Meta(usdJpy = 100.0, usdRates = emptyMap()))
        assertEquals("—", missing.format(2.0, showUsd = false))
        assertEquals("$2.00", missing.format(2.0))
        assertEquals("$2.00", UsageMoney(Locale.US, null, null).format(2.0))
    }

    @Test fun cacheSavingsPreserveUnknownSmallAndNegativeValues() {
        assertEquals("—", yen.format(null))
        assertEquals("$0.00 (¥0)", yen.format(0.0))
        assertEquals("-$2.00 (-¥300)", yen.format(-2.0))
        assertEquals("< $0.001 (< ¥1)", yen.format(0.00001))
    }
}
