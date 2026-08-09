package com.stash.core.media

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "playback_state",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

data class SavedPlaybackState(
    val trackId: Long,
    val positionMs: Long,
    val queueIndex: Int,
    /**
     * The full playback queue as an ordered list of track ids, in the
     * player's window order (the order `getMediaItemAt` returns, which is
     * the original insertion order regardless of shuffle). Empty when no
     * queue has been persisted yet — callers fall back to a single-track
     * resume in that case.
     */
    val queueTrackIds: List<Long>,
    val isShuffled: Boolean,
    val repeatMode: com.stash.core.model.RepeatMode,
    val source: com.stash.core.model.PlaybackSource,
)

@Singleton
class PlaybackStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val TRACK_ID = longPreferencesKey("last_track_id")
        val POSITION_MS = longPreferencesKey("last_position_ms")
        val QUEUE_INDEX = intPreferencesKey("last_queue_index")
        val QUEUE_IDS = stringPreferencesKey("last_queue_ids")
        val IS_SHUFFLED = booleanPreferencesKey("last_is_shuffled")
        val REPEAT_MODE = stringPreferencesKey("last_repeat_mode")
        val SOURCE = stringPreferencesKey("last_source")
    }

    suspend fun savePosition(trackId: Long, positionMs: Long, queueIndex: Int) {
        context.playbackDataStore.edit { prefs ->
            prefs[Keys.TRACK_ID] = trackId
            prefs[Keys.POSITION_MS] = positionMs
            prefs[Keys.QUEUE_INDEX] = queueIndex
        }
    }

    /**
     * Persists the full queue separately from the (frequently updated)
     * position. Caller should only invoke this when the queue contents or
     * shuffle state actually change, not on every position tick, so the
     * comma-joined id list isn't rewritten 4×/second.
     */
    suspend fun saveQueue(
        trackIds: List<Long>,
        isShuffled: Boolean,
        repeatMode: com.stash.core.model.RepeatMode,
        source: com.stash.core.model.PlaybackSource,
    ) {
        context.playbackDataStore.edit { prefs ->
            prefs[Keys.QUEUE_IDS] = trackIds.joinToString(",")
            prefs[Keys.IS_SHUFFLED] = isShuffled
            prefs[Keys.REPEAT_MODE] = repeatMode.name
            prefs[Keys.SOURCE] = source.serialize()
        }
    }

    suspend fun getLastPlaybackState(): SavedPlaybackState? {
        val prefs = context.playbackDataStore.data.first()
        val trackId = prefs[Keys.TRACK_ID] ?: return null
        val queueTrackIds = prefs[Keys.QUEUE_IDS]
            ?.split(",")
            ?.mapNotNull { it.toLongOrNull() }
            ?: emptyList()
        val repeatMode = prefs[Keys.REPEAT_MODE]
            ?.let { runCatching { com.stash.core.model.RepeatMode.valueOf(it) }.getOrNull() }
            ?: com.stash.core.model.RepeatMode.OFF
        val source = com.stash.core.model.PlaybackSource.deserialize(prefs[Keys.SOURCE])
        return SavedPlaybackState(
            trackId = trackId,
            positionMs = prefs[Keys.POSITION_MS] ?: 0L,
            queueIndex = prefs[Keys.QUEUE_INDEX] ?: 0,
            queueTrackIds = queueTrackIds,
            isShuffled = prefs[Keys.IS_SHUFFLED] ?: false,
            repeatMode = repeatMode,
            source = source,
        )
    }

    suspend fun clear() {
        context.playbackDataStore.edit { it.clear() }
    }
}
