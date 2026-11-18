package dev.remotecode.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    @Test fun chineseScriptsOverrideConflictingRegions() {
        for (region in listOf("CN", "SG", "TW", "HK", "MO")) {
            assertEquals("zh-Hant", resolveAppLanguageTag("zh-Hant-$region"))
            assertEquals("zh-CN", resolveAppLanguageTag("zh-Hans-$region"))
        }
    }

    @Test fun traditionalChineseRegionsSelectHant() {
        for (tag in listOf("zh-TW", "zh-HK", "zh-MO", "zh-TW-u-nu-hanidec")) {
            assertEquals("zh-Hant", resolveAppLanguageTag(tag))
        }
    }

    @Test fun defaultAndSimplifiedChineseRegionsSelectSimplified() {
        for (tag in listOf("zh", "zh-CN", "zh-SG", "zh-Hans")) {
            assertEquals("zh-CN", resolveAppLanguageTag(tag))
        }
    }

    @Test fun localeListsUseTheFirstApplicationOverride() {
        assertEquals("zh-Hant", resolveAppLanguageTag("zh-HK,en-US"))
        assertEquals("en", resolveAppLanguageTag("en-US,zh-Hant"))
        assertEquals("zh-Hant", resolveAppLanguageTag(" ZH-hAnT-hk "))
    }

    @Test fun otherSupportedLanguagesKeepTheirBaseSelection() {
        for (tag in listOf("ja-JP", "en-US", "en-GB", "ko-KR")) {
            assertEquals(tag.substringBefore('-'), resolveAppLanguageTag(tag))
        }
    }

    @Test fun automaticAndUnsupportedLocalesDoNotClaimASupportedOverride() {
        for (tag in listOf("", " ", "und", "fr-CA")) assertEquals("", resolveAppLanguageTag(tag))
        assertTrue("zh-Hant" in supportedLanguages)
        assertEquals(5, supportedLanguages.size)
    }
}
