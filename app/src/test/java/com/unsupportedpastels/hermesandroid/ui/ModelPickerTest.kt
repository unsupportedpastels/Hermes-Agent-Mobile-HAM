package com.unsupportedpastels.hermesandroid.ui

import com.unsupportedpastels.hermesandroid.gateway.ModelCapabilities
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.ModelProviderOption
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelPickerTest {
    private val options = ModelOptions(
        current = ModelSelection("nous", "Hermes-4-405B"),
        providers = listOf(
            ModelProviderOption(
                slug = "nous",
                name = "Nous",
                models = listOf("Hermes-4-405B", "Hermes-4-70B"),
                capabilities = mapOf(
                    "Hermes-4-405B" to ModelCapabilities(reasoning = true, fast = false),
                    "Hermes-4-70B" to ModelCapabilities(fast = true),
                ),
            ),
            ModelProviderOption(
                slug = "openai",
                name = "OpenAI",
                models = listOf("gpt-5.6-sol"),
                capabilities = mapOf("gpt-5.6-sol" to ModelCapabilities(reasoning = true)),
            ),
        ),
        profile = "default",
    )

    @Test
    fun blankQueryKeepsEveryProviderAndModel() {
        val groups = modelProviderGroups(options, "")
        assertEquals(listOf("Nous", "OpenAI"), groups.map { it.name })
        assertEquals(2, groups.first().models.size)
    }

    @Test
    fun queryMatchesModelIdentifierWithinAProvider() {
        val groups = modelProviderGroups(options, "70b")
        assertEquals(1, groups.size)
        assertEquals("Nous", groups.first().name)
        assertEquals(
            listOf(ModelSelection("nous", "Hermes-4-70B")),
            groups.first().models.map { it.selection },
        )
    }

    @Test
    fun queryMatchingProviderNameKeepsAllOfItsModels() {
        val groups = modelProviderGroups(options, "openai")
        assertEquals(1, groups.size)
        assertEquals(listOf("gpt-5.6-sol"), groups.first().models.map { it.selection.model })
    }

    @Test
    fun recentsResolveToOptionsDropUnavailableAndDedupe() {
        val recents = listOf(
            ModelSelection("openai", "gpt-5.6-sol"),
            ModelSelection("openai", "gpt-5.6-sol"), // duplicate
            ModelSelection("nous", "retired-model"), // no longer offered
            ModelSelection("nous", "Hermes-4-70B"),
        )

        val resolved = recentModelOptions(recents, options)

        assertEquals(
            listOf(
                ModelSelection("openai", "gpt-5.6-sol"),
                ModelSelection("nous", "Hermes-4-70B"),
            ),
            resolved.map { it.selection },
        )
    }

    @Test
    fun capabilityLabelsListReasoningThenFast() {
        assertEquals(
            listOf("Reasoning", "Fast"),
            modelCapabilityLabels(ModelCapabilities(reasoning = true, fast = true)),
        )
        assertEquals(emptyList<String>(), modelCapabilityLabels(ModelCapabilities()))
    }

    @Test
    fun reasoningFilterKeepsOnlyReasoningModels() {
        val groups = modelProviderGroups(
            options,
            query = "",
            filters = setOf(ModelCapabilityFilter.Reasoning),
        )
        val kept = groups.flatMap { it.models.map { model -> model.selection } }
        assertEquals(
            listOf(
                ModelSelection("nous", "Hermes-4-405B"),
                ModelSelection("openai", "gpt-5.6-sol"),
            ),
            kept,
        )
    }

    @Test
    fun fastFilterKeepsOnlyFastModels() {
        val groups = modelProviderGroups(
            options,
            query = "",
            filters = setOf(ModelCapabilityFilter.Fast),
        )
        val kept = groups.flatMap { it.models.map { model -> model.selection } }
        assertEquals(listOf(ModelSelection("nous", "Hermes-4-70B")), kept)
    }

    @Test
    fun combinedFiltersRequireEveryCapability() {
        val groups = modelProviderGroups(
            options,
            query = "",
            filters = setOf(ModelCapabilityFilter.Reasoning, ModelCapabilityFilter.Fast),
        )
        // No model in the fixture is both reasoning and fast.
        assertEquals(emptyList<ModelProviderGroup>(), groups)
    }

    @Test
    fun filterCombinesWithTextQuery() {
        val groups = modelProviderGroups(
            options,
            query = "hermes",
            filters = setOf(ModelCapabilityFilter.Reasoning),
        )
        val kept = groups.flatMap { it.models.map { model -> model.selection } }
        assertEquals(listOf(ModelSelection("nous", "Hermes-4-405B")), kept)
    }

    @Test
    fun resolveReasoningEffortPrefersOverrideThenFallbackThenDefault() {
        assertEquals("high", resolveReasoningEffort("high", "low"))
        assertEquals("low", resolveReasoningEffort(null, "low"))
        assertEquals("low", resolveReasoningEffort("none", "low"))
        assertEquals("medium", resolveReasoningEffort(null, null))
        assertEquals("medium", resolveReasoningEffort("nonsense", null))
        assertEquals("medium", resolveReasoningEffort(null, "none"))
    }

    @Test
    fun thinkingEnabledUnlessNone() {
        assertEquals(true, isThinkingEnabled(null))
        assertEquals(true, isThinkingEnabled("high"))
        assertEquals(false, isThinkingEnabled("none"))
    }

    @Test
    fun reasoningEffortLabelsAreCompact() {
        assertEquals("Med", reasoningEffortShortLabel("medium"))
        assertEquals("XHigh", reasoningEffortShortLabel("xhigh"))
        assertEquals("Off", reasoningEffortShortLabel("none"))
    }
}
