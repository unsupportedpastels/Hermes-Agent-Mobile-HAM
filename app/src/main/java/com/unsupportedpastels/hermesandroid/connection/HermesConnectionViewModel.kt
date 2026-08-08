package com.unsupportedpastels.hermesandroid.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

class HermesConnectionViewModel(
    settingsStates: Flow<ServerSettingsState>,
    private val client: HermesConnectionClient,
    private val nativeLogin: NativeLogin? = null,
    private val closeResources: () -> Unit = {},
) : ViewModel() {
    private val mutableSnapshots = MutableStateFlow(HermesGatewaySnapshot())
    val snapshots: StateFlow<HermesGatewaySnapshot> = mutableSnapshots.asStateFlow()

    private var activeOrigin: ServerOrigin? = null
    private var generation = 0L
    private var signInJob: Job? = null

    init {
        viewModelScope.launch {
            settingsStates.collectLatest { settingsState ->
                val currentGeneration = ++generation
                signInJob?.cancel()
                activeOrigin = (settingsState as? ServerSettingsState.Ready)?.serverOrigin
                when (settingsState) {
                    ServerSettingsState.Loading -> {
                        mutableSnapshots.value = HermesGatewaySnapshot()
                    }
                    ServerSettingsState.Unavailable -> {
                        mutableSnapshots.value = HermesGatewaySnapshot(
                            connectionError = "Server settings unavailable",
                        )
                    }
                    is ServerSettingsState.Ready -> connect(
                        settingsState.serverOrigin,
                        currentGeneration,
                    )
                }
            }
        }
    }

    private suspend fun connect(serverOrigin: ServerOrigin?, currentGeneration: Long) {
        if (serverOrigin == null) {
            mutableSnapshots.value = HermesGatewaySnapshot()
            return
        }

        mutableSnapshots.value = HermesGatewaySnapshot(
            connectionState = ConnectionState.Connecting,
        )
        try {
            val info = client.probe(serverOrigin)
            currentCoroutineContext().ensureActive()
            if (generation != currentGeneration || activeOrigin != serverOrigin) return
            mutableSnapshots.value = HermesGatewaySnapshot(
                connectionState = ConnectionState.Connected,
                authenticationState = if (info.authRequired) {
                    AuthenticationState.SignInRequired
                } else {
                    AuthenticationState.NotRequired
                },
                serverVersion = info.version,
                nativeOAuthSupported = info.nativeOAuthSupported,
                authProviders = info.providers,
                durableSessions = info.sessions,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
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
            ) {
                return@launch
            }

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

    override fun onCleared() {
        signInJob?.cancel()
        closeResources()
    }

    class Factory(
        private val settingsStates: Flow<ServerSettingsState>,
        private val client: HermesConnectionClient,
        private val nativeLogin: NativeLogin? = null,
        private val closeResources: () -> Unit = {},
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HermesConnectionViewModel::class.java))
            return HermesConnectionViewModel(
                settingsStates,
                client,
                nativeLogin,
                closeResources,
            ) as T
        }
    }

    class ProductionFactory(
        private val settingsStates: Flow<ServerSettingsState>,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HermesConnectionViewModel::class.java))
            val httpClient = HttpClient(OkHttp) {
                configureHermesHttpClient()
            }
            return HermesConnectionViewModel(
                settingsStates = settingsStates,
                client = HttpHermesConnectionClient(httpClient),
                nativeLogin = HermesNativeLogin(HttpHermesNativeAuthClient(httpClient)),
                closeResources = httpClient::close,
            ) as T
        }
    }
}
