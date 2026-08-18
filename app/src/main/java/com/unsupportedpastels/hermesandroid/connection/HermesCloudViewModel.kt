package com.unsupportedpastels.hermesandroid.connection

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Presentation state for the Hermes Cloud connect surface. This is a distinct
 * concern from [ServerSettingsState] (which owns the manual server-URL catalog):
 * once the user picks an agent, its `dashboardUrl` is saved as an ordinary
 * server origin and the existing dashboard `native_pkce` sign-in flow takes over
 * unchanged.
 */
sealed interface CloudConnectState {
    /** No Portal session yet — show the "Sign in to Hermes Cloud" action. */
    data object SignedOut : CloudConnectState

    /**
     * A device-code sign-in is in progress. [userCode] and [verificationUri] are
     * shown so the user can confirm the code the browser opened to.
     */
    data class SigningIn(
        val userCode: String,
        val verificationUri: String,
    ) : CloudConnectState

    /** Signed in; loading the agent roster. */
    data object Discovering : CloudConnectState

    /** Multi-org account: the user must pick which org's agents to list. */
    data class SelectOrg(
        val orgs: List<CloudOrg>,
    ) : CloudConnectState

    /** Signed in with the agent roster resolved. */
    data class Agents(
        val agents: List<CloudAgent>,
        val org: CloudOrg?,
    ) : CloudConnectState

    /** A non-fatal failure with a retry affordance; still signed in unless [signedOut]. */
    data class Error(
        val message: String,
        val signedOut: Boolean,
    ) : CloudConnectState
}

/**
 * Owns Hermes Cloud sign-in (Portal device-code OAuth) and agent discovery.
 *
 * The Portal access token is persisted encrypted and refreshed silently; only a
 * terminal grant rejection ([HermesCloudSignInRequiredException]) drops to
 * [CloudConnectState.SignedOut]. Selecting an agent hands off to
 * [onAgentSelected] with the agent's dashboard [ServerOrigin], which the caller
 * saves so the existing dashboard sign-in flow connects to it.
 */
class HermesCloudViewModel(
    private val client: HermesCloudClient,
    private val tokenStore: PortalTokenStore,
    private val portalOrigin: ServerOrigin = ServerOrigin.parse(DEFAULT_NOUS_PORTAL_ORIGIN),
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val closeResources: () -> Unit = {},
) : ViewModel() {
    private val mutableState = MutableStateFlow<CloudConnectState>(CloudConnectState.SignedOut)
    val state: StateFlow<CloudConnectState> = mutableState.asStateFlow()

    private var activeJob: Job? = null
    private var selectedOrg: String? = null

    /**
     * Attempt a silent resume: if a Portal token is persisted, refresh it if
     * needed and discover agents without any user interaction. Leaves the state
     * at [CloudConnectState.SignedOut] when there is nothing to resume, so the
     * connect screen simply offers sign-in.
     */
    fun resume() {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            val stored = tokenStore.load(portalOrigin) ?: run {
                mutableState.value = CloudConnectState.SignedOut
                return@launch
            }
            mutableState.value = CloudConnectState.Discovering
            runCatching { discoverWithValidToken(stored) }
                .onFailure { error -> publishError(error) }
        }
    }

    /**
     * Start the interactive device-code flow. [openBrowser] receives the
     * verification URL to launch (system browser / custom tab); the returned Job
     * completes when discovery resolves or fails.
     */
    fun signIn(openBrowser: suspend (String) -> Unit): Job {
        activeJob?.cancel()
        val job = viewModelScope.launch {
            try {
                val device = client.requestDeviceCode(portalOrigin)
                mutableState.value = CloudConnectState.SigningIn(
                    userCode = device.userCode,
                    verificationUri = device.verificationUriComplete,
                )
                launch { openBrowser(device.verificationUriComplete) }
                val tokens = client.awaitDeviceToken(portalOrigin, device)
                tokenStore.save(portalOrigin, tokens)
                mutableState.value = CloudConnectState.Discovering
                discover(tokens.accessToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                publishError(error)
            }
        }
        activeJob = job
        return job
    }

    /** Re-run discovery scoped to a chosen org (from [CloudConnectState.SelectOrg]). */
    fun selectOrg(org: CloudOrg) {
        selectedOrg = org.slug ?: org.id
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            val stored = tokenStore.load(portalOrigin) ?: run {
                mutableState.value = CloudConnectState.SignedOut
                return@launch
            }
            mutableState.value = CloudConnectState.Discovering
            runCatching { discoverWithValidToken(stored) }
                .onFailure { error -> publishError(error) }
        }
    }

    /** Reload the agent roster with the persisted token. */
    fun refresh() = resume()

    /** Clear the persisted Portal session and return to the signed-out state. */
    fun signOut() {
        activeJob?.cancel()
        selectedOrg = null
        activeJob = viewModelScope.launch {
            runCatching { tokenStore.clear(portalOrigin) }
            mutableState.value = CloudConnectState.SignedOut
        }
    }

    private suspend fun discoverWithValidToken(stored: PortalTokenSet) {
        // Refresh proactively when the access token is expired/near-expiry so
        // discovery does not 401 into a re-login the refresh token could heal.
        val token = if (stored.expiresAt in 1 until (nowSeconds() + EXPIRY_SKEW_SECONDS)) {
            val refreshed = client.refreshToken(portalOrigin, stored.refreshToken)
            tokenStore.save(portalOrigin, refreshed)
            refreshed.accessToken
        } else {
            stored.accessToken
        }
        try {
            discover(token)
        } catch (signInRequired: HermesCloudSignInRequiredException) {
            // A live token still rejected: try one refresh before giving up.
            val refreshed = client.refreshToken(portalOrigin, stored.refreshToken)
            tokenStore.save(portalOrigin, refreshed)
            discover(refreshed.accessToken)
        }
    }

    private suspend fun discover(accessToken: String) {
        when (val result = client.discoverAgents(portalOrigin, accessToken, selectedOrg)) {
            is CloudDiscoverResult.Agents -> {
                selectedOrg = result.org?.slug ?: result.org?.id ?: selectedOrg
                mutableState.value = CloudConnectState.Agents(result.agents, result.org)
            }
            is CloudDiscoverResult.NeedsOrgSelection -> {
                mutableState.value = CloudConnectState.SelectOrg(result.orgs)
            }
        }
    }

    private suspend fun publishError(error: Throwable) {
        if (error is CancellationException) throw error
        val signInRequired = error is HermesCloudSignInRequiredException
        if (signInRequired) {
            runCatching { tokenStore.clear(portalOrigin) }
        }
        // A transient DNS/host failure (common right after the sign-in browser
        // backgrounds the app) is not a sign-out; surface a plain retry, keep the
        // token, and never say "could not reach" for a blip that a Retry fixes.
        val transientNetwork = !signInRequired &&
            generateSequence(error) { it.cause }.any {
                it is java.net.UnknownHostException ||
                    it is java.nio.channels.UnresolvedAddressException
            }
        val message = when {
            transientNetwork -> "Network unavailable. Tap Retry."
            else -> (error as? HermesConnectionException)?.message?.takeIf { it.isNotBlank() }
                ?: "Could not reach Hermes Cloud (${error.javaClass.simpleName})"
        }
        mutableState.value = CloudConnectState.Error(message, signedOut = signInRequired)
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { closeResources() }
    }

    private companion object {
        const val EXPIRY_SKEW_SECONDS = 60L
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val applicationContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HermesCloudViewModel::class.java))
            val httpClient = HttpClient(CIO) { configureHermesHttpClient() }
            return HermesCloudViewModel(
                client = HttpHermesCloudClient(httpClient),
                tokenStore = EncryptedPortalTokenStore(applicationContext),
                closeResources = httpClient::close,
            ) as T
        }
    }
}
