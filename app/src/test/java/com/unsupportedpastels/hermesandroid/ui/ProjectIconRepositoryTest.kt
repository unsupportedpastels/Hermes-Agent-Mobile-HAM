package com.unsupportedpastels.hermesandroid.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectIconRepositoryTest {
    @Test
    fun assignmentsAreScopedByServerOriginAndProjectId() = runTest {
        val repository = DataStoreProjectIconRepository(InMemoryDataStore(emptyPreferences()))
        val firstOrigin = ServerOrigin.parse("https://first.example")
        val secondOrigin = ServerOrigin.parse("https://second.example")
        val projectId = ProjectId("shared-project-id")

        repository.assignments.test {
            assertEquals(ProjectIconAssignmentsState.Loading, awaitItem())
            assertEquals(ProjectIconAssignmentsState.Ready(emptyMap()), awaitItem())

            repository.save(firstOrigin, projectId, ProjectIconId.Rocket)
            assertEquals(
                ProjectIconAssignmentsState.Ready(
                    mapOf(ProjectIconAssignmentKey(firstOrigin, projectId) to ProjectIconId.Rocket),
                ),
                awaitItem(),
            )

            repository.save(secondOrigin, projectId, ProjectIconId.Science)
            assertEquals(
                ProjectIconAssignmentsState.Ready(
                    mapOf(
                        ProjectIconAssignmentKey(firstOrigin, projectId) to ProjectIconId.Rocket,
                        ProjectIconAssignmentKey(secondOrigin, projectId) to ProjectIconId.Science,
                    ),
                ),
                awaitItem(),
            )
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
