package com.stash.core.media

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(name = "playback_state")

data class SavedPlaybackState(
    val trackId: Long,
    val positionMs: Long,
    val queueIndex: Int,
)

@Singleton
class PlaybackStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val TRACK_ID = longPreferencesKey("last_track_id")
        val POSITION_MS = longPreferencesKey("last_position_ms")
        val QUEUE_INDEX = intPreferencesKey("last_queue_index")
    }

    suspend fun savePosition(trackId: Long, positionMs: Long, queueIndex: Int) {
        context.playbackDataStore.edit { prefs ->
            prefs[Keys.TRACK_ID] = trackId
            prefs[Keys.POSITION_MS] = positionMs
            prefs[Keys.QUEUE_INDEX] = queueIndex
        }
    }

    suspend fun getLastPlaybackState(): SavedPlaybackState? {
        val prefs = context.playbackDataStore.data.first()
        val trackId = prefs[Keys.TRACK_ID] ?: return null
        return SavedPlaybackState(
            trackId = trackId,
            positionMs = prefs[Keys.POSITION_MS] ?: 0L,
            queueIndex = prefs[Keys.QUEUE_INDEX] ?: 0,
        )
    }

    suspend fun clear() {
        context.playbackDataStore.edit { it.clear() }
    }
}
