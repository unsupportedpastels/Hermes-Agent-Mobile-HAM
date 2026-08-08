package com.unsupportedpastels.hermesandroid.navigation

import androidx.navigation3.runtime.NavKey
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import kotlinx.serialization.Serializable

@Serializable
data object SessionListRoute : NavKey

@Serializable
data object ServerSettingsRoute : NavKey

@Serializable
data class SessionDetailRoute(val durableSessionId: DurableSessionId) : NavKey
