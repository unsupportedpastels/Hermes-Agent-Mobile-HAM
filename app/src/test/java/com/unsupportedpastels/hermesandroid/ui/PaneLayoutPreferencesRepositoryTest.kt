package com.unsupportedpastels.hermesandroid.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PaneLayoutPreferencesRepositoryTest {
    @Test
    fun savedPaneProportionSurvivesRepositoryRecreation() = runTest {
        val dataStore = InMemoryPanePreferencesDataStore(emptyPreferences())
        val firstRepository = DataStorePaneLayoutPreferencesRepository(dataStore)

        firstRepository.saveProjectSessionPaneProportion(0.31f)
        val restored = DataStorePaneLayoutPreferencesRepository(dataStore)
            .projectSessionPaneProportion
            .first()

        assertEquals(0.31f, restored, 0.001f)
    }

    @Test
    fun paneProportionIsBoundedToKeepBothPanesUsable() = runTest {
        val dataStore = InMemoryPanePreferencesDataStore(emptyPreferences())
        val repository = DataStorePaneLayoutPreferencesRepository(dataStore)

        repository.saveProjectSessionPaneProportion(0.95f)

        assertEquals(0.70f, repository.projectSessionPaneProportion.first(), 0.001f)
    }

    @Test
    fun projectDockStateSurvivesRepositoryRecreation() = runTest {
        val dataStore = InMemoryPanePreferencesDataStore(emptyPreferences())
        val firstRepository = DataStorePaneLayoutPreferencesRepository(dataStore)

        firstRepository.saveProjectDockState(ProjectDockState.Collapsed)
        val restored = DataStorePaneLayoutPreferencesRepository(dataStore)
            .projectDockState
            .first()

        assertEquals(ProjectDockState.Collapsed, restored)
    }

    @Test
    fun hiddenProjectDockStateIsPersisted() = runTest {
        val dataStore = InMemoryPanePreferencesDataStore(emptyPreferences())
        val repository = DataStorePaneLayoutPreferencesRepository(dataStore)

        repository.saveProjectDockState(ProjectDockState.Hidden)

        assertEquals(ProjectDockState.Hidden, repository.projectDockState.first())
    }
}

private class InMemoryPanePreferencesDataStore(
    initial: Preferences,
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (Preferences) -> Preferences,
    ): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
