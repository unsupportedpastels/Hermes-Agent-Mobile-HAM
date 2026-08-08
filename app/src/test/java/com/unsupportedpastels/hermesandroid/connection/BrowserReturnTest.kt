package com.unsupportedpastels.hermesandroid.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserReturnTest {
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
    fun browserLaunchSuspendsUntilWindowLosesAndRegainsFocus() = runTest(dispatcher) {
        val windowFocused = MutableStateFlow(true)
        var launched = false

        val result = async {
            launchBrowserAndAwaitReturn(windowFocused) { launched = true }
        }
        runCurrent()
        assertTrue(launched)
        assertFalse(result.isCompleted)

        windowFocused.value = false
        runCurrent()
        assertFalse(result.isCompleted)

        windowFocused.value = true
        runCurrent()

        assertTrue(result.isCompleted)
    }
}
