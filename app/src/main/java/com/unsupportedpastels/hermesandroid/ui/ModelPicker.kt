package com.unsupportedpastels.hermesandroid.ui

import com.unsupportedpastels.hermesandroid.gateway.ModelCapabilities
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection

/** One selectable model with its provider label and advertised capabilities. */
internal data class ModelOption(
    val selection: ModelSelection,
    val providerName: String,
    val capabilities: ModelCapabilities = ModelCapabilities(),
)

/** A provider header plus its matching models, for the grouped picker list. */
internal data class ModelProviderGroup(
    val slug: String,
    val name: String,
    val models: List<ModelOption>,
)

/** Optional capability filters for the picker. A model must satisfy every active filter. */
internal enum class ModelCapabilityFilter { Reasoning, Fast }

/** Hermes' reasoning effort levels, ascending. `none` is thinking-off, owned by
 *  the Thinking toggle, so it is not part of this scale. Mirrors the desktop's
 *  REASONING_EFFORTS (lib/reasoning-effort.ts). */
internal val ReasoningEffortLevels = listOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra")

/** Built-in effort used when neither the model override nor the profile sets one. */
internal const val DefaultReasoningEffort = "medium"

/** Compact label for an effort level, for tight chip rows. */
internal fun reasoningEffortShortLabel(effort: String): String = when (effort.trim().lowercase()) {
    "none" -> "Off"
    "minimal" -> "Min"
    "low" -> "Low"
    "medium" -> "Med"
    "high" -> "High"
    "xhigh" -> "XHigh"
    "max" -> "Max"
    "ultra" -> "Ultra"
    else -> effort
}

/** True when thinking is enabled for the given stored effort (anything but `none`). */
internal fun isThinkingEnabled(effort: String?): Boolean =
    (effort?.trim()?.lowercase() ?: return true) != "none"

/**
 * The effort level a scale control should display for a model: its stored
 * override if it is a real level, else [fallback] (the profile default), else
 * [DefaultReasoningEffort]. `none`/blank resolve through the fallback because
 * thinking-off is represented separately by the Thinking toggle.
 */
internal fun resolveReasoningEffort(effort: String?, fallback: String?): String {
    val value = effort?.trim()?.lowercase().orEmpty()
    val resolved = value.takeUnless { it.isEmpty() || it == "none" }
        ?: fallback?.trim()?.lowercase()?.takeUnless { it.isEmpty() || it == "none" }
        ?: DefaultReasoningEffort
    return if (resolved in ReasoningEffortLevels) resolved else DefaultReasoningEffort
}

/**
 * Group the profile's providers into collapsible sections, keeping only the
 * providers with at least one model matching [query] and every filter in
 * [filters]. A blank query keeps every provider; an empty [filters] set applies
 * no capability constraint. Query matching is case-insensitive against both the
 * model identifier and the provider name, so searching a provider name surfaces
 * all of its models.
 */
internal fun modelProviderGroups(
    options: ModelOptions?,
    query: String,
    filters: Set<ModelCapabilityFilter> = emptySet(),
): List<ModelProviderGroup> {
    val trimmed = query.trim()
    return options?.providers.orEmpty().mapNotNull { provider ->
        val providerMatches = trimmed.isEmpty() ||
            provider.name.contains(trimmed, ignoreCase = true) ||
            provider.slug.contains(trimmed, ignoreCase = true)
        val models = provider.models
            .filter { model ->
                providerMatches || model.contains(trimmed, ignoreCase = true)
            }
            .map { model ->
                ModelOption(
                    selection = ModelSelection(provider.slug, model),
                    providerName = provider.name,
                    capabilities = provider.capabilities[model] ?: ModelCapabilities(),
                )
            }
            .filter { option -> option.capabilities.satisfies(filters) }
        if (models.isEmpty()) null else ModelProviderGroup(provider.slug, provider.name, models)
    }
}

/** True when these capabilities meet every requested filter. */
private fun ModelCapabilities.satisfies(filters: Set<ModelCapabilityFilter>): Boolean =
    filters.all { filter ->
        when (filter) {
            ModelCapabilityFilter.Reasoning -> reasoning == true
            ModelCapabilityFilter.Fast -> fast == true
        }
    }

/**
 * Resolve recently-used selections (most-recent first) into full options,
 * dropping any that are no longer offered by the current profile and any
 * duplicates. Used to pin the models the user actually switches between at the
 * top of the picker.
 */
internal fun recentModelOptions(
    recents: List<ModelSelection>,
    options: ModelOptions?,
): List<ModelOption> {
    val available = modelProviderGroups(options, "")
        .flatMap { group -> group.models }
        .associateBy { it.selection }
    val seen = LinkedHashSet<ModelSelection>()
    return recents.mapNotNull { selection ->
        if (!seen.add(selection)) return@mapNotNull null
        available[selection]
    }
}

/** Human-readable capability chips for a model, e.g. ["Reasoning", "Fast"]. */
internal fun modelCapabilityLabels(capabilities: ModelCapabilities): List<String> = buildList {
    if (capabilities.reasoning == true) add("Reasoning")
    if (capabilities.fast == true) add("Fast")
}
