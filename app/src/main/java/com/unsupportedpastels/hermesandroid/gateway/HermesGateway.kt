package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.connection.HermesAuthProvider
import kotlinx.coroutines.flow.StateFlow

@JvmInline
value class RuntimeSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Runtime session ID must not be blank" }
    }
}

enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Recovering,
}

enum class RuntimeAccess {
    Observer,
    Controller,
}

enum class AuthenticationState {
    Unknown,
    NotRequired,
    SignInRequired,
    SigningIn,
    Authenticated,
}

data class ActiveRuntimeSession(
    val runtimeSessionId: RuntimeSessionId,
    val durableSessionId: DurableSessionId? = null,
    val title: String,
    val access: RuntimeAccess = RuntimeAccess.Observer,
)

enum class ChatMessageRole {
    User,
    Assistant,
    System,
    Tool,
}

data class ChatMessage(
    val role: ChatMessageRole,
    val text: String,
    val isStreaming: Boolean = false,
)

data class ChatSessionSnapshot(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val error: String? = null,
)

data class HermesGatewaySnapshot(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val authenticationState: AuthenticationState = AuthenticationState.Unknown,
    val serverVersion: String? = null,
    val nativeOAuthSupported: Boolean = false,
    val authProviders: List<HermesAuthProvider> = emptyList(),
    val connectionError: String? = null,
    val durableSessions: List<SessionSummary> = emptyList(),
    val activeRuntimes: List<ActiveRuntimeSession> = emptyList(),
    val chatSessions: Map<DurableSessionId, ChatSessionSnapshot> = emptyMap(),
)

interface HermesGateway {
    val snapshots: StateFlow<HermesGatewaySnapshot>

    suspend fun refresh(): HermesGatewaySnapshot
}
