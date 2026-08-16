package com.unsupportedpastels.hermesandroid.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFilterRepositoryTest {
    @Test
    fun savedFiltersAreScopedByCanonicalOriginAndProfile() = runTest {
        val dataStore = InMemorySessionFilterDataStore(emptyPreferences())
        val repository = DataStoreSessionFilterRepository(dataStore)
        val origin = ServerOrigin.parse("HTTPS://Hermes.Example:443/")
        val work = SessionFilterScope(origin, "work")
        val defaultProfile = SessionFilterScope(origin, "default")
        val filter = SavedSessionFilter("Pinned", SessionListFilter(pinnedOnly = true))

        repository.save(work, filter)

        assertEquals(listOf(filter), repository.list(work))
        assertTrue(repository.list(defaultProfile).isEmpty())
        assertEquals(
            listOf(filter),
            repository.list(SessionFilterScope(ServerOrigin.parse("https://hermes.example"), "work")),
        )
    }

    @Test
    fun duplicateNameReplacesAndRemoveOnlyTouchesCurrentScope() = runTest {
        val dataStore = InMemorySessionFilterDataStore(emptyPreferences())
        val repository = DataStoreSessionFilterRepository(dataStore)
        val scope = SessionFilterScope(ServerOrigin.parse("https://hermes.example"), "default")
        val otherScope = SessionFilterScope(ServerOrigin.parse("https://other.example"), "default")

        repository.save(scope, SavedSessionFilter("Recent", SessionListFilter(query = "one")))
        repository.save(scope, SavedSessionFilter("Recent", SessionListFilter(query = "two")))
        repository.save(otherScope, SavedSessionFilter("Recent", SessionListFilter(query = "other")))
        repository.remove(scope, "Recent")

        assertTrue(repository.list(scope).isEmpty())
        assertEquals("other", repository.list(otherScope).single().filter.query)
    }

    @Test
    fun repositoryKeepsEachScopeBounded() = runTest {
        val repository = DataStoreSessionFilterRepository(InMemorySessionFilterDataStore(emptyPreferences()))
        val scope = SessionFilterScope(ServerOrigin.parse("https://hermes.example"), "default")

        repeat(MAX_SAVED_FILTERS_PER_SCOPE + 5) { index ->
            repository.save(
                scope,
                SavedSessionFilter("Filter $index", SessionListFilter(query = "q$index")),
            )
        }

        assertEquals(MAX_SAVED_FILTERS_PER_SCOPE, repository.list(scope).size)
        assertEquals("q5", repository.list(scope).first().filter.query)
    }
}

private class InMemorySessionFilterDataStore(
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
