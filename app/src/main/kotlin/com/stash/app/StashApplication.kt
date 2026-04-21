package com.stash.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.SingletonImageLoader
import android.util.Log
import com.stash.core.data.db.dao.ArtistProfileCacheDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.lastfm.LastFmScrobbler
import com.stash.core.data.mix.StashMixDefaults
import com.stash.core.media.listening.ListeningRecorder
import com.stash.core.data.repository.MusicRepositoryImpl
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.data.download.ytdlp.YtDlpManager
import com.stash.core.data.sync.workers.StashDiscoveryWorker
import com.stash.core.data.sync.workers.StashMixRefreshWorker
import com.stash.core.data.sync.workers.TagEnrichmentWorker
import com.stash.core.data.sync.workers.UpdateCheckWorker
import com.stash.data.download.ytdlp.YtDlpUpdateWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Implements [Configuration.Provider] so WorkManager uses the Hilt-provided
 * [HiltWorkerFactory] instead of the default reflection-based factory.
 * The default [androidx.startup.InitializationProvider] initializer for
 * WorkManager is removed in AndroidManifest.xml so that this manual
 * configuration takes effect.
 */
@HiltAndroidApp
class StashApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var musicRepository: MusicRepositoryImpl

    @Inject
    lateinit var syncNotificationManager: SyncNotificationManager

    @Inject
    lateinit var ytDlpManager: YtDlpManager

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var artistProfileCacheDao: ArtistProfileCacheDao

    @Inject
    lateinit var playlistDao: PlaylistDao

    @Inject
    lateinit var listeningRecorder: ListeningRecorder

    @Inject
    lateinit var lastFmScrobbler: LastFmScrobbler

    @Inject
    lateinit var stashMixRecipeDao: StashMixRecipeDao

    /** Application-scoped coroutine scope for one-shot startup tasks. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Supply the custom [Configuration] that tells WorkManager to use the
     * Hilt-injected [HiltWorkerFactory], which enables @AssistedInject
     * constructors in all @HiltWorker classes.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Install the app-wide Coil ImageLoader synchronously so it is ready
        // for the first Compose frame (and any AsyncImage composed before any
        // async startup work completes).
        SingletonImageLoader.setSafe { ctx -> CoilConfiguration.build(ctx, okHttpClient) }
        syncNotificationManager.createChannels()
        applicationScope.launch {
            musicRepository.runMigrations()
        }
        applicationScope.launch {
            ytDlpManager.initialize()
            // Kick a background warmup extraction right after init. Primes the
            // player-JS + QuickJS caches so the first real user preview doesn't
            // pay the ~14 s cold-start cost. Serial with initialize() because
            // warmUp() requires [YtDlpManager.initialized].
            ytDlpManager.warmUp()
        }
        // Warm up music.youtube.com TLS + DNS in the first 2s of launch so
        // the first search request doesn't pay the full handshake cost.
        applicationScope.launch {
            runCatching {
                okHttpClient.newCall(
                    Request.Builder().url("https://music.youtube.com/").head().build(),
                ).execute().close()
            }
        }
        YtDlpUpdateWorker.schedulePeriodicUpdate(this)
        UpdateCheckWorker.schedulePeriodicCheck(this)

        // Stash Mixes scheduling. Daily refresh for mix regeneration,
        // separate daily tag enrichment (unmetered+charging), separate
        // daily discovery-queue drain (unmetered+charging). All three
        // are idempotent periodic registrations.
        applicationScope.launch {
            maybeReseedStashMixes()
            StashMixDefaults.seedIfNeeded(stashMixRecipeDao)
            maybeRetuneStashDiscover()
            // Fire a one-shot refresh on first launch so mixes populate
            // without waiting for the 24-hour periodic cycle. Subsequent
            // one-shots are safe (unique-work policy = REPLACE).
            StashMixRefreshWorker.enqueueOneTime(this@StashApplication)
        }
        StashMixRefreshWorker.schedulePeriodic(this)
        TagEnrichmentWorker.schedulePeriodic(this)
        StashDiscoveryWorker.schedulePeriodic(this)
        // Also fire a one-shot check on every cold start so a release pushed
        // between periodic-worker windows surfaces within seconds of the
        // next launch — the 24-hour periodic worker alone can leave users
        // waiting up to 48 hours when Android Doze defers the fire.
        UpdateCheckWorker.enqueueOneTimeCheck(this)
        applicationScope.launch { maybeInvalidateArtistCache() }
        applicationScope.launch { maybeEnableYouTubePlaylistSync() }
        applicationScope.launch { maybeHideEmptyYouTubePlaylists() }

        // Start the local listening-history recorder + optional Last.fm
        // scrobbler. Both are safe to start unconditionally — the scrobbler
        // no-ops until a session key is stored, and the recorder just
        // observes the player regardless of whether scrobbling is on.
        listeningRecorder.start()
        lastFmScrobbler.start()
    }

    /**
     * Wipe the artist-profile cache exactly once after a parser-format
     * upgrade. Rows written by the pre-fix parser contain empty Popular
     * lists; without invalidation they'd be served for the full 6-hour TTL.
     * A SharedPreferences flag bumped to [ARTIST_CACHE_VERSION] ensures the
     * wipe runs exactly once per install.
     */
    private suspend fun maybeInvalidateArtistCache() {
        val prefs = getSharedPreferences("stash_migrations", MODE_PRIVATE)
        val stored = prefs.getInt("artist_cache_version", 0)
        if (stored < ARTIST_CACHE_VERSION) {
            artistProfileCacheDao.clearAll()
            prefs.edit().putInt("artist_cache_version", ARTIST_CACHE_VERSION).apply()
        }
    }

    /**
     * One-shot reset of the Stash Mix recipe set. Used when the shipped
     * defaults change meaningfully — e.g. the 0.4.1 switch from the
     * original seven recipes to a single "Stash Discover" flagship. We
     * delete every builtin recipe *and* its materialized playlist (which
     * also removes its playlist_tracks rows via FK cascade), then let
     * [StashMixDefaults.seedIfNeeded] repopulate with whatever the new
     * defaults are. Gated by [STASH_MIX_RECIPE_VERSION] so each rollout
     * runs exactly once per install.
     *
     * User-created mixes are untouched — only `is_builtin = 1` rows get
     * wiped, so a future custom mix-builder doesn't lose data here.
     */
    private suspend fun maybeReseedStashMixes() {
        val prefs = getSharedPreferences("stash_migrations", MODE_PRIVATE)
        val stored = prefs.getInt("stash_mix_recipe_version", 0)
        if (stored < STASH_MIX_RECIPE_VERSION) {
            val playlistIds = stashMixRecipeDao.getBuiltinPlaylistIds()
            val removedRecipes = stashMixRecipeDao.deleteAllBuiltins()
            playlistIds.forEach { playlistDao.deleteById(it) }
            Log.i(
                "StashMigration",
                "maybeReseedStashMixes: removed $removedRecipes builtin recipes " +
                    "+ ${playlistIds.size} materialized playlists",
            )
            prefs.edit()
                .putInt("stash_mix_recipe_version", STASH_MIX_RECIPE_VERSION)
                .apply()
        }
    }

    /**
     * One-shot tuning migration that updates an existing "Stash Discover"
     * recipe's knobs in place — no wipe, no cascade. Gated by
     * [STASH_DISCOVER_TUNING_VERSION] so each ratio/length change ships
     * exactly once per install. Fresh installs skip this because
     * [StashMixDefaults] already seeds with the new values.
     */
    private suspend fun maybeRetuneStashDiscover() {
        val prefs = getSharedPreferences("stash_migrations", MODE_PRIVATE)
        val stored = prefs.getInt("stash_discover_tuning_version", 0)
        if (stored < STASH_DISCOVER_TUNING_VERSION) {
            val updated = stashMixRecipeDao.retuneBuiltin(
                name = "Stash Discover",
                discoveryRatio = 0.6f,
                freshnessWindowDays = 14,
                targetLength = 50,
            )
            if (updated > 0) {
                Log.i(
                    "StashMigration",
                    "Retuned Stash Discover to discovery_ratio=0.6 ($updated row)",
                )
            }
            prefs.edit()
                .putInt("stash_discover_tuning_version", STASH_DISCOVER_TUNING_VERSION)
                .apply()
        }
    }

    /**
     * Retroactively enables `sync_enabled = 1` on every YouTube playlist in
     * the local DB exactly once. Fixes the parity gap where YouTube
     * playlists discovered before the Sync-preferences UI was extended to
     * YouTube got stuck at `sync_enabled = 0` and were silently skipped by
     * DiffWorker. Gated by [YOUTUBE_SYNC_ENABLE_VERSION] so it runs at most
     * once per install, no matter how many times the app restarts.
     */
    private suspend fun maybeEnableYouTubePlaylistSync() {
        val prefs = getSharedPreferences("stash_migrations", MODE_PRIVATE)
        val stored = prefs.getInt("youtube_sync_enable_version", 0)
        if (stored < YOUTUBE_SYNC_ENABLE_VERSION) {
            val updated = playlistDao.enableAllYouTubePlaylistSync()
            Log.i(
                "StashMigration",
                "maybeEnableYouTubePlaylistSync: flipped $updated rows to sync_enabled=1",
            )
            prefs.edit()
                .putInt("youtube_sync_enable_version", YOUTUBE_SYNC_ENABLE_VERSION)
                .apply()
        }
    }

    /**
     * Hides stale YouTube playlists that have zero linked tracks. These
     * are leftovers from syncs that ran before the Option-A auto-enable
     * fix — they got created as empty shells and never populated, but
     * still render as dead "0 track" cards on the Home screen. DiffWorker
     * will re-activate them if the same mix reappears in a future sync.
     * Gated by [YOUTUBE_HIDE_EMPTY_VERSION] so it runs at most once.
     */
    private suspend fun maybeHideEmptyYouTubePlaylists() {
        val prefs = getSharedPreferences("stash_migrations", MODE_PRIVATE)
        val stored = prefs.getInt("youtube_hide_empty_version", 0)
        if (stored < YOUTUBE_HIDE_EMPTY_VERSION) {
            val hidden = playlistDao.hideEmptyYouTubePlaylists()
            Log.i(
                "StashMigration",
                "maybeHideEmptyYouTubePlaylists: hid $hidden empty playlist(s)",
            )
            prefs.edit()
                .putInt("youtube_hide_empty_version", YOUTUBE_HIDE_EMPTY_VERSION)
                .apply()
        }
    }

    companion object {
        /**
         * Bump whenever a parser change makes existing cached rows produce
         * a worse UX than a fresh fetch. Current bump (v1) invalidates rows
         * written before the 2026-04-17 Popular-shelf title-matching fix.
         */
        private const val ARTIST_CACHE_VERSION = 1

        /**
         * Bump when [maybeEnableYouTubePlaylistSync] needs to run again.
         * Current bump (v1) is the initial rollout that flips every
         * pre-existing YouTube playlist to `sync_enabled = 1` so the Option
         * A auto-download default takes effect for users whose playlists
         * already live in the DB.
         */
        private const val YOUTUBE_SYNC_ENABLE_VERSION = 1

        /**
         * Bump when [maybeHideEmptyYouTubePlaylists] needs to run again.
         * v1 is the initial rollout that hides stale empty "My Mix N"
         * shells left over from pre-fix syncs.
         */
        private const val YOUTUBE_HIDE_EMPTY_VERSION = 1

        /**
         * Bump when the shipped Stash Mix recipe set changes in a way
         * that should wipe pre-existing builtins on upgrade. v1 = the
         * 0.4.1 switch from 7 recipes to a single "Stash Discover".
         */
        private const val STASH_MIX_RECIPE_VERSION = 1

        /**
         * Bump when the built-in Stash Discover recipe's tunables change
         * and existing installs should adopt them. v1 = 2026-04-21 bump
         * of discovery_ratio from 0.25 → 0.6 after audit showed the mix
         * was 100% library.
         */
        private const val STASH_DISCOVER_TUNING_VERSION = 1
    }
}
