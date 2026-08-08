package com.stash.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import coil3.SingletonImageLoader
import android.util.Log
import com.stash.core.data.db.dao.ArtistProfileCacheDao
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.diagnostics.CrashReporter
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lastfm.LastFmScrobbler
import com.stash.core.data.youtube.YouTubeHistoryScrobbler
import com.stash.core.data.mix.StashMixDefaults
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.media.listening.ListeningRecorder
import com.stash.core.data.repository.MusicRepositoryImpl
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.data.download.backfill.MetadataBackfillScheduler
import com.stash.data.download.ytdlp.YtDlpManager
import com.stash.core.data.sync.workers.ArtBackfillWorker
import com.stash.core.data.sync.workers.ArtistImageBackfillWorker
import com.stash.core.data.sync.workers.AutoSaveScrobbler
import com.stash.core.data.sync.workers.DiscoveryDownloadWorker
import com.stash.core.data.sync.workers.QualityInfoBackfillWorker
import com.stash.core.data.sync.workers.LoudnessBackfillWorker
import com.stash.core.data.sync.workers.StashDiscoveryWorker
import com.stash.core.data.sync.workers.StashMixRefreshWorker
import com.stash.core.data.sync.workers.TagEnrichmentWorker
import com.stash.core.data.sync.workers.TrackInfoEnrichmentWorker
import com.stash.core.data.sync.workers.UpdateCheckWorker
import com.stash.core.data.sync.workers.constraintsForManualTrigger
import com.stash.core.media.streaming.KennyyHealthProbe
import com.stash.core.media.streaming.SquidCookieAutoRefresher
import com.stash.data.download.lossless.LosslessRetryScheduler
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
    lateinit var youTubeHistoryScrobbler: YouTubeHistoryScrobbler

    @Inject
    lateinit var autoSaveScrobbler: AutoSaveScrobbler

    @Inject
    lateinit var listenSinkCoordinator: com.stash.core.data.listen.ListenSinkCoordinator

    @Inject
    lateinit var stashMixRecipeDao: StashMixRecipeDao

    @Inject
    lateinit var discoveryQueueDao: DiscoveryQueueDao

    @Inject
    lateinit var downloadQueueDao: DownloadQueueDao

    @Inject
    lateinit var trackDao: TrackDao

    @Inject
    lateinit var audioExtractor: com.stash.core.data.audio.AudioDurationExtractor

    @Inject
    lateinit var stashMixPreference: com.stash.core.data.prefs.StashMixPreference

    @Inject
    lateinit var downloadNetworkPreference: DownloadNetworkPreference

    @Inject
    lateinit var losslessSourcePreferences: com.stash.data.download.lossless.LosslessSourcePreferences

    @Inject
    lateinit var streamingPreference: com.stash.core.data.prefs.StreamingPreference

    @Inject
    lateinit var playbackModePreference: com.stash.core.data.prefs.PlaybackModePreference

    @Inject
    lateinit var streamingQualityPreferences: com.stash.data.download.prefs.StreamingQualityPreferences

    @Inject
    lateinit var connectivityMonitor: com.stash.core.media.streaming.ConnectivityMonitor

    /**
     * v0.9.17: eager-bound observer that enqueues [LosslessRetryWorker]
     * on cookie change / lastKnownBadCookie clear / circuit-breaker
     * reset events. `lateinit` + `@Inject` makes Hilt construct the
     * singleton when this field is first touched at [onCreate]; we then
     * call its `start()` to wire up the three reactive collectors.
     */
    @Inject
    lateinit var losslessRetryScheduler: LosslessRetryScheduler

    /**
     * Auto-refresher for the squid.wtf captcha cookie. Activated by
     * the ProcessLifecycle observer registered in [onCreate] — only
     * runs when (1) Kennyy is unhealthy AND (2) the app is in the
     * foreground/STARTED window. Idle no-op otherwise.
     */
    @Inject
    lateinit var squidCookieAutoRefresher: SquidCookieAutoRefresher

    /**
     * Cold-start Kennyy health probe. Started by the ProcessLifecycle
     * observer registered in [onCreate] alongside [squidCookieAutoRefresher]
     * so it runs an immediate ground-truth check on app STARTED, setting
     * Kennyy health before the first play.
     */
    @Inject
    lateinit var kennyyHealthProbe: KennyyHealthProbe

    /**
     * Writes uncaught exceptions to `cacheDir/crashes/` so the user can
     * later share the latest report from Settings → Diagnostics. Installed
     * as the first thing after super.onCreate() so it catches errors from
     * every subsequent init step.
     */
    @Inject
    lateinit var crashReporter: CrashReporter

    /**
     * Captures a rolling tail of logcat to `cacheDir/diagnostics/` so the
     * user can attach recent logs alongside a crash report when sharing
     * from Settings → Diagnostics. Started immediately after the crash
     * reporter so the tail begins as early as possible.
     */
    @Inject
    lateinit var logcatCapture: com.stash.core.data.diagnostics.LogcatCapture

    /**
     * v0.9.35: once-per-version auto-enqueue gate for [MetadataBackfillWorker].
     * Idempotent — re-installing the same binary doesn't re-fire the worker.
     */
    @Inject
    lateinit var metadataBackfillScheduler: MetadataBackfillScheduler

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
        // First thing after super: install the crash-to-file handler so any
        // exception thrown by the init steps below still produces a report
        // the user can share from Settings → Diagnostics. The handler
        // chains through to the platform default so the OS still records
        // the crash and the process exits cleanly.
        crashReporter.install()
        // Begin capturing the logcat tail as early as possible so the most
        // recent logs are available to attach when the user shares a report.
        logcatCapture.start()
        // Install the app-wide Coil ImageLoader synchronously so it is ready
        // for the first Compose frame (and any AsyncImage composed before any
        // async startup work completes).
        SingletonImageLoader.setSafe { ctx -> CoilConfiguration.build(ctx, okHttpClient) }
        syncNotificationManager.createChannels()
        // Squid cookie auto-refresher: start when the app moves to STARTED,
        // stop when it moves to STOPPED. ProcessLifecycle is the right
        // scope vs Activity-scoped because rotation/recreation shouldn't
        // interrupt a refresh in progress. The refresher itself gates on
        // KennyyHealthMonitor.isHealthy, so it stays idle when Kennyy is
        // up. Registered here (before the applicationScope.launch blocks)
        // so it's observing as early as possible; the first STARTED event
        // doesn't fire until after onCreate completes anyway.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    // PARKED 2026-07-01: kennyy + squid are out of the lossless
                    // chain (hosts down for us — see
                    // LosslessSourceRegistry.PARKED_SOURCE_IDS), so their
                    // background keep-alives are parked too: no point probing
                    // kennyy health or refreshing the squid captcha cookie for
                    // sources nothing consults. Uncomment on re-enable.
                    // squidCookieAutoRefresher.start()
                    // kennyyHealthProbe.start()
                }

                override fun onStop(owner: LifecycleOwner) {
                    // squidCookieAutoRefresher.stop()
                    // kennyyHealthProbe.stop()
                }
            },
        )
        applicationScope.launch {
            // Best-effort: a startup DB write that throws (e.g.
            // SQLiteFullException on a device the user filled with FLAC) must
            // not take down onCreate on EVERY launch — that's a crash loop from
            // housekeeping. Degrade like the sibling repairs below.
            runCatching { musicRepository.runMigrations() }
                .onFailure { Log.w("StashStartup", "runMigrations failed", it) }
        }
        // Reset stale IN_PROGRESS download_queue rows left over from an
        // interrupted worker run / process death. The worker bulk-marks rows
        // IN_PROGRESS and relies on the NEXT run's resetStaleInProgress() to
        // flip leftovers back — but that next run may never come (e.g. in
        // streaming mode the drain is skipped), so they linger forever showing
        // "downloading". At a fresh process start no worker is running, so any
        // IN_PROGRESS row is by definition stale and safe to reset.
        applicationScope.launch {
            runCatching {
                val reset = downloadQueueDao.resetStaleInProgress()
                if (reset > 0) Log.i("StashStartup", "reset $reset stale IN_PROGRESS download rows -> PENDING")
            }.onFailure { Log.w("StashStartup", "stale IN_PROGRESS reset failed", it) }
        }
        applicationScope.launch {
            runCatching { musicRepository.ensureDownloadsMixSeeded() }
                .onFailure { Log.w("StashStartup", "ensureDownloadsMixSeeded failed", it) }
        }
        // One-shot repair (2026-07-05): pre-v0.9.73 lyrics fetches stamped
        // transient failures (timeouts/429s/DNS during bulk bursts) as
        // permanent 0L misses — forensics showed ~72% of "No lyrics found"
        // tracks have lyrics available. Reset those stamps to NULL once so
        // each track re-fetches on its next sheet open. Post-fix 0L stamps
        // are trustworthy, hence the never-again preference gate.
        applicationScope.launch {
            runCatching {
                val prefs = getSharedPreferences("stash_migrations", MODE_PRIVATE)
                if (!prefs.getBoolean(LYRICS_MISS_RESET_KEY, false)) {
                    val reset = trackDao.resetMissedLyricsStamps()
                    prefs.edit().putBoolean(LYRICS_MISS_RESET_KEY, true).apply()
                    Log.i("StashStartup", "reset $reset poisoned lyrics miss-stamps -> NULL")
                }
            }.onFailure { Log.w("StashStartup", "lyrics miss-stamp reset failed", it) }
        }
        // (The lossless prefetch cache now prunes itself on insert — see
        // LosslessUrlPrefetcher.warmUp. It used to be swept by a 60s loop
        // launched here, which woke for the entire life of the process to
        // sweep a map that is empty unless the user is browsing.)
        applicationScope.launch {
            // Best-effort prewarm: initialize, freshen yt-dlp to the latest
            // nightly, and warm the player-JS + QuickJS + EJS caches so the
            // first real user preview/download pays neither the ~14 s
            // cold-start cost nor the nightly-update latency. This shares the
            // once-per-session gate with the download path (DownloadExecutor),
            // so whichever runs first does the work and the other returns
            // immediately — downloads are still hard-gated on a fresh binary.
            //
            // Gated, because this is speculative work with a real price: a
            // nightly binary download plus a throwaway YouTube extraction
            // through QuickJS, on EVERY cold start, for a user who may never
            // preview or stream anything. Offline it cannot even succeed.
            //
            // Skipping it in offline mode does not slow downloads down where
            // anyone can feel it: the download path calls ensureFreshened()
            // itself, inside a worker, where the one-off cost is invisible.
            // The latency this prewarm exists to hide is the first PREVIEW,
            // and previews only happen with streaming on.
            if (connectivityMonitor.isConnected() && streamingPreference.current()) {
                ytDlpManager.ensureFreshened()
            }
        }
        // Warm up music.youtube.com TLS + DNS in the first 2s of launch so
        // the first search request doesn't pay the full handshake cost.
        // Search still works in offline mode, so this is gated on having a
        // network at all rather than on the streaming preference — offline
        // it is a guaranteed-to-fail request that wakes the radio.
        applicationScope.launch {
            if (!connectivityMonitor.isConnected()) return@launch
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
        // v0.9.26 (issues #56, #57): all Stash Mix infrastructure is gated
        // on the user-facing opt-out toggle. When disabled, the workers
        // never schedule (and any survivors from a previous install are
        // cancelled by MusicRepositoryImpl.applyStashMixesEnabled on
        // first toggle), the built-in recipes/playlists stay inactive,
        // and Home/Library don't surface them.
        applicationScope.launch {
            val stashMixesEnabled = stashMixPreference.current()
            if (!stashMixesEnabled) {
                // Best-effort cancel of any periodic work left over from an
                // earlier run where the toggle was on. Idempotent.
                val wm = WorkManager.getInstance(applicationContext)
                listOf(
                    "stash_mix_refresh",
                    "stash_discovery",
                    "stash_tag_enrichment",
                    "stash_track_info_enrichment",
                    "discovery_download",
                ).forEach { wm.cancelUniqueWork(it) }
                return@launch
            }
            maybeReseedStashMixes()
            StashMixDefaults.seedIfNeeded(stashMixRecipeDao)
            maybeRetuneStashDiscover()
            maybeRetuneStashMixes()
            maybeRemoveRetiredBuiltinMixes()
            maybeCleanupDiscoveryLibraryHits()
            // Fire a one-shot refresh on first launch so mixes populate
            // without waiting for the 24-hour periodic cycle. Subsequent
            // one-shots are safe (unique-work policy = REPLACE).
            StashMixRefreshWorker.enqueueOneTime(this@StashApplication)
            StashMixRefreshWorker.schedulePeriodic(this@StashApplication)
            // v0.9.20: drain orphan discovery download rows from prior version
            // installs (stubs that StashDiscoveryWorker created in PR 3 era but
            // were never downloaded because the sync-chain TrackDownloadWorker
            // was always sync-gated). Respects user's network preference; runs
            // when constraints are satisfied.
            val mode = downloadNetworkPreference.current()
            DiscoveryDownloadWorker.enqueueOneTime(
                this@StashApplication,
                constraintsForManualTrigger(mode),
            )
            TagEnrichmentWorker.schedulePeriodic(this@StashApplication, mode)
            StashDiscoveryWorker.schedulePeriodic(this@StashApplication, mode)
            TrackInfoEnrichmentWorker.schedulePeriodic(this@StashApplication)
            // First-launch one-shot so users with a fresh install don't
            // wait 24h for the first enrichment pass. KEEP policy means
            // a re-launch before the worker completes doesn't re-enqueue.
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                "stash_track_info_enrichment_oneshot",
                androidx.work.ExistingWorkPolicy.KEEP,
                androidx.work.OneTimeWorkRequestBuilder<TrackInfoEnrichmentWorker>().build(),
            )
        }
        LoudnessBackfillWorker.schedulePeriodic(this)
        // Repair missing album_art_url on tracks downloaded before 0.5.3 —
        // primarily Stash Discover candidates whose match pipeline surfaced
        // no thumbnail (see ArtBackfillWorker KDoc). KEEP policy means the
        // worker is a no-op on every subsequent launch once it completes.
        ArtBackfillWorker.enqueueOneTime(this)
        // Artist photos for the Library Artists tab: resolve each displayed
        // artist's official avatar via YT Music search into artist_images.
        // KEEP — one launch's enqueue is enough; a re-launch is a no-op.
        ArtistImageBackfillWorker.enqueueOneTime(this)
        // v0.9.11: kick a background sweep that fills in bit-depth +
        // sample-rate for tracks downloaded before the columns existed.
        // The flag is set immediately on enqueue (not after success) so
        // an interrupted worker doesn't re-trigger on every launch — the
        // manual "Refresh quality info" button in Settings → Library
        // Health covers any rows that slipped through, and the worker's
        // own predicate is idempotent.
        maybeEnqueueQualityBackfill()
        maybeEnqueueBlocklistIntegrity()
        // Also fire a one-shot check on every cold start so a release pushed
        // between periodic-worker windows surfaces within seconds of the
        // next launch — the 24-hour periodic worker alone can leave users
        // waiting up to 48 hours when Android Doze defers the fire.
        UpdateCheckWorker.enqueueOneTimeCheck(this)
        applicationScope.launch { maybeInvalidateArtistCache() }
        applicationScope.launch { maybeEnableYouTubePlaylistSync() }
        applicationScope.launch { maybeHideEmptyYouTubePlaylists() }
        applicationScope.launch { maybeBackfillCodecsFromExtension() }
        applicationScope.launch { maybeBackfillTrackAlbums() }
        applicationScope.launch { maybePurgeAntraArtifacts() }
        // Seed Playback Mode from the legacy StreamingPreference value for
        // installs that predate the split. Internally idempotent — checks
        // key-presence itself, so no SharedPreferences version gate needed
        // here (unlike the other one-shots in this file).
        applicationScope.launch {
            runCatching { playbackModePreference.seedFromLegacyIfAbsent(streamingPreference.current()) }
                .onFailure { Log.w("StashStartup", "playback mode seed failed", it) }
        }
        // One-shot: seed the streaming Wi-Fi quality tier from the user's
        // current download tier on first run, so existing users' streaming
        // quality inherits their download choice instead of silently changing.
        // migrateIfNeeded() is internally idempotent (no-op after first run).
        applicationScope.launch { streamingQualityPreferences.migrateIfNeeded() }
        // Auto-enqueue the v0.9.35 metadata backfill once per version.
        // Idempotent (re-installing the same binary does not re-enqueue).
        applicationScope.launch {
            metadataBackfillScheduler.scheduleIfNeeded()
        }

        // v0.9.30 Path A: AvailabilityCheckWorker + AvailabilityRecheckWorker
        // were removed when Library reverted to downloaded-only. They populated
        // is_streamable for the "show streamable rows in Library" surface that
        // no longer exists. Streaming-tap availability is determined per-call
        // by KennyySource at the moment the user taps a search result.

        // Start the local listening-history recorder + optional Last.fm
        // and YouTube Music scrobbler. All are safe to start unconditionally —
        // they no-op until configuration/authentication is in place, and the
        // recorder just observes the player regardless of whether scrobbling is on.
        listeningRecorder.start()
        lastFmScrobbler.start()
        youTubeHistoryScrobbler.start()
        autoSaveScrobbler.start()
        // Generic listen sinks (ListenBrainz). No-ops until connected.
        listenSinkCoordinator.start()

        // Wire the three reactive triggers (cookie change /
        // lastKnownBadCookie transition / rate-limiter circuit-reset
        // event) that enqueue LosslessRetryWorker for the strict-FLAC
        // deferred set. Touching the @Inject field here forces Hilt
        // to construct the singleton if Dagger hasn't already.
        losslessRetryScheduler.start()
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
     * One-shot cleanup after the antra source was removed: deletes the
     * harvested antra.hoshi.cfd session cookie / cf_clearance / username and
     * the retired `force_antra_only` toggle from existing installs, so no
     * stale third-party login session lingers on disk. Runs exactly once per
     * install via a SharedPreferences version flag.
     */
    private suspend fun maybePurgeAntraArtifacts() {
        val prefs = getSharedPreferences("stash_migrations", MODE_PRIVATE)
        val stored = prefs.getInt("antra_purge_version", 0)
        if (stored < ANTRA_PURGE_VERSION) {
            losslessSourcePreferences.purgeAntraCredentials()
            streamingPreference.purgeRetiredKeys()
            prefs.edit().putInt("antra_purge_version", ANTRA_PURGE_VERSION).apply()
        }
    }

    /**
     * v0.9.13: One-shot data correction. Tracks downloaded before v0.9.11
     * defaulted to `file_format = 'opus'` regardless of the actual codec
     * (legacy schema default). New downloads get the correct value via
     * `TrackDao.setFormatAndQuality`, but every pre-existing row was
     * stuck mislabeled — so a downloaded `.flac` file would render
     * "OPUS · 4233 kbps" in Now Playing and undercount in Home FLAC
     * stats. The Library Health backfill fixes this from disk via
     * MMR but only when the user opens that screen.
     *
     * This migration does the cheap correction at app start: for every
     * downloaded track stuck at the legacy `'opus'` default, look at
     * the file_path extension. If it's a recognized codec, write that
     * codec back. We can do this safely because the download workers
     * always name files with the correct extension; the column was the
     * lossy field, not the path.
     */
    /**
     * v0.9.26 — one-shot pass that fills `tracks.album` for every
     * downloaded row whose column is still empty. Two sources, in order:
     *
     *  1. The file's embedded `album` tag (read via MediaMetadataRetriever).
     *     Works for any track tagged by [com.stash.data.download.files.MetadataEmbedder]
     *     when the album was known at download time (sync path always
     *     knows it; the search path knows it after the v0.9.25 fix).
     *  2. The album folder name from the file path. Stash organises files
     *     as `music/<artist-slug>/<album-slug>/title.ext`, so the
     *     second-to-last path segment is the album slug. We de-kebab it
     *     for a readable title-case fallback. Lossy on capitalisation
     *     and special characters but recovers correctly for almost all
     *     real albums.
     *
     * Albums that can't be resolved by either source stay empty (the
     * track was downloaded before any album context existed). They'll
     * fill in when the user visits the album page in Search via the
     * [com.stash.feature.search.AlbumDiscoveryViewModel] backfill path.
     *
     * Gated by [ALBUM_BACKFILL_VERSION] so each rollout runs once per
     * install. Cheap idempotency — the predicate is `album = ''`, so a
     * partial run picks up where it left off on the next launch.
     */
    private suspend fun maybeBackfillTrackAlbums() {
        val prefs = getSharedPreferences("stash_migrations", MODE_PRIVATE)
        val stored = prefs.getInt("album_backfill_version", 0)
        if (stored >= ALBUM_BACKFILL_VERSION) return

        val rows = runCatching { trackDao.tracksNeedingAlbumBackfill() }
            .onFailure { Log.w("StashMigration", "tracksNeedingAlbumBackfill failed", it) }
            .getOrDefault(emptyList())

        if (rows.isEmpty()) {
            prefs.edit().putInt("album_backfill_version", ALBUM_BACKFILL_VERSION).apply()
            return
        }

        var filledAlbum = 0
        var filledAlbumArtist = 0
        for (row in rows) {
            val path = row.filePath
            // Prefer the embedded tag — it's lossless on capitalisation
            // and special characters.
            val fromTag = runCatching { audioExtractor.extractAlbum(path) }
                .onFailure { e -> Log.w("StashMigration", "extractAlbum failed for ${row.id}: ${e.message}") }
                .getOrNull()
            val albumFolder = java.io.File(path).parentFile
            val artistFolder = albumFolder?.parentFile
            val album = fromTag ?: dekebabTitle(albumFolder?.name)
            if (!album.isNullOrBlank()) {
                runCatching { trackDao.updateAlbumIfEmpty(row.id, album) }
                    .onSuccess { filledAlbum++ }
                    .onFailure { e -> Log.w("StashMigration", "updateAlbumIfEmpty failed for ${row.id}: ${e.message}") }
            }
            // album_artist: lifted from the file's artist folder so the
            // Library Albums query can disambiguate two "Singles" releases
            // by different artists. Lossy on capitalisation (folder names
            // are kebab-cased) but groups correctly — the display name will
            // be whichever artist string we already store per-track once
            // the cleanup is shipped end-to-end.
            val albumArtist = dekebabTitle(artistFolder?.name)
            if (!albumArtist.isNullOrBlank()) {
                runCatching { trackDao.updateAlbumArtistIfEmpty(row.id, albumArtist) }
                    .onSuccess { filledAlbumArtist++ }
                    .onFailure { e -> Log.w("StashMigration", "updateAlbumArtistIfEmpty failed for ${row.id}: ${e.message}") }
            }
        }
        Log.i(
            "StashMigration",
            "maybeBackfillTrackAlbums: scanned ${rows.size} rows, " +
                "filled $filledAlbum album values, $filledAlbumArtist album_artist values",
        )
        prefs.edit().putInt("album_backfill_version", ALBUM_BACKFILL_VERSION).apply()
    }

    /** De-kebab a folder slug back into a readable Title Case string. */
    private fun dekebabTitle(slug: String?): String? =
        slug?.takeIf { it.isNotBlank() }
            ?.replace('-', ' ')
            ?.split(' ')
            ?.joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }

    private suspend fun maybeBackfillCodecsFromExtension() {
        val prefs = getSharedPreferences("stash_migrations", MODE_PRIVATE)
        val stored = prefs.getInt("codec_backfill_version", 0)
        if (stored >= CODEC_BACKFILL_VERSION) return

        val rows = runCatching { trackDao.getOpusDefaultedTracks() }
            .onFailure { Log.w("StashMigration", "getOpusDefaultedTracks failed", it) }
            .getOrDefault(emptyList())

        var updated = 0
        rows.forEach { row ->
            val ext = row.filePath.substringAfterLast('.', "").lowercase()
            if (ext.isBlank() || ext == "opus") return@forEach
            if (ext in KNOWN_CODECS) {
                runCatching { trackDao.updateFileFormat(row.id, ext) }
                    .onSuccess { updated++ }
                    .onFailure { e -> Log.w("StashMigration", "updateFileFormat failed for ${row.id}", e) }
            }
        }
        Log.i(
            "StashMigration",
            "maybeBackfillCodecsFromExtension: scanned ${rows.size}, corrected $updated rows",
        )
        prefs.edit().putInt("codec_backfill_version", CODEC_BACKFILL_VERSION).apply()
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
                discoveryRatio = 1.0f,
                freshnessWindowDays = 14,
                targetLength = 50,
                affinityBias = 0.0f,
                seedStrategy = "TAG_GRAPH",
                moodKeysCsv = "",
                tagSampleDepth = 0,
            )
            if (updated > 0) {
                Log.i(
                    "StashMigration",
                    "Retuned Stash Discover to discovery_ratio=1.0 ($updated row)",
                )
            }
            prefs.edit()
                .putInt("stash_discover_tuning_version", STASH_DISCOVER_TUNING_VERSION)
                .apply()
        }
    }

    /**
     * One-shot builtin-recipe retune, gated by [STASH_MIX_RECIPE_TUNING_VERSION]
     * so each tuning ships exactly once per install. Fresh installs skip it
     * because [StashMixDefaults] already seeds the current values.
     *
     * - v1 (v0.9.20 pivot): Daily Discover + Deep Cuts moved to recommendation-
     *   substrate (85% discovery / 15% library); Deep Cuts went NONE → TRACK_SIMILAR.
     * - v2 (v0.9.40 tag engine): Deep Cuts re-pointed TRACK_SIMILAR → TAG_GRAPH with
     *   tagSampleDepth=15 (fixes it surfacing only already-downloaded library tracks).
     *   Since this iterates every builtin, First Listen (already TAG_GRAPH) is also
     *   re-tuned and — like Deep Cuts — now seeds via the tag engine
     *   (RecipeTagResolver → TagPoolBuilder), falling back to the user's top genres
     *   for builtins with no explicit tags. Daily Discover stays ARTIST_SIMILAR.
     */
    private suspend fun maybeRetuneStashMixes() {
        val prefs = getSharedPreferences("stash_migrations", MODE_PRIVATE)
        val stored = prefs.getInt("stash_mix_recipe_tuning_version", 0)
        if (stored >= STASH_MIX_RECIPE_TUNING_VERSION) return

        var totalUpdated = 0
        for (recipe in StashMixDefaults.ALL.filter { it.isBuiltin }) {
            val updated = stashMixRecipeDao.retuneBuiltin(
                name = recipe.name,
                discoveryRatio = recipe.discoveryRatio,
                freshnessWindowDays = recipe.freshnessWindowDays,
                targetLength = recipe.targetLength,
                affinityBias = recipe.affinityBias,
                seedStrategy = recipe.seedStrategy,
                moodKeysCsv = recipe.moodKeysCsv,
                tagSampleDepth = recipe.tagSampleDepth,
            )
            totalUpdated += updated
        }
        Log.i(
            "StashMigration",
            "Retuned $totalUpdated builtin mix recipe(s) to v$STASH_MIX_RECIPE_TUNING_VERSION",
        )
        prefs.edit()
            .putInt("stash_mix_recipe_tuning_version", STASH_MIX_RECIPE_TUNING_VERSION)
            .apply()
    }

    /**
     * v0.9.40: retire the "Deep Cuts" and "First Listen" built-in mixes —
     * Daily Discover is now the sole built-in; users build the rest via the
     * Mix Builder. Deletes those recipes (CASCADE removes their discovery_queue
     * rows) AND their materialized playlists, leaving Daily Discover and all
     * user-created mixes untouched. Gated by [STASH_MIX_RETIRED_VERSION] so it
     * runs once per install; fresh installs never seed them (see StashMixDefaults).
     */
    private suspend fun maybeRemoveRetiredBuiltinMixes() {
        val prefs = getSharedPreferences("stash_migrations", MODE_PRIVATE)
        if (prefs.getInt("stash_mix_retired_version", 0) >= STASH_MIX_RETIRED_VERSION) return

        val retired = listOf("Deep Cuts", "First Listen")
        val recipes = stashMixRecipeDao.getBuiltinsByName(retired)
        recipes.mapNotNull { it.playlistId }.forEach { playlistDao.deleteById(it) }
        val removed = stashMixRecipeDao.deleteBuiltinsByName(retired)
        Log.i(
            "StashMigration",
            "Retired $removed builtin mix(es) + ${recipes.count { it.playlistId != null }} playlist(s)",
        )
        prefs.edit()
            .putInt("stash_mix_retired_version", STASH_MIX_RETIRED_VERSION)
            .apply()
    }

    /**
     * PR 7 one-time cleanup: delete pre-PR-6 era "library-hit" discovery
     * DONE rows from `discovery_queue`. Pre-PR-6 StashDiscoveryWorker.handle()
     * canonical-deduped Last.fm candidates against the user's library and
     * linked existing library tracks instead of creating discovery stubs —
     * those rows then surfaced in mixes as "discovery survivors" despite
     * being library content. PR 6's seed-stage pre-filter prevents new
     * library-hits; this migration cleans up the backlog.
     *
     * Gated by [STASH_DISCOVERY_LIBRARY_HIT_CLEANUP_VERSION] so the
     * migration runs exactly once per install. Fresh installs after PR 7
     * have nothing to clean up — the query is a fast no-op.
     */
    private suspend fun maybeCleanupDiscoveryLibraryHits() {
        val prefs = getSharedPreferences("stash_migrations", MODE_PRIVATE)
        val stored = prefs.getInt("stash_discovery_library_hit_cleanup_version", 0)
        if (stored >= STASH_DISCOVERY_LIBRARY_HIT_CLEANUP_VERSION) return

        val deleted = discoveryQueueDao.deleteLibraryHitDoneRows()
        Log.i(
            "StashMigration",
            "Cleaned up $deleted pre-PR-6 library-hit discovery rows " +
                "(v$STASH_DISCOVERY_LIBRARY_HIT_CLEANUP_VERSION)",
        )
        prefs.edit()
            .putInt("stash_discovery_library_hit_cleanup_version", STASH_DISCOVERY_LIBRARY_HIT_CLEANUP_VERSION)
            .apply()
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

    /**
     * v0.9.11 first-launch sweep. Enqueues [QualityInfoBackfillWorker]
     * once per install; subsequent launches see the SharedPreferences
     * flag and skip. Constraints require not-low-battery so a Doze
     * cycle doesn't kick this off mid-flight on a dying phone — the
     * worker is purely background polish.
     */
    private fun maybeEnqueueQualityBackfill() {
        val prefs = getSharedPreferences("stash_migrations", MODE_PRIVATE)
        if (prefs.getBoolean("quality_backfill_done_v17", false)) return
        WorkManager.getInstance(applicationContext).enqueue(
            OneTimeWorkRequestBuilder<QualityInfoBackfillWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
        )
        prefs.edit().putBoolean("quality_backfill_done_v17", true).apply()
        Log.i("StashMigration", "maybeEnqueueQualityBackfill: enqueued v17 quality-info backfill")
    }

    /**
     * v0.9.15: One-shot cleanup of tracks that leaked back during the
     * v0.9.13/14 broken-flag era. Walks every tracks row whose stored
     * canonical identity matches `track_blocklist` and tears it down.
     *
     * Uses [androidx.work.ExistingWorkPolicy.KEEP] so a re-launch before
     * the worker completes doesn't re-enqueue. Idempotent on subsequent
     * runs (matched rows are gone after the first pass).
     */
    private fun maybeEnqueueBlocklistIntegrity() {
        androidx.work.WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(
                "blocklist_integrity_v1",
                androidx.work.ExistingWorkPolicy.KEEP,
                androidx.work.OneTimeWorkRequestBuilder<
                    com.stash.core.data.sync.workers.BlocklistIntegrityWorker
                >().build(),
            )
        Log.i("StashMigration", "maybeEnqueueBlocklistIntegrity: enqueued v0.9.15 cleanup sweep")
    }

    companion object {
        /**
         * Never-again gate for the one-shot lyrics miss-stamp repair. NOT
         * version-keyed on purpose: post-fix 0L stamps are genuine misses
         * and re-wiping them on every release would refetch known-absent
         * tracks forever.
         */
        private const val LYRICS_MISS_RESET_KEY = "lyrics_miss_reset_done"

        /**
         * Bump whenever a parser change makes existing cached rows produce
         * a worse UX than a fresh fetch. Current bump (v1) invalidates rows
         * written before the 2026-04-17 Popular-shelf title-matching fix.
         */
        private const val ARTIST_CACHE_VERSION = 1

        /** Bump to re-run [maybePurgeAntraArtifacts] (one-shot antra cleanup). */
        private const val ANTRA_PURGE_VERSION = 1

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
         * that should wipe pre-existing builtins on upgrade.
         *  - v1 = the 0.4.1 switch from 7 recipes to a single
         *    "Stash Discover".
         *  - v2 = the 0.9.16 switch from the single "Stash Discover"
         *    flagship to the three-recipe set (Daily Discover / Deep
         *    Cuts / First Listen) — see [StashMixDefaults].
         */
        private const val STASH_MIX_RECIPE_VERSION = 2

        /**
         * Bump to retire built-in mixes on upgrade without wiping the others.
         *  - v1 = the 0.9.40 removal of "Deep Cuts" + "First Listen"
         *    (Daily Discover and all custom mixes are preserved).
         */
        private const val STASH_MIX_RETIRED_VERSION = 1

        /**
         * Bump when the built-in Stash Discover recipe's tunables change
         * and existing installs should adopt them.
         *  - v1 = 2026-04-21 bump of discovery_ratio from 0.25 → 0.6
         *    after audit showed the mix was 100% library.
         *  - v2 = 2026-04-22 bump to 1.0: Stash Discover is now pure
         *    Last.fm recommendations (library slots removed). Tracks
         *    already downloaded from prior Discovery runs stay in the
         *    mix via the re-link pass; the 20-ish library anchors that
         *    used to fill the non-discovery slots drop on next refresh.
         */
        private const val STASH_DISCOVER_TUNING_VERSION = 2

        /**
         * Bump when the built-in Stash Mix recipes' tunables change and
         * existing installs should adopt them.
         *  - v1 = 2026-05-11 v0.9.20 pivot: Daily Discover + Deep Cuts
         *    move from library-substrate to recommendation-substrate
         *    (85% discovery, 15% library). Deep Cuts switches seed
         *    strategy from NONE to TRACK_SIMILAR.
         */
        private const val STASH_MIX_RECIPE_TUNING_VERSION = 2

        /**
         * Bump when [maybeCleanupDiscoveryLibraryHits] should run again.
         *  - v1 = PR 7 one-time cleanup: removes pre-PR-6 era discovery_queue
         *    DONE rows whose linked track has source != YOUTUBE. Pre-PR-6
         *    StashDiscoveryWorker.handle() canonical-deduped Last.fm
         *    candidates against the library and linked existing library
         *    tracks instead of creating stubs; those rows surfaced in mixes
         *    as "discovery survivors" despite being library content. PR 6's
         *    seed-stage pre-filter prevents new ones from being created.
         */
        private const val STASH_DISCOVERY_LIBRARY_HIT_CLEANUP_VERSION = 1

        /**
         * v0.9.13: bump when [maybeBackfillCodecsFromExtension] should run
         * again. v1 = initial rollout that corrects every downloaded
         * track stuck at the legacy `file_format = 'opus'` default
         * inherited from the pre-v0.9.11 schema, by reading the actual
         * extension off the on-disk file path.
         */
        private const val CODEC_BACKFILL_VERSION = 1

        /**
         * v0.9.26: bump when [maybeBackfillTrackAlbums] should run again.
         * v1 = initial album-only rollout.
         * v2 = album + album_artist rollout, after the v24→v25 migration
         *      added the new column. Existing installs that already ran
         *      v1 (and filled `album`) re-enter the worker to populate
         *      `album_artist` from the file path's artist folder.
         */
        private const val ALBUM_BACKFILL_VERSION = 2

        /** Recognized audio file extensions that the codec-backfill writes back. */
        private val KNOWN_CODECS = setOf(
            "flac", "alac", "wav", "ape", "tta", "wv", "aiff",
            "m4a", "mp3", "ogg", "aac",
        )
    }
}
