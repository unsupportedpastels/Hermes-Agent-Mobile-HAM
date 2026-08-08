package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HermesAppTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val sessions = listOf(
        SessionSummary(DurableSessionId("stored-1"), "First session"),
        SessionSummary(DurableSessionId("stored-2"), "Second session"),
    )
    private val connectedSnapshot = HermesGatewaySnapshot(
        connectionState = ConnectionState.Connected,
        durableSessions = sessions,
    )

    @Test
    fun selectingSessionShowsDetailAndEditableComposer() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = connectedSnapshot)
            }
        }

        composeRule.onNodeWithText("Sessions").assertIsDisplayed()
        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        composeRule.onNodeWithText("No messages yet").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextInput("Fold-safe draft")
        composeRule.onNodeWithText("Fold-safe draft").assertIsDisplayed()
    }

    @Test
    fun composerDraftSurvivesSavedStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = connectedSnapshot)
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Restored draft")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("First session").assertIsDisplayed()
        composeRule.onNodeWithText("Restored draft").assertIsDisplayed()
    }

    @Test
    fun connectedServerWithoutDurableSessionsIsNotShownAsUnconfigured() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Connected,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("No saved sessions").assertIsDisplayed()
    }
}
