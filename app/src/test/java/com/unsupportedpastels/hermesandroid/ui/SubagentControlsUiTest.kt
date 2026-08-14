package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.app.DelegatedSubagent
import com.unsupportedpastels.hermesandroid.app.DelegationStatus
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
class SubagentControlsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun exactControllerExposesPauseSteerAndConfirmedInterruptForActiveChildren() {
        val sessionId = DurableSessionId("durable-delegation")
        var paused: Pair<DurableSessionId, Boolean>? = null
        var steered: Triple<DurableSessionId, String, String>? = null
        var interrupted: Pair<DurableSessionId, String>? = null
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Connected,
                        authenticationState = AuthenticationState.Authenticated,
                        durableSessions = listOf(SessionSummary(sessionId, "Delegation session")),
                        chatSessions = mapOf(sessionId to ChatSessionSnapshot()),
                        activeRuntimes = listOf(
                            ActiveRuntimeSession(
                                runtimeSessionId = RuntimeSessionId("runtime-parent"),
                                durableSessionId = sessionId,
                                title = "Delegation session",
                                access = RuntimeAccess.Controller,
                            ),
                        ),
                        delegationStatus = DelegationStatus(
                            active = listOf(
                                DelegatedSubagent(
                                    subagentId = "child-1",
                                    goal = "Inspect the focused tests",
                                    status = "running",
                                ),
                            ),
                            notice = "Guidance queued",
                            error = "A previous delegation failed",
                        ),
                    ),
                    initialRoute = SessionDetailRoute(sessionId),
                    serverSettingsState = ServerSettingsState.Ready(null),
                    onSetDelegationPaused = { id, value -> paused = id to value },
                    onSteerSubagent = { id, childId, text -> steered = Triple(id, childId, text) },
                    onInterruptSubagent = { id, childId -> interrupted = id to childId },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Pause spawning").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Steer subagent child-1").performClick()
        composeRule.onNodeWithContentDescription("Subagent guidance").performTextInput("  Focus on Android tests  ")
        composeRule.onNodeWithContentDescription("Confirm steer").performClick()
        composeRule.onNodeWithContentDescription("Interrupt subagent child-1").performClick()
        composeRule.onNodeWithText("Interrupt subagent?").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Confirm interrupt subagent child-1").performClick()

        composeRule.onNodeWithText("Guidance queued").assertIsDisplayed()
        composeRule.onNodeWithText("A previous delegation failed").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(sessionId to true, paused)
            assertEquals(Triple(sessionId, "child-1", "Focus on Android tests"), steered)
            assertEquals(sessionId to "child-1", interrupted)
        }
    }
}
