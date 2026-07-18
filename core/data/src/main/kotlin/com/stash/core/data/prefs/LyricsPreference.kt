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

/** Dedicated DataStore for lyrics display preferences. */
private val Context.lyricsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lyrics_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Lyrics display preferences.
 *
 * [liveBarEnabled] — the live synced-line bar at the bottom of Now Playing.
 * Default OFF: watching lyrics tick by can pull the listener out of the
 * music. Off, every track with lyrics (synced or plain) shows the quiet
 * "View lyrics ♪" bar instead; the opt-in toggle lives in the lyrics sheet.
 */
@Singleton
class LyricsPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val liveBarKey = booleanPreferencesKey("live_bar_enabled")

    val liveBarEnabled: Flow<Boolean> = context.lyricsDataStore.data.map { prefs ->
        prefs[liveBarKey] ?: false
    }

    suspend fun setLiveBarEnabled(value: Boolean) {
        context.lyricsDataStore.edit { it[liveBarKey] = value }
    }
}
