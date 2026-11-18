package dev.remotecode.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatFooterDisplayTest {
    @Test
    fun legacyHiddenPreferencesKeepTheirVisibilityAndDefaultOrder() {
        val legacy = AppJson.decodeFromString(ChatFooterDisplay.serializer(), """{"cache":false,"git":false}""")
        assertEquals(ChatFooterItem.entries.toList(), legacy.orderedItems)
        assertFalse(legacy.cache)
        assertFalse(legacy.git)
        assertEquals(3, legacy.visibleCount)
    }

    @Test
    fun malformedOrderKeepsFirstKnownOccurrenceAndAppendsMissingItems() {
        val display = ChatFooterDisplay(order = listOf("git", "", "future", " usage ", "git", "usage"))
        assertEquals(
            listOf(ChatFooterItem.GIT, ChatFooterItem.USAGE, ChatFooterItem.CONTEXT, ChatFooterItem.CACHE, ChatFooterItem.STATS),
            display.orderedItems,
        )
        assertEquals(ChatFooterItem.entries.toList(), ChatFooterDisplay(order = emptyList()).orderedItems)
    }

    @Test
    fun movingToEitherEndPreservesVisibilityAndEveryOtherItemsRelativeOrder() {
        val initial = ChatFooterDisplay(context = false, stats = false)
        val first = initial.move(ChatFooterItem.GIT, Int.MIN_VALUE)
        assertEquals(listOf("git", "context", "cache", "usage", "stats"), first.order)
        val last = first.move(ChatFooterItem.CONTEXT, Int.MAX_VALUE)
        assertEquals(listOf("git", "cache", "usage", "stats", "context"), last.order)
        assertEquals(initial.copy(order = last.order), last)
        assertEquals(5, last.orderedItems.distinct().size)
    }

    @Test
    fun visibilityChangesAndSerializationKeepCustomOrder() {
        val display = ChatFooterDisplay().move(ChatFooterItem.STATS, 0).withVisibility(ChatFooterItem.CACHE, false)
        val restored = AppJson.decodeFromString(ChatFooterDisplay.serializer(), AppJson.encodeToString(ChatFooterDisplay.serializer(), display))
        assertEquals(listOf("stats", "context", "cache", "usage", "git"), restored.order)
        assertFalse(restored.isVisible(ChatFooterItem.CACHE))
        assertEquals(4, restored.visibleCount)
        assertEquals(display, restored)
    }
}
