package com.unsupportedpastels.hermesandroid.connection

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerSettingsRepositoryTest {
    @Test
    fun savedCanonicalOriginIsPublishedAndReplaced() = runTest {
        val repository = DataStoreServerSettingsRepository(
            dataStore = InMemoryDataStore(emptyPreferences()),
        )

        repository.states.test {
            assertEquals(ServerSettingsState.Loading, awaitItem())
            assertEquals(ServerSettingsState.Ready(null), awaitItem())

            repository.save(ServerOrigin.parse("https://FIRST.example/"))
            val firstState = awaitItem() as ServerSettingsState.Ready
            assertEquals(ServerOrigin.parse("https://first.example"), firstState.activeOrigin)
            assertEquals(
                listOf(ServerOrigin.parse("https://first.example")),
                firstState.catalog.entries.map(ServerCatalogEntry::origin),
            )

            repository.save(ServerOrigin.parse("https://second.example:8443"))
            val secondState = awaitItem() as ServerSettingsState.Ready
            assertEquals(ServerOrigin.parse("https://second.example:8443"), secondState.activeOrigin)
            assertEquals(
                listOf(
                    ServerOrigin.parse("https://first.example"),
                    ServerOrigin.parse("https://second.example:8443"),
                ),
                secondState.catalog.entries.map(ServerCatalogEntry::origin),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun readFailureIsExposedBeforeRepositoryRetries() = runTest {
        val repository = DataStoreServerSettingsRepository(
            dataStore = RecoveringPreferencesDataStore(),
            retryDelayMillis = 0,
        )

        repository.states.test {
            assertEquals(ServerSettingsState.Loading, awaitItem())
            assertEquals(ServerSettingsState.Unavailable, awaitItem())
            assertEquals(ServerSettingsState.Ready(null), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun invalidPersistedOriginIsReportedAsUnavailable() = runTest {
        val repository = DataStoreServerSettingsRepository(
            dataStore = InMemoryDataStore(
                preferencesOf(
                    stringPreferencesKey("server_origin") to "https://example.com/not-an-origin",
                ),
            ),
        )

        repository.states.test {
            assertEquals(ServerSettingsState.Loading, awaitItem())
            assertEquals(ServerSettingsState.Unavailable, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}

private class InMemoryDataStore<T>(initial: T) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<T> = state

    override suspend fun updateData(transform: suspend (t: T) -> T): T {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

private class RecoveringPreferencesDataStore : DataStore<Preferences> {
    private var collectionCount = 0
    private var preferences = emptyPreferences()

    override val data: Flow<Preferences>
        get() = flow {
            if (collectionCount++ == 0) throw IOException("temporary read failure")
            emit(preferences)
        }

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences {
        preferences = transform(preferences)
        return preferences
    }
}
