package dev.remotecode.i18n

import dev.remotecode.R
import dev.remotecode.data.ApprovalOption
import dev.remotecode.data.ModeInfo

private val knownModes = setOf("", "default", "acceptEdits", "accept-edits", "auto", "plan", "bypassPermissions", "read-only", "untrusted", "full")
private val modeLabels = mapOf(
    "PCの設定どおり" to R.string.core_mode_pc_default,
    "PC の設定どおり" to R.string.core_mode_pc_default,
    "毎回確認" to R.string.core_mode_ask,
    "編集は自動" to R.string.core_mode_edit_auto,
    "自動判定" to R.string.core_mode_auto,
    "計画モード" to R.string.core_mode_plan,
    "すべて許可" to R.string.core_mode_allow_all,
    "読み取り専用" to R.string.core_mode_read_only,
    "作業フォルダ内は自動" to R.string.core_mode_workspace_auto,
    "フルアクセス" to R.string.core_mode_full,
)
private val modeDescriptions = mapOf(
    "PC の Claude Code の既定モードで動かします" to R.string.core_mode_description_claude_default,
    "PC の Antigravity の既定のモードで動かします" to R.string.core_mode_description_antigravity_default,
    "~/.codex/config.toml の承認・サンドボックス設定で動かします" to R.string.core_mode_description_codex_default,
    "ファイル編集やコマンド実行のたびに確認します" to R.string.core_mode_description_ask,
    "ファイル編集は自動で許可し、コマンドは確認します" to R.string.core_mode_description_edit_auto,
    "安全な操作は自動で許可し、判断できないものだけ確認します" to R.string.core_mode_description_claude_auto,
    "編集と安全なコマンドは自動で許可し、取り消せない操作だけ確認します" to R.string.core_mode_description_antigravity_auto,
    "読み取りだけで計画を立て、実行前に承認を求めます" to R.string.core_mode_description_claude_plan,
    "実行の前に、じっくり計画を立てます" to R.string.core_mode_description_antigravity_plan,
    "確認なしで何でも実行します。信頼できる作業だけに" to R.string.core_mode_description_full,
    "ファイルを読むだけ。変更やコマンドは確認します" to R.string.core_mode_description_read_only,
    "作業フォルダ内の編集とコマンドは自動、外に出るときは確認します" to R.string.core_mode_description_workspace_auto,
    "安全と判断できるもの以外はすべて確認します" to R.string.core_mode_description_untrusted,
)

fun localizedModeLabel(mode: ModeInfo, strings: AppStringResolver = applicationStrings): String =
    if (mode.id in knownModes) serverMessageCandidates(mode.label).firstNotNullOfOrNull { modeLabels[it]?.let { id -> strings.text(id) } } ?: mode.label else mode.label

fun localizedModeDescription(mode: ModeInfo, strings: AppStringResolver = applicationStrings): String =
    if (mode.id in knownModes) serverMessageCandidates(mode.description).firstNotNullOfOrNull { modeDescriptions[it]?.let { id -> strings.text(id) } } ?: mode.description else mode.description

private val usageScopes = mapOf(
    "全モデル" to R.string.core_usage_all_models,
    "モデル別" to R.string.core_usage_by_model,
    "利用枠" to R.string.core_usage_limit,
    "OAuth アプリ" to R.string.core_usage_oauth_apps,
    "All models" to R.string.core_usage_all_models,
    "By model" to R.string.core_usage_by_model,
    "Usage quota" to R.string.core_usage_limit,
    "OAuth apps" to R.string.core_usage_oauth_apps,
)
private val scopedUsage = Regex("^(.+?)[（(](.+)[）)]$")
private val hours = Regex("^([0-9]+)(?:時間|h| hours?)$", RegexOption.IGNORE_CASE)
private val days = Regex("^([0-9]+)(?:日|d| days?)$", RegexOption.IGNORE_CASE)

private fun usagePeriod(label: String, strings: AppStringResolver): String? = when (label.trim()) {
    "今週", "Weekly" -> strings.text(R.string.core_usage_week)
    "短期", "Short-term" -> strings.text(R.string.core_usage_short_term)
    "長期", "Long-term" -> strings.text(R.string.core_usage_long_term)
    else -> hours.matchEntire(label.trim())?.let { strings.text(R.string.core_usage_hours, it.groupValues[1]) }
        ?: days.matchEntire(label.trim())?.let { strings.text(R.string.core_usage_days, it.groupValues[1]) }
}

fun localizedUsageLabel(label: String, strings: AppStringResolver = applicationStrings): String {
    usagePeriod(label, strings)?.let { return it }
    val match = scopedUsage.matchEntire(label) ?: return label
    val period = usagePeriod(match.groupValues[1], strings) ?: return label
    val rawScope = match.groupValues[2]
    val scope = serverMessageCandidates(rawScope).firstNotNullOfOrNull { usageScopes[it]?.let { id -> strings.text(id) } } ?: rawScope
    return strings.text(R.string.core_usage_scoped, period, scope)
}

private val approvalLabels = mapOf(
    "allow" to mapOf(
        "許可" to R.string.core_approval_allow,
        "今回だけ許可" to R.string.core_approval_allow_once,
        "承認（編集は自動）" to R.string.core_approval_accept_edits,
    ),
    "allowAlways" to mapOf(
        "今後も許可" to R.string.core_approval_allow_always,
        "このセッション中は許可" to R.string.core_approval_allow_session,
        "承認（毎回確認）" to R.string.core_approval_accept_ask,
    ),
    "deny" to mapOf(
        "拒否" to R.string.core_approval_deny,
        "計画を続ける" to R.string.core_approval_continue_plan,
    ),
    "other" to mapOf("拒否して中断" to R.string.core_approval_deny_stop),
)

fun localizedApprovalLabel(option: ApprovalOption, strings: AppStringResolver = applicationStrings): String =
    serverMessageCandidates(option.label).firstNotNullOfOrNull { approvalLabels[option.kind]?.get(it)?.let { id -> strings.text(id) } } ?: option.label
