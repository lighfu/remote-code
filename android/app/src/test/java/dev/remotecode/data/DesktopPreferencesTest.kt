package dev.remotecode.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopPreferencesTest {
    @Test fun emptyStoreUsesTheViewerDefaults() {
        val expected = DesktopPreferences(
            fps = 30, resolution = DesktopResolution.Native, clipboard = true, audio = true,
            pixelated = true, debugOverlay = false, graphMask = 7, decodeLatest = true,
            bitrateMbps = 0, capture = "auto", codec = "jpeg", fixedJpegQuality = false,
            decodeColor = DesktopDecodeColor.Auto, control = true, touchpad = true, pointerSpeed = 1f,
        )
        assertEquals(expected, DesktopPreferences())
        assertEquals(expected, SettingsStore.readDesktopPreferences(emptyPreferences()))
    }

    @Test fun savedChoicesAndLegacyResolutionOverrideNewDefaults() {
        val highDetail = booleanPreferencesKey("desktop_high_detail")
        val resolution = stringPreferencesKey("desktop_resolution")
        val saved = preferencesOf(
            intPreferencesKey("desktop_fps") to 10, highDetail to false,
            booleanPreferencesKey("desktop_clipboard") to false, booleanPreferencesKey("desktop_audio") to false,
            booleanPreferencesKey("desktop_pixelated") to false, intPreferencesKey("desktop_graph_mask") to 3,
        )
        assertEquals(
            DesktopPreferences(fps = 10, resolution = DesktopResolution.Standard, clipboard = false,
                audio = false, pixelated = false, graphMask = 3),
            SettingsStore.readDesktopPreferences(saved),
        )
        assertEquals(DesktopResolution.High, SettingsStore.readDesktopPreferences(preferencesOf(highDetail to true)).resolution)
        assertEquals(DesktopResolution.Standard,
            SettingsStore.readDesktopPreferences(preferencesOf(resolution to "normal", highDetail to true)).resolution)
    }
}
