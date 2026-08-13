package com.unsupportedpastels.hermesandroid.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ProjectIconViewModel(
    private val repository: ProjectIconRepository,
) : ViewModel() {
    val assignments: StateFlow<ProjectIconAssignmentsState> = repository.assignments.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = ProjectIconAssignmentsState.Loading,
    )

    fun save(
        serverOrigin: ServerOrigin,
        projectId: ProjectId,
        iconId: ProjectIconId,
    ): Deferred<Result<Unit>> = viewModelScope.async {
        runCatching { repository.save(serverOrigin, projectId, iconId) }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val applicationContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ProjectIconViewModel::class.java))
            return ProjectIconViewModel(
                DataStoreProjectIconRepository(applicationContext),
            ) as T
        }
    }
}
