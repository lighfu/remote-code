package dev.remotecode.ui.chat

import dev.remotecode.data.Entry
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCardInputTest {
    @Test fun commandPreviewBecomesOneCompleteInputWhenOpened() {
        val command = "Get-Content settings.gradle.kts\n" + "git log --oneline\n".repeat(10)
        for (tool in listOf("command", "PowerShell", "Bash")) {
            val entry = Entry(id = "tool", kind = "tool", tool = tool, title = command, text = "Inspect history", detail = command)
            assertEquals(ToolCardInput(command, "Inspect history", null), toolCardInput(entry, false))
            assertEquals(ToolCardInput(tool, "Inspect history", command), toolCardInput(entry, true))
            assertEquals(ToolCardInput(tool, "Inspect history", command), toolCardInput(entry.copy(detail = null), true))
        }
    }

    @Test fun truncatedPreviewsAreReplacedOnlyWhenTheFullInputMatches() {
        val entry = Entry(id = "tool", kind = "tool", tool = "command", title = "git log -6…", detail = "git log -6 --oneline")
        assertEquals(ToolCardInput("command", null, entry.detail), toolCardInput(entry, true))
        val different = entry.copy(title = "git status…")
        assertEquals(ToolCardInput(different.title, null, entry.detail), toolCardInput(different, true))
    }

    @Test fun genericToolKeepsDistinctArgumentsAndDropsOnlyVisibleDuplicates() {
        val entry = Entry(id = "tool", kind = "tool", tool = "Search", title = "Find files", text = "Find files", detail = "*.kt")
        assertEquals(ToolCardInput("Find files", null, "*.kt"), toolCardInput(entry, true))
        val repeatedInput = entry.copy(text = "*.kt")
        assertEquals(ToolCardInput("Find files", "*.kt", null), toolCardInput(repeatedInput, false))
        assertEquals(ToolCardInput("Find files", null, "*.kt"), toolCardInput(repeatedInput, true))
        val allEqual = entry.copy(title = "Search", text = "Search", detail = "Search")
        assertEquals(ToolCardInput(null, null, "Search"), toolCardInput(allEqual, true))
    }

    @Test fun editingAFileKeepsItsNameEvenWhenTheContentsMatch() {
        val entry = Entry(id = "edit", kind = "tool", tool = "Write", title = "notes", detail = "notes")
        assertEquals(ToolCardInput("notes", null, null), toolCardInput(entry, true))
    }
}
