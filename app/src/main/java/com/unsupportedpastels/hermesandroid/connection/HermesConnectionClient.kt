package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

class HermesConnectionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

private const val MAX_RESPONSE_BODY_BYTES = 64 * 1024
private const val MAX_DURABLE_SESSIONS = 20

internal fun HttpClientConfig<*>.configureHermesHttpClient() {
    followRedirects = false
}

internal suspend fun HttpResponse.readBodyTextBounded(): String {
    val channel = bodyAsChannel()
    return try {
        val source = channel.readRemaining(MAX_RESPONSE_BODY_BYTES + 1L)
        try {
            val bytes = ByteArray(MAX_RESPONSE_BODY_BYTES + 1)
            var count = 0
            while (!source.exhausted()) {
                val read = source.readAtMostTo(bytes, count, bytes.size)
                if (read <= 0) break
                count += read
                if (count > MAX_RESPONSE_BODY_BYTES) {
                    throw HermesConnectionException("Hermes response body was too large")
                }
            }
            if (count > MAX_RESPONSE_BODY_BYTES) {
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
            throw HermesConnectionException(
                "Hermes authentication returned HTTP ${meResponse.status.value}",
            )
        }
        val user = json.decodeFromString<HermesAuthenticatedUser>(
            meResponse.readBodyTextBounded(),
        )
        if (user.userId.isBlank()) {
            throw HermesConnectionException("Hermes authentication response was incomplete")
        }

        val sessions = loadSessions(serverOrigin, accessToken)
        AuthenticatedHermesConnection(user.userId, sessions)
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException(
            "Hermes authentication failed (${error.javaClass.simpleName})",
            error,
        )
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
        }
        if (!sessionsResponse.status.isSuccess()) {
            sessionsResponse.readBodyTextBounded()
            throw HermesConnectionException(
                "Hermes session listing returned HTTP ${sessionsResponse.status.value}",
            )
        }
        val sessions = json.decodeFromString<HermesSessionsResponse>(
            sessionsResponse.readBodyTextBounded(),
        ).sessions.mapNotNull { row ->
            val id = row.sessionKey?.takeIf(String::isNotBlank)
                ?: row.id?.takeIf(String::isNotBlank)
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
