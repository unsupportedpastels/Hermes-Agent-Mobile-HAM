package com.unsupportedpastels.hermesandroid.navigation

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
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
}
