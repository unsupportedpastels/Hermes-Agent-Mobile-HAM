package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsState
import com.unsupportedpastels.hermesandroid.gateway.ActiveRuntimeSession
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.RuntimeAccess
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import com.unsupportedpastels.hermesandroid.navigation.SessionDetailRoute
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ActiveTurnSteerUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activeControllerKeepsComposerForTrimmedSteerAndLeavesAttachmentsUnavailable() {
        val sessionId = DurableSessionId("durable-steer")
        var steered: Pair<DurableSessionId, String>? = null
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Connected,
                        authenticationState = AuthenticationState.Authenticated,
                        durableSessions = listOf(SessionSummary(sessionId, "Steer session")),
                        chatSessions = mapOf(
                            sessionId to ChatSessionSnapshot(
                                isSending = true,
                                notice = "Guidance queued for the active turn",
                                error = "A later steer was rejected",
                            ),
                        ),
                        activeRuntimes = listOf(
                            ActiveRuntimeSession(
                                runtimeSessionId = RuntimeSessionId("runtime-steer"),
                                durableSessionId = sessionId,
                                title = "Steer session",
                                access = RuntimeAccess.Controller,
                            ),
                        ),
                    ),
                    initialRoute = SessionDetailRoute(sessionId),
                    serverSettingsState = ServerSettingsState.Ready(null),
                    onSteerSession = { id, text -> steered = id to text },
                )
            }
        }

        onNodeWithComposerInput().performTextInput("  Focus on the failing test  ")
        composeRule.onNodeWithContentDescription("Steer Hermes response").assertIsDisplayed().performClick()

        composeRule.onNodeWithText("Stop").assertIsDisplayed()
        composeRule.onNodeWithText("Steer").assertIsDisplayed()
        composeRule.onNodeWithText("Send").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Attach files").assertIsNotEnabled()
        composeRule.onNodeWithText("Guidance queued for the active turn").assertIsDisplayed()
        composeRule.onNodeWithText("A later steer was rejected").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(sessionId to "Focus on the failing test", steered)
        }
    }

    private fun onNodeWithComposerInput() = composeRule.onNode(hasSetTextAction())
}
