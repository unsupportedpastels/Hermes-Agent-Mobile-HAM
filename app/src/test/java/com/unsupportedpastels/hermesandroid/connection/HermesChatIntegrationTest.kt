package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesChatConnector
import com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent
import com.unsupportedpastels.hermesandroid.gateway.HermesChatProtocolException
import com.unsupportedpastels.hermesandroid.gateway.HermesChatSession
import com.unsupportedpastels.hermesandroid.gateway.HermesChatTransportException
import com.unsupportedpastels.hermesandroid.gateway.InflightPrompt
import com.unsupportedpastels.hermesandroid.gateway.PromptSubmission
import com.unsupportedpastels.hermesandroid.gateway.ResumedChatSession
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import com.unsupportedpastels.hermesandroid.gateway.SlashCompletionItem
import com.unsupportedpastels.hermesandroid.gateway.SlashCompletionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HermesChatIntegrationTest {
    private val dispatcher = StandardTestDispatcher()
    private val origin = ServerOrigin.parse("https://hermes.example")
    private val durableId = DurableSessionId("durable-1")
    private val tokens = NativeTokenSet(
        accessToken = "opaque-access",
        refreshToken = "opaque-refresh",
        expiresAt = 2_000_000_000,
        provider = "nous",
        userId = "user-1",
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun storedAuthenticationIsRestoredAndSelectedTranscriptLoads() = runTest(dispatcher) {
        val client = ChatConnectionClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = MemoryTokenStore(tokens),
            nowEpochSeconds = { 1_900_000_000 },
        )

        advanceUntilIdle()
        assertEquals(AuthenticationState.Authenticated, viewModel.snapshots.value.authenticationState)

        viewModel.openSession(durableId)
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(listOf("Earlier question", "Earlier answer"), chat.messages.map { it.text })
        assertFalse(chat.isLoading)
        assertEquals("opaque-access", client.transcriptAccessToken)
    }

    @Test
    fun rejectedPersistedAuthenticationClearsTokensAndRequiresSignIn() = runTest(dispatcher) {
        val tokenStore = MemoryTokenStore(tokens)
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = RejectedAuthenticationClient(),
            tokenStore = tokenStore,
            nowEpochSeconds = { 1_900_000_000 },
        )

        advanceUntilIdle()

        assertEquals(ConnectionState.Connected, viewModel.snapshots.value.connectionState)
        assertEquals(AuthenticationState.SignInRequired, viewModel.snapshots.value.authenticationState)
        assertNull(tokenStore.load(origin))
    }

    @Test
    fun sendResumesDurableSessionAndReducesStreamedAssistantText() = runTest(dispatcher) {
        val session = StreamingChatSession()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { requestedOrigin, accessToken ->
                assertEquals(origin, requestedOrigin)
                assertEquals("opaque-access", accessToken)
                session
            },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "New question")
        runCurrent()
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(
            listOf(ChatMessageRole.User, ChatMessageRole.Assistant),
            chat.messages.takeLast(2).map { it.role },
        )
        assertEquals(listOf("New question", "Hello world"), chat.messages.takeLast(2).map { it.text })
        assertFalse(chat.isSending)
        assertEquals(durableId, session.resumedDurableId)
        assertEquals("New question", session.submittedText)
    }

    @Test
    fun protocolFailureAfterPromptStagingFinalizesPlaceholder() = runTest(dispatcher) {
        val session = ReconnectingChatSession(
            runtimeId = "runtime-protocol-error",
            running = false,
            inflightText = null,
            submitFailure = HermesChatProtocolException("invalid response"),
        )
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Question")
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertFalse(chat.isSending)
        assertFalse(chat.messages.any { it.isStreaming })
        assertTrue(chat.error != null)
    }

    @Test
    fun terminalErrorCompletionStopsStreamingAndShowsFailure() = runTest(dispatcher) {
        val session = TerminalEventChatSession { runtime ->
            HermesChatEvent.MessageComplete(
                sessionId = runtime,
                text = "partial response",
                status = "error",
                error = "provider detail",
            )
        }
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Question")
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertFalse(chat.isSending)
        assertFalse(chat.messages.last().isStreaming)
        assertEquals("Hermes response failed", chat.error)
    }

    @Test
    fun standaloneErrorEventStopsStreamingAssistant() = runTest(dispatcher) {
        val session = TerminalEventChatSession { runtime ->
            HermesChatEvent.Error(runtime, "temporary failure")
        }
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Question")
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertFalse(chat.isSending)
        assertFalse(chat.messages.last().isStreaming)
        assertEquals("temporary failure", chat.error)
    }

    @Test
    fun slashCompletionPublishesItemsForSlashComposerText() = runTest(dispatcher) {
        val session = CompletableSlashChatSession(
            result = SlashCompletionResult(
                items = listOf(SlashCompletionItem("goal", "/goal", "Set a standing goal")),
                replaceFrom = 1,
            ),
        )
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()

        viewModel.updateSlashCompletion(durableId, "/go")
        advanceUntilIdle()

        assertEquals(listOf("/go"), session.completionRequests)
        val state = viewModel.slashCompletions.value[durableId]
        assertEquals("/go", state?.composerText)
        assertEquals(1, state?.replaceFrom)
        assertEquals(listOf("/goal"), state?.items?.map { it.display })
    }

    @Test
    fun slashCompletionIgnoresNonSlashComposerText() = runTest(dispatcher) {
        val session = CompletableSlashChatSession(
            result = SlashCompletionResult(emptyList(), 0),
        )
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()

        viewModel.updateSlashCompletion(durableId, "/home/user/file")
        viewModel.updateSlashCompletion(durableId, "hello")
        advanceUntilIdle()

        assertTrue(session.completionRequests.isEmpty())
        assertNull(viewModel.slashCompletions.value[durableId])
    }

    @Test
    fun staleSlashCompletionResultIsDiscardedAfterTextChanges() = runTest(dispatcher) {
        val session = CompletableSlashChatSession(
            result = SlashCompletionResult(
                items = listOf(SlashCompletionItem("goal", "/goal", null)),
                replaceFrom = 1,
            ),
        )
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()

        viewModel.updateSlashCompletion(durableId, "/go")
        runCurrent()
        // Composer moves on before the response lands; the stale result must not publish.
        viewModel.updateSlashCompletion(durableId, "/goa")
        advanceUntilIdle()

        val state = viewModel.slashCompletions.value[durableId]
        assertTrue(state == null || state.composerText == "/goa")
    }

    @Test
    fun switchingSessionsClearsSlashCompletion() = runTest(dispatcher) {
        val session = CompletableSlashChatSession(
            result = SlashCompletionResult(
                items = listOf(SlashCompletionItem("goal", "/goal", null)),
                replaceFrom = 1,
            ),
        )
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()
        viewModel.updateSlashCompletion(durableId, "/go")
        advanceUntilIdle()
        assertTrue(viewModel.slashCompletions.value.containsKey(durableId))

        viewModel.clearSlashCompletion(durableId)
        advanceUntilIdle()
        assertNull(viewModel.slashCompletions.value[durableId])
    }

    private fun chatViewModel(session: HermesChatSession) = HermesConnectionViewModel(
        settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
        client = ChatConnectionClient(),
        tokenStore = MemoryTokenStore(tokens),
        chatConnector = HermesChatConnector { _, _ -> session },
        nowEpochSeconds = { 1_900_000_000 },
    )

    @Test
    fun cancellingResumeClosesUnpublishedSocketSession() = runTest(dispatcher) {
        val session = BlockingResumeChatSession()
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Question")
        runCurrent()
        assertTrue(session.resumeStarted.isCompleted)

        viewModel.openSession(durableId)
        advanceUntilIdle()

        assertTrue(session.closed)
    }

    @Test
    fun replacingStagedSendFinalizesTransientUiState() = runTest(dispatcher) {
        val session = BlockingSubmitChatSession()
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Question")
        runCurrent()
        assertTrue(session.submitStarted.isCompleted)
        assertTrue(viewModel.snapshots.value.chatSessions.getValue(durableId).isSending)

        viewModel.openSession(durableId)
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertFalse(chat.isSending)
        assertFalse(chat.messages.any { it.isStreaming })
    }

    @Test
    fun reconnectReplacesLocalPartialWithInflightSnapshot() = runTest(dispatcher) {
        val first = ReconnectingChatSession(
            runtimeId = "runtime-1",
            running = false,
            inflightText = null,
            submitFailure = HermesChatTransportException("socket closed before acknowledgement"),
            onSubmit = { channel, runtime ->
                channel.trySend(HermesChatEvent.MessageDelta(runtime, "stale partial"))
                channel.close()
            },
        )
        val second = ReconnectingChatSession(
            runtimeId = "runtime-2",
            running = true,
            inflightText = "authoritative snapshot",
            inflightUser = "Reconnect this turn",
            resumeMessages = listOf(
                buildJsonObject {
                    put("role", "assistant")
                    put("content", "Earlier answer")
                },
            ),
            onResume = { channel, runtime ->
                channel.trySend(HermesChatEvent.MessageDelta(runtime, " plus delta"))
                channel.trySend(
                    HermesChatEvent.MessageComplete(
                        runtime,
                        "authoritative snapshot plus delta",
                        "done",
                    ),
                )
                channel.close()
            },
        )
        val sessions = ArrayDeque<HermesChatSession>().apply {
            add(first)
            add(second)
        }
        var connections = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ ->
                connections += 1
                if (connections == 2) {
                    throw HermesChatTransportException("network not restored yet")
                }
                sessions.removeFirst()
            },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Reconnect this turn")
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(3, connections)
        assertEquals(
            listOf("Reconnect this turn", "authoritative snapshot plus delta"),
            chat.messages.takeLast(2).map { it.text },
        )
        assertEquals(
            1,
            chat.messages.count {
                it.role == ChatMessageRole.Assistant &&
                    it.text == "authoritative snapshot plus delta"
            },
        )
        assertFalse(chat.isSending)
    }

    @Test
    fun newPromptCanRecoverAfterPriorSuccessfulReconnect() = runTest(dispatcher) {
        val sessions = ArrayDeque<HermesChatSession>().apply {
            add(
                ReconnectingChatSession(
                    runtimeId = "runtime-first",
                    running = false,
                    inflightText = null,
                    submitFailure = HermesChatTransportException("first disconnect"),
                ),
            )
            add(
                ReconnectingChatSession(
                    runtimeId = "runtime-second",
                    running = false,
                    inflightText = null,
                ),
            )
            add(
                ReconnectingChatSession(
                    runtimeId = "runtime-third",
                    running = false,
                    inflightText = null,
                    submitFailure = HermesChatTransportException("second disconnect"),
                ),
            )
            add(
                ReconnectingChatSession(
                    runtimeId = "runtime-fourth",
                    running = false,
                    inflightText = null,
                ),
            )
        }
        var connections = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ ->
                connections += 1
                sessions.removeFirst()
            },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "First turn")
        advanceUntilIdle()
        assertEquals(2, connections)

        viewModel.sendMessage(durableId, "Second turn")
        advanceUntilIdle()
        assertEquals(4, connections)
        assertFalse(viewModel.snapshots.value.chatSessions.getValue(durableId).isSending)
    }

    @Test
    fun openingSessionResumesAndReconcilesInflightPrompt() = runTest(dispatcher) {
        val session = ReconnectingChatSession(
            runtimeId = "runtime-open-resume",
            running = true,
            inflightText = "partial answer",
            inflightUser = "accepted question",
            resumeMessages = listOf(
                buildJsonObject {
                    put("role", "assistant")
                    put("content", "prior answer")
                },
            ),
        )
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(
            listOf("accepted question", "partial answer"),
            chat.messages.takeLast(2).map { it.text },
        )
        assertTrue(chat.messages.last().isStreaming)
    }

    @Test
    fun staleRecoveryCleanupCannotUnlockReplacementRecovery() = runTest(dispatcher) {
        val initial = ControllableFailingChatSession()
        val staleRecovery = NonCooperativeResumeChatSession("runtime-stale-recovery")
        val replacementInitial = ControllableFailingChatSession("runtime-replacement")
        val currentRecovery = NonCooperativeResumeChatSession("runtime-current-recovery")
        val duplicate = ReconnectingChatSession(
            runtimeId = "runtime-duplicate",
            running = false,
            inflightText = null,
        )
        val sessions = ArrayDeque<HermesChatSession>().apply {
            add(initial)
            add(staleRecovery)
            add(replacementInitial)
            add(currentRecovery)
            add(duplicate)
        }
        var connections = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ ->
                connections += 1
                sessions.removeFirst()
            },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "First operation")
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertTrue(staleRecovery.resumeStarted.isCompleted)

        viewModel.sendMessage(durableId, "Replacement operation")
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertTrue(currentRecovery.resumeStarted.isCompleted)
        assertTrue(viewModel.snapshots.value.chatSessions.getValue(durableId).isSending)

        staleRecovery.releaseResume.complete(Unit)
        runCurrent()
        assertTrue(staleRecovery.closed)
        assertTrue(viewModel.snapshots.value.chatSessions.getValue(durableId).isSending)
        replacementInitial.closeEvents()
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        assertEquals(4, connections)
        currentRecovery.releaseResume.complete(Unit)
        runCurrent()
    }

    @Test
    fun recoveredSessionFailureUsesSecondBoundedRecovery() = runTest(dispatcher) {
        val sessions = ArrayDeque<HermesChatSession>().apply {
            add(
                ReconnectingChatSession(
                    runtimeId = "runtime-initial-failure",
                    running = false,
                    inflightText = null,
                    submitFailure = HermesChatTransportException("initial disconnect"),
                ),
            )
            add(
                ReconnectingChatSession(
                    runtimeId = "runtime-recovered-failure",
                    running = true,
                    inflightText = "authoritative partial",
                    onResume = { channel, _ -> channel.close() },
                ),
            )
            add(
                ReconnectingChatSession(
                    runtimeId = "runtime-final-recovery",
                    running = false,
                    inflightText = null,
                ),
            )
        }
        var connections = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ ->
                connections += 1
                sessions.removeFirst()
            },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Recover twice")
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(3, connections)
        assertFalse(chat.isSending)
        assertFalse(chat.messages.any { it.isStreaming })
    }

    @Test
    fun openingCompletedSessionClosesIdleSocketAfterReconciliation() = runTest(dispatcher) {
        val session = ReconnectingChatSession(
            runtimeId = "runtime-open-idle",
            running = false,
            inflightText = null,
        )
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()

        assertTrue(session.closed)
        assertFalse(viewModel.snapshots.value.chatSessions.getValue(durableId).isLoading)
    }

    @Test
    fun accessOnlyExpiryWhileOpeningSessionReturnsToSignIn() = runTest(dispatcher) {
        var now = 1_900_000_000L
        val accessOnly = tokens.copy(refreshToken = "")
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(accessOnly),
            nowEpochSeconds = { now },
        )
        advanceUntilIdle()
        assertEquals(AuthenticationState.Authenticated, viewModel.snapshots.value.authenticationState)

        now = 2_000_000_000L
        viewModel.openSession(durableId)
        advanceUntilIdle()

        assertEquals(AuthenticationState.SignInRequired, viewModel.snapshots.value.authenticationState)
        assertTrue(viewModel.snapshots.value.chatSessions.isEmpty())
    }

    @Test
    fun refreshExpiryWhileOpeningSessionReturnsToSignIn() = runTest(dispatcher) {
        var now = 1_900_000_000L
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            refreshClient = object : NativeRefreshClient {
                override suspend fun refresh(
                    serverOrigin: ServerOrigin,
                    refreshToken: String,
                    provider: String,
                ): NativeTokenSet = throw NativeRefreshExpiredException()
            },
            nowEpochSeconds = { now },
        )
        advanceUntilIdle()
        assertEquals(AuthenticationState.Authenticated, viewModel.snapshots.value.authenticationState)

        now = 2_000_000_000L
        viewModel.openSession(durableId)
        advanceUntilIdle()

        assertEquals(AuthenticationState.SignInRequired, viewModel.snapshots.value.authenticationState)
        assertTrue(viewModel.snapshots.value.durableSessions.isEmpty())
        assertTrue(viewModel.snapshots.value.chatSessions.isEmpty())
    }

    @Test
    fun staleSameOriginRefreshCannotOverwriteNewGenerationCredentials() = runTest(dispatcher) {
        val firstTokens = tokens.copy(expiresAt = 1_900_000_100L)
        val newerTokens = tokens.copy(
            accessToken = "new-generation-access",
            refreshToken = "new-generation-refresh",
            expiresAt = 2_100_000_000L,
        )
        val staleRefreshedTokens = firstTokens.copy(
            accessToken = "stale-refreshed-access",
            expiresAt = 2_100_000_000L,
        )
        val stored = mutableMapOf(origin to firstTokens)
        val tokenStore = object : NativeTokenStore {
            override suspend fun load(serverOrigin: ServerOrigin) = stored[serverOrigin]
            override suspend fun save(serverOrigin: ServerOrigin, tokens: NativeTokenSet) {
                stored[serverOrigin] = tokens
            }
            override suspend fun clear(serverOrigin: ServerOrigin) {
                stored.remove(serverOrigin)
            }
        }
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val refreshClient = object : NativeRefreshClient {
            override suspend fun refresh(
                serverOrigin: ServerOrigin,
                refreshToken: String,
                provider: String,
            ): NativeTokenSet {
                refreshStarted.complete(Unit)
                withContext(NonCancellable) { releaseRefresh.await() }
                return staleRefreshedTokens
            }
        }
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        var now = 1_900_000_000L
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = ChatConnectionClient(),
            tokenStore = tokenStore,
            refreshClient = refreshClient,
            nowEpochSeconds = { now },
        )
        advanceUntilIdle()

        now = 2_000_000_000L
        viewModel.openSession(durableId)
        runCurrent()
        assertTrue(refreshStarted.isCompleted)

        settings.value = ServerSettingsState.Loading
        runCurrent()
        stored[origin] = newerTokens
        settings.value = ServerSettingsState.Ready(origin)
        runCurrent()
        releaseRefresh.complete(Unit)
        advanceUntilIdle()

        assertEquals(newerTokens, stored[origin])
    }

    @Test
    fun staleOriginRefreshCannotReplaceCurrentOriginCredentials() = runTest(dispatcher) {
        val firstOrigin = origin
        val secondOrigin = ServerOrigin.parse("https://second.example")
        val firstTokens = tokens.copy(expiresAt = 1_900_000_100L)
        val secondTokens = tokens.copy(
            accessToken = "second-origin-access",
            refreshToken = "second-origin-refresh",
            expiresAt = 2_100_000_000L,
        )
        val refreshedFirstTokens = firstTokens.copy(
            accessToken = "refreshed-first-access",
            expiresAt = 2_100_000_000L,
        )
        val stored = mutableMapOf(firstOrigin to firstTokens, secondOrigin to secondTokens)
        val tokenStore = object : NativeTokenStore {
            override suspend fun load(serverOrigin: ServerOrigin) = stored[serverOrigin]
            override suspend fun save(serverOrigin: ServerOrigin, tokens: NativeTokenSet) {
                stored[serverOrigin] = tokens
            }
            override suspend fun clear(serverOrigin: ServerOrigin) {
                stored.remove(serverOrigin)
            }
        }
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val refreshClient = object : NativeRefreshClient {
            override suspend fun refresh(
                serverOrigin: ServerOrigin,
                refreshToken: String,
                provider: String,
            ): NativeTokenSet {
                assertEquals(firstOrigin, serverOrigin)
                refreshStarted.complete(Unit)
                withContext(NonCancellable) { releaseRefresh.await() }
                return refreshedFirstTokens
            }
        }
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(firstOrigin))
        var now = 1_900_000_000L
        var connectorToken: String? = null
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = ChatConnectionClient(),
            tokenStore = tokenStore,
            refreshClient = refreshClient,
            chatConnector = HermesChatConnector { _, accessToken ->
                connectorToken = accessToken
                StreamingChatSession()
            },
            nowEpochSeconds = { now },
        )
        advanceUntilIdle()

        now = 2_000_000_000L
        viewModel.openSession(durableId)
        runCurrent()
        assertTrue(refreshStarted.isCompleted)

        settings.value = ServerSettingsState.Ready(secondOrigin)
        runCurrent()
        releaseRefresh.complete(Unit)
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Current origin prompt")
        advanceUntilIdle()

        assertEquals(secondTokens.accessToken, connectorToken)
    }

    @Test
    fun nonCooperativeOldOriginAuthenticationCannotPublishStaleSessions() = runTest(dispatcher) {
        val firstOrigin = origin
        val secondOrigin = ServerOrigin.parse("https://second.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(firstOrigin))
        val client = NonCooperativeAuthenticationClient(firstOrigin)
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = MemoryTokenStore(tokens),
            nowEpochSeconds = { 1_900_000_000 },
        )
        runCurrent()
        assertTrue(client.firstAuthenticationStarted.isCompleted)

        settings.value = ServerSettingsState.Ready(secondOrigin)
        runCurrent()
        client.releaseFirstAuthentication.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf("second-session"),
            viewModel.snapshots.value.durableSessions.map { it.id.value },
        )
    }

    @Test
    fun originChangeCancelsTranscriptAndClearsOriginScopedChatState() = runTest(dispatcher) {
        val firstOrigin = origin
        val secondOrigin = ServerOrigin.parse("https://second.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(firstOrigin))
        val client = BlockingTranscriptClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = MemoryTokenStore(tokens),
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.openSession(durableId)
        runCurrent()
        assertTrue(viewModel.snapshots.value.chatSessions.getValue(durableId).isLoading)

        settings.value = ServerSettingsState.Ready(secondOrigin)
        advanceUntilIdle()

        assertTrue(client.transcriptCancelled)
        assertTrue(viewModel.snapshots.value.chatSessions.isEmpty())
        assertEquals(AuthenticationState.Authenticated, viewModel.snapshots.value.authenticationState)
    }

    @Test
    fun replacingTranscriptLoadClearsCancelledSessionLoadingState() = runTest(dispatcher) {
        val client = BlockingTranscriptClient()
        val secondDurableId = DurableSessionId("durable-2")
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = MemoryTokenStore(tokens),
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.openSession(durableId)
        runCurrent()
        viewModel.openSession(secondDurableId)
        runCurrent()

        assertFalse(viewModel.snapshots.value.chatSessions.getValue(durableId).isLoading)
    }
}

private class RejectedAuthenticationClient : HermesConnectionClient {
    override suspend fun probe(serverOrigin: ServerOrigin) = HermesConnectionInfo(
        version = "0.20.0",
        authRequired = true,
        nativeOAuthSupported = true,
        providers = listOf(HermesAuthProvider(name = "nous")),
    )

    override suspend fun authenticate(
        serverOrigin: ServerOrigin,
        accessToken: String,
    ): AuthenticatedHermesConnection = throw HermesAuthenticationRejectedException(
        "Hermes authentication returned HTTP 401",
    )
}

private class MemoryTokenStore(initial: NativeTokenSet?) : NativeTokenStore {
    private var value = initial
    override suspend fun load(serverOrigin: ServerOrigin): NativeTokenSet? = value
    override suspend fun save(serverOrigin: ServerOrigin, tokens: NativeTokenSet) { value = tokens }
    override suspend fun clear(serverOrigin: ServerOrigin) { value = null }
}

private class ChatConnectionClient : HermesConnectionClient {
    private val durableId = DurableSessionId("durable-1")
    var transcriptAccessToken: String? = null

    override suspend fun probe(serverOrigin: ServerOrigin) = HermesConnectionInfo(
        version = "0.20.0",
        authRequired = true,
        nativeOAuthSupported = true,
        providers = listOf(HermesAuthProvider("nous")),
    )

    override suspend fun authenticate(
        serverOrigin: ServerOrigin,
        accessToken: String,
    ) = AuthenticatedHermesConnection(
        userId = "user-1",
        sessions = listOf(SessionSummary(durableId, "Test session")),
    )

    override suspend fun loadTranscript(
        serverOrigin: ServerOrigin,
        accessToken: String?,
        durableSessionId: DurableSessionId,
    ): List<com.unsupportedpastels.hermesandroid.gateway.ChatMessage> {
        transcriptAccessToken = accessToken
        return listOf(
            com.unsupportedpastels.hermesandroid.gateway.ChatMessage(ChatMessageRole.User, "Earlier question"),
            com.unsupportedpastels.hermesandroid.gateway.ChatMessage(ChatMessageRole.Assistant, "Earlier answer"),
        )
    }
}

private class NonCooperativeAuthenticationClient(
    private val firstOrigin: ServerOrigin,
) : HermesConnectionClient {
    val firstAuthenticationStarted = CompletableDeferred<Unit>()
    val releaseFirstAuthentication = CompletableDeferred<Unit>()

    override suspend fun probe(serverOrigin: ServerOrigin) = HermesConnectionInfo(
        version = "0.20.0",
        authRequired = true,
        nativeOAuthSupported = true,
        providers = listOf(HermesAuthProvider("nous")),
    )

    override suspend fun authenticate(
        serverOrigin: ServerOrigin,
        accessToken: String,
    ): AuthenticatedHermesConnection {
        if (serverOrigin == firstOrigin) {
            withContext(NonCancellable) {
                firstAuthenticationStarted.complete(Unit)
                releaseFirstAuthentication.await()
            }
            return AuthenticatedHermesConnection(
                userId = "first-user",
                sessions = listOf(SessionSummary(DurableSessionId("first-session"), "First")),
            )
        }
        return AuthenticatedHermesConnection(
            userId = "second-user",
            sessions = listOf(SessionSummary(DurableSessionId("second-session"), "Second")),
        )
    }

    override suspend fun loadTranscript(
        serverOrigin: ServerOrigin,
        accessToken: String?,
        durableSessionId: DurableSessionId,
    ) = emptyList<com.unsupportedpastels.hermesandroid.gateway.ChatMessage>()
}

private class BlockingTranscriptClient : HermesConnectionClient {
    var transcriptCancelled = false
    private val transcript = CompletableDeferred<List<com.unsupportedpastels.hermesandroid.gateway.ChatMessage>>()

    override suspend fun probe(serverOrigin: ServerOrigin) = HermesConnectionInfo(
        version = "0.20.0",
        authRequired = true,
        nativeOAuthSupported = true,
        providers = listOf(HermesAuthProvider("nous")),
    )

    override suspend fun authenticate(
        serverOrigin: ServerOrigin,
        accessToken: String,
    ) = AuthenticatedHermesConnection(
        userId = "user-1",
        sessions = listOf(SessionSummary(DurableSessionId("durable-1"), "Test session")),
    )

    override suspend fun loadTranscript(
        serverOrigin: ServerOrigin,
        accessToken: String?,
        durableSessionId: DurableSessionId,
    ): List<com.unsupportedpastels.hermesandroid.gateway.ChatMessage> = try {
        transcript.await()
    } catch (cancelled: CancellationException) {
        transcriptCancelled = true
        throw cancelled
    }
}

private class ControllableFailingChatSession(
    runtimeId: String = "runtime-controllable",
) : HermesChatSession {
    private val runtimeSessionId = RuntimeSessionId(runtimeId)
    private val channel = Channel<HermesChatEvent>(Channel.UNLIMITED)
    override val events: Flow<HermesChatEvent> = channel.receiveAsFlow()

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ) = ResumedChatSession(
        runtimeSessionId = runtimeSessionId,
        durableSessionId = durableSessionId,
        resumed = true,
        messages = emptyList(),
        running = false,
        inflight = null,
    )

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission = throw HermesChatTransportException("transport failed")

    fun closeEvents() = channel.close()
    override suspend fun close() = Unit
}

private class NonCooperativeResumeChatSession(
    private val runtimeId: String,
) : HermesChatSession {
    val resumeStarted = CompletableDeferred<Unit>()
    val releaseResume = CompletableDeferred<Unit>()
    var closed = false
    override val events: Flow<HermesChatEvent> = MutableSharedFlow()

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession {
        resumeStarted.complete(Unit)
        withContext(NonCancellable) { releaseResume.await() }
        return ResumedChatSession(
            runtimeSessionId = RuntimeSessionId(runtimeId),
            durableSessionId = durableSessionId,
            resumed = true,
            messages = emptyList(),
            running = true,
            inflight = InflightPrompt("accepted prompt", "partial response", true),
        )
    }

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ) = PromptSubmission("streaming")

    override suspend fun close() {
        closed = true
    }
}

private class BlockingResumeChatSession : HermesChatSession {
    val resumeStarted = CompletableDeferred<Unit>()
    var closed = false
    override val events: Flow<HermesChatEvent> = MutableSharedFlow()

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession {
        resumeStarted.complete(Unit)
        awaitCancellation()
    }

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ) = PromptSubmission("streaming")

    override suspend fun close() {
        closed = true
    }
}

private class BlockingSubmitChatSession : HermesChatSession {
    val submitStarted = CompletableDeferred<Unit>()
    override val events: Flow<HermesChatEvent> = MutableSharedFlow()

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ) = ResumedChatSession(
        runtimeSessionId = RuntimeSessionId("runtime-blocking-submit"),
        durableSessionId = durableSessionId,
        resumed = true,
        messages = emptyList(),
        running = false,
        inflight = null,
    )

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission {
        submitStarted.complete(Unit)
        awaitCancellation()
    }

    override suspend fun close() = Unit
}

private class TerminalEventChatSession(
    private val terminalEvent: (RuntimeSessionId) -> HermesChatEvent,
) : HermesChatSession {
    private val mutableEvents = MutableSharedFlow<HermesChatEvent>(extraBufferCapacity = 8)
    override val events: Flow<HermesChatEvent> = mutableEvents

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ) = ResumedChatSession(
        runtimeSessionId = RuntimeSessionId("runtime-error"),
        durableSessionId = durableSessionId,
        resumed = true,
        messages = emptyList(),
        running = false,
        inflight = null,
    )

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission {
        mutableEvents.emit(HermesChatEvent.MessageStart(runtimeSessionId, null))
        mutableEvents.emit(HermesChatEvent.MessageDelta(runtimeSessionId, "partial"))
        mutableEvents.emit(terminalEvent(runtimeSessionId))
        return PromptSubmission("streaming")
    }

    override suspend fun close() = Unit
}

private class CompletableSlashChatSession(
    private val result: SlashCompletionResult,
) : HermesChatSession {
    private val channel = Channel<HermesChatEvent>(Channel.UNLIMITED)
    override val events: Flow<HermesChatEvent> = channel.receiveAsFlow()
    val completionRequests = mutableListOf<String>()

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ) = ResumedChatSession(
        runtimeSessionId = RuntimeSessionId("runtime-slash"),
        durableSessionId = durableSessionId,
        resumed = true,
        messages = emptyList(),
        running = true,
        inflight = null,
    )

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ) = PromptSubmission("streaming")

    override suspend fun completeSlash(text: String): SlashCompletionResult {
        completionRequests += text
        return result
    }

    override suspend fun close() = Unit
}

private class StreamingChatSession : HermesChatSession {
    private val mutableEvents = MutableSharedFlow<HermesChatEvent>(extraBufferCapacity = 8)
    override val events: Flow<HermesChatEvent> = mutableEvents
    var resumedDurableId: DurableSessionId? = null
    var submittedText: String? = null

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession {
        resumedDurableId = durableSessionId
        return ResumedChatSession(
            runtimeSessionId = RuntimeSessionId("runtime-1"),
            durableSessionId = durableSessionId,
            resumed = true,
            messages = emptyList(),
            running = false,
            inflight = null as InflightPrompt?,
        )
    }

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission {
        submittedText = text
        mutableEvents.emit(HermesChatEvent.MessageStart(runtimeSessionId, null))
        mutableEvents.emit(HermesChatEvent.MessageDelta(runtimeSessionId, "Hello "))
        mutableEvents.emit(HermesChatEvent.MessageDelta(runtimeSessionId, "world"))
        mutableEvents.emit(HermesChatEvent.MessageComplete(runtimeSessionId, "Hello world", "done"))
        return PromptSubmission("streaming")
    }

    override suspend fun close() = Unit
}

private class ReconnectingChatSession(
    runtimeId: String,
    private val running: Boolean,
    private val inflightText: String?,
    private val inflightUser: String? = null,
    private val resumeMessages: List<JsonObject> = emptyList(),
    private val submitFailure: Exception? = null,
    private val onResume: (Channel<HermesChatEvent>, RuntimeSessionId) -> Unit = { _, _ -> },
    private val onSubmit: (Channel<HermesChatEvent>, RuntimeSessionId) -> Unit = { _, _ -> },
) : HermesChatSession {
    private val runtimeSessionId = RuntimeSessionId(runtimeId)
    private val channel = Channel<HermesChatEvent>(Channel.UNLIMITED)
    var closed = false
    override val events: Flow<HermesChatEvent> = channel.receiveAsFlow()

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession {
        onResume(channel, runtimeSessionId)
        return ResumedChatSession(
            runtimeSessionId = runtimeSessionId,
            durableSessionId = durableSessionId,
            resumed = true,
            messages = resumeMessages,
            running = running,
            inflight = if (inflightUser != null || inflightText != null) {
                InflightPrompt(inflightUser, inflightText, true)
            } else {
                null
            },
        )
    }

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission {
        onSubmit(channel, runtimeSessionId)
        submitFailure?.let { throw it }
        return PromptSubmission("streaming")
    }

    override suspend fun close() {
        closed = true
    }
}
