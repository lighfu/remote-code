package dev.remotecode.data

data class AttachedFileReference(val name: String, val path: String, val size: Long)
data class AttachedFileText(val text: String, val files: List<AttachedFileReference> = emptyList())

private val ATTACHMENTS_HEADERS = listOf("添付ファイル（PC に保存済み）:", "Attachments (saved on PC):")

/** サーバーが付加した添付一覧だけを分離する。 */
fun attachedFileText(original: String): AttachedFileText {
    val text = original.replace("\r\n", "\n")
    val location = ATTACHMENTS_HEADERS.mapNotNull { header ->
        val split = text.lastIndexOf("\n\n$header\n")
        when {
            split >= 0 -> Triple(header, split + 2, split)
            text.startsWith("$header\n") -> Triple(header, 0, -1)
            else -> null
        }
    }.maxByOrNull { it.second } ?: return AttachedFileText(original)
    val (header, start, split) = location
    val lines = text.substring(start + header.length + 1).trimEnd('\n').split('\n')
    if (lines.size !in 1..10) return AttachedFileText(original)
    val files = lines.map { attachedFileLine(it) ?: return AttachedFileText(original) }
    return AttachedFileText(if (split >= 0) text.substring(0, split) else "", files)
}

private fun attachedFileLine(line: String): AttachedFileReference? {
    if (!line.startsWith("- [") || !line.endsWith(" bytes)")) return null
    var nameEnd = 3
    while (nameEnd < line.length && !line.startsWith("](<", nameEnd)) {
        nameEnd += if (line[nameEnd] == '\\') 2 else 1
    }
    if (nameEnd >= line.length) return null
    val tail = line.lastIndexOf(">) (")
    if (tail < nameEnd + 3) return null
    val size = line.substring(tail + 4, line.length - 7).toLongOrNull()?.takeIf { it >= 0 } ?: return null
    val path = line.substring(nameEnd + 3, tail)
    val absolute = path.startsWith('/') || (path.length >= 3 && path[0].isLetter() && path[1] == ':' && path[2] == '/')
    if (!absolute || path.length > 4096 || path.any { it.code < 32 || it == '<' || it == '>' }) return null
    // 通常の Markdown 一覧を添付と誤認しない。
    val bucket = path.substringAfterLast("/attachments/", "").substringBefore('/')
    if (bucket.length != 36 || bucket.any { it !in "0123456789abcdefABCDEF-" } || !path.contains("/attachments/$bucket/file/")) return null
    val rawName = line.substring(3, nameEnd)
    val name = buildString {
        var index = 0
        while (index < rawName.length) {
            if (rawName[index] == '\\' && index + 1 < rawName.length && rawName[index + 1] in "\\[]") index++
            append(rawName[index++])
        }
    }
    if (name.isBlank() || name.any { it.code < 32 }) return null
    return AttachedFileReference(name, path, size)
}
