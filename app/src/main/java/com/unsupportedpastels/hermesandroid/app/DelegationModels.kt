package com.unsupportedpastels.hermesandroid.app

data class DelegatedSubagent(
    val subagentId: String,
    val goal: String,
    val status: String,
    val parentSubagentId: String? = null,
    val startedAtEpochSeconds: Long? = null,
)

data class DelegationStatus(
    val active: List<DelegatedSubagent> = emptyList(),
    val paused: Boolean = false,
    val maxSpawnDepth: Int? = null,
    val maxConcurrentChildren: Int? = null,
)