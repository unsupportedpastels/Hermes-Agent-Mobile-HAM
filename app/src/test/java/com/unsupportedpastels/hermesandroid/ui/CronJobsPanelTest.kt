package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.gateway.CronJob
import com.unsupportedpastels.hermesandroid.gateway.CronJobAction
import com.unsupportedpastels.hermesandroid.gateway.CronJobsState
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
class CronJobsPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun readyStateDisplaysJobsAndDispatchesLifecycleActions() {
        var refreshCount = 0
        val actions = mutableListOf<Pair<String, CronJobAction>>()
        val state = CronJobsState.Ready(
            jobs = listOf(
                CronJob(
                    jobId = "morning-brief",
                    name = "Morning brief",
                    schedule = "0 8 * * *",
                    enabled = true,
                    state = "scheduled",
                    nextRunAt = "2026-08-15T08:00:00Z",
                    lastRunAt = "2026-08-14T08:00:00Z",
                    lastStatus = "success",
                ),
                CronJob(
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
                // The panel no longer scrolls internally; the caller owns scrolling, as
                // ServerSettingsScreen does.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CronJobsPanel(
                        state = state,
                        onRefresh = { refreshCount += 1 },
                        onJobAction = { jobId, action -> actions += jobId to action },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Cron jobs").assertIsDisplayed()
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

        // The enabled job offers Disable; the paused job offers Enable.
        composeRule.onNodeWithText("Disable").performScrollTo().performClick()
        composeRule.onNodeWithText("Enable").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    "morning-brief" to CronJobAction.Disable,
                    "price-watch" to CronJobAction.Enable,
                ),
                actions,
            )
        }

        // Nothing beyond the gateway's pause/resume support is offered.
        listOf("Create", "Edit", "Delete", "Run now", "Stop").forEach { forbiddenLabel ->
            composeRule.onAllNodesWithText(forbiddenLabel, useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test
    fun inFlightActionDisablesJobControlsAndSurfacesErrors() {
        var actionCount = 0
        val job = CronJob("job-1", "Nightly monitor", "0 2 * * *", enabled = true)

        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CronJobsPanel(
                        state = CronJobsState.Ready(listOf(job)),
                        onRefresh = {},
                        actionJobId = "job-1",
                        actionError = "Could not disable the job",
                        onJobAction = { _, _ -> actionCount += 1 },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Could not disable the job").assertIsDisplayed()
        composeRule.onNodeWithText("Working…").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Disable").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(0, actionCount) }
    }

    @Test
    fun idleLoadingUnsupportedAndErrorStatesRemainRefreshableAndVisible() {
        val state = mutableStateOf<CronJobsState>(CronJobsState.Idle)
        var refreshCount = 0

        composeRule.setContent {
            MaterialTheme {
                CronJobsPanel(
                    state = state.value,
                    onRefresh = { refreshCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("No cron jobs loaded yet.").assertIsDisplayed()

        composeRule.runOnIdle { state.value = CronJobsState.Loading }
        composeRule.onNodeWithText("Loading cron jobs").assertIsDisplayed()

        composeRule.runOnIdle { state.value = CronJobsState.Unsupported }
        composeRule.onNodeWithText("Cron jobs are not supported by this server.").assertIsDisplayed()

        composeRule.runOnIdle { state.value = CronJobsState.Error("Could not reach the server") }
        composeRule.onNodeWithText("Could not reach the server").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh").performClick()
        composeRule.runOnIdle { assertEquals(1, refreshCount) }
    }

    @Test
    fun authenticatedServerSettingsHostsCronJobsPanelAndRefreshes() {
        var refreshCount = 0
        composeRule.setContent {
            MaterialTheme {
                ServerSettingsScreen(
                    serverOrigin = ServerOrigin.parse("https://hermes.example"),
                    snapshot = HermesGatewaySnapshot(
                        authenticationState = AuthenticationState.Authenticated,
                        cronJobsState = CronJobsState.Ready(
                            listOf(CronJob("job-1", "Nightly monitor", "0 2 * * *", enabled = false)),
                        ),
                    ),
                    showBack = true,
                    onBack = {},
                    onSave = { Result.success(Unit) },
                    onRefreshCronJobs = { refreshCount += 1 },
                )
            }
        }

        composeRule.onAllNodesWithText("Nightly monitor").assertCountEquals(1)
        composeRule.onAllNodesWithText("Status: Paused").assertCountEquals(1)
        composeRule.runOnIdle { assertEquals(1, refreshCount) }
    }
}
