package dev.remotecode.data

import okhttp3.Response

/** serverMs はサーバー側の計測値。 */
data class NetTiming(
    val headersMs: Long,
    val bodyMs: Long,
    val serverMs: Double? = null,
    val serverVersion: String? = null,
    val serverStartedAt: Long? = null,
    val serverInstance: String? = null,
    val wireBytes: Long? = null,
    val cacheHit: Boolean = false,
    val queueMs: Long? = null,
    val dnsMs: Long? = null,
    /** TLS を含む。tlsMs は内訳。 */
    val connectMs: Long? = null,
    val tlsMs: Long? = null,
    val connectionWaitMs: Long? = null,
    val reusedConnection: Boolean? = null,
    val serverHandlerMs: Double? = null,
    val serverJsonMs: Double? = null,
    val serverGzipMs: Double? = null,
) {
    companion object {
        fun fromResponse(response: Response, headersMs: Long, bodyMs: Long): NetTiming {
            val cacheHit = response.cacheResponse != null && response.networkResponse == null
            // 304 の古い計測値を避け、今回のネットワーク応答を使う。
            val received = response.networkResponse ?: response
            val trace = response.request.tag(HttpTrace::class.java)?.snapshot()
            return NetTiming(
                headersMs = headersMs.coerceAtLeast(0),
                bodyMs = bodyMs.coerceAtLeast(0),
                serverMs = if (cacheHit) null else serverDurationMs(received.header("Server-Timing")),
                serverVersion = if (cacheHit) null else received.header("X-Remote-Code-Version"),
                serverStartedAt = if (cacheHit) null else received.header("X-Remote-Code-Started-At")?.toLongOrNull()?.takeIf { it > 0 },
                serverInstance = if (cacheHit) null else received.header("X-Remote-Code-Instance"),
                wireBytes = if (cacheHit) 0 else response.networkResponse?.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 },
                cacheHit = cacheHit,
                queueMs = trace?.queueMs,
                dnsMs = trace?.dnsMs,
                connectMs = trace?.connectMs,
                tlsMs = trace?.tlsMs,
                connectionWaitMs = trace?.connectionWaitMs,
                reusedConnection = trace?.reusedConnection,
                serverHandlerMs = if (cacheHit) null else serverDurationMs(received.header("Server-Timing"), "handler"),
                serverJsonMs = if (cacheHit) null else serverDurationMs(received.header("Server-Timing"), "json"),
                serverGzipMs = if (cacheHit) null else serverDurationMs(received.header("Server-Timing"), "gzip"),
            )
        }
    }
}

/** 引用符内の区切り文字は無視する。 */
private fun splitTimingHeader(value: String, separator: Char): List<String> {
    val parts = mutableListOf<String>()
    var quoted = false
    var escaped = false
    var start = 0
    value.forEachIndexed { index, c ->
        when {
            escaped -> escaped = false
            quoted && c == '\\' -> escaped = true
            c == '"' -> quoted = !quoted
            !quoted && c == separator -> { parts += value.substring(start, index).trim(); start = index + 1 }
        }
    }
    parts += value.substring(start).trim()
    return parts
}

internal fun serverDurationMs(header: String?, name: String = "app"): Double? {
    if (header.isNullOrBlank()) return null
    for (metric in splitTimingHeader(header, ',')) {
        val parameters = splitTimingHeader(metric, ';')
        if (!parameters.first().equals(name, ignoreCase = true)) continue
        for (parameter in parameters.drop(1)) {
            if (!parameter.substringBefore('=').trim().equals("dur", ignoreCase = true)) continue
            return parameter.substringAfter('=', "").trim().removeSurrounding("\"").toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        }
    }
    return null
}
