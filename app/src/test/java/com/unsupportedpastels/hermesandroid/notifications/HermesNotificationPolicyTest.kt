package com.unsupportedpastels.hermesandroid.notifications

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesNotificationPolicyTest {
    @Test
    fun finalPreviewUsesFirstReadableLinesWithoutMarkdownNoise() {
        assertEquals(
            "Implemented concurrent sessions.\nAll checks pass.",
            finalResponsePreview(
                "# Result\n\n**Implemented** concurrent sessions.\n\nAll checks pass.\nThird line is hidden.",
                maxLines = 2,
            ),
        )
    }

    @Test
    fun finalPreviewIsBoundedAndHasAnEmptyFallback() {
        assertEquals("Response completed", finalResponsePreview("   \n "))
        assertEquals(240, finalResponsePreview("x".repeat(500)).length)
    }

    @Test
    fun activeTurnSummaryCountsConcurrentSessions() {
        assertEquals("Hermes is working", activeTurnTitle(1))
        assertEquals("Hermes is working in 3 sessions", activeTurnTitle(3))
    }

    @Test
    fun focusedVisibleSessionSuppressesItsOwnNotification() {
        assertFalse(
            shouldPostSessionNotification(
                sessionId = DurableSessionId("visible"),
                visibility = SessionNotificationVisibility(
                    appForeground = true,
                    windowFocused = true,
                    visibleSessionId = DurableSessionId("visible"),
                ),
            ),
        )
    }

    @Test
    fun notificationRemainsEnabledWhenAnotherSessionOrAppIsNotFocused() {
        val visible = DurableSessionId("visible")
        assertTrue(
            shouldPostSessionNotification(
                DurableSessionId("background"),
                SessionNotificationVisibility(true, true, visible),
            ),
        )
        assertTrue(
            shouldPostSessionNotification(
                visible,
                SessionNotificationVisibility(true, false, visible),
            ),
        )
        assertTrue(
            shouldPostSessionNotification(
                visible,
                SessionNotificationVisibility(false, true, visible),
            ),
        )
    }
}
