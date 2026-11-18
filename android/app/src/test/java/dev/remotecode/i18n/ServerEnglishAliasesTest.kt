package dev.remotecode.i18n

import dev.remotecode.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerEnglishAliasesTest {
    private val strings: AppStringResolver = { id, args -> "[$id:${args.joinToString()}]" }

    @Test fun exactEnglishAndLegacyErrorsUseTheSameResource() {
        for (message in listOf("Session not found", "セッションが見つかりません")) {
            assertEquals(strings.text(R.string.core_session_not_found), localizedServerMessage(message, strings))
        }
        assertEquals(strings.text(R.string.core_token_invalid), localizedServerMessage("Invalid token", strings))
    }

    @Test fun aliasesPreserveAllLegacyCandidatesInTheirOriginalOrder() {
        val candidates = serverMessageCandidates("Auto")
        assertEquals("Auto", candidates.first())
        assertTrue(candidates.contains("自動"))
        assertTrue(candidates.contains("自動判定"))
        assertEquals(listOf("unknown"), serverMessageCandidates("unknown"))
    }

    @Test fun generatedOriginTitlesShareResourcesWithLegacyLabels() {
        assertEquals(strings.text(R.string.core_origin_pc), localizedServerMessage("From PC", strings))
        assertEquals(strings.text(R.string.core_origin_main_session), localizedServerMessage("Request from main session", strings))
        assertEquals(strings.text(R.string.core_origin_other_screen), localizedServerMessage("From another screen", strings))
        assertEquals(strings.text(R.string.core_origin_other_screen), localizedServerMessage("ほかの画面から", strings))
        assertEquals(strings.text(R.string.core_answered_on_pc), localizedServerMessage("Answered on PC", strings))
    }

    @Test fun unknownServiceMessagesAndCreditTitlesRemainUnchanged() {
        for (message in listOf("Session not found: custom", "Full reset", "Custom service response: \$1", "モデル固有の説明")) {
            assertEquals(message, localizedServerMessage(message, strings))
        }
    }
}
