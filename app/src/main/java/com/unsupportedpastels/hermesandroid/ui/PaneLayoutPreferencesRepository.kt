package com.unsupportedpastels.hermesandroid.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal const val DEFAULT_PROJECT_SESSION_PANE_PROPORTION = 0.4f
internal const val MIN_PROJECT_SESSION_PANE_PROPORTION = 0.2f
internal const val MAX_PROJECT_SESSION_PANE_PROPORTION = 0.7f

private const val PaneLayoutPreferencesDataStoreName = "pane_layout_preferences"
private val ProjectSessionPaneProportionKey =
    floatPreferencesKey("project_session_pane_proportion_v1")
private val ProjectDockStateKey = stringPreferencesKey("project_dock_state_v1")
private val Context.paneLayoutPreferencesDataStore by preferencesDataStore(
    name = PaneLayoutPreferencesDataStoreName,
)

interface PaneLayoutPreferencesRepository {
    val projectSessionPaneProportion: Flow<Float>
    val projectDockState: Flow<ProjectDockState>

    suspend fun saveProjectSessionPaneProportion(proportion: Float)
    suspend fun saveProjectDockState(state: ProjectDockState)
}

enum class ProjectDockState {
    Expanded,
    Collapsed,
    Hidden,
}

class DataStorePaneLayoutPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : PaneLayoutPreferencesRepository {
    constructor(context: Context) : this(context.applicationContext.paneLayoutPreferencesDataStore)

    override val projectSessionPaneProportion: Flow<Float> = dataStore.data.map { preferences ->
        preferences[ProjectSessionPaneProportionKey]
            ?.takeIf(Float::isFinite)
            ?.coerceIn(
                MIN_PROJECT_SESSION_PANE_PROPORTION,
                MAX_PROJECT_SESSION_PANE_PROPORTION,
            )
            ?: DEFAULT_PROJECT_SESSION_PANE_PROPORTION
    }

    override val projectDockState: Flow<ProjectDockState> = dataStore.data.map { preferences ->
        preferences[ProjectDockStateKey]
            ?.let { stored -> ProjectDockState.entries.firstOrNull { it.name == stored } }
            ?: ProjectDockState.Expanded
    }

    override suspend fun saveProjectSessionPaneProportion(proportion: Float) {
        if (!proportion.isFinite()) return
        val bounded = proportion.coerceIn(
            MIN_PROJECT_SESSION_PANE_PROPORTION,
            MAX_PROJECT_SESSION_PANE_PROPORTION,
        )
        dataStore.edit { preferences ->
            preferences[ProjectSessionPaneProportionKey] = bounded
        }
    }

    override suspend fun saveProjectDockState(state: ProjectDockState) {
        dataStore.edit { preferences ->
            preferences[ProjectDockStateKey] = state.name
        }
    }
}
