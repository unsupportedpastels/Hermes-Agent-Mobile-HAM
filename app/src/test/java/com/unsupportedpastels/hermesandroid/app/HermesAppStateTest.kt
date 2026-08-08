package com.unsupportedpastels.hermesandroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HermesAppStateTest {
    private val first = SessionSummary(DurableSessionId("stored-1"), "First session")
    private val second = SessionSummary(DurableSessionId("stored-2"), "Second session")

    @Test
    fun disconnectedStateStartsWithoutSelectionOrDraft() {
        val state = HermesAppState(sessions = listOf(first, second))

        assertNull(state.selectedSessionId)
        assertEquals("", state.draftFor(first.id))
    }

    @Test
    fun selectingKnownSessionPreservesDraftsPerDurableSession() {
        val state = HermesAppState(sessions = listOf(first, second))
            .selectSession(first.id)
            .updateSelectedDraft("message for first")
            .selectSession(second.id)
            .updateSelectedDraft("message for second")
            .selectSession(first.id)

        assertEquals(first.id, state.selectedSessionId)
        assertEquals("message for first", state.selectedDraft)
        assertEquals("message for second", state.draftFor(second.id))
    }

    @Test(expected = IllegalArgumentException::class)
    fun selectingUnknownSessionFailsClosed() {
        HermesAppState(sessions = listOf(first)).selectSession(DurableSessionId("missing"))
    }

    @Test(expected = IllegalStateException::class)
    fun updatingDraftWithoutSelectionFailsClosed() {
        HermesAppState(sessions = listOf(first)).updateSelectedDraft("must not leak")
    }

    @Test
    fun reconcilingSessionsPreservesKnownSelectionAndDraft() {
        val selected = HermesAppState(sessions = listOf(first, second))
            .selectSession(first.id)
            .updateSelectedDraft("Keep me")

        val reconciled = selected.reconcileSessions(listOf(first))

        assertEquals(first.id, reconciled.selectedSessionId)
        assertEquals("Keep me", reconciled.selectedDraft)
    }

    @Test
    fun reconcilingRemovedSelectionClearsSelectionAndOrphanedDraft() {
        val selected = HermesAppState(sessions = listOf(first, second))
            .selectSession(first.id)
            .updateSelectedDraft("Remove me")

        val reconciled = selected.reconcileSessions(listOf(second))

        assertNull(reconciled.selectedSessionId)
        assertEquals("", reconciled.draftFor(first.id))
    }
}
