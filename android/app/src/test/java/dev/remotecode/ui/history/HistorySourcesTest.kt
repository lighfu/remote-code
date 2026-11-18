package dev.remotecode.ui.history

import dev.remotecode.data.HistoryItem
import dev.remotecode.data.Providers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class HistorySourcesTest {
    @Test fun slowOrUnavailableSourcesDoNotHideOtherHistories() = runBlocking {
        val codexShown = CompletableDeferred<Unit>()
        val item = HistoryItem("shared-id", "Same title")
        val rows = mutableListOf<HistoryItem>()
        val failures = mutableListOf<String>()
        withTimeout(5_000) {
            loadHistorySources(Providers.all, fetch = { source ->
                when (source) {
                    Providers.CLAUDE -> codexShown.await()
                    Providers.ANTIGRAVITY -> error("Offline")
                }
                listOf(item, item)
            }, onItems = { batch ->
                rows.addAll(batch)
                if (batch.single().provider == Providers.CODEX) codexShown.complete(Unit)
            }, onError = { source, _ -> failures.add(source) })
        }
        assertEquals(listOf("codex:shared-id", "claude:shared-id"), rows.map { it.listKey })
        assertEquals(listOf(Providers.ANTIGRAVITY), failures)
    }
}
