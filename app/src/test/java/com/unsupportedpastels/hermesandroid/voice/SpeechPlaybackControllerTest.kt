package com.unsupportedpastels.hermesandroid.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPlaybackControllerTest {
    @Test
    fun startsIdle() {
        val controller = SpeechPlaybackController()
        assertEquals(SpeechPlaybackState.Idle, controller.state.value)
        assertNull(controller.activeKey)
    }

    @Test
    fun prepareThenPlayThenComplete() {
        val controller = SpeechPlaybackController()
        controller.beginPreparing("msg-1")
        assertEquals(SpeechPlaybackState.Preparing("msg-1"), controller.state.value)
        assertTrue(controller.isActiveFor("msg-1"))

        assertTrue(controller.markPlaying("msg-1"))
        assertTrue(controller.isPlaying("msg-1"))

        controller.complete()
        assertEquals(SpeechPlaybackState.Idle, controller.state.value)
    }

    @Test
    fun onlyOneTargetPlaysAtATime() {
        val controller = SpeechPlaybackController()
        controller.beginPreparing("msg-1")
        controller.markPlaying("msg-1")

        // A new target supersedes the old one.
        controller.beginPreparing("msg-2")
        assertEquals(SpeechPlaybackState.Preparing("msg-2"), controller.state.value)
        assertFalse(controller.isPlaying("msg-1"))

        // A late "playing" from the superseded target is ignored.
        assertFalse(controller.markPlaying("msg-1"))
        assertTrue(controller.markPlaying("msg-2"))
    }

    @Test
    fun stopReturnsToIdle() {
        val controller = SpeechPlaybackController()
        controller.beginPreparing("msg-1")
        controller.markPlaying("msg-1")
        controller.stop()
        assertEquals(SpeechPlaybackState.Idle, controller.state.value)
    }

    @Test
    fun staleFailureDoesNotClobberActiveTarget() {
        val controller = SpeechPlaybackController()
        controller.beginPreparing("msg-2")
        controller.fail("msg-1", SpeechPlaybackFailure.Playback)
        // The active target survives a stale failure.
        assertEquals(SpeechPlaybackState.Preparing("msg-2"), controller.state.value)
    }

    @Test
    fun failureLatchesAndDismisses() {
        val controller = SpeechPlaybackController()
        controller.beginPreparing("msg-1")
        controller.fail("msg-1", SpeechPlaybackFailure.Synthesis)
        assertEquals(
            SpeechPlaybackState.Failed("msg-1", SpeechPlaybackFailure.Synthesis),
            controller.state.value,
        )
        controller.dismissError()
        assertEquals(SpeechPlaybackState.Idle, controller.state.value)
    }
}
