package com.unsupportedpastels.hermesandroid.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HermesCloudViewModelTest {
    private val portal = ServerOrigin.parse("https://portal.nousresearch.com")
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun agent(id: String, url: String?) = CloudAgent(
        id = id,
        name = id,
        status = "RUNNING",
        dashboardUrl = url,
        dashboardGatewayState = "unknown",
    )

    private class FakeStore(var token: PortalTokenSet? = null) : PortalTokenStore {
        var cleared = false
        override suspend fun load(portalOrigin: ServerOrigin) = token
        override suspend fun save(portalOrigin: ServerOrigin, tokens: PortalTokenSet) {
            token = tokens
        }
        override suspend fun clear(portalOrigin: ServerOrigin) {
            token = null
            cleared = true
        }
    }

    private class FakeClient : HermesCloudClient {
        var deviceCode = PortalDeviceCode("dc", "U-CODE", "https://verify", "https://verify?u=U-CODE", 600, 1)
        var tokenToReturn = PortalTokenSet("at", "rt", "Bearer", "inference:invoke", 0)
        var discoverResult: CloudDiscoverResult = CloudDiscoverResult.Agents(emptyList(), null)
        var discoverError: Throwable? = null
        var refreshError: Throwable? = null
        var refreshCalls = 0
        val refreshTokensSeen = mutableListOf<String>()
        var lastOrg: String? = null
        var discoverCalls = 0

        override suspend fun requestDeviceCode(portalOrigin: ServerOrigin) = deviceCode
        override suspend fun awaitDeviceToken(portalOrigin: ServerOrigin, deviceCode: PortalDeviceCode) = tokenToReturn
        override suspend fun refreshToken(portalOrigin: ServerOrigin, refreshToken: String): PortalTokenSet {
            refreshCalls += 1
            refreshTokensSeen += refreshToken
            refreshError?.let { throw it }
            // Rotate uniquely each call so a caller reusing a stale refresh token
            // is detectable (as the real reuse-detecting Portal would reject it).
            return tokenToReturn.copy(
                accessToken = "refreshed-at-$refreshCalls",
                refreshToken = "rotated-rt-$refreshCalls",
            )
        }
        override suspend fun discoverAgents(
            portalOrigin: ServerOrigin,
            accessToken: String,
            org: String?,
        ): CloudDiscoverResult {
            discoverCalls += 1
            lastOrg = org
            discoverError?.let { discoverError = null; throw it }
            return discoverResult
        }
    }

    private fun viewModel(client: HermesCloudClient, store: PortalTokenStore, now: Long = 1000L) =
        HermesCloudViewModel(client, store, portal, nowSeconds = { now })

    @Test
    fun signInPersistsTokenAndDiscoversAgents() = runTest(dispatcher) {
        val client = FakeClient().apply {
            discoverResult = CloudDiscoverResult.Agents(
                listOf(agent("a1", "https://small-9000.agents.nousresearch.com")),
                CloudOrg("o1", "slug", "Personal", true, "OWNER"),
            )
        }
        val store = FakeStore()
        val vm = viewModel(client, store)
        var openedUrl: String? = null

        vm.signIn { openedUrl = it }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("https://verify?u=U-CODE", openedUrl)
        assertEquals("at", store.token?.accessToken)
        val state = vm.state.value
        assertTrue(state is CloudConnectState.Agents)
        assertEquals("a1", (state as CloudConnectState.Agents).agents.single().id)
    }

    @Test
    fun resumeWithoutTokenStaysSignedOut() = runTest(dispatcher) {
        val vm = viewModel(FakeClient(), FakeStore(token = null))

        vm.resume()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(CloudConnectState.SignedOut, vm.state.value)
    }

    @Test
    fun resumeRefreshesExpiredTokenBeforeDiscovery() = runTest(dispatcher) {
        val client = FakeClient().apply {
            discoverResult = CloudDiscoverResult.Agents(listOf(agent("a1", "https://x.agents.nousresearch.com")), null)
        }
        // expiresAt in the past relative to now=1000 → must refresh first.
        val store = FakeStore(PortalTokenSet("old-at", "old-rt", "Bearer", "inference:invoke", 500))
        val vm = viewModel(client, store, now = 1000L)

        vm.resume()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, client.refreshCalls)
        assertEquals("refreshed-at-1", store.token?.accessToken)
        assertEquals("rotated-rt-1", store.token?.refreshToken)
        assertTrue(vm.state.value is CloudConnectState.Agents)
    }

    @Test
    fun discovery401RefreshesOnceThenSucceeds() = runTest(dispatcher) {
        val client = FakeClient().apply {
            // Valid (non-expired) token, but discovery still 401s once.
            discoverError = HermesCloudSignInRequiredException("expired")
            discoverResult = CloudDiscoverResult.Agents(listOf(agent("a1", "https://x.agents.nousresearch.com")), null)
        }
        val store = FakeStore(PortalTokenSet("at", "rt", "Bearer", "inference:invoke", 9_999_999L))
        val vm = viewModel(client, store, now = 1000L)

        vm.resume()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, client.refreshCalls)
        assertTrue(vm.state.value is CloudConnectState.Agents)
    }

    @Test
    fun proactiveRefreshThen401FallbackUsesRotatedTokenNotConsumedOne() = runTest(dispatcher) {
        // Near-expiry token → proactive refresh rotates rt → discovery still 401s
        // → the fallback refresh MUST use the rotated token, not the original
        // (which the Portal's reuse detection would reject, wrongly signing out).
        val client = FakeClient().apply {
            discoverError = HermesCloudSignInRequiredException("expired")
            discoverResult = CloudDiscoverResult.Agents(
                listOf(agent("a1", "https://x.agents.nousresearch.com")),
                null,
            )
        }
        // expiresAt=500 < now(1000)+skew → triggers the proactive refresh branch.
        val store = FakeStore(PortalTokenSet("at", "original-rt", "Bearer", "inference:invoke", 500L))
        val vm = viewModel(client, store, now = 1000L)

        vm.resume()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, client.refreshCalls)
        // First refresh sees the original; the fallback must use the rotated one.
        assertEquals("original-rt", client.refreshTokensSeen[0])
        assertEquals("rotated-rt-1", client.refreshTokensSeen[1])
        assertTrue(vm.state.value is CloudConnectState.Agents)
        // The latest rotated token is what stays persisted — never cleared.
        assertEquals("rotated-rt-2", store.token?.refreshToken)
    }

    @Test
    fun terminalRefreshFailureClearsTokenAndSignsOut() = runTest(dispatcher) {
        val client = FakeClient().apply {
            discoverError = HermesCloudSignInRequiredException("expired")
            refreshError = HermesCloudSignInRequiredException("reuse")
        }
        val store = FakeStore(PortalTokenSet("at", "rt", "Bearer", "inference:invoke", 9_999_999L))
        val vm = viewModel(client, store, now = 1000L)

        vm.resume()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(store.cleared)
        assertNull(store.token)
        val state = vm.state.value
        assertTrue(state is CloudConnectState.Error)
        assertTrue((state as CloudConnectState.Error).signedOut)
    }

    @Test
    fun orgSelectionSurfacedThenScopedDiscovery() = runTest(dispatcher) {
        val client = FakeClient().apply {
            discoverResult = CloudDiscoverResult.NeedsOrgSelection(
                listOf(CloudOrg("o1", "acme", "Acme", false, "MEMBER")),
            )
        }
        val store = FakeStore(PortalTokenSet("at", "rt", "Bearer", "inference:invoke", 9_999_999L))
        val vm = viewModel(client, store, now = 1000L)

        vm.resume()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value is CloudConnectState.SelectOrg)

        // Now pick the org; discovery should re-run scoped to its slug.
        client.discoverResult = CloudDiscoverResult.Agents(listOf(agent("a1", "https://x.agents.nousresearch.com")), null)
        vm.selectOrg(CloudOrg("o1", "acme", "Acme", false, "MEMBER"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("acme", client.lastOrg)
        assertTrue(vm.state.value is CloudConnectState.Agents)
    }

    @Test
    fun signOutClearsTokenAndState() = runTest(dispatcher) {
        val store = FakeStore(PortalTokenSet("at", "rt", "Bearer", "inference:invoke", 9_999_999L))
        val vm = viewModel(FakeClient(), store)

        vm.signOut()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(store.cleared)
        assertEquals(CloudConnectState.SignedOut, vm.state.value)
    }
}
