package dev.remotecode.i18n

import dev.remotecode.R
import dev.remotecode.data.AppJson
import dev.remotecode.data.ProviderUsage
import dev.remotecode.data.Ticket
import dev.remotecode.data.UsageWindow
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageLabelsTest {
    private val strings: AppStringResolver = { id, args -> "[$id:${args.joinToString()}]" }

    @Test fun serverKeysLocalizeLabelsAndPreserveModelNames() {
        val window = UsageWindow("This week (Fable)", labelKey = "week", scope = "Fable")
        assertEquals(strings.text(R.string.core_usage_scoped, strings.text(R.string.core_usage_week), "Fable"), localizedUsageLabel(window, strings))
        assertEquals(strings.text(R.string.core_usage_hours, "5"), localizedUsageLabel(UsageWindow("5 hours", labelKey = "hours", labelCount = 5), strings))
        assertEquals("Custom quota", localizedUsageLabel(UsageWindow("Custom quota"), strings))
    }

    @Test fun generatedTicketsAndSubscriptionUseKeys() {
        assertEquals(strings.text(R.string.core_usage_ticket_weekly_all), localizedTicketTitle("claude", Ticket("Weekly reset", titleKey = "weekly_all"), strings))
        assertEquals(strings.text(R.string.core_usage_ticket_weekly_scoped, "Fable"), localizedTicketTitle("claude", Ticket("Reset", titleKey = "weekly_scoped", scope = "Fable"), strings))
        assertEquals(strings.text(R.string.core_usage_subscription), localizedUsageNote("claude", ProviderUsage(note = "Subscription active", noteKey = "subscription"), strings))
        assertEquals(strings.text(R.string.core_usage_ticket_reset_limit), localizedTicketTitle("codex", Ticket("Reset usage limit", titleKey = "reset_limit"), strings))
    }

    @Test fun providerTitlesAndUnknownKeysStayVerbatim() {
        for (title in listOf("Full reset", "リセット券（Fable）", "Other service title")) {
            assertEquals(title, localizedTicketTitle("codex", Ticket(title), strings))
        }
        assertEquals("Service note", localizedUsageNote("codex", ProviderUsage(note = "Service note"), strings))
        assertEquals("New service title", localizedTicketTitle("claude", Ticket("New service title", titleKey = "future"), strings))
        assertEquals("Reset (Fable)", localizedTicketTitle("claude", Ticket("Reset (Fable)", titleKey = "weekly_scoped"), strings))
    }

    @Test fun oldJapaneseServerLabelsRemainSupported() {
        assertEquals(strings.text(R.string.core_usage_ticket_weekly_scoped, "Fable"), localizedTicketTitle("claude", Ticket("リセット券（Fable）"), strings))
        assertEquals(strings.text(R.string.core_usage_subscription), localizedUsageNote("claude", ProviderUsage(note = "サブスクリプションで利用中"), strings))
        assertEquals(strings.text(R.string.core_usage_week), localizedUsageLabel(UsageWindow("今週"), strings))
    }

    @Test fun wireMetadataDecodesWithoutChangingServiceData() {
        val usage = AppJson.decodeFromString<ProviderUsage>("""{"windows":[{"label":"This week","labelKey":"week","scopeKey":"all_models"}],"tickets":{"available":1,"items":[{"title":"Full reset"}]},"note":"Subscription active","noteKey":"subscription"}""")
        assertEquals(true, usage.windows.single().weekly)
        assertEquals(true, usage.windows.single().allModels)
        assertEquals("Full reset", localizedTicketTitle("codex", usage.tickets!!.items.single(), strings))
    }
}
