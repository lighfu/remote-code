package dev.remotecode.ui.history

import dev.remotecode.data.HistoryItem
import dev.remotecode.data.Providers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class HistorySourcesTest {
    @Test fun bulkExpansionPreservesFilteredGroupsAndIndividualOverrides() {
        val visible = setOf("A", "B")
        val mixed = setOf("B", "hidden")
        val closed = mixed.withProjectExpansion(visible, expanded = false)
        assertEquals(setOf("A", "B", "hidden"), closed)
        assertEquals(mixed, closed.withProjectExpansion(setOf("A"), expanded = true))
        assertEquals(setOf("hidden"), mixed.withProjectExpansion(visible, expanded = true))
        assertEquals(mixed, mixed.withProjectExpansion(emptySet(), expanded = false))
    }

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
