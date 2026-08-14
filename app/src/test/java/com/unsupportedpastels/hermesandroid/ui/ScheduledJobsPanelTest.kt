package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.gateway.ScheduledJob
import com.unsupportedpastels.hermesandroid.gateway.ScheduledJobsState
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ScheduledJobsPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun readyStateDisplaysEnabledAndPausedJobsAndOnlyRefreshIsActionable() {
        var refreshCount = 0
        val state = ScheduledJobsState.Ready(
            jobs = listOf(
                ScheduledJob(
                    jobId = "morning-brief",
                    name = "Morning brief",
                    schedule = "0 8 * * *",
                    enabled = true,
                    state = "scheduled",
                    nextRunAt = "2026-08-15T08:00:00Z",
                    lastRunAt = "2026-08-14T08:00:00Z",
                    lastStatus = "success",
                ),
                ScheduledJob(
                    jobId = "price-watch",
                    name = "Price watch",
                    schedule = "every 2h",
                    enabled = false,
                    state = "paused",
                    lastRunAt = "2026-08-14T06:00:00Z",
                    lastStatus = "skipped",
                ),
            ),
        )

        composeRule.setContent {
            MaterialTheme {
                ScheduledJobsPanel(
                    state = state,
                    onRefresh = { refreshCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Scheduled jobs").assertIsDisplayed()
        composeRule.onNodeWithText("Morning brief").assertIsDisplayed()
        composeRule.onNodeWithText("0 8 * * *").assertIsDisplayed()
        composeRule.onNodeWithText("Status: Enabled").assertIsDisplayed()
        composeRule.onNodeWithText("State: scheduled").assertIsDisplayed()
        composeRule.onNodeWithText("Next run: 2026-08-15T08:00:00Z").assertIsDisplayed()
        composeRule.onNodeWithText("Last run: 2026-08-14T08:00:00Z").assertIsDisplayed()
        composeRule.onNodeWithText("Last status: success").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh").performClick()
        composeRule.runOnIdle { assertEquals(1, refreshCount) }

        composeRule.onNodeWithText("Price watch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("every 2h").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Status: Paused").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("State: paused").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Last status: skipped").performScrollTo().assertIsDisplayed()

        listOf("Create", "Edit", "Delete", "Enable", "Disable", "Toggle").forEach { forbiddenLabel ->
            composeRule.onAllNodesWithText(forbiddenLabel, useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test
    fun idleLoadingUnsupportedAndErrorStatesRemainRefreshableAndVisible() {
        val state = mutableStateOf<ScheduledJobsState>(ScheduledJobsState.Idle)
        var refreshCount = 0

        composeRule.setContent {
            MaterialTheme {
                ScheduledJobsPanel(
                    state = state.value,
                    onRefresh = { refreshCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("No scheduled jobs loaded yet.").assertIsDisplayed()

        composeRule.runOnIdle { state.value = ScheduledJobsState.Loading }
        composeRule.onNodeWithText("Loading scheduled jobs").assertIsDisplayed()

        composeRule.runOnIdle { state.value = ScheduledJobsState.Unsupported }
        composeRule.onNodeWithText("Scheduled jobs are not supported by this server.").assertIsDisplayed()

        composeRule.runOnIdle { state.value = ScheduledJobsState.Error("Could not reach the server") }
        composeRule.onNodeWithText("Could not reach the server").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh").performClick()
        composeRule.runOnIdle { assertEquals(1, refreshCount) }
    }

    @Test
    fun authenticatedServerSettingsHostsReadOnlyMonitorAndRefreshes() {
        var refreshCount = 0
        composeRule.setContent {
            MaterialTheme {
                ServerSettingsScreen(
                    serverOrigin = ServerOrigin.parse("https://hermes.example"),
                    snapshot = HermesGatewaySnapshot(
                        authenticationState = AuthenticationState.Authenticated,
                        scheduledJobsState = ScheduledJobsState.Ready(
                            listOf(ScheduledJob("job-1", "Nightly monitor", "0 2 * * *", enabled = false)),
                        ),
                    ),
                    showBack = true,
                    onBack = {},
                    onSave = { Result.success(Unit) },
                    onRefreshScheduledJobs = { refreshCount += 1 },
                )
            }
        }

        composeRule.onAllNodesWithText("Nightly monitor").assertCountEquals(1)
        composeRule.onAllNodesWithText("Status: Paused").assertCountEquals(1)
        composeRule.runOnIdle { assertEquals(1, refreshCount) }
    }
}
