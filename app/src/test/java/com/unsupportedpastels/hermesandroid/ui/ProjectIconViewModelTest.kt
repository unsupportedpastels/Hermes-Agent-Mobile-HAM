package com.unsupportedpastels.hermesandroid.ui

import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectIconViewModelTest {
    @Test
    fun saveFailureIsReturnedToThePicker() = runTest {
        val viewModel = ProjectIconViewModel(FailingProjectIconRepository())

        val result = viewModel.save(
            ServerOrigin.parse("https://example.com"),
            ProjectId("project-alpha"),
            ProjectIconId.Rocket,
        ).await()

        assertTrue(result.isFailure)
    }
}

private class FailingProjectIconRepository : ProjectIconRepository {
    override val assignments: Flow<ProjectIconAssignmentsState> = flowOf(
        ProjectIconAssignmentsState.Ready(emptyMap()),
    )

    override suspend fun save(
        serverOrigin: ServerOrigin,
        projectId: ProjectId,
        iconId: ProjectIconId,
    ) {
        throw IllegalStateException("write failed")
    }
}
