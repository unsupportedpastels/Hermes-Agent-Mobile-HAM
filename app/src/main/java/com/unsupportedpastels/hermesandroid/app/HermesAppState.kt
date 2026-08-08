package com.unsupportedpastels.hermesandroid.app

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class DurableSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Durable session ID must not be blank" }
    }
}

data class SessionSummary(
    val id: DurableSessionId,
    val title: String,
)

data class HermesAppState(
    val sessions: List<SessionSummary> = emptyList(),
    val selectedSessionId: DurableSessionId? = null,
    private val drafts: Map<DurableSessionId, String> = emptyMap(),
) {
    init {
        require(selectedSessionId == null || sessions.any { it.id == selectedSessionId }) {
            "Selected session must exist in the durable session list"
        }
    }

    val selectedDraft: String
        get() = selectedSessionId?.let(::draftFor).orEmpty()

    fun draftFor(sessionId: DurableSessionId): String = drafts[sessionId].orEmpty()

    fun selectSession(sessionId: DurableSessionId): HermesAppState {
        require(sessions.any { it.id == sessionId }) {
            "Cannot select an unknown durable session"
        }
        return copy(selectedSessionId = sessionId)
    }

    fun updateSelectedDraft(draft: String): HermesAppState {
        val sessionId = checkNotNull(selectedSessionId) {
            "Cannot update a composer draft without a selected durable session"
        }
        return copy(drafts = drafts + (sessionId to draft))
    }

    fun reconcileSessions(updatedSessions: List<SessionSummary>): HermesAppState {
        val currentIds = updatedSessions.mapTo(mutableSetOf()) { it.id }
        return copy(
            sessions = updatedSessions,
            selectedSessionId = selectedSessionId?.takeIf(currentIds::contains),
            drafts = drafts.filterKeys(currentIds::contains),
        )
    }
}
