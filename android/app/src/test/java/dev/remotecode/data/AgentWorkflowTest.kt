package dev.remotecode.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentWorkflowTest {
    @Test fun groupsParallelAndNestedAgentsWithTheRootPrompt() {
        val originalPrompt = Entry("original", kind = "user", text = "Start the workflow")
        val laterPrompt = Entry("later", kind = "user", text = "Start another task")
        val workflow = agentWorkflow(listOf(
            originalPrompt,
            tool("workflow", status = "running", name = "Workflow"),
            tool("research", "workflow", "waiting"),
            Entry("preview", kind = "assistant", parent = "research"),
            tool("review", "workflow", "done"),
            laterPrompt,
            tool("separate", status = "cancelled"),
            tool("verify", "preview", "failed"),
        ))

        assertEquals(listOf(laterPrompt, originalPrompt), workflow.groups.map { it.prompt })
        assertEquals(AgentWorkflowCounts(4, 1, 1, 1, 1, 0), workflow.counts)
        val group = workflow.groups.last()
        val rows = group.rows(emptySet())
        assertEquals(listOf("workflow", "research", "verify", "review"), rows.map { it.entry.id })
        assertEquals(listOf(0, 1, 2, 1), rows.map { it.depth })
        assertEquals(listOf(2, 1, 0, 0), rows.map { it.childCount })
        assertEquals(setOf("workflow"), rows.map { it.rootId }.toSet())
        assertEquals(listOf("workflow", "research", "review"), group.rows(setOf("research")).map { it.entry.id })
        assertEquals(listOf("workflow"), group.rows(setOf("workflow", "research")).map { it.entry.id })
        assertEquals(AgentWorkflowCounts(3, 1, 1, 1, 0, 0), group.counts)
    }

    @Test fun duplicateAndUnresolvedEntriesStayVisibleOnceWithTheirLatestStatus() {
        val duplicate = tool("repeat", status = "running")
        val workflow = agentWorkflow(listOf(
            duplicate,
            tool("missing-parent", "unavailable"),
            tool("cycle-first", "cycle-second", "running"),
            tool("cycle-second", "cycle-first", "declined"),
            tool("self-cycle", "self-cycle", "error"),
            Entry("intermediate-cycle", kind = "assistant", parent = "intermediate-cycle"),
            tool("indirect-cycle", "intermediate-cycle", "pending"),
            duplicate.copy(status = "done", streaming = true),
        ))

        val group = workflow.groups.single()
        assertNull(group.prompt)
        val rows = group.rows(emptySet())
        assertEquals(listOf("repeat", "missing-parent", "cycle-first", "cycle-second", "self-cycle", "indirect-cycle"), rows.map { it.entry.id })
        assertEquals(listOf(0, 0, 0, 1, 0, 0), rows.map { it.depth })
        assertEquals(AgentWorkflowState.COMPLETED, rows.first().state)
        assertEquals(AgentWorkflowCounts(6, 2, 1, 1, 1, 1), workflow.counts)
    }

    @Test fun phaseContainersAndNonHumanMessagesDoNotAddAgentsOrSplitTheTurn() {
        val prompt = Entry("prompt", kind = "user", messageOrigin = "human")
        val workflow = agentWorkflow(listOf(
            prompt,
            Entry("peer", kind = "user", messageOrigin = "peer"),
            Entry("legacy-peer", kind = "user", title = "From PC", text = "<cross-session-message from=\"worker\">status</cross-session-message>"),
            Entry("system", kind = "user", messageOrigin = "system"),
            tool("workflow", name = "Workflow"),
            tool("research-phase", "workflow", name = "WorkflowPhase"),
            tool("review-phase", "workflow", name = "WorkflowPhase"),
            tool("research", "research-phase", "done"),
            tool("review", "review-phase", "running", name = "Task"),
        ))

        val group = workflow.groups.single()
        val rows = group.rows(emptySet())
        assertEquals(prompt, group.prompt)
        assertEquals(listOf("workflow", "research-phase", "research", "review-phase", "review"), rows.map { it.entry.id })
        assertEquals(listOf(0, 1, 2, 1, 2), rows.map { it.depth })
        assertEquals(AgentWorkflowCounts(2, 1, 1, 0, 0, 0), workflow.counts)
    }

    @Test fun guidesContinueOnlyForBranchesWithLaterSiblings() {
        val group = agentWorkflow(listOf(
            tool("root", name = "Workflow"),
            tool("other-root"),
            tool("first", "root", name = "WorkflowPhase"),
            tool("last", "root", name = "WorkflowPhase"),
            tool("first-child", "first"),
            tool("last-child", "first"),
            tool("nested-child", "first-child"),
            tool("single-child", "last"),
        )).groups.single()
        val rows = group.rows(emptySet())
        assertEquals(
            listOf("root", "first", "first-child", "nested-child", "last-child", "last", "single-child", "other-root"),
            rows.map { it.entry.id },
        )
        assertEquals(listOf(true, true, true, false, false, false, false, false), rows.map { it.hasNextSibling })
        assertEquals(
            listOf(emptyList(), emptyList(), listOf(1), listOf(2, 1), listOf(1), emptyList(), emptyList(), emptyList()),
            rows.map { it.continuingAncestorDepths().toList() },
        )

        val folded = group.rows(setOf("first-child"))
        assertEquals(rows.filter { it.entry.id != "nested-child" }, folded)
        assertEquals(listOf(1), folded.first { it.entry.id == "last-child" }.continuingAncestorDepths().toList())
    }

    @Test fun workflowCanAppearBeforeAgentsAndTheTreeHandlesDeepChains() {
        val root = tool("workflow", status = "running", name = "Workflow")
        val pending = agentWorkflow(listOf(root))
        assertEquals(0, pending.counts.total)
        assertEquals(AgentWorkflowState.ACTIVE, pending.groups.single().rows(emptySet()).single().state)

        val depth = 2_000
        val nested = (1..depth).map { number ->
            val parent = if (number == 1) root.id else (number - 1).toString()
            tool(number.toString(), parent, "done")
        }
        val group = agentWorkflow(listOf(root) + nested).groups.single()
        assertEquals(depth, group.counts.completed)
        assertEquals(depth, group.rows(emptySet()).last().depth)
        assertEquals(emptyList<Int>(), group.rows(emptySet()).last().continuingAncestorDepths().toList())
        assertEquals(2, group.rows(setOf("1")).size)
    }

    private fun tool(id: String, parent: String? = null, status: String? = null, name: String = "Agent") =
        Entry(id, kind = "tool", tool = name, parent = parent, status = status)
}
