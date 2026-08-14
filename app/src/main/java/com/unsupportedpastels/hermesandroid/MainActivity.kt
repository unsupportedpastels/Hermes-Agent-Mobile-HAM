package com.unsupportedpastels.hermesandroid

import android.os.Bundle
import android.content.Intent
import android.Manifest
import android.os.Build

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.connection.HermesConnectionViewModel
import com.unsupportedpastels.hermesandroid.connection.HermesAppForeground
import com.unsupportedpastels.hermesandroid.connection.HermesWindowFocus
import com.unsupportedpastels.hermesandroid.connection.ModelPickerState
import com.unsupportedpastels.hermesandroid.gateway.ModelSwitchResult
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsState
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsViewModel
import com.unsupportedpastels.hermesandroid.connection.launchBrowserAndAwaitReturn
import com.unsupportedpastels.hermesandroid.connection.SlashCompletionState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.notifications.NotificationNavigationInbox
import com.unsupportedpastels.hermesandroid.notifications.SessionNotificationVisibilityRegistry
import com.unsupportedpastels.hermesandroid.notifications.synchronizeVisibleSessionNotifications
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import com.unsupportedpastels.hermesandroid.ui.HermesApp
import com.unsupportedpastels.hermesandroid.ui.ProjectIconAssignmentsState
import com.unsupportedpastels.hermesandroid.ui.ProjectIconViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    private val serverSettingsViewModel by viewModels<ServerSettingsViewModel> {
        ServerSettingsViewModel.Factory(this)
    }
    private val connectionViewModel by viewModels<HermesConnectionViewModel> {
        HermesConnectionViewModel.ProductionFactory(
            context = applicationContext,
            settingsStates = serverSettingsViewModel.states,
        )
    }
    private val projectIconViewModel by viewModels<ProjectIconViewModel> {
        ProjectIconViewModel.Factory(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            HermesAndroidTheme {
                val snapshot by connectionViewModel.snapshots.collectAsStateWithLifecycle()
                val notificationRequest by NotificationNavigationInbox.requests.collectAsStateWithLifecycle()
                HermesAppHost(
                    viewModel = serverSettingsViewModel,
                    connectionViewModel = connectionViewModel,
                    projectIconViewModel = projectIconViewModel,
                    snapshot = snapshot,
                    requestedSessionId = notificationRequest?.sessionId,
                    requestedSessionRequestId = notificationRequest?.requestId,
                    onVisibleSessionChanged = { sessionId ->
                        SessionNotificationVisibilityRegistry.publishVisibleSession(sessionId)
                        synchronizeVisibleSessionNotifications(this)
                    },
                    onSignIn = {
                        connectionViewModel.signIn { authorizationUrl ->
                            launchBrowserAndAwaitReturn(HermesWindowFocus.state) {
                                startActivity(
                                    Intent(Intent.ACTION_VIEW, authorizationUrl.toUri()),
                                )
                            }
                        }
                    },
                    onOpenProject = connectionViewModel::openProject,
                    onCreateProjectSession = { projectId ->
                        connectionViewModel.createProjectSession(projectId, "New task")
                    },
                    onOpenSession = connectionViewModel::openSession,
                    onSendMessage = connectionViewModel::sendMessage,
                    onReasoningSelected = connectionViewModel::setReasoningEffort,
                    onClarificationResponse = { sessionId, requestId, answer ->
                        connectionViewModel.respondToClarification(sessionId, requestId, answer)
                    },
                    onApprovalResponse = { sessionId, choice, all ->
                        connectionViewModel.respondToApproval(sessionId, choice, all)
                    },
                    onStopSession = connectionViewModel::stopSession,
                )
            }
        }
    }


    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        HermesWindowFocus.state.value = hasFocus
        SessionNotificationVisibilityRegistry.publishWindowFocused(hasFocus)
        synchronizeVisibleSessionNotifications(this)
    }

    override fun onStart() {
        super.onStart()
        HermesAppForeground.publish(true)
        SessionNotificationVisibilityRegistry.publishAppForeground(true)
        synchronizeVisibleSessionNotifications(this)
    }

    override fun onStop() {
        HermesAppForeground.publish(false)
        SessionNotificationVisibilityRegistry.publishAppForeground(false)
        super.onStop()
    }
}

@Composable
internal fun HermesAppHost(
    viewModel: ServerSettingsViewModel,
    connectionViewModel: HermesConnectionViewModel? = null,
    projectIconViewModel: ProjectIconViewModel? = null,
    snapshot: HermesGatewaySnapshot,
    requestedSessionId: DurableSessionId? = null,
    requestedSessionRequestId: Long? = null,
    onVisibleSessionChanged: (DurableSessionId?) -> Unit = {},
    onSignIn: () -> Unit = {},
    onOpenProject: (ProjectId) -> Unit = {},
    onCreateProjectSession: (ProjectId) -> DurableSessionId? = { null },
    onOpenSession: (DurableSessionId) -> Unit = {},
    onSendMessage: (DurableSessionId, String) -> Unit = { _, _ -> },
    onReasoningSelected: (DurableSessionId, String) -> Unit = { _, _ -> },
    onClarificationResponse: (DurableSessionId, String, String) -> Unit = { _, _, _ -> },
    onApprovalResponse: (DurableSessionId, String, Boolean) -> Unit = { _, _, _ -> },
    onStopSession: (DurableSessionId) -> Unit = {},
) {
    val serverSettingsState by viewModel.states.collectAsStateWithLifecycle()
    val projectIconAssignmentsFlow = projectIconViewModel?.assignments
        ?: remember {
            MutableStateFlow<ProjectIconAssignmentsState>(ProjectIconAssignmentsState.Loading)
        }
    val projectIconAssignments by projectIconAssignmentsFlow.collectAsStateWithLifecycle()
    val currentServerOrigin = (serverSettingsState as? ServerSettingsState.Ready)?.serverOrigin
    val projectIcons = (projectIconAssignments as? ProjectIconAssignmentsState.Ready)
        ?.assignments
        .orEmpty()
        .filterKeys { it.serverOrigin == currentServerOrigin }
        .mapKeys { it.key.projectId }
    val slashCompletionsFlow = connectionViewModel?.slashCompletions
        ?: remember { MutableStateFlow(emptyMap<DurableSessionId, SlashCompletionState>()) }
    val slashCompletions by slashCompletionsFlow.collectAsStateWithLifecycle()
    val attachmentsFlow = connectionViewModel?.attachments
        ?: remember { MutableStateFlow(emptyMap<DurableSessionId, List<ComposerAttachment>>()) }
    val attachments by attachmentsFlow.collectAsStateWithLifecycle()
    val modelPickerFlow = connectionViewModel?.modelPickerState
        ?: remember { MutableStateFlow<ModelPickerState>(ModelPickerState.Closed) }
    val modelPickerState by modelPickerFlow.collectAsStateWithLifecycle()
    val homeRefreshingFlow = connectionViewModel?.homeRefreshing
        ?: remember { MutableStateFlow(false) }
    val homeRefreshing by homeRefreshingFlow.collectAsStateWithLifecycle()

    HermesApp(
        snapshot = snapshot,
        requestedSessionId = requestedSessionId,
        requestedSessionRequestId = requestedSessionRequestId,
        onVisibleSessionChanged = onVisibleSessionChanged,
        serverSettingsState = serverSettingsState,
        onSaveServerOrigin = { origin -> viewModel.save(origin).await() },
        onLoadManagementSettings = { profile -> connectionViewModel?.loadManagementSettings(profile) },
        onSetProfileDefaultModel = { selection, confirm ->
            connectionViewModel?.setProfileDefaultModel(selection, confirm)
                ?: ModelSwitchResult(accepted = false)
        },
        onLogout = { connectionViewModel?.logout() },
        onSignIn = onSignIn,
        onOpenProject = onOpenProject,
        onCreateProjectSession = onCreateProjectSession,
        onOpenSession = onOpenSession,
        isHomeRefreshing = homeRefreshing,
        onRefreshHome = { connectionViewModel?.refreshHomeData() },
        onRenameSession = { sessionId, title ->
            connectionViewModel?.let { vm -> resultPreservingCancellation { vm.renameSession(sessionId, title) } }
                ?: Result.failure(IllegalStateException("Session management unavailable"))
        },
        onSetSessionPinned = { sessionId, pinned ->
            connectionViewModel?.let { vm -> resultPreservingCancellation { vm.setSessionPinned(sessionId, pinned) } }
                ?: Result.failure(IllegalStateException("Session management unavailable"))
        },
        onSetSessionArchived = { sessionId, archived ->
            connectionViewModel?.let { vm -> resultPreservingCancellation { vm.setSessionArchived(sessionId, archived) } }
                ?: Result.failure(IllegalStateException("Session management unavailable"))
        },
        onDeleteSession = { sessionId ->
            connectionViewModel?.let { vm -> resultPreservingCancellation { vm.deleteSession(sessionId) } }
                ?: Result.failure(IllegalStateException("Session management unavailable"))
        },
        onSearchTranscripts = { query -> connectionViewModel?.searchTranscripts(query) },
        onSendMessage = onSendMessage,
        onReasoningSelected = onReasoningSelected,
        onClarificationResponse = onClarificationResponse,
        onApprovalResponse = onApprovalResponse,
        onStopSession = onStopSession,
        onCreateSession = { connectionViewModel?.createNewSession() },
        onLoadHostDirectories = { path ->
            connectionViewModel?.let { viewModel ->
                resultPreservingCancellation { viewModel.loadHostDirectories(path) }
            } ?: Result.failure(IllegalStateException("Host folder browsing unavailable"))
        },
        onCreateHostDirectory = { parentPath, name ->
            connectionViewModel?.let { viewModel ->
                resultPreservingCancellation { viewModel.createHostDirectory(parentPath, name) }
            } ?: Result.failure(IllegalStateException("Host folder creation unavailable"))
        },
        onCreateProject = { name, path ->
            connectionViewModel?.let { viewModel ->
                resultPreservingCancellation { viewModel.createProject(name, path) }
            } ?: Result.failure(IllegalStateException("Project creation unavailable"))
        },
        onLoadManagedImage = { path ->
            connectionViewModel?.let { viewModel ->
                resultPreservingCancellation { viewModel.downloadManagedImage(path) }
            } ?: Result.failure(IllegalStateException("Managed images unavailable"))
        },
        modelPickerState = modelPickerState,
        onOpenModelPicker = { sessionId -> connectionViewModel?.openModelPicker(sessionId) },
        onDismissModelPicker = { connectionViewModel?.dismissModelPicker() },
        onRetryModelPicker = { connectionViewModel?.retryModelPicker() },
        onModelSelected = { selection -> connectionViewModel?.selectModel(selection) },
        onConfirmModelSelection = { connectionViewModel?.confirmModelSelection() },
        slashCompletions = slashCompletions,
        attachments = attachments,
        onAddAttachments = { sessionId, candidates ->
            connectionViewModel?.addAttachments(sessionId, candidates).orEmpty()
        },
        onRemoveAttachment = { sessionId, attachmentId ->
            connectionViewModel?.removeAttachment(sessionId, attachmentId)
        },
        projectIcons = projectIcons,
        onSaveProjectIcon = { projectId, iconId ->
            val origin = currentServerOrigin
            if (origin == null || projectIconViewModel == null) {
                Result.failure(IllegalStateException("Project icon persistence unavailable"))
            } else {
                projectIconViewModel.save(origin, projectId, iconId).await()
            }
        },
        onSlashCompletionRequested = { sessionId, text ->
            connectionViewModel?.updateSlashCompletion(sessionId, text)
        },
    )
}

private suspend fun <T> resultPreservingCancellation(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
