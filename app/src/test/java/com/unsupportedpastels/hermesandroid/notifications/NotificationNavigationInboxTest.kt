package com.unsupportedpastels.hermesandroid.notifications

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotificationNavigationInboxTest {
    @Test
    fun repeatedSessionPublishesDistinctNavigationRequests() {
        val sessionId = DurableSessionId("session-1")

        val first = NotificationNavigationInbox.publish(sessionId)
        val second = NotificationNavigationInbox.publish(sessionId)

        assertEquals(sessionId, first.sessionId)
        assertEquals(sessionId, second.sessionId)
        assertNotEquals(first.requestId, second.requestId)
        assertEquals(second, NotificationNavigationInbox.requests.value)
    }
}