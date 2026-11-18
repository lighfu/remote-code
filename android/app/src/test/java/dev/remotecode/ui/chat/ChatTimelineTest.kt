package dev.remotecode.ui.chat

import dev.remotecode.data.Entry
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTimelineTest {
    @Test fun filtersBlankRowsBeforeLayoutAndKeepsVisibleRepliesAndApprovals() {
        val rows = listOf(
            Entry(id = "user", kind = "user", text = "Prompt"),
            Entry(id = "review", kind = "tool", tool = "automaticApprovalReview", status = "done"),
            Entry(id = "empty-thinking", kind = "thinking", text = " "),
            Entry(id = "empty-assistant", kind = "assistant"),
            Entry(id = "stream", kind = "thinking", streaming = true),
            Entry(id = "thinking", kind = "thinking", text = "Thought"),
            Entry(id = "approval", kind = "approval", status = "pending"),
            Entry(id = "reply", kind = "assistant", text = "Reply"),
            Entry(id = "image", kind = "assistant", media = listOf("asset")),
        )

        assertEquals(listOf("user", "review", "stream", "thinking", "approval", "reply", "image"), chatTimeline(rows, "full").entries.map { it.id })
        assertEquals(listOf("user", "review", "approval", "reply", "image"), chatTimeline(rows, "off").entries.map { it.id })
    }

    @Test fun onlyGroupsChildrenThatHaveAnAgentCardInTheTimeline() {
        val rows = listOf(
            Entry(id = "missing-parent", kind = "assistant", text = "Reply", parent = "omitted-agent"),
            Entry(id = "agent", kind = "tool", tool = "Agent"),
            Entry(id = "parented", kind = "assistant", text = "Agent reply", parent = "agent"),
            Entry(id = "hidden-child", kind = "thinking", parent = "agent"),
            Entry(id = "tool", kind = "tool", tool = "Read"),
            Entry(id = "not-an-agent", kind = "user", text = "Follow-up", parent = "tool"),
        )

        val timeline = chatTimeline(rows, "full")
        assertEquals(listOf("missing-parent", "agent", "tool", "not-an-agent"), timeline.entries.map { it.id })
        assertEquals(mapOf("agent" to listOf(rows[2])), timeline.children)
    }
}
