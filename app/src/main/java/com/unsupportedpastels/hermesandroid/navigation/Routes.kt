package com.unsupportedpastels.hermesandroid.navigation

import androidx.navigation3.runtime.NavKey
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectId
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute : NavKey

/** Compatibility key retained while callers migrate to [HomeRoute]. */
@Serializable
data object SessionListRoute : NavKey

@Serializable
data class ProjectRoute(val projectId: ProjectId) : NavKey

@Serializable
data object ServerSettingsRoute : NavKey

@Serializable
data class SessionDetailRoute(val durableSessionId: DurableSessionId) : NavKey
