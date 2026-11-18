package dev.remotecode.data

fun appendConversationQuote(draft: String, selected: String): String {
    if (selected.isBlank()) return draft
    val separator = when {
        draft.isEmpty() || draft.endsWith("\n\n") -> ""
        draft.endsWith('\n') -> "\n"
        else -> "\n\n"
    }
    return draft + separator + selected.lineSequence().joinToString("\n") { "> $it" } + "\n\n"
}
