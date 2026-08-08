package com.unsupportedpastels.hermesandroid.connection

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen

private const val ServerSettingsDataStoreName = "server_settings"
private val ServerOriginKey = stringPreferencesKey("server_origin")
private val Context.serverSettingsDataStore by preferencesDataStore(
    name = ServerSettingsDataStoreName,
)

sealed interface ServerSettingsState {
    data object Loading : ServerSettingsState

    data class Ready(val serverOrigin: ServerOrigin?) : ServerSettingsState

    data object Unavailable : ServerSettingsState
}

interface ServerSettingsRepository {
    val states: Flow<ServerSettingsState>

    suspend fun save(serverOrigin: ServerOrigin)
}

class DataStoreServerSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val retryDelayMillis: Long = 1_000,
) : ServerSettingsRepository {
    constructor(context: Context) : this(context.applicationContext.serverSettingsDataStore)

    override val states: Flow<ServerSettingsState> = dataStore.data
        .map<Preferences, ServerSettingsState> { preferences ->
            when (val stored = preferences[ServerOriginKey]) {
                null -> ServerSettingsState.Ready(null)
                else -> try {
                    ServerSettingsState.Ready(ServerOrigin.parse(stored))
                } catch (_: IllegalArgumentException) {
                    ServerSettingsState.Unavailable
                }
            }
        }
        .retryWhen { error, _ ->
            if (error is IOException) {
                emit(ServerSettingsState.Unavailable)
                delay(retryDelayMillis)
                true
            } else {
                false
            }
        }
        .onStart { emit(ServerSettingsState.Loading) }
        .distinctUntilChanged()

    override suspend fun save(serverOrigin: ServerOrigin) {
        dataStore.edit { preferences ->
            preferences[ServerOriginKey] = serverOrigin.value
        }
    }
}
