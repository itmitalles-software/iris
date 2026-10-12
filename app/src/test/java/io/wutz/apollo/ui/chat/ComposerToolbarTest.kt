package io.wutz.apollo.ui.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [composerModelLabel] — the label shown in the composer's
 * model/reasoning pill.
 * By default ([showProvider] = false), only the model name is shown.
 * When [showProvider] = true, the provider is kept (with "custom:" stripped).
 */
class ComposerToolbarTest {
    @Test
    fun composerModelLabel_defaultsToModelOnly() {
        assertEquals("gpt-5.5", composerModelLabel("openai/gpt-5.5"))
        assertEquals("gpt-5.5", composerModelLabel("copilot/gpt-5.5"))
        assertEquals("glm-5.3", composerModelLabel("custom:my-provider/glm-5.3"))
        assertEquals("gpt-5.5", composerModelLabel("openrouter/openai/gpt-5.5"))
        assertEquals("gpt-5", composerModelLabel("openrouter/openai/gpt-5"))
        assertEquals("llama3:8b", composerModelLabel("ollama/llama3:8b"))
        assertEquals("llama3:8b", composerModelLabel("llama3:8b"))
        assertEquals("gpt-5", composerModelLabel("gpt-5"))
        assertEquals("custom:/glm-5.3", composerModelLabel("custom:/glm-5.3"))
        assertEquals("openai/", composerModelLabel("openai/"))
        assertEquals("/gpt-5", composerModelLabel("/gpt-5"))
    }

    @Test
    fun composerModelLabel_keepsProviderWhenEnabled() {
        assertEquals("openai/gpt-5.5", composerModelLabel("openai/gpt-5.5", showProvider = true))
        assertEquals("copilot/gpt-5.5", composerModelLabel("copilot/gpt-5.5", showProvider = true))
        assertEquals("my-provider/glm-5.3", composerModelLabel("custom:my-provider/glm-5.3", showProvider = true))
        assertEquals("openrouter/openai/gpt-5.5", composerModelLabel("openrouter/openai/gpt-5.5", showProvider = true))
        assertEquals("ollama/llama3:8b", composerModelLabel("ollama/llama3:8b", showProvider = true))
        assertEquals("llama3:8b", composerModelLabel("llama3:8b", showProvider = true))
        assertEquals("gpt-5", composerModelLabel("gpt-5", showProvider = true))
        assertEquals("custom:/glm-5.3", composerModelLabel("custom:/glm-5.3", showProvider = true))
        assertEquals("openai/", composerModelLabel("openai/", showProvider = true))
        assertEquals("/gpt-5", composerModelLabel("/gpt-5", showProvider = true))
    }

    @Test
    fun buildReasoningLabel_formatsRequestedAndClampedWire() {
        assertEquals("Default", buildReasoningLabel(null, defaultLabel = "Default"))
        assertEquals("الافتراضي", buildReasoningLabel(null, defaultLabel = "الافتراضي"))
        assertEquals("None", buildReasoningLabel("none"))
        assertEquals("High", buildReasoningLabel("high"))
        assertEquals("Ultra", buildReasoningLabel("ultra"))
        assertEquals("Ultra→Max", buildReasoningLabel("ultra", wireLevel = "max"))
        assertEquals("High", buildReasoningLabel("high", wireLevel = "high"))
        assertEquals("None", buildReasoningLabel("none", wireLevel = "low"))
    }

    @Test
    fun buildReasoningLabel_usesLevelResolverForKnownLevels() {
        val german = mapOf("none" to "Aus", "medium" to "Mittel", "high" to "Hoch", "max" to "Max")
        val resolve: (String) -> String = { german[it] ?: "?$it" }
        assertEquals("Hoch", buildReasoningLabel("high", levelLabel = resolve))
        assertEquals("Aus", buildReasoningLabel("none", levelLabel = resolve))
        assertEquals("Hoch→Mittel", buildReasoningLabel("high", wireLevel = "medium", levelLabel = resolve))
        // Unknown levels stay raw and never reach the resolver.
        assertEquals("turbo", buildReasoningLabel("turbo", levelLabel = { error(it) }))
        assertEquals("Hoch→turbo", buildReasoningLabel("high", wireLevel = "turbo", levelLabel = resolve))
        assertEquals("Standard", buildReasoningLabel(null, defaultLabel = "Standard", levelLabel = { error(it) }))
    }
}
