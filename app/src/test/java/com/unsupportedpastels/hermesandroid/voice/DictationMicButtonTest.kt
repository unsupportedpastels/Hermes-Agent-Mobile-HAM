package com.unsupportedpastels.hermesandroid.voice

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DictationMicButtonTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class FakeRecorder(
        private val recording: DictationRecording? =
            DictationRecording("data:audio/mp4;base64,AAAA", "audio/mp4"),
    ) : DictationRecorder {
        override val mimeType = "audio/mp4"
        var cancelled = false
        override fun start() = true
        override fun sampleAmplitude() = 10_000 // stays above the silence threshold
        override fun sampleLevel() = 0.3f
        override fun stopAndEncode() = recording
        override fun cancel() { cancelled = true }
    }

    private fun grantMic() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun idleShowsDictateAffordance() {
        composeRule.setContent {
            DictationMicButton(
                dictation = ComposerDictation(VoiceServerConfig.DEFAULT) { _, _ ->
                    Result.success(TranscriptionResult("hi", "whisper"))
                },
                enabled = true,
                onAppendTranscript = {},
                recorderFactory = { FakeRecorder() },
            )
        }
        composeRule.onNodeWithContentDescription("Dictate message").assertIsDisplayed()
    }

    @Test
    fun tapStartsRecording() {
        grantMic()
        composeRule.setContent {
            DictationMicButton(
                dictation = ComposerDictation(VoiceServerConfig.DEFAULT) { _, _ ->
                    Result.success(TranscriptionResult("hi", "whisper"))
                },
                enabled = true,
                onAppendTranscript = {},
                recorderFactory = { FakeRecorder() },
            )
        }

        // Quick tap (the accessible activation path) starts a hands-free recording.
        composeRule.onNodeWithContentDescription("Dictate message").performClick()
        composeRule.onNodeWithContentDescription("Stop dictation and insert").assertIsDisplayed()
    }

    @Test
    fun tapTwiceTranscribesAndAppendsWithoutSending() = runTest {
        grantMic()
        var appended: String? = null
        composeRule.setContent {
            DictationMicButton(
                dictation = ComposerDictation(VoiceServerConfig.DEFAULT) { _, _ ->
                    Result.success(TranscriptionResult("hello world", "whisper"))
                },
                enabled = true,
                onAppendTranscript = { appended = it },
                recorderFactory = { FakeRecorder() },
            )
        }

        composeRule.onNodeWithContentDescription("Dictate message").performClick()
        composeRule.onNodeWithContentDescription("Stop dictation and insert").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) { appended != null }

        assertEquals("hello world", appended)
    }

    @Test
    fun leavingCompositionCancelsRecorder() {
        grantMic()
        var visible by mutableStateOf(true)
        var recorder: FakeRecorder? = null
        composeRule.setContent {
            if (visible) {
                DictationMicButton(
                    dictation = ComposerDictation(VoiceServerConfig.DEFAULT) { _, _ ->
                        Result.success(TranscriptionResult("hello", "whisper"))
                    },
                    enabled = true,
                    onAppendTranscript = {},
                    recorderFactory = { FakeRecorder().also { recorder = it } },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Dictate message").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { visible = false }
        composeRule.waitForIdle()

        assertTrue(recorder?.cancelled == true)
    }
}
