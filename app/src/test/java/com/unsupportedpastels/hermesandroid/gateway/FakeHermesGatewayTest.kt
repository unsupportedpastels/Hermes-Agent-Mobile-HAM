package com.unsupportedpastels.hermesandroid.gateway

import app.cash.turbine.test
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FakeHermesGatewayTest {
    @Test
    fun startsDisconnectedWithNoDurableOrRuntimeSessions() = runTest {
        val gateway = FakeHermesGateway()

        gateway.snapshots.test {
            val initial = awaitItem()
            assertEquals(ConnectionState.Disconnected, initial.connectionState)
            assertEquals(emptyList<SessionSummary>(), initial.durableSessions)
            assertEquals(emptyList<ActiveRuntimeSession>(), initial.activeRuntimes)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun replacingSnapshotPublishesTheReconciledState() = runTest {
        val gateway = FakeHermesGateway()

        gateway.snapshots.test {
            assertEquals(ConnectionState.Disconnected, awaitItem().connectionState)

            gateway.replaceSnapshot(
                HermesGatewaySnapshot(connectionState = ConnectionState.Recovering),
            )

            assertEquals(ConnectionState.Recovering, awaitItem().connectionState)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun activeRuntimeDefaultsToObserverAndKeepsRuntimeIdentitySeparate() = runTest {
        val durableId = DurableSessionId("stored-1")
        val runtime = ActiveRuntimeSession(
            runtimeSessionId = RuntimeSessionId("runtime-1"),
            durableSessionId = durableId,
            title = "Running turn",
        )
        val gateway = FakeHermesGateway()

        gateway.replaceSnapshot(
            HermesGatewaySnapshot(
                connectionState = ConnectionState.Connected,
                durableSessions = listOf(SessionSummary(durableId, "Saved transcript")),
                activeRuntimes = listOf(runtime),
            ),
        )

        val current = gateway.refresh()
        assertEquals(RuntimeAccess.Observer, current.activeRuntimes.single().access)
        assertEquals("runtime-1", current.activeRuntimes.single().runtimeSessionId.value)
        assertEquals("stored-1", current.activeRuntimes.single().durableSessionId?.value)
    }

    @Test
    fun runtimeMayExistWithoutDurableTranscriptIdentity() = runTest {
        val gateway = FakeHermesGateway(
            HermesGatewaySnapshot(
                connectionState = ConnectionState.Connected,
                activeRuntimes = listOf(
                    ActiveRuntimeSession(
                        runtimeSessionId = RuntimeSessionId("runtime-only"),
                        title = "Ephemeral turn",
                    ),
                ),
            ),
        )

        assertNull(gateway.refresh().activeRuntimes.single().durableSessionId)
    }
}
