package dev.remotecode.i18n

import dev.remotecode.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlPathLabelsTest {
    private val strings: AppStringResolver = { id, args -> "[$id:${args.joinToString()}]" }

    @Test fun englishAndJapaneseCatalogLabelsUseTheSameResource() {
        for (label in listOf("Android App", "Android アプリ")) {
            assertEquals(strings.text(R.string.control_path_catalog_001), localizedControlPathText(label, strings))
        }
        assertEquals(strings.text(R.string.control_path_catalog_015), localizedControlPathText("Claude Code connects to the service.", strings))
        assertEquals("Custom path", localizedControlPathText("Custom path", strings))
    }

    @Test fun dynamicLiveNotesPreserveTheCountAcrossServerVersions() {
        val english = "There are 12 conversations saved as live. Because transcript viewing and ACP execution cannot be distinguished by saved flags alone, exact counts by path cannot be determined."
        val japanese = "live として保存されている会話は 12 件です。PC の記録表示と ACP 実行を保存フラグだけでは区別できないため、経路別の件数は確定できません。"
        val expected = strings.text(R.string.control_path_catalog_live_note, "12")
        assertEquals(expected, localizedControlPathText(english, strings))
        assertEquals(expected, localizedControlPathText(japanese, strings))
        assertEquals("$english extra", localizedControlPathText("$english extra", strings))
    }
}
