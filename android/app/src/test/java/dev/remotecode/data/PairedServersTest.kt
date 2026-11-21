package dev.remotecode.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairedServersTest {
    private val desktop = ServerConfig(
        url = "http://localhost:8787", token = "desktop-token", name = "Desktop",
        accessId = "desktop-id", accessSecret = "desktop-secret", publicUrl = "https://desktop.example",
    )
    private val laptop = ServerConfig(
        url = "https://laptop.example", token = "laptop-token", name = "Laptop",
        accessId = "laptop-id", accessSecret = "laptop-secret",
    )

    @Test fun legacyConnectionSurvivesPairingAndSwitching() {
        val lastCwd = stringPreferencesKey("last_cwd")
        val desktopClipboard = booleanPreferencesKey("desktop_clipboard")
        val prefs = preferencesOf(
            stringPreferencesKey("server_url") to desktop.url,
            stringPreferencesKey("server_token") to desktop.token,
            stringPreferencesKey("server_name") to desktop.name,
            stringPreferencesKey("cf_access_id") to desktop.accessId,
            stringPreferencesKey("cf_access_secret") to desktop.accessSecret,
            stringPreferencesKey("public_url") to desktop.publicUrl,
            lastCwd to "C:/work",
            desktopClipboard to false,
        ).toMutablePreferences()

        val legacy = SettingsStore.readPairedServers(prefs)
        assertEquals(listOf(desktop), legacy.all)

        SettingsStore.writePairedServers(prefs, legacy.pair(laptop))
        val paired = SettingsStore.readPairedServers(prefs)
        val laptopWithPublicUrl = laptop.copy(publicUrl = laptop.url)
        assertEquals(listOf(laptopWithPublicUrl, desktop), paired.all)
        assertNull(prefs[lastCwd])
        assertNull(prefs[desktopClipboard])

        SettingsStore.writePairedServers(prefs, requireNotNull(paired.select(desktop.token)))
        assertEquals(listOf(desktop, laptopWithPublicUrl), SettingsStore.readPairedServers(prefs).all)
    }

    @Test fun pairingAnInactiveServerMergesItsRoutesAndCredentials() {
        val original = PairedServers(laptop, listOf(desktop))
        val renamed = original.pair(desktop.copy(url = "https://new-desktop.example", name = "Renamed", accessId = "", accessSecret = ""))

        assertEquals(listOf(desktop.copy(name = "Renamed", publicUrl = "https://new-desktop.example"), laptop), renamed.all)
        assertEquals(renamed.active?.copy(url = "http://127.0.0.1:8787"),
            renamed.pair(desktop.copy(url = "http://127.0.0.1:8787", name = "")).active)
    }

    @Test fun forgettingOnlyRemovesAnInactiveServer() {
        val paired = PairedServers(desktop, listOf(laptop))
        assertNull(paired.select("unknown"))
        assertNull(paired.forget(desktop.token))
        assertEquals(listOf(desktop), paired.forget(laptop.token)?.all)
    }
}
