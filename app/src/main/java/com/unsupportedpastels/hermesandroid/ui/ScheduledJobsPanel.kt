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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.gateway.ScheduledJob
import com.unsupportedpastels.hermesandroid.gateway.ScheduledJobsState

/**
 * Displays the server's scheduled jobs without exposing lifecycle controls.
 *
 * Insets are intentionally owned by the caller so this surface can be placed in
 * compact or adaptive containers without applying system-bar padding twice.
 */
@Composable
fun ScheduledJobsPanel(
    state: ScheduledJobsState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
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
                text = "Scheduled jobs",
                style = MaterialTheme.typography.titleLarge,
            )
            Button(
                onClick = onRefresh,
                enabled = state !is ScheduledJobsState.Loading,
            ) {
                Text("Refresh")
            }
        }

        when (state) {
            ScheduledJobsState.Idle ->
                Text("No scheduled jobs loaded yet.")

            ScheduledJobsState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Text("Loading scheduled jobs")
            }

            is ScheduledJobsState.Ready -> {
                if (state.jobs.isEmpty()) {
                    Text("No scheduled jobs found.")
                } else {
                    state.jobs.forEach { job ->
                        ScheduledJobCard(job)
                    }
                }
            }

            ScheduledJobsState.Unsupported ->
                Text("Scheduled jobs are not supported by this server.")

            is ScheduledJobsState.Error -> {
                Text(
                    text = "Could not load scheduled jobs.",
                    color = MaterialTheme.colorScheme.error,
                )
                Text(state.message)
            }
        }
    }
}

@Composable
private fun ScheduledJobCard(job: ScheduledJob) {
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
        }
    }
}

private fun ScheduledJob.displayStatus(): String = when (enabled) {
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
