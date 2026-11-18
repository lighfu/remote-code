package dev.remotecode.data

import java.util.Locale

private val activeAgentStatuses = setOf(
    "running", "waiting", "pending", "queued", "starting", "initializing", "working",
    "in_progress", "inprogress", "awaiting_input", "awaiting_approval", "paused",
)
private val finishedAgentStatuses = setOf(
    "done", "completed", "complete", "succeeded", "success", "error", "failed", "failure",
    "stopped", "declined", "cancelled", "canceled", "interrupted", "closed", "shutdown",
)

internal fun isActiveAgent(status: String?, streaming: Boolean = false): Boolean {
    val value = status?.trim()?.lowercase(Locale.ROOT)?.replace('-', '_')
    if (value in finishedAgentStatuses) return false
    return streaming || value in activeAgentStatuses
}

internal data class AgentSections(val active: List<Entry>, val completed: List<Entry>)

internal fun agentSections(agents: List<Entry>): AgentSections {
    val (active, completed) = agents.asReversed().partition { isActiveAgent(it.status, it.streaming) }
    return AgentSections(active, completed)
}
