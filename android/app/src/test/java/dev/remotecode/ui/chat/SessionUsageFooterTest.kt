package dev.remotecode.ui.chat

import dev.remotecode.R
import dev.remotecode.data.ChatFooterDisplay
import dev.remotecode.data.Meta
import dev.remotecode.data.SessionStats
import dev.remotecode.data.TokenTotals
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionUsageFooterTest {
    private val yen = UsageMoney(Locale.US, Locale.JAPAN, Meta(usdRates = mapOf("JPY" to 150.0)))

    @Test fun tokensAndCostsAreIndependentAndKeepTheSameOrder() {
        val stats = SessionStats(costUsd = 2.0, partial = true, tokens = TokenTotals(80, 60, 0))
        val all = usageMetrics(stats, ChatFooterDisplay(), yen)
        assertEquals(listOf("80", "0", "60", "75%", "≥ $2.00", "≥ ¥300"), all.map { it.value })
        val onlyLocal = ChatFooterDisplay(inputTokens = false, outputTokens = false, cachedTokens = false, cacheRate = false, costUsd = false)
        assertEquals(listOf(UsageMetric(R.string.chat_footer_cost_local, "≥ ¥300")), usageMetrics(stats, onlyLocal, yen))
        assertTrue(usageMetrics(stats, onlyLocal.copy(stats = false), yen).isEmpty())
    }

    @Test fun missingFiguresDifferFromReportedZeros() {
        assertTrue(usageMetrics(null, ChatFooterDisplay(), yen).all { it.value == "—" })
        val stats = SessionStats(costUsd = 0.0, tokens = TokenTotals())
        assertEquals(listOf("0", "0", "0", "—", "$0.00", "¥0"), usageMetrics(stats, ChatFooterDisplay(), yen).map { it.value })
    }

    @Test fun regionIsIndependentOfUiLanguageAndFallsBackOnlyForLegacyYenQuotes() {
        val euro = UsageMoney(Locale.US, Locale.GERMANY, Meta(usdRates = mapOf("EUR" to 0.8)))
        assertEquals("€1.60", euro.regional(2.0, false))
        assertEquals("¥200", UsageMoney(Locale.US, Locale.JAPAN, Meta(usdJpy = 100.0)).regional(2.0, false))
        val missing = UsageMoney(Locale.US, Locale.JAPAN, Meta(usdJpy = 100.0, usdRates = emptyMap()))
        assertEquals("—", missing.regional(2.0, false))
        assertEquals("$2.00", missing.savings(2.0))
        assertEquals("$2.00", UsageMoney(Locale.US, Locale.US, null).savings(2.0))
    }

    @Test fun cacheSavingsPreserveUnknownSmallAndNegativeValues() {
        assertEquals("—", yen.savings(null))
        assertEquals("$0.00 (¥0)", yen.savings(0.0))
        assertEquals("-$2.00 (-¥300)", yen.savings(-2.0))
        assertEquals("< $0.001 (< ¥1)", yen.savings(0.00001))
    }
}
