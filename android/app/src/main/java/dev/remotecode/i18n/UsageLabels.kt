package dev.remotecode.i18n

import dev.remotecode.R
import dev.remotecode.data.ProviderUsage
import dev.remotecode.data.Ticket
import dev.remotecode.data.UsageWindow

private fun UsageWindow.legacyPeriod(): String? = when {
    label == "今週" || label.startsWith("今週（") || label == "This week" || label.startsWith("This week (") -> "week"
    else -> null
}

val UsageWindow.weekly: Boolean get() = (labelKey ?: legacyPeriod()) == "week"
val UsageWindow.displayScope: String? get() = scope ?: when {
    label.startsWith("今週（") -> label.removePrefix("今週（").removeSuffix("）")
    label.startsWith("This week (") -> label.removePrefix("This week (").removeSuffix(")")
    else -> null
}
val UsageWindow.allModels: Boolean get() = scopeKey == "all_models" || displayScope in setOf("全モデル", "All models", "all models")

fun localizedUsageLabel(window: UsageWindow, strings: AppStringResolver = applicationStrings): String {
    val period = when (window.labelKey) {
        "week" -> strings.text(R.string.core_usage_week)
        "short_term" -> strings.text(R.string.core_usage_short_term)
        "long_term" -> strings.text(R.string.core_usage_long_term)
        "hours" -> window.labelCount?.let { strings.text(R.string.core_usage_hours, it.toString()) }
        "days" -> window.labelCount?.let { strings.text(R.string.core_usage_days, it.toString()) }
        else -> null
    } ?: return localizedUsageLabel(window.label, strings)
    val scope = when (window.scopeKey) {
        "all_models" -> strings.text(R.string.core_usage_all_models)
        "by_model" -> strings.text(R.string.core_usage_by_model)
        "oauth_apps" -> strings.text(R.string.core_usage_oauth_apps)
        "limit" -> strings.text(R.string.core_usage_limit)
        else -> window.scope
    }
    return if (scope.isNullOrBlank()) period else strings.text(R.string.core_usage_scoped, period, scope)
}

fun localizedUsageNote(provider: String, usage: ProviderUsage?, strings: AppStringResolver = applicationStrings): String? {
    if (usage?.noteKey == "subscription" || provider == "claude" && usage?.note == "サブスクリプションで利用中") {
        return strings.text(R.string.core_usage_subscription)
    }
    return usage?.note
}

fun localizedTicketTitle(provider: String, ticket: Ticket, strings: AppStringResolver = applicationStrings): String {
    val key = ticket.titleKey ?: if (provider == "claude") when {
        ticket.title == "リセット券（週の上限・全モデル）" -> "weekly_all"
        ticket.title == "リセット券（モデル別の週の上限）" -> "weekly_model"
        ticket.title == "5時間の上限のリセット（週 1 回）" -> "session_weekly"
        ticket.title.startsWith("リセット券（") && ticket.title.endsWith("）") -> "weekly_scoped"
        else -> null
    } else null
    return when (key) {
        "weekly_all" -> strings.text(R.string.core_usage_ticket_weekly_all)
        "weekly_model" -> strings.text(R.string.core_usage_ticket_weekly_model)
        "weekly_scoped" -> {
            val scope = ticket.scope ?: ticket.title.takeIf { it.startsWith("リセット券（") && it.endsWith("）") }?.removePrefix("リセット券（")?.removeSuffix("）")
            if (scope == null) ticket.title else strings.text(R.string.core_usage_ticket_weekly_scoped, scope)
        }
        "session_weekly" -> strings.text(R.string.core_usage_ticket_session_weekly)
        "reset_limit" -> strings.text(R.string.core_usage_ticket_reset_limit)
        else -> ticket.title
    }
}
