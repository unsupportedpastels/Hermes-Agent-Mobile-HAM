package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsState
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ActiveRuntimeSession
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.ContextBreakdownCategory
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.RuntimeAccess
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import com.unsupportedpastels.hermesandroid.gateway.SessionContextBreakdown
import com.unsupportedpastels.hermesandroid.gateway.SessionUsage
import com.unsupportedpastels.hermesandroid.navigation.SessionDetailRoute
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SessionInsightsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun openingDetailsShowsCanonicalUsageAndRefreshesExactDurableSession() {
        val sessionId = DurableSessionId("durable-session-42")
        val insightLoads = mutableListOf<DurableSessionId>()
        val snapshot = HermesGatewaySnapshot(
            connectionState = ConnectionState.Connected,
            authenticationState = AuthenticationState.Authenticated,
            durableSessions = listOf(SessionSummary(sessionId, "Usage session")),
            activeRuntimes = listOf(
                ActiveRuntimeSession(
                    runtimeSessionId = RuntimeSessionId("runtime-session-42"),
                    durableSessionId = sessionId,
                    title = "Usage session",
                    access = RuntimeAccess.Controller,
                ),
            ),
            chatSessions = mapOf(
                sessionId to ChatSessionSnapshot(
                    model = "Fable 5",
                    sessionUsage = SessionUsage(
                        inputTokens = 1_024,
                        outputTokens = 512,
                        totalTokens = 1_536,
                        contextUsedTokens = 3_072,
                        contextMaxTokens = 8_192,
                        contextPercent = 37.5,
                    ),
                    contextBreakdown = SessionContextBreakdown(
                        categories = listOf(
                            ContextBreakdownCategory("System prompt", tokens = 1_024),
                            ContextBreakdownCategory("Conversation", tokens = 2_048),
                        ),
                    ),
                ),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    initialRoute = SessionDetailRoute(sessionId),
                    serverSettingsState = ServerSettingsState.Ready(null),
                    onLoadSessionInsights = { insightLoads += it },
                )
            }
        }

        composeRule.runOnIdle {
            assertEquals(emptyList<DurableSessionId>(), insightLoads)
        }
        composeRule.onNodeWithContentDescription("Open session details").performClick()
        composeRule.onNodeWithText("Session details").assertIsDisplayed()
        composeRule.onNodeWithText("Input tokens").assertIsDisplayed()
        composeRule.onNodeWithText("1,024").assertIsDisplayed()
        composeRule.onNodeWithText("Output tokens").assertIsDisplayed()
        composeRule.onNodeWithText("512").assertIsDisplayed()
        composeRule.onAllNodesWithText("Total tokens").assertCountEquals(1)
        composeRule.onAllNodesWithText("1,536").assertCountEquals(1)
        composeRule.onAllNodesWithText("Context used").assertCountEquals(1)
        composeRule.onAllNodesWithText("3,072 / 8,192 (37.5%)").assertCountEquals(1)
        composeRule.onAllNodesWithText("Model: Fable 5").assertCountEquals(1)
        composeRule.onAllNodesWithText("System prompt").assertCountEquals(1)
        composeRule.onAllNodesWithText("1,024 tokens").assertCountEquals(1)
        composeRule.onNodeWithText("Refresh").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(sessionId, sessionId), insightLoads)
        }
    }

    @Test
    fun cachedDetailsStayAvailableWithoutControllerAndDoNotShowRefreshFailure() {
        val sessionId = DurableSessionId("durable-idle-session")
        val insightLoads = mutableListOf<DurableSessionId>()
        val snapshot = HermesGatewaySnapshot(
            connectionState = ConnectionState.Connected,
            authenticationState = AuthenticationState.Authenticated,
            durableSessions = listOf(SessionSummary(sessionId, "Idle session")),
            chatSessions = mapOf(
                sessionId to ChatSessionSnapshot(
                    sessionUsage = SessionUsage(totalTokens = 1_536),
                    insightsError = "Session details require an active HAM runtime",
                ),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    initialRoute = SessionDetailRoute(sessionId),
                    serverSettingsState = ServerSettingsState.Ready(null),
                    onLoadSessionInsights = { insightLoads += it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open session details").performClick()
        composeRule.onAllNodesWithText("1,536").assertCountEquals(1)
        composeRule.onNodeWithText("Could not load session details").assertDoesNotExist()
        composeRule.onNodeWithText("Session details require an active HAM runtime").assertDoesNotExist()
        composeRule.onNodeWithText("Refresh").assertIsNotEnabled()
        composeRule.runOnIdle {
            assertEquals(emptyList<DurableSessionId>(), insightLoads)
        }
    }
}
