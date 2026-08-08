package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsState
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.navigation.SessionDetailRoute
import com.unsupportedpastels.hermesandroid.navigation.SessionListRoute
import com.unsupportedpastels.hermesandroid.navigation.ServerSettingsRoute
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import kotlinx.coroutines.launch

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
    serverSettingsState: ServerSettingsState = ServerSettingsState.Ready(null),
    onSaveServerOrigin: suspend (ServerOrigin) -> Result<Unit> = { Result.success(Unit) },
    onSignIn: () -> Unit = {},
    onOpenSession: (DurableSessionId) -> Unit = {},
    onSendMessage: (DurableSessionId, String) -> Unit = { _, _ -> },
) {
    val sessions = snapshot.durableSessions
    val serverOrigin = (serverSettingsState as? ServerSettingsState.Ready)?.serverOrigin
    var observedServerOrigin by remember { mutableStateOf(serverOrigin) }
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
    val openServerSettings = {
        if (backStack.lastOrNull() is SessionDetailRoute ||
            backStack.lastOrNull() is ServerSettingsRoute
        ) {
            backStack.removeLastOrNull()
        }
        backStack.add(ServerSettingsRoute)
        Unit
    }
    LaunchedEffect(serverOrigin) {
        if (observedServerOrigin != serverOrigin && backStack.lastOrNull() is SessionDetailRoute) {
            backStack.removeLastOrNull()
        }
        observedServerOrigin = serverOrigin
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
                    snapshot = snapshot,
                    serverSettingsState = serverSettingsState,
                    onConfigureServer = openServerSettings,
                    onSignIn = onSignIn,
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
                    val draftKey = "${serverOrigin?.value.orEmpty()}\u0000${session.id.value}"
                    val chat = snapshot.chatSessions[session.id] ?: ChatSessionSnapshot()
                    LaunchedEffect(session.id) {
                        onOpenSession(session.id)
                    }
                    SessionDetailScreen(
                        session = session,
                        chat = chat,
                        draft = drafts[draftKey].orEmpty(),
                        onDraftChanged = { drafts[draftKey] = it },
                        canSend = snapshot.authenticationState == AuthenticationState.Authenticated,
                        onSend = { text -> onSendMessage(session.id, text) },
                        showBack = !supportsListDetail,
                        onBack = navigateBack,
                    )
                }
            }
            entry<ServerSettingsRoute>(
                metadata = ListDetailSceneStrategy.detailPane(),
            ) {
                ServerSettingsScreen(
                    serverOrigin = serverOrigin,
                    showBack = !supportsListDetail,
                    onBack = navigateBack,
                    onSave = onSaveServerOrigin,
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionListScreen(
    sessions: List<SessionSummary>,
    snapshot: HermesGatewaySnapshot,
    serverSettingsState: ServerSettingsState,
    onConfigureServer: () -> Unit,
    onSignIn: () -> Unit,
    onSessionSelected: (DurableSessionId) -> Unit,
) {
    val connectionState = snapshot.connectionState
    val serverOrigin = (serverSettingsState as? ServerSettingsState.Ready)?.serverOrigin
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Sessions") },
                actions = {
                    TextButton(
                        enabled = serverSettingsState !is ServerSettingsState.Loading,
                        onClick = dropUnlessResumed { onConfigureServer() },
                    ) {
                        Text("Server")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (sessions.isEmpty()) {
            val (title, supportingText) = when {
                serverSettingsState is ServerSettingsState.Loading ->
                    "Loading server settings" to "Reading the saved server origin."
                serverSettingsState is ServerSettingsState.Unavailable ->
                    "Server settings unavailable" to "Open Server to replace the saved origin."
                connectionState == ConnectionState.Connected &&
                    snapshot.authenticationState == AuthenticationState.SignInRequired ->
                    "Server reachable" to
                        "Hermes ${snapshot.serverVersion ?: "unknown"} · Sign in required"
                connectionState == ConnectionState.Connected &&
                    snapshot.authenticationState == AuthenticationState.SigningIn ->
                    "Signing in to Hermes" to "Complete sign-in in your browser"
                connectionState == ConnectionState.Disconnected && serverOrigin == null ->
                    "No server configured" to "Add the HTTPS origin of your Hermes server."
                connectionState == ConnectionState.Disconnected ->
                    "Server configured" to serverOrigin?.value.orEmpty()
                connectionState == ConnectionState.Connecting ->
                    "Connecting" to "Waiting for the Hermes server."
                connectionState == ConnectionState.Connected ->
                    "No saved sessions" to "This server has no durable transcripts yet."
                else ->
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
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        supportingText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    snapshot.connectionError?.let { connectionError ->
                        Text(
                            connectionError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (
                        snapshot.authenticationState == AuthenticationState.SignInRequired &&
                        snapshot.nativeOAuthSupported &&
                        snapshot.authProviders.any { it.name == "nous" }
                    ) {
                        Button(onClick = dropUnlessResumed { onSignIn() }) {
                            Text("Sign in with Nous")
                        }
                    } else if (
                        connectionState == ConnectionState.Disconnected &&
                        serverSettingsState is ServerSettingsState.Ready
                    ) {
                        Button(onClick = dropUnlessResumed { onConfigureServer() }) {
                            Text(if (serverOrigin == null) "Configure server" else "Edit server")
                        }
                    }
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
internal fun ServerSettingsScreen(
    serverOrigin: ServerOrigin?,
    showBack: Boolean,
    onBack: () -> Unit,
    onSave: suspend (ServerOrigin) -> Result<Unit>,
) {
    var value by rememberSaveable(serverOrigin?.value) {
        mutableStateOf(serverOrigin?.value.orEmpty())
    }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by rememberSaveable { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val parsedOrigin = remember(value) {
        runCatching { ServerOrigin.parse(value) }.getOrNull()
    }
    val validationMessage = remember(value) {
        if (value.isBlank()) {
            null
        } else {
            runCatching { ServerOrigin.parse(value) }
                .exceptionOrNull()
                ?.message
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Hermes server") },
                navigationIcon = {
                    if (showBack) {
                        TextButton(
                            enabled = !isSaving,
                            onClick = dropUnlessResumed { onBack() },
                        ) {
                            Text("Back")
                        }
                    }
                },
                actions = {
                    if (!showBack) {
                        TextButton(
                            enabled = !isSaving,
                            onClick = dropUnlessResumed { onBack() },
                        ) {
                            Text("Close")
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
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Enter the public HTTPS origin of your unchanged Hermes Serve instance.",
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    saveError = null
                },
                label = { Text("Server origin") },
                supportingText = {
                    Text(
                        validationMessage
                            ?: "HTTPS origin only — no path, credentials, query, or ticket.",
                    )
                },
                isError = validationMessage != null,
                enabled = !isSaving,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            saveError?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = parsedOrigin != null && !isSaving,
                    onClick = {
                        val origin = parsedOrigin ?: return@Button
                        coroutineScope.launch {
                            isSaving = true
                            saveError = null
                            val result = onSave(origin)
                            isSaving = false
                            if (result.isSuccess) {
                                onBack()
                            } else {
                                saveError = "Could not save server. Try again."
                            }
                        }
                    },
                ) {
                    Text(if (isSaving) "Saving…" else "Save")
                }
                TextButton(
                    enabled = !isSaving,
                    onClick = dropUnlessResumed { onBack() },
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetailScreen(
    session: SessionSummary,
    chat: ChatSessionSnapshot,
    draft: String,
    onDraftChanged: (String) -> Unit,
    canSend: Boolean,
    onSend: (String) -> Unit,
    showBack: Boolean,
    onBack: () -> Unit,
) {
    val transcriptListState = rememberLazyListState(
        initialFirstVisibleItemIndex = chat.messages.lastIndex.coerceAtLeast(0),
    )
    LaunchedEffect(session.id, chat.messages.size, chat.messages.lastOrNull()?.text?.length) {
        if (chat.messages.isNotEmpty()) {
            transcriptListState.scrollToItem(chat.messages.lastIndex)
        }
    }

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
            when {
                chat.isLoading && chat.messages.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Loading transcript…")
                    }
                }
                chat.messages.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No messages yet")
                    }
                }
                else -> {
                    LazyColumn(
                        state = transcriptListState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(chat.messages) { message ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    when (message.role) {
                                        ChatMessageRole.User -> "You"
                                        ChatMessageRole.Assistant -> "Hermes"
                                        ChatMessageRole.System -> "System"
                                        ChatMessageRole.Tool -> "Tool"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                val renderedText = message.text.ifEmpty {
                                    if (message.isStreaming) "…" else ""
                                }
                                MarkdownMessage(renderedText)
                            }
                        }
                    }
                }
            }
            chat.error?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (chat.isSending) {
                Text("Hermes is responding…", style = MaterialTheme.typography.bodyMedium)
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
                    enabled = !chat.isSending,
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 5,
                )
                Button(
                    onClick = {
                        val message = draft.trim()
                        onSend(message)
                        onDraftChanged("")
                    },
                    enabled = canSend && !chat.isSending && draft.isNotBlank(),
                ) {
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
