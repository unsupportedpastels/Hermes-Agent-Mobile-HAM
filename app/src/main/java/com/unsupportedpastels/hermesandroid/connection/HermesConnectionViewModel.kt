package com.unsupportedpastels.hermesandroid.connection

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesChatConnector
import com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent
import com.unsupportedpastels.hermesandroid.gateway.HermesChatGateway
import com.unsupportedpastels.hermesandroid.gateway.HermesChatSession
import com.unsupportedpastels.hermesandroid.gateway.HermesChatTransportException
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.HERMES_CHAT_MAX_FRAME_BYTES
import com.unsupportedpastels.hermesandroid.gateway.KtorChatWebSocketFactory
import com.unsupportedpastels.hermesandroid.gateway.KtorWsTicketClient
import com.unsupportedpastels.hermesandroid.gateway.ResumedChatSession
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val TOKEN_REFRESH_SKEW_SECONDS = 30L
private const val MAX_CHAT_RECOVERIES_PER_OPERATION = 2

private data class ActiveTokenRecord(
    val origin: ServerOrigin,
    val generation: Long,
    val tokens: NativeTokenSet,
)

private class ChatRecoveryState(
    val operationGeneration: Long,
    var remaining: Int = MAX_CHAT_RECOVERIES_PER_OPERATION,
    var activeAttempt: ChatRecoveryAttempt? = null,
)

private class ChatRecoveryAttempt(
    val state: ChatRecoveryState,
)

class HermesConnectionViewModel(
    settingsStates: Flow<ServerSettingsState>,
    private val client: HermesConnectionClient,
    private val nativeLogin: NativeLogin? = null,
    private val closeResources: () -> Unit = {},
    private val tokenStore: NativeTokenStore? = null,
    private val refreshClient: NativeRefreshClient? = null,
    private val chatConnector: HermesChatConnector? = null,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) : ViewModel() {
    private val mutableSnapshots = MutableStateFlow(HermesGatewaySnapshot())
    val snapshots: StateFlow<HermesGatewaySnapshot> = mutableSnapshots.asStateFlow()

    private var activeOrigin: ServerOrigin? = null
    private var activeTokens: ActiveTokenRecord? = null
    private var generation = 0L
    private var connectionJob: Job? = null
    private var signInJob: Job? = null
    private var chatJob: Job? = null
    private var chatOperationGeneration = 0L
    private var eventJob: Job? = null
    private var activeChatSession: HermesChatSession? = null
    private var activeChatDurableId: DurableSessionId? = null
    private var activeRuntimeSessionId: RuntimeSessionId? = null
    private var chatRecoveryState: ChatRecoveryState? = null

    init {
        viewModelScope.launch {
            settingsStates.collect { settingsState ->
                val currentGeneration = ++generation
                connectionJob?.cancel()
                chatOperationGeneration += 1
                signInJob?.cancel()
                chatJob?.cancel()
                chatJob = null
                disconnectChat()
                chatRecoveryState = null
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
            val info = client.probe(serverOrigin)
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

            val stored = tokenStore?.load(serverOrigin)
            currentCoroutineContext().ensureActive()
            if (generation != currentGeneration || activeOrigin != serverOrigin) return
            val usableTokens = stored?.let { refreshIfNeeded(serverOrigin, it) }
            currentCoroutineContext().ensureActive()
            if (generation != currentGeneration || activeOrigin != serverOrigin) return
            if (usableTokens != null) {
                if (usableTokens != stored) {
                    tokenStore.save(serverOrigin, usableTokens)
                    currentCoroutineContext().ensureActive()
                    if (generation != currentGeneration || activeOrigin != serverOrigin) return
                }
                val authenticated = client.authenticate(serverOrigin, usableTokens.accessToken)
                currentCoroutineContext().ensureActive()
                if (generation != currentGeneration || activeOrigin != serverOrigin) return
                activeTokens = ActiveTokenRecord(serverOrigin, currentGeneration, usableTokens)
                mutableSnapshots.value = HermesGatewaySnapshot(
                    connectionState = ConnectionState.Connected,
                    authenticationState = AuthenticationState.Authenticated,
                    serverVersion = info.version,
                    nativeOAuthSupported = info.nativeOAuthSupported,
                    authProviders = info.providers,
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
        } catch (_: Exception) {
            if (generation != currentGeneration || activeOrigin != serverOrigin) return
            mutableSnapshots.value = HermesGatewaySnapshot(
                connectionState = ConnectionState.Disconnected,
                connectionError = "Could not reach Hermes Serve",
            )
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

    fun openSession(durableSessionId: DurableSessionId): Job {
        val operationGeneration = ++chatOperationGeneration
        chatJob?.cancel()
        clearTransientChatStates()
        val job = viewModelScope.launch {
            val origin = activeOrigin ?: return@launch
            val originGeneration = generation
            if (!isCurrentChatOperation(origin, originGeneration, operationGeneration)) return@launch
            disconnectChat()
            updateChat(durableSessionId) { it.copy(isLoading = true, error = null) }
            try {
                val accessToken = accessTokenForRequest(origin, originGeneration)
                if (!isCurrentChatOperation(origin, originGeneration, operationGeneration)) return@launch
                val messages = client.loadTranscript(origin, accessToken, durableSessionId)
                if (!isCurrentChatOperation(origin, originGeneration, operationGeneration)) return@launch
                updateChat(durableSessionId) {
                    it.copy(messages = messages, isLoading = false, error = null)
                }
                if (
                    accessToken != null &&
                    chatConnector != null &&
                    mutableSnapshots.value.authenticationState == AuthenticationState.Authenticated
                ) {
                    chatRecoveryState = ChatRecoveryState(operationGeneration)
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
                if (isCurrentChatOperation(origin, originGeneration, operationGeneration)) {
                    updateChat(durableSessionId) { it.copy(isLoading = false) }
                }
                throw cancelled
            } catch (_: NativeRefreshExpiredException) {
                if (isCurrentChatOperation(origin, originGeneration, operationGeneration)) {
                    disconnectChat()
                    publishSignInRequired()
                }
            } catch (error: Exception) {
                if (!isCurrentChatOperation(origin, originGeneration, operationGeneration)) return@launch
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
        chatJob = job
        return job
    }

    fun sendMessage(durableSessionId: DurableSessionId, rawText: String): Job {
        val text = rawText.trim()
        if (text.isEmpty()) return viewModelScope.launch { }
        val operationGeneration = ++chatOperationGeneration
        chatJob?.cancel()
        clearTransientChatStates()
        val job = viewModelScope.launch {
            val origin = activeOrigin ?: return@launch
            val originGeneration = generation
            if (!isCurrentChatOperation(origin, originGeneration, operationGeneration)) return@launch
            var promptStaged = false
            try {
                val accessToken = accessTokenForRequest(origin, originGeneration)
                    ?: throw HermesConnectionException("Sign in is required to send messages")
                chatRecoveryState = ChatRecoveryState(operationGeneration)
                val session = ensureLiveSession(
                    origin = origin,
                    originGeneration = originGeneration,
                    operationGeneration = operationGeneration,
                    accessToken = accessToken,
                    durableSessionId = durableSessionId,
                )
                val runtimeId = checkNotNull(activeRuntimeSessionId)
                if (!isCurrentChatOperation(origin, originGeneration, operationGeneration)) return@launch

                updateChat(durableSessionId) { current ->
                    current.copy(
                        messages = current.messages +
                            ChatMessage(ChatMessageRole.User, text) +
                            ChatMessage(ChatMessageRole.Assistant, "", isStreaming = true),
                        isLoading = false,
                        isSending = true,
                        error = null,
                    )
                }
                promptStaged = true
                yield()
                session.submitPrompt(runtimeId, text)
            } catch (cancelled: CancellationException) {
                if (isCurrentChatOperation(origin, originGeneration, operationGeneration)) {
                    clearSendingState(durableSessionId)
                }
                throw cancelled
            } catch (_: NativeRefreshExpiredException) {
                if (isCurrentChatOperation(origin, originGeneration, operationGeneration)) {
                    disconnectChat()
                    publishSignInRequired()
                }
            } catch (error: Exception) {
                if (!isCurrentChatOperation(origin, originGeneration, operationGeneration)) return@launch
                if (promptStaged && error is HermesChatTransportException) {
                    val recoveryAttempt = startChatRecovery(operationGeneration)
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
                    } else if (!isChatRecoveryInProgress(operationGeneration)) {
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
        chatJob = job
        return job
    }

    private suspend fun ensureLiveSession(
        origin: ServerOrigin,
        originGeneration: Long,
        operationGeneration: Long,
        accessToken: String,
        durableSessionId: DurableSessionId,
        closeWhenIdle: Boolean = false,
    ): HermesChatSession {
        val existing = activeChatSession
        if (activeChatDurableId == durableSessionId && existing != null) {
            val runtimeId = checkNotNull(activeRuntimeSessionId)
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
        disconnectChat()
        val connector = chatConnector
            ?: throw HermesConnectionException("Live chat is unavailable")
        if (!isCurrentChatOperation(origin, originGeneration, operationGeneration)) {
            throw CancellationException("Chat operation was replaced")
        }
        val session = connector.connect(origin, accessToken)
        try {
            val resumed = session.resume(durableSessionId, profile = "default")
            if (!isCurrentChatOperation(origin, originGeneration, operationGeneration)) {
                throw CancellationException("Chat operation was replaced")
            }
            applyResume(durableSessionId, resumed)
            if (closeWhenIdle && !resumed.running) {
                session.close()
                return session
            }
            activeChatSession = session
            activeChatDurableId = durableSessionId
            activeRuntimeSessionId = resumed.runtimeSessionId
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
            runCatching { session.close() }
            throw error
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
        eventJob?.cancel()
        eventJob = viewModelScope.launch {
            session.events.collect { event ->
                if (
                    !isCurrentChatOperation(origin, originGeneration, operationGeneration) ||
                    event.sessionId != runtimeSessionId ||
                    activeChatDurableId != durableSessionId
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
                    is HermesChatEvent.MessageComplete -> {
                        updateAssistant(durableSessionId, streaming = false) { current ->
                            event.text ?: current
                        }
                        val terminalError = when (event.status?.lowercase()) {
                            "error", "failed" -> "Hermes response failed"
                            "cancelled", "canceled" -> "Hermes response was cancelled"
                            else -> if (event.error.isNullOrBlank()) null else "Hermes response failed"
                        }
                        updateChat(durableSessionId) {
                            it.copy(isSending = false, error = terminalError)
                        }
                    }
                    is HermesChatEvent.Error -> {
                        updateAssistant(durableSessionId, streaming = false) { current -> current }
                        updateChat(durableSessionId) {
                            it.copy(isSending = false, error = event.message.take(160))
                        }
                    }
                }
            }
            if (
                isCurrentChatOperation(origin, originGeneration, operationGeneration) &&
                mutableSnapshots.value.chatSessions[durableSessionId]?.isSending == true
            ) {
                val recoveryAttempt = startChatRecovery(operationGeneration)
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
                } else if (!isChatRecoveryInProgress(operationGeneration)) {
                    clearSendingState(durableSessionId)
                    updateChat(durableSessionId) {
                        it.copy(error = "Connection lost while receiving response")
                    }
                }
            }
        }
    }

    private fun startChatRecovery(operationGeneration: Long): ChatRecoveryAttempt? {
        val state = chatRecoveryState
            ?.takeIf { it.operationGeneration == operationGeneration }
            ?: return null
        if (state.activeAttempt != null || state.remaining <= 0) return null
        state.remaining -= 1
        return ChatRecoveryAttempt(state).also { state.activeAttempt = it }
    }

    private fun finishChatRecovery(attempt: ChatRecoveryAttempt) {
        if (chatRecoveryState === attempt.state && attempt.state.activeAttempt === attempt) {
            attempt.state.activeAttempt = null
        }
    }

    private fun isChatRecoveryInProgress(operationGeneration: Long): Boolean =
        chatRecoveryState
            ?.takeIf { it.operationGeneration == operationGeneration }
            ?.activeAttempt != null

    private suspend fun recoverChat(
        durableSessionId: DurableSessionId,
        origin: ServerOrigin,
        originGeneration: Long,
        operationGeneration: Long,
        recoveryAttempt: ChatRecoveryAttempt,
    ) {
        if (!isCurrentChatOperation(origin, originGeneration, operationGeneration)) return
        val connector = chatConnector ?: return
        activeChatSession?.close()
        activeChatSession = null
        activeRuntimeSessionId = null

        for (backoffMillis in listOf(500L, 1_000L, 2_000L)) {
            delay(backoffMillis)
            if (!isCurrentChatOperation(origin, originGeneration, operationGeneration)) return
            var candidate: HermesChatSession? = null
            try {
                val token = accessTokenForRequest(origin, originGeneration)
                    ?: throw HermesConnectionException("Sign in is required to reconnect")
                if (!isCurrentChatOperation(origin, originGeneration, operationGeneration)) return
                candidate = connector.connect(origin, token)
                if (!isCurrentChatOperation(origin, originGeneration, operationGeneration)) {
                    candidate.close()
                    return
                }
                val resumed = candidate.resume(durableSessionId, profile = "default")
                if (!isCurrentChatOperation(origin, originGeneration, operationGeneration)) {
                    candidate.close()
                    return
                }
                applyResume(durableSessionId, resumed)
                if (resumed.running) {
                    activeChatSession = candidate
                    activeChatDurableId = durableSessionId
                    activeRuntimeSessionId = resumed.runtimeSessionId
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
                    val messages = client.loadTranscript(origin, token, durableSessionId)
                    if (!isCurrentChatOperation(origin, originGeneration, operationGeneration)) {
                        candidate.close()
                        return
                    }
                    updateChat(durableSessionId) {
                        it.copy(messages = messages, isSending = false, error = null)
                    }
                    candidate.close()
                }
                return
            } catch (cancelled: CancellationException) {
                candidate?.close()
                throw cancelled
            } catch (_: NativeRefreshExpiredException) {
                candidate?.close()
                if (isCurrentChatOperation(origin, originGeneration, operationGeneration)) {
                    disconnectChat()
                    publishSignInRequired()
                }
                return
            } catch (_: Exception) {
                candidate?.close()
            }
        }

        if (!isCurrentChatOperation(origin, originGeneration, operationGeneration)) return
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
        val text = row["content"]?.jsonPrimitive?.contentOrNull
            ?: row["text"]?.jsonPrimitive?.contentOrNull
            ?: return null
        return ChatMessage(role, text)
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

    private fun isCurrentChatOperation(
        origin: ServerOrigin,
        originGeneration: Long,
        operationGeneration: Long,
    ): Boolean =
        activeOrigin == origin &&
            generation == originGeneration &&
            chatOperationGeneration == operationGeneration

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

    private fun publishSignInRequired() {
        mutableSnapshots.value = mutableSnapshots.value.copy(
            connectionState = ConnectionState.Connected,
            authenticationState = AuthenticationState.SignInRequired,
            connectionError = null,
            durableSessions = emptyList(),
            chatSessions = emptyMap(),
        )
    }

    private suspend fun disconnectChat() {
        eventJob?.cancel()
        eventJob = null
        activeChatSession?.close()
        activeChatSession = null
        activeChatDurableId = null
        activeRuntimeSessionId = null
    }

    override fun onCleared() {
        signInJob?.cancel()
        chatJob?.cancel()
        eventJob?.cancel()
        viewModelScope.launch { activeChatSession?.close() }
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
                }
            }
            val connector = HermesChatConnector { origin, accessToken ->
                HermesChatGateway(
                    origin = origin,
                    accessToken = accessToken,
                    ticketClient = KtorWsTicketClient(httpClient),
                    socketFactory = KtorChatWebSocketFactory(httpClient),
                ).connect()
            }
            return HermesConnectionViewModel(
                settingsStates = settingsStates,
                client = HttpHermesConnectionClient(httpClient),
                nativeLogin = HermesNativeLogin(HttpHermesNativeAuthClient(httpClient)),
                closeResources = httpClient::close,
                tokenStore = EncryptedNativeTokenStore(context),
                refreshClient = HttpHermesNativeRefreshClient(httpClient),
                chatConnector = connector,
            ) as T
        }
    }
}
