package com.unsupportedpastels.hermesandroid.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationControllerTest {
    @Test
    fun happyPathIdleToRecordingToTranscribingToIdle() {
        val controller = DictationController()
        assertEquals(DictationState.Idle, controller.state.value)

        assertTrue(controller.beginRecording())
        assertTrue(controller.state.value is DictationState.Recording)

        assertTrue(controller.finishRecording())
        assertEquals(DictationState.Transcribing, controller.state.value)

        controller.onTranscriptionComplete()
        assertEquals(DictationState.Idle, controller.state.value)
    }

    @Test
    fun cannotReachTranscribingWithoutRecording() {
        val controller = DictationController()
        assertFalse(controller.finishRecording())
        assertEquals(DictationState.Idle, controller.state.value)
    }

    @Test
    fun cannotBeginWhileAlreadyActive() {
        val controller = DictationController()
        assertTrue(controller.beginRecording())
        assertFalse(controller.beginRecording())
    }

    @Test
    fun levelAndElapsedOnlyApplyWhileRecording() {
        val controller = DictationController()
        controller.onAudioLevel(0.8f)
        controller.onElapsed(500L)
        assertEquals(DictationState.Idle, controller.state.value)

        controller.beginRecording()
        controller.onAudioLevel(2.0f) // coerced to 1.0
        controller.onElapsed(500L)
        val recording = controller.state.value as DictationState.Recording
        assertEquals(1.0f, recording.level, 0.0f)
        assertEquals(500L, recording.elapsedMillis)
    }

    @Test
    fun elapsedReportsWhenRecordingCapReached() {
        val controller = DictationController(maxRecordingMillis = 1_000L)
        controller.beginRecording()
        assertFalse(controller.onElapsed(999L))
        assertTrue(controller.onElapsed(1_000L))
    }

    @Test
    fun cancelFromAnyPhaseReturnsToIdle() {
        val controller = DictationController()
        controller.beginRecording()
        controller.cancel()
        assertEquals(DictationState.Idle, controller.state.value)

        controller.beginRecording()
        controller.finishRecording()
        controller.cancel()
        assertEquals(DictationState.Idle, controller.state.value)
    }

    @Test
    fun failureLatchesUntilDismissedAndAllowsRetry() {
        val controller = DictationController()
        controller.beginRecording()
        controller.fail(DictationFailure.PermissionDenied)
        assertEquals(DictationState.Failed(DictationFailure.PermissionDenied), controller.state.value)

        // A dismissed failure can immediately restart.
        assertTrue(controller.beginRecording())
    }

    @Test
    fun dismissErrorClearsToIdle() {
        val controller = DictationController()
        controller.fail(DictationFailure.TranscriptionFailed)
        controller.dismissError()
        assertEquals(DictationState.Idle, controller.state.value)
    }

    @Test
    fun defaultRecordingCapMatchesServerDefault() {
        // 120s from VoiceServerConfig.DEFAULT.maxRecordingSeconds.
        assertEquals(120_000L, DictationController().maxRecordingMillis)
    }
}
