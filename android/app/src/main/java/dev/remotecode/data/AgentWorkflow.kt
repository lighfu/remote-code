package dev.remotecode.data

internal data class AgentWorkflowCounts(
    val total: Int,
    val active: Int,
    val completed: Int,
    val failed: Int,
    val cancelled: Int,
    val unknown: Int,
)

internal data class AgentWorkflowRow(
    val entry: Entry,
    val rootId: String,
    val depth: Int,
    val childCount: Int,
    val state: AgentWorkflowState,
    val hasNextSibling: Boolean,
    private val ancestorBranches: AgentWorkflowBranch?,
) {
    /** Node depths of ancestor branches that continue past this row, nearest first. */
    fun continuingAncestorDepths(): Sequence<Int> =
        generateSequence(ancestorBranches, AgentWorkflowBranch::previous).map(AgentWorkflowBranch::depth)
}

// Descendants share the guides they inherit. An uninterrupted chain adds no guides.
internal class AgentWorkflowBranch(val depth: Int, val previous: AgentWorkflowBranch?)

internal data class AgentWorkflowGroup(
    val id: String,
    val prompt: Entry?,
    val counts: AgentWorkflowCounts,
    private val allRows: List<AgentWorkflowRow>,
) {
    fun rows(collapsed: Set<String>): List<AgentWorkflowRow> {
        if (collapsed.isEmpty()) return allRows
        return buildList {
            var collapsedDepth: Int? = null
            for (row in allRows) {
                if (collapsedDepth != null && row.depth > collapsedDepth) continue
                add(row)
                collapsedDepth = row.depth.takeIf { row.entry.id in collapsed }
            }
        }
    }
}

internal data class AgentWorkflow(val groups: List<AgentWorkflowGroup>, val counts: AgentWorkflowCounts)

internal fun agentWorkflow(entries: List<Entry>): AgentWorkflow {
    // Keep the timeline position of each entry, using the last copy for its latest status.
    val byId = entries.asSequence().filter { it.id.isNotBlank() }.associateBy(Entry::id)
    val nodes = byId.filterValues { it.kind == "tool" && it.tool in WorkflowTools }
    val parents = workflowParents(byId, nodes)
    val children = mutableMapOf<String, MutableList<Entry>>()
    val rootsByPrompt = linkedMapOf<String?, MutableList<Entry>>()
    var promptId: String? = null
    for (entry in byId.values) {
        if (entry.isWorkflowPrompt()) promptId = entry.id
        if (entry.id !in nodes) continue
        val parent = parents[entry.id]
        val branch = if (parent == null) rootsByPrompt.getOrPut(promptId) { mutableListOf() }
            else children.getOrPut(parent) { mutableListOf() }
        branch.add(entry)
    }

    val groups = rootsByPrompt.map { (prompt, roots) ->
        val rows = flattenWorkflow(roots, children)
        val id = if (prompt != null) "prompt:$prompt" else "before-prompt"
        AgentWorkflowGroup(id, prompt?.let(byId::get), workflowCounts(rows.asSequence()), rows)
    }.asReversed()
    val counts = workflowCounts(groups.asSequence().flatMap { it.rows(emptySet()).asSequence() })
    return AgentWorkflow(groups, counts)
}

private val AgentTools = setOf("Agent", "Task")
private val WorkflowTools = AgentTools + setOf("Workflow", "WorkflowPhase")

private fun Entry.isWorkflowPrompt(): Boolean = kind == "user" && parent.isNullOrBlank() &&
    (messageOrigin.isNullOrBlank() || messageOrigin == "human") && crossSessionMessage() == null

private fun workflowParents(byId: Map<String, Entry>, nodes: Map<String, Entry>): Map<String, String?> {
    val nearest = mutableMapOf<String, String?>()
    val parents = nodes.mapValuesTo(mutableMapOf()) { (_, entry) ->
        val trail = linkedSetOf<String>()
        var parent = entry.parent
        // An intermediate tool or message can still carry the enclosing agent ID.
        while (parent != null && parent !in nodes) {
            if (parent in nearest) {
                parent = nearest[parent]
                break
            }
            if (!trail.add(parent)) {
                parent = null
                break
            }
            parent = byId[parent]?.parent
        }
        for (id in trail) nearest[id] = parent
        parent
    }

    // Keep each row reachable even when a malformed record links back to itself.
    val visited = hashSetOf<String>()
    for (id in parents.keys) {
        val path = linkedSetOf<String>()
        var next: String? = id
        while (next != null && next !in visited) {
            if (!path.add(next)) {
                parents[next] = null
                break
            }
            next = parents[next]
        }
        visited.addAll(path)
    }
    return parents
}

private data class UnvisitedWorkflowNode(
    val entry: Entry,
    val rootId: String,
    val depth: Int,
    val hasNextSibling: Boolean,
    val ancestorBranches: AgentWorkflowBranch?,
)

private fun flattenWorkflow(roots: List<Entry>, children: Map<String, List<Entry>>): List<AgentWorkflowRow> = buildList {
    val stack = ArrayDeque<UnvisitedWorkflowNode>()
    for (index in roots.indices.reversed()) {
        val root = roots[index]
        stack.addLast(UnvisitedWorkflowNode(root, root.id, 0, index < roots.lastIndex, null))
    }
    while (stack.isNotEmpty()) {
        val (entry, root, depth, hasNextSibling, ancestorBranches) = stack.removeLast()
        val descendants = children[entry.id].orEmpty()
        add(AgentWorkflowRow(
            entry, root, depth, descendants.size, agentState(entry.status, entry.streaming, entry.ok),
            hasNextSibling, ancestorBranches,
        ))
        val childBranches = if (depth > 0 && hasNextSibling && descendants.isNotEmpty()) {
            AgentWorkflowBranch(depth, ancestorBranches)
        } else ancestorBranches
        for (index in descendants.indices.reversed()) {
            stack.addLast(UnvisitedWorkflowNode(
                descendants[index], root, depth + 1, index < descendants.lastIndex, childBranches,
            ))
        }
    }
}

private fun workflowCounts(rows: Sequence<AgentWorkflowRow>): AgentWorkflowCounts {
    val counts = IntArray(AgentWorkflowState.entries.size)
    for (row in rows) {
        if (row.entry.tool in AgentTools) counts[row.state.ordinal]++
    }
    return AgentWorkflowCounts(
        total = counts.sum(),
        active = counts[AgentWorkflowState.ACTIVE.ordinal],
        completed = counts[AgentWorkflowState.COMPLETED.ordinal],
        failed = counts[AgentWorkflowState.FAILED.ordinal],
        cancelled = counts[AgentWorkflowState.CANCELLED.ordinal],
        unknown = counts[AgentWorkflowState.UNKNOWN.ordinal],
    )
}
