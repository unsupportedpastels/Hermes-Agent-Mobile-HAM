package com.unsupportedpastels.hermesandroid.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val ProjectIconDataStoreName = "project_icons"
private const val MaxProjectIconAssignments = 512
private val ProjectIconAssignmentsKey = stringPreferencesKey("project_icon_assignments_v1")
private val Context.projectIconDataStore by preferencesDataStore(name = ProjectIconDataStoreName)

data class ProjectIconAssignmentKey(
    val serverOrigin: ServerOrigin,
    val projectId: ProjectId,
)

sealed interface ProjectIconAssignmentsState {
    data object Loading : ProjectIconAssignmentsState
    data class Ready(
        val assignments: Map<ProjectIconAssignmentKey, ProjectIconId>,
    ) : ProjectIconAssignmentsState
    data object Unavailable : ProjectIconAssignmentsState
}

interface ProjectIconRepository {
    val assignments: Flow<ProjectIconAssignmentsState>

    suspend fun save(
        serverOrigin: ServerOrigin,
        projectId: ProjectId,
        iconId: ProjectIconId,
    )
}

class DataStoreProjectIconRepository(
    private val dataStore: DataStore<Preferences>,
    private val retryDelayMillis: Long = 1_000,
) : ProjectIconRepository {
    constructor(context: Context) : this(context.applicationContext.projectIconDataStore)

    override val assignments: Flow<ProjectIconAssignmentsState> = dataStore.data
        .map<Preferences, ProjectIconAssignmentsState> { preferences ->
            ProjectIconAssignmentsState.Ready(
                decodeAssignments(preferences[ProjectIconAssignmentsKey]),
            )
        }
        .retryWhen { error, _ ->
            if (error is IOException) {
                emit(ProjectIconAssignmentsState.Unavailable)
                delay(retryDelayMillis)
                true
            } else {
                false
            }
        }
        .onStart { emit(ProjectIconAssignmentsState.Loading) }
        .distinctUntilChanged()

    override suspend fun save(
        serverOrigin: ServerOrigin,
        projectId: ProjectId,
        iconId: ProjectIconId,
    ) {
        dataStore.edit { preferences ->
            val updated = LinkedHashMap(decodeAssignments(preferences[ProjectIconAssignmentsKey]))
            val key = ProjectIconAssignmentKey(serverOrigin, projectId)
            updated.remove(key)
            updated[key] = iconId
            while (updated.size > MaxProjectIconAssignments) {
                updated.remove(updated.keys.first())
            }
            preferences[ProjectIconAssignmentsKey] = encodeAssignments(updated)
        }
    }
}

private fun decodeAssignments(encoded: String?): Map<ProjectIconAssignmentKey, ProjectIconId> {
    if (encoded.isNullOrBlank() || encoded.length > 256_000) return emptyMap()
    val rows = runCatching { Json.parseToJsonElement(encoded).jsonArray }.getOrNull() ?: return emptyMap()
    val assignments = LinkedHashMap<ProjectIconAssignmentKey, ProjectIconId>()
    for (row in rows.take(MaxProjectIconAssignments)) {
        val values = runCatching { row.jsonObject }.getOrNull() ?: continue
        val originValue = values["origin"]?.jsonPrimitive?.contentOrNull ?: continue
        val projectValue = values["project"]?.jsonPrimitive?.contentOrNull ?: continue
        val iconValue = values["icon"]?.jsonPrimitive?.contentOrNull ?: continue
        val origin = runCatching { ServerOrigin.parse(originValue) }.getOrNull() ?: continue
        val projectId = runCatching { ProjectId(projectValue) }.getOrNull() ?: continue
        val iconId = ProjectIconId.fromPersistedValue(iconValue) ?: continue
        assignments[ProjectIconAssignmentKey(origin, projectId)] = iconId
    }
    return assignments
}

private fun encodeAssignments(
    assignments: Map<ProjectIconAssignmentKey, ProjectIconId>,
): String = buildJsonArray {
    assignments.forEach { (key, iconId) ->
        add(
            buildJsonObject {
                put("origin", key.serverOrigin.value)
                put("project", key.projectId.value)
                put("icon", iconId.persistedValue)
            },
        )
    }
}.toString()
