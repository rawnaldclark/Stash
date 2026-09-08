package com.stash.core.data.lastfm

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dedicated DataStore for Last.fm-as-a-source preferences (issue #255) —
 * mirrors `lastfm_session`, which stays scoped to the scrobble connection.
 */
private val Context.lastFmSourceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lastfm_source",
)

/**
 * User toggle for the "Recommended by Last.fm" source playlist
 * ([com.stash.core.data.mix.LastFmRecommendationSource]).
 *
 * Default is `true`: the issue asks for recommendations to simply show up
 * under Sync → Sources once Last.fm is connected, and this matches how
 * Daily Discover behaves for everyone. The recipe only ever materializes
 * while a Last.fm session exists — turning the connection off (or this
 * toggle off) deactivates it without deleting anything, so re-enabling
 * restores the playlist with its accumulated tracks intact.
 */
@Singleton
class LastFmSourcePreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val recommendationsEnabledKey = booleanPreferencesKey("recommendations_enabled")

    val recommendationsEnabled: Flow<Boolean> = context.lastFmSourceDataStore.data.map { prefs ->
        prefs[recommendationsEnabledKey] ?: true
    }

    suspend fun current(): Boolean = recommendationsEnabled.first()

    suspend fun setRecommendationsEnabled(enabled: Boolean) {
        context.lastFmSourceDataStore.edit {
            it[recommendationsEnabledKey] = enabled
        }
    }
}
