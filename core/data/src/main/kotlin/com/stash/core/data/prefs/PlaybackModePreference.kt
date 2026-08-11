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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playbackModeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "playback_mode_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Independent from [StreamingPreference] (which governs Download Mode — whether
 * sync pulls real files to disk vs. just refreshing the streamable index).
 * [enabled] here governs Playback Mode — whether Now Playing is allowed to
 * stream a non-downloaded track on tap, or is restricted to files already on
 * disk. The two are orthogonal: a user can stream a mixed playlist (Playback
 * = Online) while sync still only writes real files (Download = Offline), or
 * browse only downloaded tracks (Playback = Offline) while sync quietly keeps
 * the streamable index current in the background (Download = Online).
 *
 * Default `enabled = false` (Offline playback — only plays downloaded files),
 * matching pre-existing behavior for the current install base.
 */
@Singleton
class PlaybackModePreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val enabledKey = booleanPreferencesKey("playback_online_enabled")

    /** True = Online playback (streams non-downloaded tracks on tap). False = Offline (downloaded files only). */
    val enabled: Flow<Boolean> = context.playbackModeDataStore.data.map { prefs ->
        prefs[enabledKey] ?: false
    }

    suspend fun current(): Boolean = enabled.first()

    suspend fun setEnabled(value: Boolean) {
        context.playbackModeDataStore.edit { it[enabledKey] = value }
    }

    /**
     * One-time migration for existing installs: if this key has never been
     * written, seed it from the legacy StreamingPreference.enabled value
     * instead of defaulting to false. Before this split, StreamingPreference
     * governed both download AND playback — a user with it enabled had Now
     * Playing streaming undownloaded tracks. Without this seed, that cohort
     * silently loses streaming playback on update until they find the new
     * toggle. No-op once the key exists (never overwrites a user choice).
     */
    suspend fun seedFromLegacyIfAbsent(legacyStreamingEnabled: Boolean) {
        context.playbackModeDataStore.edit { prefs ->
            if (!prefs.contains(enabledKey)) {
                prefs[enabledKey] = legacyStreamingEnabled
            }
        }
    }
}