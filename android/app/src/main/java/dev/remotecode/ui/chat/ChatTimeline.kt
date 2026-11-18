package dev.remotecode.ui.chat

import dev.remotecode.data.Entry

internal data class ChatTimeline(val entries: List<Entry>, val children: Map<String, List<Entry>>)

internal fun Entry.isVisibleInChat(thinking: String): Boolean = when (kind) {
    "thinking" -> thinking != "off" && (streaming || !text.isNullOrBlank())
    "assistant" -> streaming || !text.isNullOrBlank() || !media.isNullOrEmpty() || !videos.isNullOrEmpty()
    else -> true
}

internal fun chatTimeline(entries: List<Entry>, thinking: String): ChatTimeline {
    val visible = entries.filter { it.isVisibleInChat(thinking) }
    val agents = visible.filter { it.kind == "tool" && it.tool == "Agent" }.mapTo(hashSetOf()) { it.id }
    val roots = mutableListOf<Entry>()
    val children = mutableMapOf<String, MutableList<Entry>>()
    for (entry in visible) {
        val parent = entry.parent
        if (parent != null && parent != entry.id && parent in agents) {
            children.getOrPut(parent) { mutableListOf() }.add(entry)
        } else {
            roots.add(entry)
        }
    }
    return ChatTimeline(roots, children)
}
