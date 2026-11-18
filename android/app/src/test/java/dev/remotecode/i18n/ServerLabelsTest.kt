package dev.remotecode.i18n

import dev.remotecode.R
import dev.remotecode.data.ApprovalOption
import dev.remotecode.data.ModeInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerLabelsTest {
    private val strings: AppStringResolver = { id, args -> "[$id:${args.joinToString()}]" }

    @Test fun knownModeLabelsAndDescriptionsUseResourcesWithoutChangingIds() {
        val mode = ModeInfo("acceptEdits", "編集は自動", "ファイル編集は自動で許可し、コマンドは確認します")
        assertEquals(strings.text(R.string.core_mode_edit_auto), localizedModeLabel(mode, strings))
        assertEquals(strings.text(R.string.core_mode_description_edit_auto), localizedModeDescription(mode, strings))
        assertEquals("acceptEdits", mode.id)
        assertEquals("編集は自動", mode.label)
    }

    @Test fun unknownModesAndCustomDescriptionsStayUnchanged() {
        assertEquals("毎回確認", localizedModeLabel(ModeInfo("custom-mode", "毎回確認"), strings))
        val mode = ModeInfo("auto", "Custom title", "user-authored description")
        assertEquals(mode.label, localizedModeLabel(mode, strings))
        assertEquals(mode.description, localizedModeDescription(mode, strings))
    }

    @Test fun scopedUsageKeepsProperModelNamesAndOnlyLocalizesKnownPeriods() {
        val week = strings.text(R.string.core_usage_week)
        assertEquals(strings.text(R.string.core_usage_scoped, week, "GPT-6 Model"), localizedUsageLabel("今週（GPT-6 Model）", strings))
        assertEquals(strings.text(R.string.core_usage_scoped, week, strings.text(R.string.core_usage_all_models)), localizedUsageLabel("今週（全モデル）", strings))
        assertEquals(strings.text(R.string.core_usage_hours, "5"), localizedUsageLabel("5時間", strings))
        assertEquals(strings.text(R.string.core_usage_days, "2"), localizedUsageLabel("2日", strings))
        assertEquals("Custom period（My quota）", localizedUsageLabel("Custom period（My quota）", strings))
    }

    @Test fun approvalSemanticsArePreservedAndCustomLabelsAreNotTranslated() {
        assertEquals(strings.text(R.string.core_approval_allow), localizedApprovalLabel(ApprovalOption("accept", "許可", "allow"), strings))
        assertEquals(strings.text(R.string.core_approval_allow_session), localizedApprovalLabel(ApprovalOption("session", "このセッション中は許可", "allowAlways"), strings))
        assertEquals(strings.text(R.string.core_approval_continue_plan), localizedApprovalLabel(ApprovalOption("deny", "計画を続ける", "deny"), strings))
        assertEquals("Custom authorization", localizedApprovalLabel(ApprovalOption("accept", "Custom authorization", "allow"), strings))
        assertEquals("許可", localizedApprovalLabel(ApprovalOption("custom", "許可", "other"), strings))
    }

    @Test fun englishModesUseTheSameResourcesAndResolveAmbiguousAliasesByCatalog() {
        val mode = ModeInfo("auto", "Auto", "Allow safe actions automatically; ask only when unsure")
        assertEquals(strings.text(R.string.core_mode_auto), localizedModeLabel(mode, strings))
        assertEquals(strings.text(R.string.core_mode_description_claude_auto), localizedModeDescription(mode, strings))
        assertEquals(strings.text(R.string.core_mode_ask), localizedModeLabel(ModeInfo("default", "Confirm every time"), strings))
        assertEquals(strings.text(R.string.core_mode_ask), localizedModeLabel(ModeInfo("default", "Ask every time"), strings))
        assertEquals("Auto", localizedModeLabel(ModeInfo("custom", "Auto"), strings))
    }

    @Test fun englishUsagePeriodsAndScopesLeaveProperModelNamesUnchanged() {
        val week = strings.text(R.string.core_usage_week)
        assertEquals(strings.text(R.string.core_usage_scoped, week, "GPT-6 Model"), localizedUsageLabel("Weekly (GPT-6 Model)", strings))
        assertEquals(strings.text(R.string.core_usage_scoped, week, strings.text(R.string.core_usage_all_models)), localizedUsageLabel("Weekly (All models)", strings))
        assertEquals(strings.text(R.string.core_usage_hours, "5"), localizedUsageLabel("5h", strings))
        assertEquals(strings.text(R.string.core_usage_hours, "12"), localizedUsageLabel("12 hours", strings))
        assertEquals(strings.text(R.string.core_usage_days, "2"), localizedUsageLabel("2d", strings))
        assertEquals(strings.text(R.string.core_usage_short_term), localizedUsageLabel("Short-term", strings))
        assertEquals("Custom (My quota)", localizedUsageLabel("Custom (My quota)", strings))
    }

    @Test fun englishApprovalAliasesStillRequireTheCorrectKind() {
        assertEquals(strings.text(R.string.core_approval_allow), localizedApprovalLabel(ApprovalOption("accept", "Allow", "allow"), strings))
        assertEquals(strings.text(R.string.core_approval_allow_session), localizedApprovalLabel(ApprovalOption("session", "Allow for this session", "allowAlways"), strings))
        assertEquals("Allow", localizedApprovalLabel(ApprovalOption("custom", "Allow", "other"), strings))
    }
}
