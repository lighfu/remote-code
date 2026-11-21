package dev.remotecode.data

import java.util.Locale

private val activeAgentStatuses = setOf(
    "running", "waiting", "pending", "queued", "starting", "initializing", "working",
    "in_progress", "inprogress", "awaiting_input", "awaiting_approval", "paused",
)
internal enum class AgentWorkflowState { ACTIVE, COMPLETED, FAILED, CANCELLED, UNKNOWN }

internal fun agentState(status: String?, streaming: Boolean, ok: Boolean? = null): AgentWorkflowState {
    val value = status?.trim()?.lowercase(Locale.ROOT)?.replace('-', '_')
    return when (value) {
        "done", "completed", "complete", "succeeded", "success" -> AgentWorkflowState.COMPLETED
        "error", "failed", "failure" -> AgentWorkflowState.FAILED
        "stopped", "declined", "cancelled", "canceled", "interrupted", "closed", "shutdown" -> AgentWorkflowState.CANCELLED
        else -> when {
            streaming || value in activeAgentStatuses -> AgentWorkflowState.ACTIVE
            ok == true -> AgentWorkflowState.COMPLETED
            ok == false -> AgentWorkflowState.FAILED
            else -> AgentWorkflowState.UNKNOWN
        }
    }
}

internal fun isActiveAgent(status: String?, streaming: Boolean = false): Boolean =
    agentState(status, streaming) == AgentWorkflowState.ACTIVE

internal data class AgentSections(val active: List<Entry>, val completed: List<Entry>)

internal fun agentSections(agents: List<Entry>): AgentSections {
    val (active, completed) = agents.asReversed().partition { isActiveAgent(it.status, it.streaming) }
    return AgentSections(active, completed)
}
