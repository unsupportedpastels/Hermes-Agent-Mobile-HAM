package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.gateway.CronJob
import com.unsupportedpastels.hermesandroid.gateway.CronJobAction
import com.unsupportedpastels.hermesandroid.gateway.CronJobsState

/**
 * Displays the server's cron jobs with per-job lifecycle controls
 * (enable/disable).
 *
 * Insets are intentionally owned by the caller so this surface can be placed in
 * compact or adaptive containers without applying system-bar padding twice.
 */
@Composable
fun CronJobsPanel(
    state: CronJobsState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    actionJobId: String? = null,
    actionError: String? = null,
    onJobAction: (String, CronJobAction) -> Unit = { _, _ -> },
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 720.dp)
            .verticalScroll(rememberScrollState())
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
                        CronJobCard(
                            job = job,
                            actionJobId = actionJobId,
                            onJobAction = onJobAction,
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
        }
    }
}

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
