package com.stash.core.data.prefs

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

/** Dedicated DataStore for YT history sync opt-in. */
private val Context.youtubeHistoryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "youtube_history_preference",
)

/**
 * Persists the user's opt-in for scrobbling local plays to YouTube
 * Music's Watch History (thereby feeding YT Music's recommender graph).
 *
 * Default is false — the feature is off on every fresh install. Flipping
 * on shows a one-time confirmation dialog in the Settings layer; this
 * class itself is unaware of the dialog, it just stores the bool.
 */
@Singleton
class YouTubeHistoryPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val enabledKey = booleanPreferencesKey("enabled")

    val enabled: Flow<Boolean> = context.youtubeHistoryDataStore.data.map { prefs ->
        prefs[enabledKey] ?: false
    }

    suspend fun current(): Boolean = enabled.first()

    suspend fun setEnabled(value: Boolean) {
        context.youtubeHistoryDataStore.edit { it[enabledKey] = value }
    }
}
