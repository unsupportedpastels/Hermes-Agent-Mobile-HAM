package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.mutableStateOf
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
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import com.unsupportedpastels.hermesandroid.navigation.SessionDetailRoute
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SessionMaintenanceUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun idleExactControllerConfirmsMaintenanceAndBranchesSelectedSession() {
        val sessionId = DurableSessionId("durable-session-42")
        val compressed = mutableListOf<Pair<DurableSessionId, String?>>()
        val undone = mutableListOf<DurableSessionId>()
        val branched = mutableListOf<Triple<DurableSessionId, Int?, String?>>()
        val branchId = DurableSessionId("authoritative-branch")
        val snapshotState = mutableStateOf(
            snapshotFor(
                sessionId = sessionId,
                activeRuntimes = listOf(
                    ActiveRuntimeSession(
                        runtimeSessionId = RuntimeSessionId("runtime-42"),
                        durableSessionId = sessionId,
                        title = "Maintenance session",
                        access = RuntimeAccess.Controller,
                    ),
                ),
            ),
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshotState.value,
                    initialRoute = SessionDetailRoute(sessionId),
                    serverSettingsState = ServerSettingsState.Ready(null),
                    onCompressSession = { id, focusTopic -> compressed += id to focusTopic },
                    onUndoSession = { undone += it },
                    onBranchSession = { id, count, name ->
                        branched += Triple(id, count, name)
                        snapshotState.value = snapshotState.value.copy(
                            durableSessions = snapshotState.value.durableSessions +
                                SessionSummary(branchId, "Authoritative branch"),
                            chatSessions = snapshotState.value.chatSessions +
                                (branchId to ChatSessionSnapshot()),
                            lastBranchedSessionId = branchId,
                        )
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open session details").performClick()
        composeRule.onNodeWithText("Compress").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Confirm compression").performClick()
        composeRule.onNodeWithText("Undo").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Confirm undo").performClick()
        composeRule.onNodeWithText("Branch").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Create branch").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(sessionId to null), compressed)
            assertEquals(listOf(sessionId), undone)
            assertEquals(
                listOf(Triple(sessionId, null, "Maintenance session branch")),
                branched,
            )
        }
        composeRule.onNodeWithText("Authoritative branch").assertIsDisplayed()
    }

    @Test
    fun maintenanceControlsRequireExactControllerAndDisableWhileBusy() {
        val sessionId = DurableSessionId("durable-session-42")
        val otherSessionId = DurableSessionId("other-session")
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshotFor(
                        sessionId = sessionId,
                        chat = ChatSessionSnapshot(
                            isSending = true,
                            maintenanceError = "Maintenance failed",
                            notice = "Maintenance notice",
                        ),
                        activeRuntimes = listOf(
                            ActiveRuntimeSession(
                                runtimeSessionId = RuntimeSessionId("observer-42"),
                                durableSessionId = sessionId,
                                title = "Observer",
                                access = RuntimeAccess.Observer,
                            ),
                            ActiveRuntimeSession(
                                runtimeSessionId = RuntimeSessionId("controller-other"),
                                durableSessionId = otherSessionId,
                                title = "Other controller",
                                access = RuntimeAccess.Controller,
                            ),
                        ),
                    ),
                    initialRoute = SessionDetailRoute(sessionId),
                    serverSettingsState = ServerSettingsState.Ready(null),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open session details").performClick()
        composeRule.onAllNodesWithText("Compress").assertCountEquals(0)
        composeRule.onAllNodesWithText("Undo").assertCountEquals(0)
        composeRule.onAllNodesWithText("Branch").assertCountEquals(0)
        composeRule.onAllNodesWithText("Maintenance failed").assertCountEquals(1)
        composeRule.onAllNodesWithText("Maintenance notice").assertCountEquals(2)
    }

    @Test
    fun exactControllerShowsDisabledControlsDuringSendingAndMaintenanceLoading() {
        val sessionId = DurableSessionId("durable-session-42")
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshotFor(
                        sessionId = sessionId,
                        chat = ChatSessionSnapshot(maintenanceLoading = true),
                        activeRuntimes = listOf(
                            ActiveRuntimeSession(
                                runtimeSessionId = RuntimeSessionId("runtime-42"),
                                durableSessionId = sessionId,
                                title = "Maintenance session",
                                access = RuntimeAccess.Controller,
                            ),
                        ),
                    ),
                    initialRoute = SessionDetailRoute(sessionId),
                    serverSettingsState = ServerSettingsState.Ready(null),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open session details").performClick()
        composeRule.onAllNodesWithText("Maintenance").assertCountEquals(1)
        composeRule.onNodeWithText("Compress").assertIsNotEnabled()
        composeRule.onNodeWithText("Undo").assertIsNotEnabled()
        composeRule.onNodeWithText("Branch").assertIsNotEnabled()
    }

    private fun snapshotFor(
        sessionId: DurableSessionId,
        chat: ChatSessionSnapshot = ChatSessionSnapshot(),
        activeRuntimes: List<ActiveRuntimeSession>,
    ) = HermesGatewaySnapshot(
        connectionState = ConnectionState.Connected,
        authenticationState = AuthenticationState.Authenticated,
        durableSessions = listOf(SessionSummary(sessionId, "Maintenance session")),
        chatSessions = mapOf(sessionId to chat),
        activeRuntimes = activeRuntimes,
    )
}
