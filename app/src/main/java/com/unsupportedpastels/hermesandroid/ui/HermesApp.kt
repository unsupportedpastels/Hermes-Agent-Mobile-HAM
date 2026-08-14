package com.unsupportedpastels.hermesandroid.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.PaneExpansionStateKey
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.rememberPaneExpansionState
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import com.unsupportedpastels.hermesandroid.app.ApprovalInteraction
import com.unsupportedpastels.hermesandroid.app.ClarificationInteraction
import com.unsupportedpastels.hermesandroid.app.RunEventState
import com.unsupportedpastels.hermesandroid.app.RunInteractionLifecycle
import com.unsupportedpastels.hermesandroid.app.RunStatus
import com.unsupportedpastels.hermesandroid.app.RunToolRow
import com.unsupportedpastels.hermesandroid.app.RunToolState
import com.unsupportedpastels.hermesandroid.app.UnsupportedBlockingInteraction
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.DelegatedSubagent
import com.unsupportedpastels.hermesandroid.app.DelegationStatus
import com.unsupportedpastels.hermesandroid.app.ProjectLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSessionLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.app.validHostFolderName
import com.unsupportedpastels.hermesandroid.app.validProjectWorkspacePath
import com.unsupportedpastels.hermesandroid.attachment.AttachmentPolicy
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.ModelPickerState
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsState
import com.unsupportedpastels.hermesandroid.connection.SlashCompletionState
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ActiveRuntimeSession
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.ContextBreakdownCategory
import com.unsupportedpastels.hermesandroid.gateway.CronJobAction
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryListing
import com.unsupportedpastels.hermesandroid.gateway.ModelProviderOption
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection
import com.unsupportedpastels.hermesandroid.gateway.ModelSwitchResult
import com.unsupportedpastels.hermesandroid.gateway.RuntimeAccess
import com.unsupportedpastels.hermesandroid.gateway.SlashCompletionItem
import com.unsupportedpastels.hermesandroid.navigation.HomeRoute
import com.unsupportedpastels.hermesandroid.navigation.ProjectRoute
import com.unsupportedpastels.hermesandroid.navigation.SessionDetailRoute
import com.unsupportedpastels.hermesandroid.navigation.ServerSettingsRoute
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import com.unsupportedpastels.hermesandroid.theme.LocalHermesSemanticColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.net.URI
import java.util.Locale

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

internal val SessionStatusPulseAlpha = SemanticsPropertyKey<Float>("SessionStatusPulseAlpha")
private var SemanticsPropertyReceiver.sessionStatusPulseAlpha by SessionStatusPulseAlpha

private const val SESSION_STATUS_PULSE_MILLIS = 900

internal fun sessionStatusPulseAlphaAt(playTimeMillis: Long): Float {
    val boundedTime = playTimeMillis.coerceAtLeast(0L) % (SESSION_STATUS_PULSE_MILLIS * 2L)
    val phase = if (boundedTime <= SESSION_STATUS_PULSE_MILLIS) {
        boundedTime.toFloat() / SESSION_STATUS_PULSE_MILLIS
    } else {
        (SESSION_STATUS_PULSE_MILLIS * 2L - boundedTime).toFloat() / SESSION_STATUS_PULSE_MILLIS
    }
    val easedPhase = FastOutSlowInEasing.transform(phase)
    return 1f + (0.35f - 1f) * easedPhase
}

private const val PROJECT_DOCK_MIN_WIDTH_DP = 800

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun HermesApp(
    snapshot: HermesGatewaySnapshot,
    modifier: Modifier = Modifier,
    initialRoute: NavKey = HomeRoute,
    requestedSessionId: DurableSessionId? = null,
    requestedSessionRequestId: Long? = null,
    onVisibleSessionChanged: (DurableSessionId?) -> Unit = {},
    initialHomeSearchOpen: Boolean = false,
    initialProjectDockCollapsed: Boolean = false,
    persistedProjectDockState: ProjectDockState? = null,
    onProjectDockStateChanged: (ProjectDockState) -> Unit = {},
    projectSessionPaneProportion: Float? = DEFAULT_PROJECT_SESSION_PANE_PROPORTION,
    onProjectSessionPaneProportionChanged: (Float) -> Unit = {},
    initialProjectCreatorOpen: Boolean = false,
    initialProjectCreatorListing: HostDirectoryListing? = null,
    serverSettingsState: ServerSettingsState = ServerSettingsState.Ready(null),
    onSaveServerOrigin: suspend (ServerOrigin) -> Result<Unit> = { Result.success(Unit) },
    onLoadManagementSettings: (String) -> Unit = {},
    onSetProfileDefaultModel: suspend (ModelSelection, Boolean) -> ModelSwitchResult = { _, _ ->
        ModelSwitchResult(accepted = false)
    },
    onLogout: suspend () -> Unit = {},
    onSignIn: () -> Unit = {},
    onOpenProject: (ProjectId) -> Unit = {},
    onOpenSession: (DurableSessionId) -> Unit = {},
    onLoadSessionInsights: (DurableSessionId) -> Unit = {},
    onCompressSession: (DurableSessionId, String?) -> Unit = { _, _ -> },
    onUndoSession: (DurableSessionId) -> Unit = {},
    onBranchSession: (DurableSessionId, Int?, String?) -> Unit = { _, _, _ -> },
    onRefreshCronJobs: () -> Unit = {},
    onCronJobAction: (String, CronJobAction) -> Unit = { _, _ -> },
    isHomeRefreshing: Boolean = false,
    onRefreshHome: () -> Unit = {},
    onRenameSession: suspend (DurableSessionId, String) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    onSetSessionPinned: suspend (DurableSessionId, Boolean) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    onSetSessionArchived: suspend (DurableSessionId, Boolean) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    onDeleteSession: suspend (DurableSessionId) -> Result<Unit> = { Result.success(Unit) },
    onSearchTranscripts: (String) -> Unit = {},
    onSendMessage: (DurableSessionId, String) -> Unit = { _, _ -> },
    onReasoningSelected: (DurableSessionId, String) -> Unit = { _, _ -> },
    onClarificationResponse: (DurableSessionId, String, String) -> Unit = { _, _, _ -> },
    onApprovalResponse: (DurableSessionId, String, Boolean) -> Unit = { _, _, _ -> },
    onStopSession: (DurableSessionId) -> Unit = {},
    onSteerSession: (DurableSessionId, String) -> Unit = { _, _ -> },
    onSetDelegationPaused: (DurableSessionId, Boolean) -> Unit = { _, _ -> },
    onSteerSubagent: (DurableSessionId, String, String) -> Unit = { _, _, _ -> },
    onInterruptSubagent: (DurableSessionId, String) -> Unit = { _, _ -> },
    onCreateSession: () -> DurableSessionId? = { null },
    onCreateProjectSession: (ProjectId) -> DurableSessionId? = { null },
    onLoadHostDirectories: suspend (String?) -> Result<HostDirectoryListing> = {
        Result.failure(UnsupportedOperationException("Host folder browsing is unavailable"))
    },
    onCreateHostDirectory: suspend (String, String) -> Result<HostDirectoryListing> = { _, _ ->
        Result.failure(UnsupportedOperationException("Host folder creation is unavailable"))
    },
    onCreateProject: suspend (String, String) -> Result<ProjectSummary> = { _, _ ->
        Result.failure(UnsupportedOperationException("Project creation is unavailable"))
    },
    onLoadManagedImage: suspend (String) -> Result<ByteArray> = {
        Result.failure(UnsupportedOperationException("Managed images are unavailable"))
    },
    modelPickerState: ModelPickerState = ModelPickerState.Closed,
    onOpenModelPicker: (DurableSessionId) -> Unit = {},
    onDismissModelPicker: () -> Unit = {},
    onRetryModelPicker: () -> Unit = {},
    onModelSelected: (ModelSelection) -> Unit = {},
    onConfirmModelSelection: () -> Unit = {},
    slashCompletions: Map<DurableSessionId, SlashCompletionState> = emptyMap(),
    onSlashCompletionRequested: (DurableSessionId, String) -> Unit = { _, _ -> },
    attachments: Map<DurableSessionId, List<ComposerAttachment>> = emptyMap(),
    onAddAttachments: (DurableSessionId, List<ComposerAttachment>) -> List<String> = { _, _ -> emptyList() },
    onRemoveAttachment: (DurableSessionId, String) -> Unit = { _, _ -> },
    projectIcons: Map<ProjectId, ProjectIconId> = emptyMap(),
    onSaveProjectIcon: suspend (ProjectId, ProjectIconId) -> Result<Unit> = { _, _ ->
        Result.success(Unit)
    },
) {
    val durableSessions = snapshot.durableSessions
    val sessions = buildList {
        addAll(durableSessions)
        snapshot.projectSessions.values.flatten().forEach { projectSession ->
            if (none { it.id == projectSession.id }) add(projectSession)
        }
        snapshot.transcriptSearchResults.forEach { result ->
            if (none { it.id == result.sessionId }) {
                add(
                    SessionSummary(
                        id = result.sessionId,
                        title = result.title,
                        preview = result.snippet,
                        profile = snapshot.selectedProfile,
                    ),
                )
            }
        }
    }
    val loadedProjectState = snapshot.projectState as? ProjectLoadState.Loaded
    val projects = loadedProjectState?.projects ?: snapshot.projects
    val scopedSessionIds = loadedProjectState?.scopedSessionIds.orEmpty()
    val recentSessions = if (loadedProjectState == null) {
        sessions
    } else {
        sessions.filterNot { it.id in scopedSessionIds }
    }
    val serverOrigin = (serverSettingsState as? ServerSettingsState.Ready)?.serverOrigin
    var observedServerOrigin by remember { mutableStateOf(serverOrigin) }
    val initialBackStack = remember(initialRoute, sessions) {
        when (initialRoute) {
            HomeRoute -> arrayOf<NavKey>(HomeRoute)
            is SessionDetailRoute -> {
                val projectId = sessions
                    .firstOrNull { it.id == initialRoute.durableSessionId }
                    ?.projectId
                if (projectId == null) {
                    arrayOf(HomeRoute, initialRoute)
                } else {
                    arrayOf(HomeRoute, ProjectRoute(projectId), initialRoute)
                }
            }
            else -> arrayOf(HomeRoute, initialRoute)
        }
    }
    val backStack = rememberNavBackStack(*initialBackStack)
    val drafts = rememberSaveable(saver = DraftsSaver) { mutableStateMapOf() }
    val observedSendingSessions = remember { mutableStateMapOf<String, Boolean>() }
    val unreadCompletedSessions = remember { mutableStateMapOf<String, Boolean>() }
    var projectDockState by rememberSaveable {
        mutableStateOf(
            if (initialProjectDockCollapsed) ProjectDockState.Collapsed else ProjectDockState.Expanded,
        )
    }
    LaunchedEffect(persistedProjectDockState) {
        persistedProjectDockState?.let { projectDockState = it }
    }
    var workspaceWidthPx by remember { mutableStateOf(0) }
    var measuredProjectSessionPaneProportion by remember { mutableStateOf<Float?>(null) }
    var iconPickerProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var projectCreatorOpen by rememberSaveable { mutableStateOf(initialProjectCreatorOpen) }
    val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()
    val supportsListDetail =
        windowAdaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
            WIDTH_DP_MEDIUM_LOWER_BOUND,
        )
    val supportsNavigationRail = LocalConfiguration.current.screenWidthDp >= PROJECT_DOCK_MIN_WIDTH_DP
    val paneExpansionState = rememberPaneExpansionState(PaneExpansionStateKey.Default)
    LaunchedEffect(projectSessionPaneProportion, supportsListDetail) {
        if (supportsListDetail && projectSessionPaneProportion != null) {
            paneExpansionState.setFirstPaneProportion(
                projectSessionPaneProportion.coerceIn(
                    MIN_PROJECT_SESSION_PANE_PROPORTION,
                    MAX_PROJECT_SESSION_PANE_PROPORTION,
                ),
            )
        }
    }
    LaunchedEffect(measuredProjectSessionPaneProportion, projectSessionPaneProportion) {
        val measured = measuredProjectSessionPaneProportion ?: return@LaunchedEffect
        val persisted = projectSessionPaneProportion ?: return@LaunchedEffect
        if (kotlin.math.abs(measured - persisted) >= 0.005f) {
            delay(400)
            onProjectSessionPaneProportionChanged(measured)
        }
    }
    val recordProjectSessionPaneWidth = { width: Int ->
        if (supportsListDetail && workspaceWidthPx > 0 && width > 0) {
            measuredProjectSessionPaneProportion = (width.toFloat() / workspaceWidthPx)
                .coerceIn(
                    MIN_PROJECT_SESSION_PANE_PROPORTION,
                    MAX_PROJECT_SESSION_PANE_PROPORTION,
                )
        }
        Unit
    }
    val directive = remember(windowAdaptiveInfo, supportsListDetail) {
        calculatePaneScaffoldDirective(windowAdaptiveInfo)
            .copy(
                maxHorizontalPartitions = if (supportsListDetail) 2 else 1,
                horizontalPartitionSpacerSize = 0.dp,
            )
    }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(
        directive = directive,
        paneExpansionDragHandle = { state ->
            val interactionSource = remember { MutableInteractionSource() }
            VerticalDragHandle(
                modifier = Modifier
                    .testTag("Project session pane resize handle")
                    .paneExpansionDraggable(
                        state = state,
                        minTouchTargetSize = LocalMinimumInteractiveComponentSize.current,
                        interactionSource = interactionSource,
                    ),
                interactionSource = interactionSource,
            )
        },
        paneExpansionState = paneExpansionState,
    )

    val navigateBack = {
        if (backStack.size > 1) backStack.removeLastOrNull()
        Unit
    }
    val navigateToProject = { projectId: ProjectId ->
        while (backStack.size > 1 && backStack.lastOrNull() !is HomeRoute) {
            backStack.removeLastOrNull()
        }
        backStack.add(ProjectRoute(projectId))
        onOpenProject(projectId)
        Unit
    }
    val navigateToSession = { sessionId: DurableSessionId ->
        unreadCompletedSessions.remove(sessionId.value)
        if (backStack.lastOrNull() is SessionDetailRoute) {
            backStack.removeLastOrNull()
        }
        backStack.add(SessionDetailRoute(sessionId))
        Unit
    }
    var handledRequestedSessionKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(requestedSessionRequestId, requestedSessionId, sessions) {
        val sessionId = requestedSessionId
        val requestKey = requestedSessionRequestId
            ?.let { "request:$it" }
            ?: sessionId?.let { "session:${it.value}" }
        if (
            sessionId != null &&
            requestKey != null &&
            requestKey != handledRequestedSessionKey &&
            sessions.any { it.id == sessionId }
        ) {
            handledRequestedSessionKey = requestKey
            navigateToSession(sessionId)
        }
    }
    var handledBranchId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(snapshot.lastBranchedSessionId, sessions) {
        val branchId = snapshot.lastBranchedSessionId
        if (
            branchId != null &&
            branchId.value != handledBranchId &&
            sessions.any { it.id == branchId }
        ) {
            handledBranchId = branchId.value
            navigateToSession(branchId)
        }
    }
    val openServerSettings = {
        while (backStack.size > 1) backStack.removeLastOrNull()
        backStack.add(ServerSettingsRoute)
        Unit
    }
    val navigateHome = {
        while (backStack.size > 1) backStack.removeLastOrNull()
        Unit
    }
    LaunchedEffect(serverOrigin) {
        if (observedServerOrigin != serverOrigin &&
            (backStack.lastOrNull() is SessionDetailRoute || backStack.lastOrNull() is ProjectRoute)
        ) {
            backStack.removeLastOrNull()
        }
        observedServerOrigin = serverOrigin
    }
    val selectedProjectId = when (val currentRoute = backStack.lastOrNull()) {
        is ProjectRoute -> currentRoute.projectId
        is SessionDetailRoute -> sessions
            .firstOrNull { it.id == currentRoute.durableSessionId }
            ?.projectId
        else -> null
    }
    val selectedSessionId = (backStack.lastOrNull() as? SessionDetailRoute)?.durableSessionId
    LaunchedEffect(selectedSessionId) {
        onVisibleSessionChanged(selectedSessionId)
    }
    DisposableEffect(Unit) {
        onDispose { onVisibleSessionChanged(null) }
    }
    val workingSessionIds = buildSet {
        snapshot.chatSessions
            .filterValues(ChatSessionSnapshot::isSending)
            .keys
            .forEach(::add)
    }
    LaunchedEffect(workingSessionIds, selectedSessionId) {
        val sessionIds = observedSendingSessions.keys
            .map(::DurableSessionId)
            .toSet() + workingSessionIds
        sessionIds.forEach { sessionId ->
            val key = sessionId.value
            val isSending = sessionId in workingSessionIds
            if (observedSendingSessions[key] == true && !isSending && selectedSessionId != sessionId) {
                unreadCompletedSessions[key] = true
            }
            if (selectedSessionId == sessionId) unreadCompletedSessions.remove(key)
            observedSendingSessions[key] = isSending
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (supportsNavigationRail && projectDockState != ProjectDockState.Hidden) {
                ProjectDock(
                    state = projectDockState,
                    projects = projects,
                    selectedProjectId = selectedProjectId,
                    projectIcons = projectIcons,
                    canStartNewTask = snapshot.authenticationState == AuthenticationState.Authenticated,
                    settingsSelected = backStack.lastOrNull() is ServerSettingsRoute,
                    onProjectSelected = navigateToProject,
                    onChooseProjectIcon = { iconPickerProjectId = it.value },
                    onCreateProject = { projectCreatorOpen = true },
                    onNewTask = {
                        val newSessionId = if (selectedProjectId != null) {
                            onCreateProjectSession(selectedProjectId)
                        } else {
                            onCreateSession()
                        }
                        if (newSessionId != null) navigateToSession(newSessionId)
                    },
                    onSettings = openServerSettings,
                    onExpand = {
                        projectDockState = ProjectDockState.Expanded
                        onProjectDockStateChanged(ProjectDockState.Expanded)
                    },
                    onCollapse = {
                        projectDockState = ProjectDockState.Collapsed
                        onProjectDockStateChanged(ProjectDockState.Collapsed)
                    },
                    onHide = {
                        projectDockState = ProjectDockState.Hidden
                        onProjectDockStateChanged(ProjectDockState.Hidden)
                    },
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .onSizeChanged { workspaceWidthPx = it.width },
            ) {
                NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = navigateBack,
        sceneStrategies = listOf(listDetailStrategy),
        entryProvider = entryProvider {
            entry<HomeRoute>(
                metadata = ListDetailSceneStrategy.listPane(
                    detailPlaceholder = { SessionPlaceholder() },
                ) + ListDetailSceneStrategy.preferredPaneSize(width = 0.4f),
            ) {
                SessionListScreen(
                    projects = projects,
                    sessions = recentSessions,
                    modifier = Modifier.onSizeChanged {
                        recordProjectSessionPaneWidth(it.width)
                    },
                    projectState = snapshot.projectState,
                    snapshot = snapshot,
                    serverSettingsState = serverSettingsState,
                    initialSearchOpen = initialHomeSearchOpen,
                    showDockOwnedActions = !supportsNavigationRail,
                    isRefreshing = isHomeRefreshing,
                    onRefresh = onRefreshHome,
                    onConfigureServer = openServerSettings,
                    onSignIn = onSignIn,
                    onProjectSelected = navigateToProject,
                    onSessionSelected = navigateToSession,
                    onRenameSession = onRenameSession,
                    onSetSessionPinned = onSetSessionPinned,
                    onSetSessionArchived = onSetSessionArchived,
                    onDeleteSession = onDeleteSession,
                    onSearchTranscripts = onSearchTranscripts,
                    onCreateProject = { projectCreatorOpen = true },
                    onNewSession = {
                        val newSessionId = onCreateSession()
                        if (newSessionId != null) navigateToSession(newSessionId)
                    },
                )
            }
            entry<ProjectRoute>(
                metadata = ListDetailSceneStrategy.listPane(
                    detailPlaceholder = { SessionPlaceholder() },
                ) + ListDetailSceneStrategy.preferredPaneSize(width = 0.4f),
            ) { route ->
                val project = projects.firstOrNull { it.id == route.projectId }
                if (project == null) {
                    MissingProjectScreen()
                } else {
                    ProjectDetailScreen(
                        project = project,
                        state = snapshot.projectSessionStates[route.projectId],
                        sessions = snapshot.projectSessions[route.projectId].orEmpty(),
                        workingSessionIds = workingSessionIds,
                        unreadCompletedSessionIds = unreadCompletedSessions
                            .filterValues { it }
                            .keys
                            .mapTo(mutableSetOf(), ::DurableSessionId),
                        modifier = Modifier.onSizeChanged {
                            recordProjectSessionPaneWidth(it.width)
                        },
                        showBack = !supportsListDetail,
                        showNewTaskAction = !supportsNavigationRail,
                        onBack = navigateBack,
                        onSessionSelected = navigateToSession,
                        onNewTask = {
                            val newSessionId = onCreateProjectSession(project.id)
                            if (newSessionId != null) navigateToSession(newSessionId)
                        },
                    )
                }
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
                    val hasControllerRuntime = snapshot.activeRuntimes.any { runtime ->
                        runtime.durableSessionId == session.id && runtime.access == RuntimeAccess.Controller
                    }
                    val projectDraftMissingWorkspace = session.isLocalDraft &&
                        session.projectId != null &&
                        validProjectWorkspacePath(session.workspacePath) == null
                    val latestUserMessageIndex = chat.messages
                        .indexOfLast { it.role == ChatMessageRole.User }
                    val hasAcceptedRunActivity = chat.runState.status != null ||
                        chat.runState.tools.isNotEmpty() ||
                        chat.runState.clarification != null ||
                        chat.runState.approval != null ||
                        chat.runState.unsupportedBlocking != null ||
                        chat.messages
                            .drop(latestUserMessageIndex + 1)
                            .any { it.role == ChatMessageRole.Assistant }
                    val latestAcceptedUserText = chat.messages
                        .getOrNull(latestUserMessageIndex)
                        ?.text
                        ?.takeIf { hasAcceptedRunActivity }
                    LaunchedEffect(session.id, latestAcceptedUserText) {
                        val currentDraft = drafts[draftKey].orEmpty()
                        if (
                            latestAcceptedUserText != null &&
                            currentDraft.trim() == latestAcceptedUserText.trim()
                        ) {
                            drafts[draftKey] = ""
                            onSlashCompletionRequested(session.id, "")
                        }
                    }
                    LaunchedEffect(session.id) {
                        onOpenSession(session.id)
                    }
                    SessionDetailScreen(
                        session = session,
                        chat = chat,
                        draft = drafts[draftKey].orEmpty(),
                        onDraftChanged = { updated ->
                            drafts[draftKey] = updated
                            onSlashCompletionRequested(session.id, updated)
                        },
                        canSend = snapshot.authenticationState == AuthenticationState.Authenticated &&
                            !projectDraftMissingWorkspace,
                        attachments = attachments[session.id].orEmpty(),
                        onAddAttachments = { candidates -> onAddAttachments(session.id, candidates) },
                        onRemoveAttachment = { attachmentId ->
                            onRemoveAttachment(session.id, attachmentId)
                        },
                        onSend = { text ->
                            onSlashCompletionRequested(session.id, "")
                            onSendMessage(session.id, text)
                        },
                        onReasoningSelected = { effort -> onReasoningSelected(session.id, effort) },
                        onOpenModelPicker = {
                            drafts[draftKey] = ""
                            onSlashCompletionRequested(session.id, "")
                            onOpenModelPicker(session.id)
                        },
                        onLoadSessionInsights = { onLoadSessionInsights(session.id) },
                        maintenanceAvailable = hasControllerRuntime,
                        maintenanceEnabled = hasControllerRuntime &&
                            !chat.isLoading &&
                            !chat.isSending &&
                            !chat.isStopping &&
                            !chat.maintenanceLoading,
                        onCompressSession = { focusTopic ->
                            onCompressSession(session.id, focusTopic)
                        },
                        onUndoSession = { onUndoSession(session.id) },
                        onBranchSession = { count, name ->
                            onBranchSession(session.id, count, name)
                        },
                        onClarificationResponse = { requestId, answer ->
                            onClarificationResponse(session.id, requestId, answer)
                        },
                        onApprovalResponse = { choice, all ->
                            onApprovalResponse(session.id, choice, all)
                        },
                        showStop = chat.isSending && hasControllerRuntime,
                        stopping = chat.isStopping,
                        onStop = { onStopSession(session.id) },
                        steeringAvailable = chat.isSending && hasControllerRuntime,
                        onSteer = { guidance -> onSteerSession(session.id, guidance) },
                        delegationStatus = snapshot.delegationStatus,
                        delegationAvailable = hasControllerRuntime &&
                            snapshot.delegationStatus.active.isNotEmpty(),
                        onSetDelegationPaused = { paused ->
                            onSetDelegationPaused(session.id, paused)
                        },
                        onSteerSubagent = { subagentId, text ->
                            onSteerSubagent(session.id, subagentId, text)
                        },
                        onInterruptSubagent = { subagentId ->
                            onInterruptSubagent(session.id, subagentId)
                        },
                        slashCompletion = slashCompletions[session.id]?.takeIf {
                            it.composerText == drafts[draftKey].orEmpty()
                        },
                        onSlashCompletionSelected = { completion, item ->
                            val updated = applySlashCompletion(
                                drafts[draftKey].orEmpty(),
                                item,
                                completion.replaceFrom,
                            )
                            drafts[draftKey] = updated
                            onSlashCompletionRequested(session.id, updated)
                        },
                        showBack = !supportsListDetail,
                        onBack = navigateBack,
                        onLoadManagedImage = onLoadManagedImage,
                    )
                }
            }
            entry<ServerSettingsRoute>(
                metadata = ListDetailSceneStrategy.detailPane(),
            ) {
                ServerSettingsScreen(
                    serverOrigin = serverOrigin,
                    snapshot = snapshot,
                    showBack = !supportsListDetail,
                    onBack = navigateBack,
                    onSave = onSaveServerOrigin,
                    onLoadManagementSettings = onLoadManagementSettings,
                    onSetProfileDefaultModel = onSetProfileDefaultModel,
                    onRefreshCronJobs = onRefreshCronJobs,
                    onCronJobAction = onCronJobAction,
                    onLogout = onLogout,
                )
            }
            },
                )
            }
        }
        if (supportsNavigationRail && projectDockState == ProjectDockState.Hidden) {
            ProjectDockEdgeTab(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .zIndex(1f),
                onShow = {
                    projectDockState = ProjectDockState.Collapsed
                    onProjectDockStateChanged(ProjectDockState.Collapsed)
                },
            )
        }
    }
    if (projectCreatorOpen) {
        ProjectCreationSheet(
            initialListing = initialProjectCreatorListing,
            onDismiss = { projectCreatorOpen = false },
            onLoadHostDirectories = onLoadHostDirectories,
            onCreateHostDirectory = onCreateHostDirectory,
            onCreateProject = onCreateProject,
            onCreated = { project ->
                projectCreatorOpen = false
                navigateToProject(project.id)
            },
        )
    }
    ModelPickerSheet(
        state = modelPickerState,
        onDismiss = onDismissModelPicker,
        onRetry = onRetryModelPicker,
        onSelected = onModelSelected,
        onConfirm = onConfirmModelSelection,
    )
    val iconPickerProject = projects.firstOrNull { it.id.value == iconPickerProjectId }
    if (iconPickerProject != null) {
        ProjectIconPickerSheet(
            project = iconPickerProject,
            selectedIcon = projectIcons[iconPickerProject.id]
                ?: defaultProjectIconId(iconPickerProject),
            onDismiss = { iconPickerProjectId = null },
            onSave = { iconId -> onSaveProjectIcon(iconPickerProject.id, iconId) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectCreationSheet(
    initialListing: HostDirectoryListing? = null,
    onDismiss: () -> Unit,
    onLoadHostDirectories: suspend (String?) -> Result<HostDirectoryListing>,
    onCreateHostDirectory: suspend (String, String) -> Result<HostDirectoryListing>,
    onCreateProject: suspend (String, String) -> Result<ProjectSummary>,
    onCreated: (ProjectSummary) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var projectName by rememberSaveable { mutableStateOf("") }
    var pathInput by rememberSaveable { mutableStateOf(initialListing?.path.orEmpty()) }
    var listing by remember { mutableStateOf(initialListing) }
    var loading by remember { mutableStateOf(initialListing == null) }
    var submitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showNewFolder by rememberSaveable { mutableStateOf(false) }
    var newFolderName by rememberSaveable { mutableStateOf("") }

    suspend fun loadPath(path: String?) {
        loading = true
        errorMessage = null
        onLoadHostDirectories(path).fold(
            onSuccess = { loaded ->
                listing = loaded
                pathInput = loaded.path
            },
            onFailure = { error ->
                errorMessage = projectCreationError(error, "Could not open that host folder")
            },
        )
        loading = false
    }

    LaunchedEffect(initialListing) {
        if (initialListing == null) loadPath(null)
    }

    val sheetContent: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .heightIn(max = 720.dp)
                    .fillMaxHeight()
                    .align(Alignment.Center)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .testTag("Create project sheet"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Create project", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Choose an existing folder on the Hermes host, or create a folder there.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it.take(ProjectSummary.MAX_LABEL_LENGTH) },
                    label = { Text("Project name") },
                    singleLine = true,
                    enabled = !submitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("Project name input"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = pathInput,
                        onValueChange = { updated ->
                            pathInput = updated.take(1_024)
                            if (updated != listing?.path) listing = null
                        },
                        label = { Text("Host folder") },
                        singleLine = true,
                        enabled = !loading && !submitting,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("Host folder input"),
                    )
                    Button(
                        onClick = {
                            coroutineScope.launch { loadPath(pathInput.trim()) }
                        },
                        enabled = !loading && !submitting &&
                            validProjectWorkspacePath(pathInput) != null,
                    ) {
                        Text("Open")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            listing?.parentPath?.let { parent ->
                                coroutineScope.launch { loadPath(parent) }
                            }
                        },
                        enabled = !loading && !submitting && listing?.parentPath != null,
                    ) {
                        Text("Up")
                    }
                    TextButton(
                        onClick = {
                            listing?.path?.let { current ->
                                coroutineScope.launch { loadPath(current) }
                            }
                        },
                        enabled = !loading && !submitting && listing != null,
                    ) {
                        Text("Refresh")
                    }
                    listing?.let { current ->
                        Text(
                            current.path,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                when {
                    loading -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Loading host folders…")
                    }
                    listing != null -> {
                        val directories = listing!!.directories
                        if (directories.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    "No subfolders here",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 16.dp),
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .testTag("Host directory list"),
                            ) {
                                items(directories, key = { it.path }) { directory ->
                                    ListItem(
                                        headlineContent = { Text(directory.name) },
                                        supportingContent = {
                                            Text(
                                                directory.path,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !submitting) {
                                                coroutineScope.launch { loadPath(directory.path) }
                                            },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                    else -> Spacer(modifier = Modifier.weight(1f))
                }
                TextButton(
                    onClick = { showNewFolder = !showNewFolder },
                    enabled = listing != null && !loading && !submitting,
                    modifier = Modifier.testTag("Toggle create host folder"),
                ) {
                    Text(if (showNewFolder) "Cancel new folder" else "Create folder here")
                }
                if (showNewFolder) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = newFolderName,
                            onValueChange = { newFolderName = it.take(255) },
                            label = { Text("New folder name") },
                            singleLine = true,
                            enabled = !submitting,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("New folder name input"),
                        )
                        Button(
                            onClick = {
                                val parent = listing?.path ?: return@Button
                                val name = validHostFolderName(newFolderName) ?: return@Button
                                submitting = true
                                errorMessage = null
                                coroutineScope.launch {
                                    onCreateHostDirectory(parent, name).fold(
                                        onSuccess = { created ->
                                            listing = created
                                            pathInput = created.path
                                            if (projectName.isBlank()) projectName = name
                                            newFolderName = ""
                                            showNewFolder = false
                                        },
                                        onFailure = { error ->
                                            errorMessage = projectCreationError(
                                                error,
                                                "Could not create that host folder",
                                            )
                                        },
                                    )
                                    submitting = false
                                }
                            },
                            enabled = !submitting && validHostFolderName(newFolderName) != null,
                            modifier = Modifier.testTag("Confirm create host folder"),
                        ) {
                            Text("Create folder")
                        }
                    }
                }
                errorMessage?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !submitting,
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val selectedPath = listing?.path ?: return@Button
                            val name = projectName.trim()
                            submitting = true
                            errorMessage = null
                            coroutineScope.launch {
                                onCreateProject(name, selectedPath).fold(
                                    onSuccess = onCreated,
                                    onFailure = { error ->
                                        errorMessage = projectCreationError(
                                            error,
                                            "Could not create the project",
                                        )
                                    },
                                )
                                submitting = false
                            }
                        },
                        enabled = !loading && !submitting && listing != null &&
                            projectName.trim().isNotEmpty() && pathInput == listing?.path,
                        modifier = Modifier.testTag("Confirm create project"),
                    ) {
                        Text(if (submitting) "Creating…" else "Create project")
                    }
                }
            }
        }
    }
    if (LocalInspectionMode.current) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                tonalElevation = 1.dp,
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                sheetContent()
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = { if (!submitting) onDismiss() },
            sheetState = sheetState,
        ) {
            sheetContent()
        }
    }
}

private fun projectCreationError(error: Throwable?, fallback: String): String =
    error?.message
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.take(180)
        ?: fallback

@Composable
private fun ProjectDock(
    state: ProjectDockState,
    projects: List<ProjectSummary>,
    selectedProjectId: ProjectId?,
    projectIcons: Map<ProjectId, ProjectIconId>,
    canStartNewTask: Boolean,
    settingsSelected: Boolean,
    onProjectSelected: (ProjectId) -> Unit,
    onChooseProjectIcon: (ProjectId) -> Unit,
    onCreateProject: () -> Unit,
    onNewTask: () -> Unit,
    onSettings: () -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onHide: () -> Unit,
) {
    val expanded = state == ProjectDockState.Expanded
    val dockWidth by animateDpAsState(
        targetValue = if (expanded) 228.dp else 76.dp,
        label = "Project dock width",
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .width(dockWidth)
            .fillMaxSize()
            .semantics {
                contentDescription = if (expanded) {
                    "Project dock, expanded"
                } else {
                    "Project dock, collapsed"
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = if (expanded) 12.dp else 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ProjectDockControl(
                        glyph = "‹",
                        description = "Collapse project dock",
                        onClick = onCollapse,
                    )
                }
            } else {
                ProjectDockControl(
                    glyph = "›",
                    description = "Expand project dock",
                    onClick = onExpand,
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(projects, key = { it.id.value }) { project ->
                    val iconId = projectIcons[project.id] ?: defaultProjectIconId(project)
                    val iconLabel = ProjectIconCatalog.entries.first { it.id == iconId }.label
                    ProjectDockAction(
                        glyph = projectDockInitial(project.label),
                        icon = projectIconVector(iconId),
                        iconDescription = "${project.label} icon $iconLabel",
                        label = project.label,
                        description = "Open project ${project.label}",
                        expanded = expanded,
                        selected = project.id == selectedProjectId,
                        trailingContent = if (expanded && project.id == selectedProjectId) {
                            {
                                IconButton(
                                    onClick = { onChooseProjectIcon(project.id) },
                                    modifier = Modifier.semantics {
                                        contentDescription = "Choose icon for ${project.label}"
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        } else {
                            null
                        },
                        onClick = { onProjectSelected(project.id) },
                    )
                }
            }
            HorizontalDivider()
            ProjectDockAction(
                glyph = "+",
                icon = Icons.Outlined.CreateNewFolder,
                iconDescription = null,
                label = "Create project",
                description = "Create project",
                expanded = expanded,
                selected = false,
                enabled = canStartNewTask,
                onClick = onCreateProject,
            )
            ProjectDockAction(
                glyph = "+",
                label = "New task",
                description = selectedProjectId
                    ?.let { id -> projects.firstOrNull { it.id == id }?.label }
                    ?.let { "New task in $it" }
                    ?: "New task",
                expanded = expanded,
                selected = false,
                enabled = canStartNewTask,
                accent = true,
                onClick = onNewTask,
            )
            ProjectDockAction(
                glyph = "⚙",
                label = "Settings",
                description = "Settings navigation",
                expanded = expanded,
                selected = settingsSelected,
                onClick = onSettings,
            )
            if (!expanded) {
                ProjectDockControl(
                    glyph = "‹",
                    description = "Hide project dock",
                    onClick = onHide,
                )
            }
        }
    }
}

@Composable
private fun ProjectDockAction(
    glyph: String,
    label: String,
    description: String,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    accent: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconDescription: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val semanticColors = LocalHermesSemanticColors.current
    val containerColor = when {
        accent -> semanticColors.active
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val contentColor = when {
        accent -> semanticColors.onActive
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val actionModifier = if (expanded) {
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    } else {
        Modifier.size(48.dp)
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
        modifier = actionModifier.semantics {
            contentDescription = description
            this.selected = selected
        },
    ) {
        Row(
            modifier = if (expanded) {
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
            } else {
                Modifier.fillMaxSize()
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.spacedBy(10.dp) else Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(24.dp)
                            .then(
                                iconDescription?.let { description ->
                                    Modifier.semantics { contentDescription = description }
                                } ?: Modifier,
                            ),
                    )
                } else {
                    Text(glyph, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (expanded) {
                Text(
                    label,
                    modifier = if (trailingContent != null) Modifier.weight(1f) else Modifier,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
                trailingContent?.invoke()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectIconPickerSheet(
    project: ProjectSummary,
    selectedIcon: ProjectIconId,
    onDismiss: () -> Unit,
    onSave: suspend (ProjectIconId) -> Result<Unit>,
) {
    var query by rememberSaveable(project.id.value) { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by rememberSaveable(project.id.value) { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val normalizedQuery = query.trim().lowercase()
    val visibleIcons = remember(normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            ProjectIconCatalog.entries
        } else {
            ProjectIconCatalog.entries.filter { option ->
                option.label.lowercase().contains(normalizedQuery) ||
                    option.searchTerms.any { it.contains(normalizedQuery) }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Choose project icon", style = MaterialTheme.typography.headlineSmall)
            Text(
                project.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it.take(80)
                    saveError = null
                },
                enabled = !isSaving,
                singleLine = true,
                label = { Text("Search icons") },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Search project icons" },
            )
            saveError?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (visibleIcons.isEmpty()) {
                Text(
                    "No matching icons",
                    modifier = Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 84.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    gridItems(ProjectIconCatalog.entries.filter { it in visibleIcons }, key = { it.id.persistedValue }) { option ->
                        val selected = option.id == selectedIcon
                        Surface(
                            onClick = {
                                if (!isSaving) {
                                    coroutineScope.launch {
                                        isSaving = true
                                        saveError = null
                                        val result = onSave(option.id)
                                        isSaving = false
                                        if (result.isSuccess) {
                                            onDismiss()
                                        } else {
                                            saveError = "Could not save icon. Try again."
                                        }
                                    }
                                }
                            },
                            enabled = !isSaving,
                            selected = selected,
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            modifier = Modifier
                                .heightIn(min = 80.dp)
                                .semantics {
                                    contentDescription = "Project icon ${option.label}"
                                    this.selected = selected
                                },
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = projectIconVector(option.id),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                )
                                Text(
                                    option.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectDockControl(
    glyph: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                glyph,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun ProjectDockEdgeTab(
    modifier: Modifier = Modifier,
    onShow: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(width = 48.dp, height = 72.dp)
            .semantics { contentDescription = "Show project dock" }
            .clickable(onClick = onShow),
    ) {
        Surface(
            shape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .size(width = 16.dp, height = 72.dp)
                .align(Alignment.CenterStart),
        ) {}
        Text(
            "›",
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.CenterStart),
        )
    }
}

private fun projectDockInitial(label: String): String {
    val initials = label
        .trim()
        .split(Regex("\\s+"))
        .take(2)
        .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
        .joinToString(separator = "") { it.uppercaseChar().toString() }
    return initials.ifBlank { "•" }
}

internal fun serverHostnameLabel(serverOrigin: ServerOrigin?): String {
    val hostname = serverOrigin?.value?.let { origin ->
        runCatching { URI(origin).host }.getOrNull()
    }
    return hostname?.takeIf { it.isNotBlank() } ?: "Hermes"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    state: ModelPickerState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSelected: (ModelSelection) -> Unit,
    onConfirm: () -> Unit,
) {
    if (state == ModelPickerState.Closed) return
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Choose model", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Applies to this session only",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            when (state) {
                ModelPickerState.Closed -> Unit
                is ModelPickerState.Loading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Loading models…")
                    }
                }
                is ModelPickerState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRetry) { Text("Retry") }
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                }
                is ModelPickerState.Ready -> {
                    ModelPickerReadyContent(
                        state = state,
                        onDismiss = onDismiss,
                        onRetry = onRetry,
                        onSelected = onSelected,
                        onConfirm = onConfirm,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelPickerReadyContent(
    state: ModelPickerState.Ready,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSelected: (ModelSelection) -> Unit,
    onConfirm: () -> Unit,
) {
    val providers = state.options.providers
    state.confirmationMessage?.let { confirmation ->
        Text(confirmation, color = MaterialTheme.colorScheme.onSurface)
        state.pendingSelection?.let { selection ->
            Text(
                "${selection.provider} · ${selection.model}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !state.applying, onClick = onConfirm) {
                Text(if (state.applying) "Applying…" else "Use model")
            }
            TextButton(enabled = !state.applying, onClick = onDismiss) { Text("Cancel") }
        }
        return
    }
    if (providers.isEmpty()) {
        Text("No configured models are available for this profile.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) { Text("Retry") }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
        return
    }

    val initialProvider = state.options.current
        ?.provider
        ?.takeIf { current -> providers.any { it.slug == current } }
        ?: providers.first().slug
    var selectedProviderSlug by remember(state.durableSessionId, providers) {
        mutableStateOf(initialProvider)
    }
    var query by rememberSaveable(state.durableSessionId.value) { mutableStateOf("") }
    val selectedProvider = providers.firstOrNull { it.slug == selectedProviderSlug }
        ?: providers.first()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        providers.forEach { provider ->
            FilterChip(
                selected = provider.slug == selectedProvider.slug,
                onClick = {
                    selectedProviderSlug = provider.slug
                    query = ""
                },
                enabled = !state.applying,
                label = { Text(provider.name) },
                modifier = Modifier.semantics {
                    contentDescription = "Provider ${provider.name}"
                },
            )
        }
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it.take(128) },
        label = { Text("Search ${selectedProvider.name} models") },
        singleLine = true,
        enabled = !state.applying,
        modifier = Modifier.fillMaxWidth(),
    )
    state.error?.let { error ->
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    val matchingModels = selectedProvider.models.filter { model ->
        query.isBlank() || model.contains(query.trim(), ignoreCase = true)
    }
    if (matchingModels.isEmpty()) {
        Text("No models match your search.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
        ) {
            items(matchingModels, key = { model -> "${selectedProvider.slug}:$model" }) { model ->
                val selection = ModelSelection(selectedProvider.slug, model)
                val isCurrent = selection == state.options.current
                ModelPickerRow(
                    provider = selectedProvider,
                    model = model,
                    current = isCurrent,
                    enabled = !state.applying,
                    onClick = { onSelected(selection) },
                )
            }
        }
    }
    if (state.applying) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Text("Applying model…")
        }
    }
}

@Composable
private fun ModelPickerRow(
    provider: ModelProviderOption,
    model: String,
    current: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(model) },
        supportingContent = { Text(provider.name) },
        trailingContent = {
            if (current) {
                Text("Current", color = MaterialTheme.colorScheme.primary)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { selected = current }
            .clickable(enabled = enabled, onClick = onClick),
    )
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionListScreen(
    projects: List<ProjectSummary>,
    sessions: List<SessionSummary>,
    modifier: Modifier = Modifier,
    projectState: ProjectLoadState,
    snapshot: HermesGatewaySnapshot,
    serverSettingsState: ServerSettingsState,
    initialSearchOpen: Boolean,
    showDockOwnedActions: Boolean = true,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onConfigureServer: () -> Unit,
    onSignIn: () -> Unit,
    onProjectSelected: (ProjectId) -> Unit,
    onSessionSelected: (DurableSessionId) -> Unit,
    onRenameSession: suspend (DurableSessionId, String) -> Result<Unit>,
    onSetSessionPinned: suspend (DurableSessionId, Boolean) -> Result<Unit>,
    onSetSessionArchived: suspend (DurableSessionId, Boolean) -> Result<Unit>,
    onDeleteSession: suspend (DurableSessionId) -> Result<Unit>,
    onSearchTranscripts: (String) -> Unit,
    onCreateProject: () -> Unit,
    onNewSession: () -> Unit = {},
) {
    val connectionState = snapshot.connectionState
    val semanticColors = LocalHermesSemanticColors.current
    val serverOrigin = (serverSettingsState as? ServerSettingsState.Ready)?.serverOrigin
    val canStartNewChat = snapshot.authenticationState == AuthenticationState.Authenticated
    var searchOpen by rememberSaveable { mutableStateOf(initialSearchOpen) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    var editingSession by remember { mutableStateOf<SessionSummary?>(null) }
    var deletingSession by remember { mutableStateOf<SessionSummary?>(null) }
    var pendingDelete by remember { mutableStateOf<SessionSummary?>(null) }
    val sessionActionScope = rememberCoroutineScope()
    val pinnedOnly = searchQuery.contains("is:pinned", ignoreCase = true)
    val archivedOnly = searchQuery.contains("is:archived", ignoreCase = true)
    val normalizedSearch = searchQuery
        .replace(Regex("(?i)\\bis:(pinned|archived)\\b"), " ")
        .trim()
    val visibleProjects = projects.filter { project ->
        normalizedSearch.isEmpty() ||
            project.label.contains(normalizedSearch, ignoreCase = true) ||
            project.primaryPath?.contains(normalizedSearch, ignoreCase = true) == true ||
            project.previewSessions.any { session ->
                session.title.contains(normalizedSearch, ignoreCase = true)
            }
    }
    val visibleSessions = sessions.filter { session ->
        session.id != pendingDelete?.id &&
            (!pinnedOnly || session.pinned) &&
            (!archivedOnly || session.archived) &&
            (
                normalizedSearch.isEmpty() ||
                    session.title.contains(normalizedSearch, ignoreCase = true) ||
                    session.workspacePath?.contains(normalizedSearch, ignoreCase = true) == true
            )
    }
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        val context = connectionContext(snapshot, serverOrigin)
                        val contextColor = when {
                            connectionState == ConnectionState.Disconnected && serverOrigin != null ->
                                MaterialTheme.colorScheme.error
                            connectionState == ConnectionState.Disconnected ->
                                MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.semantics {
                                contentDescription = "Sessions. Connection: $context"
                                stateDescription = context
                            },
                        ) {
                            Text(
                                serverHostnameLabel(serverOrigin),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("Agent workspace", style = MaterialTheme.typography.labelMedium)
                                Text("·", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    context,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = contextColor,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                searchOpen = !searchOpen
                                if (!searchOpen) searchQuery = ""
                            },
                            modifier = Modifier.semantics {
                                contentDescription = if (searchOpen) {
                                    "Close search"
                                } else {
                                    "Search projects and sessions"
                                }
                            },
                        ) {
                            Text(
                                if (searchOpen) "×" else "⌕",
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        if (showDockOwnedActions) {
                            IconButton(
                                enabled = canStartNewChat,
                                onClick = dropUnlessResumed { onCreateProject() },
                                modifier = Modifier.semantics {
                                    contentDescription = "Create project"
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CreateNewFolder,
                                    contentDescription = null,
                                )
                            }
                            IconButton(
                                enabled = serverSettingsState !is ServerSettingsState.Loading,
                                onClick = dropUnlessResumed { onConfigureServer() },
                                modifier = Modifier.semantics {
                                    contentDescription = "Settings"
                                },
                            ) {
                                Text("⚙", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    },
                )
                if (searchOpen) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("Opaque project search"),
                        color = MaterialTheme.colorScheme.background,
                        shadowElevation = 3.dp,
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it.take(128)
                                if (searchQuery.trim().length >= 2) onSearchTranscripts(searchQuery)
                                else onSearchTranscripts("")
                            },
                            label = { Text("Search projects and sessions") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (showDockOwnedActions && canStartNewChat) {
                ExtendedFloatingActionButton(
                    onClick = dropUnlessResumed { onNewSession() },
                    containerColor = semanticColors.active,
                    contentColor = semanticColors.onActive,
                ) {
                    Text("New task")
                }
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .testTag("Home pull to refresh"),
        ) {
            if (projects.isEmpty() && sessions.isEmpty()) {
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
                if (snapshot.delegationStatus.active.isNotEmpty()) {
                    item(key = "running-subagents-heading") {
                        Text(
                            "Running subagents",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    items(
                        snapshot.delegationStatus.active,
                        key = { "subagent:${it.subagentId}" },
                    ) { subagent ->
                        RunningSubagentRow(subagent)
                    }
                }
                if (normalizedSearch.isNotEmpty() && snapshot.transcriptSearchResults.isNotEmpty()) {
                    item(key = "transcript-search-heading") {
                        Text(
                            "Transcript matches",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    items(snapshot.transcriptSearchResults, key = { "search:${it.sessionId.value}" }) { result ->
                        ListItem(
                            headlineContent = { Text(result.title) },
                            supportingContent = {
                                Text(result.snippet, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            },
                            modifier = Modifier.clickable { onSessionSelected(result.sessionId) },
                        )
                    }
                }
                if (visibleProjects.isNotEmpty() && projectState is ProjectLoadState.Loaded) {
                    val sendingSessionIds = snapshot.chatSessions
                        .filterValues(ChatSessionSnapshot::isSending)
                        .keys
                    val workingProjectIds = buildSet {
                        snapshot.durableSessions
                            .asSequence()
                            .filter { it.id in sendingSessionIds }
                            .mapNotNull(SessionSummary::projectId)
                            .forEach(::add)
                        snapshot.projectSessions.forEach { (projectId, sessions) ->
                            if (sessions.any { it.id in sendingSessionIds }) add(projectId)
                        }
                    }
                    item(key = "projects-heading") {
                        Text(
                            "Projects",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    items(visibleProjects, key = { "project:${it.id.value}" }) { project ->
                        ProjectHomeRow(
                            project = project,
                            working = project.id in workingProjectIds,
                            onClick = dropUnlessResumed { onProjectSelected(project.id) },
                        )
                    }
                }
                item(key = "recent-sessions-heading") {
                    Text(
                        "Recent Sessions",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                if (visibleSessions.isEmpty()) {
                    item(key = "recent-sessions-empty") {
                        Text(
                            "No recent sessions",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                } else {
                    val activeControllerSessionIds = snapshot.activeRuntimes
                        .filter { it.access == RuntimeAccess.Controller }
                        .mapNotNull { it.durableSessionId }
                        .toSet()
                    items(visibleSessions, key = { "session:${it.id.value}" }) { session ->
                        val isCurrent = session.id in activeControllerSessionIds
                        RecentSessionHomeRow(
                            session = session,
                            current = isCurrent,
                            onClick = dropUnlessResumed { onSessionSelected(session.id) },
                            onRename = { editingSession = session },
                            onPin = { sessionActionScope.launch { onSetSessionPinned(session.id, !session.pinned) } },
                            onArchive = { sessionActionScope.launch { onSetSessionArchived(session.id, !session.archived) } },
                            onDelete = { deletingSession = session },
                        )
                    }
                    item(key = "home-bottom-clearance") {
                        Box(modifier = Modifier.size(104.dp))
                    }
                }
            }
            }
        }
    }
    editingSession?.let { session ->
        var title by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { editingSession = null },
            title = { Text("Rename session") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(512) },
                        label = { Text("Session title") },
                        singleLine = true,
                    )
                    TextButton(onClick = {
                        sessionActionScope.launch { onSetSessionPinned(session.id, !session.pinned) }
                        editingSession = null
                    }) { Text(if (session.pinned) "Unpin session" else "Pin session") }
                    TextButton(onClick = {
                        sessionActionScope.launch { onSetSessionArchived(session.id, !session.archived) }
                        editingSession = null
                    }) { Text(if (session.archived) "Restore session" else "Archive session") }
                    TextButton(onClick = {
                        editingSession = null
                        deletingSession = session
                    }) { Text("Delete session") }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = title.isNotBlank(),
                    onClick = {
                        sessionActionScope.launch { onRenameSession(session.id, title.trim()) }
                        editingSession = null
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingSession = null }) { Text("Cancel") } },
        )
    }
    deletingSession?.let { session ->
        AlertDialog(
            onDismissRequest = { deletingSession = null },
            title = { Text("Delete session?") },
            text = { Text("This permanently deletes ${session.title} from Hermes Serve.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = session
                    deletingSession = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingSession = null }) { Text("Cancel") } },
        )
    }
    pendingDelete?.let { session ->
        Snackbar(
            action = { TextButton(onClick = { pendingDelete = null }) { Text("Undo") } },
            modifier = Modifier.padding(16.dp),
        ) { Text("${session.title} will be deleted") }
        LaunchedEffect(session.id) {
            delay(5_000)
            if (pendingDelete?.id == session.id) {
                onDeleteSession(session.id)
                pendingDelete = null
            }
        }
    }
}

@Composable
private fun RunningSubagentRow(subagent: DelegatedSubagent) {
    val statusLine = buildString {
        append(subagent.status)
        subagent.parentSubagentId?.let { append(" · child of ").append(it) }
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Running subagent: ${subagent.goal}, $statusLine"
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                subagent.goal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProjectHomeRow(
    project: ProjectSummary,
    working: Boolean,
    onClick: () -> Unit,
) {
    val sessionLabel = if (project.sessionCount == 1) "1 session" else "${project.sessionCount} sessions"
    val latestTitle = project.previewSessions.firstOrNull()?.title
    val description = buildString {
        append("Project ")
        append(project.label)
        if (working) append(", active session running")
        append(", ")
        append(sessionLabel)
        if (latestTitle != null) {
            append(", latest ")
            append(latestTitle)
        }
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (working) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = if (working) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        tonalElevation = if (working) 1.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                selected = working
                contentDescription = description
                if (working) stateDescription = "Active session running"
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    project.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (working) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    sessionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                validProjectWorkspacePath(project.primaryPath) ?: "No workspace",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            latestTitle?.let { latest ->
                Text(
                    "Latest · $latest",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun RecentSessionHomeRow(
    session: SessionSummary,
    current: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (current) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onRename)
            .semantics(mergeDescendants = true) {
                if (current) stateDescription = "Current controller session"
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    session.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (current) "Controller active" else "Durable session",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (current) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text("›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun connectionContext(
    snapshot: HermesGatewaySnapshot,
    serverOrigin: ServerOrigin?,
): String = when (snapshot.connectionState) {
    ConnectionState.Connected -> when (snapshot.authenticationState) {
        AuthenticationState.SignInRequired -> "Sign in required"
        AuthenticationState.SigningIn -> "Signing in"
        else -> "Connected"
    }
    ConnectionState.Connecting -> "Connecting"
    ConnectionState.Recovering -> "Reconnecting"
    ConnectionState.Disconnected -> if (serverOrigin == null) "Not configured" else "Offline"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectDetailScreen(
    project: ProjectSummary,
    state: ProjectSessionLoadState?,
    sessions: List<SessionSummary>,
    workingSessionIds: Set<DurableSessionId>,
    unreadCompletedSessionIds: Set<DurableSessionId>,
    modifier: Modifier = Modifier,
    showBack: Boolean,
    showNewTaskAction: Boolean = true,
    onBack: () -> Unit,
    onSessionSelected: (DurableSessionId) -> Unit,
    onNewTask: () -> Unit,
) {
    val semanticColors = LocalHermesSemanticColors.current
    val loadedSessions = when (state) {
        is ProjectSessionLoadState.Loaded ->
            if (sessions.isEmpty()) state.sessions else sessions
        else -> emptyList()
    }
    val workspace = validProjectWorkspacePath(project.primaryPath)
    val workspaceLabel = workspace ?: "No workspace"
    Scaffold(
        modifier = modifier.semantics {
            contentDescription = "Project sessions for ${project.label}"
        },
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(project.label)
                        Text("Project", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    if (showBack) {
                        TextButton(onClick = dropUnlessResumed { onBack() }) {
                            Text("Back")
                        }
                    }
                },
                actions = {
                    if (showNewTaskAction && state is ProjectSessionLoadState.Loaded) {
                        TextButton(onClick = dropUnlessResumed { onNewTask() }) {
                            Text("New task")
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
                .consumeWindowInsets(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Sessions inbox, ${project.sessionCount} sessions"
                    },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Sessions",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        project.sessionCount.toString(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(
                    "Workspace: $workspaceLabel",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (workspace == null) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider()
            when (state) {
                null,
                ProjectSessionLoadState.Loading,
                -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Loading project sessions")
                    }
                }
                ProjectSessionLoadState.Unsupported -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Project sessions unavailable")
                    }
                }
                is ProjectSessionLoadState.TransientError -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Could not load project sessions", style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is ProjectSessionLoadState.Loaded -> {
                    if (loadedSessions.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("No sessions in this project")
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
                        ) {
                            items(loadedSessions, key = { it.id.value }) { session ->
                                val isWorking = session.id in workingSessionIds
                                val isUnreadComplete = !isWorking && session.id in unreadCompletedSessionIds
                                SessionInboxRow(
                                    session = session,
                                    projectLabel = project.label,
                                    isWorking = isWorking,
                                    isUnreadComplete = isUnreadComplete,
                                    activeColor = semanticColors.active,
                                    completedColor = semanticColors.completed,
                                    onClick = { onSessionSelected(session.id) },
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionInboxRow(
    session: SessionSummary,
    projectLabel: String,
    isWorking: Boolean,
    isUnreadComplete: Boolean,
    activeColor: androidx.compose.ui.graphics.Color,
    completedColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val workspace = validProjectWorkspacePath(session.workspacePath)
    val workspaceLabel = workspace ?: "No workspace"
    val ownerLabel = session.profile ?: projectLabel
    val preview = session.preview?.trim()?.takeIf(String::isNotEmpty)
    val recency = session.lastActiveEpochSeconds?.let(::formatSessionRecency)
    val metadata = listOfNotNull(
        session.model?.trim()?.takeIf(String::isNotEmpty),
        session.messageCount?.let { count -> "$count ${if (count == 1) "message" else "messages"}" },
    ).joinToString(" · ")
    val rowDescription = buildString {
        append("Session ${session.title}, $workspaceLabel")
        if (isWorking) append(", running")
        if (isUnreadComplete) append(", completed unread")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = dropUnlessResumed { onClick() })
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics {
                contentDescription = rowDescription
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.padding(top = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isWorking -> PulsingSessionStatusIndicator(
                    color = activeColor,
                    contentDescription = "${session.title} is running",
                    size = 10.dp,
                )
                isUnreadComplete -> Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(completedColor, androidx.compose.foundation.shape.CircleShape)
                        .semantics {
                            contentDescription = "${session.title} completed; unread"
                        },
                )
                else -> Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            MaterialTheme.colorScheme.outlineVariant,
                            androidx.compose.foundation.shape.CircleShape,
                        ),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    ownerLabel,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                if (recency != null) {
                    Text(
                        recency,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.semantics {
                            contentDescription = "Last active time available"
                        },
                    )
                }
            }
            Text(
                session.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            if (preview != null) {
                Text(
                    preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (metadata.isNotEmpty()) {
                Text(
                    metadata,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            } else {
                Text(
                    workspaceLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (workspace == null) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (session.isLocalDraft) {
                Text(
                    "Draft",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun formatSessionRecency(epochSeconds: Double): String {
    val timestampMillis = (epochSeconds * 1_000.0).toLong()
    return DateUtils.getRelativeTimeSpanString(
        timestampMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

@Composable
private fun PulsingSessionStatusIndicator(
    color: androidx.compose.ui.graphics.Color,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp = 14.dp,
) {
    val pulse = rememberInfiniteTransition(label = "Session running pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SESSION_STATUS_PULSE_MILLIS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Session running indicator alpha",
    )
    Box(
        modifier = Modifier
            .size(size)
            .alpha(alpha)
            .background(color, androidx.compose.foundation.shape.CircleShape)
            .semantics {
                this.contentDescription = contentDescription
                sessionStatusPulseAlpha = alpha
            },
    )
}

@Composable
private fun MissingProjectScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Project is no longer available")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerSettingsScreen(
    serverOrigin: ServerOrigin?,
    snapshot: HermesGatewaySnapshot = HermesGatewaySnapshot(),
    showBack: Boolean,
    onBack: () -> Unit,
    onSave: suspend (ServerOrigin) -> Result<Unit>,
    onLoadManagementSettings: (String) -> Unit = {},
    onSetProfileDefaultModel: suspend (ModelSelection, Boolean) -> ModelSwitchResult = { _, _ ->
        ModelSwitchResult(accepted = false)
    },
    onRefreshCronJobs: () -> Unit = {},
    onCronJobAction: (String, CronJobAction) -> Unit = { _, _ -> },
    onLogout: suspend () -> Unit = {},
) {
    var value by rememberSaveable(serverOrigin?.value) {
        mutableStateOf(serverOrigin?.value.orEmpty())
    }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by rememberSaveable { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var modelQuery by rememberSaveable { mutableStateOf("") }
    var pendingExpensive by remember { mutableStateOf<ModelSelection?>(null) }
    var pendingDefault by remember { mutableStateOf<ModelSelection?>(null) }
    var expensiveMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(serverOrigin, snapshot.authenticationState) {
        if (serverOrigin != null && snapshot.authenticationState == AuthenticationState.Authenticated) {
            onLoadManagementSettings(snapshot.selectedProfile)
            onRefreshCronJobs()
        }
    }
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
                .verticalScroll(rememberScrollState())
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
            if (snapshot.authenticationState == AuthenticationState.Authenticated) {
                HorizontalDivider()
                Text("Connection", style = MaterialTheme.typography.titleMedium)
                Text("Hermes ${snapshot.serverVersion ?: "unknown"} · Authenticated")
                if (snapshot.profiles.isNotEmpty()) {
                    Text("Profile", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        snapshot.profiles.forEach { profile ->
                            FilterChip(
                                selected = profile == snapshot.selectedProfile,
                                onClick = { onLoadManagementSettings(profile) },
                                label = { Text(profile) },
                            )
                        }
                    }
                }
                Text("Default model for new chats", style = MaterialTheme.typography.titleMedium)
                snapshot.defaultModelOptions?.current?.let { current ->
                    Text("${current.provider} / ${current.model}")
                }
                OutlinedTextField(
                    value = modelQuery,
                    onValueChange = { modelQuery = it.take(128) },
                    label = { Text("Search profile models") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                snapshot.defaultModelOptions?.providers.orEmpty().forEach { provider ->
                    provider.models
                        .filter { modelQuery.isBlank() || it.contains(modelQuery.trim(), ignoreCase = true) }
                        .take(8)
                        .forEach { model ->
                            val selection = ModelSelection(provider.slug, model)
                            FilterChip(
                                selected = pendingDefault == selection,
                                onClick = { pendingDefault = selection },
                                label = { Text("${provider.name} · $model") },
                            )
                        }
                }
                pendingDefault?.let { selection ->
                    Button(onClick = {
                        coroutineScope.launch {
                            val result = onSetProfileDefaultModel(selection, false)
                            if (result.confirmationRequired) {
                                pendingExpensive = selection
                                expensiveMessage = result.confirmationMessage
                            } else if (result.accepted) {
                                pendingDefault = null
                            }
                        }
                    }) { Text("Set default") }
                }
                TextButton(onClick = { coroutineScope.launch { onLogout() } }) { Text("Log out") }
                snapshot.managementError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                CronJobsPanel(
                    state = snapshot.cronJobsState,
                    onRefresh = onRefreshCronJobs,
                    actionJobId = snapshot.cronJobActionJobId,
                    actionError = snapshot.cronJobActionError,
                    onJobAction = onCronJobAction,
                )
            }
        }
    }
    pendingExpensive?.let { selection ->
        AlertDialog(
            onDismissRequest = { pendingExpensive = null },
            title = { Text("Confirm expensive model") },
            text = { Text(expensiveMessage ?: "This model may have a high per-token cost.") },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch { onSetProfileDefaultModel(selection, true) }
                    pendingExpensive = null
                }) { Text("Set default") }
            },
            dismissButton = { TextButton(onClick = { pendingExpensive = null }) { Text("Cancel") } },
        )
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
    attachments: List<ComposerAttachment>,
    onAddAttachments: (List<ComposerAttachment>) -> List<String>,
    onRemoveAttachment: (String) -> Unit,
    onSend: (String) -> Unit,
    onReasoningSelected: (String) -> Unit,
    onOpenModelPicker: () -> Unit,
    onClarificationResponse: (String, String) -> Unit,
    onApprovalResponse: (String, Boolean) -> Unit,
    showStop: Boolean,
    stopping: Boolean,
    onStop: () -> Unit,
    steeringAvailable: Boolean,
    onSteer: (String) -> Unit,
    delegationStatus: DelegationStatus,
    delegationAvailable: Boolean,
    onSetDelegationPaused: (Boolean) -> Unit,
    onSteerSubagent: (String, String) -> Unit,
    onInterruptSubagent: (String) -> Unit,
    slashCompletion: SlashCompletionState? = null,
    onSlashCompletionSelected: (SlashCompletionState, SlashCompletionItem) -> Unit = { _, _ -> },
    onLoadSessionInsights: () -> Unit,
    maintenanceAvailable: Boolean,
    maintenanceEnabled: Boolean,
    onCompressSession: (String?) -> Unit,
    onUndoSession: () -> Unit,
    onBranchSession: (Int?, String?) -> Unit,
    showBack: Boolean,
    onBack: () -> Unit,
    onLoadManagedImage: suspend (String) -> Result<ByteArray>,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val semanticColors = LocalHermesSemanticColors.current
    var showSessionInsights by remember(session.id) { mutableStateOf(false) }
    val workspacePath = validProjectWorkspacePath(session.workspacePath)
    val workspaceLabel = workspacePath ?: session.projectId?.let { "No workspace" }
    val projectDraftMissingWorkspace = session.isLocalDraft &&
        session.projectId != null &&
        workspacePath == null
    var attachmentError by remember(session.id) { mutableStateOf<String?>(null) }
    var pendingSend by remember(session.id) { mutableStateOf<Pair<String, Int>?>(null) }
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val candidates = mutableListOf<ComposerAttachment>()
        val errors = mutableListOf<String>()
        uris.forEach { uri ->
            runCatching { resolvePickedAttachment(context, uri) }
                .onSuccess(candidates::add)
                .onFailure { errors += it.message ?: "Could not read selected file" }
        }
        errors += onAddAttachments(candidates)
        attachmentError = errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
    val hasRunStateContent = chat.runState.hasVisibleContent()
    val timelineLastIndex = (
        chat.messages.size + if (hasRunStateContent) 1 else 0
    ).minus(1).coerceAtLeast(0)
    val transcriptListState = rememberLazyListState(
        initialFirstVisibleItemIndex = timelineLastIndex,
    )
    LaunchedEffect(
        session.id,
        chat.messages.size,
        chat.messages.lastOrNull()?.text?.length,
        chat.runState,
    ) {
        if (chat.messages.isNotEmpty() || hasRunStateContent) {
            transcriptListState.scrollToItem(timelineLastIndex)
        }
    }
    LaunchedEffect(session.id, chat.messages.size, chat.isSending, chat.error) {
        val pending = pendingSend ?: return@LaunchedEffect
        if (chat.messages.size > pending.second && !chat.isSending) {
            if (chat.error == null && draft.trim() == pending.first) onDraftChanged("")
            pendingSend = null
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(session.title)
                        workspaceLabel?.let { label ->
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                        listOfNotNull(
                            chat.provider?.takeIf(String::isNotBlank),
                            chat.model?.takeIf(String::isNotBlank),
                            chat.reasoningEffort
                                ?.takeIf(String::isNotBlank)
                                ?.let { "reasoning $it" },
                        ).takeIf(List<String>::isNotEmpty)?.let { runtimeConfiguration ->
                            Text(
                                runtimeConfiguration.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (showBack) {
                        TextButton(onClick = dropUnlessResumed { onBack() }) {
                            Text("Back")
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            showSessionInsights = true
                            if (maintenanceAvailable) onLoadSessionInsights()
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "Open session details"
                        },
                    ) {
                        Icon(Icons.Outlined.Info, contentDescription = null)
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
            workspaceLabel?.let { label ->
                Text(
                    "Workspace: $label",
                    color = if (workspacePath == null) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics {
                        contentDescription = "Session workspace: $label"
                    },
                )
            }
            when {
                chat.isLoading && chat.messages.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Loading transcript…")
                    }
                }
                chat.messages.isEmpty() && !hasRunStateContent -> {
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("Session timeline"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(
                            items = chat.messages,
                            key = { index, _ -> "message:$index" },
                        ) { messageIndex, message ->
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
                                message.reasoningText.takeIf { it.isNotBlank() }?.let { reasoning ->
                                    var showReasoning by rememberSaveable(session.id.value, messageIndex) {
                                        mutableStateOf(false)
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(
                                            onClick = { showReasoning = !showReasoning },
                                            modifier = Modifier.semantics {
                                                contentDescription = if (showReasoning) {
                                                    "Hide thinking"
                                                } else {
                                                    "Show thinking"
                                                }
                                            },
                                        ) {
                                            Text(
                                                if (showReasoning) "Hide thinking" else "Show thinking",
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        }
                                        if (showReasoning) {
                                            Text(
                                                reasoning,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                val renderedText = message.text.ifEmpty {
                                    if (message.isStreaming) "…" else ""
                                }
                                if (message.role == ChatMessageRole.Assistant && message.isStreaming) {
                                    // Streaming text renders as raw plain text: parsing
                                    // partial markdown (unclosed code fences, stray bold
                                    // markers, half-built tables) produces garbled output
                                    // until message.complete finalizes the full text.
                                    Text(
                                        renderedText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.testTag("Streaming assistant text"),
                                    )
                                } else {
                                    MarkdownMessage(
                                        renderedText,
                                        loadManagedImage = { path ->
                                            onLoadManagedImage(path).getOrThrow()
                                        },
                                    )
                                }
                            }
                        }
                        if (hasRunStateContent) {
                            item(key = "run-state") {
                                RunStateContent(
                                    runState = chat.runState,
                                    durableSessionId = session.id,
                                    onClarificationResponse = onClarificationResponse,
                                    onApprovalResponse = onApprovalResponse,
                                )
                            }
                        }
                    }
                }
            }
            chat.error
                ?.takeUnless { projectDraftMissingWorkspace && it == "No workspace" }
                ?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            chat.notice?.let { notice ->
                Text(
                    notice,
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (delegationAvailable) {
                DelegationControls(
                    status = delegationStatus,
                    onSetPaused = onSetDelegationPaused,
                    onSteer = onSteerSubagent,
                    onInterrupt = onInterruptSubagent,
                )
            }
            chat.billingNotice?.let { billing ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Billing action required" },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Billing action required", style = MaterialTheme.typography.titleSmall)
                        billing.message?.takeIf(String::isNotBlank)?.let { Text(it) }
                        billing.provider?.takeIf(String::isNotBlank)?.let { provider ->
                            Text("Provider: $provider", style = MaterialTheme.typography.bodySmall)
                        }
                        billing.billingUrl
                            ?.takeIf { it.startsWith("https://", ignoreCase = true) }
                            ?.let { billingUrl ->
                                Button(onClick = { runCatching { uriHandler.openUri(billingUrl) } }) {
                                    Text(if (billing.isNous) "Open Nous billing" else "Open billing")
                                }
                            }
                    }
                }
            }
            if (chat.isSending) {
                Text(
                    "Hermes is responding…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (slashCompletion != null && slashCompletion.items.isNotEmpty()) {
                SlashCompletionMenu(
                    completion = slashCompletion,
                    onItemSelected = { item -> onSlashCompletionSelected(slashCompletion, item) },
                )
            }
            attachmentError?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val composerEnabled = (!chat.isSending || steeringAvailable) && !chat.isLoading
            val attachmentsEnabled = canSend && composerEnabled && !steeringAvailable
            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    attachments.forEach { attachment ->
                        InputChip(
                            selected = true,
                            onClick = { onRemoveAttachment(attachment.id) },
                            enabled = attachmentsEnabled,
                            label = { Text(attachment.displayName, maxLines = 1) },
                            trailingIcon = { Text("×") },
                            modifier = Modifier.semantics {
                                contentDescription = "Remove ${attachment.displayName}"
                            },
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("Message composer"),
                shape = RoundedCornerShape(30.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            attachmentError = null
                            attachmentPicker.launch(arrayOf("*/*"))
                        },
                        enabled = attachmentsEnabled,
                        modifier = Modifier
                            .size(44.dp)
                            .semantics { contentDescription = "Attach files" },
                    ) {
                        Text("+", style = MaterialTheme.typography.headlineSmall)
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChanged,
                        enabled = composerEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp, max = 132.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = if (composerEnabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        minLines = 1,
                        maxLines = 5,
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp)
                                    .padding(horizontal = 4.dp, vertical = 10.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (draft.isEmpty()) {
                                    Text(
                                        "Message Hermes",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    if (showStop) {
                        if (steeringAvailable) {
                            Button(
                                enabled = !stopping && draft.isNotBlank(),
                                onClick = {
                                    val guidance = draft.trim()
                                    if (guidance.isNotEmpty()) {
                                        onSteer(guidance)
                                        onDraftChanged("")
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                modifier = Modifier
                                    .heightIn(min = 40.dp)
                                    .semantics {
                                        contentDescription = "Steer Hermes response"
                                        stateDescription = if (stopping) "Steering unavailable while stopping" else "Ready to steer"
                                    },
                            ) {
                                Text("Steer")
                            }
                        }
                        Button(
                            enabled = !stopping,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = semanticColors.active,
                                contentColor = semanticColors.onActive,
                                disabledContainerColor = semanticColors.active.copy(alpha = 0.38f),
                                disabledContentColor = semanticColors.onActive.copy(alpha = 0.38f),
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            onClick = dropUnlessResumed { onStop() },
                            modifier = Modifier
                                .heightIn(min = 40.dp)
                                .semantics {
                                    contentDescription = "Stop Hermes response"
                                    stateDescription = if (stopping) "Stopping" else "Ready to stop"
                                },
                        ) {
                            Text(if (stopping) "Stopping…" else "Stop")
                        }
                    } else {
                        Button(
                            onClick = {
                                val message = draft.trim()
                                val reasoningEffort = reasoningEffortCommand(message)
                                when {
                                    isModelPickerCommand(message) -> {
                                        pendingSend = null
                                        onDraftChanged("")
                                        onOpenModelPicker()
                                    }
                                    reasoningEffort != null -> {
                                        pendingSend = null
                                        onDraftChanged("")
                                        onReasoningSelected(reasoningEffort)
                                    }
                                    else -> {
                                        pendingSend = message to chat.messages.size
                                        onSend(message)
                                    }
                                }
                            },
                            enabled = canSend &&
                                composerEnabled &&
                                (draft.isNotBlank() || attachments.isNotEmpty()),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = semanticColors.active,
                                contentColor = semanticColors.onActive,
                                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            modifier = Modifier.heightIn(min = 40.dp),
                        ) {
                            Text("Send")
                        }
                    }
                }
            }
        }
    }
    if (showSessionInsights) {
        SessionInsightsSheet(
            sessionTitle = session.title,
            chat = chat,
            maintenanceAvailable = maintenanceAvailable,
            maintenanceEnabled = maintenanceEnabled,
            onRefresh = onLoadSessionInsights,
            onCompress = onCompressSession,
            onUndo = onUndoSession,
            onBranch = onBranchSession,
            onDismiss = { showSessionInsights = false },
        )
    }
}

private const val MAX_SUBAGENT_GUIDANCE_LENGTH = 512

@Composable
private fun DelegationControls(
    status: DelegationStatus,
    onSetPaused: (Boolean) -> Unit,
    onSteer: (String, String) -> Unit,
    onInterrupt: (String) -> Unit,
) {
    var steeringSubagent by remember { mutableStateOf<DelegatedSubagent?>(null) }
    var interruptingSubagentId by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Subagent controls" },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Subagent controls", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = { onSetPaused(!status.paused) },
                enabled = !status.actionLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = if (status.paused) {
                            "Resume spawning"
                        } else {
                            "Pause spawning"
                        }
                    },
            ) {
                Text(if (status.paused) "Resume spawning" else "Pause spawning")
            }
            status.active.forEach { subagent ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            subagent.goal,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            subagent.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { steeringSubagent = subagent },
                                enabled = !status.actionLoading,
                                modifier = Modifier.semantics {
                                    contentDescription = "Steer subagent ${subagent.subagentId}"
                                },
                                contentPadding = PaddingValues(horizontal = 14.dp),
                            ) {
                                Text("Steer")
                            }
                            Button(
                                onClick = { interruptingSubagentId = subagent.subagentId },
                                enabled = !status.actionLoading,
                                modifier = Modifier.semantics {
                                    contentDescription = "Interrupt subagent ${subagent.subagentId}"
                                },
                                contentPadding = PaddingValues(horizontal = 14.dp),
                            ) {
                                Text("Interrupt")
                            }
                        }
                    }
                }
            }
            status.notice?.let { notice ->
                Text(
                    notice.take(180),
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            status.error?.let { error ->
                Text(
                    error.take(180),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    steeringSubagent?.let { subagent ->
        var guidance by remember(subagent.subagentId) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { steeringSubagent = null },
            title = { Text("Steer subagent") },
            text = {
                OutlinedTextField(
                    value = guidance,
                    onValueChange = { guidance = it.take(MAX_SUBAGENT_GUIDANCE_LENGTH) },
                    label = { Text("Guidance") },
                    supportingText = {
                        Text("${guidance.length}/$MAX_SUBAGENT_GUIDANCE_LENGTH")
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Subagent guidance"
                    },
                    maxLines = 5,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = guidance.trim()
                        if (!status.actionLoading && trimmed.isNotEmpty()) {
                            steeringSubagent = null
                            onSteer(subagent.subagentId, trimmed)
                        }
                    },
                    enabled = !status.actionLoading && guidance.isNotBlank(),
                    modifier = Modifier.semantics {
                        contentDescription = "Confirm steer"
                    },
                ) {
                    Text("Steer")
                }
            },
            dismissButton = {
                TextButton(onClick = { steeringSubagent = null }) { Text("Cancel") }
            },
        )
    }
    interruptingSubagentId?.let { subagentId ->
        AlertDialog(
            onDismissRequest = { interruptingSubagentId = null },
            title = { Text("Interrupt subagent?") },
            text = { Text("Stop the active process-local subagent?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!status.actionLoading) {
                            interruptingSubagentId = null
                            onInterrupt(subagentId)
                        }
                    },
                    enabled = !status.actionLoading,
                    modifier = Modifier.semantics {
                        contentDescription = "Confirm interrupt subagent $subagentId"
                    },
                ) {
                    Text("Confirm interrupt")
                }
            },
            dismissButton = {
                TextButton(onClick = { interruptingSubagentId = null }) { Text("Cancel") }
            },
        )
    }
}

private enum class SessionMaintenanceAction {
    Compress,
    Undo,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionInsightsSheet(
    sessionTitle: String,
    chat: ChatSessionSnapshot,
    maintenanceAvailable: Boolean,
    maintenanceEnabled: Boolean,
    onRefresh: () -> Unit,
    onCompress: (String?) -> Unit,
    onUndo: () -> Unit,
    onBranch: (Int?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingAction by remember { mutableStateOf<SessionMaintenanceAction?>(null) }
    var branchDialogOpen by remember { mutableStateOf(false) }
    var branchName by remember(sessionTitle) { mutableStateOf("$sessionTitle branch") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Session details", style = MaterialTheme.typography.headlineSmall)
                TextButton(
                    onClick = onRefresh,
                    enabled = maintenanceAvailable && !chat.insightsLoading,
                ) {
                    Text("Refresh")
                }
            }
            if (chat.insightsLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Loading session details" },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("Loading session details…")
                }
            }
            chat.insightsError?.takeIf { maintenanceAvailable }?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Session details error" },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Could not load session details", style = MaterialTheme.typography.titleSmall)
                        Text(error.take(180))
                    }
                }
            }
            if (!chat.insightsLoading) {
                SessionUsageCard(chat)
                SessionContextCard(chat)
            }
            if (chat.maintenanceLoading ||
                chat.maintenanceError != null ||
                chat.notice != null
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Maintenance", style = MaterialTheme.typography.titleSmall)
                        if (chat.maintenanceLoading) {
                            Text("Applying session maintenance…")
                        }
                        chat.maintenanceError?.let { error ->
                            Text(
                                error.take(180),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        chat.notice?.let { notice ->
                            Text(
                                notice.take(180),
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
            if (maintenanceAvailable) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Session maintenance", style = MaterialTheme.typography.titleMedium)
                        if (!maintenanceEnabled && !chat.maintenanceLoading) {
                            Text(
                                "Available when the session is idle",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(
                            onClick = { pendingAction = SessionMaintenanceAction.Compress },
                            enabled = maintenanceEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Compress")
                        }
                        Button(
                            onClick = { pendingAction = SessionMaintenanceAction.Undo },
                            enabled = maintenanceEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Undo")
                        }
                        Button(
                            onClick = { branchDialogOpen = true },
                            enabled = maintenanceEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Branch")
                        }
                    }
                }
            }
        }
    }
    pendingAction?.let { action ->
        val title = when (action) {
            SessionMaintenanceAction.Compress -> "Compress session?"
            SessionMaintenanceAction.Undo -> "Undo last turn?"
        }
        val confirmLabel = when (action) {
            SessionMaintenanceAction.Compress -> "Confirm compression"
            SessionMaintenanceAction.Undo -> "Confirm undo"
        }
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(title) },
            text = {
                Text(
                    when (action) {
                        SessionMaintenanceAction.Compress -> "Compress this session context?"
                        SessionMaintenanceAction.Undo -> "Remove the last user turn from this session?"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAction = null
                        when (action) {
                            SessionMaintenanceAction.Compress -> onCompress(null)
                            SessionMaintenanceAction.Undo -> onUndo()
                        }
                    },
                    enabled = maintenanceEnabled,
                ) {
                    Text(confirmLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) { Text("Cancel") }
            },
        )
    }
    if (branchDialogOpen) {
        AlertDialog(
            onDismissRequest = { branchDialogOpen = false },
            title = { Text("Branch session") },
            text = {
                OutlinedTextField(
                    value = branchName,
                    onValueChange = { branchName = it },
                    label = { Text("Branch name") },
                    singleLine = true,
                    enabled = maintenanceEnabled,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = branchName.trim()
                        if (maintenanceEnabled && name.isNotEmpty()) {
                            branchDialogOpen = false
                            onBranch(null, name)
                        }
                    },
                    enabled = maintenanceEnabled && branchName.isNotBlank(),
                ) {
                    Text("Create branch")
                }
            },
            dismissButton = {
                TextButton(onClick = { branchDialogOpen = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SessionUsageCard(chat: ChatSessionSnapshot) {
    val usage = chat.sessionUsage
    val context = chat.contextBreakdown
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Token usage", style = MaterialTheme.typography.titleMedium)
            SessionInsightMetric("Input tokens", formatSessionTokens(usage?.inputTokens))
            SessionInsightMetric("Output tokens", formatSessionTokens(usage?.outputTokens))
            SessionInsightMetric("Total tokens", formatSessionTokens(usage?.totalTokens))
            Text("Context used", style = MaterialTheme.typography.labelLarge)
            Text(
                formatContextSummary(
                    used = usage?.contextUsedTokens ?: context?.usedTokens,
                    max = usage?.contextMaxTokens ?: context?.maxTokens,
                    percent = usage?.contextPercent ?: context?.percent,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Model: ${chat.model?.takeIf(String::isNotBlank) ?: "Unknown"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SessionContextCard(chat: ChatSessionSnapshot) {
    val categories = chat.contextBreakdown?.categories.orEmpty()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Context categories", style = MaterialTheme.typography.titleMedium)
            if (categories.isEmpty()) {
                Text(
                    "No context categories reported",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                categories.forEach { category ->
                    ContextCategoryRow(category)
                }
            }
        }
    }
}

@Composable
private fun ContextCategoryRow(category: ContextBreakdownCategory) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(category.name, modifier = Modifier.weight(1f))
        Text(
            "${formatSessionTokens(category.tokens)} tokens",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun SessionInsightMetric(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatSessionTokens(value: Long?): String =
    value?.let { String.format(Locale.US, "%,d", it) } ?: "—"

private fun formatContextSummary(used: Long?, max: Long?, percent: Double?): String {
    val tokenSummary = when {
        used != null && max != null -> "${formatSessionTokens(used)} / ${formatSessionTokens(max)}"
        used != null -> formatSessionTokens(used)
        max != null -> "— / ${formatSessionTokens(max)}"
        else -> "—"
    }
    return if (percent == null) tokenSummary else "$tokenSummary (${formatPercent(percent)}%)"
}

private fun formatPercent(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(Locale.US, "%.1f", value)

@Composable
private fun RunStateContent(
    runState: RunEventState,
    durableSessionId: DurableSessionId,
    onClarificationResponse: (String, String) -> Unit,
    onApprovalResponse: (String, Boolean) -> Unit,
) {
    if (!runState.hasVisibleContent()) return
    val runningTools = runState.tools.filter { it.state == RunToolState.Running }
    val completedTools = runState.tools.filter { it.state == RunToolState.Completed }
    var completedToolsExpanded by remember(durableSessionId.value) {
        mutableStateOf(false)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        runState.status?.let { status -> RunStatusPill(status) }
        runningTools.forEach { tool ->
            key(tool.toolId) {
                RunToolRowContent(tool)
            }
        }
        if (completedTools.isNotEmpty()) {
            CompletedToolsSummary(
                count = completedTools.size,
                expanded = completedToolsExpanded,
                onToggle = { completedToolsExpanded = !completedToolsExpanded },
            )
            if (completedToolsExpanded) {
                completedTools.forEach { tool ->
                    key(tool.toolId) {
                        RunToolRowContent(tool)
                    }
                }
            }
        }
        runState.clarification?.let { clarification ->
            ClarificationCard(
                durableSessionId = durableSessionId,
                interaction = clarification,
                onResponse = onClarificationResponse,
            )
        }
        runState.approval?.let { approval ->
            ApprovalCard(
                durableSessionId = durableSessionId,
                interaction = approval,
                onResponse = onApprovalResponse,
            )
        }
        runState.unsupportedBlocking?.let { interaction ->
            UnsupportedBlockingCard(interaction)
        }
    }
}

private fun RunEventState.hasVisibleContent(): Boolean =
    status != null ||
        tools.isNotEmpty() ||
        clarification != null ||
        approval != null ||
        unsupportedBlocking != null

@Composable
private fun CompletedToolsSummary(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val noun = if (count == 1) "tool" else "tools"
    TextButton(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$count completed $noun, ${if (expanded) "expanded" else "collapsed"}"
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✓", color = LocalHermesSemanticColors.current.completed)
            Text(
                "$count $noun completed",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(if (expanded) "Hide" else "Show")
        }
    }
}

@Composable
private fun ClarificationCard(
    durableSessionId: DurableSessionId,
    interaction: ClarificationInteraction,
    onResponse: (String, String) -> Unit,
) {
    var answer by remember(interaction.requestId) { mutableStateOf("") }
    var selectedChoices by remember(interaction.requestId) { mutableStateOf(emptySet<String>()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Clarification", style = MaterialTheme.typography.titleSmall)
            Text(interaction.question)
            if (interaction.lifecycle == RunInteractionLifecycle.Pending) {
                if (interaction.choices.isEmpty()) {
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { answer = it },
                        label = { Text("Response") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = true,
                    )
                    Button(
                        enabled = answer.isNotBlank(),
                        onClick = dropUnlessResumed {
                            onResponse(interaction.requestId, answer.trim())
                        },
                    ) {
                        Text("Send response")
                    }
                } else if (interaction.multiSelect) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        interaction.choices.forEach { choice ->
                            FilterChip(
                                selected = choice in selectedChoices,
                                onClick = {
                                    selectedChoices = if (choice in selectedChoices) {
                                        selectedChoices - choice
                                    } else {
                                        selectedChoices + choice
                                    }
                                },
                                label = { Text(choice) },
                            )
                        }
                    }
                    Button(
                        enabled = selectedChoices.isNotEmpty(),
                        onClick = dropUnlessResumed {
                            val answer = interaction.choices
                                .filter { it in selectedChoices }
                                .joinToString(", ")
                            onResponse(interaction.requestId, answer)
                        },
                    ) {
                        Text("Send response")
                    }
                } else {
                    interaction.choices.forEach { choice ->
                        Button(
                            onClick = dropUnlessResumed {
                                onResponse(interaction.requestId, choice)
                            },
                        ) {
                            Text(choice)
                        }
                    }
                }
            } else {
                Text("Clarification response", style = MaterialTheme.typography.labelMedium)
                Text(interaction.lifecycle.name)
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    durableSessionId: DurableSessionId,
    interaction: ApprovalInteraction,
    onResponse: (String, Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (interaction.lifecycle == RunInteractionLifecycle.Pending) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
            contentColor = if (interaction.lifecycle == RunInteractionLifecycle.Pending) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = if (interaction.lifecycle == RunInteractionLifecycle.Pending) {
                    "Approval pending"
                } else {
                    "Approval ${interaction.lifecycle.name}"
                }
                stateDescription = interaction.lifecycle.name
            },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Approval",
                color = if (interaction.lifecycle == RunInteractionLifecycle.Pending) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                style = MaterialTheme.typography.titleSmall,
            )
            interaction.commandPreview?.takeIf(String::isNotBlank)?.let {
                Text("Command preview: $it")
            }
            interaction.descriptionPreview?.takeIf(String::isNotBlank)?.let {
                Text("Description preview: $it")
            }
            if (interaction.lifecycle == RunInteractionLifecycle.Pending) {
                interaction.choices.forEach { choice ->
                    Button(
                        onClick = dropUnlessResumed {
                            onResponse(choice, false)
                        },
                    ) {
                        Text(choice)
                    }
                }
            } else {
                Text("Approval response", style = MaterialTheme.typography.labelMedium)
                Text(interaction.lifecycle.name)
            }
        }
    }
}

@Composable
private fun UnsupportedBlockingCard(interaction: UnsupportedBlockingInteraction) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("${interaction.kind.name} request", style = MaterialTheme.typography.titleSmall)
            Text("This Android client cannot answer this request.")
            Text("Continue in another connected Hermes client.")
            Text("Request status: ${interaction.lifecycle.name}")
        }
    }
}

@Composable
private fun RunStatusPill(status: RunStatus) {
    val semanticColors = LocalHermesSemanticColors.current
    Surface(
        shape = MaterialTheme.shapes.small,
        color = semanticColors.active,
        contentColor = semanticColors.onActive,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Current status: ${status.kind} — ${status.text}"
                stateDescription = "Current"
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(status.kind, style = MaterialTheme.typography.labelMedium)
            Text(status.text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RunToolRowContent(tool: RunToolRow) {
    val semanticColors = LocalHermesSemanticColors.current
    val toolContext = tool.context?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
    val toolSummary = tool.summary?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
    val description = when (tool.state) {
        RunToolState.Running -> "Running tool ${tool.name}$toolContext"
        RunToolState.Completed -> "Completed tool ${tool.name}$toolSummary"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = description
                stateDescription = tool.state.name
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (tool.state) {
            RunToolState.Running -> CircularProgressIndicator(
                modifier = Modifier
                    .size(18.dp)
                    .semantics { contentDescription = "Running" },
                color = semanticColors.active,
                strokeWidth = 2.dp,
            )
            RunToolState.Completed -> Text(
                "✓",
                modifier = Modifier.semantics { contentDescription = "Completed" },
                color = semanticColors.completed,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(tool.name, style = MaterialTheme.typography.bodyMedium)
            tool.context?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            tool.summary?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Text(
            tool.state.name,
            color = when (tool.state) {
                RunToolState.Running -> semanticColors.active
                RunToolState.Completed -> semanticColors.completed
            },
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun resolvePickedAttachment(context: Context, uri: Uri): ComposerAttachment {
    require(uri.scheme == "content") { "Selected item was not a readable document" }
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    var rawName: String? = null
    var sizeBytes = -1L
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameColumn >= 0 && !cursor.isNull(nameColumn)) rawName = cursor.getString(nameColumn)
            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) sizeBytes = cursor.getLong(sizeColumn)
        }
    }
    val displayName = AttachmentPolicy.sanitizeDisplayName(
        rawName ?: uri.lastPathSegment.orEmpty(),
    )
    return ComposerAttachment(
        id = uri.toString(),
        uri = uri.toString(),
        displayName = displayName,
        mimeType = context.contentResolver.getType(uri)?.takeIf(String::isNotBlank),
        sizeBytes = sizeBytes,
    )
}

@Composable
private fun SessionPlaceholder() {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("Session placeholder surface"),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Select a session", style = MaterialTheme.typography.titleMedium)
        }
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
