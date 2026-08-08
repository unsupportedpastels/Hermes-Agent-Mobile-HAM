package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
private data class HermesStatusResponse(
    val version: String? = null,
    @SerialName("auth_required") val authRequired: Boolean,
    @SerialName("auth_flows") val authFlows: List<String> = emptyList(),
)

@Serializable
data class HermesAuthProvider(
    val name: String,
    @SerialName("display_name") val displayName: String = name,
    @SerialName("supports_password") val supportsPassword: Boolean = false,
)

@Serializable
private data class HermesAuthProvidersResponse(
    val providers: List<HermesAuthProvider>,
)

@Serializable
private data class HermesAuthenticatedUser(
    @SerialName("user_id") val userId: String = "",
)

@Serializable
private data class HermesSessionRow(
    @SerialName("session_key") val sessionKey: String? = null,
    val id: String? = null,
    val title: String? = null,
)

@Serializable
private data class HermesSessionsResponse(
    val sessions: List<HermesSessionRow>,
)

@Serializable
private data class HermesTranscriptResponse(
    val messages: List<JsonObject> = emptyList(),
    val data: List<JsonObject> = emptyList(),
)

data class HermesConnectionInfo(
    val version: String?,
    val authRequired: Boolean,
    val nativeOAuthSupported: Boolean,
    val providers: List<HermesAuthProvider>,
    val sessions: List<SessionSummary> = emptyList(),
)

data class AuthenticatedHermesConnection(
    val userId: String,
    val sessions: List<SessionSummary>,
)

open class HermesConnectionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class HermesAuthenticationRejectedException(
    message: String,
) : HermesConnectionException(message)

private const val MAX_RESPONSE_BODY_BYTES = 64 * 1024
private const val MAX_TRANSCRIPT_BODY_BYTES = 1024 * 1024
private const val MAX_DURABLE_SESSIONS = 20

internal fun HttpClientConfig<*>.configureHermesHttpClient() {
    followRedirects = false
}

internal suspend fun HttpResponse.readBodyTextBounded(
    maxBytes: Int = MAX_RESPONSE_BODY_BYTES,
): String {
    require(maxBytes in 1..MAX_TRANSCRIPT_BODY_BYTES)
    val channel = bodyAsChannel()
    return try {
        val source = channel.readRemaining(maxBytes + 1L)
        try {
            val bytes = ByteArray(maxBytes + 1)
            var count = 0
            while (!source.exhausted()) {
                val read = source.readAtMostTo(bytes, count, bytes.size)
                if (read <= 0) break
                count += read
                if (count > maxBytes) {
                    throw HermesConnectionException("Hermes response body was too large")
                }
            }
            if (count > maxBytes) {
                throw HermesConnectionException("Hermes response body was too large")
            }
            String(bytes, 0, count, Charsets.UTF_8)
        } finally {
            source.close()
        }
    } finally {
        channel.cancel(null)
    }
}

interface HermesConnectionClient {
    suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo

    suspend fun authenticate(
        serverOrigin: ServerOrigin,
        accessToken: String,
    ): AuthenticatedHermesConnection = throw UnsupportedOperationException()

    suspend fun loadTranscript(
        serverOrigin: ServerOrigin,
        accessToken: String?,
        durableSessionId: DurableSessionId,
    ): List<ChatMessage> = throw UnsupportedOperationException()
}

class HttpHermesConnectionClient(
    private val client: HttpClient,
) : HermesConnectionClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo = try {
        val statusResponse = client.get("${serverOrigin.value}/api/status")
        if (!statusResponse.status.isSuccess()) {
            statusResponse.readBodyTextBounded()
            throw HermesConnectionException(
                "Hermes status returned HTTP ${statusResponse.status.value}",
            )
        }
        val status = json.decodeFromString<HermesStatusResponse>(
            statusResponse.readBodyTextBounded(),
        )
        val providers = if (status.authRequired) {
            val providersResponse = client.get("${serverOrigin.value}/api/auth/providers")
            if (!providersResponse.status.isSuccess()) {
                providersResponse.readBodyTextBounded()
                throw HermesConnectionException(
                    "Hermes provider discovery returned HTTP ${providersResponse.status.value}",
                )
            }
            json.decodeFromString<HermesAuthProvidersResponse>(
                providersResponse.readBodyTextBounded(),
            ).providers
        } else {
            emptyList()
        }
        val sessions = if (status.authRequired) {
            emptyList()
        } else {
            loadSessions(serverOrigin, accessToken = null)
        }
        HermesConnectionInfo(
            version = status.version,
            authRequired = status.authRequired,
            nativeOAuthSupported = "native_pkce" in status.authFlows,
            providers = providers,
            sessions = sessions,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException("Could not connect to Hermes Serve", error)
    }

    override suspend fun authenticate(
        serverOrigin: ServerOrigin,
        accessToken: String,
    ): AuthenticatedHermesConnection = try {
        val meResponse = client.get("${serverOrigin.value}/api/auth/me") {
            bearerAuth(accessToken)
        }
        if (!meResponse.status.isSuccess()) {
            meResponse.readBodyTextBounded()
            val message = "Hermes authentication returned HTTP ${meResponse.status.value}"
            if (meResponse.status.value == 401 || meResponse.status.value == 403) {
                throw HermesAuthenticationRejectedException(message)
            }
            throw HermesConnectionException(message)
        }
        val user = json.decodeFromString<HermesAuthenticatedUser>(
            meResponse.readBodyTextBounded(),
        )
        if (user.userId.isBlank()) {
            throw HermesConnectionException("Hermes authentication response was incomplete")
        }

        val sessions = loadSessions(serverOrigin, accessToken)
        AuthenticatedHermesConnection(user.userId, sessions)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException(
            "Hermes authentication failed (${error.javaClass.simpleName})",
            error,
        )
    }

    override suspend fun loadTranscript(
        serverOrigin: ServerOrigin,
        accessToken: String?,
        durableSessionId: DurableSessionId,
    ): List<ChatMessage> = try {
        val encodedId = durableSessionId.value.encodeURLPathPart()
        val response = client.get("${serverOrigin.value}/api/sessions/$encodedId/messages") {
            accessToken?.let { bearerAuth(it) }
            parameter("limit", 100)
            parameter("profile", "default")
        }
        if (!response.status.isSuccess()) {
            response.readBodyTextBounded()
            throw HermesConnectionException(
                "Hermes transcript returned HTTP ${response.status.value}",
            )
        }
        val decoded = json.decodeFromString<HermesTranscriptResponse>(
            response.readBodyTextBounded(MAX_TRANSCRIPT_BODY_BYTES),
        )
        (decoded.messages.ifEmpty { decoded.data }).mapNotNull { row ->
            val role = when (row["role"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "user" -> ChatMessageRole.User
                "assistant" -> ChatMessageRole.Assistant
                "system" -> ChatMessageRole.System
                "tool" -> ChatMessageRole.Tool
                else -> return@mapNotNull null
            }
            val text = row["content"]?.jsonPrimitive?.contentOrNull
                ?: row["text"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            ChatMessage(role = role, text = text)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException("Could not load Hermes transcript", error)
    }

    private suspend fun loadSessions(
        serverOrigin: ServerOrigin,
        accessToken: String?,
    ): List<SessionSummary> {
        val sessionsResponse = client.get("${serverOrigin.value}/api/profiles/sessions") {
            accessToken?.let { bearerAuth(it) }
            parameter("limit", MAX_DURABLE_SESSIONS)
            parameter("order", "recent")
            parameter("archived", "exclude")
            parameter("profile", "default")
        }
        if (!sessionsResponse.status.isSuccess()) {
            sessionsResponse.readBodyTextBounded()
            val message = "Hermes session listing returned HTTP ${sessionsResponse.status.value}"
            if (
                accessToken != null &&
                (sessionsResponse.status.value == 401 || sessionsResponse.status.value == 403)
            ) {
                throw HermesAuthenticationRejectedException(message)
            }
            throw HermesConnectionException(message)
        }
        val sessions = json.decodeFromString<HermesSessionsResponse>(
            sessionsResponse.readBodyTextBounded(),
        ).sessions.mapNotNull { row ->
            val id = row.id?.takeIf(String::isNotBlank)
                ?: row.sessionKey?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            SessionSummary(
                id = DurableSessionId(id),
                title = row.title?.takeIf(String::isNotBlank) ?: "Untitled session",
            )
        }.distinctBy { it.id }
            .take(MAX_DURABLE_SESSIONS)
        return sessions
    }
}
