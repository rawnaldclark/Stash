package com.stash.data.download

import android.util.Log
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.mapper.toDomain
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.MetadataEmbedder
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
    private val metadataEmbedder: MetadataEmbedder,
    private val qualityPrefs: QualityPreferencesManager,
    private val ytLibraryCanonicalizer: YtLibraryCanonicalizer,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val lastFmApiClient: LastFmApiClient,
    private val lastFmCredentials: LastFmCredentials,
    private val losslessRegistry: LosslessSourceRegistry,
    private val losslessUrlDownloader: LosslessUrlDownloader,
    private val losslessPrefs: LosslessSourcePreferences,
) {
    /** Limits concurrent downloads. 8 parallel slots — with native opus (no FFmpeg
     *  transcode) downloads are almost entirely network-bound so more parallelism helps. */
    private val concurrencySemaphore = Semaphore(8)

    private val _progress = MutableSharedFlow<DownloadProgress>(replay = 1)

    /** Emits real-time progress snapshots for all in-flight downloads. */
    val progress: SharedFlow<DownloadProgress> = _progress.asSharedFlow()

    companion object {
        private const val TAG = "DownloadManager"
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

        // Step 0: Lossless source attempt. Skipped when a preResolvedUrl
        // is supplied (caller already chose a specific YouTube id).
        // Otherwise we attempt lossless when EITHER:
        //   - the global lossless toggle is on, OR
        //   - this track belongs to a Stash Mix (locally-curated
        //     rotating playlist). Mix tracks are the small, curated
        //     surface where bandwidth/storage cost is worth eating
        //     even if the user hasn't opted into lossless globally —
        //     they'll get FLAC for the ~30 mix tracks and yt-dlp for
        //     the rest of their library.
        // On success we short-circuit the YouTube pipeline and return
        // a finalised path. On any failure we fall through to the
        // YouTube path — same behaviour as before this feature shipped.
        if (preResolvedUrl == null) {
            val forceLossless = isStashMixTrack(track.id)
            if (forceLossless || losslessPrefs.enabledNow()) {
                val losslessResult = tryLosslessDownload(track, forced = forceLossless)
                if (losslessResult != null) return losslessResult
            }
        }

        // Step 1: Resolve YouTube URL
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

        // Metadata is now embedded by yt-dlp via --embed-metadata flag.
        // No separate ffmpeg step needed.

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

    private suspend fun tryLosslessDownload(track: Track, forced: Boolean = false): TrackDownloadResult? {
        val query = TrackQuery(
            artist = track.artist,
            title = track.title,
            album = track.album.takeIf { it.isNotBlank() },
            isrc = track.isrc,
            durationMs = track.durationMs.takeIf { it > 0 },
        )

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
        val ext = match.format.codec.lowercase().ifBlank { "flac" }
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

        emitProgress(track.id, 0.85f, DownloadStatus.PROCESSING)

        // Embed metadata (title/artist/album) so the FLAC has the same
        // tagging discipline as a yt-dlp output. ffmpeg -c copy means
        // no re-encode — fast and lossless.
        runCatching { metadataEmbedder.embedMetadata(fetched, track) }
            .onFailure { e -> Log.w(TAG, "lossless metadata embed failed for ${track.id}", e) }

        val committed = runCatching {
            fileOrganizer.commitDownload(
                tempFile = fetched,
                artist = track.artist,
                album = track.album.ifEmpty { null },
                title = track.title,
                format = ext,
            )
        }.getOrElse { e ->
            Log.w(TAG, "lossless commitDownload failed for ${track.id}", e)
            runCatching { fetched.delete() }
            return null
        }

        Log.i(
            TAG,
            "Lossless downloaded (${match.sourceId}): ${track.artist} - ${track.title} → ${committed.filePath}",
        )
        emitProgress(track.id, 1f, DownloadStatus.COMPLETED)
        return TrackDownloadResult.Success(committed.filePath)
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
     * 1. **Title similarity** >= 0.6 (prevents wrong song by same artist)
     * 2. **Short title containment** — for titles <= 5 chars, candidate must
     *    contain the target as a word (Jaro-Winkler inflates scores for short strings)
     * 3. **Artist similarity** >= 0.65 + word overlap (prevents wrong artist)
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

        // Gate 1: Title similarity
        val titleSim = matchScorer.titleSimilarity(track.title, best.title)
        if (titleSim < 0.6f) {
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

        // Gate 3: Artist similarity + word overlap
        val artistSim = matchScorer.artistSimilarity(track.artist, best.uploader)
        if (artistSim < 0.65f) {
            Log.w(TAG, "resolveUrl: rejecting '${best.title}' by '${best.uploader}' — fuzzy artist sim ${String.format("%.2f", artistSim)} too low for '${track.artist}'")
            return null
        }
        if (!artistWordsMatch(track.artist, best.uploader)) {
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
            if (actualTitleSim < 0.6f) {
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
                    lastFmApiClient.getTrackInfo(track.artist, track.title).getOrNull()
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
