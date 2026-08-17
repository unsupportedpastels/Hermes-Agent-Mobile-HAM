package com.unsupportedpastels.hermesandroid.voice

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VoiceConversationUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class SilentRecorder : DictationRecorder {
        override val mimeType = "audio/mp4"
        override fun start() = true
        override fun sampleAmplitude() = 0
        override fun sampleLevel() = 0f
        override fun stopAndEncode(): DictationRecording? = null
        override fun cancel() = Unit
    }

    private class NoOpSpeechEngine : SpeechEngine {
        override suspend fun play(
            audio: SpeechAudio,
            onStarted: () -> Unit,
            onFinished: () -> Unit,
            onError: () -> Unit,
        ) {
            onStarted()
            onFinished()
        }

        override fun stop() = Unit

        override fun release() = Unit
    }

    private fun grantMic() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun conversation() = ComposerVoiceConversation(
        serverConfig = VoiceServerConfig.DEFAULT,
        transcribe = { _, _ -> Result.success(TranscriptionResult("", null)) },
        openStream = { null },
        synthesize = { Result.success(SpeechAudio(byteArrayOf(1), "audio/mpeg")) },
    )

    @Test
    fun toggleStartsLoopAndShowsBarThenEndStops() {
        grantMic()
        val submissions = mutableListOf<String>()
        composeRule.setContent {
            val host = rememberVoiceConversationHost(
                conversation = conversation(),
                sessionId = "session-1",
                chat = ChatSessionSnapshot(),
                onSubmit = { text, _ -> submissions += text },
                recorderFactory = { SilentRecorder() },
                speechEngineFactory = { NoOpSpeechEngine() },
            )
            if (host != null) {
                VoiceConversationBar(host = host)
                VoiceConversationToggleButton(host = host, enabled = true)
            }
        }

        composeRule.onNodeWithContentDescription("Start voice conversation").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Start voice conversation").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("Voice conversation bar").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Mute microphone").assertIsDisplayed()

        // Both the bar's close and the active toggle expose "End voice conversation".
        composeRule.onAllNodesWithContentDescription("End voice conversation")
            .onFirst()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Start voice conversation").assertIsDisplayed()
        assertTrue(submissions.isEmpty())
    }

    @Test
    fun barHiddenWhileIdle() {
        composeRule.setContent {
            val host = rememberVoiceConversationHost(
                conversation = conversation(),
                sessionId = "session-1",
                chat = ChatSessionSnapshot(),
                onSubmit = { _, _ -> },
                recorderFactory = { SilentRecorder() },
                speechEngineFactory = { NoOpSpeechEngine() },
            )
            if (host != null) {
                VoiceConversationBar(host = host)
                VoiceConversationToggleButton(host = host, enabled = true)
            }
        }
        composeRule.onNodeWithTag("Voice conversation bar").assertDoesNotExist()
    }
}
