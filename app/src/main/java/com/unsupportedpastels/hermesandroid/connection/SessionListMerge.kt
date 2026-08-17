package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary

/**
 * Replaces the durable session list with a server-fetched list while keeping
 * local draft sessions ("New chat" entries that only exist on this client)
 * visible. Without this, a background session-list refresh that lands while a
 * draft is open removes the draft from the snapshot and the open detail route
 * degrades to "Session is no longer available".
 *
 * Drafts are kept only while they are still pending (not yet promoted to a
 * server session) and not already represented in the server list.
 */
internal fun mergeServerSessionsPreservingDrafts(
    serverSessions: List<SessionSummary>,
    currentSessions: List<SessionSummary>,
    pendingDrafts: Set<DurableSessionId>,
): List<SessionSummary> {
    if (pendingDrafts.isEmpty()) return serverSessions
    val serverIds = serverSessions.mapTo(mutableSetOf(), SessionSummary::id)
    val preservedDrafts = currentSessions.filter { session ->
        session.isLocalDraft && session.id in pendingDrafts && session.id !in serverIds
    }
    if (preservedDrafts.isEmpty()) return serverSessions
    return preservedDrafts + serverSessions
}
