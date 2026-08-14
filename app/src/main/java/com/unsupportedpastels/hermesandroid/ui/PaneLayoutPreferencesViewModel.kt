package com.unsupportedpastels.hermesandroid.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PaneLayoutPreferencesViewModel(
    private val repository: PaneLayoutPreferencesRepository,
) : ViewModel() {
    val projectSessionPaneProportion: StateFlow<Float?> =
        repository.projectSessionPaneProportion.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = null,
        )
    val projectDockState: StateFlow<ProjectDockState?> =
        repository.projectDockState.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = null,
        )

    fun saveProjectSessionPaneProportion(proportion: Float) {
        viewModelScope.launch {
            repository.saveProjectSessionPaneProportion(proportion)
        }
    }

    fun saveProjectDockState(state: ProjectDockState) {
        viewModelScope.launch {
            repository.saveProjectDockState(state)
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val applicationContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PaneLayoutPreferencesViewModel::class.java))
            return PaneLayoutPreferencesViewModel(
                DataStorePaneLayoutPreferencesRepository(applicationContext),
            ) as T
        }
    }
}
