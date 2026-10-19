package com.stash.data.download.search

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.SimpleCache
import com.stash.core.common.TrackCanonical
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.library.SameSongRule
import com.stash.core.data.library.findReusableByWords
import com.stash.core.data.library.findSameSongByWords
import com.stash.core.data.prefs.QualityPreference
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.model.DownloadStatus
import com.stash.core.model.TrackItem
import com.stash.core.model.YouTubeVideoId
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.DownloadManager
import com.stash.data.download.jiosaavn.JioSaavnResolver
import com.stash.data.download.jiosaavn.toJioSaavnKbps
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.LosslessUrlDownloader
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.forLibrarySource
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossyAacLengthMatches
import com.stash.core.model.MusicSource
import com.stash.data.download.lyrics.LyricsFetchTrigger
import com.stash.data.download.prefs.toYtDlpArgs
import com.stash.data.download.shared.TrackFinalizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Replaces the direct [DownloadExecutor.download] call in
 * [com.stash.core.media.actions.TrackActionsDelegate.downloadTrack].
 *
 * Workflow:
 *   1. Dedupe — if a download for this trackKey is already in flight,
 *      join it.
 *   2. Re-resolve via [LosslessSourceRegistry] (don't reuse the
 *      prefetcher's cached match — its signed URL may be stale).
 *   3. Hit (confidence >= 0.65): use CacheDataSource to read upstream
 *      bytes through the preview cache into tempFile. Any preview-cached
 *      ranges are reused; missing ranges fetched fresh. Then hand off to
 *      TrackFinalizer for embed/commit/probe; do search-specific DB writes
 *      inline (insert/update Track row, link to "Your Downloads" playlist).
 *   4. Miss: fall through to existing yt-dlp DownloadExecutor.
 *
 * Note on [CacheKeyFactory]: this coordinator lives in `:data:download`,
 * which cannot depend on `:core:media` (circular — `:core:media` already
 * depends on `:data:download`). The concrete [com.stash.core.media.preview.TrackKeyCacheKeyFactory]
 * is therefore injected through the interface, bound in
 * `core/media/.../di/PreviewCacheModule.provideCacheKeyFactory()`.
 */
@Singleton
class SearchDownloadCoordinator @Inject constructor(
    private val registry: LosslessSourceRegistry,
    private val previewCache: SimpleCache,
    private val httpDataSourceFactory: HttpDataSource.Factory,
    /** Resolved at runtime to TrackKeyCacheKeyFactory via PreviewCacheModule. */
    private val cacheKeyFactory: CacheKeyFactory,
    private val downloadExecutor: DownloadExecutor,
    private val trackFinalizer: TrackFinalizer,
    private val trackDao: com.stash.core.data.db.dao.TrackDao,
    private val musicRepository: com.stash.core.data.repository.MusicRepository,
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard,
    @ApplicationContext private val context: Context,
    /**
     * v0.9.17: Used by the strict-FLAC defer branch. When the lossless
     * registry can't serve the track and the user has yt-dlp fallback off,
     * the coordinator emits [SearchDownloadStatus.WaitingForLossless] and
     * marks the queue row [DownloadStatus.WAITING_FOR_LOSSLESS] instead
     * of falling through to yt-dlp.
     */
    private val losslessPrefs: LosslessSourcePreferences,
    private val jioSaavnResolver: JioSaavnResolver,
    private val tidalAacResolver: com.stash.data.download.lossless.tidal.TidalAacResolver,
    private val losslessUrlDownloader: LosslessUrlDownloader,
    private val audioDurationExtractor: AudioDurationExtractor,
    private val downloadQueueDao: DownloadQueueDao,
    private val localFileOps: com.stash.core.data.files.LocalFileOps,
    private val loudnessMeasurer: com.stash.core.data.audio.LoudnessMeasurer,
    /**
     * v0.9.36: enqueue a [com.stash.data.lyrics.worker.LyricsFetchWorker]
     * after a successful finalize on either branch. Interface lives in
     * `:data:download` and the production binding in `:app`, mirroring
     * the [com.stash.data.download.DownloadManager] hookup — see
     * [LyricsFetchTrigger] for the cyclic-dep rationale.
     */
    private val lyricsFetchTrigger: LyricsFetchTrigger,
    /**
     * The user's YouTube quality tier, turned into yt-dlp `-f` args for the
     * fallback path. Without it yt-dlp picks its default `bv*+ba/b` (video).
     */
    private val qualityPrefs: QualityPreference,
    /**
     * The daily lossless allowance. While it is spent and the fallback is off, a download
     * waits for the reset instead of saving Tidal AAC, as [DownloadManager] does: the
     * lossless rung skipped the relays, so the song has not shown it has no FLAC.
     */
    private val losslessQuota: LosslessQuota,
) {
    // App-lifetime scope. Class is @Singleton.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * In-flight download map keyed by videoId. Protected by [mutex].
     *
     * A [Deferred] is inserted before the download starts and removed in the
     * `finally` block of [download], so a concurrent caller on the same
     * videoId joins the same coroutine rather than launching a duplicate
     * network+disk operation.
     */
    private val inFlight = mutableMapOf<String, Deferred<DownloadJobResult>>()
    private val mutex = Mutex()

    /**
     * Initiates or joins an in-flight download for [track] and emits status
     * updates as a cold [Flow].
     *
     * Emitted sequence:
     *   [SearchDownloadStatus.Resolving] → [SearchDownloadStatus.Downloading] →
     *   [SearchDownloadStatus.Completed] | [SearchDownloadStatus.Failed]
     *
     * v0.9.17 strict-FLAC: when the lossless registry returns null AND
     * [LosslessSourcePreferences.youtubeFallbackEnabledNow] is false the
     * sequence shortens to:
     *   [SearchDownloadStatus.Resolving] → [SearchDownloadStatus.WaitingForLossless]
     *
     * No Stash-Mix exemption here — the search-tab pipeline is always an
     * explicit user action on a specific track, so the user's fallback
     * preference governs unconditionally.
     *
     * Operational errors are mapped to [SearchDownloadStatus.Failed].
     * [CancellationException] still propagates for cooperative cancellation.
     *
     * A [TrackItem.videoId] without the YouTube video-id shape fails at once
     * ([SearchDownloadStatus.Resolving] → [SearchDownloadStatus.Failed]).
     *
     * A song the library already has the audio of (the same video's row with a
     * file, or the same song by [SameSongRule] with one) downloads nothing:
     *   [SearchDownloadStatus.Resolving] → [SearchDownloadStatus.AlreadyInLibrary]
     */
    fun download(track: TrackItem): Flow<SearchDownloadStatus> = flow {
        val key = track.videoId
        emit(SearchDownloadStatus.Resolving)

        // The id names this download's temp files and its yt-dlp URL, and is
        // stored as the track's youtube_id. It comes from a server response, so
        // one without the YouTube video-id shape goes no further: no lookup,
        // no temp file, no download, no library row.
        if (!YouTubeVideoId.isValid(key)) {
            Log.w(TAG, "refusing a download whose id isn't a YouTube video id")
            emit(SearchDownloadStatus.Failed("Not a valid YouTube video"))
            return@flow
        }

        // A song the user blocked is refused before anything is fetched. The
        // check in upsertSearchTrack alone ran only once the whole file (up
        // to 140 MB of FLAC) was downloaded and committed to the library,
        // where it then sat with no row recording it.
        if (isBlocked(track)) {
            Log.d(TAG, "Refused download of blocked: ${track.artist} - ${track.title}")
            emit(SearchDownloadStatus.Failed("Track is blocked"))
            return@flow
        }

        // A song the library already has the audio of is not downloaded a second time, and no
        // second row is made for it. A different version, or a clearly different length, is
        // another song; another upload of the same song is not (see libraryCopyOf).
        libraryCopyOf(track)?.let { copy ->
            Log.d(TAG, "already in the library as track ${copy.id}: ${track.videoId}")
            emit(SearchDownloadStatus.AlreadyInLibrary)
            return@flow
        }

        val deferred = mutex.withLock {
            inFlight[key] ?: scope.async { performDownload(track) }.also { created ->
                inFlight[key] = created
                // The producer belongs to the singleton scope, not to any one
                // collector. Remove it only when the producer completes.
                created.invokeOnCompletion {
                    scope.launch {
                        mutex.withLock {
                            if (inFlight[key] === created) inFlight.remove(key)
                        }
                    }
                }
            }
        }

        when (val job = deferred.await()) {
            is DownloadJobResult.Resolved -> {
                // Signal which source is delivering bytes now that resolution is done.
                emit(SearchDownloadStatus.Downloading(job.source))
                emit(
                    when (val r = job.outcome) {
                        is TrackFinalizer.FinalizeResult.Success -> SearchDownloadStatus.Completed
                        is TrackFinalizer.FinalizeResult.Failed -> SearchDownloadStatus.Failed(r.message)
                    }
                )
            }
            is DownloadJobResult.Deferred -> {
                emit(SearchDownloadStatus.WaitingForLossless)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal: resolve + route
    // -------------------------------------------------------------------------

    private suspend fun performDownload(track: TrackItem): DownloadJobResult {
        // Master lossless switch OFF → skip the registry (and the strict-FLAC
        // defer) entirely and go straight to yt-dlp, mirroring
        // DownloadManager.executeDownload. Without this, "lossless off" still
        // resolved the registry, missed (sources down / no captcha cookie),
        // and — with fallback also off — deferred to WAITING_FOR_LOSSLESS, so
        // artist-page / search downloads hung on "waiting for lossless" forever.
        if (!losslessPrefs.enabledNow()) {
            return finalizeFromLossyFallback(track)
        }

        val match = runCatching { registry.resolve(track.toQuery()) }
            .onFailure { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "registry.resolve threw for ${track.videoId}: ${e.message}")
            }
            .getOrNull()

        if (match != null && match.confidence >= MIN_SEARCH_CONFIDENCE) {
            return DownloadJobResult.Resolved(
                source = SearchDownloadStatus.Source.LOSSLESS,
                outcome = finalizeFromLossless(track, match),
            )
        }

        // v0.9.17 strict-FLAC defer branch — mirrors DownloadManager.
        // No Stash-Mix exemption: search-tab is always an explicit user
        // action, so the fallback pref governs unconditionally. Nothing from
        // JioSaavn or YouTube is saved below this line with the fallback off.
        if (!losslessPrefs.youtubeFallbackEnabledNow()) {
            // The day's lossless allowance is spent: the relays were skipped, so the
            // song has not shown it has no FLAC. It waits for the reset, as in
            // DownloadManager, rather than being saved as Tidal AAC for good.
            if (losslessQuota.isSpent()) {
                Log.i(TAG, "deferring search download '${track.artist} - ${track.title}': daily lossless allowance spent, fallback off")
                return deferForLossless(track)
            }
            tryTidalAac(track)?.let { return it }
            Log.i(
                TAG,
                "deferring search download '${track.artist} - ${track.title}': no FLAC or Tidal AAC, fallback off",
            )
            return deferForLossless(track)
        }

        return finalizeFromLossyFallback(track, losslessAttempted = true)
    }

    /**
     * The strict-FLAC wait: [DownloadJobResult.Deferred], and the song's queue row, when it has
     * one, marked WAITING_FOR_LOSSLESS so the retry scheduler picks it up later. Lookup is
     * best-effort and chained on the local Track row — the search-tab path bypasses
     * download_queue for fresh tracks, so most search defers won't find a row here. The write
     * only matters when the user re-taps Download on a track the sync pipeline previously
     * deferred. It also marks the row as the user's ask, a sync's too: otherwise the leftover
     * cleanup would drop it, and the lossless retry skip it (#532).
     */
    private suspend fun deferForLossless(track: TrackItem): DownloadJobResult.Deferred {
        runCatching {
            val trackId = trackDao.findByYoutubeId(track.videoId)?.id
            if (trackId != null) {
                downloadQueueDao.getByTrackId(trackId)?.let { row ->
                    downloadQueueDao.deferForTap(row.id)
                }
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "WAITING_FOR_LOSSLESS DAO write failed for ${track.videoId}: ${e.message}")
        }
        return DownloadJobResult.Deferred
    }

    /**
     * Tidal's 320 kbps AAC for a track the lossless rung missed, or null. It has its
     * own switch ("Tidal AAC when no FLAC", checked by the resolver), so it runs even
     * with the YouTube fallback off.
     *
     * Asked with [tidalAacQuery]: the query streaming makes for this video's library row, so
     * the download takes the Tidal track the stream played, and its file is held to that
     * row's length the way the matcher held the match ([lossyAacLengthMatches]).
     */
    private suspend fun tryTidalAac(track: TrackItem): DownloadJobResult.Resolved? {
        val query = tidalAacQuery(track)
        val tidalMatch = runCatching { tidalAacResolver.resolve(query) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Tidal AAC resolve threw for ${track.videoId}: ${error.message}")
            }.getOrNull()
        if (tidalMatch != null) {
            // The row's length, or the search result's when the row has none (a stub synced
            // without one). The query keeps the row's own, so it shares streaming's cache key.
            val length = AacLength(
                expectedMs = query.durationMs ?: (track.durationSeconds * 1_000).toLong(),
                reliable = query.durationReliable,
            )
            when (val attempt = finalizeFromSource(track, tidalMatch, length)) {
                is SourceFinalizeAttempt.BeforeCommitFailure -> Log.w(
                    TAG,
                    "Tidal AAC failed before commit for ${track.videoId}; moving on: " +
                        attempt.message,
                )
                is SourceFinalizeAttempt.AfterCommit -> {
                    Log.i(TAG, "Tidal AAC search download committed for ${track.videoId}")
                    return DownloadJobResult.Resolved(
                        source = SearchDownloadStatus.Source.TIDAL_AAC,
                        outcome = attempt.outcome,
                    )
                }
            }
        }
        return null
    }

    /** Resolves the preferred lossy fallback, then preserves YouTube as the last resort. */
    private suspend fun finalizeFromLossyFallback(
        track: TrackItem,
        losslessAttempted: Boolean = false,
    ): DownloadJobResult.Resolved {
        // Tidal's 320 kbps AAC first, only after the lossless rung ran and missed
        // (JioSaavn misses many of these tracks). Lossless off = unchanged chain.
        if (losslessAttempted) tryTidalAac(track)?.let { return it }
        // The Download quality picker sets the JioSaavn variant too.
        val match = runCatching {
            jioSaavnResolver.resolve(track.toQuery(), qualityPrefs.qualityTier.first().toJioSaavnKbps())
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "JioSaavn resolve threw for ${track.videoId}: ${error.message}")
        }.getOrNull()

        if (match != null) {
            when (val attempt = finalizeFromSource(track, match)) {
                is SourceFinalizeAttempt.BeforeCommitFailure -> {
                    Log.w(
                        TAG,
                        "JioSaavn failed before commit for ${track.videoId}; falling through to YouTube: " +
                            attempt.message,
                    )
                }
                is SourceFinalizeAttempt.AfterCommit -> {
                    // Once the file reaches permanent storage, any persistence
                    // failure is terminal. Starting YouTube here could leave an
                    // orphaned m4a and partially-mutated library rows.
                    return DownloadJobResult.Resolved(
                        source = SearchDownloadStatus.Source.JIOSAAVN,
                        outcome = attempt.outcome,
                    )
                }
            }
        }

        return DownloadJobResult.Resolved(
            source = SearchDownloadStatus.Source.YOUTUBE,
            outcome = finalizeFromYtDlp(track),
        )
    }

    // -------------------------------------------------------------------------
    // Lossless path — ExoPlayer CacheDataSource → temp file → TrackFinalizer
    // -------------------------------------------------------------------------

    private suspend fun finalizeFromLossless(
        track: TrackItem,
        match: SourceResult,
    ): TrackFinalizer.FinalizeResult = when (val attempt = finalizeFromSource(track, match)) {
        is SourceFinalizeAttempt.BeforeCommitFailure ->
            TrackFinalizer.FinalizeResult.Failed(attempt.message)
        is SourceFinalizeAttempt.AfterCommit -> attempt.outcome
    }

    /**
     * @param aacLength the length a lossy AAC file is checked against, and whether it is the
     *        song's ([lossyAacLengthMatches]). Null = the search result's own length, trusted:
     *        JioSaavn's matcher already held its match to that window.
     */
    private suspend fun finalizeFromSource(
        track: TrackItem,
        match: SourceResult,
        aacLength: AacLength? = null,
    ): SourceFinalizeAttempt {
        // SearchPreviewMediaSource's key: only used to free the preview's bytes
        // once this download is committed. The lossless fetch below reads under
        // its own key, which names the exact file (SearchLosslessFile.cacheKey):
        // the preview key names only the song, and reading under it spliced a
        // previewed file's spans into a different file's download.
        val cacheNamespace = if (match.sourceId == JioSaavnResolver.SOURCE_ID) "jiosaavn" else "lossless"
        val previewKey = "$cacheNamespace:${track.videoId}"
        val cacheKey = if (match.sourceId == JioSaavnResolver.SOURCE_ID) previewKey else SearchLosslessFile.cacheKey(match)
        var bytesWritten = 0L
        var expectedBytes = C.LENGTH_UNSET.toLong()
        val tempFile = File(
            context.cacheDir,
            "search_lossless_${track.videoId}.${match.format.fileExtension}",
        )
        runCatching { tempFile.delete() }

        // Lossy AAC rungs: fetched by the downloader and checked as AAC, not FLAC.
        val isLossyAac = match.sourceId == JioSaavnResolver.SOURCE_ID ||
            match.sourceId == com.stash.data.download.lossless.tidal.TidalAacResolver.SOURCE_ID
        if (isLossyAac || match.isDash) {
            // Use the shared OkHttp downloader rather than Media3's redirect-
            // following HTTP source. The Jio transport disables redirects so
            // the trusted aac.saavncdn.com URL cannot pivot to another host.
            // A DASH match (Tidal) is a manifest, not the file: only the
            // downloader knows to fetch its segments and remux them to FLAC.
            val fetched = losslessUrlDownloader.download(match, tempFile)
            fetched.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
                runCatching { tempFile.delete() }
                return SourceFinalizeAttempt.BeforeCommitFailure(
                    "${match.sourceId} fetch failed: ${error.message}",
                )
            }
        } else {
            // CacheDataSource with FLAG_BLOCK_ON_CACHE reads cached spans first,
            // then fills missing byte ranges from upstream HTTP. Returns bytes
            // contiguously regardless of which spans an earlier attempt pre-filled.
            val dataSource = CacheDataSource.Factory()
                .setCache(previewCache)
                .setUpstreamDataSourceFactory(httpDataSourceFactory)
                .setCacheKeyFactory(cacheKeyFactory)
                .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
                .createDataSource()

            runCatching {
                val spec = DataSpec.Builder()
                    .setUri(match.downloadUrl)
                    .setKey(cacheKey)
                    .build()

                expectedBytes = dataSource.open(spec)
                tempFile.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = dataSource.read(buf, 0, buf.size)
                        if (n == C.RESULT_END_OF_INPUT) break
                        out.write(buf, 0, n)
                        bytesWritten += n
                    }
                }
            }.onFailure { error ->
                runCatching { dataSource.close() }
                if (error is CancellationException) throw error
                Log.w(TAG, "lossless cache->file copy failed for $cacheKey: ${error.message}")
                return SourceFinalizeAttempt.BeforeCommitFailure(
                    "Cache fill failed: ${error.message}",
                )
            }
            runCatching { dataSource.close() }
        }

        if (isLossyAac) {
            val metadata = audioDurationExtractor.extract(tempFile.absolutePath)
            val length = aacLength ?: AacLength(expectedMs = (track.durationSeconds * 1_000).toLong(), reliable = true)
            if (!isValidLossyAac(metadata, length, match.format.bitrateKbps)) {
                runCatching { tempFile.delete() }
                return SourceFinalizeAttempt.BeforeCommitFailure(
                    "${match.sourceId} media failed AAC quality validation: format=${metadata?.format}, " +
                        "bitrate=${metadata?.bitrateKbps} (asked ${match.format.bitrateKbps}), " +
                        "duration=${metadata?.durationMs}, " +
                        "expected=${length.expectedMs} (${if (length.reliable) "song length" else "video length"})",
                )
            }
        } else {
            // The checks DownloadManager's lossless path has: a short copy, a file
            // that isn't the codec asked for, or a preview-length stub never commits.
            val metadata = audioDurationExtractor.extract(tempFile.absolutePath)
            val problem = SearchLosslessFile.problem(
                expectedCodec = match.format.codec,
                bytesWritten = bytesWritten,
                expectedBytes = expectedBytes,
                probedFormat = metadata?.format,
                probedMs = metadata?.durationMs,
                expectedMs = (track.durationSeconds * 1_000).toLong(),
            )
            if (problem != null) {
                Log.w(TAG, "lossless file from ${match.sourceId} rejected for ${track.videoId}: $problem")
                runCatching { tempFile.delete() }
                runCatching { previewCache.removeResource(cacheKey) }
                return SourceFinalizeAttempt.BeforeCommitFailure("Lossless file rejected: $problem")
            }
        }

        if (!localFileOps.acceptDownloadOrDelete(tempFile.absolutePath)) {
            return SourceFinalizeAttempt.BeforeCommitFailure("Downloaded file failed validation")
        }

        val finalized = trackFinalizer.finalizeFile(
            sourceFile = tempFile,
            track = track.toDomainStub(existingTrackId(track)),
            format = match.format,
        )

        // TrackFinalizer success proves only that the file was committed.
        // Completed additionally requires validation and durable library state.
        var outcome: TrackFinalizer.FinalizeResult = finalized
        if (finalized is TrackFinalizer.FinalizeResult.Success) {
            outcome = persistFinalizedTrack(
                track,
                match.format,
                finalized,
                match.coverArtUrl,
            )
            if (outcome is TrackFinalizer.FinalizeResult.Success) {
                // v0.9.36 lyrics integration: chain the lyrics-fetch enqueue off
                // the stamp's resolved trackId so we don't repeat findByYoutubeId.
                // If the stamp lookup failed (returned null), skip lyrics too —
                // we have no stable id to key the worker on.
                stampEmbeddedAt(track.videoId)?.let { lyricsFetchTrigger.enqueueFor(it) }
            }
        }

        // Free preview-cache space now that bytes are on permanent storage.
        listOf(previewKey, cacheKey).distinct().forEach { key ->
            runCatching { previewCache.removeResource(key) }
                .onFailure { e -> Log.w(TAG, "removeResource failed for $key: ${e.message}") }
        }

        return SourceFinalizeAttempt.AfterCommit(outcome)
    }

    /** An AAC file at the bitrate asked for, as long as [length] allows ([lossyAacLengthMatches]). */
    private fun isValidLossyAac(
        metadata: com.stash.core.data.audio.AudioMetadata?,
        length: AacLength,
        expectedKbps: Int,
    ): Boolean {
        metadata ?: return false
        if (metadata.format != "aac" || metadata.bitrateKbps < DownloadManager.minJioSaavnBitrateKbps(expectedKbps)) {
            return false
        }
        return lossyAacLengthMatches(metadata.durationMs, length.expectedMs, length.reliable)
    }

    /**
     * The Tidal AAC query for [track]: the one streaming makes for this video's library row
     * (TidalStreamResolver: the row's words, ISRC and length through [forLibrarySource]), so
     * both read one cached Tidal match and agree on whether the length is the song's.
     *
     * Without a row (a search result never played), the row this download would create:
     * YouTube-sourced, with the result's words, album and length. A music video's length is
     * then marked unreliable, as it is for the same video once it is in the library.
     * The lossless rung keeps [toQuery]: this changes only the Tidal AAC lookup.
     */
    private suspend fun tidalAacQuery(track: TrackItem): TrackQuery {
        val row = try {
            trackDao.findByYoutubeId(track.videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "row lookup for the Tidal AAC query failed for ${track.videoId}: ${e.message}")
            null
        }
        val query = if (row != null) {
            TrackQuery(
                artist = row.artist,
                title = row.title,
                album = row.album.takeIf { it.isNotBlank() },
                isrc = row.isrc?.takeIf { it.isNotBlank() },
                durationMs = row.durationMs.takeIf { it > 0 },
                trackId = row.id,
            ).forLibrarySource(row.source, row.musicVideoType)
        } else {
            track.toQuery().forLibrarySource(MusicSource.YOUTUBE)
        }
        Log.d(
            TAG,
            "Tidal AAC query for ${track.videoId}: '${query.artist} - ${query.title}' " +
                "${query.durationMs}ms (${if (query.durationReliable) "song length" else "video length"}" +
                (if (row != null) ", row ${row.id})" else ", no row)"),
        )
        return query
    }

    /** The length a lossy AAC file is checked against, and whether it is the song's own. */
    private data class AacLength(val expectedMs: Long, val reliable: Boolean)

    // -------------------------------------------------------------------------
    // YouTube / yt-dlp fallback path
    // -------------------------------------------------------------------------

    /**
     * Converts file-finalization success into end-to-end success only after
     * the required database state is durable. Expected validation rejections
     * and persistence failures become [TrackFinalizer.FinalizeResult.Failed];
     * cooperative cancellation is never converted into a terminal status.
     */
    private suspend fun persistFinalizedTrack(
        track: TrackItem,
        format: AudioFormat,
        finalized: TrackFinalizer.FinalizeResult.Success,
        coverArtUrl: String?,
    ): TrackFinalizer.FinalizeResult = try {
        upsertSearchTrack(track, format, finalized, coverArtUrl)
        finalized
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "search-track persistence failed for ${track.videoId}: ${e.message}", e)
        TrackFinalizer.FinalizeResult.Failed(
            "Failed to save download: ${e.message ?: "unknown persistence error"}",
        )
    }

    private suspend fun finalizeFromYtDlp(track: TrackItem): TrackFinalizer.FinalizeResult {
        // download() already refused a malformed id; built from the checked id all the same.
        val url = YouTubeVideoId.watchUrl(track.videoId)
            ?: return TrackFinalizer.FinalizeResult.Failed("Not a valid YouTube video")
        val tempDir = File(context.cacheDir, "search_ytdlp").also { it.mkdirs() }
        val filename = "search_${track.videoId}"

        val ytDlpResult = runCatching {
            downloadExecutor.download(
                url = url,
                outputDir = tempDir,
                filename = filename,
                qualityArgs = qualityPrefs.qualityTier.first().toYtDlpArgs(),
            )
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            return TrackFinalizer.FinalizeResult.Failed("yt-dlp threw: ${e.message}")
        }

        val tempFile = when (ytDlpResult) {
            is DownloadResult.Success -> ytDlpResult.file
            is DownloadResult.YtDlpError ->
                return TrackFinalizer.FinalizeResult.Failed("yt-dlp: ${ytDlpResult.message}")
            is DownloadResult.Error ->
                return TrackFinalizer.FinalizeResult.Failed("yt-dlp error: ${ytDlpResult.message}")
            is DownloadResult.NoOutput ->
                return TrackFinalizer.FinalizeResult.Failed("yt-dlp produced no output")
        }

        // Checked before the commit: a junk body (a ~274-byte yt-dlp error)
        // committed first would take the place of the song's own library file
        // at the same name before upsertSearchTrack rejected it.
        if (!localFileOps.acceptDownloadOrDelete(tempFile.absolutePath)) {
            return TrackFinalizer.FinalizeResult.Failed("Downloaded file failed validation")
        }

        // opus/0 are placeholder values — TrackFinalizer probes the real codec
        // post-download and upsertSearchTrack writes the probed values if available.
        val format = AudioFormat(codec = "opus", bitrateKbps = 0)
        val finalized = trackFinalizer.finalizeFile(
            sourceFile = tempFile,
            track = track.toDomainStub(existingTrackId(track)),
            format = format,
        )

        var outcome: TrackFinalizer.FinalizeResult = finalized
        if (finalized is TrackFinalizer.FinalizeResult.Success) {
            outcome = persistFinalizedTrack(track, format, finalized, track.thumbnailUrl)
            if (outcome is TrackFinalizer.FinalizeResult.Success) {
                // v0.9.36 lyrics integration: parity with the lossless branch.
                stampEmbeddedAt(track.videoId)?.let { lyricsFetchTrigger.enqueueFor(it) }
            }
        }
        return outcome
    }

    /**
     * Stamps `tracks.metadata_embedded_at` after a successful finalize so the
     * v0.9.35 backfill worker skips this row. Lookup is best-effort: when the
     * Track row isn't found by videoId (rare — `upsertSearchTrack` always
     * inserts before we get here, but defensive against a concurrent delete)
     * the stamp is simply skipped. Failure is non-fatal — the file is on
     * disk and playable regardless.
     *
     * v0.9.36: returns the resolved Long trackId so the caller can hand it
     * straight to [LyricsFetchTrigger.enqueueFor] without repeating the
     * `findByYoutubeId` lookup. Returns null when the row isn't found OR
     * the DAO call threw — both cases mean we couldn't establish a stable
     * trackId, so the lyrics enqueue must also be skipped.
     */
    private suspend fun stampEmbeddedAt(videoId: String): Long? {
        return runCatching {
            val trackId = trackDao.findByYoutubeId(videoId)?.id ?: return null
            trackDao.setMetadataEmbeddedAt(trackId, System.currentTimeMillis())
            trackId
        }.onFailure { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "setMetadataEmbeddedAt failed for $videoId: ${e.message}")
        }.getOrNull()
    }

    // -------------------------------------------------------------------------
    // DB upsert — search-specific path
    // -------------------------------------------------------------------------

    /**
     * Looks up an existing Track row by videoId, or by canonical identity
     * under [SameSongRule] ([findReusableRow]); inserts a new row when
     * neither matches. Then records the
     * download columns and links to the "Your Downloads" playlist so
     * orphan-cleanup cannot delete the file.
     */
    private suspend fun upsertSearchTrack(
        track: TrackItem,
        format: AudioFormat,
        finalized: TrackFinalizer.FinalizeResult.Success,
        coverArtUrl: String?,
    ) {
        // v0.9.15: Reject blocklisted identities. Without this, tapping
        // "Download" on a search result for a song the user previously
        // blocked would silently resurrect the file + create a new
        // "Your Downloads" link.
        if (blocklistGuard.isBlocked(
                artist = track.artist, title = track.title,
                spotifyUri = null, youtubeId = track.videoId,
            )) {
            android.util.Log.d("SearchDownload", "Refused download of blocked: ${track.artist} - ${track.title}")
            throw PersistenceRejectedException("Track is blocked")
        }
        // Reject a committed file before inserting or mutating library rows.
        if (!localFileOps.acceptDownloadOrDelete(finalized.committed.filePath)) {
            Log.w(TAG, "search download: discarded invalid file for videoId=${track.videoId}: ${finalized.committed.filePath}")
            throw PersistenceRejectedException("Downloaded file failed validation")
        }


        val existing = findReusableRow(track)
        // A stub taken by its words has no video id (SameSongRule): record this one, so the
        // next download or lookup of the same video finds the row by id, and the row says which
        // video its file came from. Guarded: a concurrent claim leaves the row as it is.
        if (existing != null && existing.youtubeId.isNullOrBlank()) {
            trackDao.updateYoutubeIdIfUnclaimed(existing.id, track.videoId)
        }

        val albumName = track.album.orEmpty()
        val albumArtistName = track.albumArtist.orEmpty()
        val trackId: Long = existing?.id ?: trackDao.insert(
            com.stash.core.data.db.entity.TrackEntity(
                title = track.title,
                artist = track.artist,
                // Album from the discovery-screen context (set by
                // AlbumDiscoveryScreen / AlbumDiscoveryViewModel when the user
                // taps Download All on an album, or null when downloading a
                // loose search result). Without this the row lands with
                // album = "" and TrackDao.getAllAlbums (filtered by
                // album != '') never surfaces it in the Library Albums tab.
                album = albumName,
                // v0.9.26 — album_artist disambiguates same-titled releases
                // by different artists ("Singles" by Usher vs "Singles" by
                // Drake) and lets multi-artist collab albums stay grouped
                // even when per-track artist credits vary.
                albumArtist = albumArtistName,
                youtubeId = track.videoId,
                canonicalTitle = TrackCanonical.title(track.title),
                canonicalArtist = TrackCanonical.artist(track.artist),
                // (durationSeconds * 1_000).toLong() preserves sub-second precision.
                // Doing .toLong() first then * 1_000L would truncate 3.7s → 3_000ms.
                durationMs = (track.durationSeconds * 1_000).toLong(),
                source = com.stash.core.model.MusicSource.YOUTUBE,
                albumArtUrl = track.thumbnailUrl,
            )
        )

        // If the row already existed (from a prior sync or a different
        // identity-equivalent download) and has no album/album_artist
        // recorded, fill them in now that we know them. Same fix as the
        // insert path — without this, an imported-then-redownloaded track
        // would still hide its album from the Library tab.
        if (existing != null && existing.album.isBlank() && albumName.isNotBlank()) {
            runCatching { trackDao.updateAlbumIfEmpty(trackId, albumName) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "updateAlbumIfEmpty failed: ${e.message}")
                }
        }
        if (existing != null && existing.albumArtist.isBlank() && albumArtistName.isNotBlank()) {
            runCatching { trackDao.updateAlbumArtistIfEmpty(trackId, albumArtistName) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "updateAlbumArtistIfEmpty failed: ${e.message}")
                }
        }

        // Establish orphan-sweep protection before flipping isDownloaded. A
        // failed link therefore leaves a retryable streamable row rather than
        // a downloaded row whose file can be deleted on the next cleanup.
        musicRepository.linkTrackToDownloadsMix(trackId)

        val updated = trackDao.markAsDownloaded(
            trackId = trackId,
            filePath = finalized.committed.filePath,
            fileSizeBytes = finalized.committed.sizeBytes,
            sampleRateHz = finalized.meta?.sampleRateHz,
            bitsPerSample = finalized.meta?.bitsPerSample,
        )

        check(updated == 1) {
            "Track disappeared before download state could be persisted: trackId=$trackId"
        }
        finalized.meta?.let { meta ->
            // Only write probed values when the probe succeeded and reported
            // a real codec. "unknown" indicates MediaMetadataRetriever returned
            // no MIME type — writing it would corrupt the format column.
            if (meta.format != "unknown") {
                runCatching { trackDao.setFormatAndQuality(trackId, meta.format, meta.bitrateKbps) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "setFormatAndQuality failed: ${e.message}")
                    }
            }
        }

        // Trigger loudness measurement off the download thread. The
        // measurement (~25–50 s of ffmpeg ebur128) used to run synchronously
        // inside TrackFinalizer and serialised entire albums behind a
        // single measurer mutex. Now it's fire-and-forget — the row gets
        // updated whenever the background scan completes, the download flow
        // returns immediately.
        loudnessMeasurer.measureAndPersistInBackground(
            trackId = trackId,
            file = java.io.File(finalized.committed.filePath),
        )

        coverArtUrl?.let {
            runCatching { trackDao.fillMissingAlbumArtUrl(trackId, it) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "fillMissingAlbumArtUrl failed: ${e.message}")
                }
        }

    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Converts a [TrackItem] to a [TrackQuery] for the lossless registry.
     *  Passes [TrackItem.album] through so lossless matching can use it as
     *  a tie-breaker on releases with the same track title across albums. */
    private fun TrackItem.toQuery() = TrackQuery(
        artist = artist,
        title = title,
        album = album?.takeIf { it.isNotBlank() },
        isrc = null,
        // (durationSeconds * 1_000).toLong() preserves sub-second precision —
        // .toLong().times(1_000L) would truncate 3.7s → 3_000ms (wrong).
        durationMs = durationSeconds.takeIf { it > 0 }?.let { (it * 1_000).toLong() },
        // TrackItem is the YouTube/search-tab flow (videoId/title/artist/
        // duration only) — no Spotify URI exists, so search-tab downloads
        // correctly skip antra (which requires a spotify track URL).
        spotifyUri = null,
    )

    /**
     * Whether the user blocked this song. A failed lookup reads as not
     * blocked: [upsertSearchTrack] checks again before anything is recorded.
     */
    private suspend fun isBlocked(track: TrackItem): Boolean = try {
        blocklistGuard.isBlocked(
            artist = track.artist,
            title = track.title,
            spotifyUri = null,
            youtubeId = track.videoId,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "blocklist lookup failed for ${track.videoId}: ${e.message}")
        false
    }

    /**
     * The library's own copy of [track], when it has one: the same video's row with a file, or a
     * row with a file that is this song by its words, in the same version ([SameSongRule]) and of
     * about the same length ([lengthsAgree]). Then [download] fetches nothing
     * ([SearchDownloadStatus.AlreadyInLibrary]). A failed lookup reads as no copy: the download
     * goes ahead, and [upsertSearchTrack] never overwrites a file another row holds.
     *
     * Unlike a row's identity ([findReusableRow]), the video id doesn't count here: another upload
     * of a song you have is still that song. With lossless on, the audio is fetched by title and
     * artist, not by video, so tapping a second upload downloaded the same recording again under
     * a second row (device-checked: two near-identical FLACs of one song).
     */
    private suspend fun libraryCopyOf(track: TrackItem): com.stash.core.data.db.entity.TrackEntity? = try {
        val sameVideo = trackDao.findByYoutubeId(track.videoId)
        sameVideo?.takeIf(SameSongRule::holdsFile)
            ?: trackDao.findSameSongByWords(
                title = track.title,
                artist = track.artist,
                youtubeId = null,
                spotifyUri = null,
            ).firstOrNull { SameSongRule.holdsFile(it) && lengthsAgree(it.durationMs, track.durationSeconds) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "library-copy lookup failed for ${track.videoId}: ${e.message}")
        null
    }

    /**
     * Whether a row of [rowMs] and a search result of [itemSeconds] can be one recording: within
     * 10 s or 5 %, or either length unknown. A same-titled track of clearly different length (two
     * "Interlude"s by one artist, a music video with a long intro) is another track.
     */
    private fun lengthsAgree(rowMs: Long, itemSeconds: Double): Boolean {
        if (rowMs <= 0L || itemSeconds <= 0.0) return true
        val diffMs = kotlin.math.abs(rowMs - (itemSeconds * 1000).toLong())
        return diffMs <= maxOf(10_000L, rowMs / 20)
    }

    /**
     * The library row a download of [track] is recorded on, or null for a new row. The same
     * video always reuses its row. Otherwise a row with the same canonical words is taken only
     * under [SameSongRule] for a download: no conflicting video or Spotify id, the same version
     * ("Song (Live)" never lands on "Song"), and no file of its own (a row that already has audio
     * is another copy, which this download must not replace). In practice that is a synced song's
     * stub still waiting for its download, which this download then fills: [download] already
     * stopped at a same-song row with a file ([libraryCopyOf]), so one here landed meanwhile.
     */
    private suspend fun findReusableRow(track: TrackItem) =
        trackDao.findByYoutubeId(track.videoId)
            ?: trackDao.findReusableByWords(
                title = track.title,
                artist = track.artist,
                youtubeId = track.videoId,
                spotifyUri = null,
                forDownload = true,
            )

    /**
     * The library row this download will be recorded on, when there is one:
     * the same lookup [upsertSearchTrack] makes. The commit replaces a file
     * at its name only when no OTHER track records it; without the row's id
     * every row counts as another, so re-downloading a song already in the
     * library would step around its own file and orphan it.
     */
    private suspend fun existingTrackId(track: TrackItem): Long? = try {
        findReusableRow(track)?.id
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "existing-row lookup failed for ${track.videoId}: ${e.message}")
        null
    }

    /**
     * Builds a minimal [com.stash.core.model.Track] stub for [TrackFinalizer].
     * The finalizer uses title/artist/album for metadata embedding and
     * artist/title for the library file path.
     *
     * [TrackItem.album] flows through here so album-context downloads land
     * with a non-empty `tracks.album` value. Without that, downloaded album
     * tracks don't show up in the Library's Albums view (TrackDao.getAllAlbums
     * filters out tracks with empty album values).
     */
    private fun TrackItem.toDomainStub(existingId: Long?) = com.stash.core.model.Track(
        id = existingId ?: 0L,
        title = title,
        artist = artist,
        album = album.orEmpty(),
        albumArtist = albumArtist.orEmpty(),
        durationMs = (durationSeconds * 1_000).toLong(),
        albumArtUrl = thumbnailUrl,
        youtubeId = videoId,
    )

    /**
     * Outcome of [performDownload]. Either:
     *  - [Resolved]: a source was chosen and bytes were finalized (or
     *    finalization failed) — flow emits Downloading + Completed/Failed.
     *  - [Deferred]: v0.9.17 strict-FLAC defer — registry returned null and
     *    the user has yt-dlp fallback off — flow emits WaitingForLossless.
     */
    private sealed interface DownloadJobResult {
        /** Pairs a [SearchDownloadStatus.Source] with the [TrackFinalizer.FinalizeResult]. */
        data class Resolved(
            val source: SearchDownloadStatus.Source,
            val outcome: TrackFinalizer.FinalizeResult,
        ) : DownloadJobResult

        /** v0.9.17: lossless unavailable + fallback off → WaitingForLossless. */
        data object Deferred : DownloadJobResult
    }

    /** Distinguishes fallback-safe temp failures from terminal post-commit outcomes. */
    private sealed interface SourceFinalizeAttempt {
        data class BeforeCommitFailure(val message: String) : SourceFinalizeAttempt
        data class AfterCommit(
            val outcome: TrackFinalizer.FinalizeResult,
        ) : SourceFinalizeAttempt
    }

    /** Expected policy/file rejection while converting a committed file into durable library state. */
    private class PersistenceRejectedException(message: String) : Exception(message)

    companion object {
        private const val TAG = "SearchDownloadCoordinator"

        /** Minimum confidence threshold for accepting a lossless source match. */
        const val MIN_SEARCH_CONFIDENCE = 0.65f
    }
}
