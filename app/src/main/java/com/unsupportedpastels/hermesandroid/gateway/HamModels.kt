package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val MAX_HAM_TEXT = 4096
private const val MAX_HAM_ROWS = 128
private const val MAX_HAM_CATEGORIES = 64
private const val MAX_HAM_FIELD = 512

/** Bounded result for an explicit active-turn steer. */
data class SessionSteerResult(val status: String, val text: String?)

data class SessionUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val contextUsedTokens: Long? = null,
    val contextMaxTokens: Long? = null,
    val contextPercent: Double? = null,
    val calls: Long? = null,
    val creditsLines: List<String> = emptyList(),
    val rawInfo: String? = null,
)

data class ContextBreakdownCategory(
    val name: String,
    val tokens: Long? = null,
    val percent: Double? = null,
)

data class SessionContextBreakdown(
    val categories: List<ContextBreakdownCategory> = emptyList(),
    val usedTokens: Long? = null,
    val maxTokens: Long? = null,
    val percent: Double? = null,
)

data class SessionCompressResult(
    val status: String? = null,
    val aborted: Boolean = false,
    val messages: List<JsonObject> = emptyList(),
    val info: String? = null,
    val usage: SessionUsage? = null,
)

data class SessionUndoResult(val removed: Int)

data class SessionBranchResult(
    val runtimeSessionId: RuntimeSessionId?,
    val durableSessionId: DurableSessionId,
    val title: String?,
    val messages: List<JsonObject> = emptyList(),
)

data class DelegationPauseResult(val paused: Boolean)
data class SubagentInterruptResult(val found: Boolean, val subagentId: String?)
data class SubagentSteerResult(val status: String, val text: String?)

data class ScheduledJob(
    val jobId: String,
    val name: String,
    val schedule: String,
    val enabled: Boolean? = null,
    val state: String? = null,
    val nextRunAt: String? = null,
    val lastRunAt: String? = null,
    val lastStatus: String? = null,
)

sealed interface ScheduledJobsState {
    data object Idle : ScheduledJobsState
    data object Loading : ScheduledJobsState
    data class Ready(val jobs: List<ScheduledJob>) : ScheduledJobsState
    data object Unsupported : ScheduledJobsState
    data class Error(val message: String) : ScheduledJobsState
}

/** Parse only the bounded, display-safe subset of `cron.manage list`. */
fun parseScheduledJobs(result: JsonObject): List<ScheduledJob> =
    (result["jobs"] as? JsonArray)
        .orEmpty()
        .take(MAX_HAM_ROWS)
        .mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val id = row.text("job_id", MAX_HAM_FIELD) ?: row.text("id", MAX_HAM_FIELD) ?: return@mapNotNull null
            val name = row.text("name", MAX_HAM_FIELD) ?: return@mapNotNull null
            val schedule = row.text("schedule", MAX_HAM_FIELD) ?: return@mapNotNull null
            ScheduledJob(
                jobId = id,
                name = name,
                schedule = schedule,
                enabled = row["enabled"].asBoolean(),
                state = row.text("state", MAX_HAM_FIELD),
                nextRunAt = row.scalarText("next_run_at"),
                lastRunAt = row.scalarText("last_run_at"),
                lastStatus = row.text("last_status", MAX_HAM_FIELD),
            )
        }
        .distinctBy(ScheduledJob::jobId)

internal fun parseSessionUsage(result: JsonObject): SessionUsage = SessionUsage(
    inputTokens = result.long("input_tokens", "input", "prompt_tokens"),
    outputTokens = result.long("output_tokens", "output", "completion_tokens"),
    totalTokens = result.long("total_tokens", "total"),
    contextUsedTokens = result.long("context_used_tokens", "context_used", "used_tokens"),
    contextMaxTokens = result.long("context_max_tokens", "context_max", "max_tokens"),
    contextPercent = result.number("context_percent", "context_percentage", "percent"),
    calls = result.long("calls", "request_count", "requests"),
    creditsLines = (result["credits_lines"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.take(MAX_HAM_FIELD) }
        .take(MAX_HAM_ROWS),
    rawInfo = result.text("info", MAX_HAM_FIELD),
)

internal fun parseContextBreakdown(result: JsonObject): SessionContextBreakdown {
    val rows = (result["categories"] as? JsonArray)
        ?: (result["breakdown"] as? JsonArray)
        ?: JsonArray(emptyList())
    val categories = rows.take(MAX_HAM_CATEGORIES).mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val name = row.text("name", MAX_HAM_FIELD)
            ?: row.text("category", MAX_HAM_FIELD)
            ?: row.text("label", MAX_HAM_FIELD)
            ?: return@mapNotNull null
        ContextBreakdownCategory(
            name = name,
            tokens = row.long("tokens", "token_count", "count"),
            percent = row.number("percent", "percentage"),
        )
    }.distinctBy(ContextBreakdownCategory::name)
    return SessionContextBreakdown(
        categories = categories,
        usedTokens = result.long("used_tokens", "context_used_tokens", "context_used"),
        maxTokens = result.long("max_tokens", "context_max_tokens", "context_max"),
        percent = result.number("percent", "context_percent"),
    )
}

internal fun parseCompressResult(result: JsonObject): SessionCompressResult {
    val messages = (result["messages"] as? JsonArray)
        .orEmpty().take(MAX_HAM_ROWS).mapNotNull { it as? JsonObject }
    return SessionCompressResult(
        status = result.text("status", MAX_HAM_FIELD),
        aborted = result["aborted"].asBoolean() == true ||
            result.text("status", MAX_HAM_FIELD)?.lowercase() in setOf("aborted", "cancelled", "canceled"),
        messages = messages,
        info = result.text("info", MAX_HAM_FIELD),
        usage = (result["usage"] as? JsonObject)?.let(::parseSessionUsage),
    )
}

internal fun parseBranchResult(result: JsonObject): SessionBranchResult {
    val durable = result.text("stored_session_id", MAX_HAM_FIELD)
        ?: result.text("durable_session_id", MAX_HAM_FIELD)
        ?: throw HermesChatProtocolException("Branch response was incomplete")
    return SessionBranchResult(
        runtimeSessionId = result.text("session_id", MAX_HAM_FIELD)?.let { value ->
            runCatching { RuntimeSessionId(value) }.getOrNull()
        },
        durableSessionId = DurableSessionId(durable),
        title = result.text("title", MAX_HAM_FIELD),
        messages = (result["messages"] as? JsonArray).orEmpty().take(MAX_HAM_ROWS).mapNotNull { it as? JsonObject },
    )
}

private fun JsonElement?.asBoolean(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.text(key: String, max: Int): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.take(max)
private fun JsonObject.scalarText(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.take(MAX_HAM_FIELD)
private fun JsonObject.long(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0)
}
private fun JsonObject.number(vararg keys: String): Double? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
}
