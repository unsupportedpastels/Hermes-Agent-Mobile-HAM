package com.unsupportedpastels.hermesandroid.session

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin

const val MAX_SAVED_FILTERS_PER_SCOPE = 20
const val MAX_SAVED_FILTER_SCOPES = 64
const val MAX_SAVED_FILTER_NAME_CHARS = 64
const val MAX_SAVED_FILTER_QUERY_CHARS = 128
const val MAX_BULK_SELECTION = 500

/** The only predicates currently understood by the Home session list. */
data class SessionListFilter(
    val query: String = "",
    val pinnedOnly: Boolean = false,
    val archivedOnly: Boolean = false,
) {
    init {
        require(query.length <= MAX_SAVED_FILTER_QUERY_CHARS) {
            "Session filter query is too long"
        }
    }

    fun toSearchQuery(): String = buildList {
        query.trim().takeIf(String::isNotEmpty)?.let(::add)
        if (pinnedOnly) add("is:pinned")
        if (archivedOnly) add("is:archived")
    }.joinToString(" ")

    companion object {
        private val predicatePattern = Regex("(?i)\\bis:(pinned|archived)\\b")

        fun fromSearchQuery(value: String): SessionListFilter {
            val bounded = value.trim().take(MAX_SAVED_FILTER_QUERY_CHARS)
            val pinned = Regex("(?i)\\bis:pinned\\b").containsMatchIn(bounded)
            val archived = Regex("(?i)\\bis:archived\\b").containsMatchIn(bounded)
            val query = predicatePattern.replace(bounded, " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_SAVED_FILTER_QUERY_CHARS)
            return SessionListFilter(query, pinned, archived)
        }
    }
}

/** Local-only, content-free saved list filter. */
data class SavedSessionFilter(
    val name: String,
    val filter: SessionListFilter,
) {
    init {
        require(name.trim().isNotEmpty()) { "Saved filter name must not be blank" }
        require(name.length <= MAX_SAVED_FILTER_NAME_CHARS) {
            "Saved filter name is too long"
        }
    }

    val normalizedName: String
        get() = name.trim()
}

/** A saved-filter scope is never just an origin: profile is part of its identity. */
data class SessionFilterScope(
    val serverOrigin: ServerOrigin,
    val profile: String,
) {
    init {
        require(profile.isNotBlank() && profile == profile.trim()) {
            "Session filter profile is invalid"
        }
        require(profile.length <= 64) { "Session filter profile is too long" }
    }
}

data class BulkDeleteSelectionDecision(
    val selectedSessionIds: List<DurableSessionId>,
    val invalidSessionIds: Set<DurableSessionId> = emptySet(),
    val blockedSessionIds: Set<DurableSessionId> = emptySet(),
    val tooMany: Boolean = false,
) {
    val canDelete: Boolean
        get() = selectedSessionIds.isNotEmpty() &&
            invalidSessionIds.isEmpty() &&
            blockedSessionIds.isEmpty() &&
            !tooMany
}

fun toggleBulkSelection(
    selectedSessionIds: Set<DurableSessionId>,
    session: SessionSummary,
): Set<DurableSessionId> {
    if (session.isLocalDraft) return selectedSessionIds
    return if (session.id in selectedSessionIds) {
        selectedSessionIds - session.id
    } else if (selectedSessionIds.size < MAX_BULK_SELECTION) {
        selectedSessionIds + session.id
    } else {
        selectedSessionIds
    }
}

fun evaluateBulkDeleteSelection(
    selectedIds: Collection<DurableSessionId>,
    sessions: Collection<SessionSummary>,
    controllerRuntimeSessionIds: Set<DurableSessionId>,
    activeTurnSessionIds: Set<DurableSessionId>,
): BulkDeleteSelectionDecision {
    val distinctIds = selectedIds.distinct()
    val sessionById = sessions.associateBy(SessionSummary::id)
    val invalid = distinctIds.filterTo(linkedSetOf()) { id ->
        val session = sessionById[id]
        session == null || session.isLocalDraft
    }
    val blocked = distinctIds.filterTo(linkedSetOf()) { id ->
        id in controllerRuntimeSessionIds || id in activeTurnSessionIds
    }
    return BulkDeleteSelectionDecision(
        selectedSessionIds = distinctIds,
        invalidSessionIds = invalid,
        blockedSessionIds = blocked,
        tooMany = distinctIds.size > MAX_BULK_SELECTION,
    )
}
