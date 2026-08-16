package com.unsupportedpastels.hermesandroid.app

import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProcessModelsTest {
    private val durable = DurableSessionId("durable-1")
    private val runtime = RuntimeSessionId("runtime-1")
    private val origin = "https://hermes.example"

    @Test
    fun processReducerRejectsRowsFromAStaleIdentity() {
        val current = ProcessRowsState(
            durableSessionId = durable,
            runtimeSessionId = runtime,
            origin = origin,
            originGeneration = 4,
            operationGeneration = 9,
            rows = listOf(ProcessRow("old", "server", "running")),
        )

        val unchanged = current.reduce(
            expected = ProcessListIdentity(durable, runtime, origin, 4, 9),
            incoming = ProcessListIdentity(
                durableSessionId = DurableSessionId("durable-2"),
                runtimeSessionId = RuntimeSessionId("runtime-2"),
                origin = "https://other.example",
                originGeneration = 5,
                operationGeneration = 10,
            ),
            rows = listOf(ProcessRow("new", "other", "running")),
        )

        assertSame(current, unchanged)
        assertEquals("old", unchanged.rows.single().processId)
    }

    @Test
    fun processReducerAcceptsOnlyTheCurrentBoundedIdentity() {
        val current = ProcessRowsState(
            durableSessionId = durable,
            runtimeSessionId = runtime,
            origin = origin,
            originGeneration = 4,
            operationGeneration = 9,
        )

        val updated = current.reduce(
            expected = ProcessListIdentity(durable, runtime, origin, 4, 9),
            incoming = ProcessListIdentity(durable, runtime, origin, 4, 9),
            rows = (1..(MAX_PROCESS_ROWS + 3)).map {
                ProcessRow("process-$it", "command-$it", "running")
            },
        )

        assertEquals(MAX_PROCESS_ROWS, updated.rows.size)
        assertEquals("process-1", updated.rows.first().processId)
    }
}
