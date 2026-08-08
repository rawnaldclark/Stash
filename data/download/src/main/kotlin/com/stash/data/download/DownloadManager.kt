package com.stash.data.download

import android.util.Log
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.mapper.toDomain
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.data.download.BuildConfig
import com.stash.data.download.files.AlbumArtCache
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.MetadataEmbedder
import com.stash.data.download.lyrics.LyricsFetchTrigger
import com.stash.data.download.jiosaavn.JioSaavnResolver
import com.stash.data.download.shared.TrackFinalizer
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.data.download.lossless.LosslessSourceHealthGate
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.LosslessUrlDownloader
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.matching.AlbumMatchExecutor
import com.stash.data.download.matching.DuplicateDetectionService
import com.stash.data.download.matching.MatchScorer
import com.stash.data.download.matching.HybridSearchExecutor
import com.stash.data.download.matching.YtLibraryCanonicalizer
import java.io.File
import com.stash.data.download.model.DownloadProgress
import com.stash.data.download.model.DownloadStatus
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.prefs.toYtDlpArgs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of the full download pipeline for a single track.
 */
sealed class TrackDownloadResult {
    /** Download succeeded. [filePath] is the absolute path on disk. */
    data class Success(val filePath: String) : TrackDownloadResult()

    /** No YouTube match found for the track. [rejectedVideoId] is the best candidate that failed verification. */
    data class Unmatched(val rejectedVideoId: String? = null) : TrackDownloadResult()

    /** Download failed. [error] describes why. */
    data class Failed(val error: String) : TrackDownloadResult()

    /**
     * v0.9.17+: lossless registry returned null and the user opted out
     * of yt-dlp fallback. The track stays in the queue under
     * DownloadStatus.WAITING_FOR_LOSSLESS until LosslessRetryWorker
     * re-resolves successfully.
     */
    data object Deferred : TrackDownloadResult()
}

/** Absolute ceiling (ms) under which a finished file is "sample-length". */
internal const val LOSSLESS_SAMPLE_MAX_MS = 35_000L

/** A degraded file must also be below this fraction of the expected duration. */
internal const val LOSSLESS_SAMPLE_FRACTION = 0.5

/**
 * True when a finished lossless download is a preview stub: a readable
 * duration that is both absolutely short (≤ [LOSSLESS_SAMPLE_MAX_MS]) AND far
 * below the track's known duration (< [LOSSLESS_SAMPLE_FRACTION] of expected).
 * Requires a known expected duration (> 0) and a readable probe; otherwise
 * returns false — never reject on missing data (that's the yt-dlp path's job).
 */
internal fun isLosslessDurationDegraded(probedMs: Long?, expectedMs: Long): Boolean {
    if (probedMs == null || probedMs <= 0L) return false
    if (expectedMs <= 0L) return false
    return probedMs <= LOSSLESS_SAMPLE_MAX_MS && probedMs < expectedMs * LOSSLESS_SAMPLE_FRACTION
}

/**
 * Orchestrates the full download pipeline for a single track:
 *
 * 1. Search YouTube for the track (or use a pre-resolved URL).
 * 2. Score results and select the best match.
 * 3. Download native Opus audio via yt-dlp (metadata embedded via --embed-metadata).
 * 4. Move the file to the organized artist/album directory.
 *
 * Concurrency is limited to 8 simultaneous downloads via a [Semaphore].
 * Real-time progress is emitted through the [progress] shared flow.
 */
@Singleton
class DownloadManager @Inject constructor(
    private val downloadExecutor: DownloadExecutor,
    private val searchExecutor: HybridSearchExecutor,
    private val albumMatchExecutor: AlbumMatchExecutor,
    private val matchScorer: MatchScorer,
    private val duplicateDetection: DuplicateDetectionService,
    private val fileOrganizer: FileOrganizer,
    private val qualityPrefs: QualityPreferencesManager,
    private val ytLibraryCanonicalizer: YtLibraryCanonicalizer,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val lastFmApiClient: LastFmApiClient,
    private val lastFmCredentials: LastFmCredentials,
    private val losslessRegistry: LosslessSourceRegistry,
    private val losslessUrlDownloader: LosslessUrlDownloader,
    private val losslessPrefs: LosslessSourcePreferences,
    private val jioSaavnResolver: JioSaavnResolver,
    private val trackFinalizer: TrackFinalizer,
    private val loudnessMeasurer: com.stash.core.data.audio.LoudnessMeasurer,
    private val metadataEmbedder: MetadataEmbedder,
    private val albumArtCache: AlbumArtCache,
    /**
     * v0.9.36: enqueue a [com.stash.data.lyrics.worker.LyricsFetchWorker]
     * immediately after a successful download stamps
     * `metadata_embedded_at`. The interface lives in `:data:download`
     * (this module) and the production binding in `:app` so
     * `:data:download` stays free of a cyclic `:data:lyrics` dependency.
     */
    private val lyricsFetchTrigger: LyricsFetchTrigger,
    /**
     * Degradation-detection (2026-06-05): probes a finished lossless file's
     * duration to catch a preview-stub whose URL lacked the `range=` marker,
     * and the per-source cooldown gate the duration backstop records into.
     */
    private val audioDurationExtractor: AudioDurationExtractor,
    private val losslessHealthGate: LosslessSourceHealthGate,
) {
    /**
     * Test/build seam for the debug-build lossy-fallback carve-out below.
     * Defaults to the real [BuildConfig.DEBUG] so production behavior is
     * unchanged, but unit tests — which always run under the debug test
     * variant, where BuildConfig.DEBUG is unconditionally true — can flip
     * this to false to actually exercise the release strict-FLAC deferral
     * path instead of it being permanently unreachable in tests.
     */
    internal var forceYoutubeFallbackOnDebugBuilds: Boolean = BuildConfig.DEBUG

    /** Limits concurrent downloads. 8 parallel slots — with native opus (no FFmpeg
     *  transcode) downloads are almost entirely network-bound so more parallelism helps. */
    private val concurrencySemaphore = Semaphore(8)

    private val _progress = MutableSharedFlow<DownloadProgress>(replay = 1)

    /** Emits real-time progress snapshots for all in-flight downloads. */
    val progress: SharedFlow<DownloadProgress> = _progress.asSharedFlow()

    companion object {
        private const val TAG = "DownloadManager"

        /**
         * Max lossless resolve→download→probe attempts per track. Each
         * duration-backstop rejection cools a source down and re-resolves;
         * the cap bounds that retry to the small set of lossless sources
         * (kennyy, squid, + headroom) so it can't spin.
         */
        internal const val MAX_LOSSLESS_FAILOVER_ATTEMPTS = 3
        internal const val MIN_JIOSAAVN_BITRATE_KBPS = 256
        private const val JIOSAAVN_DURATION_TOLERANCE_MS = 8_000L
        private const val JIOSAAVN_DURATION_TOLERANCE_FRACTION = 0.03
    }

    /**
     * Downloads a single track through the full pipeline.
     *
     * @param track          The track to download.
     * @param preResolvedUrl Optional YouTube URL if already known (skips search).
     * @return A [TrackDownloadResult] with either the file path or a detailed error.
     */
    suspend fun downloadTrack(
        track: Track,
        preResolvedUrl: String? = null,
    ): TrackDownloadResult {
        concurrencySemaphore.acquire()
        try {
            return executeDownload(track, preResolvedUrl)
        } finally {
            concurrencySemaphore.release()
        }
    }

    /**
     * Result of URL resolution: the accepted URL (or null) plus the best
     * rejected candidate's video ID for user preview on the unmatched screen.
     */
    private data class ResolveResult(val url: String?, val rejectedVideoId: String? = null)

    /**
     * Executes the download pipeline for a single track.
     */
    private suspend fun executeDownload(track: Track, preResolvedUrl: String?): TrackDownloadResult {
        emitProgress(track.id, 0f, DownloadStatus.MATCHING)

        // Step 0: Lossless source attempt. Attempted whenever lossless
        // is enabled (or the track belongs to a Stash Mix), regardless
        // of whether the caller supplied a preResolvedUrl. Stash Mix
        // tracks are a small curated surface where lossless is worth
        // eating bandwidth even when the user hasn't opted in globally.
        //
        // Historical note: this block used to be wrapped in
        // `if (preResolvedUrl == null)`, which meant YT-Music synced
        // tracks (which always arrive with their video URL preset by
        // DiffWorker) silently bypassed lossless and downloaded as
        // 128kbps m4a. Spotify tracks happened to work because Spotify
        // doesn't expose YouTube ids, so their preResolvedUrl was null.
        // Lifting the guard means "Find in Lossless" semantics now
        // apply to the initial sync path too — see 2026-05-12.
        //
        // On success we short-circuit the YouTube pipeline. On null /
        // failure we fall through to the YouTube path (or defer when
        // fallback is off, per v0.9.17 strict-FLAC).
        val forceLossless = isStashMixTrack(track.id)
        if (forceLossless || losslessPrefs.enabledNow()) {
            val losslessResult = tryLosslessDownload(track, forced = forceLossless)
            if (losslessResult != null) return losslessResult
            // strict-FLAC: lossless returned null AND yt-dlp fallback is off,
            // so defer instead of pulling a lossy opus/m4a from the YouTube
            // path. Applies to EVERY track — including genuinely YouTube-
            // sourced ones — so "fallback off" means no YouTube downloads at
            // all, matching SearchDownloadCoordinator's unconditional gate.
            // (Earlier a track.source==YOUTUBE carve-out let YT-native tracks
            // fall through to yt-dlp here; that leaked opus/m4a downloads with
            // fallback off, so it's removed.) The lone exemption is Stash-Mix
            // tracks (forceLossless=true): the small curated rotating playlist
            // would silently empty if its tracks got stuck in deferral.
            if (!forceLossless && !losslessPrefs.youtubeFallbackEnabledNow()) {
                // Debug/beta builds never bundle lossless credentials (QBDLX_CONFIGURED /
                // ARCOD_CONFIGURED are false, amz needs none but is proxy-outage-prone) —
                // respecting "fallback off" here would permanently strand every beta
                // tester's tracks in WAITING_FOR_LOSSLESS regardless of their toggle.
                // Force the lossy fallback chain on debug builds; release keeps
                // real strict-FLAC. The chain is JioSaavn first, then YouTube.
                if (forceYoutubeFallbackOnDebugBuilds) {
                    Log.i(
                        TAG,
                        "debug build: forcing lossy fallback for '${track.artist} - ${track.title}' " +
                            "despite fallback-off (no lossless tokens on beta builds)",
                    )
                } else {
                    Log.i(
                        TAG,
                        "deferring '${track.artist} - ${track.title}': lossless unavailable, fallback off",
                    )
                    return TrackDownloadResult.Deferred
                }
            }
        }

        // Step 1: Try the high-bitrate AAC fallback before YouTube. This is
        // deliberately outside the lossless registry: AAC is lossy, and must
        // never satisfy a strict-lossless request. If strict fallback is off,
        // the branch above already returned Deferred before reaching here.
        val jioSaavnResult = tryJioSaavnDownload(track)
        if (jioSaavnResult != null) return jioSaavnResult

        // Step 2: Resolve YouTube URL
        val resolveResult = if (preResolvedUrl != null) ResolveResult(url = preResolvedUrl) else resolveUrl(track)
        if (resolveResult.url == null) {
            emitProgress(track.id, 0f, DownloadStatus.UNMATCHED)
            return TrackDownloadResult.Unmatched(rejectedVideoId = resolveResult.rejectedVideoId)
        }
        val youtubeUrl = resolveResult.url

        // If resolveUrl routed through YtLibraryCanonicalizer, the DB
        // now has the ATV's refreshed title/album/album_art/duration —
        // but the `track` object passed in here is still the stale
        // in-memory copy from TrackDownloadWorker. Re-fetch so
        // commitDownload's filename derivation uses the new title.
        // Fallback to the original in-memory track if the row was
        // deleted mid-flight (rare; better to still download than bail).
        val effectiveTrack = trackDao.getById(track.id)?.toDomain() ?: track
        if (effectiveTrack.title != track.title) {
            Log.i(
                TAG,
                "executeDownload: track metadata refreshed by canonicalizer " +
                    "'${track.title}' → '${effectiveTrack.title}'",
            )
        }

        emitProgress(track.id, 0.1f, DownloadStatus.DOWNLOADING)

        // Step 2: Get quality args from user preferences
        val qualityTier = qualityPrefs.qualityTier.first()
        val qualityArgs = qualityTier.toYtDlpArgs()

        // Step 3: Download via yt-dlp
        val tempDir = fileOrganizer.getTempDir()
        val tempFilename = "dl_${track.id}"

        val dlResult = downloadExecutor.download(
            url = youtubeUrl,
            outputDir = tempDir,
            filename = tempFilename,
            qualityArgs = qualityArgs,
            onProgress = { progress ->
                emitProgress(track.id, 0.1f + progress * 0.7f, DownloadStatus.DOWNLOADING)
            },
        )

        val downloadedFile = when (dlResult) {
            is DownloadResult.Success -> dlResult.file
            is DownloadResult.YtDlpError -> {
                emitProgress(track.id, 0f, DownloadStatus.FAILED)
                return TrackDownloadResult.Failed("yt-dlp: ${dlResult.message.take(500)}")
            }
            is DownloadResult.NoOutput -> {
                emitProgress(track.id, 0f, DownloadStatus.FAILED)
                val detail = buildString {
                    append("yt-dlp produced no output file.")
                    dlResult.stderr?.let { append(" stderr: ${it.take(300)}") }
                }
                return TrackDownloadResult.Failed(detail)
            }
            is DownloadResult.Error -> {
                emitProgress(track.id, 0f, DownloadStatus.FAILED)
                return TrackDownloadResult.Failed("Error: ${dlResult.message}")
            }
        }

        // Embed clean Stash-side tags + cover art into the file before
        // commit. yt-dlp's --embed-metadata leaves YouTube-flavoured tags
        // (uploader, video title); our pass overwrites them with the clean
        // Spotify/YT-Music identity already on the Track row. Failure is
        // non-fatal: the file remains playable and yt-dlp's fallback tags
        // stay in place.
        val art = runCatching { albumArtCache.resolveArt(effectiveTrack) }.getOrNull()
        runCatching { metadataEmbedder.embedMetadata(downloadedFile, effectiveTrack, art) }
            .onFailure { Log.w(TAG, "metadata embed failed for ${track.id}: ${it.message}") }

        // Metadata + cover art written above by MetadataEmbedder. yt-dlp's
        // --embed-metadata still runs as a fallback layer (see toYtDlpArgs).

        emitProgress(track.id, 0.9f, DownloadStatus.PROCESSING)

        // Step 4: Move to organized destination (internal or user-selected SAF target).
        val committed = fileOrganizer.commitDownload(
            tempFile = downloadedFile,
            artist = effectiveTrack.artist,
            album = effectiveTrack.album.ifEmpty { null },
            title = effectiveTrack.title,
            format = downloadedFile.extension,
        )

        // Clean up the previous file if the canonicalizer renamed the
        // track (new title → new derived file path). Without this the
        // old OMV-titled file lingers as an orphan consuming disk. Only
        // delete when paths genuinely differ — for a plain re-download
        // commitDownload already overwrote the same path.
        val oldPath = track.filePath
        if (oldPath != null && oldPath != committed.filePath) {
            runCatching {
                val deleted = File(oldPath).delete()
                Log.d(TAG, "executeDownload: deleted orphaned old file path=$oldPath deleted=$deleted")
            }.onFailure { e ->
                Log.w(TAG, "executeDownload: failed to delete old file $oldPath", e)
            }
        }

        Log.i(TAG, "Downloaded: ${effectiveTrack.artist} - ${effectiveTrack.title} → ${committed.filePath}")
        runCatching { trackDao.setMetadataEmbeddedAt(track.id, System.currentTimeMillis()) }
            .onFailure { Log.w(TAG, "setMetadataEmbeddedAt failed for ${track.id}: ${it.message}") }
        // v0.9.36 lyrics integration: fire the post-download lyrics fetch
        // on the same success boundary as the metadata stamp so any track
        // that survives to a stamped state also gets a lyrics-fetch attempt.
        lyricsFetchTrigger.enqueueFor(track.id)
        emitProgress(track.id, 1f, DownloadStatus.COMPLETED)
        return TrackDownloadResult.Success(committed.filePath)
    }

    /**
     * Attempts to fetch [track] from a registered [LosslessSource]
     * (squid.wtf-proxied Qobuz, etc.) ahead of the YouTube path.
     *
     * Returns a successful [TrackDownloadResult.Success] when a source
     * matched + the file fetched + commit succeeded — or null in every
     * other case so the caller can fall through to yt-dlp without
     * try/catch noise. Failures here are NEVER returned as
     * [TrackDownloadResult.Failed]: that would surface as a hard error
     * on the user's sync screen even though yt-dlp can almost always
     * succeed where lossless can't (region locks, deep cuts, niche
     * uploads). The whole point of lossless-first is that it's
     * advisory — if it works we win, if it doesn't we behave exactly
     * as if it weren't enabled.
     *
     * Files land in the same on-disk location format as a yt-dlp
     * download, just with a `.flac` extension instead of `.opus`. The
     * existing [MetadataEmbedder] handles FLAC tagging because ffmpeg
     * `-c copy` is container-agnostic.
     */
    /**
     * True when [trackId] appears in any active Stash Mix playlist.
     * Returns false on any DB error — failure here should never block
     * a download (we'd just lose the lossless override and fall back
     * to global-toggle behaviour).
     */
    private suspend fun isStashMixTrack(trackId: Long): Boolean =
        runCatching { playlistDao.isTrackInStashMix(trackId) }
            .onFailure { Log.w(TAG, "isTrackInStashMix lookup failed for $trackId", it) }
            .getOrDefault(false)

    internal suspend fun tryJioSaavnDownload(track: Track): TrackDownloadResult? {
        val query = TrackQuery(
            artist = track.artist,
            title = track.title,
            album = track.album.takeIf { it.isNotBlank() },
            durationMs = track.durationMs.takeIf { it > 0 },
            explicit = track.explicit,
            trackId = track.id,
        )

        val match = try {
            jioSaavnResolver.resolve(query)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "JioSaavn resolve failed for '${track.artist} - ${track.title}'", error)
            null
        } ?: return null

        emitProgress(track.id, 0.1f, DownloadStatus.DOWNLOADING)
        val tempFile = File(fileOrganizer.getTempDir(), "jiosaavn_${track.id}.m4a")
        val fetched = losslessUrlDownloader.download(
            source = match,
            destination = tempFile,
            onProgress = { read, total ->
                val fraction = if (total > 0L) read.toFloat() / total else 0f
                emitProgress(track.id, 0.1f + fraction * 0.7f, DownloadStatus.DOWNLOADING)
            },
        ).getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.w(TAG, "JioSaavn fetch failed for '${track.artist} - ${track.title}': ${error.message}")
            runCatching { tempFile.delete() }
            return null
        }

        val metadata = audioDurationExtractor.extract(fetched.absolutePath)
        val durationTolerance = maxOf(
            JIOSAAVN_DURATION_TOLERANCE_MS,
            (track.durationMs * JIOSAAVN_DURATION_TOLERANCE_FRACTION).toLong(),
        )
        val durationValid = track.durationMs <= 0L ||
            (metadata != null && kotlin.math.abs(metadata.durationMs - track.durationMs) <= durationTolerance)
        val codecValid = metadata?.format == "aac"
        val bitrateValid = metadata != null && metadata.bitrateKbps >= MIN_JIOSAAVN_BITRATE_KBPS
        if (!codecValid || !bitrateValid || !durationValid) {
            Log.w(
                TAG,
                "JioSaavn media rejected for '${track.artist} - ${track.title}': " +
                    "format=${metadata?.format}, bitrate=${metadata?.bitrateKbps}, " +
                    "duration=${metadata?.durationMs}, expected=${track.durationMs}",
            )
            runCatching { fetched.delete() }
            return null
        }

        emitProgress(track.id, 0.85f, DownloadStatus.PROCESSING)
        val effectiveTrack = trackDao.getById(track.id)?.toDomain() ?: track
        return when (
            val finalized = trackFinalizer.finalizeFile(
                sourceFile = fetched,
                track = effectiveTrack,
                format = match.format,
            )
        ) {
            is TrackFinalizer.FinalizeResult.Success -> {
                Log.i(
                    TAG,
                    "JioSaavn AAC downloaded: ${effectiveTrack.artist} - ${effectiveTrack.title} " +
                        "-> ${finalized.committed.filePath}",
                )
                match.coverArtUrl?.let { url ->
                    runCatching {
                        val existingArt = trackDao.getById(effectiveTrack.id)?.albumArtUrl
                        if (existingArt.isNullOrBlank() ||
                            com.stash.core.common.ArtUrlUpgrader.isYouTubeVideoThumbnail(existingArt)
                        ) {
                            trackDao.updateAlbumArtUrl(effectiveTrack.id, url)
                        }
                    }.onFailure { error ->
                        Log.w(TAG, "JioSaavn album-art update failed for ${effectiveTrack.id}: ${error.message}")
                    }
                }
                loudnessMeasurer.measureAndPersistInBackground(
                    trackId = track.id,
                    file = File(finalized.committed.filePath),
                )
                runCatching { trackDao.setMetadataEmbeddedAt(track.id, System.currentTimeMillis()) }
                    .onFailure { Log.w(TAG, "setMetadataEmbeddedAt failed for ${track.id}: ${it.message}") }
                lyricsFetchTrigger.enqueueFor(track.id)
                emitProgress(track.id, 1f, DownloadStatus.COMPLETED)
                TrackDownloadResult.Success(finalized.committed.filePath)
            }
            is TrackFinalizer.FinalizeResult.Failed -> {
                Log.w(TAG, "JioSaavn finalize failed for ${track.id}: ${finalized.message}")
                runCatching { fetched.delete() }
                null
            }
        }
    }

    internal suspend fun tryLosslessDownload(track: Track, forced: Boolean = false): TrackDownloadResult? {
        val query = TrackQuery(
            artist = track.artist,
            title = track.title,
            album = track.album.takeIf { it.isNotBlank() },
            isrc = track.isrc,
            durationMs = track.durationMs.takeIf { it > 0 },
            explicit = track.explicit,
            spotifyUri = track.spotifyUri,
            trackId = track.id,
        )

        // Bounded failover loop: the duration backstop can reject a degraded
        // source's preview-stub and record it degraded, after which the
        // registry skips it — so we re-resolve to reach the next lossless
        // source. Capped so a pathologically-degrading set can't spin.
        repeat(MAX_LOSSLESS_FAILOVER_ATTEMPTS) {
        val match: SourceResult = runCatching { losslessRegistry.resolve(query) }
            .onFailure { e ->
                Log.w(TAG, "lossless registry threw for '${track.artist} - ${track.title}'", e)
            }
            .getOrNull() ?: return null

        Log.d(
            TAG,
            "lossless match: ${match.sourceId} for '${track.artist} - ${track.title}' " +
                "(${match.format.codec} ${match.format.bitsPerSample}bit/${match.format.sampleRateHz}Hz, " +
                "confidence=${"%.2f".format(match.confidence)}" +
                if (forced) ", forced=stash-mix)" else ")",
        )

        emitProgress(track.id, 0.1f, DownloadStatus.DOWNLOADING)

        // Choose extension from the source's stated codec rather than
        // hardcoding "flac" — sources may legitimately return "alac",
        // "mp3", etc. and the file organizer just plumbs through.
        val ext = match.format.fileExtension.lowercase()
            .ifBlank { match.format.codec.lowercase().ifBlank { "flac" } }
        val tempFile = File(fileOrganizer.getTempDir(), "lossless_${track.id}.$ext")

        val fetched = losslessUrlDownloader.download(
            source = match,
            destination = tempFile,
            onProgress = { read, total ->
                val frac = if (total > 0) read.toFloat() / total else 0f
                emitProgress(track.id, 0.1f + frac * 0.7f, DownloadStatus.DOWNLOADING)
            },
        ).getOrElse { e ->
            Log.w(TAG, "lossless fetch failed for '${track.artist} - ${track.title}': ${e.message}")
            runCatching { tempFile.delete() }
            return null
        }

        // Duration backstop (degradation detection): a degraded source can
        // serve a 30s preview whose URL lacked the `range=` marker the
        // inspector keys on. Probe the fetched file; if it's a sample-length
        // stub far short of the known duration, reject it, cool the source
        // down, and re-resolve so the registry fails over to the next source.
        val probedMs = runCatching { audioDurationExtractor.extractMs(fetched.absolutePath) }
            .getOrNull()
        if (isLosslessDurationDegraded(probedMs, track.durationMs)) {
            Log.w(
                TAG,
                "duration backstop: ${match.sourceId} returned ${probedMs}ms vs expected " +
                    "${track.durationMs}ms for '${track.artist} - ${track.title}' — " +
                    "rejecting + failing over",
            )
            losslessHealthGate.recordDegraded(match.sourceId)
            runCatching { fetched.delete() }
            return@repeat
        }

        emitProgress(track.id, 0.85f, DownloadStatus.PROCESSING)

        // Re-fetch the track so any canonicalizer-driven refresh of
        // album_artist (or other tag-bearing fields) that landed between
        // sync and download is picked up before TrackFinalizer embeds
        // tags. Mirrors the pattern in executeDownload (line ~199).
        // Fallback to the in-memory copy if the row was deleted mid-flight.
        val effectiveTrack = trackDao.getById(track.id)?.toDomain() ?: track

        // Delegate embed + commit + probe to the shared TrackFinalizer.
        // TrackFinalizer is stateless w.r.t. DB — all DB writes remain here
        // so the full domain Track (spotifyUri, isrc, album, explicit, etc.)
        // is preserved without being funnelled through a thinner data class.
        val finalized = trackFinalizer.finalizeFile(
            sourceFile = fetched,
            track = effectiveTrack,
            format = match.format,
        )
        when (finalized) {
            is TrackFinalizer.FinalizeResult.Success -> {
                Log.i(
                    TAG,
                    "Lossless downloaded (${match.sourceId}): ${effectiveTrack.artist} - ${effectiveTrack.title}" +
                        " → ${finalized.committed.filePath}",
                )

                // Persist album art surfaced by the source's catalog API.
                // We OVERWRITE when the stored URL is blank OR is a YouTube
                // *video* thumbnail (`i.ytimg.com/vi/...`) — both YOUTUBE-
                // sourced rows AND Spotify rows that got deduped against a
                // YT-Music match can carry these. We leave other URLs alone
                // (proper YT Music catalog art on lh3.googleusercontent.com,
                // Spotify scdn art) since those are generally fine.
                // Mirrors PlayerRepositoryImpl's on-stream art swap (A1) for
                // the download path. Failure here is non-fatal — file is on
                // disk and playable; only the visual is degraded.
                match.coverArtUrl?.let { url ->
                    runCatching {
                        val existingArt = trackDao.getById(effectiveTrack.id)?.albumArtUrl
                        val needsUpgrade = existingArt.isNullOrBlank() ||
                            com.stash.core.common.ArtUrlUpgrader.isYouTubeVideoThumbnail(existingArt)
                        if (needsUpgrade) {
                            trackDao.updateAlbumArtUrl(effectiveTrack.id, url)
                        }
                    }.onFailure { e ->
                        Log.w(TAG, "lossless: album-art update failed for ${effectiveTrack.id}: ${e.message}")
                    }
                }

                // Trigger loudness measurement off the download thread. The
                // measurement (~25–50 s of ffmpeg ebur128) used to run
                // synchronously inside TrackFinalizer and serialised entire
                // albums behind it. Now it's fire-and-forget: the DB row
                // gets updated whenever the background scan completes, the
                // download flow returns immediately.
                loudnessMeasurer.measureAndPersistInBackground(
                    trackId = track.id,
                    file = File(finalized.committed.filePath),
                )

                runCatching { trackDao.setMetadataEmbeddedAt(track.id, System.currentTimeMillis()) }
                    .onFailure { Log.w(TAG, "setMetadataEmbeddedAt failed for ${track.id}: ${it.message}") }
                // v0.9.36 lyrics integration: mirrors the yt-dlp branch in
                // executeDownload — enqueue lyrics on the same success
                // boundary as the metadata stamp.
                lyricsFetchTrigger.enqueueFor(track.id)
                emitProgress(track.id, 1f, DownloadStatus.COMPLETED)
                return TrackDownloadResult.Success(finalized.committed.filePath)
            }
            is TrackFinalizer.FinalizeResult.Failed -> {
                Log.w(TAG, "lossless finalize failed for ${track.id}: ${finalized.message}")
                // fetched is managed by TrackFinalizer's internal runCatching;
                // if commitDownload threw, the temp file may still be on disk.
                // Best-effort cleanup to avoid leaving orphans in the temp dir.
                runCatching { fetched.delete() }
                return null  // fall through to yt-dlp — same semantics as before
            }
        }
        }
        // Every failover attempt this call rejected its source on the
        // duration backstop → yt-dlp fallthrough (same null semantics).
        Log.w(
            TAG,
            "lossless: exhausted failover attempts for '${track.artist} - ${track.title}'",
        )
        return null
    }

    /**
     * Resolves a YouTube URL for the given track by trying multiple search
     * strategies in order of expected quality. Stops as soon as one produces
     * a match above the auto-accept threshold.
     *
     * Strategies (in order):
     * 1. Original full query: "Artist Title (feat. X) [Remaster]"
     * 2. Without parentheticals: "Artist Title"
     * 3. Without remaster/deluxe suffixes: "Artist Title"
     * 4. With dash separator: "Artist - Title"
     *
     * @return A [ResolveResult] with the best-matching YouTube URL, or null URL
     *         with the best rejected candidate's video ID if no match was accepted.
     */
    private suspend fun resolveUrl(track: Track): ResolveResult {
        // If we already have a YouTube ID, use it directly — except for
        // YT-library-sourced tracks, which get canonicalized: the imported
        // videoId may point at an OMV / UGC / PODCAST, and the canonicalizer
        // swaps it for the ATV master when one exists. Spotify-sourced
        // tracks with a youtubeId have already been through the scorer +
        // verifyMatch gates on a previous sync, so they skip the canonicalizer
        // to avoid pointless re-search work.
        track.youtubeId?.let { videoId ->
            val url = if (track.source == MusicSource.YOUTUBE) {
                ytLibraryCanonicalizer.canonicalize(track, videoId)
            } else {
                "https://www.youtube.com/watch?v=$videoId"
            }
            return ResolveResult(url = url)
        }

        // Track the best rejected candidate across all strategies so users can
        // preview it from the Unmatched Songs screen.
        var bestRejectedVideoId: String? = null

        // ── Primary strategy: Album-based matching (most reliable) ──
        // Searches for the album on YouTube Music, browses the tracklist,
        // and finds the exact track within it. This gives correct video IDs
        // because album tracklists are structured data, not search results.
        if (track.album.isNotBlank()) {
            val albumResult = albumMatchExecutor.findTrackInAlbum(
                targetTitle = track.title,
                targetArtist = track.artist,
                targetAlbum = track.album,
                targetDurationMs = track.durationMs,
            )
            if (albumResult != null) {
                val scored = matchScorer.scoreResults(
                    targetTitle = track.title,
                    targetArtist = track.artist,
                    targetDurationMs = track.durationMs,
                    results = listOf(albumResult),
                    targetAlbum = track.album,
                    targetExplicit = track.explicit,
                )
                val best = matchScorer.bestMatch(scored)
                if (best != null) {
                    // Album matches skip verifyMatch() intentionally — video IDs come from
                    // the album's structured tracklist, not search results, so they don't
                    // suffer from the metadata/ID mismatch that verifyMatch guards against.
                    Log.d(TAG, "resolveUrl: ALBUM MATCH '${track.artist} - ${track.title}' → ${best.youtubeUrl}")
                    persistMatchMetadata(track, best)
                    return ResolveResult(url = best.youtubeUrl)
                }
            }
        }

        // ── Fallback: Track-level search strategies ──
        val strategies = buildSearchQueries(track)

        for (query in strategies) {
            if (query.isBlank()) continue
            val results = searchExecutor.search(query, maxResults = 10)
            if (results.isEmpty()) continue

            val scored = matchScorer.scoreResults(
                targetTitle = track.title,
                targetArtist = track.artist,
                targetDurationMs = track.durationMs,
                results = results,
                targetAlbum = track.album,
                targetExplicit = track.explicit,
            )

            val best = matchScorer.bestMatch(scored) ?: continue
            val verified = verifyMatch(track, best, query)
            if (verified != null) {
                persistMatchMetadata(track, best)
                return ResolveResult(url = verified)
            }
            bestRejectedVideoId = best.videoId  // Save the closest rejected match
        }

        // Final fallback: direct yt-dlp search (bypasses InnerTube entirely)
        Log.d(TAG, "resolveUrl: InnerTube strategies exhausted, trying yt-dlp for '${track.artist} - ${track.title}'")
        val ytDlpQuery = "${track.artist} ${track.title}"
        val ytDlpResults = searchExecutor.searchYtDlpDirect(ytDlpQuery, maxResults = 5)
        if (ytDlpResults.isNotEmpty()) {
            val scored = matchScorer.scoreResults(
                targetTitle = track.title,
                targetArtist = track.artist,
                targetDurationMs = track.durationMs,
                results = ytDlpResults,
                targetAlbum = track.album,
                targetExplicit = track.explicit,
            )
            val best = matchScorer.bestMatch(scored)
            if (best != null) {
                val verified = verifyMatch(track, best, ytDlpQuery)
                if (verified != null) {
                    persistMatchMetadata(track, best)
                    return ResolveResult(url = verified)
                }
                bestRejectedVideoId = best.videoId  // Save the closest rejected match
            }
        }

        Log.w(TAG, "resolveUrl: all strategies failed for '${track.artist} - ${track.title}'")
        return ResolveResult(url = null, rejectedVideoId = bestRejectedVideoId)
    }

    /**
     * Runs all verification gates on a match candidate.
     *
     * Four-level verification:
     * 1. **Title similarity** >= 0.6, OR the candidate contains the target title
     *    as a contiguous token run (rescues decorated / CJK / dual-script titles)
     * 2. **Short title containment** — for titles <= 5 chars, candidate must
     *    contain the target as a word (Jaro-Winkler inflates scores for short strings)
     * 3. **Artist similarity** >= 0.65 + word overlap, OR a per-part match
     *    (rescues bilingual slash-joined uploaders); prevents wrong artist
     * 4. **Video ID verification** — InnerTube player endpoint confirms the actual
     *    video title matches (catches InnerTube metadata/ID mismatches)
     *
     * @return The YouTube URL if all gates pass, null if rejected.
     */
    private suspend fun verifyMatch(
        track: Track,
        best: com.stash.data.download.model.MatchResult,
        query: String,
    ): String? {
        // Gate 0: Duration hard gate. A candidate more than ±15s off the
        // target duration is structurally the wrong recording (extended
        // mix, music-video cut with spoken intro, podcast episode, etc.)
        // even if title/artist/popularity push soft-scoring past threshold.
        // This is the gate that neutralises the 9:25-vs-4:18 Smooth Criminal
        // class of bugs once and for all.
        if (!matchScorer.durationPassesHardGate(
                targetMs = track.durationMs,
                candidateDurationSec = best.durationSeconds,
            )
        ) {
            val deltaSec = Math.abs((track.durationMs / 1000) - best.durationSeconds)
            Log.w(
                TAG,
                "resolveUrl: rejecting '${best.title}' — duration ${best.durationSeconds}s is " +
                    "${deltaSec}s off target ${track.durationMs / 1000}s (gate ±${
                        com.stash.data.download.matching.MatchScorer.DURATION_HARD_GATE_SEC
                    }s)",
            )
            return null
        }

        // Gate 1: Title similarity — with a containment escape for decorated
        // candidate titles (artist prefix, "(Official Lyric Video)", 【…】,
        // "OST - 172", CJK + romanised dual titles) whose Jaro-Winkler is
        // dragged below 0.6 even though the candidate clearly contains the
        // target title. Duration (Gate 0), short-title word (Gate 2) and
        // artist (Gate 3) gates still guard against wrong-song acceptance.
        val titleSim = matchScorer.titleSimilarity(track.title, best.title)
        if (titleSim < 0.6f && !matchScorer.titleContainsTarget(track.title, best.title)) {
            Log.w(TAG, "resolveUrl: rejecting '${best.title}' — title sim ${String.format("%.2f", titleSim)} too low for '${track.title}'")
            return null
        }

        // Gate 2: Short title word containment
        // Jaro-Winkler is unreliable for short strings (e.g., "Hey" vs "Otherside" = 0.63)
        // For titles <= 5 chars, require the target to appear as a word in the candidate
        val targetTitle = track.title.trim()
        if (targetTitle.length <= 5) {
            val targetWord = targetTitle.lowercase()
            val candidateWords = best.title.lowercase().split(Regex("\\W+"))
            if (targetWord !in candidateWords) {
                Log.w(TAG, "resolveUrl: rejecting '${best.title}' — short title '${track.title}' not found as word")
                return null
            }
        }

        // Gate 3: Artist similarity + word overlap, with three escapes for the
        // CJK / official-upload cases the whitespace-token metric can't see:
        //  - per-part match for bilingual slash-joined uploaders
        //    ("かいりきベア／Kairiki bear" whose romanised half == a target part),
        //  - artist named in the title ("美波「…」MV" / "OMORI OST - …" on a
        //    romanised or studio channel), and
        //  - " - Topic" channels (YouTube's auto-generated official audio,
        //    already trusted by the scorer's topic bonus).
        // Any escape short-circuits BOTH the fuzzy-sim and word-overlap sub-gates.
        val artistSim = matchScorer.artistSimilarity(track.artist, best.uploader)
        val isTopicChannel = best.uploader.trim().endsWith(" - Topic")
        val artistMatchOk = isTopicChannel ||
            matchScorer.artistPartMatches(track.artist, best.uploader) ||
            matchScorer.artistAppearsInTitle(track.artist, best.title)
        if (artistSim < 0.65f && !artistMatchOk) {
            Log.w(TAG, "resolveUrl: rejecting '${best.title}' by '${best.uploader}' — fuzzy artist sim ${String.format("%.2f", artistSim)} too low for '${track.artist}'")
            return null
        }
        if (!artistMatchOk && !artistWordsMatch(track.artist, best.uploader)) {
            Log.w(TAG, "resolveUrl: rejecting '${best.title}' by '${best.uploader}' — artist words don't match '${track.artist}'")
            return null
        }

        // Gate 4: Video ID verification via InnerTube player endpoint
        // The player returns the actual video title even for "unplayable" videos
        // (WEB_REMIX client lacks playback auth, but yt-dlp handles that separately).
        // We only check the title — if InnerTube search says "Song A" but the video
        // is actually "Song B", we reject it.
        val verification = searchExecutor.verifyVideo(best.videoId)
        if (verification != null) {
            val actualTitleSim = matchScorer.titleSimilarity(track.title, verification.title)
            if (actualTitleSim < 0.6f && !matchScorer.titleContainsTarget(track.title, verification.title)) {
                Log.w(TAG, "resolveUrl: VIDEO ID MISMATCH for '${track.title}' — " +
                    "search said '${best.title}' but player says '${verification.title}' (sim=${String.format("%.2f", actualTitleSim)})")
                return null
            }
        }

        Log.d(TAG, "resolveUrl: matched '${track.artist} - ${track.title}' with query '$query' → ${best.youtubeUrl} (artist=%.2f, verified=${verification != null})".format(artistSim))
        return best.youtubeUrl
    }

    /**
     * Persists per-match metadata that search discovers but callers of
     * resolveUrl otherwise discard. Fill-only-if-blank semantics (via the
     * DAO query) so Spotify-sourced tracks whose title / album / art
     * arrived with the sync don't get silently rewritten to whatever the
     * YouTube match happens to say. The primary win is the Stash
     * Discover pipeline — it creates tracks with null art / blank album
     * / zero duration / null youtube_id, and without this call the
     * finished download would leave them permanently metadata-less.
     */
    private suspend fun persistMatchMetadata(
        track: Track,
        best: com.stash.data.download.model.MatchResult,
    ) {
        // Resolve the best-available album art URL via a three-tier fallback:
        //   1. best.thumbnailUrl  — YouTube Music's search-shelf thumbnail.
        //      Present for most InnerTube hits; absent for yt-dlp-fallback
        //      results and for UGC / niche uploads where the response comes
        //      back as a plain video renderer without the music thumbnail
        //      path. Niche ambient / modern-classical + compilation uploads
        //      land in this tier frequently.
        //   2. Last.fm track.getInfo   — album art keyed to the canonical
        //      (artist, title) identity rather than the YouTube video. When
        //      Last.fm has the track, this is typically higher quality than
        //      any YouTube thumbnail because it's actual album art, not a
        //      video still. Skipped when credentials aren't configured.
        //   3. i.ytimg.com/vi/<id>/hqdefault.jpg  — deterministic per video
        //      id. hqdefault (480×360) is guaranteed to exist for every YT
        //      video; maxresdefault would be higher res but returns a stock
        //      placeholder on non-HD uploads. Only usable when best.videoId
        //      is non-blank.
        val resolvedArtUrl: String? = best.thumbnailUrl
            ?: (if (lastFmCredentials.isConfigured) {
                runCatching {
                    lastFmApiClient.getTrackInfo(track.artist, track.title).getOrNull()?.bestImageUrl
                }.getOrNull()
            } else null)
            ?: best.videoId.takeIf { it.isNotBlank() }?.let { vid ->
                "https://i.ytimg.com/vi/$vid/hqdefault.jpg"
            }

        runCatching {
            trackDao.fillMissingMetadata(
                trackId = track.id,
                album = best.album?.takeIf { it.isNotBlank() },
                albumArtUrl = resolvedArtUrl,
                durationMs = best.durationSeconds.takeIf { it > 0 }?.let { it * 1000L } ?: 0L,
                youtubeId = best.videoId.takeIf { it.isNotBlank() },
            )
        }.onFailure { e ->
            // fillMissingMetadata can fail on UNIQUE constraint (youtube_id
            // already used by another track, e.g. two Discovery candidates
            // both resolving to the same compilation video) — when that
            // happens the whole row is rolled back and the art write is
            // lost collaterally. Fall back to the art-only write so we
            // still get the album cover even if youtube_id can't be set.
            Log.w(
                TAG,
                "persistMatchMetadata: fillMissingMetadata failed for trackId=${track.id} " +
                    "(${e.javaClass.simpleName}: ${e.message}); trying art-only fallback",
            )
            if (resolvedArtUrl != null) {
                runCatching {
                    trackDao.fillMissingAlbumArtUrl(track.id, resolvedArtUrl)
                }.onFailure { inner ->
                    Log.w(TAG, "persistMatchMetadata: art-only fallback also failed for trackId=${track.id}", inner)
                }
            }
        }
    }

    /**
     * Builds a list of progressively simplified search queries for a track.
     * Each query strips more noise (feat. credits, remaster tags, etc.) to
     * increase the chance of finding a match on YouTube.
     *
     * @param track The track to build queries for.
     * @return Deduplicated list of search queries, ordered from most specific to simplest.
     */
    private fun buildSearchQueries(track: Track): List<String> {
        val artist = track.artist
        val title = track.title
        // Strip parenthetical content: (feat. X), [Remaster], (Deluxe), etc.
        val cleanTitle = title
            .replace(Regex("""\s*[\(\[][^)\]]*[\)\]]"""), "")
            .replace(Regex("""\s*(feat\.?|ft\.?|featuring)\s+.*""", RegexOption.IGNORE_CASE), "")
            .trim()
        // Strip "- Remaster", "- Single Version", etc.
        val simpleTitle = cleanTitle
            .replace(Regex("""\s*-\s*(Remaster|Remastered|Single Version|Deluxe|Bonus Track).*""", RegexOption.IGNORE_CASE), "")
            .trim()

        val album = track.album.takeIf { it.isNotBlank() }

        return listOfNotNull(
            "$artist $simpleTitle",         // Clean title (best for InnerTube)
            "$artist $title",               // Original full query (fallback)
            "$artist - $simpleTitle",       // With dash separator (last resort)
        ).map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    /**
     * Checks that the significant words in the artist names actually match,
     * not just that the characters are similar.
     *
     * Prevents "Jimi Hendrix" matching "Jim Hendricks" — Jaro-Winkler gives
     * 0.77 similarity for these, but "Hendrix" ≠ "Hendricks" word-wise.
     *
     * At least one significant word (>3 chars) from the target must appear
     * exactly (case-insensitive) in the candidate, or vice versa.
     */
    private fun artistWordsMatch(targetArtist: String, candidateArtist: String): Boolean {
        val targetWords = targetArtist.lowercase().split(Regex("[\\s,&+]+"))
            .filter { it.length > 3 }
            .map { it.trim() }
            .toSet()
        val candidateWords = candidateArtist.lowercase().split(Regex("[\\s,&+]+"))
            .filter { it.length > 3 }
            .map { it.trim() }
            .toSet()

        if (targetWords.isEmpty() || candidateWords.isEmpty()) return true // Can't verify short names

        // At least one significant word must match exactly
        return targetWords.any { it in candidateWords } || candidateWords.any { it in targetWords }
    }

    /**
     * Emits a progress update for the given track.
     */
    private fun emitProgress(trackId: Long, progress: Float, status: DownloadStatus) {
        _progress.tryEmit(DownloadProgress(trackId, progress, 0, status))
    }
}
