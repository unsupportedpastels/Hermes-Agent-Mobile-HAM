package com.unsupportedpastels.hermesandroid.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSettingsViewModelTest {
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
    fun saveContinuesWhenAwaitingUiIsCancelled() = runTest(dispatcher) {
        val repository = PausingServerSettingsRepository()
        val viewModel = ServerSettingsViewModel(repository)
        val origin = ServerOrigin.parse("https://hermes.example")

        val request = viewModel.save(origin)
        runCurrent()
        assertFalse(request.isCompleted)

        val uiWaiter = launch { request.await() }
        runCurrent()
        uiWaiter.cancel()
        repository.allowSave.complete(Unit)
        advanceUntilIdle()

        assertTrue(request.await().isSuccess)
        assertEquals(origin, repository.savedOrigin)
    }
}

private class PausingServerSettingsRepository : ServerSettingsRepository {
    val allowSave = CompletableDeferred<Unit>()
    var savedOrigin: ServerOrigin? = null
    override val states: Flow<ServerSettingsState> = flowOf(ServerSettingsState.Ready(null))

    override suspend fun save(serverOrigin: ServerOrigin) {
        allowSave.await()
        savedOrigin = serverOrigin
    }
}
