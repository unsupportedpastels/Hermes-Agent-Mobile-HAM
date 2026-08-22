package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.gateway.ActiveRuntimeSession
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.OperationalStatusState
import com.unsupportedpastels.hermesandroid.gateway.RuntimeAccess
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OperationalOverviewTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactOverviewUsesExactLabelsAndExpandsDetailsInOrder() {
        val sessionId = DurableSessionId("sending")
        val snapshot = HermesGatewaySnapshot(
            connectionState = ConnectionState.Connected,
            authenticationState = AuthenticationState.Authenticated,
            chatSessions = mapOf(sessionId to ChatSessionSnapshot(isSending = true)),
            activeRuntimes = listOf(
                ActiveRuntimeSession(
                    runtimeSessionId = com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId("runtime"),
                    durableSessionId = sessionId,
                    title = "Sending",
                    access = RuntimeAccess.Controller,
                ),
            ),
            operationalStatusState = OperationalStatusState.Unavailable,
        )

        composeRule.setContent {
            HermesAndroidTheme { OperationalOverviewItem(snapshot) }
        }

        composeRule.onNodeWithText("Working here").assertIsDisplayed()
        composeRule.onAllNodesWithText("Process-local subagents").assertCountEquals(0)
        composeRule.onAllNodesWithText("Unavailable").assertCountEquals(0)
        composeRule.onNodeWithTag("Operational overview").performClick()
        composeRule.onNodeWithText("Status").assertIsDisplayed()
        composeRule.onNodeWithText("Memory pressure").assertIsDisplayed()
        composeRule.onNodeWithText("Disk pressure").assertIsDisplayed()
        composeRule.onAllNodesWithText("Operational").assertCountEquals(0)
    }
}
