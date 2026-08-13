package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.app.validHostFolderName
import com.unsupportedpastels.hermesandroid.app.validProjectWorkspacePath
import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryEntry
import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryListing
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.ModelProviderOption
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection
import com.unsupportedpastels.hermesandroid.gateway.ModelSwitchResult
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
    val preview: String? = null,
    @SerialName("last_active") val lastActive: Double? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    val model: String? = null,
    @SerialName("billing_provider") val billingProvider: String? = null,
    val provider: String? = null,
    val profile: String? = null,
    val cwd: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
)

@Serializable
private data class HermesSessionsResponse(
    val sessions: List<HermesSessionRow>,
)

@Serializable
private data class HermesSessionSearchResponse(
    val results: List<HermesSessionSearchRow> = emptyList(),
)

@Serializable
private data class HermesSessionSearchRow(
    @SerialName("session_id") val sessionId: String? = null,
    val id: String? = null,
    val title: String? = null,
    val snippet: String? = null,
    val role: String? = null,
)

data class SessionSearchResult(
    val sessionId: DurableSessionId,
    val title: String,
    val snippet: String,
    val role: String? = null,
)

@Serializable
private data class ProfilesResponse(
    val profiles: List<JsonObject> = emptyList(),
)

@Serializable
private data class DefaultModelSetResponse(
    val ok: Boolean = false,
    @SerialName("confirm_required") val confirmRequired: Boolean = false,
    @SerialName("confirm_message") val confirmMessage: String? = null,
)

@Serializable
private data class HermesManagedFileEntry(
    val name: String,
    val path: String,
    @SerialName("is_directory") val isDirectory: Boolean,
)

@Serializable
private data class HermesManagedFilesResponse(
    val path: String,
    val parent: String? = null,
    val entries: List<HermesManagedFileEntry>,
    val root: String? = null,
    @SerialName("locked_root") val lockedRoot: String? = null,
    @SerialName("can_change_path") val canChangePath: Boolean = true,
)

@Serializable
private data class HermesManagedDirectoryCreateRequest(
    val path: String,
)

@Serializable
private data class HermesManagedDirectoryCreateResponse(
    val ok: Boolean,
    val path: String,
)

@Serializable
private data class HermesTranscriptResponse(
    val messages: List<JsonObject> = emptyList(),
    val data: List<JsonObject> = emptyList(),
)

@Serializable
data class SessionUpdateResult(
    val ok: Boolean,
    val title: String? = null,
    val archived: Boolean? = null,
    val pinned: Boolean? = null,
)

@Serializable
private data class SessionUpdateRequest(
    val title: String? = null,
    val archived: Boolean? = null,
    val pinned: Boolean? = null,
    val profile: String? = null,
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
private const val MAX_TRANSCRIPT_REASONING_CHARS = 1024 * 1024
private const val MAX_DURABLE_SESSIONS = 20
private const val MAX_HOST_DIRECTORY_ENTRIES = 500
private const val MAX_MANAGED_IMAGE_BYTES = 10 * 1024 * 1024

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

    suspend fun loadHostDirectories(
        serverOrigin: ServerOrigin,
        accessToken: String?,
        path: String? = null,
    ): HostDirectoryListing = throw UnsupportedOperationException()

    suspend fun createHostDirectory(
        serverOrigin: ServerOrigin,
        accessToken: String?,
        parentPath: String,
        name: String,
    ): HostDirectoryListing = throw UnsupportedOperationException()

    suspend fun downloadManagedImage(
        serverOrigin: ServerOrigin,
        accessToken: String?,
        path: String,
    ): ByteArray = throw UnsupportedOperationException()

    suspend fun updateSession(
        serverOrigin: ServerOrigin,
        accessToken: String,
        durableSessionId: DurableSessionId,
        profile: String? = null,
        title: String? = null,
        archived: Boolean? = null,
        pinned: Boolean? = null,
    ): SessionUpdateResult = throw UnsupportedOperationException()

    suspend fun deleteSession(
        serverOrigin: ServerOrigin,
        accessToken: String,
        durableSessionId: DurableSessionId,
        profile: String? = null,
    ): Unit = throw UnsupportedOperationException()

    suspend fun searchSessions(
        serverOrigin: ServerOrigin,
        accessToken: String,
        query: String,
        profile: String? = null,
    ): List<SessionSearchResult> = throw UnsupportedOperationException()

    suspend fun loadProfiles(serverOrigin: ServerOrigin, accessToken: String): List<String> =
        throw UnsupportedOperationException()

    suspend fun loadDefaultModelOptions(
        serverOrigin: ServerOrigin,
        accessToken: String,
        profile: String,
    ): ModelOptions = throw UnsupportedOperationException()

    suspend fun setDefaultModel(
        serverOrigin: ServerOrigin,
        accessToken: String,
        profile: String,
        selection: ModelSelection,
        confirmExpensiveModel: Boolean = false,
    ): ModelSwitchResult = throw UnsupportedOperationException()
}

internal suspend fun authenticatedConnectionConcurrently(
    loadUser: suspend () -> String,
    loadSessions: suspend () -> List<SessionSummary>,
): AuthenticatedHermesConnection = coroutineScope {
    val user = async { loadUser() }
    val sessions = async { loadSessions() }
    AuthenticatedHermesConnection(user.await(), sessions.await())
}

class HttpHermesConnectionClient(
    private val client: HttpClient,
) : HermesConnectionClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun updateSession(
        serverOrigin: ServerOrigin,
        accessToken: String,
        durableSessionId: DurableSessionId,
        profile: String?,
        title: String?,
        archived: Boolean?,
        pinned: Boolean?,
    ): SessionUpdateResult {
        require(title != null || archived != null || pinned != null) { "Session update is empty" }
        val response = client.patch(
            "${serverOrigin.value}/api/sessions/${durableSessionId.value.encodeURLPathPart()}",
        ) {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    SessionUpdateRequest(
                        title = title?.take(512),
                        archived = archived,
                        pinned = pinned,
                        profile = profile?.takeIf { it != "default" },
                    ),
                ),
            )
        }
        val body = response.readBodyTextBounded()
        if (!response.status.isSuccess()) {
            throw HermesConnectionException("Hermes session update returned HTTP ${response.status.value}")
        }
        return json.decodeFromString<SessionUpdateResult>(body)
    }

    override suspend fun deleteSession(
        serverOrigin: ServerOrigin,
        accessToken: String,
        durableSessionId: DurableSessionId,
        profile: String?,
    ) {
        val response = client.delete(
            "${serverOrigin.value}/api/sessions/${durableSessionId.value.encodeURLPathPart()}",
        ) {
            bearerAuth(accessToken)
            profile?.takeIf { it != "default" }?.let { parameter("profile", it) }
        }
        response.readBodyTextBounded()
        if (!response.status.isSuccess()) {
            throw HermesConnectionException("Hermes session deletion returned HTTP ${response.status.value}")
        }
    }

    override suspend fun searchSessions(
        serverOrigin: ServerOrigin,
        accessToken: String,
        query: String,
        profile: String?,
    ): List<SessionSearchResult> {
        val boundedQuery = query.trim().take(256)
        if (boundedQuery.isEmpty()) return emptyList()
        val response = client.get("${serverOrigin.value}/api/sessions/search") {
            bearerAuth(accessToken)
            parameter("q", boundedQuery)
            parameter("limit", 20)
            profile?.let { parameter("profile", it) }
        }
        val body = response.readBodyTextBounded()
        if (!response.status.isSuccess()) {
            throw HermesConnectionException("Hermes session search returned HTTP ${response.status.value}")
        }
        return json.decodeFromString<HermesSessionSearchResponse>(body).results
            .mapNotNull { row ->
                val id = row.sessionId?.takeIf(String::isNotBlank)
                    ?: row.id?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                SessionSearchResult(
                    sessionId = DurableSessionId(id.take(256)),
                    title = row.title?.takeIf(String::isNotBlank)?.take(512) ?: "Untitled session",
                    snippet = row.snippet.orEmpty().take(1_000),
                    role = row.role?.takeIf(String::isNotBlank)?.take(32),
                )
            }
            .distinctBy(SessionSearchResult::sessionId)
            .take(20)
    }

    override suspend fun loadProfiles(
        serverOrigin: ServerOrigin,
        accessToken: String,
    ): List<String> {
        val response = client.get("${serverOrigin.value}/api/profiles") { bearerAuth(accessToken) }
        val body = response.readBodyTextBounded()
        if (!response.status.isSuccess()) {
            throw HermesConnectionException("Hermes profiles returned HTTP ${response.status.value}")
        }
        return json.decodeFromString<ProfilesResponse>(body).profiles
            .mapNotNull { row ->
                row["name"]?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it.length <= 64 }
            }
            .distinct()
            .take(32)
    }

    override suspend fun loadDefaultModelOptions(
        serverOrigin: ServerOrigin,
        accessToken: String,
        profile: String,
    ): ModelOptions {
        val response = client.get("${serverOrigin.value}/api/model/options") {
            bearerAuth(accessToken)
            parameter("profile", profile.take(64))
            parameter("explicit_only", 1)
        }
        val body = response.readBodyTextBounded()
        if (!response.status.isSuccess()) {
            throw HermesConnectionException("Hermes model options returned HTTP ${response.status.value}")
        }
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw HermesConnectionException("Hermes model options response was invalid")
        val provider = root["provider"]?.jsonPrimitive?.contentOrNull
        val model = root["model"]?.jsonPrimitive?.contentOrNull
        val providers = (root["providers"] as? JsonArray).orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val slug = row["slug"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            if (row["authenticated"]?.jsonPrimitive?.contentOrNull == "false" && slug != provider) {
                return@mapNotNull null
            }
            val name = row["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: slug
            val models = (row["models"] as? JsonArray).orEmpty().mapNotNull { model ->
                model.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank)?.take(256)
            }.distinct().take(200)
            if (models.isEmpty()) null else ModelProviderOption(slug.take(64), name.take(128), models)
        }.take(32)
        return ModelOptions(
            current = if (!provider.isNullOrBlank() && !model.isNullOrBlank()) {
                ModelSelection(provider.take(64), model.take(256))
            } else null,
            providers = providers,
        )
    }

    override suspend fun setDefaultModel(
        serverOrigin: ServerOrigin,
        accessToken: String,
        profile: String,
        selection: ModelSelection,
        confirmExpensiveModel: Boolean,
    ): ModelSwitchResult {
        val response = client.post("${serverOrigin.value}/api/model/set") {
            bearerAuth(accessToken)
            parameter("profile", profile.take(64))
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("scope", "main")
                put("provider", selection.provider.take(64))
                put("model", selection.model.take(256))
                put("confirm_expensive_model", confirmExpensiveModel)
            }.toString())
        }
        val body = response.readBodyTextBounded()
        if (!response.status.isSuccess()) {
            throw HermesConnectionException("Hermes default model update returned HTTP ${response.status.value}")
        }
        val result = json.decodeFromString<DefaultModelSetResponse>(body)
        return ModelSwitchResult(
            accepted = result.ok,
            confirmationRequired = result.confirmRequired,
            confirmationMessage = result.confirmMessage?.take(1_000),
        )
    }

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
        authenticatedConnectionConcurrently(
            loadUser = { loadAuthenticatedUser(serverOrigin, accessToken) },
            loadSessions = { loadSessions(serverOrigin, accessToken) },
        )
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

    private suspend fun loadAuthenticatedUser(
        serverOrigin: ServerOrigin,
        accessToken: String,
    ): String {
        val response = client.get("${serverOrigin.value}/api/auth/me") {
            bearerAuth(accessToken)
        }
        if (!response.status.isSuccess()) {
            response.readBodyTextBounded()
            val message = "Hermes authentication returned HTTP ${response.status.value}"
            if (response.status.value == 401 || response.status.value == 403) {
                throw HermesAuthenticationRejectedException(message)
            }
            throw HermesConnectionException(message)
        }
        val user = json.decodeFromString<HermesAuthenticatedUser>(
            response.readBodyTextBounded(),
        )
        if (user.userId.isBlank()) {
            throw HermesConnectionException("Hermes authentication response was incomplete")
        }
        return user.userId
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
            parameter("order", "latest")
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
            // Tool rows from the server's transcript projection carry
            // {role, name, context, args?} with no text/content field; combine
            // the name and context preview so tool activity survives reloads
            // instead of being silently dropped.
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
            if (text == null && reasoning == null) return@mapNotNull null
            ChatMessage(
                role = role,
                text = text.orEmpty(),
                reasoningText = reasoning.orEmpty(),
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException("Could not load Hermes transcript", error)
    }

    override suspend fun loadHostDirectories(
        serverOrigin: ServerOrigin,
        accessToken: String?,
        path: String?,
    ): HostDirectoryListing = try {
        val requestedPath = path?.let {
            validProjectWorkspacePath(it)
                ?: throw HermesConnectionException("Host folder path is invalid")
        }
        val response = client.get("${serverOrigin.value}/api/files") {
            accessToken?.let { bearerAuth(it) }
            requestedPath?.let { parameter("path", it) }
        }
        if (!response.status.isSuccess()) {
            response.readBodyTextBounded()
            throw HermesConnectionException(
                "Hermes host folder listing returned HTTP ${response.status.value}",
            )
        }
        val decoded = json.decodeFromString<HermesManagedFilesResponse>(
            response.readBodyTextBounded(),
        )
        val canonicalPath = validProjectWorkspacePath(decoded.path)
            ?: throw HermesConnectionException("Hermes host folder response was incomplete")
        HostDirectoryListing(
            path = canonicalPath,
            directories = decoded.entries.asSequence()
                .filter(HermesManagedFileEntry::isDirectory)
                .mapNotNull { entry ->
                    val name = validManagedDirectoryEntryName(entry.name) ?: return@mapNotNull null
                    val entryPath = validProjectWorkspacePath(entry.path) ?: return@mapNotNull null
                    HostDirectoryEntry(name = name, path = entryPath)
                }
                .distinctBy(HostDirectoryEntry::path)
                .take(MAX_HOST_DIRECTORY_ENTRIES)
                .toList(),
            parentPath = decoded.parent?.let(::validProjectWorkspacePath),
            lockedRoot = decoded.lockedRoot?.let(::validProjectWorkspacePath),
            canChangePath = decoded.canChangePath,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException("Could not load host folders", error)
    }

    override suspend fun createHostDirectory(
        serverOrigin: ServerOrigin,
        accessToken: String?,
        parentPath: String,
        name: String,
    ): HostDirectoryListing = try {
        val validParent = validProjectWorkspacePath(parentPath)
            ?: throw HermesConnectionException("Host parent folder is invalid")
        val validName = validHostFolderName(name)
            ?: throw HermesConnectionException("Host folder name is invalid")
        val requestedPath = joinManagedHostPath(validParent, validName)
        val response = client.post("${serverOrigin.value}/api/files/mkdir") {
            accessToken?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(HermesManagedDirectoryCreateRequest(requestedPath)))
        }
        if (!response.status.isSuccess()) {
            response.readBodyTextBounded()
            throw HermesConnectionException(
                "Hermes host folder creation returned HTTP ${response.status.value}",
            )
        }
        val created = json.decodeFromString<HermesManagedDirectoryCreateResponse>(
            response.readBodyTextBounded(),
        )
        if (!created.ok) {
            throw HermesConnectionException("Hermes did not create the host folder")
        }
        val canonicalPath = validProjectWorkspacePath(created.path)
            ?: throw HermesConnectionException("Hermes host folder response was incomplete")
        loadHostDirectories(serverOrigin, accessToken, canonicalPath)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException("Could not create host folder", error)
    }

    override suspend fun downloadManagedImage(
        serverOrigin: ServerOrigin,
        accessToken: String?,
        path: String,
    ): ByteArray = try {
        require(path.startsWith('/')) { "Managed image path must be absolute" }
        val response = client.get("${serverOrigin.value}/api/files/download") {
            accessToken?.let { bearerAuth(it) }
            parameter("path", path)
        }
        if (!response.status.isSuccess()) {
            response.bodyAsChannel().cancel(null)
            throw HermesConnectionException(
                "Hermes managed image returned HTTP ${response.status.value}",
            )
        }
        val contentType = response.headers[io.ktor.http.HttpHeaders.ContentType]
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        if (contentType?.startsWith("image/") != true) {
            response.bodyAsChannel().cancel(null)
            throw HermesConnectionException("Hermes managed file was not an image")
        }
        val declaredLength = response.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredLength != null && declaredLength > MAX_MANAGED_IMAGE_BYTES) {
            response.bodyAsChannel().cancel(null)
            throw HermesConnectionException("Hermes managed image was too large")
        }
        val channel = response.bodyAsChannel()
        try {
            val source = channel.readRemaining(MAX_MANAGED_IMAGE_BYTES + 1L)
            try {
                val bytes = ByteArray(MAX_MANAGED_IMAGE_BYTES + 1)
                var count = 0
                while (!source.exhausted()) {
                    val read = source.readAtMostTo(bytes, count, bytes.size)
                    if (read <= 0) break
                    count += read
                    if (count > MAX_MANAGED_IMAGE_BYTES) {
                        throw HermesConnectionException("Hermes managed image was too large")
                    }
                }
                bytes.copyOf(count)
            } finally {
                source.close()
            }
        } finally {
            channel.cancel(null)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException("Could not download Hermes managed image", error)
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
                workspacePath = row.cwd?.takeIf(String::isNotBlank),
                preview = row.preview?.takeIf(String::isNotBlank),
                lastActiveEpochSeconds = row.lastActive,
                messageCount = row.messageCount?.coerceAtLeast(0),
                model = row.model?.takeIf(String::isNotBlank),
                provider = (row.provider ?: row.billingProvider)?.takeIf(String::isNotBlank),
                profile = row.profile?.takeIf(String::isNotBlank),
                pinned = row.pinned,
                archived = row.archived,
            )
        }.distinctBy { it.id }
            .take(MAX_DURABLE_SESSIONS)
        return sessions
    }
}

private fun validManagedDirectoryEntryName(name: String): String? {
    if (name.isEmpty() || name.length > 255 || name in setOf(".", "..")) return null
    if (name.any(Char::isISOControl) || '/' in name || '\\' in name) return null
    return name
}

private fun joinManagedHostPath(parent: String, child: String): String {
    val windows = parent.length >= 2 && parent[1] == ':'
    val separator = if (windows) '\\' else '/'
    return parent.trimEnd('/', '\\') + separator + child
}

private fun JsonObject.assistantReasoningText(): String? =
    sequenceOf("reasoning", "reasoning_content", "reasoning_details")
        .mapNotNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull }
        .firstOrNull(String::isNotBlank)
        ?.take(MAX_TRANSCRIPT_REASONING_CHARS)

private fun JsonObject.transcriptToolText(): String? {
    val explicitText = (this["content"] as? JsonPrimitive)?.contentOrNull
        ?: (this["text"] as? JsonPrimitive)?.contentOrNull
    if (!explicitText.isNullOrBlank()) return explicitText
    val name = (this["name"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    val context = (this["context"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    return listOfNotNull(name, context?.takeUnless { it == name })
        .joinToString(" · ")
        .takeIf(String::isNotEmpty)
}
