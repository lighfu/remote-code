package dev.remotecode.ui.chat

private val changeSectionHeader = Regex("""(?m)^── ([^\r\n]+)""")

/** Keep an older combined patch intact unless its named sections account for every listed file. */
internal fun fileChangeSections(files: List<String>, detail: String): Map<String, String> {
    if (files.size < 2 || !detail.startsWith("── ")) return emptyMap()
    val headings = changeSectionHeader.findAll(detail).toList()
    val parts = mutableMapOf<String, MutableList<String>>()
    val paths = files.toSet()
    for ((index, heading) in headings.withIndex()) {
        val name = heading.groupValues[1].trim()
        val parsed = fileChangePath(name)
        val file = when {
            parsed in paths -> parsed
            name in paths -> name
            else -> return emptyMap()
        }
        val next = headings.getOrNull(index + 1)?.range?.first ?: detail.length
        parts.getOrPut(file) { mutableListOf() }.add(detail.substring(heading.range.first, next))
    }
    return if (parts.keys == paths) parts.mapValues { (_, patches) -> patches.joinToString("") } else emptyMap()
}
