package com.unsupportedpastels.hermesandroid.navigation

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTest {
    @Test
    fun serializedDetailRoutePreservesTypedDurableIdentity() {
        val route = SessionDetailRoute(DurableSessionId("stored-1"))

        val encoded = Json.encodeToString(SessionDetailRoute.serializer(), route)
        val decoded = Json.decodeFromString(SessionDetailRoute.serializer(), encoded)

        assertEquals(DurableSessionId("stored-1"), decoded.durableSessionId)
    }

    @Test
    fun homeRouteIsSerializable() {
        val encoded = Json.encodeToString(HomeRoute.serializer(), HomeRoute)
        val decoded = Json.decodeFromString(HomeRoute.serializer(), encoded)

        assertEquals(HomeRoute, decoded)
    }

    @Test
    fun projectRoutePreservesTypedProjectIdentity() {
        val route = ProjectRoute(ProjectId("project-1"))

        val encoded = Json.encodeToString(ProjectRoute.serializer(), route)
        val decoded = Json.decodeFromString(ProjectRoute.serializer(), encoded)

        assertEquals(ProjectId("project-1"), decoded.projectId)
    }

    @Test
    fun serverSettingsRouteIsSerializable() {
        val encoded = Json.encodeToString(ServerSettingsRoute.serializer(), ServerSettingsRoute)
        val decoded = Json.decodeFromString(ServerSettingsRoute.serializer(), encoded)

        assertEquals(ServerSettingsRoute, decoded)
    }

    @Test
    fun settingsSectionRoutesAreSerializable() {
        assertEquals(
            SettingsServersRoute,
            Json.decodeFromString(
                SettingsServersRoute.serializer(),
                Json.encodeToString(SettingsServersRoute.serializer(), SettingsServersRoute),
            ),
        )
        assertEquals(
            SettingsConnectionRoute,
            Json.decodeFromString(
                SettingsConnectionRoute.serializer(),
                Json.encodeToString(SettingsConnectionRoute.serializer(), SettingsConnectionRoute),
            ),
        )
        assertEquals(
            SettingsModelRoute,
            Json.decodeFromString(
                SettingsModelRoute.serializer(),
                Json.encodeToString(SettingsModelRoute.serializer(), SettingsModelRoute),
            ),
        )
        assertEquals(
            SettingsVoiceRoute,
            Json.decodeFromString(
                SettingsVoiceRoute.serializer(),
                Json.encodeToString(SettingsVoiceRoute.serializer(), SettingsVoiceRoute),
            ),
        )
        assertEquals(
            SettingsOfflineRoute,
            Json.decodeFromString(
                SettingsOfflineRoute.serializer(),
                Json.encodeToString(SettingsOfflineRoute.serializer(), SettingsOfflineRoute),
            ),
        )
        assertEquals(
            SettingsJobsRoute,
            Json.decodeFromString(
                SettingsJobsRoute.serializer(),
                Json.encodeToString(SettingsJobsRoute.serializer(), SettingsJobsRoute),
            ),
        )
        assertEquals(
            SettingsAccountRoute,
            Json.decodeFromString(
                SettingsAccountRoute.serializer(),
                Json.encodeToString(SettingsAccountRoute.serializer(), SettingsAccountRoute),
            ),
        )
    }
}
