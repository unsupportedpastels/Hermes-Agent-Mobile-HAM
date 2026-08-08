package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HermesConnectionViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun configuredOriginIsProbedAndBecomesReachableSignInRequired() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = FakeHermesConnectionClient()
        val info =
            HermesConnectionInfo(
                version = "0.20.0",
                authRequired = true,
                nativeOAuthSupported = true,
                providers = listOf(
                    HermesAuthProvider("nous", "Nous Research", supportsPassword = false),
                ),
            )
        val viewModel = HermesConnectionViewModel(settings, client)

        runCurrent()
        assertEquals(ConnectionState.Connecting, viewModel.snapshots.value.connectionState)
        client.response.complete(info)
        advanceUntilIdle()

        val snapshot = viewModel.snapshots.value
        assertEquals(ConnectionState.Connected, snapshot.connectionState)
        assertEquals(AuthenticationState.SignInRequired, snapshot.authenticationState)
        assertEquals("0.20.0", snapshot.serverVersion)
        assertTrue(snapshot.nativeOAuthSupported)
        assertEquals(listOf("nous"), snapshot.authProviders.map { it.name })
        assertEquals(listOf(origin), client.probedOrigins)
    }

    @Test
    fun serverWithoutAuthenticationPublishesProbedDurableSessions() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = FakeHermesConnectionClient()
        val viewModel = HermesConnectionViewModel(settings, client)

        runCurrent()
        client.response.complete(
            HermesConnectionInfo(
                version = "0.20.0",
                authRequired = false,
                nativeOAuthSupported = false,
                providers = emptyList(),
                sessions = listOf(
                    SessionSummary(DurableSessionId("stored-1"), "First session"),
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(AuthenticationState.NotRequired, viewModel.snapshots.value.authenticationState)
        assertEquals(
            listOf("First session"),
            viewModel.snapshots.value.durableSessions.map { it.title },
        )
    }

    @Test
    fun nativeSignInVerifiesBearerAndPublishesDurableSessions() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = AuthenticatingHermesConnectionClient()
        val login = FakeNativeLogin()
        val viewModel = HermesConnectionViewModel(settings, client, login)
        runCurrent()
        client.probeResponse.complete(
            HermesConnectionInfo(
                version = "0.20.0",
                authRequired = true,
                nativeOAuthSupported = true,
                providers = listOf(HermesAuthProvider("nous", "Nous Research")),
            ),
        )
        advanceUntilIdle()

        viewModel.signIn { }
        runCurrent()
        assertEquals(AuthenticationState.SigningIn, viewModel.snapshots.value.authenticationState)
        login.response.complete(
            NativeTokenSet(
                accessToken = "opaque-access",
                refreshToken = "opaque-refresh",
                provider = "nous",
            ),
        )
        client.authenticationResponse.complete(
            AuthenticatedHermesConnection(
                userId = "user",
                sessions = listOf(
                    SessionSummary(DurableSessionId("stored-1"), "First session"),
                ),
            ),
        )
        advanceUntilIdle()

        val snapshot = viewModel.snapshots.value
        assertEquals(AuthenticationState.Authenticated, snapshot.authenticationState)
        assertEquals(listOf("First session"), snapshot.durableSessions.map { it.title })
        assertEquals("opaque-access", client.authenticatedWith)
    }

    @Test
    fun originChangeCancelsSignInAndRejectsLateOldOriginTokens() = runTest(dispatcher) {
        val originA = ServerOrigin.parse("https://a.example")
        val originB = ServerOrigin.parse("https://b.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(originA))
        val client = SwitchingHermesConnectionClient()
        val login = LateNativeLogin()
        val viewModel = HermesConnectionViewModel(settings, client, login)
        runCurrent()
        client.probes.getValue(originA).complete(authRequiredInfo())
        advanceUntilIdle()

        viewModel.signIn { }
        runCurrent()
        settings.value = ServerSettingsState.Ready(originB)
        runCurrent()
        login.response.complete(
            NativeTokenSet(
                accessToken = "old-origin-token",
                refreshToken = "old-refresh",
                expiresAt = 1,
                provider = "nous",
                userId = "user",
            ),
        )
        runCurrent()
        client.probes.getValue(originB).complete(authRequiredInfo())
        advanceUntilIdle()

        assertTrue(login.wasCancelled)
        assertEquals(emptyList<String>(), client.authenticatedTokens)
        assertEquals(AuthenticationState.SignInRequired, viewModel.snapshots.value.authenticationState)
    }

    @Test
    fun clearingViewModelClosesOwnedNetworkResources() {
        var closed = false
        val store = ViewModelStore()
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
        ViewModelProvider(
            owner,
            HermesConnectionViewModel.Factory(
                settingsStates = MutableStateFlow(ServerSettingsState.Loading),
                client = FakeHermesConnectionClient(),
                closeResources = { closed = true },
            ),
        )[HermesConnectionViewModel::class.java]

        store.clear()

        assertTrue(closed)
    }
}

private fun authRequiredInfo() = HermesConnectionInfo(
    version = "0.20.0",
    authRequired = true,
    nativeOAuthSupported = true,
    providers = listOf(HermesAuthProvider("nous", "Nous Research")),
)

private class FakeHermesConnectionClient : HermesConnectionClient {
    val response = CompletableDeferred<HermesConnectionInfo>()
    val probedOrigins = mutableListOf<ServerOrigin>()

    override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo {
        probedOrigins += serverOrigin
        return response.await()
    }
}

private class AuthenticatingHermesConnectionClient : HermesConnectionClient {
    val probeResponse = CompletableDeferred<HermesConnectionInfo>()
    val authenticationResponse = CompletableDeferred<AuthenticatedHermesConnection>()
    var authenticatedWith: String? = null

    override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo =
        probeResponse.await()

    override suspend fun authenticate(
        serverOrigin: ServerOrigin,
        accessToken: String,
    ): AuthenticatedHermesConnection {
        authenticatedWith = accessToken
        return authenticationResponse.await()
    }
}

private class FakeNativeLogin : NativeLogin {
    val response = CompletableDeferred<NativeTokenSet>()

    override suspend fun signIn(
        serverOrigin: ServerOrigin,
        provider: String,
        openBrowser: suspend (String) -> Unit,
    ): NativeTokenSet = response.await()
}

private class SwitchingHermesConnectionClient : HermesConnectionClient {
    val probes = mutableMapOf<ServerOrigin, CompletableDeferred<HermesConnectionInfo>>()
    val authenticatedTokens = mutableListOf<String>()

    override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo =
        probes.getOrPut(serverOrigin) { CompletableDeferred() }.await()

    override suspend fun authenticate(
        serverOrigin: ServerOrigin,
        accessToken: String,
    ): AuthenticatedHermesConnection {
        authenticatedTokens += accessToken
        return AuthenticatedHermesConnection("user", emptyList())
    }
}

private class LateNativeLogin : NativeLogin {
    val response = CompletableDeferred<NativeTokenSet>()
    var wasCancelled = false

    override suspend fun signIn(
        serverOrigin: ServerOrigin,
        provider: String,
        openBrowser: suspend (String) -> Unit,
    ): NativeTokenSet = try {
        withContext(NonCancellable) { response.await() }
    } finally {
        wasCancelled = !currentCoroutineContext().isActive
    }
}
