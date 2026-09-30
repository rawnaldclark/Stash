package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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

/** Which source family the lyrics chain is allowed to use. See [LyricsPreference.sourcePreference]. */
enum class LyricsSourcePreference {
    /** Default. Apple Music (word-synced TTML) is consulted first, LRC sources fall back. */
    APPLE_MUSIC,
    /** Apple Music is never consulted — LRCLIB/KuGou/YT only. */
    LRC_ONLY,
}

/**
 * Lyrics display preferences.
 *
 * [liveBarEnabled] — the live synced-line bar at the bottom of Now Playing.
 * Default OFF: watching lyrics tick by can pull the listener out of the
 * music. Off, every track with lyrics (synced or plain) shows the quiet
 * "View lyrics ♪" bar instead; the opt-in toggle lives in the lyrics sheet.
 *
 * [sourcePreference] — whether the fetch chain consults Apple Music's word-synced TTML at all.
 * Changing this doesn't itself trigger any fetch/cleanup — see
 * [com.stash.data.lyrics.LyricsRepository.setSourcePreference] for what switching to LRC_ONLY does
 * to already-stored lyrics.
 */
@Singleton
class LyricsPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val liveBarKey = booleanPreferencesKey("live_bar_enabled")
    private val sourceKey = stringPreferencesKey("lyrics_source_preference")
    private val bulkFetchCursorKey = longPreferencesKey("bulk_fetch_cursor")
    private val ttmlUpgradeCursorKey = longPreferencesKey("ttml_upgrade_cursor")

    val liveBarEnabled: Flow<Boolean> = context.lyricsDataStore.data.map { prefs ->
        prefs[liveBarKey] ?: false
    }

    suspend fun setLiveBarEnabled(value: Boolean) {
        context.lyricsDataStore.edit { it[liveBarKey] = value }
    }

    val sourcePreference: Flow<LyricsSourcePreference> = context.lyricsDataStore.data.map { prefs ->
        prefs[sourceKey]?.let { stored ->
            runCatching { LyricsSourcePreference.valueOf(stored) }.getOrNull()
        } ?: LyricsSourcePreference.APPLE_MUSIC
    }

    suspend fun setSourcePreference(value: LyricsSourcePreference) {
        context.lyricsDataStore.edit { it[sourceKey] = value.name }
    }

    /** Last track id the manual "Fetch lyrics" run reached; the next run starts just after it. */
    val bulkFetchCursor: Flow<Long> = context.lyricsDataStore.data.map { it[bulkFetchCursorKey] ?: 0L }

    suspend fun setBulkFetchCursor(trackId: Long) {
        context.lyricsDataStore.edit { it[bulkFetchCursorKey] = trackId }
    }

    /** Last track id the TTML upgrade pass reached; the next run starts just after it. */
    val ttmlUpgradeCursor: Flow<Long> = context.lyricsDataStore.data.map { it[ttmlUpgradeCursorKey] ?: 0L }

    suspend fun setTtmlUpgradeCursor(trackId: Long) {
        context.lyricsDataStore.edit { it[ttmlUpgradeCursorKey] = trackId }
    }
}
