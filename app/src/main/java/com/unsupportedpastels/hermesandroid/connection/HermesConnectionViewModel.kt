package com.unsupportedpastels.hermesandroid.connection

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.ProjectLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSessionLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSessionsResult
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.ProjectTreeResult
import com.unsupportedpastels.hermesandroid.app.RunEventState
import com.unsupportedpastels.hermesandroid.app.RunInteractionLifecycle
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.app.reconcileProjectSession
import com.unsupportedpastels.hermesandroid.app.validProjectWorkspacePath
import com.unsupportedpastels.hermesandroid.attachment.AttachmentAddResult
import com.unsupportedpastels.hermesandroid.attachment.AttachmentByteReader
import com.unsupportedpastels.hermesandroid.attachment.AttachmentPolicy
import com.unsupportedpastels.hermesandroid.attachment.AttachmentReadException
import com.unsupportedpastels.hermesandroid.attachment.AttachmentStager
import com.unsupportedpastels.hermesandroid.attachment.ContentAttachmentByteReader
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ActiveRuntimeSession
import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.ChatBillingNotice
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesChatConnector
import com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent
import com.unsupportedpastels.hermesandroid.gateway.HermesChatException
import com.unsupportedpastels.hermesandroid.gateway.HermesChatMethodNotFoundException
import com.unsupportedpastels.hermesandroid.gateway.HermesChatProtocolException
import com.unsupportedpastels.hermesandroid.gateway.HermesChatResponseStatus
import com.unsupportedpastels.hermesandroid.gateway.HermesChatGateway
import com.unsupportedpastels.hermesandroid.gateway.HermesChatSession
import com.unsupportedpastels.hermesandroid.gateway.HermesChatTransportException
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryListing
import com.unsupportedpastels.hermesandroid.gateway.HERMES_CHAT_MAX_FRAME_BYTES
import com.unsupportedpastels.hermesandroid.gateway.HERMES_CHAT_MAX_MESSAGE_TEXT_CHARS
import com.unsupportedpastels.hermesandroid.gateway.KtorChatWebSocketFactory
import com.unsupportedpastels.hermesandroid.notifications.AndroidTurnNotificationController
import com.unsupportedpastels.hermesandroid.notifications.NoOpTurnNotificationController
import com.unsupportedpastels.hermesandroid.notifications.TurnNotificationController
import com.unsupportedpastels.hermesandroid.gateway.KtorWsTicketClient
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection
import com.unsupportedpastels.hermesandroid.gateway.ModelSwitchResult
import com.unsupportedpastels.hermesandroid.gateway.ResumedChatSession
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import com.unsupportedpastels.hermesandroid.gateway.RuntimeAccess
import com.unsupportedpastels.hermesandroid.gateway.SlashCompletionItem
import com.unsupportedpastels.hermesandroid.gateway.SlashCompletionResult
import com.unsupportedpastels.hermesandroid.gateway.canonicalReasoningEffort
import com.unsupportedpastels.hermesandroid.ui.isSlashCommandContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val TOKEN_REFRESH_SKEW_SECONDS = 30L
private const val MAX_CHAT_RECOVERIES_PER_OPERATION = 2
private const val MAX_SESSION_TITLE_CHARS = 256
private const val SLASH_COMPLETION_DEBOUNCE_MS = 60L

internal suspend fun <Probe, SavedToken : Any> probeAndLoadSavedTokenConcurrently(
    probe: suspend () -> Probe,
    loadSavedToken: suspend () -> SavedToken?,
    needsSavedToken: (Probe) -> Boolean,
): Pair<Probe, SavedToken?> = supervisorScope {
    val savedToken = async { loadSavedToken() }
    try {
        val probeResult = probe()
        if (needsSavedToken(probeResult)) {
            probeResult to savedToken.await()
        } else {
            savedToken.cancel()
            probeResult to null
        }
    } catch (cancelled: CancellationException) {
        savedToken.cancel()
        throw cancelled
    } catch (error: Throwable) {
        savedToken.cancel()
        throw error
    }
}

internal suspend fun <Authentication, Metadata> authenticateAndPrefetchConcurrently(
    authenticate: suspend () -> Authentication,
    prefetchMetadata: suspend () -> Metadata,
    discardMetadata: suspend (Metadata) -> Unit,
): Pair<Authentication, Result<Metadata>> = supervisorScope {
    val authentication = async { authenticate() }
    val metadata = async { prefetchMetadata() }
    try {
        val authenticated = authentication.await()
        val prefetched = try {
            Result.success(metadata.await())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        authenticated to prefetched
    } catch (error: Throwable) {
        metadata.cancel()
        val completedMetadata = withContext(NonCancellable) {
            runCatching { metadata.await() }.getOrNull()
        }
        completedMetadata?.let { runCatching { discardMetadata(it) } }
        throw error
    }
}

/** Published slash-completion menu state for one composer. */
data class SlashCompletionState(
    val composerText: String,
    val items: List<SlashCompletionItem>,
    val replaceFrom: Int,
)

sealed interface ModelPickerState {
    data object Closed : ModelPickerState

    data class Loading(val durableSessionId: DurableSessionId) : ModelPickerState

    data class Ready(
        val durableSessionId: DurableSessionId,
        val options: ModelOptions,
        val applying: Boolean = false,
        val error: String? = null,
        val pendingSelection: ModelSelection? = null,
        val confirmationMessage: String? = null,
    ) : ModelPickerState

    data class Error(
        val durableSessionId: DurableSessionId,
        val message: String,
    ) : ModelPickerState
}

private data class ActiveTokenRecord(
    val origin: ServerOrigin,
    val generation: Long,
    val tokens: NativeTokenSet,
)

private data class ProjectMetadataSessionRecord(
    val origin: ServerOrigin,
    val generation: Long,
    val accessToken: String,
    val session: HermesChatSession,
)

private class ChatRecoveryState(
    val operationGeneration: Long,
    var remaining: Int = MAX_CHAT_RECOVERIES_PER_OPERATION,
    var activeAttempt: ChatRecoveryAttempt? = null,
)

private class ChatRecoveryAttempt(
    val state: ChatRecoveryState,
)

private data class LiveChatController(
    val durableSessionId: DurableSessionId,
    val session: HermesChatSession,
    val runtimeSessionId: RuntimeSessionId,
    var operationGeneration: Long,
    var eventJob: Job? = null,
    var recoveryState: ChatRecoveryState? = null,
)

private data class ControllerOperation(
    val durableSessionId: DurableSessionId,
    val session: HermesChatSession,
    val runtimeSessionId: RuntimeSessionId,
    val origin: ServerOrigin,
    val originGeneration: Long,
    val chatOperationGeneration: Long,
    val requestId: String? = null,
    val advertisedChoices: List<String> = emptyList(),
)

private fun HermesGatewaySnapshot.mapSession(
    sessionId: DurableSessionId,
    transform: (SessionSummary) -> SessionSummary,
): HermesGatewaySnapshot = copy(
    durableSessions = durableSessions.map { if (it.id == sessionId) transform(it) else it },
    projects = projects.map { project ->
        project.copy(previewSessions = project.previewSessions.map {
            if (it.id == sessionId) transform(it) else it
        })
    },
    projectSessions = projectSessions.mapValues { (_, sessions) ->
        sessions.map { if (it.id == sessionId) transform(it) else it }
    },
)

private fun HermesGatewaySnapshot.removeSession(sessionId: DurableSessionId): HermesGatewaySnapshot = copy(
    durableSessions = durableSessions.filterNot { it.id == sessionId },
    projects = projects.map { project ->
        project.copy(
            sessionCount = (project.sessionCount - project.previewSessions.count { it.id == sessionId })
                .coerceAtLeast(0),
            previewSessions = project.previewSessions.filterNot { it.id == sessionId },
        )
    },
    projectSessions = projectSessions.mapValues { (_, sessions) ->
        sessions.filterNot { it.id == sessionId }
    },
    chatSessions = chatSessions - sessionId,
    activeRuntimes = activeRuntimes.filterNot { it.durableSessionId == sessionId },
)

class HermesConnectionViewModel(
    settingsStates: Flow<ServerSettingsState>,
    private val client: HermesConnectionClient,
    private val nativeLogin: NativeLogin? = null,
    private val closeResources: () -> Unit = {},
    private val tokenStore: NativeTokenStore? = null,
    private val refreshClient: NativeRefreshClient? = null,
    private val chatConnector: HermesChatConnector? = null,
    private val projectConnector: HermesChatConnector? = null,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val attachmentReader: AttachmentByteReader =
        AttachmentByteReader { throw AttachmentReadException("Attachment reading is not available") },
    private val appForegroundStates: StateFlow<Boolean> = MutableStateFlow(true),
    private val notifications: TurnNotificationController = NoOpTurnNotificationController,
) : ViewModel() {
    private val mutableSnapshots = MutableStateFlow(HermesGatewaySnapshot())
    val snapshots: StateFlow<HermesGatewaySnapshot> = mutableSnapshots.asStateFlow()

    private val _homeRefreshing = MutableStateFlow(false)
    val homeRefreshing: StateFlow<Boolean> = _homeRefreshing.asStateFlow()

    private var activeOrigin: ServerOrigin? = null
    private var activeTokens: ActiveTokenRecord? = null
    private var generation = 0L
    private var connectionJob: Job? = null
    private var projectLoadJob: Job? = null
    private var refreshHomeJob: Job? = null
    private var managementJob: Job? = null
    private var searchJob: Job? = null
    private val projectSessionJobs = mutableMapOf<ProjectId, Job>()
    private val projectSessionGenerations = mutableMapOf<ProjectId, Long>()
    private var nextProjectSessionGeneration = 0L
    private val projectMetadataLock = Any()
    private var activeProjectMetadataSession: ProjectMetadataSessionRecord? = null
    private var signInJob: Job? = null
    private var nextChatOperationGeneration = 0L
    private val chatJobs = mutableMapOf<DurableSessionId, Job>()
    private val chatOperationGenerations = mutableMapOf<DurableSessionId, Long>()
    private val liveControllers = mutableMapOf<DurableSessionId, LiveChatController>()
    private val activeTurnIds = mutableSetOf<DurableSessionId>()
    // Selected-session compatibility state. Live event ownership is held by
    // [liveControllers], so changing the visible session does not close peers.
    private var chatJob: Job? = null
    private var chatOperationGeneration = 0L
    private var eventJob: Job? = null
    private var activeChatSession: HermesChatSession? = null
    private var activeChatDurableId: DurableSessionId? = null
    private var activeRuntimeSessionId: RuntimeSessionId? = null
    private var chatRecoveryState: ChatRecoveryState? = null
    private val controllerLock = Any()

    private val mutableSlashCompletions =
        MutableStateFlow<Map<DurableSessionId, SlashCompletionState>>(emptyMap())
    val slashCompletions: StateFlow<Map<DurableSessionId, SlashCompletionState>> =
        mutableSlashCompletions.asStateFlow()
    private val slashCompletionJobs = mutableMapOf<DurableSessionId, Job>()
    private val slashCompletionGenerations = mutableMapOf<DurableSessionId, Long>()

    private val mutableModelPickerState = MutableStateFlow<ModelPickerState>(ModelPickerState.Closed)
    val modelPickerState: StateFlow<ModelPickerState> = mutableModelPickerState.asStateFlow()
    private var modelPickerJob: Job? = null
    private var modelPickerGeneration = 0L

    /** Composer attachments staged per session; uploaded to the host at send time. */
    private val mutableAttachments =
        MutableStateFlow<Map<DurableSessionId, List<ComposerAttachment>>>(emptyMap())
    val attachments: StateFlow<Map<DurableSessionId, List<ComposerAttachment>>> =
        mutableAttachments.asStateFlow()

    /** Maps local draft IDs to the canonical durable IDs returned by session.create. */
    private val serverDurableIds = mutableMapOf<DurableSessionId, DurableSessionId>()

    /** Locally created chat drafts not yet persisted server-side (no durable row). */
    private val pendingDraftSessions = mutableSetOf<DurableSessionId>()
    private var draftCounter = 0L

    init {
        viewModelScope.launch {
            settingsStates.collect { settingsState ->
                val currentGeneration = ++generation
                connectionJob?.cancel()
                projectLoadJob?.cancel()
                projectLoadJob = null
                projectSessionJobs.values.forEach(Job::cancel)
                projectSessionJobs.clear()
                projectSessionGenerations.clear()
                nextChatOperationGeneration += 1
                signInJob?.cancel()
                chatJobs.values.forEach(Job::cancel)
                chatJobs.clear()
                chatOperationGenerations.clear()
                modelPickerGeneration += 1
                modelPickerJob?.cancel()
                modelPickerJob = null
                mutableModelPickerState.value = ModelPickerState.Closed
                disconnectProjectMetadata()
                disconnectChat()

                serverDurableIds.clear()
                mutableAttachments.value = emptyMap()
                activeTokens = null
                activeOrigin = (settingsState as? ServerSettingsState.Ready)?.serverOrigin
                when (settingsState) {
                    ServerSettingsState.Loading -> mutableSnapshots.value = HermesGatewaySnapshot()
                    ServerSettingsState.Unavailable -> {
                        mutableSnapshots.value = HermesGatewaySnapshot(
                            connectionError = "Server settings unavailable",
                        )
                    }
                    is ServerSettingsState.Ready -> {
                        connectionJob = viewModelScope.launch {
                            connect(settingsState.serverOrigin, currentGeneration)
                        }
                    }
                }
            }
        }
    }

    private suspend fun connect(serverOrigin: ServerOrigin?, currentGeneration: Long) {
        if (serverOrigin == null) {
            mutableSnapshots.value = HermesGatewaySnapshot()
            return
        }

        mutableSnapshots.value = HermesGatewaySnapshot(connectionState = ConnectionState.Connecting)
        try {
            val store = tokenStore
            val startup = if (store != null) {
                probeAndLoadSavedTokenConcurrently(
                    probe = { client.probe(serverOrigin) },
                    loadSavedToken = { store.load(serverOrigin) },
                    needsSavedToken = { it.authRequired },
                )
            } else {
                client.probe(serverOrigin) to null
            }
            val info = startup.first
            val stored = startup.second
            currentCoroutineContext().ensureActive()
            if (generation != currentGeneration || activeOrigin != serverOrigin) return

            if (!info.authRequired) {
                mutableSnapshots.value = HermesGatewaySnapshot(
                    connectionState = ConnectionState.Connected,
                    authenticationState = AuthenticationState.NotRequired,
                    serverVersion = info.version,
                    nativeOAuthSupported = info.nativeOAuthSupported,
                    authProviders = info.providers,
                    durableSessions = info.sessions,
                )
                return
            }

            val usableTokens = stored?.let { refreshIfNeeded(serverOrigin, it) }
            currentCoroutineContext().ensureActive()
            if (generation != currentGeneration || activeOrigin != serverOrigin) return
            if (usableTokens != null) {
                if (store != null && usableTokens != stored) {
                    store.save(serverOrigin, usableTokens)
                    currentCoroutineContext().ensureActive()
                    if (generation != currentGeneration || activeOrigin != serverOrigin) return
                }
                val authenticated: AuthenticatedHermesConnection
                val prefetchedSession: HermesChatSession?
                if (projectConnector != null) {
                    val concurrentResult = authenticateAndPrefetchConcurrently(
                        authenticate = {
                            client.authenticate(serverOrigin, usableTokens.accessToken)
                        },
                        prefetchMetadata = {
                            connectProjectMetadataCandidate(
                                serverOrigin = serverOrigin,
                                originGeneration = currentGeneration,
                                accessToken = usableTokens.accessToken,
                            )
                        },
                        discardMetadata = { candidate ->
                            closeChatSessionNonCancellably(candidate)
                        },
                    )
                    authenticated = concurrentResult.first
                    prefetchedSession = concurrentResult.second.getOrNull()
                } else {
                    authenticated = client.authenticate(serverOrigin, usableTokens.accessToken)
                    prefetchedSession = null
                }
                try {
                    currentCoroutineContext().ensureActive()
                } catch (cancelled: CancellationException) {
                    closeChatSessionNonCancellably(prefetchedSession)
                    throw cancelled
                }
                if (generation != currentGeneration || activeOrigin != serverOrigin) {
                    closeChatSessionNonCancellably(prefetchedSession)
                    return
                }
                activeTokens = ActiveTokenRecord(serverOrigin, currentGeneration, usableTokens)
                mutableSnapshots.value = HermesGatewaySnapshot(
                    connectionState = ConnectionState.Connected,
                    authenticationState = AuthenticationState.Authenticated,
                    serverVersion = info.version,
                    nativeOAuthSupported = info.nativeOAuthSupported,
                    authProviders = info.providers,
                    durableSessions = authenticated.sessions,
                )
                prefetchedSession?.let { candidate ->
                    adoptProjectMetadataSessionCandidate(
                        serverOrigin = serverOrigin,
                        originGeneration = currentGeneration,
                        accessToken = usableTokens.accessToken,
                        candidate = candidate,
                    )
                }
                startProjectTreeLoad(
                    serverOrigin = serverOrigin,
                    originGeneration = currentGeneration,
                    accessToken = usableTokens.accessToken,
                    durableSessions = authenticated.sessions,
                )
            } else {
                mutableSnapshots.value = HermesGatewaySnapshot(
                    connectionState = ConnectionState.Connected,
                    authenticationState = AuthenticationState.SignInRequired,
                    serverVersion = info.version,
                    nativeOAuthSupported = info.nativeOAuthSupported,
                    authProviders = info.providers,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: NativeRefreshExpiredException) {
            if (generation != currentGeneration || activeOrigin != serverOrigin) return
            tokenStore?.clear(serverOrigin)
            activeTokens = null
            disconnectChat()
            publishSignInRequired()
        } catch (_: HermesAuthenticationRejectedException) {
            if (generation != currentGeneration || activeOrigin != serverOrigin) return
            tokenStore?.clear(serverOrigin)
            activeTokens = null
            disconnectChat()
            publishSignInRequired()
        } catch (_: Exception) {
            if (generation != currentGeneration || activeOrigin != serverOrigin) return
            mutableSnapshots.value = HermesGatewaySnapshot(
                connectionState = ConnectionState.Disconnected,
                connectionError = "Could not reach Hermes Serve",
            )
        }
    }

    private fun startProjectTreeLoad(
        serverOrigin: ServerOrigin,
        originGeneration: Long,
        accessToken: String,
        durableSessions: List<SessionSummary>,
        profile: String = "default",
        preserveContent: Boolean = false,
    ) {
        projectLoadJob?.cancel()
        val connector = projectConnector
        if (connector == null) {
            publishProjectStateIfCurrent(
                serverOrigin = serverOrigin,
                originGeneration = originGeneration,
                projectState = ProjectLoadState.Unsupported,
            )
            return
        }

        if (!isCurrentProjectLoad(serverOrigin, originGeneration)) return
        if (!preserveContent) {
            mutableSnapshots.value = mutableSnapshots.value.copy(
                projects = emptyList(),
                projectState = ProjectLoadState.Loading,
                activeProjectId = null,
                scopedSessionIds = emptySet(),
                projectSessions = emptyMap(),
            )
        }
        projectLoadJob = viewModelScope.launch {
            try {
                if (!isCurrentProjectLoad(serverOrigin, originGeneration)) return@launch
                val tree = withProjectMetadataSession(
                    serverOrigin = serverOrigin,
                    originGeneration = originGeneration,
                    accessToken = accessToken,
                ) { session ->
                    session.loadProjectTree(profile = profile)
                }

                if (!isCurrentProjectLoad(serverOrigin, originGeneration)) return@launch
                val projects = tree.projects.map { project ->
                    project.copy(
                        previewSessions = project.previewSessions.map { preview ->
                            reconcileProjectSession(project.id, preview, durableSessions)
                        },
                    )
                }
                mutableSnapshots.value = mutableSnapshots.value.copy(
                    projects = projects,
                    projectState = ProjectLoadState.Loaded(
                        projects = projects,
                        activeProjectId = tree.activeProjectId,
                        scopedSessionIds = tree.scopedSessionIds,
                    ),
                    activeProjectId = tree.activeProjectId,
                    scopedSessionIds = tree.scopedSessionIds,
                    projectSessions = emptyMap(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: HermesChatMethodNotFoundException) {
                disconnectProjectMetadata()
                publishProjectStateIfCurrent(
                    serverOrigin = serverOrigin,
                    originGeneration = originGeneration,
                    projectState = ProjectLoadState.Unsupported,
                )
            } catch (error: Exception) {
                publishProjectStateIfCurrent(
                    serverOrigin = serverOrigin,
                    originGeneration = originGeneration,
                    projectState = ProjectLoadState.TransientError(
                        error.message?.take(160)?.takeIf(String::isNotBlank)
                            ?: "Could not load project metadata",
                    ),
                )
            }
        }
    }

    private suspend fun <T> withHermesRestOperation(
        block: suspend (ServerOrigin, String?) -> T,
    ): T {
        val serverOrigin = activeOrigin
            ?: throw HermesConnectionException("Hermes Serve is not connected")
        val originGeneration = generation
        if (!isCurrentRestOperation(serverOrigin, originGeneration)) {
            throw HermesConnectionException("Hermes host files are unavailable")
        }
        val accessToken = accessTokenForRequest(serverOrigin, originGeneration)
        if (
            mutableSnapshots.value.authenticationState == AuthenticationState.Authenticated &&
            accessToken == null
        ) {
            throw HermesConnectionException("Hermes host files require authentication")
        }
        val result = block(serverOrigin, accessToken)
        currentCoroutineContext().ensureActive()
        if (!isCurrentRestOperation(serverOrigin, originGeneration)) {
            throw CancellationException("Server origin was replaced")
        }
        return result
    }

    private fun isCurrentRestOperation(
        serverOrigin: ServerOrigin,
        originGeneration: Long,
    ): Boolean {
        val authenticationState = mutableSnapshots.value.authenticationState
        return activeOrigin == serverOrigin &&
            generation == originGeneration &&
            (
                authenticationState == AuthenticationState.Authenticated ||
                    authenticationState == AuthenticationState.NotRequired
                )
    }

    /**
     * Opens an unpublished observer candidate. The caller owns it until
     * [adoptProjectMetadataSessionCandidate] either publishes or closes it.
     */
    private suspend fun connectProjectMetadataCandidate(
        serverOrigin: ServerOrigin,
        originGeneration: Long,
        accessToken: String,
    ): HermesChatSession {
        val connector = projectConnector
            ?: throw HermesChatMethodNotFoundException("projects.tree")
        val candidate = connector.connect(serverOrigin, accessToken)
        try {
            currentCoroutineContext().ensureActive()
            if (!isCurrentOrigin(serverOrigin, originGeneration)) {
                throw CancellationException("Server origin was replaced")
            }
            return candidate
        } catch (cancelled: CancellationException) {
            closeChatSessionNonCancellably(candidate)
            throw cancelled
        } catch (error: Exception) {
            closeChatSessionNonCancellably(candidate)
            throw error
        }
    }

    /**
     * Publishes a candidate only after authentication and origin identity are
     * current. It closes both stale candidates and displaced owners.
     */
    private suspend fun adoptProjectMetadataSessionCandidate(
        serverOrigin: ServerOrigin,
        originGeneration: Long,
        accessToken: String,
        candidate: HermesChatSession,
    ): HermesChatSession? {
        var sessionToUse: HermesChatSession? = null
        val sessionsToClose = mutableListOf<HermesChatSession>()
        synchronized(projectMetadataLock) {
            if (!isCurrentProjectLoad(serverOrigin, originGeneration)) {
                sessionsToClose += candidate
            } else {
                val current = activeProjectMetadataSession
                if (
                    current != null &&
                    current.origin == serverOrigin &&
                    current.generation == originGeneration &&
                    current.accessToken == accessToken
                ) {
                    sessionToUse = current.session
                    sessionsToClose += candidate
                } else {
                    current?.session?.let(sessionsToClose::add)
                    activeProjectMetadataSession = ProjectMetadataSessionRecord(
                        origin = serverOrigin,
                        generation = originGeneration,
                        accessToken = accessToken,
                        session = candidate,
                    )
                    sessionToUse = candidate
                }
            }
        }
        sessionsToClose.distinct().forEach { closeChatSessionNonCancellably(it) }
        return sessionToUse
    }

    /**
     * Reuses one dedicated observer connection for project metadata. Ownership is
     * scoped to the exact origin, generation, and access token; candidates are
     * published only after those identities are revalidated.
     */
    private suspend fun acquireProjectMetadataSession(
        serverOrigin: ServerOrigin,
        originGeneration: Long,
        accessToken: String,
    ): HermesChatSession {
        synchronized(projectMetadataLock) {
            activeProjectMetadataSession
                ?.takeIf {
                    it.origin == serverOrigin &&
                        it.generation == originGeneration &&
                        it.accessToken == accessToken
                }
                ?.let { return it.session }
        }

        val candidate = connectProjectMetadataCandidate(
            serverOrigin = serverOrigin,
            originGeneration = originGeneration,
            accessToken = accessToken,
        )
        return adoptProjectMetadataSessionCandidate(
            serverOrigin = serverOrigin,
            originGeneration = originGeneration,
            accessToken = accessToken,
            candidate = candidate,
        ) ?: throw CancellationException("Server origin was replaced")
    }

    private suspend fun <T> withProjectMetadataSession(
        serverOrigin: ServerOrigin,
        originGeneration: Long,
        accessToken: String,
        block: suspend (HermesChatSession) -> T,
    ): T {
        val session = acquireProjectMetadataSession(
            serverOrigin = serverOrigin,
            originGeneration = originGeneration,
            accessToken = accessToken,
        )
        return try {
            val result = block(session)
            currentCoroutineContext().ensureActive()
            if (!isCurrentProjectLoad(serverOrigin, originGeneration)) {
                throw CancellationException("Server origin was replaced")
            }
            result
        } catch (error: HermesChatTransportException) {
            invalidateProjectMetadataSession(session)
            throw error
        }
    }

    private suspend fun <T> withProjectMetadataSession(
        block: suspend (HermesChatSession) -> T,
    ): T {
        val serverOrigin = activeOrigin
            ?: throw HermesChatProtocolException("Hermes Serve is not connected")
        val originGeneration = generation
        if (!isCurrentProjectLoad(serverOrigin, originGeneration)) {
            throw HermesChatProtocolException("Hermes project metadata is unavailable")
        }
        val accessToken = accessTokenForRequest(serverOrigin, originGeneration)
            ?: throw HermesChatProtocolException("Hermes project metadata requires authentication")
        return withProjectMetadataSession(
            serverOrigin = serverOrigin,
            originGeneration = originGeneration,
            accessToken = accessToken,
            block = block,
        )
    }

    private fun detachProjectMetadataSession(): HermesChatSession? =
        synchronized(projectMetadataLock) {
            activeProjectMetadataSession?.session.also {
                activeProjectMetadataSession = null
            }
        }

    private suspend fun invalidateProjectMetadataSession(session: HermesChatSession) {
        val detached = synchronized(projectMetadataLock) {
            if (activeProjectMetadataSession?.session === session) {
                activeProjectMetadataSession = null
                session
            } else {
                null
            }
        }
        closeChatSessionNonCancellably(detached)
    }

    private suspend fun disconnectProjectMetadata() {
        closeChatSessionNonCancellably(detachProjectMetadataSession())
    }

    private fun isCurrentOrigin(
        serverOrigin: ServerOrigin,
        originGeneration: Long,
    ): Boolean =
        activeOrigin == serverOrigin && generation == originGeneration

    private fun isCurrentProjectLoad(
        serverOrigin: ServerOrigin,
        originGeneration: Long,
    ): Boolean =
        isCurrentOrigin(serverOrigin, originGeneration) &&
            mutableSnapshots.value.authenticationState == AuthenticationState.Authenticated

    private fun publishProjectStateIfCurrent(
        serverOrigin: ServerOrigin,
        originGeneration: Long,
        projectState: ProjectLoadState,
    ) {
        if (!isCurrentProjectLoad(serverOrigin, originGeneration)) return
        mutableSnapshots.value = mutableSnapshots.value.copy(
            projects = emptyList(),
            projectState = projectState,
            activeProjectId = null,
            scopedSessionIds = emptySet(),
            projectSessions = emptyMap(),
        )
    }

    /**
     * Opens one project through the dedicated observer connection. This path may
     * only call `projects.project_sessions`; it never resumes or creates a runtime.
     */
    fun openProject(projectId: ProjectId, profile: String = "default"): Job {
        projectSessionJobs[projectId]?.cancel()
        val requestGeneration = ++nextProjectSessionGeneration
        projectSessionGenerations[projectId] = requestGeneration
        val serverOrigin = activeOrigin
        val originGeneration = generation
        val connector = projectConnector

        if (serverOrigin == null || connector == null ||
            !isCurrentProjectLoad(serverOrigin, originGeneration)
        ) {
            if (serverOrigin != null && connector == null) {
                publishProjectSessionStateIfCurrent(
                    projectId = projectId,
                    serverOrigin = serverOrigin,
                    originGeneration = originGeneration,
                    requestGeneration = requestGeneration,
                    projectState = ProjectSessionLoadState.Unsupported,
                )
            }
            return viewModelScope.launch { }
        }

        publishProjectSessionStateIfCurrent(
            projectId = projectId,
            serverOrigin = serverOrigin,
            originGeneration = originGeneration,
            requestGeneration = requestGeneration,
            projectState = ProjectSessionLoadState.Loading,
        )

        val job = viewModelScope.launch {
            try {
                if (!isCurrentProjectSession(
                        projectId,
                        serverOrigin,
                        originGeneration,
                        requestGeneration,
                    )
                ) return@launch
                val result = withProjectMetadataSession { metadataSession ->
                    metadataSession.loadProjectSessions(
                        projectId = projectId,
                        profile = profile,
                    )
                }

                if (!isCurrentProjectSession(
                        projectId,
                        serverOrigin,
                        originGeneration,
                        requestGeneration,
                    )
                ) return@launch
                val durableSessions = mutableSnapshots.value.durableSessions
                val sessions = result.sessions
                    .take(ProjectSummary.MAX_PROJECT_SESSIONS)
                    .map { session ->
                        reconcileProjectSession(projectId, session, durableSessions)
                    }
                publishProjectSessionStateIfCurrent(
                    projectId = projectId,
                    serverOrigin = serverOrigin,
                    originGeneration = originGeneration,
                    requestGeneration = requestGeneration,
                    projectState = ProjectSessionLoadState.Loaded(sessions),
                )
                reconcileProjectSummaryFromDrillIn(
                    fresh = result.project,
                    serverOrigin = serverOrigin,
                    originGeneration = originGeneration,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NativeRefreshExpiredException) {
                if (isCurrentProjectSession(
                        projectId,
                        serverOrigin,
                        originGeneration,
                        requestGeneration,
                    )
                ) {
                    disconnectChat()
                    publishSignInRequired()
                }
            } catch (_: HermesChatMethodNotFoundException) {
                publishProjectSessionStateIfCurrent(
                    projectId = projectId,
                    serverOrigin = serverOrigin,
                    originGeneration = originGeneration,
                    requestGeneration = requestGeneration,
                    projectState = ProjectSessionLoadState.Unsupported,
                )
            } catch (error: Exception) {
                publishProjectSessionStateIfCurrent(
                    projectId = projectId,
                    serverOrigin = serverOrigin,
                    originGeneration = originGeneration,
                    requestGeneration = requestGeneration,
                    projectState = ProjectSessionLoadState.TransientError(
                        error.message?.take(160)?.takeIf(String::isNotBlank)
                            ?: "Could not load project sessions",
                    ),
                )
            } finally {
                if (projectSessionGenerations[projectId] == requestGeneration) {
                    projectSessionJobs.remove(projectId)
                    projectSessionGenerations.remove(projectId)
                }
            }
        }
        projectSessionJobs[projectId] = job
        return job
    }

    /** Alias retained for callers that name the operation after its RPC. */
    fun loadProjectSessions(projectId: ProjectId, profile: String = "default"): Job =
        openProject(projectId, profile)

    /**
     * Manually refreshes the Home snapshot: re-reads the durable REST sessions
     * and reloads the project tree through the official contracts. The tree
     * reload preserves the existing list while it is in flight so a pull-to-
     * refresh never blanks the screen; [homeRefreshing] reports the window.
     */
    fun refreshHomeData(): Job {
        val serverOrigin = activeOrigin
        val originGeneration = generation
        if (serverOrigin == null || !isCurrentProjectLoad(serverOrigin, originGeneration)) {
            return viewModelScope.launch { }
        }
        refreshHomeJob?.cancel()
        val job = viewModelScope.launch {
            _homeRefreshing.value = true
            try {
                val accessToken = accessTokenForRequest(serverOrigin, originGeneration)
                    ?: return@launch
                currentCoroutineContext().ensureActive()
                if (!isCurrentProjectLoad(serverOrigin, originGeneration)) return@launch
                val durableSessions = client.authenticate(serverOrigin, accessToken).sessions
                currentCoroutineContext().ensureActive()
                if (!isCurrentProjectLoad(serverOrigin, originGeneration)) return@launch
                if (durableSessions != mutableSnapshots.value.durableSessions) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        durableSessions = durableSessions,
                    )
                }
                startProjectTreeLoad(
                    serverOrigin = serverOrigin,
                    originGeneration = originGeneration,
                    accessToken = accessToken,
                    durableSessions = durableSessions,
                    preserveContent = true,
                )
                projectLoadJob?.join()
                val delegationStatus = try {
                    withProjectMetadataSession(HermesChatSession::loadDelegationStatus)
                } catch (_: HermesChatMethodNotFoundException) {
                    null
                }
                currentCoroutineContext().ensureActive()
                if (delegationStatus != null && isCurrentProjectLoad(serverOrigin, originGeneration)) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        delegationStatus = delegationStatus,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NativeRefreshExpiredException) {
                if (isCurrentProjectLoad(serverOrigin, originGeneration)) {
                    disconnectChat()
                    publishSignInRequired()
                }
            } catch (_: HermesAuthenticationRejectedException) {
                if (isCurrentProjectLoad(serverOrigin, originGeneration)) {
                    disconnectChat()
                    publishSignInRequired()
                }
            } catch (_: Exception) {
                // Transient refresh failure: keep the current snapshot intact so
                // a later pull can retry. Authentication rejections are handled
                // above and never degrade into this branch.
            } finally {
                if (generation == originGeneration) {
                    _homeRefreshing.value = false
                }
            }
        }
        refreshHomeJob = job
        return job
    }

    fun loadManagementSettings(profile: String = mutableSnapshots.value.selectedProfile): Job {
        managementJob?.cancel()
        val origin = activeOrigin ?: return viewModelScope.launch { }
        val expectedGeneration = generation
        return viewModelScope.launch {
            mutableSnapshots.value = mutableSnapshots.value.copy(
                selectedProfile = profile.take(64),
                managementLoading = true,
                managementError = null,
            )
            try {
                val token = accessTokenForRequest(origin, expectedGeneration) ?: return@launch
                val profiles = client.loadProfiles(origin, token)
                val selected = profile.takeIf(profiles::contains) ?: profiles.firstOrNull() ?: "default"
                val options = client.loadDefaultModelOptions(origin, token, selected)
                currentCoroutineContext().ensureActive()
                if (generation == expectedGeneration && activeOrigin == origin) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        profiles = profiles,
                        selectedProfile = selected,
                        defaultModelOptions = options,
                        managementLoading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == expectedGeneration) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        managementLoading = false,
                        managementError = "Could not load profile settings",
                    )
                }
            }
        }.also { managementJob = it }
    }

    suspend fun setProfileDefaultModel(
        selection: ModelSelection,
        confirmExpensiveModel: Boolean = false,
    ): ModelSwitchResult {
        val origin = checkNotNull(activeOrigin) { "No Hermes server configured" }
        val expectedGeneration = generation
        val token = checkNotNull(accessTokenForRequest(origin, expectedGeneration)) { "Sign in required" }
        val result = client.setDefaultModel(
            origin,
            token,
            mutableSnapshots.value.selectedProfile,
            selection,
            confirmExpensiveModel,
        )
        if (result.accepted) loadManagementSettings().join()
        return result
    }

    suspend fun logout() {
        val origin = activeOrigin ?: return
        val expectedGeneration = generation
        tokenStore?.clear(origin)
        currentCoroutineContext().ensureActive()
        if (generation != expectedGeneration || activeOrigin != origin) return
        activeTokens = null
        disconnectChat()
        publishSignInRequired()
    }

    fun searchTranscripts(query: String): Job {
        searchJob?.cancel()
        val origin = activeOrigin ?: return viewModelScope.launch { }
        val expectedGeneration = generation
        val bounded = query.trim().take(256)
        if (bounded.isEmpty()) {
            mutableSnapshots.value = mutableSnapshots.value.copy(
                searchQuery = "",
                transcriptSearchResults = emptyList(),
                searchLoading = false,
                searchError = null,
            )
            return viewModelScope.launch { }
        }
        return viewModelScope.launch {
            mutableSnapshots.value = mutableSnapshots.value.copy(
                searchQuery = bounded,
                searchLoading = true,
                searchError = null,
            )
            try {
                val token = accessTokenForRequest(origin, expectedGeneration) ?: return@launch
                val results = client.searchSessions(
                    origin,
                    token,
                    bounded,
                    mutableSnapshots.value.selectedProfile,
                )
                if (generation == expectedGeneration && activeOrigin == origin) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        transcriptSearchResults = results,
                        searchLoading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == expectedGeneration) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        searchLoading = false,
                        searchError = "Transcript search unavailable",
                    )
                }
            }
        }.also { searchJob = it }
    }

    suspend fun renameSession(sessionId: DurableSessionId, title: String) {
        updateSession(sessionId, title = title.trim().take(512))
    }

    suspend fun setSessionPinned(sessionId: DurableSessionId, pinned: Boolean) {
        updateSession(sessionId, pinned = pinned)
    }

    suspend fun setSessionArchived(sessionId: DurableSessionId, archived: Boolean) {
        updateSession(sessionId, archived = archived)
    }

    suspend fun deleteSession(sessionId: DurableSessionId) {
        check(mutableSnapshots.value.chatSessions[sessionId]?.isSending != true) {
            "Stop the active turn before deleting this session"
        }
        val origin = checkNotNull(activeOrigin) { "No Hermes server configured" }
        val expectedGeneration = generation
        val token = checkNotNull(accessTokenForRequest(origin, expectedGeneration)) { "Sign in required" }
        client.deleteSession(origin, token, sessionId, mutableSnapshots.value.selectedProfile)
        detachFailedRuntime(sessionId)
        mutableSnapshots.value = mutableSnapshots.value.removeSession(sessionId)
    }

    private suspend fun updateSession(
        sessionId: DurableSessionId,
        title: String? = null,
        archived: Boolean? = null,
        pinned: Boolean? = null,
    ) {
        val origin = checkNotNull(activeOrigin) { "No Hermes server configured" }
        val expectedGeneration = generation
        val token = checkNotNull(accessTokenForRequest(origin, expectedGeneration)) { "Sign in required" }
        client.updateSession(
            origin,
            token,
            sessionId,
            mutableSnapshots.value.selectedProfile,
            title,
            archived,
            pinned,
        )
        mutableSnapshots.value = mutableSnapshots.value.mapSession(sessionId) { current ->
            current.copy(
                title = title ?: current.title,
                archived = archived ?: current.archived,
                pinned = pinned ?: current.pinned,
            )
        }
    }

    private fun isCurrentProjectSession(
        projectId: ProjectId,
        serverOrigin: ServerOrigin,
        originGeneration: Long,
        requestGeneration: Long,
    ): Boolean =
        isCurrentProjectLoad(serverOrigin, originGeneration) &&
            projectSessionGenerations[projectId] == requestGeneration

    private fun publishProjectSessionStateIfCurrent(
        projectId: ProjectId,
        serverOrigin: ServerOrigin,
        originGeneration: Long,
        requestGeneration: Long,
        projectState: ProjectSessionLoadState,
    ) {
        if (!isCurrentProjectSession(
                projectId,
                serverOrigin,
                originGeneration,
                requestGeneration,
            )
        ) return
        val snapshot = mutableSnapshots.value
        val projectSessions = if (projectState is ProjectSessionLoadState.Loaded) {
            snapshot.projectSessions + (projectId to projectState.sessions)
        } else {
            snapshot.projectSessions
        }
        mutableSnapshots.value = snapshot.copy(
            projectSessions = projectSessions,
            projectSessionStates = snapshot.projectSessionStates + (projectId to projectState),
        )
    }

    /**
     * Adopts the drill-in response's authoritative summary into the shared
     * projects list so a project's header count, label, and path always match
     * the session rows rendered from that same response. Tree preview sessions
     * are preserved; only identity-free fields are replaced.
     */
    private fun reconcileProjectSummaryFromDrillIn(
        fresh: ProjectSummary,
        serverOrigin: ServerOrigin,
        originGeneration: Long,
    ) {
        if (!isCurrentProjectLoad(serverOrigin, originGeneration)) return
        val snapshot = mutableSnapshots.value
        val projects = snapshot.projects.map { existing ->
            if (existing.id == fresh.id) {
                existing.copy(
                    label = fresh.label,
                    primaryPath = fresh.primaryPath,
                    sessionCount = fresh.sessionCount,
                )
            } else {
                existing
            }
        }
        if (projects != snapshot.projects) {
            mutableSnapshots.value = snapshot.copy(projects = projects)
        }
    }

    fun signIn(openBrowser: suspend (String) -> Unit): Job {
        signInJob?.cancel()
        val job = viewModelScope.launch {
            val serverOrigin = activeOrigin ?: return@launch
            val currentGeneration = generation
            val login = nativeLogin ?: return@launch
            val beforeSignIn = mutableSnapshots.value
            if (
                beforeSignIn.authenticationState != AuthenticationState.SignInRequired ||
                !beforeSignIn.nativeOAuthSupported ||
                beforeSignIn.authProviders.none { it.name == "nous" }
            ) return@launch

            mutableSnapshots.value = beforeSignIn.copy(
                authenticationState = AuthenticationState.SigningIn,
                connectionError = null,
            )
            try {
                val tokens = login.signIn(serverOrigin, "nous", openBrowser)
                currentCoroutineContext().ensureActive()
                if (generation != currentGeneration || activeOrigin != serverOrigin) return@launch
                val authenticated = client.authenticate(serverOrigin, tokens.accessToken)
                currentCoroutineContext().ensureActive()
                if (generation != currentGeneration || activeOrigin != serverOrigin) return@launch
                tokenStore?.save(serverOrigin, tokens)
                activeTokens = ActiveTokenRecord(serverOrigin, currentGeneration, tokens)
                mutableSnapshots.value = mutableSnapshots.value.copy(
                    authenticationState = AuthenticationState.Authenticated,
                    connectionError = null,
                    durableSessions = authenticated.sessions,
                )
                startProjectTreeLoad(
                    serverOrigin = serverOrigin,
                    originGeneration = currentGeneration,
                    accessToken = tokens.accessToken,
                    durableSessions = authenticated.sessions,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation != currentGeneration || activeOrigin != serverOrigin) return@launch
                val safeError = (error as? HermesConnectionException)
                    ?.message
                    ?.takeIf(String::isNotBlank)
                    ?: "Hermes sign-in failed (${error.javaClass.simpleName})"
                mutableSnapshots.value = mutableSnapshots.value.copy(
                    authenticationState = AuthenticationState.SignInRequired,
                    connectionError = safeError,
                )
            }
        }
        signInJob = job
        return job
    }

    suspend fun loadHostDirectories(
        path: String? = null,
    ): HostDirectoryListing = withHermesRestOperation { serverOrigin, accessToken ->
        client.loadHostDirectories(serverOrigin, accessToken, path)
    }

    suspend fun createHostDirectory(
        parentPath: String,
        name: String,
    ): HostDirectoryListing = withHermesRestOperation { serverOrigin, accessToken ->
        client.createHostDirectory(serverOrigin, accessToken, parentPath, name)
    }

    suspend fun downloadManagedImage(path: String): ByteArray =
        withHermesRestOperation { serverOrigin, accessToken ->
            client.downloadManagedImage(serverOrigin, accessToken, path)
        }

    suspend fun createProject(
        name: String,
        path: String,
        profile: String = "default",
    ): ProjectSummary {
        val created = withProjectMetadataSession { session ->
            session.createProject(name, path, profile)
        }
        val snapshot = mutableSnapshots.value
        val projects = listOf(created) + snapshot.projects.filterNot { it.id == created.id }
        val loaded = snapshot.projectState as? ProjectLoadState.Loaded
        mutableSnapshots.value = snapshot.copy(
            projects = projects,
            projectState = ProjectLoadState.Loaded(
                projects = projects,
                activeProjectId = created.id,
                scopedSessionIds = loaded?.scopedSessionIds ?: snapshot.scopedSessionIds,
            ),
            activeProjectId = created.id,
        )
        return created
    }

    /** Adds an explicit unscoped local "New chat" draft to Home. */
    fun createNewSession(title: String = "New chat"): DurableSessionId {
        val draftId = DurableSessionId("draft-${++draftCounter}")
        pendingDraftSessions += draftId
        val snapshot = mutableSnapshots.value
        mutableSnapshots.value = snapshot.copy(
            durableSessions = listOf(
                SessionSummary(
                    id = draftId,
                    title = title,
                    projectId = null,
                    workspacePath = null,
                    isLocalDraft = true,
                ),
            ) + snapshot.durableSessions,
        )
        return draftId
    }

    /** Adds a local draft to the exact project identified by [projectId]. */
    fun createProjectSession(projectId: ProjectId, title: String): DurableSessionId {
        val project = mutableSnapshots.value.projects.firstOrNull { it.id == projectId }
            ?: throw IllegalArgumentException("Unknown project")
        val draftId = DurableSessionId("draft-${++draftCounter}")
        val workspacePath = validProjectWorkspacePath(project.primaryPath)
        val draft = SessionSummary(
            id = draftId,
            title = title,
            projectId = project.id,
            workspacePath = workspacePath,
            isLocalDraft = true,
        )
        pendingDraftSessions += draftId

        val snapshot = mutableSnapshots.value
        val projectSessions = listOf(draft) +
            snapshot.projectSessions[projectId].orEmpty().filterNot { it.id == draftId }
        val existingChat = snapshot.chatSessions[draftId]
        val chatSessions = if (workspacePath == null) {
            snapshot.chatSessions + (
                draftId to (existingChat ?: ChatSessionSnapshot()).copy(error = "No workspace")
                )
        } else {
            snapshot.chatSessions
        }
        mutableSnapshots.value = snapshot.copy(
            projectSessions = snapshot.projectSessions + (projectId to projectSessions),
            projectSessionStates = snapshot.projectSessionStates + (
                projectId to ProjectSessionLoadState.Loaded(projectSessions)
                ),
            chatSessions = chatSessions,
        )
        return draftId
    }

    /**
     * Stage new picker results into the composer, enforcing count/size caps at
     * metadata time. Accepted attachments are published for the chip row; rejected
     * ones are returned as reasons so the UI can surface them.
     */
    fun addAttachments(
        durableSessionId: DurableSessionId,
        candidates: List<ComposerAttachment>,
    ): List<String> {
        val current = mutableAttachments.value[durableSessionId].orEmpty()
        val accepted = mutableListOf<ComposerAttachment>()
        val rejected = mutableListOf<String>()
        for (candidate in candidates) {
            val safeCandidate = candidate.copy(
                displayName = AttachmentPolicy.sanitizeDisplayName(candidate.displayName),
            )
            when (val result = AttachmentPolicy.checkAdd(current + accepted, safeCandidate)) {
                is AttachmentAddResult.Accepted -> accepted += safeCandidate
                is AttachmentAddResult.Rejected -> rejected += result.reason
            }
        }
        if (accepted.isNotEmpty()) {
            mutableAttachments.value = mutableAttachments.value + (durableSessionId to current + accepted)
        }
        return rejected
    }

    fun removeAttachment(durableSessionId: DurableSessionId, attachmentId: String) {
        val updated = mutableAttachments.value[durableSessionId].orEmpty().filterNot { it.id == attachmentId }
        mutableAttachments.value = if (updated.isEmpty()) {
            mutableAttachments.value - durableSessionId
        } else {
            mutableAttachments.value + (durableSessionId to updated)
        }
    }

    private fun clearAttachments(durableSessionId: DurableSessionId) {
        if (mutableAttachments.value.containsKey(durableSessionId)) {
            mutableAttachments.value = mutableAttachments.value - durableSessionId
        }
    }

    /**
     * Selecting a local draft is UI navigation only. Its runtime is opened by the
     * first send, so this must not replace or interrupt another selected runtime.
     */
    private fun openDraftSession(@Suppress("UNUSED_PARAMETER") durableSessionId: DurableSessionId): Job =
        viewModelScope.launch { }

    private fun loadBackgroundViewedTranscript(durableSessionId: DurableSessionId): Job =
        viewModelScope.launch {
            val origin = activeOrigin ?: return@launch
            val originGeneration = generation
            updateChat(durableSessionId) { it.copy(isLoading = true, error = null) }
            try {
                val accessToken = accessTokenForRequest(origin, originGeneration)
                if (!isCurrentOrigin(origin, originGeneration)) return@launch
                val messages = client.loadTranscript(
                    origin,
                    accessToken,
                    serverDurableId(durableSessionId),
                )
                if (!isCurrentOrigin(origin, originGeneration)) return@launch
                updateChat(durableSessionId) {
                    it.copy(messages = messages, isLoading = false, error = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isCurrentOrigin(origin, originGeneration)) return@launch
                updateChat(durableSessionId) {
                    it.copy(
                        isLoading = false,
                        error = error.message?.take(120)
                            ?: "Could not load transcript (${error.javaClass.simpleName})",
                    )
                }
            }
        }

    fun openSession(durableSessionId: DurableSessionId): Job {
        if (durableSessionId in pendingDraftSessions) {
            return openDraftSession(durableSessionId)
        }
        if (liveControllers[durableSessionId] != null) {
            return viewModelScope.launch { }
        }
        val operationGeneration = ++nextChatOperationGeneration
        chatOperationGenerations[durableSessionId] = operationGeneration
        chatJobs.remove(durableSessionId)?.cancel()
        val job = viewModelScope.launch {
            val origin = activeOrigin ?: return@launch
            val originGeneration = generation
            if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
            updateChat(durableSessionId) { it.copy(isLoading = true, error = null) }
            try {
                val accessToken = accessTokenForRequest(origin, originGeneration)
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
                val messages = client.loadTranscript(
                    origin,
                    accessToken,
                    serverDurableId(durableSessionId),
                )
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
                updateChat(durableSessionId) {
                    it.copy(messages = messages, isLoading = false, error = null)
                }
                if (
                    accessToken != null &&
                    chatConnector != null &&
                    mutableSnapshots.value.authenticationState == AuthenticationState.Authenticated
                ) {
                    ensureLiveSession(
                        origin = origin,
                        originGeneration = originGeneration,
                        operationGeneration = operationGeneration,
                        accessToken = accessToken,
                        durableSessionId = durableSessionId,
                        closeWhenIdle = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                if (isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                    updateChat(durableSessionId) { it.copy(isLoading = false) }
                }
                throw cancelled
            } catch (_: NativeRefreshExpiredException) {
                if (isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                    disconnectChat()
                    publishSignInRequired()
                }
            } catch (error: Exception) {
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
                updateChat(durableSessionId) {
                    it.copy(
                        isLoading = false,
                        error = error.message
                            ?.take(120)
                            ?: "Could not load transcript (${error.javaClass.simpleName})",
                    )
                }
            }
        }
        chatJobs[durableSessionId] = job
        return job
    }

    fun sendMessage(durableSessionId: DurableSessionId, rawText: String): Job {
        val text = rawText.trim()
        val hasAttachments = mutableAttachments.value[durableSessionId].orEmpty().isNotEmpty()
        if (text.isEmpty() && !hasAttachments) return viewModelScope.launch { }
        val draft = localDraftSession(durableSessionId)
        if (
            draft?.projectId != null &&
            validProjectWorkspacePath(draft.workspacePath) == null
        ) {
            updateChat(durableSessionId) {
                it.copy(isLoading = false, isSending = false, error = "No workspace")
            }
            return viewModelScope.launch { }
        }
        val operationGeneration = ++nextChatOperationGeneration
        chatOperationGenerations[durableSessionId] = operationGeneration
        chatJobs.remove(durableSessionId)?.cancel()
        clearSendingState(durableSessionId)
        updateChat(durableSessionId) { it.copy(runState = RunEventState()) }
        val job = viewModelScope.launch {
            val origin = activeOrigin ?: return@launch
            val originGeneration = generation
            if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
            var promptStaged = false
            var stagingFailed = false
            try {
                val accessToken = accessTokenForRequest(origin, originGeneration)
                    ?: throw HermesConnectionException("Sign in is required to send messages")
                val session = ensureLiveSession(
                    origin = origin,
                    originGeneration = originGeneration,
                    operationGeneration = operationGeneration,
                    accessToken = accessToken,
                    durableSessionId = durableSessionId,
                )
                val runtimeId = checkNotNull(liveControllers[durableSessionId]?.runtimeSessionId)
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch

                // Stage attachments on the host BEFORE the optimistic bubble: bytes
                // live on this device, so nothing can be sent without uploading
                // them first, and chips must survive a staging failure.
                val pendingAttachments = mutableAttachments.value[durableSessionId].orEmpty()
                var submittedText = text
                if (pendingAttachments.isNotEmpty()) {
                    try {
                        val staged = AttachmentStager(session, runtimeId, attachmentReader)
                            .stage(pendingAttachments)
                        submittedText = AttachmentPolicy.composePromptText(
                            typedText = text,
                            fileRefs = staged.refTexts,
                            attachedNames = staged.names,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        stagingFailed = true
                        throw error
                    }
                }

                updateChat(durableSessionId) { current ->
                    current.copy(
                        messages = current.messages +
                            ChatMessage(ChatMessageRole.User, text) +
                            ChatMessage(ChatMessageRole.Assistant, "", isStreaming = true),
                        isLoading = false,
                        isSending = true,
                        error = null,
                        notice = null,
                        billingNotice = null,
                    )
                }
                notifications.turnStarted(
                    durableSessionId,
                    sessionTitle(durableSessionId),
                    activeTurnIds.apply { add(durableSessionId) }.size,
                )
                promptStaged = true
                yield()
                session.submitPrompt(runtimeId, submittedText)
                markDraftPersisted(
                    durableSessionId,
                    origin,
                    originGeneration,
                    operationGeneration,
                )
                if (pendingAttachments.isNotEmpty()) clearAttachments(durableSessionId)
            } catch (cancelled: CancellationException) {
                if (isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                    clearSendingState(durableSessionId)
                }
                throw cancelled
            } catch (_: NativeRefreshExpiredException) {
                if (isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                    disconnectChat()
                    publishSignInRequired()
                }
            } catch (error: Exception) {
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
                if (stagingFailed) {
                    // Nothing was submitted; the draft stays editable with its chips.
                    // A fresh draft runtime may hold partially staged orphaned files,
                    // so drop it — the next send creates a clean runtime.
                    if (durableSessionId in pendingDraftSessions || error is HermesChatException) {
                        detachFailedRuntime(durableSessionId)
                    }
                    clearSendingState(durableSessionId)
                    updateChat(durableSessionId) { current ->
                        current.copy(
                            error = error.message?.take(160) ?: "Could not attach files",
                        )
                    }
                    return@launch
                }
                if (
                    promptStaged &&
                    hasAttachments &&
                    error is HermesChatException &&
                    error !is HermesChatTransportException
                ) {
                    detachFailedRuntime(durableSessionId)
                }
                if (promptStaged && error is HermesChatTransportException) {
                    val recoveryAttempt = startChatRecovery(durableSessionId, operationGeneration)
                    if (recoveryAttempt != null) {
                        try {
                            recoverChat(
                                durableSessionId,
                                origin,
                                originGeneration,
                                operationGeneration,
                                recoveryAttempt,
                            )
                        } finally {
                            finishChatRecovery(recoveryAttempt)
                        }
                    } else if (!isChatRecoveryInProgress(durableSessionId, operationGeneration)) {
                        clearSendingState(durableSessionId)
                        updateChat(durableSessionId) {
                            it.copy(error = "Connection lost while receiving response")
                        }
                    }
                } else {
                    clearSendingState(durableSessionId)
                    updateChat(durableSessionId) { current ->
                        current.copy(
                            error = error.message?.take(160) ?: "Could not send message",
                        )
                    }
                }
            }
        }
        chatJobs[durableSessionId] = job
        return job
    }

    /** Responds to the currently displayed clarification for exactly one live runtime. */
    fun respondToClarification(
        durableSessionId: DurableSessionId,
        requestId: String,
        answer: String,
    ): Job {
        val operation = beginClarificationResponse(durableSessionId, requestId)
            ?: return viewModelScope.launch { }
        return viewModelScope.launch {
            try {
                val response = operation.session.respondToClarification(requestId, answer)
                currentCoroutineContext().ensureActive()
                val lifecycle = when (response.status) {
                    HermesChatResponseStatus.Ok,
                    HermesChatResponseStatus.Resolved,
                    -> RunInteractionLifecycle.Resolved
                    HermesChatResponseStatus.Expired -> RunInteractionLifecycle.Expired
                    HermesChatResponseStatus.Interrupted,
                    HermesChatResponseStatus.Unknown,
                    -> RunInteractionLifecycle.Failed
                }
                publishClarificationResponse(operation, lifecycle)
                if (lifecycle == RunInteractionLifecycle.Failed) {
                    publishControllerError(operation, "Could not respond to clarification")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publishClarificationResponse(operation, RunInteractionLifecycle.Failed)
                publishControllerError(operation, "Could not respond to clarification")
            }
        }
    }

    /** Responds to the currently displayed approval for exactly one live runtime. */
    fun respondToApproval(
        durableSessionId: DurableSessionId,
        choice: String,
        all: Boolean = false,
    ): Job {
        val operation = beginApprovalResponse(durableSessionId, choice)
            ?: return viewModelScope.launch { }
        return viewModelScope.launch {
            try {
                val response = operation.session.respondToApproval(
                    operation.runtimeSessionId,
                    choice,
                    all,
                )
                currentCoroutineContext().ensureActive()
                val lifecycle = when (response.status) {
                    HermesChatResponseStatus.Ok,
                    HermesChatResponseStatus.Resolved,
                    -> RunInteractionLifecycle.Resolved
                    HermesChatResponseStatus.Expired -> RunInteractionLifecycle.Expired
                    HermesChatResponseStatus.Interrupted,
                    HermesChatResponseStatus.Unknown,
                    -> RunInteractionLifecycle.Failed
                }
                publishApprovalResponse(operation, lifecycle)
                if (lifecycle == RunInteractionLifecycle.Failed) {
                    publishApprovalError(operation)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publishApprovalResponse(operation, RunInteractionLifecycle.Failed)
                publishApprovalError(operation)
            }
        }
    }

    /** Interrupts exactly the selected session's current sending controller runtime. */
    fun stopSession(durableSessionId: DurableSessionId): Job {
        val operation = beginStopSession(durableSessionId)
            ?: return viewModelScope.launch { }
        return viewModelScope.launch {
            try {
                val response = operation.session.interruptSession(operation.runtimeSessionId)
                currentCoroutineContext().ensureActive()
                if (
                    response.status == HermesChatResponseStatus.Interrupted ||
                    response.status == HermesChatResponseStatus.Ok
                ) {
                    publishStopSuccess(operation)
                } else {
                    publishStopFailure(operation)
                }
            } catch (cancelled: CancellationException) {
                clearStoppingIfCurrent(operation)
                throw cancelled
            } catch (_: Exception) {
                publishStopFailure(operation)
            }
        }
    }

    /**
     * Debounced live slash-command completion for the composer of [durableSessionId].
     * Non-slash text clears the menu without a request. Results publish only when the
     * request is still the latest for that composer; failures clear the menu silently.
     * Completion requires the chat's live runtime (opened on first send/resume).
     */
    fun updateSlashCompletion(durableSessionId: DurableSessionId, text: String) {
        val requestGeneration = (slashCompletionGenerations[durableSessionId] ?: 0L) + 1L
        slashCompletionGenerations[durableSessionId] = requestGeneration
        slashCompletionJobs.remove(durableSessionId)?.cancel()
        if (!isSlashCommandContext(text)) {
            mutableSlashCompletions.value = mutableSlashCompletions.value - durableSessionId
            return
        }
        val job = viewModelScope.launch {
            delay(SLASH_COMPLETION_DEBOUNCE_MS)
            if (slashCompletionGenerations[durableSessionId] != requestGeneration) return@launch
            val session = liveControllers[durableSessionId]?.session
            if (session == null) {
                if (slashCompletionGenerations[durableSessionId] == requestGeneration) {
                    mutableSlashCompletions.value =
                        mutableSlashCompletions.value - durableSessionId
                }
                return@launch
            }
            val result = try {
                session.completeSlash(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (slashCompletionGenerations[durableSessionId] == requestGeneration) {
                    mutableSlashCompletions.value =
                        mutableSlashCompletions.value - durableSessionId
                }
                return@launch
            }
            if (slashCompletionGenerations[durableSessionId] != requestGeneration) return@launch
            mutableSlashCompletions.value = if (result.items.isEmpty()) {
                mutableSlashCompletions.value - durableSessionId
            } else {
                mutableSlashCompletions.value + (
                    durableSessionId to SlashCompletionState(
                        composerText = text,
                        items = result.items,
                        replaceFrom = result.replaceFrom,
                    )
                    )
            }
        }
        slashCompletionJobs[durableSessionId] = job
    }

    fun clearSlashCompletion(durableSessionId: DurableSessionId) {
        slashCompletionGenerations[durableSessionId] =
            (slashCompletionGenerations[durableSessionId] ?: 0L) + 1L
        slashCompletionJobs.remove(durableSessionId)?.cancel()
        mutableSlashCompletions.value = mutableSlashCompletions.value - durableSessionId
    }

    fun setReasoningEffort(durableSessionId: DurableSessionId, effort: String): Job {
        val canonicalEffort = canonicalReasoningEffort(effort)
        if (canonicalEffort == null) {
            return viewModelScope.launch {
                updateChat(durableSessionId) { it.copy(error = "Reasoning effort is invalid") }
            }
        }
        val operationGeneration = liveControllers[durableSessionId]?.operationGeneration
            ?: (++nextChatOperationGeneration).also {
                chatOperationGenerations[durableSessionId] = it
            }
        val job = viewModelScope.launch {
            val origin = activeOrigin
            if (origin == null) {
                updateChat(durableSessionId) { it.copy(error = "Hermes is not connected") }
                return@launch
            }
            val originGeneration = generation
            try {
                val accessToken = accessTokenForRequest(origin, originGeneration)
                    ?: throw HermesConnectionException("Sign in is required to change reasoning")
                val session = ensureLiveSession(
                    origin = origin,
                    originGeneration = originGeneration,
                    operationGeneration = operationGeneration,
                    accessToken = accessToken,
                    durableSessionId = durableSessionId,
                )
                val runtimeSessionId = liveControllers[durableSessionId]?.runtimeSessionId
                    ?: throw HermesConnectionException("Hermes session is not ready")
                session.setReasoning(runtimeSessionId, canonicalEffort)
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
                updateChat(durableSessionId) {
                    it.copy(reasoningEffort = canonicalEffort, error = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NativeRefreshExpiredException) {
                if (isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                    disconnectChat()
                    publishSignInRequired()
                }
            } catch (error: Exception) {
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
                updateChat(durableSessionId) {
                    it.copy(error = error.message?.take(160) ?: "Could not change reasoning")
                }
            }
        }
        chatJob = job
        return job
    }

    fun openModelPicker(durableSessionId: DurableSessionId): Job {
        val requestGeneration = ++modelPickerGeneration
        modelPickerJob?.cancel()
        mutableModelPickerState.value = ModelPickerState.Loading(durableSessionId)
        val operationGeneration = liveControllers[durableSessionId]?.operationGeneration
            ?: (++nextChatOperationGeneration).also {
                chatOperationGenerations[durableSessionId] = it
            }
        val job = viewModelScope.launch {
            val origin = activeOrigin
            if (origin == null) {
                publishModelPickerError(requestGeneration, durableSessionId, "Hermes is not connected")
                return@launch
            }
            val originGeneration = generation
            try {
                val accessToken = accessTokenForRequest(origin, originGeneration)
                    ?: throw HermesConnectionException("Sign in is required to select a model")
                val session = ensureLiveSession(
                    origin = origin,
                    originGeneration = originGeneration,
                    operationGeneration = operationGeneration,
                    accessToken = accessToken,
                    durableSessionId = durableSessionId,
                )
                val runtimeSessionId = liveControllers[durableSessionId]?.runtimeSessionId
                    ?: throw HermesConnectionException("Hermes session is not ready")
                val options = session.loadModelOptions(runtimeSessionId)
                if (!isCurrentModelPicker(requestGeneration, durableSessionId)) return@launch
                mutableModelPickerState.value = ModelPickerState.Ready(
                    durableSessionId = durableSessionId,
                    options = options,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NativeRefreshExpiredException) {
                if (isCurrentModelPicker(requestGeneration, durableSessionId)) {
                    mutableModelPickerState.value = ModelPickerState.Closed
                    disconnectChat()
                    publishSignInRequired()
                }
            } catch (error: Exception) {
                publishModelPickerError(
                    requestGeneration,
                    durableSessionId,
                    error.message?.take(160) ?: "Could not load models",
                )
            }
        }
        modelPickerJob = job
        return job
    }

    fun retryModelPicker(): Job {
        val durableSessionId = when (val state = mutableModelPickerState.value) {
            is ModelPickerState.Error -> state.durableSessionId
            is ModelPickerState.Ready -> state.durableSessionId
            is ModelPickerState.Loading -> state.durableSessionId
            ModelPickerState.Closed -> return viewModelScope.launch { }
        }
        return openModelPicker(durableSessionId)
    }

    fun dismissModelPicker() {
        modelPickerGeneration += 1
        modelPickerJob?.cancel()
        modelPickerJob = null
        mutableModelPickerState.value = ModelPickerState.Closed
    }

    fun selectModel(selection: ModelSelection): Job = applyModelSelection(selection, confirmExpensive = false)

    fun confirmModelSelection(): Job {
        val selection = (mutableModelPickerState.value as? ModelPickerState.Ready)
            ?.pendingSelection
            ?: return viewModelScope.launch { }
        return applyModelSelection(selection, confirmExpensive = true)
    }

    private fun applyModelSelection(
        selection: ModelSelection,
        confirmExpensive: Boolean,
    ): Job {
        val state = mutableModelPickerState.value as? ModelPickerState.Ready
            ?: return viewModelScope.launch { }
        if (state.applying) return viewModelScope.launch { }
        val advertised = state.options.providers.any { provider ->
            provider.slug == selection.provider && selection.model in provider.models
        }
        if (!advertised) {
            mutableModelPickerState.value = state.copy(error = "That model is no longer available")
            return viewModelScope.launch { }
        }
        val controller = liveControllers[state.durableSessionId]
        val session = controller?.session
        val runtimeSessionId = controller?.runtimeSessionId
        if (session == null || runtimeSessionId == null) {
            mutableModelPickerState.value = state.copy(error = "Hermes session is not ready")
            return viewModelScope.launch { }
        }
        val requestGeneration = modelPickerGeneration
        mutableModelPickerState.value = state.copy(
            applying = true,
            error = null,
            pendingSelection = null,
            confirmationMessage = null,
        )
        val job = viewModelScope.launch {
            try {
                val result = session.setModel(
                    runtimeSessionId = runtimeSessionId,
                    provider = selection.provider,
                    model = selection.model,
                    confirmExpensiveModel = confirmExpensive,
                )
                val current = mutableModelPickerState.value as? ModelPickerState.Ready
                if (
                    requestGeneration != modelPickerGeneration ||
                    current?.durableSessionId != state.durableSessionId
                ) return@launch
                mutableModelPickerState.value = when {
                    result.confirmationRequired -> current.copy(
                        applying = false,
                        pendingSelection = selection,
                        confirmationMessage = result.confirmationMessage
                            ?: "Hermes requires confirmation for this model.",
                    )
                    result.accepted -> ModelPickerState.Closed
                    else -> current.copy(applying = false, error = "Hermes did not accept the model change")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val current = mutableModelPickerState.value as? ModelPickerState.Ready
                if (
                    requestGeneration == modelPickerGeneration &&
                    current?.durableSessionId == state.durableSessionId
                ) {
                    mutableModelPickerState.value = current.copy(
                        applying = false,
                        error = error.message?.take(160) ?: "Could not change model",
                    )
                }
            }
        }
        modelPickerJob = job
        return job
    }

    private fun publishModelPickerError(
        requestGeneration: Long,
        durableSessionId: DurableSessionId,
        message: String,
    ) {
        if (!isCurrentModelPicker(requestGeneration, durableSessionId)) return
        mutableModelPickerState.value = ModelPickerState.Error(durableSessionId, message)
    }

    private fun isCurrentModelPicker(
        requestGeneration: Long,
        durableSessionId: DurableSessionId,
    ): Boolean {
        if (requestGeneration != modelPickerGeneration) return false
        return when (val state = mutableModelPickerState.value) {
            is ModelPickerState.Loading -> state.durableSessionId == durableSessionId
            is ModelPickerState.Ready -> state.durableSessionId == durableSessionId
            is ModelPickerState.Error -> state.durableSessionId == durableSessionId
            ModelPickerState.Closed -> false
        }
    }

    private fun serverDurableId(localId: DurableSessionId): DurableSessionId =
        serverDurableIds[localId] ?: localId

    private fun localDraftSession(durableSessionId: DurableSessionId): SessionSummary? =
        mutableSnapshots.value.durableSessions.firstOrNull {
            it.id == durableSessionId && it.isLocalDraft
        } ?: mutableSnapshots.value.projectSessions.values
            .asSequence()
            .flatten()
            .firstOrNull { it.id == durableSessionId && it.isLocalDraft }

    private fun clearAllSlashCompletions() {
        slashCompletionJobs.values.forEach(Job::cancel)
        slashCompletionJobs.clear()
        slashCompletionGenerations.clear()
        mutableSlashCompletions.value = emptyMap()
    }

    private suspend fun ensureLiveSession(
        origin: ServerOrigin,
        originGeneration: Long,
        operationGeneration: Long,
        accessToken: String,
        durableSessionId: DurableSessionId,
        closeWhenIdle: Boolean = false,
    ): HermesChatSession {
        val existingController = liveControllers[durableSessionId]
        if (existingController != null) {
            val existing = existingController.session
            val runtimeId = existingController.runtimeSessionId
            existingController.operationGeneration = operationGeneration
            existingController.recoveryState = ChatRecoveryState(operationGeneration)
            activeChatSession = existing
            activeChatDurableId = durableSessionId
            activeRuntimeSessionId = runtimeId
            chatOperationGeneration = operationGeneration
            chatRecoveryState = existingController.recoveryState
            publishActiveRuntime(durableSessionId, runtimeId)
            collectEvents(
                existing,
                durableSessionId,
                runtimeId,
                origin,
                originGeneration,
                operationGeneration,
            )
            return existing
        }
        val connector = chatConnector
            ?: throw HermesConnectionException("Live chat is unavailable")
        if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
            throw CancellationException("Chat operation was replaced")
        }
        val session = connector.connect(origin, accessToken)
        try {
            val resumed = if (durableSessionId in pendingDraftSessions) {
                session.createSession(
                    durableSessionId = durableSessionId,
                    profile = "default",
                    workspacePath = localDraftSession(durableSessionId)
                        ?.workspacePath
                        ?.let(::validProjectWorkspacePath),
                )
            } else {
                session.resume(serverDurableId(durableSessionId), profile = "default")
            }
            if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                throw CancellationException("Chat operation was replaced")
            }
            resumed.durableSessionId
                ?.takeIf { it != durableSessionId }
                ?.let { serverDurableIds[durableSessionId] = it }
            applyResume(durableSessionId, resumed)
            if (closeWhenIdle && !resumed.running) {
                session.close()
                return session
            }
            liveControllers[durableSessionId] = LiveChatController(
                durableSessionId = durableSessionId,
                session = session,
                runtimeSessionId = resumed.runtimeSessionId,
                operationGeneration = operationGeneration,
                recoveryState = ChatRecoveryState(operationGeneration),
            )
            activeChatSession = session
            activeChatDurableId = durableSessionId
            activeRuntimeSessionId = resumed.runtimeSessionId
            chatOperationGeneration = operationGeneration
            chatRecoveryState = liveControllers[durableSessionId]?.recoveryState
            publishActiveRuntime(durableSessionId, resumed.runtimeSessionId)
            collectEvents(
                session,
                durableSessionId,
                resumed.runtimeSessionId,
                origin,
                originGeneration,
                operationGeneration,
            )
            return session
        } catch (error: Throwable) {
            closeChatSessionNonCancellably(session)
            throw error
        }
    }

    private fun markDraftPersisted(
        durableSessionId: DurableSessionId,
        origin: ServerOrigin,
        originGeneration: Long,
        operationGeneration: Long,
    ) {
        val wasDraft = pendingDraftSessions.remove(durableSessionId)
        if (wasDraft) promoteLocalDraftSummary(durableSessionId)
        refreshSessionsAfterFirstPrompt(
            durableSessionId,
            origin,
            originGeneration,
            operationGeneration,
        )
    }

    private fun reconcileCanonicalSessionMetadata(
        localId: DurableSessionId,
        canonical: SessionSummary,
    ) {
        val snapshot = mutableSnapshots.value
        fun reconcile(session: SessionSummary): SessionSummary =
            if (session.id == localId) {
                canonical.copy(
                    id = localId,
                    projectId = session.projectId,
                    workspacePath = canonical.workspacePath ?: session.workspacePath,
                    isLocalDraft = false,
                )
            } else {
                session
            }
        val projectSessions = snapshot.projectSessions.mapValues { (_, sessions) ->
            sessions.map(::reconcile)
        }
        val projectSessionStates = snapshot.projectSessionStates.mapValues { (_, state) ->
            if (state is ProjectSessionLoadState.Loaded) {
                state.copy(sessions = state.sessions.map(::reconcile))
            } else {
                state
            }
        }
        val canonicalDurable = canonical.copy(id = localId, isLocalDraft = false)
        val durableSessions = if (snapshot.durableSessions.any { it.id == localId }) {
            snapshot.durableSessions.map(::reconcile)
        } else {
            listOf(canonicalDurable) + snapshot.durableSessions
        }
        mutableSnapshots.value = snapshot.copy(
            durableSessions = durableSessions,
            projectSessions = projectSessions,
            projectSessionStates = projectSessionStates,
        )
    }

    private fun promoteLocalDraftSummary(durableSessionId: DurableSessionId) {
        val snapshot = mutableSnapshots.value
        fun promote(session: SessionSummary): SessionSummary =
            if (session.id == durableSessionId && session.isLocalDraft) {
                session.copy(isLocalDraft = false)
            } else {
                session
            }
        val durableSessions = snapshot.durableSessions.map(::promote)
        val projectSessions = snapshot.projectSessions.mapValues { (_, sessions) ->
            sessions.map(::promote)
        }
        val projectSessionStates = snapshot.projectSessionStates.mapValues { (_, state) ->
            when (state) {
                is ProjectSessionLoadState.Loaded -> state.copy(sessions = state.sessions.map(::promote))
                is ProjectSessionLoadState.TransientError,
                ProjectSessionLoadState.Loading,
                ProjectSessionLoadState.Unsupported,
                -> state
            }
        }
        mutableSnapshots.value = snapshot.copy(
            durableSessions = durableSessions,
            projectSessions = projectSessions,
            projectSessionStates = projectSessionStates,
        )
    }

    /**
     * Once the gateway accepts a draft's first prompt, its durable row exists server-side;
     * reload the transcript so the local draft converges with the canonical session.
     */
    private fun refreshSessionsAfterFirstPrompt(
        durableSessionId: DurableSessionId,
        origin: ServerOrigin,
        originGeneration: Long,
        operationGeneration: Long,
    ) {
        viewModelScope.launch {
            val accessToken = try {
                accessTokenForRequest(origin, originGeneration)
            } catch (_: Exception) {
                null
            } ?: return@launch
            val canonicalSessions = try {
                client.authenticate(origin, accessToken).sessions
            } catch (_: Exception) {
                emptyList()
            }
            if (!isCurrentOrigin(origin, originGeneration)) return@launch
            val canonicalId = serverDurableId(durableSessionId)
            val canonical = canonicalSessions.firstOrNull { it.id == canonicalId }
            if (canonical != null) {
                reconcileCanonicalSessionMetadata(durableSessionId, canonical)
            }
            val messages = try {
                client.loadTranscript(origin, accessToken, canonicalId)
            } catch (_: Exception) {
                return@launch
            }
            if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
            updateChat(durableSessionId) { current ->
                if (current.messages.isEmpty()) {
                    current.copy(messages = messages)
                } else {
                    current
                }
            }
        }
    }

    private fun collectEvents(
        session: HermesChatSession,
        durableSessionId: DurableSessionId,
        runtimeSessionId: RuntimeSessionId,
        origin: ServerOrigin,
        originGeneration: Long,
        operationGeneration: Long,
    ) {
        val controller = liveControllers[durableSessionId] ?: return
        controller.eventJob?.cancel()
        controller.eventJob = viewModelScope.launch {
            session.events.collect { event ->
                if (
                    !isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) ||
                    event.sessionId != runtimeSessionId ||
                    liveControllers[durableSessionId]?.session !== session
                ) {
                    return@collect
                }
                when (event) {
                    is HermesChatEvent.MessageStart -> updateAssistant(durableSessionId) { current ->
                        event.text ?: current
                    }
                    is HermesChatEvent.MessageDelta -> updateAssistant(durableSessionId) { current ->
                        current + event.text
                    }
                    is HermesChatEvent.ReasoningDelta -> appendAssistantReasoning(
                        durableSessionId,
                        event.text,
                        event.replace,
                    )
                    is HermesChatEvent.MessageInterim -> updateChat(durableSessionId) { current ->
                        if (event.text.isBlank()) {
                            current
                        } else {
                            val messages = current.messages.toMutableList()
                            val streamingIndex = messages.indexOfLast {
                                it.role == ChatMessageRole.Assistant && it.isStreaming
                            }
                            if (streamingIndex >= 0) {
                                messages[streamingIndex] = messages[streamingIndex].copy(
                                    text = event.text,
                                    isStreaming = false,
                                )
                            } else {
                                messages += ChatMessage(ChatMessageRole.Assistant, event.text)
                            }
                            current.copy(messages = messages)
                        }
                    }
                    is HermesChatEvent.ToolGenerating -> updateRunState(durableSessionId, event)
                    is HermesChatEvent.SessionTitle -> updateSessionTitle(
                        durableSessionId,
                        event.title,
                    )
                    is HermesChatEvent.SessionInfo -> {
                        event.storedSessionId
                            ?.takeIf { it != durableSessionId }
                            ?.let { serverDurableIds[durableSessionId] = it }
                        updateChat(durableSessionId) { current ->
                            current.copy(
                                model = event.model ?: current.model,
                                provider = event.provider ?: current.provider,
                                reasoningEffort = event.reasoningEffort ?: current.reasoningEffort,
                                isSending = event.running ?: current.isSending,
                            )
                        }
                        event.title?.takeIf(String::isNotBlank)?.let { title ->
                            updateSessionTitle(durableSessionId, title)
                        }
                    }
                    is HermesChatEvent.MessageComplete -> {
                        updateAssistant(durableSessionId, streaming = false) { current ->
                            event.text ?: current
                        }
                        val billingNotice = event.billing?.let { billing ->
                            ChatBillingNotice(
                                provider = billing.provider,
                                billingUrl = billing.billingUrl,
                                isNous = billing.isNous,
                                message = billing.message ?: event.failureReason,
                            )
                        }
                        val terminalError = when (event.status?.lowercase()) {
                            "error", "failed" -> if (billingNotice != null) {
                                null
                            } else {
                                "Hermes response failed"
                            }
                            "cancelled", "canceled", "interrupted" ->
                                "Hermes response was cancelled"
                            else -> if (event.error.isNullOrBlank()) {
                                null
                            } else {
                                "Hermes response failed"
                            }
                        }
                        event.reasoning?.takeIf(String::isNotBlank)?.let { reasoning ->
                            updateChat(durableSessionId) { current ->
                                val messages = current.messages.toMutableList()
                                val index = messages.indexOfLast {
                                    it.role == ChatMessageRole.Assistant
                                }
                                if (index >= 0 && messages[index].reasoningText.isBlank()) {
                                    messages[index] = messages[index].copy(
                                        reasoningText = reasoning,
                                    )
                                    current.copy(messages = messages)
                                } else {
                                    current
                                }
                            }
                        }
                        updateChat(durableSessionId) {
                            it.copy(
                                isSending = false,
                                error = terminalError,
                                notice = event.warning?.takeIf(String::isNotBlank),
                                billingNotice = billingNotice,
                                runState = it.runState.reduce(event),
                            )
                        }
                        notifications.turnCompleted(
                            durableSessionId,
                            sessionTitle(durableSessionId),
                            event.text.orEmpty(),
                            event.status,
                        )
                        activeTurnIds.remove(durableSessionId)
                        notifications.activeCountChanged(activeTurnIds.size)
                        removeActiveRuntime(runtimeSessionId)
                        markDraftPersisted(
                            durableSessionId,
                            origin,
                            originGeneration,
                            operationGeneration,
                        )
                    }
                    is HermesChatEvent.Error -> {
                        updateAssistant(durableSessionId, streaming = false) { current -> current }
                        updateChat(durableSessionId) {
                            it.copy(
                                isSending = false,
                                error = event.message.take(160),
                                runState = it.runState.reduce(event),
                            )
                        }
                        activeTurnIds.remove(durableSessionId)
                        notifications.activeCountChanged(activeTurnIds.size)
                        removeActiveRuntime(runtimeSessionId)
                        detachController(durableSessionId, session, closeSession = true)
                    }
                    is HermesChatEvent.ToolStart -> updateRunState(durableSessionId, event)
                    is HermesChatEvent.ToolComplete -> updateRunState(durableSessionId, event)
                    is HermesChatEvent.StatusUpdate -> updateRunState(durableSessionId, event)
                    is HermesChatEvent.ClarifyRequest -> {
                        updateRunState(durableSessionId, event)
                        notifications.clarificationRequired(
                            durableSessionId,
                            sessionTitle(durableSessionId),
                            event.question,
                        )
                    }
                    is HermesChatEvent.ClarifyExpire -> updateRunState(durableSessionId, event)
                    is HermesChatEvent.ApprovalRequest -> {
                        updateRunState(durableSessionId, event)
                        notifications.approvalRequired(
                            durableSessionId,
                            sessionTitle(durableSessionId),
                            event.description ?: event.command ?: "Authorization is required to continue",
                        )
                    }
                    is HermesChatEvent.ApprovalExpire -> updateRunState(durableSessionId, event)
                    is HermesChatEvent.UnsupportedBlockingRequest -> {
                        updateRunState(durableSessionId, event)
                        notifications.unsupportedInputRequired(
                            durableSessionId,
                            sessionTitle(durableSessionId),
                            event.prompt ?: "Open a controlling Hermes client to continue",
                        )
                    }
                    is HermesChatEvent.UnsupportedBlockingExpire -> updateRunState(durableSessionId, event)
                }
            }
            if (liveControllers[durableSessionId]?.session === session) {
                removeActiveRuntime(runtimeSessionId)
                if (mutableSnapshots.value.chatSessions[durableSessionId]?.isSending != true) {
                    detachController(durableSessionId, session, closeSession = false)
                }
            }
            if (
                isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) &&
                mutableSnapshots.value.chatSessions[durableSessionId]?.isSending == true
            ) {
                val recoveryAttempt = startChatRecovery(durableSessionId, operationGeneration)
                if (recoveryAttempt != null) {
                    try {
                        recoverChat(
                            durableSessionId,
                            origin,
                            originGeneration,
                            operationGeneration,
                            recoveryAttempt,
                        )
                    } finally {
                        finishChatRecovery(recoveryAttempt)
                    }
                } else if (!isChatRecoveryInProgress(durableSessionId, operationGeneration)) {
                    clearSendingState(durableSessionId)
                    updateChat(durableSessionId) {
                        it.copy(error = "Connection lost while receiving response")
                    }
                }
            }
        }
    }

    private fun startChatRecovery(
        durableSessionId: DurableSessionId,
        operationGeneration: Long,
    ): ChatRecoveryAttempt? {
        val state = liveControllers[durableSessionId]?.recoveryState
            ?.takeIf { it.operationGeneration == operationGeneration }
            ?: return null
        if (state.activeAttempt != null || state.remaining <= 0) return null
        state.remaining -= 1
        return ChatRecoveryAttempt(state).also { state.activeAttempt = it }
    }

    private fun finishChatRecovery(attempt: ChatRecoveryAttempt) {
        if (liveControllers.values.any { it.recoveryState === attempt.state } &&
            attempt.state.activeAttempt === attempt
        ) {
            attempt.state.activeAttempt = null
        }
    }

    private fun isChatRecoveryInProgress(
        durableSessionId: DurableSessionId,
        operationGeneration: Long,
    ): Boolean =
        liveControllers[durableSessionId]?.recoveryState
            ?.takeIf { it.operationGeneration == operationGeneration }
            ?.activeAttempt != null

    private suspend fun recoverChat(
        durableSessionId: DurableSessionId,
        origin: ServerOrigin,
        originGeneration: Long,
        operationGeneration: Long,
        recoveryAttempt: ChatRecoveryAttempt,
    ) {
        if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return
        val connector = chatConnector ?: return
        val previous = liveControllers[durableSessionId]
        closeChatSessionNonCancellably(previous?.session)
        liveControllers.remove(durableSessionId)

        for (backoffMillis in listOf(500L, 1_000L, 2_000L)) {
            appForegroundStates.first { it }
            if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return
            delay(backoffMillis)
            appForegroundStates.first { it }
            if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return
            var candidate: HermesChatSession? = null
            try {
                val token = accessTokenForRequest(origin, originGeneration)
                    ?: throw HermesConnectionException("Sign in is required to reconnect")
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return
                candidate = connector.connect(origin, token)
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                    closeChatSessionNonCancellably(candidate)
                    return
                }
                val resumed = candidate.resume(
                    serverDurableId(durableSessionId),
                    profile = "default",
                )
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                    closeChatSessionNonCancellably(candidate)
                    return
                }
                markDraftPersisted(
                    durableSessionId,
                    origin,
                    originGeneration,
                    operationGeneration,
                )
                applyResume(durableSessionId, resumed)
                if (resumed.running) {
                    liveControllers[durableSessionId] = LiveChatController(
                        durableSessionId = durableSessionId,
                        session = candidate,
                        runtimeSessionId = resumed.runtimeSessionId,
                        operationGeneration = operationGeneration,
                        recoveryState = recoveryAttempt.state,
                    )
                    activeChatSession = candidate
                    activeChatDurableId = durableSessionId
                    activeRuntimeSessionId = resumed.runtimeSessionId
                    chatOperationGeneration = operationGeneration
                    chatRecoveryState = recoveryAttempt.state
                    publishActiveRuntime(durableSessionId, resumed.runtimeSessionId)
                    finishChatRecovery(recoveryAttempt)
                    collectEvents(
                        candidate,
                        durableSessionId,
                        resumed.runtimeSessionId,
                        origin,
                        originGeneration,
                        operationGeneration,
                    )
                } else {
                    val messages = client.loadTranscript(
                        origin,
                        token,
                        serverDurableId(durableSessionId),
                    )
                    if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                        closeChatSessionNonCancellably(candidate)
                        return
                    }
                    updateChat(durableSessionId) {
                        it.copy(messages = messages, isSending = false, error = null)
                    }
                    closeChatSessionNonCancellably(candidate)
                }
                return
            } catch (cancelled: CancellationException) {
                closeChatSessionNonCancellably(candidate)
                throw cancelled
            } catch (_: NativeRefreshExpiredException) {
                closeChatSessionNonCancellably(candidate)
                if (isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                    disconnectChat()
                    publishSignInRequired()
                }
                return
            } catch (_: Exception) {
                closeChatSessionNonCancellably(candidate)
            }
        }

        appForegroundStates.first { it }
        if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return
        clearSendingState(durableSessionId)
        updateChat(durableSessionId) {
            it.copy(
                error = "Connection lost while receiving response",
            )
        }
    }

    private fun applyResume(durableSessionId: DurableSessionId, resumed: ResumedChatSession) {
        val resumedMessages = resumed.messages.mapNotNull(::chatMessageFromJson)
        updateChat(durableSessionId) { current ->
            val baseMessages = if (resumedMessages.isNotEmpty()) resumedMessages else current.messages
            val withInflightUser = resumed.inflight?.user
                ?.takeIf(String::isNotBlank)
                ?.let { prompt ->
                    val messages = baseMessages.toMutableList()
                    val latestUser = messages.indexOfLast { it.role == ChatMessageRole.User }
                    if (latestUser < 0 || messages[latestUser].text != prompt) {
                        messages += ChatMessage(ChatMessageRole.User, prompt)
                    }
                    messages
                }
                ?: baseMessages
            val withInflight = resumed.inflight?.assistant?.let { partial ->
                val messages = withInflightUser.toMutableList()
                val localPartialIndex = messages.indexOfLast {
                    it.role == ChatMessageRole.Assistant && it.isStreaming
                }
                val snapshot = ChatMessage(
                    role = ChatMessageRole.Assistant,
                    text = partial,
                    isStreaming = true,
                )
                if (localPartialIndex >= 0) {
                    messages[localPartialIndex] = snapshot
                } else {
                    messages += snapshot
                }
                messages
            } ?: withInflightUser
            current.copy(
                messages = withInflight,
                isLoading = false,
                isSending = resumed.running,
                error = null,
                model = resumed.model ?: current.model,
                provider = resumed.provider ?: current.provider,
                reasoningEffort = resumed.reasoningEffort ?: current.reasoningEffort,
            )
        }
    }

    private fun chatMessageFromJson(row: JsonObject): ChatMessage? {
        val role = when (row["role"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "user" -> ChatMessageRole.User
            "assistant" -> ChatMessageRole.Assistant
            "system" -> ChatMessageRole.System
            "tool" -> ChatMessageRole.Tool
            else -> return null
        }
        val text = when (role) {
            ChatMessageRole.Tool -> row.transcriptToolText()
            else -> row["content"]?.jsonPrimitive?.contentOrNull
                ?: row["text"]?.jsonPrimitive?.contentOrNull
        }
        val reasoning = if (role == ChatMessageRole.Assistant) {
            row.assistantReasoningText()
        } else {
            null
        }
        if (text == null && reasoning == null) return null
        return ChatMessage(
            role = role,
            text = text.orEmpty(),
            reasoningText = reasoning.orEmpty(),
        )
    }

    private fun updateAssistant(
        durableSessionId: DurableSessionId,
        streaming: Boolean = true,
        transform: (String) -> String,
    ) {
        updateChat(durableSessionId) { current ->
            val messages = current.messages.toMutableList()
            val index = messages.indexOfLast {
                it.role == ChatMessageRole.Assistant && it.isStreaming
            }
            if (index >= 0) {
                messages[index] = messages[index].copy(
                    text = transform(messages[index].text),
                    isStreaming = streaming,
                )
            } else {
                messages += ChatMessage(
                    role = ChatMessageRole.Assistant,
                    text = transform(""),
                    isStreaming = streaming,
                )
            }
            current.copy(messages = messages)
        }
    }

    private fun appendAssistantReasoning(
        durableSessionId: DurableSessionId,
        text: String,
        replace: Boolean,
    ) {
        if (text.isBlank()) return
        updateChat(durableSessionId) { current ->
            val messages = current.messages.toMutableList()
            val index = messages.indexOfLast {
                it.role == ChatMessageRole.Assistant && it.isStreaming
            }
            if (index >= 0) {
                messages[index] = messages[index].copy(
                    reasoningText = if (replace) text else messages[index].reasoningText + text,
                )
            } else {
                messages += ChatMessage(
                    role = ChatMessageRole.Assistant,
                    text = "",
                    reasoningText = text,
                    isStreaming = true,
                )
            }
            current.copy(messages = messages)
        }
    }

    private fun updateSessionTitle(
        durableSessionId: DurableSessionId,
        title: String,
    ) {
        val clean = title.trim().take(MAX_SESSION_TITLE_CHARS)
        if (clean.isEmpty()) return
        val snapshot = mutableSnapshots.value
        val sessions = snapshot.durableSessions.map { session ->
            if (session.id == durableSessionId) session.copy(title = clean) else session
        }
        val projectSessions = snapshot.projectSessions.mapValues { (_, entries) ->
            entries.map { session ->
                if (session.id == durableSessionId) session.copy(title = clean) else session
            }
        }
        mutableSnapshots.value = snapshot.copy(
            durableSessions = sessions,
            projectSessions = projectSessions,
        )
    }

    private fun updateRunState(
        durableSessionId: DurableSessionId,
        event: HermesChatEvent,
    ) {
        updateChat(durableSessionId) { current ->
            current.copy(runState = current.runState.reduce(event))
        }
    }

    private fun beginClarificationResponse(
        durableSessionId: DurableSessionId,
        requestId: String,
    ): ControllerOperation? = synchronized(controllerLock) {
        val origin = activeOrigin ?: return@synchronized null
        val controller = liveControllers[durableSessionId] ?: return@synchronized null
        val session = controller.session
        val runtimeSessionId = controller.runtimeSessionId
        val snapshot = mutableSnapshots.value
        val chat = snapshot.chatSessions[durableSessionId] ?: return@synchronized null
        val clarification = chat.runState.clarification ?: return@synchronized null
        val operationGeneration = controller.operationGeneration
        val originGeneration = generation
        if (
            !isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) ||
            clarification.runtimeSessionId != runtimeSessionId ||
            clarification.requestId != requestId ||
            clarification.lifecycle != RunInteractionLifecycle.Pending
        ) return@synchronized null

        val updatedChat = chat.copy(
            runState = chat.runState.transitionClarificationLifecycle(
                requestId,
                RunInteractionLifecycle.Responding,
            ),
        )
        mutableSnapshots.value = snapshot.copy(
            chatSessions = snapshot.chatSessions + (durableSessionId to updatedChat),
        )
        ControllerOperation(
            durableSessionId = durableSessionId,
            session = session,
            runtimeSessionId = runtimeSessionId,
            origin = origin,
            originGeneration = originGeneration,
            chatOperationGeneration = operationGeneration,
            requestId = requestId,
        )
    }

    private fun beginApprovalResponse(
        durableSessionId: DurableSessionId,
        choice: String,
    ): ControllerOperation? = synchronized(controllerLock) {
        val origin = activeOrigin ?: return@synchronized null
        val controller = liveControllers[durableSessionId] ?: return@synchronized null
        val session = controller.session
        val runtimeSessionId = controller.runtimeSessionId
        val snapshot = mutableSnapshots.value
        val chat = snapshot.chatSessions[durableSessionId] ?: return@synchronized null
        val approval = chat.runState.approval ?: return@synchronized null
        val operationGeneration = controller.operationGeneration
        val originGeneration = generation
        if (
            !isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) ||
            approval.runtimeSessionId != runtimeSessionId ||
            approval.lifecycle != RunInteractionLifecycle.Pending ||
            choice !in approval.choices
        ) return@synchronized null

        val updatedChat = chat.copy(
            runState = chat.runState.transitionApprovalLifecycle(
                runtimeSessionId,
                approval.requestId,
                RunInteractionLifecycle.Responding,
            ),
        )
        mutableSnapshots.value = snapshot.copy(
            chatSessions = snapshot.chatSessions + (durableSessionId to updatedChat),
        )
        ControllerOperation(
            durableSessionId = durableSessionId,
            session = session,
            runtimeSessionId = runtimeSessionId,
            origin = origin,
            originGeneration = originGeneration,
            chatOperationGeneration = operationGeneration,
            requestId = approval.requestId,
            advertisedChoices = approval.choices,
        )
    }

    private fun beginStopSession(
        durableSessionId: DurableSessionId,
    ): ControllerOperation? = synchronized(controllerLock) {
        val origin = activeOrigin ?: return@synchronized null
        val controller = liveControllers[durableSessionId] ?: return@synchronized null
        val session = controller.session
        val runtimeSessionId = controller.runtimeSessionId
        val operationGeneration = controller.operationGeneration
        val originGeneration = generation
        val snapshot = mutableSnapshots.value
        val chat = snapshot.chatSessions[durableSessionId] ?: return@synchronized null
        if (
            !isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) ||
            !chat.isSending ||
            chat.isStopping ||
            snapshot.activeRuntimes.none {
                it.runtimeSessionId == runtimeSessionId &&
                    it.durableSessionId == durableSessionId &&
                    it.access == RuntimeAccess.Controller
            }
        ) return@synchronized null

        mutableSnapshots.value = snapshot.copy(
            chatSessions = snapshot.chatSessions + (
                durableSessionId to chat.copy(isStopping = true)
                ),
        )
        ControllerOperation(
            durableSessionId = durableSessionId,
            session = session,
            runtimeSessionId = runtimeSessionId,
            origin = origin,
            originGeneration = originGeneration,
            chatOperationGeneration = operationGeneration,
        )
    }

    private fun publishStopSuccess(operation: ControllerOperation) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            if (!chat.isStopping) return
            val messages = chat.messages.mapNotNull { message ->
                when {
                    message.role == ChatMessageRole.Assistant &&
                        message.isStreaming &&
                        message.text.isEmpty() -> null
                    message.isStreaming -> message.copy(isStreaming = false)
                    else -> message
                }
            }
            mutableSnapshots.value = snapshot.copy(
                activeRuntimes = snapshot.activeRuntimes.filterNot {
                    it.runtimeSessionId == operation.runtimeSessionId
                },
                chatSessions = snapshot.chatSessions + (
                    operation.durableSessionId to chat.copy(
                        messages = messages,
                        isLoading = false,
                        isSending = false,
                        isStopping = false,
                        error = null,
                        runState = terminalizeLiveInteractions(chat.runState),
                    )
                    ),
            )
        }
    }

    private fun publishStopFailure(operation: ControllerOperation) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            if (!chat.isStopping) return
            mutableSnapshots.value = snapshot.copy(
                chatSessions = snapshot.chatSessions + (
                    operation.durableSessionId to chat.copy(
                        isStopping = false,
                        error = "Could not stop session",
                    )
                    ),
            )
        }
    }

    private fun clearStoppingIfCurrent(operation: ControllerOperation) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            if (!chat.isStopping) return
            mutableSnapshots.value = snapshot.copy(
                chatSessions = snapshot.chatSessions + (
                    operation.durableSessionId to chat.copy(isStopping = false)
                    ),
            )
        }
    }

    private fun terminalizeLiveInteractions(runState: RunEventState): RunEventState = runState.copy(
        clarification = runState.clarification?.let { interaction ->
            if (interaction.lifecycle == RunInteractionLifecycle.Pending ||
                interaction.lifecycle == RunInteractionLifecycle.Responding
            ) interaction.copy(lifecycle = RunInteractionLifecycle.Expired) else interaction
        },
        approval = runState.approval?.let { interaction ->
            if (interaction.lifecycle == RunInteractionLifecycle.Pending ||
                interaction.lifecycle == RunInteractionLifecycle.Responding
            ) interaction.copy(lifecycle = RunInteractionLifecycle.Expired) else interaction
        },
        unsupportedBlocking = runState.unsupportedBlocking?.let { interaction ->
            if (interaction.lifecycle == RunInteractionLifecycle.Pending ||
                interaction.lifecycle == RunInteractionLifecycle.Responding
            ) interaction.copy(lifecycle = RunInteractionLifecycle.Expired) else interaction
        },
    )

    private fun isCurrentControllerOperation(operation: ControllerOperation): Boolean =
        isCurrentChatOperation(
            operation.durableSessionId,
            operation.origin,
            operation.originGeneration,
            operation.chatOperationGeneration,
        ) &&
            liveControllers[operation.durableSessionId]?.let { controller ->
                controller.session === operation.session &&
                    controller.runtimeSessionId == operation.runtimeSessionId &&
                    controller.operationGeneration == operation.chatOperationGeneration
            } == true

    private fun publishClarificationResponse(
        operation: ControllerOperation,
        lifecycle: RunInteractionLifecycle,
    ) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            val current = chat.runState.clarification ?: return
            if (
                current.runtimeSessionId != operation.runtimeSessionId ||
                current.requestId != operation.requestId ||
                current.lifecycle != RunInteractionLifecycle.Responding
            ) return
            mutableSnapshots.value = snapshot.copy(
                chatSessions = snapshot.chatSessions + (
                    operation.durableSessionId to chat.copy(
                        error = null,
                        runState = chat.runState.transitionClarificationLifecycle(
                            checkNotNull(operation.requestId),
                            lifecycle,
                        ),
                    )
                    ),
            )
        }
    }

    private fun publishApprovalResponse(
        operation: ControllerOperation,
        lifecycle: RunInteractionLifecycle,
    ) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            val current = chat.runState.approval ?: return
            if (
                current.runtimeSessionId != operation.runtimeSessionId ||
                current.requestId != operation.requestId ||
                current.choices != operation.advertisedChoices ||
                current.lifecycle != RunInteractionLifecycle.Responding
            ) return
            mutableSnapshots.value = snapshot.copy(
                chatSessions = snapshot.chatSessions + (
                    operation.durableSessionId to chat.copy(
                        error = null,
                        runState = chat.runState.transitionApprovalLifecycle(
                            operation.runtimeSessionId,
                            operation.requestId,
                            lifecycle,
                        ),
                    )
                    ),
            )
        }
    }

    private fun publishApprovalError(operation: ControllerOperation) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            val approval = chat.runState.approval ?: return
            if (
                approval.runtimeSessionId != operation.runtimeSessionId ||
                approval.requestId != operation.requestId ||
                approval.choices != operation.advertisedChoices ||
                approval.lifecycle != RunInteractionLifecycle.Failed
            ) return
            updateChat(operation.durableSessionId) { current ->
                current.copy(error = "Could not respond to approval")
            }
        }
    }

    private fun publishControllerError(operation: ControllerOperation, error: String) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            val interaction = chat.runState.clarification ?: return
            if (
                interaction.runtimeSessionId != operation.runtimeSessionId ||
                interaction.requestId != operation.requestId ||
                interaction.lifecycle != RunInteractionLifecycle.Failed
            ) return
            updateChat(operation.durableSessionId) { current ->
                current.copy(error = error.take(160))
            }
        }
    }

    private fun publishActiveRuntime(
        durableSessionId: DurableSessionId,
        runtimeSessionId: RuntimeSessionId,
    ) {
        val title = mutableSnapshots.value.durableSessions
            .firstOrNull { it.id == durableSessionId }
            ?.title
            ?: mutableSnapshots.value.projectSessions.values
                .asSequence()
                .flatten()
                .firstOrNull { it.id == durableSessionId }
                ?.title
            ?: "Untitled session"
        val active = ActiveRuntimeSession(
            runtimeSessionId = runtimeSessionId,
            durableSessionId = durableSessionId,
            title = title,
            access = RuntimeAccess.Controller,
        )
        mutableSnapshots.value = mutableSnapshots.value.copy(
            activeRuntimes = mutableSnapshots.value.activeRuntimes
                .filterNot { it.runtimeSessionId == runtimeSessionId } + active,
        )
    }

    private fun sessionTitle(durableSessionId: DurableSessionId): String =
        mutableSnapshots.value.durableSessions.firstOrNull { it.id == durableSessionId }?.title
            ?: mutableSnapshots.value.projectSessions.values.asSequence().flatten()
                .firstOrNull { it.id == durableSessionId }?.title
            ?: "Hermes session"

    private fun removeActiveRuntime(runtimeSessionId: RuntimeSessionId) {
        if (mutableSnapshots.value.activeRuntimes.none { it.runtimeSessionId == runtimeSessionId }) return
        mutableSnapshots.value = mutableSnapshots.value.copy(
            activeRuntimes = mutableSnapshots.value.activeRuntimes
                .filterNot { it.runtimeSessionId == runtimeSessionId },
        )
    }

    private fun isCurrentChatOperation(
        durableSessionId: DurableSessionId,
        origin: ServerOrigin,
        originGeneration: Long,
        operationGeneration: Long,
    ): Boolean =
        activeOrigin == origin &&
            generation == originGeneration &&
            chatOperationGenerations[durableSessionId] == operationGeneration

    private fun clearTransientChatStates() {
        val snapshot = mutableSnapshots.value
        if (snapshot.chatSessions.none { (_, chat) ->
                chat.isLoading || chat.isSending || chat.messages.any(ChatMessage::isStreaming)
            }
        ) return
        mutableSnapshots.value = snapshot.copy(
            chatSessions = snapshot.chatSessions.mapValues { (_, chat) ->
                chat.copy(
                    messages = chat.messages.mapNotNull { message ->
                        when {
                            message.role == ChatMessageRole.Assistant &&
                                message.isStreaming &&
                                message.text.isEmpty() -> null
                            message.isStreaming -> message.copy(isStreaming = false)
                            else -> message
                        }
                    },
                    isLoading = false,
                    isSending = false,
                    isStopping = false,
                    runState = chat.runState.finishRunningTools(),
                )
            },
        )
    }

    private fun clearSendingState(durableSessionId: DurableSessionId) {
        updateChat(durableSessionId) { current ->
            current.copy(
                messages = current.messages.mapNotNull { message ->
                    when {
                        message.role == ChatMessageRole.Assistant &&
                            message.isStreaming &&
                            message.text.isEmpty() -> null
                        message.isStreaming -> message.copy(isStreaming = false)
                        else -> message
                    }
                },
                isLoading = false,
                isSending = false,
                isStopping = false,
                runState = current.runState.finishRunningTools(),
            )
        }
    }

    private fun updateChat(
        durableSessionId: DurableSessionId,
        transform: (ChatSessionSnapshot) -> ChatSessionSnapshot,
    ) {
        val snapshot = mutableSnapshots.value
        val current = snapshot.chatSessions[durableSessionId] ?: ChatSessionSnapshot()
        mutableSnapshots.value = snapshot.copy(
            chatSessions = snapshot.chatSessions + (durableSessionId to transform(current)),
        )
    }

    private suspend fun accessTokenForRequest(
        origin: ServerOrigin,
        expectedGeneration: Long,
    ): String? {
        if (mutableSnapshots.value.authenticationState == AuthenticationState.NotRequired) return null
        if (generation != expectedGeneration || activeOrigin != origin) {
            throw CancellationException("Server origin was replaced")
        }
        val active = activeTokens
            ?.takeIf { it.origin == origin && it.generation == expectedGeneration }
            ?: return null
        val refreshed = try {
            refreshIfNeeded(origin, active.tokens)
        } catch (expired: NativeRefreshExpiredException) {
            currentCoroutineContext().ensureActive()
            if (generation != expectedGeneration || activeOrigin != origin) {
                throw CancellationException("Server origin was replaced")
            }
            tokenStore?.clear(origin)
            if (activeTokens == active) activeTokens = null
            throw expired
        }
        currentCoroutineContext().ensureActive()
        if (generation != expectedGeneration || activeOrigin != origin) {
            throw CancellationException("Server origin was replaced")
        }
        if (refreshed == null) {
            tokenStore?.clear(origin)
            if (activeTokens == active) activeTokens = null
            throw NativeRefreshExpiredException()
        }
        if (refreshed != active.tokens) {
            tokenStore?.save(origin, refreshed)
            currentCoroutineContext().ensureActive()
            if (generation != expectedGeneration || activeOrigin != origin) {
                throw CancellationException("Server origin was replaced")
            }
        }
        activeTokens = ActiveTokenRecord(origin, expectedGeneration, refreshed)
        return refreshed.accessToken
    }

    private suspend fun refreshIfNeeded(
        origin: ServerOrigin,
        tokens: NativeTokenSet,
    ): NativeTokenSet? {
        if (tokens.expiresAt <= 0L || tokens.expiresAt > nowEpochSeconds() + TOKEN_REFRESH_SKEW_SECONDS) {
            return tokens
        }
        if (tokens.refreshToken.isBlank() || tokens.provider.isBlank()) return null
        return refreshClient?.refresh(origin, tokens.refreshToken, tokens.provider)
    }

    private suspend fun publishSignInRequired() {
        disconnectProjectMetadata()
        pendingDraftSessions.clear()
        serverDurableIds.clear()
        mutableAttachments.value = emptyMap()
        clearAllSlashCompletions()
        mutableSnapshots.value = mutableSnapshots.value.copy(
            connectionState = ConnectionState.Connected,
            authenticationState = AuthenticationState.SignInRequired,
            connectionError = null,
            durableSessions = emptyList(),
            chatSessions = emptyMap(),
            projects = emptyList(),
            projectState = ProjectLoadState.Loaded(emptyList()),
            activeProjectId = null,
            scopedSessionIds = emptySet(),
            projectSessions = emptyMap(),
            projectSessionStates = emptyMap(),
            activeRuntimes = emptyList(),
        )
    }

    private fun detachFailedRuntime(durableSessionId: DurableSessionId) {
        clearSlashCompletion(durableSessionId)
        val controller = liveControllers[durableSessionId] ?: return
        controller.eventJob?.cancel()
        removeActiveRuntime(controller.runtimeSessionId)
        detachController(durableSessionId, controller.session, closeSession = true)
    }

    private suspend fun closeChatSessionNonCancellably(session: HermesChatSession?) {
        if (session == null) return
        withContext(NonCancellable) {
            runCatching { session.close() }
        }
    }

    private fun detachController(
        durableSessionId: DurableSessionId,
        expectedSession: HermesChatSession,
        closeSession: Boolean,
    ) {
        val controller = liveControllers[durableSessionId]
            ?.takeIf { it.session === expectedSession }
            ?: return
        liveControllers.remove(durableSessionId)
        chatOperationGenerations.remove(durableSessionId)
        chatJobs.remove(durableSessionId)
        notifications.activeCountChanged(activeTurnIds.size)
        if (activeChatSession === expectedSession) {
            activeChatSession = null
            activeChatDurableId = null
            activeRuntimeSessionId = null
            chatRecoveryState = null
        }
        if (closeSession) {
            viewModelScope.launch { closeChatSessionNonCancellably(controller.session) }
        }
    }

    private suspend fun disconnectChat() {
        clearAllSlashCompletions()
        val controllers = liveControllers.values.toList()
        liveControllers.clear()
        chatOperationGenerations.clear()
        chatJobs.values.forEach(Job::cancel)
        chatJobs.clear()
        controllers.forEach { controller ->
            controller.eventJob?.cancel()
            removeActiveRuntime(controller.runtimeSessionId)
            closeChatSessionNonCancellably(controller.session)
        }
        activeTurnIds.clear()
        notifications.activeCountChanged(0)
        activeChatSession = null
        activeChatDurableId = null
        activeRuntimeSessionId = null
        chatRecoveryState = null
    }

    override fun onCleared() {
        signInJob?.cancel()
        chatJobs.values.forEach(Job::cancel)
        val controllerSessions = liveControllers.values.map { controller ->
            controller.eventJob?.cancel()
            controller.session
        }
        liveControllers.clear()
        chatOperationGenerations.clear()
        activeTurnIds.clear()
        val metadataSession = detachProjectMetadataSession()
        viewModelScope.launch {
            closeChatSessionNonCancellably(metadataSession)
            controllerSessions.forEach { closeChatSessionNonCancellably(it) }
        }
        closeResources()
    }

    class Factory(
        private val settingsStates: Flow<ServerSettingsState>,
        private val client: HermesConnectionClient,
        private val nativeLogin: NativeLogin? = null,
        private val closeResources: () -> Unit = {},
        private val tokenStore: NativeTokenStore? = null,
        private val refreshClient: NativeRefreshClient? = null,
        private val chatConnector: HermesChatConnector? = null,
        private val projectConnector: HermesChatConnector? = null,
        private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HermesConnectionViewModel::class.java))
            return HermesConnectionViewModel(
                settingsStates = settingsStates,
                client = client,
                nativeLogin = nativeLogin,
                closeResources = closeResources,
                tokenStore = tokenStore,
                refreshClient = refreshClient,
                chatConnector = chatConnector,
                projectConnector = projectConnector,
                nowEpochSeconds = nowEpochSeconds,
            ) as T
        }
    }

    class ProductionFactory(
        private val context: Context,
        private val settingsStates: Flow<ServerSettingsState>,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HermesConnectionViewModel::class.java))
            val httpClient = HttpClient(CIO) {
                configureHermesHttpClient()
                install(WebSockets) {
                    maxFrameSize = HERMES_CHAT_MAX_FRAME_BYTES.toLong()
                    pingIntervalMillis = 30_000L
                }
            }
            fun newConnector() = HermesChatConnector { origin, accessToken ->
                HermesChatGateway(
                    origin = origin,
                    accessToken = accessToken,
                    ticketClient = KtorWsTicketClient(httpClient),
                    socketFactory = KtorChatWebSocketFactory(httpClient),
                ).connect()
            }
            val chatConnector = newConnector()
            val projectConnector = newConnector()
            return HermesConnectionViewModel(
                settingsStates = settingsStates,
                client = HttpHermesConnectionClient(httpClient),
                nativeLogin = HermesNativeLogin(
                    exchanger = HttpHermesNativeAuthClient(httpClient),
                    awaitExchangeReady = {
                        HermesWindowFocus.state.first { it }
                    },
                ),
                closeResources = httpClient::close,
                tokenStore = EncryptedNativeTokenStore(context),
                refreshClient = HttpHermesNativeRefreshClient(httpClient),
                chatConnector = chatConnector,
                projectConnector = projectConnector,
                attachmentReader = ContentAttachmentByteReader(context),
                appForegroundStates = HermesAppForeground.states,
                notifications = AndroidTurnNotificationController(context),
            ) as T
        }
    }
}

object HermesAppForeground {
    private val mutableStates = MutableStateFlow(false)
    val states: StateFlow<Boolean> = mutableStates.asStateFlow()

    fun publish(foreground: Boolean) {
        mutableStates.value = foreground
    }
}

private fun JsonObject.assistantReasoningText(): String? =
    sequenceOf("reasoning", "reasoning_content", "reasoning_details")
        .mapNotNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull }
        .firstOrNull(String::isNotBlank)
        ?.take(HERMES_CHAT_MAX_MESSAGE_TEXT_CHARS)

private fun JsonObject.transcriptToolText(): String? {
    val explicitText = (this["content"] as? JsonPrimitive)?.contentOrNull
        ?: (this["text"] as? JsonPrimitive)?.contentOrNull
    if (!explicitText.isNullOrBlank()) return explicitText
    val name = (this["name"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    val context = (this["context"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    return listOfNotNull(name, context?.takeUnless { it == name })
        .joinToString(" · ")
        .takeIf(String::isNotEmpty)
        ?.take(HERMES_CHAT_MAX_MESSAGE_TEXT_CHARS)
}
