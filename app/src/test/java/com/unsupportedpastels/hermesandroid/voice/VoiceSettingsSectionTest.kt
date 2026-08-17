package com.unsupportedpastels.hermesandroid.voice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VoiceSettingsSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun settings(
        accepted: Boolean,
        capabilities: VoiceCapabilities = VoiceCapabilities(
            audioRoutesPresent = true,
            elevenLabsVoicesAvailable = false,
        ),
    ) = VoiceSettings(
        capabilities = capabilities,
        config = VoiceServerConfig.DEFAULT.copy(sttProvider = "local", ttsProvider = "edge"),
        setAutoTts = { accepted },
        setElevenLabsVoice = { accepted },
        loadVoices = { emptyList() },
    )

    @Test
    fun acceptedAutoTtsToggleStaysOn() {
        composeRule.setContent { VoiceSettingsSection(settings(accepted = true)) }
        composeRule.onNodeWithText("Voice").assertIsDisplayed()
        composeRule.onNodeWithText("Via local").assertIsDisplayed()
        composeRule.onNodeWithText("Via edge").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Speak replies automatically").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Speak replies automatically").assertIsOn()
    }

    @Test
    fun rejectedAutoTtsToggleRollsBackWithError() {
        composeRule.setContent { VoiceSettingsSection(settings(accepted = false)) }
        composeRule.onNodeWithContentDescription("Speak replies automatically").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Speak replies automatically").assertIsOff()
        composeRule.onNodeWithText("Couldn't update the server setting").assertIsDisplayed()
    }

    @Test
    fun hiddenEntirelyWithoutAudioRoutes() {
        composeRule.setContent {
            VoiceSettingsSection(
                settings(accepted = true, capabilities = VoiceCapabilities.NONE),
            )
        }
        composeRule.onNodeWithText("Voice").assertDoesNotExist()
    }
}
