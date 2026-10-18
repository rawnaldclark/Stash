package com.stash.core.data.listening

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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Its own small store: the recorder reads it on every song, and nothing else lives here. */
private val Context.listeningHistoryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "listening_history_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Settings › Library & Storage › Listening history: whether this phone keeps a history at all.
 *
 * While [paused], [com.stash.core.media.listening.ListeningRecorder] records nothing: no listen, no
 * skip, and no "now playing" to Last.fm or ListenBrainz (owner decision 6, 2026-10-18). With no listen
 * written, nothing is scrobbled either, and Stash Mixes, play counts and auto-save see nothing. Plays
 * that reach the phone from another device (the web mirror, a library file) are not this phone
 * listening and still arrive.
 *
 * Reads that fail (an IOException from the store) fall back to "not paused": history on is the
 * default, and a broken file must not silently stop it.
 */
@Singleton
class ListeningHistoryPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val pausedKey = booleanPreferencesKey("history_paused")

    val paused: Flow<Boolean> = context.listeningHistoryDataStore.data
        .map { it[pausedKey] ?: false }
        .distinctUntilChanged()
        .catch { emit(false) }

    suspend fun setPaused(paused: Boolean) {
        context.listeningHistoryDataStore.edit { it[pausedKey] = paused }
    }
}
