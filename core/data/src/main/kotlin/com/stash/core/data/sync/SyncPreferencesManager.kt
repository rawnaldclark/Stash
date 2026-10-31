package com.stash.core.data.sync

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.model.MusicSource
import com.stash.core.model.SyncMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// internal (not private) so unit tests in this module can clear the live
// store between runs — deleting the backing file alone doesn't reset
// DataStore's in-memory cache.
internal val Context.syncPrefsDataStore by preferencesDataStore(
    name = "sync_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

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
 * @property autoSyncPausedAfterUndo Auto-sync skips its runs until the user next taps Sync.
 */
data class SyncPreferences(
    val syncHour: Int = 6,
    val syncMinute: Int = 0,
    val autoSyncEnabled: Boolean = false,
    val wifiOnly: Boolean = true,
    val spotifySyncMode: SyncMode = SyncMode.ACCUMULATE,
    val youtubeSyncMode: SyncMode = SyncMode.ACCUMULATE,
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
    /**
     * Set by "Undo last sync", cleared by the next "Sync now". While set, the
     * daily Auto-sync skips its runs: otherwise it would redo the sync the user
     * just undid, hours later and unasked. A manual Sync is the user saying the
     * library is right again, so it resumes the schedule.
     */
    val autoSyncPausedAfterUndo: Boolean = false,
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
 * without a value. YouTube defaults independently to [SyncMode.ACCUMULATE].
 * The split matches the user-facing UI, which renders a separate chip
 * row in each service's Sync Preferences card.
 */
@Singleton
class SyncPreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
    // Only for anyAccumulate(): which accounts' modes count for deleting downloads.
    private val tokenManager: TokenManager,
    private val playlistDao: PlaylistDao,
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
        val SOUNDCLOUD_SYNC_MODE = stringPreferencesKey("soundcloud_sync_mode")
        val YOUTUBE_LIKED_STUDIO_ONLY = booleanPreferencesKey("youtube_liked_studio_only")
        val SYNC_DAYS = intPreferencesKey("sync_days")
        val DISCOVER_AUTO_MIXES = booleanPreferencesKey("discover_auto_mixes")
        val AUTO_SYNC_PAUSED_AFTER_UNDO = booleanPreferencesKey("auto_sync_paused_after_undo")
        val ARRIVAL_CHOSEN = booleanPreferencesKey("first_sync_arrival_chosen")
        val PIN_NEW_PLAYLISTS = booleanPreferencesKey("pin_new_playlists_to_home")
        val MIX_STACK_SEEDED = booleanPreferencesKey("mix_stack_seeded")
    }

    /** Reactive stream of the current [SyncPreferences]. */
    val preferences: Flow<SyncPreferences> = context.syncPrefsDataStore.data.map { prefs ->
        SyncPreferences(
            syncHour = prefs[Keys.SYNC_HOUR] ?: 6,
            syncMinute = prefs[Keys.SYNC_MINUTE] ?: 0,
            autoSyncEnabled = prefs[Keys.AUTO_SYNC] ?: false,
            wifiOnly = prefs[Keys.WIFI_ONLY] ?: true,
            spotifySyncMode = resolveSpotifyMode(prefs),
            youtubeSyncMode = resolveYoutubeMode(prefs),
            youtubeLikedStudioOnly = prefs[Keys.YOUTUBE_LIKED_STUDIO_ONLY] ?: false,
            syncDays = prefs[Keys.SYNC_DAYS] ?: 0b1111111,
            autoSyncPausedAfterUndo = prefs[Keys.AUTO_SYNC_PAUSED_AFTER_UNDO] ?: false,
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

    /** Reactive stream of YouTube's sync mode. Defaults to ACCUMULATE. */
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
     * Whether sync discovers the service's own algorithmic mixes — Spotify's
     * home-feed Daily Mixes and friends. Read once per sync run in
     * [com.stash.core.data.sync.workers.PlaylistFetchWorker].
     *
     * These are playlists the user never created or saved, so they read as
     * "the app added playlists that aren't mine" (#335, #344) and they make
     * every sync longer. Turning this off stops discovering them; mixes already
     * in the library stay until removed, and can be hidden from Home
     * individually in Manage Playlists.
     *
     * **Defaults to TRUE — deliberately opt-OUT, not opt-in.** The "Made for
     * you" rail is a headline surface; defaulting this off would silently strip
     * it from every existing library on upgrade, and leave a fresh install
     * looking empty. The complaint was never "these exist", it was "I can't
     * stop them" — so this gives the off switch rather than flipping the
     * experience for everyone.
     */
    val discoverAutoMixes: Flow<Boolean> =
        context.syncPrefsDataStore.data.map { it[Keys.DISCOVER_AUTO_MIXES] ?: true }

    /**
     * Reactive stream of the days-of-week bitmask. Read via .first() inside
     * [SyncScheduler] when computing the next firing time. Default 127 = every day.
     */
    val syncDays: Flow<Int> =
        context.syncPrefsDataStore.data.map { it[Keys.SYNC_DAYS] ?: 0b1111111 }

    /**
     * Whether the first-sync sheet ("How should your music arrive?") has been
     * answered. A new install's first Sync asks before it runs; an install that
     * has synced before never sees the sheet, and keeps its mode and switches
     * (the Sync tab overhaul, 2026-10-29).
     */
    val arrivalChosen: Flow<Boolean> =
        context.syncPrefsDataStore.data.map { it[Keys.ARRIVAL_CHOSEN] ?: false }

    /**
     * Whether the per-mix Stack switches have been set from the old per-account mode
     * (DiffWorker does it once: an accumulating account's stackable mixes keep stacking).
     */
    val mixStackSeeded: Flow<Boolean> =
        context.syncPrefsDataStore.data.map { it[Keys.MIX_STACK_SEEDED] ?: false }

    suspend fun markMixStackSeeded() {
        context.syncPrefsDataStore.edit { it[Keys.MIX_STACK_SEEDED] = true }
    }

    /**
     * Whether a playlist sync finds for the first time goes on Home's Your
     * playlists rail. Turned on by "Stream everything" on the first-sync sheet,
     * so a new user's whole account shows up on Home without picking anything.
     * Off for everyone else: their Home stays as they arranged it. Pins only;
     * never touches sync_enabled, which in Download mode would download (#368).
     */
    val pinNewPlaylists: Flow<Boolean> =
        context.syncPrefsDataStore.data.map { it[Keys.PIN_NEW_PLAYLISTS] ?: false }

    private fun resolveSpotifyMode(
        prefs: androidx.datastore.preferences.core.Preferences,
    ): SyncMode {
        val explicit = prefs[Keys.SPOTIFY_SYNC_MODE]?.let { parseMode(it) }
        if (explicit != null) return explicit
        // Fall back to the legacy global key so users who set a preference
        // before the split keep their choice; absent that, default to the
        // safe ACCUMULATE (never-delete) mode.
        return prefs[Keys.LEGACY_SYNC_MODE]?.let { parseMode(it) } ?: SyncMode.ACCUMULATE
    }

    private fun resolveYoutubeMode(
        prefs: androidx.datastore.preferences.core.Preferences,
    ): SyncMode =
        prefs[Keys.YOUTUBE_SYNC_MODE]?.let { parseMode(it) } ?: SyncMode.ACCUMULATE

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

    /** Pause Auto-sync after "Undo last sync" (true), or resume it on the next "Sync now" (false). */
    suspend fun setAutoSyncPausedAfterUndo(paused: Boolean) {
        context.syncPrefsDataStore.edit { it[Keys.AUTO_SYNC_PAUSED_AFTER_UNDO] = paused }
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

    /**
     * True while the orphan-cleanup sweep
     * ([com.stash.core.data.repository.MusicRepository.cleanOrphanedMixTracks])
     * must delete nothing: an account that counts is on ACCUMULATE, or no account
     * counts at all. Cleanup runs only when every account that counts is REFRESH.
     *
     * An account counts while it is signed in, or while its playlists are still in
     * the library: a disconnected account's songs stay, and its mode still decides
     * whether they may go. These are the accounts whose switch the Sync history
     * screen shows. One never connected used to count too, with its default
     * ACCUMULATE and no switch on screen, so a Spotify-only or YouTube-only library
     * never deleted anything, Refresh confirmed or not (#273). SoundCloud has no
     * switch, and the sweep never takes its songs, so it never counts.
     *
     * With no account counting, nobody chose Refresh, so nothing is deleted. And when
     * the sign-ins or playlists can't be read, nothing is deleted either.
     */
    suspend fun anyAccumulate(): Boolean {
        val counted = try {
            SWEPT_ACCOUNTS.filter { (source, service) ->
                tokenManager.isAuthenticated(service) || playlistDao.hasLibraryPlaylists(source)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't tell which accounts count; keeping every song", e)
            return true
        }
        return counted.isEmpty() ||
            counted.any { (source, _) -> syncModeFor(source).first() == SyncMode.ACCUMULATE }
    }

    /** Persist the YT Music Liked Songs studio-only filter. */
    suspend fun setYoutubeLikedStudioOnly(enabled: Boolean) {
        context.syncPrefsDataStore.edit { it[Keys.YOUTUBE_LIKED_STUDIO_ONLY] = enabled }
    }

    /** Persist whether sync discovers the service's own algorithmic mixes. */
    suspend fun setDiscoverAutoMixes(enabled: Boolean) {
        context.syncPrefsDataStore.edit { it[Keys.DISCOVER_AUTO_MIXES] = enabled }
    }

    /** Record the first-sync sheet's answer: [pinNewPlaylists] for "Stream everything". */
    suspend fun setArrivalChosen(pinNewPlaylists: Boolean) {
        context.syncPrefsDataStore.edit {
            it[Keys.ARRIVAL_CHOSEN] = true
            it[Keys.PIN_NEW_PLAYLISTS] = pinNewPlaylists
        }
    }

    /** Mark the sheet answered without changing anything: an install that already synced. */
    suspend fun markArrivalChosen() {
        context.syncPrefsDataStore.edit { it[Keys.ARRIVAL_CHOSEN] = true }
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
    val soundCloudSyncMode: Flow<SyncMode> = context.syncPrefsDataStore.data.map { prefs ->
        prefs[Keys.SOUNDCLOUD_SYNC_MODE]?.let { parseMode(it) } ?: SyncMode.ACCUMULATE
    }

    suspend fun setSoundCloudSyncMode(mode: SyncMode) {
        context.syncPrefsDataStore.edit { it[Keys.SOUNDCLOUD_SYNC_MODE] = mode.name }
    }

    fun syncModeFor(source: MusicSource): Flow<SyncMode> = when (source) {
        MusicSource.SPOTIFY -> spotifySyncMode
        MusicSource.YOUTUBE -> youtubeSyncMode
        MusicSource.SOUNDCLOUD -> soundCloudSyncMode
        MusicSource.LOCAL, MusicSource.BOTH, MusicSource.CLOUD -> spotifySyncMode
    }

    private companion object {
        const val TAG = "SyncPreferences"

        /** The accounts with a Refresh/Accumulate switch, and the sign-in each reads. */
        val SWEPT_ACCOUNTS = listOf(
            MusicSource.SPOTIFY to AuthService.SPOTIFY,
            MusicSource.YOUTUBE to AuthService.YOUTUBE_MUSIC,
        )
    }
}
