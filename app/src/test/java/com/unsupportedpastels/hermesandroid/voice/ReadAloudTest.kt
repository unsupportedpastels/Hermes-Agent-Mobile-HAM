package com.unsupportedpastels.hermesandroid.voice

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ReadAloudTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class FakeSpeechEngine : SpeechEngine {
        override suspend fun play(
            audio: SpeechAudio,
            onStarted: () -> Unit,
            onFinished: () -> Unit,
            onError: () -> Unit,
        ) {
            onStarted() // start and keep playing
        }

        override fun stop() {}
        override fun release() {}
    }

    private val readAloud = MessageReadAloud { _ ->
        Result.success(SpeechAudio(byteArrayOf(1, 2, 3), "audio/mpeg"))
    }

    @Composable
    private fun session(): ReadAloudSession =
        rememberReadAloudSession(readAloud, sessionId = "sess", engineFactory = { FakeSpeechEngine() })!!

    @Test
    fun idleShowsReadAloudAffordance() {
        composeRule.setContent {
            MessageSpeakerButton(session(), "message:0", "Hello world", enabled = true)
        }
        composeRule.onNodeWithContentDescription("Read message aloud").assertIsDisplayed()
    }

    @Test
    fun tapStartsPlaybackAndTogglesToStop() {
        composeRule.setContent {
            MessageSpeakerButton(session(), "message:0", "Hello world", enabled = true)
        }
        composeRule.onNodeWithContentDescription("Read message aloud").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Stop reading aloud").assertIsDisplayed()
    }

    @Test
    fun onlyOneMessagePlaysAtATime() {
        composeRule.setContent {
            val shared = session()
            Row {
                MessageSpeakerButton(shared, "message:0", "First", enabled = true)
                MessageSpeakerButton(shared, "message:1", "Second", enabled = true)
            }
        }
        // Start message:0 (first of the two "Read message aloud" nodes).
        composeRule.onAllNodesWithContentDescription("Read message aloud")[0].performClick()
        composeRule.waitForIdle()
        // message:0 now shows "Stop reading aloud"; message:1 remains, but disabled.
        composeRule.onNodeWithContentDescription("Read message aloud").assertIsNotEnabled()
    }
}
