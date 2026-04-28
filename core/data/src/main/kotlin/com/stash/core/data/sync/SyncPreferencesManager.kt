package com.stash.core.data.sync

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.model.MusicSource
import com.stash.core.model.SyncMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncPrefsDataStore by preferencesDataStore(name = "sync_preferences")

/**
 * User-configurable sync scheduling preferences backed by DataStore.
 *
 * @property syncHour   Hour of day (0-23) for the scheduled daily sync.
 * @property syncMinute Minute of hour (0-59) for the scheduled daily sync.
 * @property autoSyncEnabled Whether the daily auto-sync is active.
 * @property wifiOnly   Whether sync should only run on unmetered (Wi-Fi) connections.
 * @property spotifySyncMode Refresh/accumulate mode applied to Spotify-sourced playlists.
 * @property youtubeSyncMode Refresh/accumulate mode applied to YouTube-sourced playlists.
 * @property youtubeLikedStudioOnly When true, filters UGC, cover, live, and podcast tracks from YT Music Liked Songs sync.
 * @property syncDays 7-bit bitmask of days the auto-sync should run (bit 0 = Monday … bit 6 = Sunday).
 */
data class SyncPreferences(
    val syncHour: Int = 6,
    val syncMinute: Int = 0,
    val autoSyncEnabled: Boolean = true,
    val wifiOnly: Boolean = true,
    val spotifySyncMode: SyncMode = SyncMode.REFRESH,
    val youtubeSyncMode: SyncMode = SyncMode.REFRESH,
    /**
     * When true, the YouTube Music Liked Songs sync filters out UGC, cover,
     * live, and podcast tracks (anything that isn't ATV / OMV /
     * OFFICIAL_SOURCE_MUSIC). Other YT Music content (Home Mixes, custom
     * user playlists) is unaffected. Defaults to false — everything syncs.
     */
    val youtubeLikedStudioOnly: Boolean = false,
    /**
     * 7-bit bitmask of days the auto-sync should run. Bit 0 = Monday … bit 6 = Sunday.
     * Default 0b1111111 (127) = every day, matching the prior daily behavior.
     * 0 means no day is enabled — UI surfaces a "pick at least one day" hint and
     * the scheduler does not enqueue work.
     */
    val syncDays: Int = 0b1111111,
)

/**
 * Singleton manager for reading and writing sync scheduling preferences.
 *
 * All values are persisted in a dedicated DataStore file (`sync_preferences`)
 * and exposed as a reactive [Flow] so that UI and background workers can
 * observe changes immediately.
 *
 * Migration note — v0.5: the old global `sync_mode` key is transparently
 * forwarded into `spotify_sync_mode` the first time the new key is read
 * without a value. YouTube defaults independently to [SyncMode.REFRESH].
 * The split matches the user-facing UI, which renders a separate chip
 * row in each service's Sync Preferences card.
 */
@Singleton
class SyncPreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val SYNC_HOUR = intPreferencesKey("sync_hour")
        val SYNC_MINUTE = intPreferencesKey("sync_minute")
        val AUTO_SYNC = booleanPreferencesKey("auto_sync_enabled")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        /** Legacy global key — read-only now. Used for one-shot migration. */
        val LEGACY_SYNC_MODE = stringPreferencesKey("sync_mode")
        val SPOTIFY_SYNC_MODE = stringPreferencesKey("spotify_sync_mode")
        val YOUTUBE_SYNC_MODE = stringPreferencesKey("youtube_sync_mode")
        val YOUTUBE_LIKED_STUDIO_ONLY = booleanPreferencesKey("youtube_liked_studio_only")
        val SYNC_DAYS = intPreferencesKey("sync_days")
    }

    /** Reactive stream of the current [SyncPreferences]. */
    val preferences: Flow<SyncPreferences> = context.syncPrefsDataStore.data.map { prefs ->
        SyncPreferences(
            syncHour = prefs[Keys.SYNC_HOUR] ?: 6,
            syncMinute = prefs[Keys.SYNC_MINUTE] ?: 0,
            autoSyncEnabled = prefs[Keys.AUTO_SYNC] ?: true,
            wifiOnly = prefs[Keys.WIFI_ONLY] ?: true,
            spotifySyncMode = resolveSpotifyMode(prefs),
            youtubeSyncMode = resolveYoutubeMode(prefs),
            youtubeLikedStudioOnly = prefs[Keys.YOUTUBE_LIKED_STUDIO_ONLY] ?: false,
            syncDays = prefs[Keys.SYNC_DAYS] ?: 0b1111111,
        )
    }

    /**
     * Reactive stream of Spotify's sync mode. Exposed separately so
     * background workers can read only the mode without pulling the full
     * [SyncPreferences] combine. Migrates the legacy global `sync_mode`
     * value if the Spotify-specific key isn't set yet.
     */
    val spotifySyncMode: Flow<SyncMode> =
        context.syncPrefsDataStore.data.map { resolveSpotifyMode(it) }

    /** Reactive stream of YouTube's sync mode. Defaults to REFRESH. */
    val youtubeSyncMode: Flow<SyncMode> =
        context.syncPrefsDataStore.data.map { resolveYoutubeMode(it) }

    /**
     * Reactive stream of the YT Music Liked Songs studio-only filter.
     * Read once per sync run via `.first()` inside [PlaylistFetchWorker].
     * Default false: everything syncs.
     */
    val youtubeLikedStudioOnly: Flow<Boolean> =
        context.syncPrefsDataStore.data.map { it[Keys.YOUTUBE_LIKED_STUDIO_ONLY] ?: false }

    /**
     * Reactive stream of the days-of-week bitmask. Read via .first() inside
     * [SyncScheduler] when computing the next firing time. Default 127 = every day.
     */
    val syncDays: Flow<Int> =
        context.syncPrefsDataStore.data.map { it[Keys.SYNC_DAYS] ?: 0b1111111 }

    private fun resolveSpotifyMode(
        prefs: androidx.datastore.preferences.core.Preferences,
    ): SyncMode {
        val explicit = prefs[Keys.SPOTIFY_SYNC_MODE]?.let { parseMode(it) }
        if (explicit != null) return explicit
        // Fall back to the legacy global key so users who set a preference
        // before the split don't silently flip to REFRESH after upgrade.
        return prefs[Keys.LEGACY_SYNC_MODE]?.let { parseMode(it) } ?: SyncMode.REFRESH
    }

    private fun resolveYoutubeMode(
        prefs: androidx.datastore.preferences.core.Preferences,
    ): SyncMode =
        prefs[Keys.YOUTUBE_SYNC_MODE]?.let { parseMode(it) } ?: SyncMode.REFRESH

    private fun parseMode(name: String): SyncMode? =
        runCatching { SyncMode.valueOf(name) }.getOrNull()

    /**
     * Persist a new sync schedule time.
     *
     * @param hour   Hour of day (0-23).
     * @param minute Minute of hour (0-59).
     */
    suspend fun setSyncTime(hour: Int, minute: Int) {
        context.syncPrefsDataStore.edit {
            it[Keys.SYNC_HOUR] = hour
            it[Keys.SYNC_MINUTE] = minute
        }
    }

    /** Enable or disable the daily automatic sync. */
    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        context.syncPrefsDataStore.edit { it[Keys.AUTO_SYNC] = enabled }
    }

    /** Restrict sync to Wi-Fi only, or allow any network. */
    suspend fun setWifiOnly(wifiOnly: Boolean) {
        context.syncPrefsDataStore.edit { it[Keys.WIFI_ONLY] = wifiOnly }
    }

    /** Persist Spotify's sync mode. Also removes the legacy global key so
     *  subsequent reads come exclusively from the Spotify-specific slot. */
    suspend fun setSpotifySyncMode(mode: SyncMode) {
        context.syncPrefsDataStore.edit {
            it[Keys.SPOTIFY_SYNC_MODE] = mode.name
            it.remove(Keys.LEGACY_SYNC_MODE)
        }
    }

    /** Persist YouTube's sync mode. */
    suspend fun setYoutubeSyncMode(mode: SyncMode) {
        context.syncPrefsDataStore.edit { it[Keys.YOUTUBE_SYNC_MODE] = mode.name }
    }

    /** Persist the YT Music Liked Songs studio-only filter. */
    suspend fun setYoutubeLikedStudioOnly(enabled: Boolean) {
        context.syncPrefsDataStore.edit { it[Keys.YOUTUBE_LIKED_STUDIO_ONLY] = enabled }
    }

    /** Persist the days-of-week bitmask. UI passes [DayOfWeekSet.bitmask]. */
    suspend fun setSyncDays(bitmask: Int) {
        context.syncPrefsDataStore.edit { it[Keys.SYNC_DAYS] = bitmask }
    }

    /**
     * Read-time resolver used by sync workers: returns the appropriate
     * [SyncMode] for the given [MusicSource]. LOCAL/BOTH sources (STASH_MIX
     * etc.) don't flow through the sync pipeline, but a sane default
     * keeps this total.
     */
    fun syncModeFor(source: MusicSource): Flow<SyncMode> = when (source) {
        MusicSource.SPOTIFY -> spotifySyncMode
        MusicSource.YOUTUBE -> youtubeSyncMode
        MusicSource.LOCAL, MusicSource.BOTH -> spotifySyncMode
    }
}
