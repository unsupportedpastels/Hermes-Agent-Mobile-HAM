package com.unsupportedpastels.hermesandroid.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal object HermesWindowFocus {
    val state = MutableStateFlow(false)
}

internal suspend fun launchBrowserAndAwaitReturn(
    windowFocused: StateFlow<Boolean>,
    launchBrowser: () -> Unit,
) {
    withContext(Dispatchers.Main.immediate) { launchBrowser() }
    windowFocused.first { focused -> !focused }
    windowFocused.first { focused -> focused }
}
