package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
    fun sessionDetailDoesNotRenderProcessLocalSubagentState() {
        val sessionId = DurableSessionId("durable-delegation")
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = delegationSnapshot(sessionId),
                    initialRoute = SessionDetailRoute(sessionId),
                    serverSettingsState = ServerSettingsState.Ready(null),
                )
            }
        }

        composeRule.onAllNodesWithText("Subagent controls").assertCountEquals(0)
        composeRule.onAllNodesWithText("Running subagents").assertCountEquals(0)
        composeRule.onAllNodesWithText("Inspect the focused tests").assertCountEquals(0)
    }

    @Test
    fun homeRendersProcessLocalSubagentState() {
        val sessionId = DurableSessionId("durable-delegation-home")
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = delegationSnapshot(sessionId),
                    serverSettingsState = ServerSettingsState.Ready(null),
                )
            }
        }

        composeRule.onAllNodesWithText("Running subagents").assertCountEquals(1)
        composeRule.onAllNodesWithText("Inspect the focused tests").assertCountEquals(1)
        composeRule.onAllNodesWithText("Subagent controls").assertCountEquals(0)
    }

    private fun delegationSnapshot(sessionId: DurableSessionId) = HermesGatewaySnapshot(
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
        ),
    )
}