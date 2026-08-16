package com.unsupportedpastels.hermesandroid.session

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.gateway.ActiveRuntimeSession
import com.unsupportedpastels.hermesandroid.gateway.RuntimeAccess
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOrganizationTest {
    @Test
    fun searchQueryRoundTripsSupportedPredicatesWithoutPersistingRawSyntax() {
        val filter = SessionListFilter.fromSearchQuery(" is:pinned lifecycle race is:archived ")

        assertEquals("lifecycle race", filter.query)
        assertTrue(filter.pinnedOnly)
        assertTrue(filter.archivedOnly)
        assertEquals("lifecycle race is:pinned is:archived", filter.toSearchQuery())
    }

    @Test
    fun selectionPolicyRejectsLocalMissingAndActiveWorkAndCapsAt500() {
        val durable = (0 until 501).map { SessionSummary(DurableSessionId("s-$it"), "Session $it") }
        val selected = durable.map { it.id }
        val decision = evaluateBulkDeleteSelection(
            selectedIds = selected,
            sessions = durable,
            controllerRuntimeSessionIds = setOf(durable[0].id),
            activeTurnSessionIds = setOf(durable[1].id),
        )

        assertFalse(decision.canDelete)
        assertTrue(decision.tooMany)
        assertEquals(setOf(durable[0].id, durable[1].id), decision.blockedSessionIds)
    }

    @Test
    fun selectionToggleNeverAddsLocalDraftOrMoreThan500VisibleRows() {
        val draft = SessionSummary(DurableSessionId("draft-1"), "Draft", isLocalDraft = true)
        val selected = (0 until MAX_BULK_SELECTION).mapTo(linkedSetOf()) {
            DurableSessionId("s-$it")
        }

        assertEquals(selected, toggleBulkSelection(selected, draft))
        assertEquals(selected, toggleBulkSelection(selected, SessionSummary(DurableSessionId("s-new"), "New")))
        assertEquals(
            selected - DurableSessionId("s-1"),
            toggleBulkSelection(selected, SessionSummary(DurableSessionId("s-1"), "One")),
        )
    }

    @Test
    fun scopeUsesCanonicalOriginAndSelectedProfile() {
        val first = SessionFilterScope(
            ServerOrigin.parse("HTTPS://Hermes.Example:443/"),
            "work",
        )
        val same = SessionFilterScope(ServerOrigin.parse("https://hermes.example"), "work")
        val otherProfile = SessionFilterScope(ServerOrigin.parse("https://hermes.example"), "default")

        assertEquals(first, same)
        assertFalse(first == otherProfile)
        assertEquals("https://hermes.example", first.serverOrigin.value)
    }
}
