package dev.remotecode.data

import org.junit.Assert.*
import org.junit.Test

class ModelRefreshTest {
    private val server = ServerConfig("https://remote.example", "token", "PC").connectionIdentity()

    @Test fun validatedModelsAreReusedForTenMinutesAndForceBypassesTheWindow() {
        var now = 0L
        val refresh = ModelRefresh { now }
        assertTrue(refresh.needed(server, "codex"))
        refresh.fetched(server, "codex")
        now = 9 * 60_000
        assertFalse(refresh.needed(server, "codex"))
        assertTrue(refresh.needed(server, "codex", force = true))
        now = 10 * 60_000
        assertTrue(refresh.needed(server, "codex"))
    }

    @Test fun serverCredentialsAndProvidersNeverShareValidation() {
        val refresh = ModelRefresh { 0 }
        refresh.fetched(server, "codex")
        assertFalse(refresh.needed(server, "codex"))
        assertTrue(refresh.needed(server, "claude"))
        assertTrue(refresh.needed(server.copy(url = "https://other.example"), "codex"))
        assertTrue(refresh.needed(server.copy(token = "other-token"), "codex"))
        assertTrue(refresh.needed(server.copy(accessSecret = "different"), "codex"))
        refresh.clear()
        assertTrue(refresh.needed(server, "codex"))
    }
}
