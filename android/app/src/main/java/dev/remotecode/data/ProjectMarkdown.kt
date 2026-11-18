package dev.remotecode.data

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

internal fun isProjectMarkdown(path: String): Boolean {
    val name = path.replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)
    return name == "readme" || name.substringAfterLast('.', "") in setOf("md", "markdown", "mdown", "mkd", "mkdn", "mdx")
}

private fun markdownDecoded(value: String): String =
    runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

/** PC パスは Markdown の場所を基準に解決する。 */
internal fun resolveProjectMarkdownLink(path: String, link: String): String? {
    val raw = link.trim().removeSurrounding("<", ">")
    if (raw.isEmpty() || raw.any { it.code < 32 }) return null
    if (raw.startsWith("//")) return "https:$raw"
    val windowsDrive = Regex("^[A-Za-z]:[/\\\\]")
    val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(raw)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
    if (!windowsDrive.containsMatchIn(raw) && scheme != null) {
        if (scheme in setOf("http", "https", "mailto", "tel")) return raw
        if (scheme == "file") return runCatching { URI(raw).toASCIIString() }.getOrNull()
        return null
    }
    val beforeFragment = raw.substringBefore('#')
    val fragment = raw.substringAfter('#', "").takeIf { '#' in raw }?.let(::markdownDecoded)
    val targetPath = markdownDecoded(beforeFragment.substringBefore('?')).replace('\\', '/')
    val base = path.replace('\\', '/')
    val combined = when {
        targetPath.isEmpty() -> base
        windowsDrive.containsMatchIn(targetPath) || targetPath.startsWith("//") -> targetPath
        targetPath.startsWith('/') && windowsDrive.containsMatchIn(base) -> base.take(2) + targetPath
        targetPath.startsWith('/') -> targetPath
        else -> base.substringBeforeLast('/', "") + "/" + targetPath
    }
    val drive = Regex("^[A-Za-z]:").find(combined)?.value
    val unc = combined.startsWith("//")
    val absolute = combined.startsWith('/') || drive != null
    val body = if (drive != null) combined.substring(2) else combined
    val parts = mutableListOf<String>()
    for (part in body.split('/')) when (part) {
        "", "." -> Unit
        ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
        else -> parts.add(part)
    }
    if (!absolute) return null
    return runCatching {
        when {
            unc -> {
                val authority = parts.firstOrNull() ?: return null
                URI("file", authority, "/" + parts.drop(1).joinToString("/"), null, fragment).toASCIIString()
            }
            drive != null -> URI("file", "", "/$drive/" + parts.joinToString("/"), null, fragment).toASCIIString()
            else -> URI("file", "", "/" + parts.joinToString("/"), null, fragment).toASCIIString()
        }
    }.getOrNull()
}

internal fun projectMarkdownAnchor(value: String): String = markdownDecoded(value.removePrefix("#"))
    .lowercase(Locale.ROOT).trim().replace(Regex("[^\\p{L}\\p{N}\\s_-]"), "").replace(Regex("\\s+"), "-")

/** 画像の自動取得で Windows 共有へ認証しない。 */
internal fun projectMarkdownLocalImagePath(link: String): String? = runCatching {
    val uri = URI(link)
    if (!uri.scheme.equals("file", true) || !uri.authority.isNullOrEmpty() && !uri.authority.equals("localhost", true)) return null
    val path = uri.path ?: return null
    if (path.startsWith("//") || path.startsWith("\\\\") || path.any { it.code < 32 }) return null
    if (Regex("^/[A-Za-z]:/").containsMatchIn(path)) path.drop(1) else path
}.getOrNull()
