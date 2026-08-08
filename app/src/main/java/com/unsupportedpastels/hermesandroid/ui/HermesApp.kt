package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.navigation.SessionDetailRoute
import com.unsupportedpastels.hermesandroid.navigation.SessionListRoute
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme

private val DraftsSaver = Saver<SnapshotStateMap<String, String>, ArrayList<String>>(
    save = { drafts ->
        ArrayList(drafts.entries.flatMap { (sessionId, draft) -> listOf(sessionId, draft) })
    },
    restore = { saved ->
        mutableStateMapOf<String, String>().apply {
            saved.chunked(2).forEach { pair ->
                if (pair.size == 2) put(pair[0], pair[1])
            }
        }
    },
)

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun HermesApp(
    snapshot: HermesGatewaySnapshot,
    modifier: Modifier = Modifier,
) {
    val sessions = snapshot.durableSessions
    val backStack = rememberNavBackStack(SessionListRoute)
    val drafts = rememberSaveable(saver = DraftsSaver) { mutableStateMapOf() }
    val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()
    val supportsListDetail =
        windowAdaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
            WIDTH_DP_MEDIUM_LOWER_BOUND,
        )
    val directive = remember(windowAdaptiveInfo, supportsListDetail) {
        calculatePaneScaffoldDirective(windowAdaptiveInfo)
            .copy(
                maxHorizontalPartitions = if (supportsListDetail) 2 else 1,
                horizontalPartitionSpacerSize = 0.dp,
            )
    }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(directive = directive)
    val navigateBack = {
        if (backStack.size > 1) backStack.removeLastOrNull()
        Unit
    }

    NavDisplay(
        backStack = backStack,
        modifier = modifier.fillMaxSize(),
        onBack = navigateBack,
        sceneStrategies = listOf(listDetailStrategy),
        entryProvider = entryProvider {
            entry<SessionListRoute>(
                metadata = ListDetailSceneStrategy.listPane(
                    detailPlaceholder = { SessionPlaceholder() },
                ),
            ) {
                SessionListScreen(
                    sessions = sessions,
                    connectionState = snapshot.connectionState,
                    onSessionSelected = { sessionId ->
                        if (backStack.lastOrNull() is SessionDetailRoute) {
                            backStack.removeLastOrNull()
                        }
                        backStack.add(SessionDetailRoute(sessionId))
                    },
                )
            }
            entry<SessionDetailRoute>(
                metadata = ListDetailSceneStrategy.detailPane(),
            ) { route ->
                val session = sessions.firstOrNull { it.id == route.durableSessionId }
                if (session == null) {
                    MissingSessionScreen()
                } else {
                    SessionDetailScreen(
                        session = session,
                        draft = drafts[session.id.value].orEmpty(),
                        onDraftChanged = { drafts[session.id.value] = it },
                        showBack = !supportsListDetail,
                        onBack = navigateBack,
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionListScreen(
    sessions: List<SessionSummary>,
    connectionState: ConnectionState,
    onSessionSelected: (DurableSessionId) -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopAppBar(title = { Text("Sessions") }) },
    ) { innerPadding ->
        if (sessions.isEmpty()) {
            val (title, supportingText) = when (connectionState) {
                ConnectionState.Disconnected ->
                    "No server configured" to "Connection setup is the next milestone."
                ConnectionState.Connecting ->
                    "Connecting" to "Waiting for the Hermes server."
                ConnectionState.Connected ->
                    "No saved sessions" to "This server has no durable transcripts yet."
                ConnectionState.Recovering ->
                    "Reconnecting" to "Reconciling sessions with the Hermes server."
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        supportingText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
            ) {
                items(sessions, key = { it.id.value }) { session ->
                    ListItem(
                        headlineContent = { Text(session.title) },
                        supportingContent = { Text("Durable session") },
                        modifier = Modifier.clickable(
                            onClick = dropUnlessResumed { onSessionSelected(session.id) },
                        ),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetailScreen(
    session: SessionSummary,
    draft: String,
    onDraftChanged: (String) -> Unit,
    showBack: Boolean,
    onBack: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(session.title) },
                navigationIcon = {
                    if (showBack) {
                        TextButton(onClick = dropUnlessResumed { onBack() }) {
                            Text("Back")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text("No messages yet", style = MaterialTheme.typography.bodyLarge)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChanged,
                    label = { Text("Message") },
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 5,
                )
                Button(onClick = {}, enabled = false) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
private fun SessionPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Select a session", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MissingSessionScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Session is no longer available")
    }
}

private val previewSessions = listOf(
    SessionSummary(DurableSessionId("stored-1"), "Android client planning"),
    SessionSummary(DurableSessionId("stored-2"), "Foldable UI review"),
    SessionSummary(DurableSessionId("stored-3"), "Hermes protocol notes"),
)

@Preview(name = "Cover screen", widthDp = 400, heightDp = 900, showBackground = true)
@Preview(name = "Unfolded", widthDp = 900, heightDp = 1000, showBackground = true)
@Composable
private fun HermesAppPreview() {
    HermesAndroidTheme {
        HermesApp(
            snapshot = HermesGatewaySnapshot(
                connectionState = ConnectionState.Connected,
                durableSessions = previewSessions,
            ),
        )
    }
}
