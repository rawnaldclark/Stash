package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Dedicated DataStore for the Home Qobuz-discovery opt-out toggle. */
private val Context.homeDiscoveryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "home_discovery_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * User opt-out for the Qobuz discovery sections on Home (New Releases,
 * Qobuz Playlists, Top Albums, and the genre chips that steer them).
 * When `false`, Home keeps only the Stash mix hero/rails and whatever the
 * user imports from Spotify / YouTube — and the catalog fetches behind the
 * discovery rows are not made at all. Default `true` preserves current
 * behavior.
 */
@Singleton
class HomeDiscoveryPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val enabledKey = booleanPreferencesKey("qobuz_discovery_enabled")

    val enabled: Flow<Boolean> = context.homeDiscoveryDataStore.data.map { prefs ->
        prefs[enabledKey] ?: true
    }

    suspend fun setEnabled(value: Boolean) {
        context.homeDiscoveryDataStore.edit { it[enabledKey] = value }
    }
}
