package dev.remotecode.data

import kotlinx.serialization.Serializable

internal val serverDependencyTargets = setOf("claude", "codex", "antigravity", "cloudflared")

@Serializable
data class ServerDependency(
    val target: String,
    val name: String = "",
    val path: String = "",
    val status: String = "",
    val detail: String = "",
    val installed: Boolean = false,
) {
    val inProgress get() = status.startsWith("installing", ignoreCase = true)
    val failed get() = status.equals("Failed", ignoreCase = true)
    // An executable left by a partial install must still be repairable.
    val canInstall get() = target in serverDependencyTargets && !inProgress && (!installed || failed)
}

@Serializable
data class ServerDependencies(
    val supported: Boolean = false,
    val installing: Boolean = false,
    val dependencies: List<ServerDependency> = emptyList(),
) {
    internal fun completedInstallation(previous: ServerDependencies?): Boolean = previous != null && dependencies.any { next ->
        val old = previous.dependencies.firstOrNull { it.target == next.target }
        next.installed && !next.failed && !next.inProgress && old != null && (!old.installed || old.failed || old.inProgress)
    }
}
