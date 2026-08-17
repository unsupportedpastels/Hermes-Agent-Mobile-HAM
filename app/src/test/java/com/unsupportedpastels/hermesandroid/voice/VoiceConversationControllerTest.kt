package com.unsupportedpastels.hermesandroid.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceConversationControllerTest {
    private fun controller(stopPhrases: List<String> = listOf("stop")) =
        VoiceConversationController { stopPhrases }

    @Test
    fun startEntersListeningAndBumpsGeneration() {
        val c = controller()
        val before = c.generation
        assertTrue(c.start())
        assertTrue(c.state.value is VoiceConversationState.Listening)
        assertTrue(c.generation > before)
        // Double-start is rejected.
        assertFalse(c.start())
    }

    @Test
    fun fullTurnCycle() {
        val c = controller()
        c.start()
        assertTrue(c.onUtteranceCaptured())
        assertEquals(VoiceConversationState.Transcribing, c.state.value)
        assertEquals(TranscriptDisposition.Submit, c.onTranscript("what's the weather"))
        assertEquals(VoiceConversationState.Thinking, c.state.value)
        assertTrue(c.onSpeechStarted())
        assertEquals(VoiceConversationState.Speaking, c.state.value)
        c.onSpeechFinished()
        assertTrue(c.state.value is VoiceConversationState.Listening)
    }

    @Test
    fun emptyTranscriptRearmsWithoutSubmit() {
        val c = controller()
        c.start()
        c.onUtteranceCaptured()
        assertEquals(TranscriptDisposition.Rearm, c.onTranscript("   "))
        assertTrue(c.state.value is VoiceConversationState.Listening)
    }

    @Test
    fun wholeUtteranceStopEndsWithoutSubmit() {
        val c = controller()
        c.start()
        c.onUtteranceCaptured()
        assertEquals(TranscriptDisposition.EndConversation, c.onTranscript("Stop."))
        assertEquals(VoiceConversationState.Idle, c.state.value)
    }

    @Test
    fun substantiveSentenceContainingStopSubmits() {
        val c = controller()
        c.start()
        c.onUtteranceCaptured()
        assertEquals(TranscriptDisposition.Submit, c.onTranscript("stop the docker container"))
    }

    @Test
    fun transcriptionFailureShowsNoticeAndRearms() {
        val c = controller()
        c.start()
        c.onUtteranceCaptured()
        c.onTranscriptionFailed()
        assertEquals(VoiceConversationNotice.TranscriptionFailed, c.notice.value)
        assertTrue(c.state.value is VoiceConversationState.Listening)
        // The notice clears on the next successful transcript.
        c.onUtteranceCaptured()
        c.onTranscript("hello")
        assertNull(c.notice.value)
    }

    @Test
    fun toolOnlyTurnRearmsWithoutSpeech() {
        val c = controller()
        c.start()
        c.onUtteranceCaptured()
        c.onTranscript("run the tests")
        c.onTurnCompleteWithoutSpeech()
        assertTrue(c.state.value is VoiceConversationState.Listening)
    }

    @Test
    fun speechFailureShowsNoticeAndRearms() {
        val c = controller()
        c.start()
        c.onUtteranceCaptured()
        c.onTranscript("hi")
        c.onSpeechFailed()
        assertEquals(VoiceConversationNotice.SpeechFailed, c.notice.value)
        assertTrue(c.state.value is VoiceConversationState.Listening)
    }

    @Test
    fun bargeInDuringSpeakingReturnsToListening() {
        val c = controller()
        c.start()
        c.onUtteranceCaptured()
        c.onTranscript("hi")
        c.onSpeechStarted()
        assertTrue(c.onBargeIn())
        assertTrue(c.state.value is VoiceConversationState.Listening)
    }

    @Test
    fun bargeInWhileListeningIsRejected() {
        val c = controller()
        c.start()
        assertFalse(c.onBargeIn())
    }

    @Test
    fun endFromAnyPhaseGoesIdleAndInvalidatesGeneration() {
        val c = controller()
        c.start()
        c.onUtteranceCaptured()
        c.onTranscript("hello")
        val generationBefore = c.generation
        c.end()
        assertEquals(VoiceConversationState.Idle, c.state.value)
        assertTrue(c.generation > generationBefore)
        // Stale engine callbacks after end are ignored.
        assertFalse(c.onUtteranceCaptured())
        assertFalse(c.onSpeechStarted())
        c.onSpeechFinished()
        assertEquals(VoiceConversationState.Idle, c.state.value)
    }

    @Test
    fun staleTranscriptAfterEndDoesNotSubmit() {
        val c = controller()
        c.start()
        c.onUtteranceCaptured()
        c.end()
        assertEquals(TranscriptDisposition.Rearm, c.onTranscript("late transcript"))
        assertEquals(VoiceConversationState.Idle, c.state.value)
    }

    @Test
    fun muteTogglesOnlyWhileActive() {
        val c = controller()
        c.setMuted(true)
        assertFalse(c.muted.value)
        c.start()
        c.setMuted(true)
        assertTrue(c.muted.value)
        c.end()
        assertFalse(c.muted.value)
    }
}
