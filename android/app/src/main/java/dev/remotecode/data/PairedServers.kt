package dev.remotecode.data

import kotlinx.serialization.builtins.ListSerializer

internal data class PairedServers(val active: ServerConfig?, val inactive: List<ServerConfig>) {
    val all get() = listOfNotNull(active) + inactive

    /** The token stays the same when a server is paired through USB and HTTPS. */
    fun pair(incoming: ServerConfig): PairedServers {
        val existing = all.firstOrNull { it.token == incoming.token }
        val next = if (existing == null) {
            incoming.copy(publicUrl = if (isPublicUrl(incoming.url)) incoming.url else "")
        } else if (isPublicUrl(incoming.url)) {
            existing.copy(
                name = incoming.name.ifBlank { existing.name },
                publicUrl = incoming.url,
                accessId = incoming.accessId.ifBlank { existing.accessId },
                accessSecret = incoming.accessSecret.ifBlank { existing.accessSecret },
            )
        } else {
            existing.copy(url = incoming.url, name = incoming.name.ifBlank { existing.name })
        }
        return PairedServers(next, all.filterNot { it.token == next.token })
    }

    fun select(token: String): PairedServers? {
        val next = all.firstOrNull { it.token == token } ?: return null
        return PairedServers(next, all.filterNot { it.token == token })
    }

    fun forget(token: String): PairedServers? {
        if (inactive.none { it.token == token }) return null
        return copy(inactive = inactive.filterNot { it.token == token })
    }

    fun encodeInactive(): String = AppJson.encodeToString(serializer, inactive)

    companion object {
        private val serializer = ListSerializer(ServerConfig.serializer())

        fun restore(active: ServerConfig?, saved: String?): PairedServers {
            val inactive = saved?.let { runCatching { AppJson.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
            return PairedServers(active, inactive.filter { it.url.isNotBlank() && it.token.isNotBlank() && it.token != active?.token }
                .distinctBy { it.token })
        }
    }
}
