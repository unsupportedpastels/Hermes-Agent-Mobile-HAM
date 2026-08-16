package com.unsupportedpastels.hermesandroid.gateway

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

const val MAX_OPERATIONAL_COMPONENTS = 32
private const val MAX_OPERATIONAL_TEXT = 128
private const val MAX_OPERATIONAL_PROFILE = 64

/** Coarse health reported by Hermes Serve; Unknown is never rendered as healthy. */
enum class OperationalHealth {
    Ok,
    Degraded,
    Unknown,
}

enum class OperationalPressure {
    Ok,
    Warning,
    Critical,
    Unknown,
}

data class OperationalComponentStatus(
    val name: String,
    val health: OperationalHealth,
    val state: String? = null,
)

data class OperationalStatus(
    val profile: String,
    val version: String? = null,
    val overall: OperationalHealth = OperationalHealth.Unknown,
    val components: List<OperationalComponentStatus> = emptyList(),
    val memoryPressure: OperationalPressure = OperationalPressure.Unknown,
    val diskPressure: OperationalPressure = OperationalPressure.Unknown,
)

data class OperationalSnapshot(
    val origin: String,
    val profile: String,
    val status: OperationalStatus,
    val fetchedAtEpochSeconds: Long,
)

/** Snapshot state keeps the last good payload available during a transient failure. */
sealed interface OperationalStatusState {
    data object Unavailable : OperationalStatusState
    data class Loading(val lastGood: OperationalSnapshot? = null) : OperationalStatusState
    data class Ready(val snapshot: OperationalSnapshot) : OperationalStatusState
    data class TransientError(
        val lastGood: OperationalSnapshot?,
        val message: String = "Operational status temporarily unavailable",
    ) : OperationalStatusState
}

fun OperationalStatusState.lastGoodOrNull(): OperationalSnapshot? = when (this) {
    OperationalStatusState.Unavailable -> null
    is OperationalStatusState.Loading -> lastGood
    is OperationalStatusState.Ready -> snapshot
    is OperationalStatusState.TransientError -> lastGood
}

/** Parse only the bounded public `/api/status` operational subset. */
fun parseOperationalStatus(result: JsonObject, profile: String): OperationalStatus {
    val boundedProfile = profile.trim().take(MAX_OPERATIONAL_PROFILE).ifEmpty { "default" }
    val components = parseComponents(result["components"])
    return OperationalStatus(
        profile = boundedProfile,
        version = scalarText(result["version"]),
        overall = health(result["overall"]),
        components = components,
        memoryPressure = pressure(result["memory"] ?: result["memory_pressure"]),
        diskPressure = pressure(result["disk"] ?: result["disk_pressure"]),
    )
}

private fun parseComponents(element: JsonElement?): List<OperationalComponentStatus> = when (element) {
    is JsonObject -> element.entries.take(MAX_OPERATIONAL_COMPONENTS).mapNotNull { (rawName, value) ->
        val name = rawName.trim().take(MAX_OPERATIONAL_TEXT).takeIf(String::isNotBlank) ?: return@mapNotNull null
        val row = value as? JsonObject
        OperationalComponentStatus(
            name = name,
            health = health(row?.get("status") ?: value),
            state = scalarText(row?.get("state")),
        )
    }
    is JsonArray -> element.take(MAX_OPERATIONAL_COMPONENTS).mapNotNull { value ->
        val row = value as? JsonObject ?: return@mapNotNull null
        val name = scalarText(row["name"]) ?: return@mapNotNull null
        OperationalComponentStatus(
            name = name,
            health = health(row["status"]),
            state = scalarText(row["state"]),
        )
    }
    else -> emptyList()
}

private fun health(element: JsonElement?): OperationalHealth {
    val value = scalarText(element)?.lowercase() ?: return OperationalHealth.Unknown
    return when (value) {
        "ok", "healthy", "ready", "running" -> OperationalHealth.Ok
        "degraded", "warning", "critical", "error", "failed", "unhealthy" -> OperationalHealth.Degraded
        else -> OperationalHealth.Unknown
    }
}

private fun pressure(element: JsonElement?): OperationalPressure {
    val value = when (element) {
        is JsonObject -> scalarText(element["pressure"] ?: element["status"])
        else -> scalarText(element)
    }?.lowercase() ?: return OperationalPressure.Unknown
    return when (value) {
        "ok", "healthy" -> OperationalPressure.Ok
        "warning", "elevated", "degraded" -> OperationalPressure.Warning
        "critical", "full" -> OperationalPressure.Critical
        else -> OperationalPressure.Unknown
    }
}

private fun scalarText(element: JsonElement?): String? =
    (element as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.take(MAX_OPERATIONAL_TEXT)

private val completedCronStatuses = setOf("completed", "success", "succeeded", "ok")
private val terminalCronStatuses = completedCronStatuses + setOf(
    "failed", "failure", "error", "timeout", "timed_out", "cancelled", "canceled", "skipped", "partial",
)

/** Delivery failures remain attention-worthy even when execution completed. */
fun CronJob.requiresOperationalAttention(): Boolean {
    if (!lastDeliveryError.isNullOrBlank()) return true
    val normalized = lastStatus?.trim()?.lowercase() ?: return false
    return normalized in terminalCronStatuses && normalized !in completedCronStatuses
}
