package com.unsupportedpastels.hermesandroid.app

import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId

const val MAX_PROCESS_ROWS = 50

private const val MAX_PROCESS_ID_CHARS = 256
private const val MAX_PROCESS_COMMAND_CHARS = 4_096
private const val MAX_PROCESS_STATUS_CHARS = 64
private const val MAX_PROCESS_OUTPUT_CHARS = 4_000

data class ProcessRow(
    val processId: String,
    val command: String,
    val status: String,
    val outputTail: String? = null,
    val exitCode: Int? = null,
    val uptimeSeconds: Long? = null,
) {
    init {
        require(processId.isNotBlank() && processId.length <= MAX_PROCESS_ID_CHARS)
        require(status.isNotBlank() && status.length <= MAX_PROCESS_STATUS_CHARS)
        require(command.isNotBlank() && command.length <= MAX_PROCESS_COMMAND_CHARS)
        require(outputTail == null || outputTail.length <= MAX_PROCESS_OUTPUT_CHARS)
        require(uptimeSeconds == null || uptimeSeconds >= 0)
    }
}

data class ProcessListIdentity(
    val durableSessionId: DurableSessionId,
    val runtimeSessionId: RuntimeSessionId,
    val origin: String,
    val originGeneration: Long,
    val operationGeneration: Long,
)

data class ProcessRowsState(
    val durableSessionId: DurableSessionId? = null,
    val runtimeSessionId: RuntimeSessionId? = null,
    val origin: String? = null,
    val originGeneration: Long? = null,
    val operationGeneration: Long? = null,
    val rows: List<ProcessRow> = emptyList(),
) {
    init {
        require(rows.size <= MAX_PROCESS_ROWS) { "Process rows exceed the bounded limit" }
    }

    fun reduce(
        expected: ProcessListIdentity,
        incoming: ProcessListIdentity,
        rows: List<ProcessRow>,
    ): ProcessRowsState {
        val currentIdentity = if (
            durableSessionId != null &&
            runtimeSessionId != null &&
            origin != null &&
            originGeneration != null &&
            operationGeneration != null
        ) {
            ProcessListIdentity(
                durableSessionId = durableSessionId,
                runtimeSessionId = runtimeSessionId,
                origin = origin,
                originGeneration = originGeneration,
                operationGeneration = operationGeneration,
            )
        } else {
            null
        }
        if (expected != incoming || (currentIdentity != null && currentIdentity != expected)) return this
        return copy(
            durableSessionId = incoming.durableSessionId,
            runtimeSessionId = incoming.runtimeSessionId,
            origin = incoming.origin,
            originGeneration = incoming.originGeneration,
            operationGeneration = incoming.operationGeneration,
            rows = rows.take(MAX_PROCESS_ROWS),
        )
    }

    fun clear(): ProcessRowsState = ProcessRowsState()
}
