package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.gateway.CronJob
import com.unsupportedpastels.hermesandroid.gateway.CronJobAction
import com.unsupportedpastels.hermesandroid.gateway.CronJobRun
import com.unsupportedpastels.hermesandroid.gateway.CronJobRunsState
import com.unsupportedpastels.hermesandroid.gateway.CronJobScope
import com.unsupportedpastels.hermesandroid.gateway.CronJobsState
import com.unsupportedpastels.hermesandroid.gateway.CronRestCapability

/**
 * Displays the server's cron jobs with JSON-RPC lifecycle controls and capability-gated REST
 * trigger/history controls. The caller owns the page scroll; run history owns a bounded nested
 * scroll so a long returned list cannot push the rest of Settings offscreen.
 */
@Composable
fun CronJobsPanel(
    state: CronJobsState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    actionJobId: String? = null,
    actionError: String? = null,
    onJobAction: (String, CronJobAction) -> Unit = { _, _ -> },
    cronServerOrigin: String? = null,
    cronProfile: String = "default",
    triggerCapability: CronRestCapability = CronRestCapability.Unknown,
    historyCapability: CronRestCapability = CronRestCapability.Unknown,
    runLoadingScopes: Set<CronJobScope> = emptySet(),
    runErrors: Map<CronJobScope, String> = emptyMap(),
    runsByScope: Map<CronJobScope, CronJobRunsState> = emptyMap(),
    onRunNow: (String) -> Unit = {},
    onToggleRuns: (String) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 720.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Cron jobs",
                style = MaterialTheme.typography.titleLarge,
            )
            Button(
                onClick = onRefresh,
                enabled = state !is CronJobsState.Loading,
            ) {
                Text("Refresh")
            }
        }

        actionError?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
            )
        }

        when (state) {
            CronJobsState.Idle ->
                Text("No cron jobs loaded yet.")

            CronJobsState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Text("Loading cron jobs")
            }

            is CronJobsState.Ready -> {
                if (state.jobs.isEmpty()) {
                    Text("No cron jobs found.")
                } else {
                    state.jobs.forEach { job ->
                        val scope = cronServerOrigin?.let {
                            CronJobScope(it, cronProfile, job.jobId)
                        }
                        CronJobCard(
                            job = job,
                            actionJobId = actionJobId,
                            onJobAction = onJobAction,
                            scope = scope,
                            triggerVisible = scope != null &&
                                triggerCapability != CronRestCapability.Unsupported,
                            historyVisible = scope != null &&
                                historyCapability != CronRestCapability.Unsupported,
                            runLoading = scope != null && scope in runLoadingScopes,
                            runError = scope?.let(runErrors::get),
                            runsState = scope?.let(runsByScope::get)
                                ?: CronJobRunsState.Collapsed,
                            onRunNow = onRunNow,
                            onToggleRuns = onToggleRuns,
                        )
                    }
                }
            }

            CronJobsState.Unsupported ->
                Text("Cron jobs are not supported by this server.")

            is CronJobsState.Error -> {
                Text(
                    text = "Could not load cron jobs.",
                    color = MaterialTheme.colorScheme.error,
                )
                Text(state.message)
            }
        }
    }
}

@Composable
private fun CronJobCard(
    job: CronJob,
    actionJobId: String?,
    onJobAction: (String, CronJobAction) -> Unit,
    scope: CronJobScope?,
    triggerVisible: Boolean,
    historyVisible: Boolean,
    runLoading: Boolean,
    runError: String?,
    runsState: CronJobRunsState,
    onRunNow: (String) -> Unit,
    onToggleRuns: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = job.name,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = job.schedule,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Status: ${job.displayStatus()}")
            job.state.displayValue("State")
            job.lastStatus.displayValue("Last status")
            job.nextRunAt.displayValue("Next run")
            job.lastRunAt.displayValue("Last run")
            val toggle = if (job.enabled == false) CronJobAction.Enable else CronJobAction.Disable
            OutlinedButton(
                enabled = actionJobId == null,
                onClick = { onJobAction(job.jobId, toggle) },
            ) {
                Text(if (toggle == CronJobAction.Enable) "Enable" else "Disable")
            }
            if (actionJobId == job.jobId) {
                Text("Working…", style = MaterialTheme.typography.bodySmall)
            }

            if (triggerVisible) {
                Button(
                    enabled = !runLoading,
                    onClick = { onRunNow(job.jobId) },
                    modifier = Modifier.semantics {
                        contentDescription = "Run ${job.name} now"
                    },
                ) {
                    Text("Run now")
                }
                if (runLoading) {
                    Text("Running…", style = MaterialTheme.typography.bodySmall)
                }
                runError?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics {
                            contentDescription = "Run now status for ${job.name}"
                        },
                    )
                }
            }

            if (historyVisible) {
                TextButton(
                    enabled = runsState !is CronJobRunsState.Loading,
                    onClick = { onToggleRuns(job.jobId) },
                    modifier = Modifier.semantics {
                        contentDescription = "Execution history for ${job.name}"
                    },
                ) {
                    Text(if (runsState is CronJobRunsState.Ready) "Hide runs" else "View runs")
                }
                when (runsState) {
                    CronJobRunsState.Loading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        Text("Loading runs")
                    }
                    is CronJobRunsState.Ready -> CronRunDetails(runsState.runs, job)
                    is CronJobRunsState.Error -> {
                        Text(runsState.message, color = MaterialTheme.colorScheme.error)
                    }
                    CronJobRunsState.Collapsed,
                    is CronJobRunsState.Cached,
                    CronJobRunsState.Unsupported,
                    -> Unit
                }
            }
        }
    }
}

@Composable
private fun CronRunDetails(runs: List<com.unsupportedpastels.hermesandroid.gateway.CronJobRun>, job: CronJob) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState())
            .semantics {
                contentDescription = "Execution session details for ${job.name}"
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (runs.isEmpty()) {
            Text("No returned runs.")
        } else {
            runs.forEach { run ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(run.title?.takeIf(String::isNotBlank) ?: run.id)
                    run.preview.displayValue("Preview")
                    run.source.displayValue("Source")
                    run.model.displayValue("Model")
                    run.provider.displayValue("Provider")
                    run.profile.displayValue("Profile")
                    run.cwd.displayValue("Workspace")
                    run.startedAt.displayValue("Started")
                    run.endedAt.displayValue("Ended")
                    run.lastActive.displayValue("Last active")
                    run.isActive.displayValue("Active")
                    run.status.displayValue("Status")
                    run.finishReason.displayValue("Finish reason")
                    run.error.displayValue("Error")
                    run.messageCount.displayValue("Messages")
                    run.toolCallCount.displayValue("Tool calls")
                    run.inputTokens.displayValue("Input tokens")
                    run.outputTokens.displayValue("Output tokens")
                }
            }
        }
    }
    if (runs.any(CronJobRun::hasAdditionalReturnedDetails)) {
        Text(
            "Scroll run details for more",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun CronJobRun.hasAdditionalReturnedDetails(): Boolean =
    listOfNotNull(
        preview,
        source,
        model,
        provider,
        profile,
        cwd,
        startedAt,
        endedAt,
        lastActive,
        isActive,
        status,
        finishReason,
        error,
        messageCount,
        toolCallCount,
        inputTokens,
        outputTokens,
    ).size > 4

private fun CronJob.displayStatus(): String = when (enabled) {
    true -> "Enabled"
    false -> "Paused"
    null -> state?.takeIf(String::isNotBlank)
        ?: lastStatus?.takeIf(String::isNotBlank)
        ?: "Unknown"
}

@Composable
private fun String?.displayValue(label: String) {
    takeIf { !it.isNullOrBlank() }?.let { value ->
        Text("$label: $value")
    }
}

@Composable
private fun Double?.displayValue(label: String) {
    this?.let {
        val rendered = if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
        Text("$label: $rendered")
    }
}

@Composable
private fun Long?.displayValue(label: String) {
    this?.let { Text("$label: $it") }
}

@Composable
private fun Boolean?.displayValue(label: String) {
    this?.let { Text("$label: $it") }
}
