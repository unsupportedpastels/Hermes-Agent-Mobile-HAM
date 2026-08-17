package com.unsupportedpastels.hermesandroid.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoSpeakPolicyTest {
    private fun view(finalText: String?, finalIndex: Int, count: Int, running: Boolean = false) =
        VoiceReplyView(
            turnRunning = running,
            streamingText = null,
            streamingIndex = -1,
            finalAssistantText = finalText,
            finalAssistantIndex = finalIndex,
            messageCount = count,
        )

    @Test
    fun firstObservationTreatsEverythingAsHistory() {
        val policy = AutoSpeakPolicy()
        // Opening a session with an existing reply: never spoken.
        assertNull(policy.nextToSpeak(view("old reply", 11, 12)))
        // Unchanged state stays silent.
        assertNull(policy.nextToSpeak(view("old reply", 11, 12)))
    }

    @Test
    fun replyArrivingThroughIncrementalGrowthSpeaksOnce() {
        val policy = AutoSpeakPolicy()
        assertNull(policy.nextToSpeak(view("old reply", 1, 2)))
        // A turn adds user + assistant rows incrementally.
        assertNull(policy.nextToSpeak(view("old reply", 1, 4, running = true)))
        val target = policy.nextToSpeak(view("new reply", 3, 4))
        assertEquals(AutoSpeakTarget(3, "new reply"), target)
        policy.markSpoken(3)
        assertNull(policy.nextToSpeak(view("new reply", 3, 4)))
    }

    @Test
    fun listShrinkRemarksEverythingAsHistory() {
        // Regression: reopening a session resumes/replaces the transcript; a
        // transient short list must not make the reloaded reply look new.
        val policy = AutoSpeakPolicy()
        assertNull(policy.nextToSpeak(view("old reply", 11, 12)))
        // Resume transiently replaces the list with a short one…
        assertNull(policy.nextToSpeak(view(null, -1, 1)))
        // …then the full transcript returns. Still history.
        assertNull(policy.nextToSpeak(view("old reply", 11, 12)))
    }

    @Test
    fun bulkGrowthIsALoadNotNews() {
        val policy = AutoSpeakPolicy()
        assertNull(policy.nextToSpeak(view(null, -1, 0)))
        // Twelve rows at once = transcript load, not a new reply.
        assertNull(policy.nextToSpeak(view("old reply", 11, 12)))
    }

    @Test
    fun backlogCollapsesToNewestReply() {
        val policy = AutoSpeakPolicy()
        assertNull(policy.nextToSpeak(view(null, -1, 0)))
        assertNull(policy.nextToSpeak(view(null, -1, 2, running = true)))
        // Two replies finalized while a clip was playing; only the newest speaks.
        assertEquals(
            AutoSpeakTarget(3, "second"),
            policy.nextToSpeak(view("second", 3, 4)),
        )
        policy.markSpoken(3)
        // The older reply (index 1) can never be spoken afterwards.
        assertNull(policy.nextToSpeak(view("first", 1, 4)))
    }

    @Test
    fun blankOrMissingReplyIsSkipped() {
        val policy = AutoSpeakPolicy()
        assertNull(policy.nextToSpeak(view(null, -1, 0)))
        assertNull(policy.nextToSpeak(view(null, -1, 2)))
    }
}
