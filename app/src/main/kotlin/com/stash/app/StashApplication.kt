package com.stash.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.SingletonImageLoader
import android.util.Log
import com.stash.core.data.db.dao.ArtistProfileCacheDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.lastfm.LastFmScrobbler
import com.stash.core.media.listening.ListeningRecorder
import com.stash.core.data.repository.MusicRepositoryImpl
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.data.download.ytdlp.YtDlpManager
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
    }
}
