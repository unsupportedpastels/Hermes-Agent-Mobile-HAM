package com.unsupportedpastels.hermesandroid.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val SESSION_FILTER_DATASTORE_NAME = "session_organization"
private const val MAX_SERIALIZED_FILTER_BYTES = 64 * 1024
private val SavedFiltersKey = stringPreferencesKey("saved_session_filters_v1")
private val Context.sessionOrganizationDataStore by preferencesDataStore(
    name = SESSION_FILTER_DATASTORE_NAME,
)

interface SessionFilterRepository {
    suspend fun list(scope: SessionFilterScope): List<SavedSessionFilter>

    suspend fun save(scope: SessionFilterScope, filter: SavedSessionFilter)

    suspend fun remove(scope: SessionFilterScope, name: String)
}

/**
 * Persists only bounded local filter metadata. Session rows, transcript text,
 * credentials, and connection tokens are deliberately not part of this schema.
 */
class DataStoreSessionFilterRepository(
    private val dataStore: DataStore<Preferences>,
) : SessionFilterRepository {
    constructor(context: Context) : this(context.applicationContext.sessionOrganizationDataStore)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun list(scope: SessionFilterScope): List<SavedSessionFilter> =
        readBundle().scopes
            .firstOrNull { it.origin == scope.serverOrigin.value && it.profile == scope.profile }
            ?.filters
            .orEmpty()
            .mapNotNull(::decodeFilter)
            .take(MAX_SAVED_FILTERS_PER_SCOPE)

    override suspend fun save(scope: SessionFilterScope, filter: SavedSessionFilter) {
        val normalized = SavedSessionFilter(filter.normalizedName, filter.filter)
        dataStore.edit { preferences ->
            val bundle = decodeBundle(preferences[SavedFiltersKey])
            val existing = bundle.scopes.firstOrNull {
                it.origin == scope.serverOrigin.value && it.profile == scope.profile
            }
            val filters = existing?.filters.orEmpty()
                .mapNotNull(::decodeFilter)
                .filterNot { it.normalizedName == normalized.normalizedName }
                .toMutableList()
            filters += normalized
            val updatedScope = StoredScope(
                origin = scope.serverOrigin.value,
                profile = scope.profile,
                filters = filters.takeLast(MAX_SAVED_FILTERS_PER_SCOPE).map(::encodeFilter),
            )
            val updated = StoredBundle(
                scopes = listOf(updatedScope) + bundle.scopes.filterNot {
                    it.origin == scope.serverOrigin.value && it.profile == scope.profile
                }.take(MAX_SAVED_FILTER_SCOPES - 1),
            )
            preferences[SavedFiltersKey] = encodeBundle(updated)
        }
    }

    override suspend fun remove(scope: SessionFilterScope, name: String) {
        val normalizedName = name.trim().take(MAX_SAVED_FILTER_NAME_CHARS)
        if (normalizedName.isEmpty()) return
        dataStore.edit { preferences ->
            val bundle = decodeBundle(preferences[SavedFiltersKey])
            val updatedScopes = bundle.scopes.mapNotNull { storedScope ->
                if (storedScope.origin != scope.serverOrigin.value ||
                    storedScope.profile != scope.profile
                ) {
                    storedScope
                } else {
                    val remaining = storedScope.filters
                        .mapNotNull(::decodeFilter)
                        .filterNot { it.normalizedName == normalizedName }
                        .map(::encodeFilter)
                    remaining.takeIf { it.isNotEmpty() }?.let {
                        storedScope.copy(filters = it.take(MAX_SAVED_FILTERS_PER_SCOPE))
                    }
                }
            }.take(MAX_SAVED_FILTER_SCOPES)
            if (updatedScopes.isEmpty()) {
                preferences.remove(SavedFiltersKey)
            } else {
                preferences[SavedFiltersKey] = encodeBundle(StoredBundle(updatedScopes))
            }
        }
    }

    private suspend fun readBundle(): StoredBundle = decodeBundle(dataStore.data.first()[SavedFiltersKey])

    private fun encodeBundle(bundle: StoredBundle): String {
        val encoded = json.encodeToString(StoredBundle.serializer(), bundle)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_SERIALIZED_FILTER_BYTES) {
            "Saved session filters are too large"
        }
        return encoded
    }

    private fun decodeBundle(encoded: String?): StoredBundle {
        if (encoded.isNullOrBlank() ||
            encoded.toByteArray(Charsets.UTF_8).size > MAX_SERIALIZED_FILTER_BYTES
        ) return StoredBundle()
        return runCatching {
            json.decodeFromString(StoredBundle.serializer(), encoded)
        }.getOrDefault(StoredBundle())
    }

    private fun encodeFilter(filter: SavedSessionFilter): StoredFilter = StoredFilter(
        name = filter.normalizedName,
        query = filter.filter.query,
        pinnedOnly = filter.filter.pinnedOnly,
        archivedOnly = filter.filter.archivedOnly,
    )

    private fun decodeFilter(stored: StoredFilter): SavedSessionFilter? = runCatching {
        SavedSessionFilter(
            name = stored.name,
            filter = SessionListFilter(
                query = stored.query,
                pinnedOnly = stored.pinnedOnly,
                archivedOnly = stored.archivedOnly,
            ),
        )
    }.getOrNull()
}

@Serializable
private data class StoredBundle(
    val scopes: List<StoredScope> = emptyList(),
)

@Serializable
private data class StoredScope(
    val origin: String,
    val profile: String,
    val filters: List<StoredFilter>,
)

@Serializable
private data class StoredFilter(
    val name: String,
    val query: String,
    @SerialName("pinned") val pinnedOnly: Boolean,
    @SerialName("archived") val archivedOnly: Boolean,
)
