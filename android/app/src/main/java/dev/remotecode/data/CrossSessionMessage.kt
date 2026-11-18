package dev.remotecode.data

import kotlinx.serialization.Serializable

@Serializable
data class CrossSessionMessage(
    val senderName: String? = null,
    val senderSessionId: String? = null,
    val senderMode: String? = null,
    val senderAddress: String? = null,
    val text: String = "",
)

private val peerEnvelope = Regex("""^\s*<cross-session-message\b((?:[^>"']|"[^"]*"|'[^']*')*)>([\s\S]*)</cross-session-message>\s*$""")
private val peerAttribute = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
private val peerEntity = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);""")
private fun decodePeerAttribute(value: String): String = peerEntity.replace(value) { match ->
    when (val entity = match.groupValues[1]) {
        "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
        else -> {
            val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
            if (code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF) String(Character.toChars(code)) else match.value
        }
    }
}

fun parseCrossSessionMessage(input: String): CrossSessionMessage? {
    if (input.length > 1_100_000 || !input.trimStart().startsWith("<cross-session-message")) return null
    val envelope = peerEnvelope.matchEntire(input) ?: return null
    val attributes = linkedMapOf<String, String>()
    var end = 0
    for (attribute in peerAttribute.findAll(envelope.groupValues[1])) {
        if (envelope.groupValues[1].substring(end, attribute.range.first).isNotBlank()) return null
        if (attributes.containsKey(attribute.groupValues[1])) return null
        attributes[attribute.groupValues[1]] = decodePeerAttribute(attribute.groups[2]?.value ?: attribute.groupValues[3])
        end = attribute.range.last + 1
    }
    if (envelope.groupValues[1].substring(end).isNotBlank()) return null
    fun attribute(name: String, maximum: Int): String? = attributes[name]?.takeIf { it.isNotBlank() && it.length <= maximum && it.none(Char::isISOControl) }
    val address = attribute("from", 4096)
    val session = attribute("from-session", 512)
    val name = attribute("from-name", 512)?.replace(Regex("[\\p{Cc}\\p{Cf}\\p{Cs}\\p{Zl}\\p{Zp}]"), "")?.trim()?.take(128)
    if (address == null && session == null && name.isNullOrEmpty()) return null
    val body = envelope.groupValues[2].removePrefix("\r\n").removePrefix("\n").removeSuffix("\r\n").removeSuffix("\n")
    return CrossSessionMessage(name, session, attribute("from-mode", 64), address, body)
}

fun Entry.crossSessionMessage(): CrossSessionMessage? {
    if (kind != "user") return null
    if (messageOrigin == "human" || messageOrigin == "other") return null
    crossSession?.let { return it.copy(text = text.orEmpty()) }
    return if (title in setOf("PC から", "PCから", "本体からの依頼", "ほかの画面から", "別の画面から", "From PC", "From another screen", "Request from main session")) parseCrossSessionMessage(text.orEmpty()) else null
}
