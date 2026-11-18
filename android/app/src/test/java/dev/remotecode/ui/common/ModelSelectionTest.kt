package dev.remotecode.ui.common

import dev.remotecode.data.ModelOption
import org.junit.Assert.*
import org.junit.Test

class ModelSelectionTest {
    @Test fun resolvedModelIdsAndPcDefaultsUseTheSameCapabilitiesAsTheirAliases() {
        val defaults = ModelOption("default", "PC の既定", efforts = listOf("medium"), isDefault = true, supportsFastMode = false, resolvedModel = "claude-sonnet-5-5")
        val sonnet = ModelOption("sonnet", "Sonnet 5.5", efforts = listOf("high"), supportsFastMode = false, resolvedModel = "claude-sonnet-5-5")
        val opus = ModelOption("opus", "Opus 5.5", supportsFastMode = true, resolvedModel = "claude-opus-5-5")
        val options = listOf(defaults, sonnet, opus)
        assertEquals("sonnet", matchModelOption("claude-sonnet-5-5", options))
        assertEquals("sonnet", matchModelOption("sonnet", options))
        assertEquals(false, resolveModelOption("claude-sonnet-5-5", options)?.supportsFastMode)
        assertEquals(listOf("high"), resolveModelOption("claude-sonnet-5-5", options)?.efforts)
        assertEquals(defaults, resolveModelOption(null, options))
        assertEquals(defaults, resolveModelOption("default", options))
        assertEquals("opus", matchModelOption("claude-opus-5-5", options))
        assertEquals("opus", matchModelOption("opus[1m]", options))
        assertEquals("opus", matchModelOption("claude-opus-5-5", listOf(ModelOption("opus", "Opus 5.5"))))
        assertEquals("new-model", matchModelOption("new-model", options))
        assertNull(resolveModelOption("new-model", options))
    }
}
