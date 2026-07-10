package com.hermes.mobile.ui

import android.app.Application
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.data.AuthProvider
import com.hermes.mobile.data.ChatMessage
import com.hermes.mobile.data.ConnectionStore
import com.hermes.mobile.data.GatewayEvent
import com.hermes.mobile.data.HermesApi
import com.hermes.mobile.data.HermesAuthenticationRequired
import com.hermes.mobile.data.HermesGateway
import com.hermes.mobile.data.HermesProfile
import com.hermes.mobile.data.HermesProject
import com.hermes.mobile.data.HermesProjectFolder
import com.hermes.mobile.data.HermesRpcException
import com.hermes.mobile.data.HermesSession
import com.hermes.mobile.data.HermesStatus
import com.hermes.mobile.data.ModelOption
import com.hermes.mobile.data.NativeOAuthFlow
import com.hermes.mobile.data.PendingAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

data class HermesUiState(
    val baseUrl: String = "",
    val loading: Boolean = false,
    val connected: Boolean = false,
    val status: HermesStatus? = null,
    val providers: List<AuthProvider> = emptyList(),
    val needsAuthentication: Boolean = false,
    val oauthUrl: String? = null,
    val sessions: List<HermesSession> = emptyList(),
    val profiles: List<HermesProfile> = emptyList(),
    val projects: List<HermesProject> = emptyList(),
    val modelOptions: List<ModelOption> = emptyList(),
    val selectedSessionId: String? = null,
    val selectedProject: String? = null,
    val selectedProjectCwd: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val loadingChat: Boolean = false,
    val runtimeSessionId: String? = null,
    val composerText: String = "",
    val selectedProfile: String = "default",
    val selectedCwd: String = "",
    val selectedWorkspaceLabel: String = "No project",
    val selectedModel: String = "",
    val selectedProvider: String = "",
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val loadingOptions: Boolean = false,
    val savingProfileDefault: Boolean = false,
    val profileDefaultConfirmation: ProfileDefaultConfirmation? = null,
    val sending: Boolean = false,
    val error: String? = null,
)

class HermesViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ConnectionStore(application)
    private val api = HermesApi(store)
    private val gateway = HermesGateway()
    private var nativeOAuthFlow: NativeOAuthFlow? = null
    private var turnRecoveryJob: Job? = null
    private var turnRecovery: TurnRecovery? = null
    private var gatewayDisconnected = false
    private var disconnectError: Throwable? = null
    private var chatGeneration = 0L
    private var modelSelectionGeneration = 0L
    private val modelSelectionMutex = Mutex()

    var state = androidx.compose.runtime.mutableStateOf(HermesUiState(baseUrl = store.baseUrl))
        private set

    init {
        if (store.baseUrl.isNotBlank()) {
            api.restoreSession(store.baseUrl)
            connect()
        }
    }

    fun setBaseUrl(value: String) { state.value = state.value.copy(baseUrl = value, providers = emptyList(), needsAuthentication = false) }
    fun setComposerText(value: String) { state.value = state.value.copy(composerText = value) }

    fun addAttachment(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        if (state.value.pendingAttachments.size >= MAX_ATTACHMENT_COUNT) {
            state.value = state.value.copy(error = "You can attach up to $MAX_ATTACHMENT_COUNT files at once")
            return
        }
        val metadata = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = cursor.stringColumn(OpenableColumns.DISPLAY_NAME)
                    .ifBlank { uri.lastPathSegment ?: "attachment" }
                    .safeAttachmentName()
                val size = cursor.longColumn(OpenableColumns.SIZE)
                name to size
            }
        }.getOrNull() ?: ((uri.lastPathSegment ?: "attachment").safeAttachmentName() to -1L)
        val mimeType = resolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        val isImage = mimeType.startsWith("image/") || metadata.first.hasImageExtension()
        val perFileLimit = if (isImage) MAX_IMAGE_BYTES else MAX_ATTACHMENT_BYTES
        if (metadata.second > perFileLimit) {
            val megabytes = perFileLimit / (1024 * 1024)
            state.value = state.value.copy(error = "${metadata.first} is larger than the $megabytes MB upload limit")
            return
        }
        val knownAggregate = state.value.pendingAttachments.sumOf { maxOf(0L, it.sizeBytes) } + maxOf(0L, metadata.second)
        if (knownAggregate > MAX_TOTAL_ATTACHMENT_BYTES) {
            state.value = state.value.copy(error = "Attachments exceed the 100 MB total upload limit")
            return
        }
        val attachment = PendingAttachment(
            uri = uri.toString(),
            name = metadata.first,
            mimeType = mimeType,
            sizeBytes = metadata.second,
        )
        state.value = state.value.copy(
            pendingAttachments = (state.value.pendingAttachments + attachment).distinctBy { it.uri },
            error = null,
        )
    }

    fun removeAttachment(uri: String) {
        state.value = state.value.copy(pendingAttachments = state.value.pendingAttachments.filterNot { it.uri == uri })
    }

    fun selectDraftProfile(profile: String) {
        if (state.value.selectedSessionId != "new" || state.value.sending) return
        state.value = state.value.copy(
            selectedProfile = profile,
            selectedModel = "",
            selectedProvider = "",
            modelOptions = emptyList(),
            loadingOptions = true,
            error = null,
        )
        val operation = chatGeneration
        viewModelScope.launch {
            runCatching { api.loadModelOptions(state.value.baseUrl, profile) }
                .onSuccess { options ->
                    if (operation != chatGeneration || state.value.selectedProfile != profile) return@onSuccess
                    val preferred = preferredModel(options, profile)
                    state.value = state.value.copy(
                        modelOptions = options,
                        selectedModel = preferred?.model.orEmpty(),
                        selectedProvider = preferred?.provider.orEmpty(),
                        loadingOptions = false,
                    )
                }
                .onFailure { if (operation == chatGeneration) showError(it) }
        }
    }

    fun selectDraftWorkspace(projectName: String, cwd: String) {
        if (state.value.selectedSessionId != "new" || state.value.sending) return
        state.value = state.value.copy(selectedWorkspaceLabel = projectName, selectedCwd = cwd)
    }

    fun selectModel(option: ModelOption) {
        val current = state.value
        if (!option.available || current.sending || current.loadingChat) return
        val selection = ++modelSelectionGeneration
        state.value = current.copy(selectedModel = option.model, selectedProvider = option.provider, error = null)
        val runtimeId = current.runtimeSessionId ?: return
        val operation = chatGeneration
        viewModelScope.launch {
            modelSelectionMutex.withLock {
                if (operation != chatGeneration || selection != modelSelectionGeneration) return@withLock
                runCatching {
                    gateway.request(
                        "config.set",
                        JSONObject()
                            .put("session_id", runtimeId)
                            .put("key", "model")
                            .put("value", "${option.model} --provider ${option.provider}"),
                    )
                }.onFailure { error ->
                    if (operation != chatGeneration || selection != modelSelectionGeneration) return@onFailure
                    state.value = state.value.copy(
                        selectedModel = current.selectedModel,
                        selectedProvider = current.selectedProvider,
                    )
                    showError(error)
                }
            }
        }
    }

    /** Sets the profile's main provider/model for future sessions only. */
    fun setProfileDefaultModel(option: ModelOption, confirmExpensiveModel: Boolean = false) {
        val current = state.value
        if (!option.available || current.savingProfileDefault) return
        val profile = current.selectedProfile.ifBlank { "default" }
        state.value = current.copy(savingProfileDefault = true, profileDefaultConfirmation = null, error = null)
        viewModelScope.launch {
            runCatching {
                api.setProfileDefaultModel(current.baseUrl, profile, option, confirmExpensiveModel)
            }.onSuccess { result ->
                if (state.value.baseUrl != current.baseUrl || state.value.selectedProfile != profile) return@onSuccess
                val confirmation = result.confirmationMessage
                if (confirmation != null) {
                    state.value = state.value.copy(
                        savingProfileDefault = false,
                        profileDefaultConfirmation = ProfileDefaultConfirmation(option, confirmation),
                    )
                } else {
                    val isDraft = state.value.selectedSessionId == "new" && state.value.runtimeSessionId == null
                    state.value = state.value.copy(
                        savingProfileDefault = false,
                        modelOptions = state.value.modelOptions.map {
                            it.copy(isProfileDefault = it.provider == option.provider && it.model == option.model)
                        },
                        selectedModel = if (isDraft) option.model else state.value.selectedModel,
                        selectedProvider = if (isDraft) option.provider else state.value.selectedProvider,
                    )
                }
            }.onFailure { error ->
                if (state.value.baseUrl == current.baseUrl && state.value.selectedProfile == profile) {
                    state.value = state.value.copy(savingProfileDefault = false)
                    showError(error)
                }
            }
        }
    }

    fun cancelProfileDefaultConfirmation() {
        state.value = state.value.copy(profileDefaultConfirmation = null)
    }

    fun selectProject(value: String?, cwd: String = "") {
        chatGeneration++
        modelSelectionGeneration++
        clearTurnRecovery()
        gateway.close()
        state.value = state.value.copy(
            selectedProject = value,
            selectedProjectCwd = if (value == null) "" else cwd,
            selectedSessionId = null,
            messages = emptyList(),
            runtimeSessionId = null,
            pendingAttachments = emptyList(),
        )
    }

    fun leaveChat() {
        val operation = ++chatGeneration
        modelSelectionGeneration++
        clearTurnRecovery()
        val runtimeId = state.value.runtimeSessionId
        state.value = state.value.copy(
            selectedSessionId = null,
            messages = emptyList(),
            runtimeSessionId = null,
            composerText = "",
            pendingAttachments = emptyList(),
            sending = false,
            loadingChat = false,
        )
        viewModelScope.launch {
            if (runtimeId != null && operation == chatGeneration) {
                runCatching {
                    withTimeout(3_000) {
                        gateway.request("session.close", JSONObject().put("session_id", runtimeId))
                    }
                }
            }
            if (operation == chatGeneration) gateway.close()
        }
    }

    fun selectSession(id: String) {
        val operation = ++chatGeneration
        clearTurnRecovery()
        val session = state.value.sessions.firstOrNull { it.id == id } ?: return
        gateway.close()
        state.value = state.value.copy(selectedSessionId = id, messages = emptyList(), loadingChat = true, runtimeSessionId = null, error = null)
        viewModelScope.launch {
            runCatching {
                val storedMessages = api.loadMessages(state.value.baseUrl, session)
                if (operation != chatGeneration) throw CancellationException("Chat selection changed")
                state.value = state.value.copy(messages = storedMessages, loadingChat = false)
                connectGateway(operation)
                val response = gateway.request(
                    "session.resume",
                    JSONObject().put("session_id", session.id).put("cols", 96).put("source", "android")
                        .put("close_on_disconnect", false)
                        .apply { if (session.profile.isNotBlank()) put("profile", session.profile) },
                )
                if (operation != chatGeneration) throw CancellationException("Chat selection changed")
                val resumedMessages = HermesApi.parseMessages(response)
                val modelOptions = runCatching {
                    gateway.request(
                        "model.options",
                        JSONObject()
                            .put("session_id", response.optString("session_id"))
                            .put("explicit_only", true),
                    )
                }.map(HermesApi::parseModelOptions).getOrDefault(emptyList())
                SessionOpenResult(response, resumedMessages, modelOptions)
            }.onSuccess { opened ->
                if (operation != chatGeneration) return@onSuccess
                val info = opened.response.optJSONObject("info") ?: JSONObject()
                state.value = state.value.copy(
                    runtimeSessionId = opened.response.optString("session_id"),
                    messages = if (opened.messages.isNotEmpty()) opened.messages else state.value.messages,
                    loadingChat = false,
                    selectedProfile = info.optString("profile_name").ifBlank { session.profile.ifBlank { "default" } },
                    selectedCwd = info.optString("cwd").ifBlank { session.cwd },
                    selectedWorkspaceLabel = session.projectName,
                    selectedModel = info.optString("model").ifBlank { session.model },
                    selectedProvider = info.optString("provider"),
                    modelOptions = opened.modelOptions,
                    pendingAttachments = emptyList(),
                )
            }.onFailure { error ->
                if (operation == chatGeneration && error !is CancellationException) showError(error)
            }
        }
    }

    fun deleteSession(session: HermesSession) {
        if (session.id.isBlank() || session.id == "new") return
        val current = state.value
        if (current.sessions.none { it.id == session.id }) return
        state.value = current.copy(
            sessions = current.sessions.filterNot { it.id == session.id },
            error = null,
        )
        viewModelScope.launch {
            runCatching { api.deleteSession(current.baseUrl, session) }
                .onFailure { error ->
                    val latest = state.value
                    if (latest.sessions.none { it.id == session.id }) {
                        state.value = latest.copy(
                            sessions = (latest.sessions + session).sortedByDescending { it.lastActive },
                            error = error.message ?: "Could not delete session",
                        )
                    }
                }
        }
    }

    fun startNewChat() {
        val operation = ++chatGeneration
        clearTurnRecovery()
        val current = state.value
        val projectSession = current.selectedProjectCwd.takeIf(String::isNotBlank)?.let { selectedCwd ->
            current.sessions.filter { it.cwd.equals(selectedCwd, ignoreCase = true) }.maxByOrNull { it.lastActive }
        }
        gateway.close()
        val initialProfile = projectSession?.profile?.ifBlank { "default" }
            ?: current.profiles.firstOrNull()?.name
            ?: current.status?.profiles?.firstOrNull()
            ?: "default"
        state.value = current.copy(
            selectedSessionId = "new",
            messages = emptyList(),
            loadingChat = false,
            runtimeSessionId = null,
            composerText = "",
            selectedProfile = initialProfile,
            selectedCwd = current.selectedProjectCwd.ifBlank { projectSession?.cwd.orEmpty() },
            selectedWorkspaceLabel = current.selectedProject ?: projectSession?.projectName ?: "No project",
            selectedModel = "",
            selectedProvider = "",
            pendingAttachments = emptyList(),
            loadingOptions = true,
            error = null,
        )
        viewModelScope.launch {
            runCatching {
                val profiles = runCatching { api.loadProfiles(current.baseUrl) }.getOrElse {
                    current.status?.profiles.orEmpty().map(::HermesProfile)
                }.ifEmpty { listOf(HermesProfile("default")) }
                val options = api.loadModelOptions(current.baseUrl, initialProfile)
                val projects = runCatching {
                    connectGateway(operation)
                    parseProjects(gateway.request("projects.list"))
                }.getOrDefault(emptyList())
                NewChatOptions(
                    profiles = profiles,
                    models = options,
                    projects = mergeProjects(projects, current.sessions),
                )
            }.onSuccess { options ->
                if (operation != chatGeneration) return@onSuccess
                val preferred = preferredModel(options.models, initialProfile)
                state.value = state.value.copy(
                    profiles = options.profiles,
                    projects = options.projects,
                    modelOptions = options.models,
                    selectedModel = preferred?.model.orEmpty(),
                    selectedProvider = preferred?.provider.orEmpty(),
                    loadingOptions = false,
                )
            }.onFailure { error ->
                if (operation == chatGeneration && error !is CancellationException) showError(error)
            }
        }
    }

    fun sendMessage() {
        val current = state.value
        val text = current.composerText.trim()
        val attachments = current.pendingAttachments
        if ((text.isBlank() && attachments.isEmpty()) || current.sending || current.loadingOptions || current.loadingChat) return
        val operation = chatGeneration
        var createdDraftRuntime: RuntimeSession? = null
        var turnVisible = false
        state.value = current.copy(sending = true, error = null)
        viewModelScope.launch {
            runCatching {
                val runtime = ensureRuntimeSession(current, operation)
                if (current.runtimeSessionId == null) createdDraftRuntime = runtime
                val fileRefs = uploadAttachments(runtime.runtimeId, attachments)
                val requestText = text.ifBlank { "Please inspect the attached file${if (attachments.size == 1) "" else "s"}." }
                val submittedText = (fileRefs + requestText).joinToString("\n\n")
                val displayText = buildString {
                    append(requestText)
                    if (attachments.isNotEmpty()) {
                        append("\n\n")
                        append(attachments.joinToString("\n") { "📎 ${it.name}" })
                    }
                }
                val userMessage = ChatMessage(UUID.randomUUID().toString(), "user", displayText)
                val assistant = ChatMessage("streaming-${UUID.randomUUID()}", "assistant", "", streaming = true)
                val baselineCount = current.messages.size
                turnVisible = true
                state.value = state.value.copy(
                    selectedSessionId = runtime.storedSessionId,
                    runtimeSessionId = runtime.runtimeId,
                    messages = state.value.messages + userMessage + assistant,
                    composerText = "",
                    pendingAttachments = emptyList(),
                    sending = true,
                    error = null,
                )
                scheduleTurnRecovery(
                    TurnRecovery(
                        baseUrl = current.baseUrl,
                        storedSessionId = runtime.storedSessionId,
                        profile = current.selectedProfile,
                        promptText = submittedText,
                        expectedMinimumMessageCount = baselineCount + 2,
                        chatGeneration = operation,
                    ),
                )
                gateway.request(
                    "prompt.submit",
                    JSONObject().put("session_id", runtime.runtimeId).put("text", submittedText),
                )
            }.onFailure { error ->
                if (operation != chatGeneration) return@onFailure
                if (!turnVisible) {
                    createdDraftRuntime?.let { runtime ->
                        state.value = state.value.copy(runtimeSessionId = null, selectedSessionId = "new")
                        viewModelScope.launch {
                            runCatching {
                                withTimeout(3_000) {
                                    gateway.request("session.close", JSONObject().put("session_id", runtime.runtimeId))
                                }
                            }
                        }
                    }
                }
                if (error is HermesRpcException) {
                    clearTurnRecovery()
                    state.value = state.value.copy(sending = false)
                    showError(error)
                } else if (!gateway.isConnected() && turnRecovery != null) {
                    onGatewayDisconnected(error, operation)
                } else {
                    clearTurnRecovery()
                    state.value = state.value.copy(sending = false)
                    showError(error)
                }
            }
        }
    }

    private suspend fun ensureRuntimeSession(current: HermesUiState, operation: Long): RuntimeSession {
        current.runtimeSessionId?.takeIf(String::isNotBlank)?.let { runtimeId ->
            return RuntimeSession(runtimeId, current.selectedSessionId.orEmpty())
        }
        if (!gateway.isConnected()) connectGateway(operation)
        val params = JSONObject()
            .put("cols", 96)
            .put("source", "android")
            .put("close_on_disconnect", false)
            .apply {
                current.selectedCwd.takeIf(String::isNotBlank)?.let { put("cwd", it) }
                current.selectedProfile.takeIf(String::isNotBlank)?.let { put("profile", it) }
                current.selectedModel.takeIf(String::isNotBlank)?.let { put("model", it) }
                current.selectedProvider.takeIf(String::isNotBlank)?.let { put("provider", it) }
            }
        val response = gateway.request("session.create", params)
        if (operation != chatGeneration) throw CancellationException("Chat selection changed")
        val runtimeId = response.optString("session_id").ifBlank { error("Hermes returned no runtime session ID") }
        val storedId = response.optString("stored_session_id").ifBlank { "new" }
        state.value = state.value.copy(runtimeSessionId = runtimeId, selectedSessionId = storedId)
        return RuntimeSession(runtimeId, storedId)
    }

    private suspend fun uploadAttachments(
        runtimeId: String,
        attachments: List<PendingAttachment>,
    ): List<String> {
        if (attachments.isEmpty()) return emptyList()
        val resolver = getApplication<Application>().contentResolver
        val refs = mutableListOf<String>()
        var totalBytes = 0L
        for (attachment in attachments) {
            val safeName = attachment.name.safeAttachmentName()
            val isImage = attachment.mimeType.startsWith("image/") || safeName.hasImageExtension()
            val byteLimit = if (isImage) MAX_IMAGE_BYTES else MAX_ATTACHMENT_BYTES
            val bytes = withContext(Dispatchers.IO) {
                resolver.openInputStream(Uri.parse(attachment.uri))?.use { input ->
                    input.readLimitedBytes(byteLimit, safeName)
                } ?: error("Could not read $safeName")
            }
            totalBytes += bytes.size
            if (totalBytes > MAX_TOTAL_ATTACHMENT_BYTES) error("Attachments exceed the 100 MB total upload limit")
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            if (isImage) {
                gateway.request(
                    "image.attach_bytes",
                    JSONObject()
                        .put("session_id", runtimeId)
                        .put("filename", safeName)
                        .put("content_base64", encoded),
                )
            } else {
                val response = gateway.request(
                    "file.attach",
                    JSONObject()
                        .put("session_id", runtimeId)
                        .put("path", safeName)
                        .put("name", safeName)
                        .put("data_url", "data:${attachment.mimeType};base64,$encoded"),
                )
                response.optString("ref_text").takeIf(String::isNotBlank)?.let(refs::add)
            }
        }
        return refs
    }

    fun cancelOAuth() {
        nativeOAuthFlow?.callbackServer?.close()
        nativeOAuthFlow = null
        state.value = state.value.copy(oauthUrl = null, loading = false)
    }

    fun disconnect() {
        chatGeneration++
        modelSelectionGeneration++
        clearTurnRecovery()
        nativeOAuthFlow?.callbackServer?.close()
        nativeOAuthFlow = null
        gateway.close()
        api.clearSession(state.value.baseUrl)
        store.clearTokens()
        val baseUrl = state.value.baseUrl
        state.value = HermesUiState(baseUrl = baseUrl)
    }

    fun connect() {
        val current = state.value
        if (current.loading) return
        val normalizedTarget = runCatching { HermesApi.normalizeBaseUrl(current.baseUrl) }.getOrElse {
            showError(it)
            return
        }
        val previousBaseUrl = store.baseUrl
        if (previousBaseUrl.isNotBlank() && !HermesApi.sameOrigin(previousBaseUrl, normalizedTarget)) {
            api.clearSession(previousBaseUrl)
            store.clearTokens()
        }
        state.value = current.copy(baseUrl = normalizedTarget, loading = true, error = null)
        viewModelScope.launch {
            runCatching { api.probe(normalizedTarget) }
                .onSuccess { probe ->
                    store.baseUrl = normalizedTarget
                    if (probe.status.authRequired) {
                        val existingSession = runCatching { api.load(normalizedTarget) }
                        existingSession.onSuccess { snapshot ->
                            state.value = state.value.copy(
                                loading = false,
                                connected = true,
                                needsAuthentication = false,
                                status = snapshot.status,
                                sessions = snapshot.sessions,
                                profiles = snapshot.status.profiles.map(::HermesProfile).ifEmpty { listOf(HermesProfile("default")) },
                                projects = mergeProjects(emptyList(), snapshot.sessions),
                                error = null,
                            )
                        }.onFailure { error ->
                            if (error is HermesAuthenticationRequired) {
                                state.value = state.value.copy(
                                    loading = false,
                                    connected = false,
                                    status = probe.status,
                                    providers = probe.providers,
                                    needsAuthentication = true,
                                    error = if (probe.providers.isEmpty()) "Hermes requires login but advertised no providers" else null,
                                )
                            } else {
                                // A provider outage or transient server error is
                                // not proof that the session expired. Keep the
                                // login screen hidden and offer a normal retry.
                                state.value = state.value.copy(needsAuthentication = false)
                                showError(error)
                            }
                        }
                    } else loadSnapshot()
                }
                .onFailure(::showError)
        }
    }

    fun beginOAuth(provider: AuthProvider) {
        val current = state.value
        if (current.loading) return
        state.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching {
                val flow = api.prepareNativeOAuth(current.baseUrl, provider.name)
                nativeOAuthFlow = flow
                state.value = state.value.copy(oauthUrl = flow.authorizationUrl)
                api.completeNativeOAuth(current.baseUrl, flow)
            }.onSuccess { snapshot ->
                nativeOAuthFlow = null
                state.value = state.value.copy(
                    oauthUrl = null,
                    loading = false,
                    connected = true,
                    needsAuthentication = false,
                    status = snapshot.status,
                    sessions = snapshot.sessions,
                    profiles = snapshot.status.profiles.map(::HermesProfile).ifEmpty { listOf(HermesProfile("default")) },
                    projects = mergeProjects(emptyList(), snapshot.sessions),
                )
            }.onFailure { error ->
                val cancelled = nativeOAuthFlow == null
                nativeOAuthFlow = null
                state.value = state.value.copy(oauthUrl = null, loading = false)
                if (!cancelled) showError(error)
            }
        }
    }

    private suspend fun connectGateway(operation: Long) {
        val baseUrl = state.value.baseUrl
        val ticket = api.mintWsTicket(baseUrl)
        if (operation != chatGeneration) throw CancellationException("Chat selection changed")
        gateway.connect(
            baseUrl,
            ticket,
            { event -> onGatewayEvent(event, operation) },
            { error -> onGatewayDisconnected(error, operation) },
        )
        if (operation != chatGeneration) throw CancellationException("Chat selection changed")
        gatewayDisconnected = false
        disconnectError = null
    }

    private fun onGatewayDisconnected(error: Throwable, operation: Long) {
        viewModelScope.launch {
            if (operation != chatGeneration || !state.value.sending || turnRecovery == null) return@launch
            disconnectError = error
            if (gatewayDisconnected) return@launch
            gatewayDisconnected = true
            startTurnRecovery(initialDelayMillis = 0)
        }
    }

    private fun onGatewayEvent(event: GatewayEvent, operation: Long) {
        viewModelScope.launch {
            if (operation != chatGeneration) return@launch
            val current = state.value
            if (event.sessionId.isNotBlank() && current.runtimeSessionId != null && event.sessionId != current.runtimeSessionId) return@launch
            when (event.type) {
                "message.delta" -> {
                    val delta = event.payload.optString("text").ifBlank { event.payload.optString("delta") }
                    if (delta.isNotEmpty()) updateStreamingMessage { it + delta }
                }
                "message.complete" -> {
                    val finalText = event.payload.optString("text").ifBlank { event.payload.optString("rendered") }
                    clearTurnRecovery()
                    state.value = state.value.copy(sending = false, error = null)
                    updateStreamingMessage { existing -> finalText.ifBlank { existing } }
                }
                "tool.start" -> {
                    val name = event.payload.optString("name", "tool")
                    state.value = state.value.copy(messages = state.value.messages + ChatMessage("tool-${UUID.randomUUID()}", "tool", "Running $name…"))
                }
                "session.info" -> {
                    state.value = state.value.copy(
                        selectedModel = event.payload.optString("model").ifBlank { state.value.selectedModel },
                        selectedProvider = event.payload.optString("provider").ifBlank { state.value.selectedProvider },
                        selectedProfile = event.payload.optString("profile_name").ifBlank { state.value.selectedProfile },
                        selectedCwd = event.payload.optString("cwd").ifBlank { state.value.selectedCwd },
                    )
                }
                "error" -> {
                    clearTurnRecovery()
                    state.value = state.value.copy(sending = false, error = event.payload.optString("message", "Hermes returned an error"))
                }
            }
        }
    }

    private fun updateStreamingMessage(transform: (String) -> String) {
        val messages = state.value.messages.toMutableList()
        val index = messages.indexOfLast { it.role == "assistant" && it.streaming }
        if (index >= 0) {
            val old = messages[index]
            messages[index] = old.copy(text = transform(old.text), streaming = state.value.sending)
        } else {
            messages += ChatMessage("assistant-${UUID.randomUUID()}", "assistant", transform(""), streaming = state.value.sending)
        }
        state.value = state.value.copy(messages = messages)
    }

    private fun scheduleTurnRecovery(recovery: TurnRecovery) {
        turnRecovery = recovery
        gatewayDisconnected = false
        disconnectError = null
        startTurnRecovery(initialDelayMillis = RECOVERY_POLL_INTERVAL_MILLIS)
    }

    private fun startTurnRecovery(initialDelayMillis: Long) {
        val recovery = turnRecovery ?: return
        turnRecoveryJob?.cancel()
        turnRecoveryJob = viewModelScope.launch {
            if (initialDelayMillis > 0) delay(initialDelayMillis)
            var polls = 0
            while (
                isActive &&
                state.value.sending &&
                turnRecovery === recovery &&
                recovery.chatGeneration == chatGeneration
            ) {
                if (gatewayDisconnected) {
                    val resumed = runCatching { reconnectAndResume(recovery) }
                    if (resumed.isSuccess) {
                        gatewayDisconnected = false
                        disconnectError = null
                        if (applyResumeRecovery(resumed.getOrThrow(), recovery)) return@launch
                    } else {
                        gatewayDisconnected = true
                        disconnectError = resumed.exceptionOrNull()
                    }
                }

                val stored = runCatching {
                    api.loadMessages(
                        recovery.baseUrl,
                        recovery.storedSessionId,
                        recovery.profile,
                    )
                }.getOrNull()
                val recovered = stored?.let {
                    findRecoveredAssistant(it, recovery.expectedMinimumMessageCount, recovery.promptText)
                }
                if (recovered != null) {
                    finishRecoveredTurn(stored, recovery)
                    return@launch
                }
                polls++
                if (polls >= MAX_RECOVERY_POLLS) {
                    failTurnRecovery(disconnectError)
                    return@launch
                }
                delay(RECOVERY_POLL_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun reconnectAndResume(recovery: TurnRecovery): JSONObject {
        require(recovery.storedSessionId.isNotBlank() && recovery.storedSessionId != "new") {
            "Hermes did not provide a stored session ID for recovery"
        }
        connectGateway(recovery.chatGeneration)
        return gateway.request(
            "session.resume",
            JSONObject()
                .put("session_id", recovery.storedSessionId)
                .put("cols", 96)
                .put("source", "android")
                .put("close_on_disconnect", false)
                .apply { if (recovery.profile.isNotBlank()) put("profile", recovery.profile) },
        )
    }

    private fun applyResumeRecovery(response: JSONObject, recovery: TurnRecovery): Boolean {
        val runtimeId = response.optString("session_id")
        if (runtimeId.isNotBlank()) state.value = state.value.copy(runtimeSessionId = runtimeId)

        val resumedMessages = HermesApi.parseMessages(response)
        val recovered = findRecoveredAssistant(
            resumedMessages,
            recovery.expectedMinimumMessageCount,
            recovery.promptText,
        )
        if (recovered != null && !response.optBoolean("running", false)) {
            finishRecoveredTurn(resumedMessages, recovery)
            return true
        }

        val inflight = response.optJSONObject("inflight")
        val inflightText = inflight?.optString("assistant").orEmpty()
        if (inflightText.isNotBlank()) updateStreamingMessage { inflightText }
        return false
    }

    private fun finishRecoveredTurn(storedMessages: List<ChatMessage>, recovery: TurnRecovery) {
        val messages = mergeRecoveredTranscript(state.value.messages, storedMessages, recovery.promptText)
            .map { it.copy(streaming = false) }
        state.value = state.value.copy(messages = messages, sending = false, error = null)
        clearTurnRecovery(cancelJob = false)
    }

    private fun failTurnRecovery(error: Throwable?) {
        state.value = state.value.copy(
            sending = false,
            error = error?.message ?: "Hermes response could not be recovered",
        )
        updateStreamingMessage { it }
        clearTurnRecovery(cancelJob = false)
    }

    private fun clearTurnRecovery(cancelJob: Boolean = true) {
        if (cancelJob) turnRecoveryJob?.cancel()
        turnRecoveryJob = null
        turnRecovery = null
        gatewayDisconnected = false
        disconnectError = null
    }

    private suspend fun loadSnapshot() {
        runCatching { api.load(state.value.baseUrl) }
            .onSuccess { snapshot ->
                state.value = state.value.copy(
                    loading = false,
                    connected = true,
                    status = snapshot.status,
                    sessions = snapshot.sessions,
                    profiles = snapshot.status.profiles.map(::HermesProfile).ifEmpty { listOf(HermesProfile("default")) },
                    projects = mergeProjects(emptyList(), snapshot.sessions),
                    error = null,
                )
            }
            .onFailure(::showError)
    }

    private fun showError(error: Throwable) {
        state.value = state.value.copy(
            loading = false,
            loadingChat = false,
            loadingOptions = false,
            error = error.message ?: "Connection failed",
        )
    }

    private fun preferredModel(options: List<ModelOption>, profile: String): ModelOption? {
        options.firstOrNull { it.available && it.isProfileDefault }?.let { return it }
        val recentModel = state.value.sessions
            .filter { it.profile.ifBlank { "default" } == profile }
            .maxByOrNull { it.lastActive }
            ?.model
        return options.firstOrNull { it.available && it.model == recentModel }
            ?: options.firstOrNull { it.available }
    }

    private fun parseProjects(json: JSONObject): List<HermesProject> {
        val array = json.optJSONArray("projects") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val name = item.optString("name")
            if (id.isBlank() || name.isBlank() || item.optBoolean("archived", false)) return@mapNotNull null
            val foldersJson = item.optJSONArray("folders")
            val folders = if (foldersJson == null) emptyList() else (0 until foldersJson.length()).mapNotNull { folderIndex ->
                val folder = foldersJson.optJSONObject(folderIndex) ?: return@mapNotNull null
                folder.optString("path").takeIf(String::isNotBlank)?.let { path ->
                    HermesProjectFolder(
                        path = path,
                        label = folder.optString("label"),
                        isPrimary = folder.optBoolean("is_primary", false),
                    )
                }
            }
            HermesProject(id, name, item.optString("primary_path"), folders)
        }
    }

    private fun mergeProjects(projects: List<HermesProject>, sessions: List<HermesSession>): List<HermesProject> {
        val result = projects.toMutableList()
        sessions.filter { it.cwd.isNotBlank() }.groupBy { it.cwd }.forEach { (cwd, rows) ->
            val covered = result.any { project -> project.selectableFolders.any { it.path.equals(cwd, ignoreCase = true) } }
            if (!covered) {
                result += HermesProject(
                    id = "session:$cwd",
                    name = rows.first().projectName,
                    primaryPath = cwd,
                    folders = listOf(HermesProjectFolder(cwd, isPrimary = true)),
                )
            }
        }
        return result.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    override fun onCleared() {
        clearTurnRecovery()
        nativeOAuthFlow?.callbackServer?.close()
        nativeOAuthFlow = null
        gateway.close()
        super.onCleared()
    }
}

private fun InputStream.readLimitedBytes(limit: Long, name: String): ByteArray {
    val output = ByteArrayOutputStream(minOf(limit, 64 * 1024L).toInt())
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > limit) {
            val megabytes = limit / (1024 * 1024)
            error("$name is larger than the $megabytes MB upload limit")
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun String.safeAttachmentName(): String {
    val basename = replace('\\', '/').substringAfterLast('/')
    val sanitized = basename.map { character ->
        if (character.isISOControl() || character in "/\\:*?\"<>|") '_' else character
    }.joinToString("").trim().trimStart('.').take(128)
    return sanitized.ifBlank { "attachment" }
}

private fun String.hasImageExtension(): Boolean {
    val extension = substringAfterLast('.', "").lowercase()
    return extension in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
}

private fun Cursor.stringColumn(name: String): String {
    val index = getColumnIndex(name)
    return if (index >= 0 && !isNull(index)) getString(index).orEmpty() else ""
}

private fun Cursor.longColumn(name: String): Long {
    val index = getColumnIndex(name)
    return if (index >= 0 && !isNull(index)) getLong(index) else -1L
}

private data class SessionOpenResult(
    val response: JSONObject,
    val messages: List<ChatMessage>,
    val modelOptions: List<ModelOption>,
)

private data class NewChatOptions(
    val profiles: List<HermesProfile>,
    val models: List<ModelOption>,
    val projects: List<HermesProject>,
)

private data class RuntimeSession(
    val runtimeId: String,
    val storedSessionId: String,
)

data class ProfileDefaultConfirmation(
    val option: ModelOption,
    val message: String,
)

internal data class TurnRecovery(
    val baseUrl: String,
    val storedSessionId: String,
    val profile: String,
    val promptText: String,
    val expectedMinimumMessageCount: Int,
    val chatGeneration: Long,
)

internal fun findRecoveredAssistant(
    messages: List<ChatMessage>,
    expectedMinimumMessageCount: Int,
    promptText: String,
): ChatMessage? {
    if (messages.size < expectedMinimumMessageCount) return null
    val userIndex = messages.indexOfLast { it.role == "user" && it.text.trim() == promptText.trim() }
    if (userIndex < 0) return null
    val terminalMessage = messages.subList(userIndex + 1, messages.size)
        .lastOrNull { it.text.isNotBlank() }
    return terminalMessage?.takeIf { it.role == "assistant" && !it.hasToolCalls }
}

internal fun mergeRecoveredTranscript(
    localMessages: List<ChatMessage>,
    storedMessages: List<ChatMessage>,
    promptText: String,
): List<ChatMessage> {
    if (storedMessages.isEmpty()) return localMessages
    val localUserIndex = localMessages.indexOfLast { it.role == "user" && it.text.trim() == promptText.trim() }
    val storedUserIndex = storedMessages.indexOfLast { it.role == "user" && it.text.trim() == promptText.trim() }
    return if (localUserIndex >= 0 && storedUserIndex >= 0) {
        localMessages.take(localUserIndex) + storedMessages.drop(storedUserIndex)
    } else {
        storedMessages
    }
}

private const val RECOVERY_POLL_INTERVAL_MILLIS = 2_000L
private const val MAX_RECOVERY_POLLS = 150
private const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
private const val MAX_ATTACHMENT_BYTES = 50L * 1024 * 1024
private const val MAX_TOTAL_ATTACHMENT_BYTES = 100L * 1024 * 1024
private const val MAX_ATTACHMENT_COUNT = 10
