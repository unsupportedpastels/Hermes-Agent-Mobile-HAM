package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.gateway.CronJob
import com.unsupportedpastels.hermesandroid.gateway.CronJobsState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.OperationalHealth
import com.unsupportedpastels.hermesandroid.gateway.OperationalPressure
import com.unsupportedpastels.hermesandroid.gateway.RuntimeAccess
import com.unsupportedpastels.hermesandroid.gateway.lastGoodOrNull
import com.unsupportedpastels.hermesandroid.gateway.requiresOperationalAttention

internal fun workingHereCount(snapshot: HermesGatewaySnapshot): Int = snapshot.chatSessions.count { (sessionId, chat) ->
    chat.isSending && snapshot.activeRuntimes.any { runtime ->
        runtime.durableSessionId == sessionId && runtime.access == RuntimeAccess.Controller
    }
}

internal fun processLocalSubagentCount(snapshot: HermesGatewaySnapshot): Int? =
    if (snapshot.delegationStatusAvailable || snapshot.delegationStatus.active.isNotEmpty()) {
        snapshot.delegationStatus.active.size
    } else {
        null
    }

internal fun operationalAttentionCount(snapshot: HermesGatewaySnapshot): Int {
    val status = snapshot.operationalStatusState.lastGoodOrNull()?.status
    val pressureAttention = status?.let {
        listOf(it.memoryPressure, it.diskPressure).count { pressure -> pressure != OperationalPressure.Ok }
    } ?: 0
    val cronAttention = (snapshot.cronJobsState as? CronJobsState.Ready)
        ?.takeIf { it.profile == snapshot.selectedProfile }
        ?.jobs
        ?.count(CronJob::requiresOperationalAttention)
        ?: 0
    return pressureAttention + cronAttention
}

@Composable
internal fun OperationalOverviewItem(
    snapshot: HermesGatewaySnapshot,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val lastGood = snapshot.operationalStatusState.lastGoodOrNull()
    val status = lastGood?.status
    val statusLabel = when {
        status == null -> "Unavailable"
        status.overall == OperationalHealth.Ok &&
            status.memoryPressure == OperationalPressure.Ok &&
            status.diskPressure == OperationalPressure.Ok -> "Operational"
        else -> "Needs attention"
    }
    val statusColor = when (statusLabel) {
        "Operational" -> MaterialTheme.colorScheme.primary
        "Needs attention" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val processLocalSubagents = processLocalSubagentCount(snapshot)
    val attentionCount = operationalAttentionCount(snapshot)
    val transient = snapshot.operationalStatusState is com.unsupportedpastels.hermesandroid.gateway.OperationalStatusState.TransientError

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier
            .fillMaxWidth()
            .testTag("Operational overview")
            .semantics {
                contentDescription = "Operational overview"
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }
            .clickable { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Operational overview", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse operational overview" else "Expand operational overview",
                    modifier = Modifier.size(20.dp),
                )
            }
            OverviewValueRow("Working here", workingHereCount(snapshot).toString())
            OverviewValueRow(
                "Process-local subagents",
                processLocalSubagents?.toString() ?: "Unavailable",
            )
            if (expanded) {
                OverviewValueRow("Status", statusLabel, statusColor)
                if (transient && status != null) {
                    Text(
                        "Last good status shown; refresh is temporarily unavailable",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OverviewValueRow(
                    "Memory pressure",
                    status?.let { it.memoryPressure.operationalLabel() } ?: "Unavailable",
                )
                OverviewValueRow(
                    "Disk pressure",
                    status?.let { it.diskPressure.operationalLabel() } ?: "Unavailable",
                )
                if (attentionCount > 0) {
                    OverviewValueRow("Needs attention", attentionCount.toString(), MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun OverviewValueRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(width = 8.dp, height = 1.dp))
        Text(
            value,
            modifier = Modifier.padding(start = 4.dp),
            maxLines = 1,
            softWrap = false,
            color = valueColor,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private fun OperationalPressure?.operationalLabel(): String = when (this) {
    OperationalPressure.Ok -> "OK"
    OperationalPressure.Warning -> "Warning"
    OperationalPressure.Critical -> "Critical"
    OperationalPressure.Unknown, null -> "Unavailable"
}
