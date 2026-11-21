package dev.remotecode.data

import java.security.MessageDigest

/** 表示名や代替 URL は通信先の識別に含めない。 */
internal data class ConnectionIdentity(val url: String, val token: String, val accessId: String, val accessSecret: String)

internal fun ServerConfig.connectionIdentity() = ConnectionIdentity(url.trimEnd('/'), token, accessId, accessSecret)

internal fun ServerConfig.pairedServerId(): String =
    MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray()).toHexString()

internal typealias SharedRequests<K> = SharedValues<K, Unit>

internal suspend fun <K> SharedRequests<K>.run(key: K, block: suspend () -> Unit) = get(key, block)

/** WS の新しい利用状況を古い HTTP 応答で上書きしない。 */
internal fun mergeUsage(current: UsageInfo?, incoming: UsageInfo): UsageInfo {
    fun newer(old: ProviderUsage?, next: ProviderUsage?): ProviderUsage? = when {
        next == null -> old
        old != null && old.at > next.at -> old
        else -> next
    }
    return UsageInfo(
        claude = newer(current?.claude, incoming.claude),
        codex = newer(current?.codex, incoming.codex),
        antigravity = newer(current?.antigravity, incoming.antigravity),
    )
}
