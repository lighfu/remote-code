package dev.remotecode.data

import kotlinx.serialization.Serializable

enum class ChatFooterItem(val id: String) {
    CONTEXT("context"), CACHE("cache"), USAGE("usage"), GIT("git"), STATS("stats"),
}

/** 未設定の項目は表示する。 */
@Serializable
data class ChatFooterDisplay(
    val context: Boolean = true,
    val cache: Boolean = true,
    val usage: Boolean = true,
    val stats: Boolean = true,
    val git: Boolean = true,
    val order: List<String> = ChatFooterItem.entries.map { it.id },
    val inputTokens: Boolean = true,
    val outputTokens: Boolean = true,
    val cachedTokens: Boolean = true,
    val cacheRate: Boolean = true,
    val costUsd: Boolean = true,
    val costLocal: Boolean = true,
) {
    val visibleCount: Int get() = listOf(context, cache, usage, stats, git).count { it }

    /** 既知の全項目を重複なく返す。 */
    val orderedItems: List<ChatFooterItem>
        get() {
            val known = ChatFooterItem.entries.associateBy { it.id }
            return (order.mapNotNull { known[it.trim()] } + ChatFooterItem.entries).distinct()
        }

    fun isVisible(item: ChatFooterItem): Boolean = when (item) {
        ChatFooterItem.CONTEXT -> context
        ChatFooterItem.CACHE -> cache
        ChatFooterItem.USAGE -> usage
        ChatFooterItem.STATS -> stats
        ChatFooterItem.GIT -> git
    }

    fun withVisibility(item: ChatFooterItem, visible: Boolean): ChatFooterDisplay = when (item) {
        ChatFooterItem.CONTEXT -> copy(context = visible)
        ChatFooterItem.CACHE -> copy(cache = visible)
        ChatFooterItem.USAGE -> copy(usage = visible)
        ChatFooterItem.STATS -> copy(stats = visible)
        ChatFooterItem.GIT -> copy(git = visible)
    }

    /** toIndex は移動後の位置。 */
    fun move(item: ChatFooterItem, toIndex: Int): ChatFooterDisplay {
        val items = orderedItems.toMutableList()
        items.remove(item)
        items.add(toIndex.coerceIn(0, items.size), item)
        return copy(order = items.map { it.id })
    }
}
