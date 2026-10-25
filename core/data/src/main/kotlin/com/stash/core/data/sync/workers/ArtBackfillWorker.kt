package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmApiException
import com.stash.core.data.lastfm.LastFmCachedMiss
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmEnrichmentBackoff
import com.stash.core.data.lastfm.LastFmLookupRules
import com.stash.core.data.lastfm.LastFmTrackInfo
import com.stash.core.model.YouTubeVideoId
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Backfill that repairs Discovery-pipeline metadata gaps. Three passes run
 * per invocation:
 *
 * ## Pass 1 — `album_art_url`
 * Fills missing art on is_downloaded=1 tracks with a null `album_art_url`.
 * Strategy per candidate:
 *   - Last.fm `track.getInfo(artist, title)` — album art keyed to the
 *     canonical track identity; preferred because the result is real
 *     album art, not a video still.
 *   - Fallback `https://i.ytimg.com/vi/<id>/maxresdefault.jpg` when the row
 *     has a `youtube_id` and Last.fm had nothing. A video without that
 *     variant is walked down to sd/hq by `ArtFallbackInterceptor`.
 *   - Neither: the row waits out a back-off before it is tried again.
 *
 * ## Pass 2 — `duration_ms`
 * Fills missing duration on is_downloaded=1 tracks with `duration_ms = 0`,
 * read off the file with [AudioDurationExtractor]. No network.
 *
 * ## Pass 3 — stream-only stubs
 * One `track.getInfo` per stub fills art and duration together.
 *
 * ## Pass 4 — `album`
 * Rows with no album (a Last.fm playlist's or a mix's songs arrive with only a
 * title and an artist) get the album `track.getInfo` names, filled only where
 * blank. Passes 1 and 3 fill it too from their own lookups; a row any pass
 * already asked about this run is not asked again.
 *
 * ## Keeping the Last.fm traffic to what can fill something
 * This worker was most of the Last.fm proxy's traffic: it re-asked about the
 * same unfillable rows (voice notes, "Unknown Artist" files, deep cuts) on
 * every run, and it ran on every cold start and after every mix refresh,
 * sometimes as two runs at once. Now:
 *  - rows that can't match anything are never looked up
 *    ([LastFmLookupRules.isUnlookable]), and YouTube presentation tags are
 *    stripped from titles first ([LastFmLookupRules.cleanTitle]);
 *  - Last.fm's "not found" is remembered by the client for two weeks
 *    ([LastFmCachedMiss]), so repeats cost no request;
 *  - a row no source could fill waits out
 *    [LastFmEnrichmentBackoff.Pass.ART_BACKFILL], so the batch moves on;
 *  - a run goes ahead only for new rows or every few hours
 *    ([ArtBackfillSchedule]);
 *  - there is one unique work name, so two runs never overlap.
 * A lookup that answers from the cache is not rate-limited (no request).
 */
@HiltWorker
class ArtBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val lastFmApiClient: LastFmApiClient,
    private val credentials: LastFmCredentials,
    private val audioDurationExtractor: AudioDurationExtractor,
    private val backoff: LastFmEnrichmentBackoff,
    private val schedule: ArtBackfillSchedule,
) : CoroutineWorker(appContext, params) {

    /** Test seam for "now". */
    internal var clock: () -> Long = System::currentTimeMillis

    companion object {
        private const val TAG = "ArtBackfill"

        /**
         * The one unique work name, for every enqueue. There used to be a
         * second ("…_mix_refresh") for the mix-refresh path, which let two
         * runs walk the same rows through Last.fm at the same time.
         */
        internal const val WORK_NAME = "stash_art_backfill"

        /** The retired second name; cancelled on enqueue so a queued one can't still run. */
        private const val RETIRED_MIX_REFRESH_WORK_NAME = "stash_art_backfill_mix_refresh"

        private const val BATCH_SIZE = 500
        private const val REQUEST_INTERVAL_MS = 220L

        /** Transient Last.fm failures in a row that end a pass's lookups (an outage). */
        private const val MAX_TRANSIENT_IN_A_ROW = 5

        /**
         * The cold-start enqueue. `KEEP`: a run already queued or running is
         * left alone. A finished run doesn't block the next enqueue (that's
         * WorkManager's KEEP), so the worker itself decides whether there is
         * anything to do ([ArtBackfillSchedule]); a cold start with nothing
         * new costs three small queries and no request.
         */
        fun enqueueOneTime(context: Context) = enqueue(context, ExistingWorkPolicy.KEEP)

        /**
         * After each [StashMixRefreshWorker] cycle, so freshly-created
         * stream-only stubs gain art + duration within one refresh. Same
         * unique name as [enqueueOneTime]. `APPEND_OR_REPLACE`: a refresh that
         * lands while a run is going queues one more run after it, which picks
         * up the stubs the refresh just made (a run already under way may have
         * read its batch before they existed); the queued run does nothing if
         * there turns out to be nothing new.
         */
        fun enqueueFromMixRefresh(context: Context) = enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val work = OneTimeWorkRequestBuilder<ArtBackfillWorker>()
                .setConstraints(constraints)
                .build()
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(RETIRED_MIX_REFRESH_WORK_NAME)
            workManager.enqueueUniqueWork(WORK_NAME, policy, work)
        }
    }

    override suspend fun doWork(): Result {
        val startedAt = clock()
        // One cheap probe per pass: the newest row any pass would look at.
        val newest = listOfNotNull(
            trackDao.findArtBackfillCandidates(1).firstOrNull()?.id,
            trackDao.findDurationBackfillCandidates(1).firstOrNull()?.id,
            trackDao.findStreamableMetadataBackfillCandidates(1).firstOrNull()?.id,
            trackDao.findAlbumBackfillCandidates(1).firstOrNull()?.id,
        ).maxOrNull()
        val state = schedule.read()
        if (!ArtBackfillSchedule.shouldRun(state, newest, startedAt)) {
            Log.i(TAG, "skipped: no new rows (newest=$newest, seen=${state.watermarkId}) and last run was recent")
            return Result.success()
        }
        // Stamped at the start: a run that is killed part-way doesn't make the
        // next cold start redo it at once (its rows come back after the interval).
        schedule.recordRun(startedAt, newest ?: 0L)
        stats = Stats()
        askedThisRun.clear()
        runArtPass(startedAt)
        runDurationPass()
        runStreamableMetadataPass(startedAt)
        runAlbumPass(startedAt)
        Log.i(
            TAG,
            "run done: lastfm network=${stats.network} cached=${stats.cached} " +
                "skippedUnlookable=${stats.unlookable} transient=${stats.transient}",
        )
        return Result.success()
    }

    /** How the lookups of one run went, for the log line that proves the traffic. */
    private class Stats {
        var network = 0
        var cached = 0
        var unlookable = 0
        var transient = 0
    }

    private var stats = Stats()

    /** This run's lookups by (artist, title): a row two passes look at is asked about once. */
    private val askedThisRun = mutableMapOf<Pair<String, String>, LookupResult>()

    /** What a `track.getInfo` lookup for one row came to. */
    private enum class Lookup {
        /** Last.fm answered (a track, or a final "not found"). */
        ANSWERED,

        /** Never sent: the row can't match anything ([LastFmLookupRules.isUnlookable]). */
        UNLOOKABLE,

        /** Failed for a reason that may pass (network, 5xx, rate limit). Retried next run. */
        TRANSIENT,
    }

    private class LookupResult(val info: LastFmTrackInfo?, val kind: Lookup, val fromNetwork: Boolean)

    /**
     * One `track.getInfo` for [artist]/[title], cleaned of YouTube
     * presentation tags first, or none at all for an unlookable row. The
     * client answers repeats from its cache, misses included
     * ([com.stash.core.data.lastfm.LastFmCachedMiss]).
     */
    private suspend fun lookUp(artist: String, title: String): LookupResult {
        askedThisRun[artist to title]?.let { return LookupResult(it.info, it.kind, fromNetwork = false) }
        return lookUpOnce(artist, title).also { askedThisRun[artist to title] = it }
    }

    private suspend fun lookUpOnce(artist: String, title: String): LookupResult {
        if (LastFmLookupRules.isUnlookable(artist, title)) {
            stats.unlookable++
            return LookupResult(null, Lookup.UNLOOKABLE, fromNetwork = false)
        }
        var cached = false
        val result = lastFmApiClient.getTrackInfo(
            artist = LastFmLookupRules.cleanArtist(artist),
            title = LastFmLookupRules.cleanTitle(title),
            onCacheHit = { cached = true },
        )
        if (cached) stats.cached++ else stats.network++
        result.getOrNull()?.let { return LookupResult(it, Lookup.ANSWERED, fromNetwork = !cached) }
        val error = result.exceptionOrNull()
        if (error is CancellationException) throw error
        if (error is LastFmApiException && error.errorCode in LastFmCachedMiss.FINAL_ERROR_CODES) {
            return LookupResult(null, Lookup.ANSWERED, fromNetwork = !cached)
        }
        stats.transient++
        return LookupResult(null, Lookup.TRANSIENT, fromNetwork = !cached)
    }

    /**
     * Pass 1: repair missing `album_art_url`. Network-bound (Last.fm),
     * rate-limited to respect Last.fm's ceiling. Graceful no-op when no
     * candidates match the query.
     *
     * A row neither source could fill waits out the back-off
     * ([LastFmEnrichmentBackoff.Pass.ART_BACKFILL]) before it is tried again,
     * so the batch moves on to other rows instead of re-asking about the same
     * ones every run. A transient failure doesn't count against a row; five
     * in a row end the Last.fm part of the pass (an outage).
     */
    private suspend fun runArtPass(startedAt: Long) {
        val candidates = backoff.candidateRows(
            LastFmEnrichmentBackoff.Pass.ART_BACKFILL, BATCH_SIZE, startedAt, { it.id },
        ) { limit -> trackDao.findArtBackfillCandidates(limit) }
        if (candidates.isEmpty()) {
            Log.d(TAG, "no tracks need art backfill")
            return
        }
        Log.i(TAG, "backfilling art for ${candidates.size} tracks")

        var lastFmUsable = credentials.isConfigured
        var filled = 0
        val unfillable = mutableListOf<Long>()
        var transientInARow = 0
        for (row in candidates) {
            val lookup = if (lastFmUsable) lookUp(row.artist, row.title) else null
            lookup?.info?.let { fillAlbum(row.id, it) }
            val artUrl = lookup?.info?.bestImageUrl?.takeIf { it.isNotBlank() } ?: youTubeArt(row.youtubeId)
            if (artUrl != null) {
                runCatching { trackDao.fillMissingAlbumArtUrl(row.id, artUrl) }
                    .onSuccess { filled++ }
                    .onFailure { e ->
                        Log.w(TAG, "fillMissingAlbumArtUrl failed for trackId=${row.id}", e)
                    }
            } else if (lookup?.kind == Lookup.ANSWERED || lookup?.kind == Lookup.UNLOOKABLE ||
                (lookup == null && !credentials.isConfigured)
            ) {
                // Every source had its say and none had art. (A row the outage
                // stop left unasked isn't one: it is tried next run.)
                unfillable += row.id
            }
            if (lookup?.kind == Lookup.TRANSIENT) {
                if (++transientInARow >= MAX_TRANSIENT_IN_A_ROW) {
                    Log.w(TAG, "art pass: $transientInARow Last.fm failures in a row; no more lookups this run")
                    lastFmUsable = false
                }
            } else if (lookup != null) {
                transientInARow = 0
            }
            // Rate limit, only after a request actually went out: a cached or
            // skipped row costs Last.fm nothing.
            if (lookup?.fromNetwork == true) delay(REQUEST_INTERVAL_MS)
        }
        backoff.recordFailures(LastFmEnrichmentBackoff.Pass.ART_BACKFILL, unfillable, startedAt)
        Log.i(TAG, "art backfill done: filled=$filled unfillable=${unfillable.size} (backed off)")
    }

    /**
     * Pass 2: repair missing `duration_ms`. Purely local — reads each
     * file's container metadata via `MediaMetadataRetriever`. No rate-
     * limit needed; each extraction takes single-digit milliseconds and
     * the candidate set is typically small (dozens, not thousands).
     */
    private suspend fun runDurationPass() {
        val candidates = trackDao.findDurationBackfillCandidates(BATCH_SIZE)
        if (candidates.isEmpty()) {
            Log.d(TAG, "no tracks need duration backfill")
            return
        }
        Log.i(TAG, "backfilling duration for ${candidates.size} tracks")

        var filled = 0
        var skipped = 0
        for (row in candidates) {
            val ms = audioDurationExtractor.extractMs(row.filePath)
            if (ms != null && ms > 0) {
                runCatching { trackDao.fillMissingDuration(row.id, ms) }
                    .onSuccess { filled++ }
                    .onFailure { e ->
                        Log.w(TAG, "fillMissingDuration failed for trackId=${row.id}", e)
                    }
            } else {
                skipped++
            }
        }
        Log.i(TAG, "duration backfill done: filled=$filled skipped=$skipped (unreadable or missing)")
    }

    /**
     * Pass 3: hydrate v0.9.37 stream-only stubs. `StashDiscoveryWorker`
     * lands those rows with only title + artist; album art and duration
     * default to NULL / 0, so the mix list rendered with empty thumbnails
     * and missing run-times until the player resolved a queue window at
     * play time (conversation 2026-05-28).
     *
     * One Last.fm `track.getInfo` lookup per candidate yields both
     * `bestImageUrl` and `durationMs`, so we issue a single network call
     * and apply both UPDATEs guarded by their existing "only-if-blank"
     * predicates. Rate-limited like [runArtPass]. No-ops when Last.fm isn't
     * configured — the play-time hydration path remains the fallback.
     *
     * A stub Last.fm answered without filling both (no such track, no art,
     * no duration, which is common) stays a candidate by the query's rule;
     * it now waits out the back-off instead of being asked about again on
     * every run.
     */
    private suspend fun runStreamableMetadataPass(startedAt: Long) {
        if (!credentials.isConfigured) {
            Log.d(TAG, "Last.fm not configured — skipping stream-only metadata backfill")
            return
        }
        val candidates = backoff.candidateRows(
            LastFmEnrichmentBackoff.Pass.ART_BACKFILL, BATCH_SIZE, startedAt, { it.id },
        ) { limit -> trackDao.findStreamableMetadataBackfillCandidates(limit) }
        if (candidates.isEmpty()) {
            Log.d(TAG, "no stream-only stubs need metadata backfill")
            return
        }
        Log.i(TAG, "backfilling metadata for ${candidates.size} stream-only stubs")

        var artFilled = 0
        var durationFilled = 0
        val unfillable = mutableListOf<Long>()
        var transientInARow = 0
        for (row in candidates) {
            val lookup = lookUp(row.artist, row.title)
            if (lookup.fromNetwork) delay(REQUEST_INTERVAL_MS)
            if (lookup.kind == Lookup.TRANSIENT) {
                if (++transientInARow >= MAX_TRANSIENT_IN_A_ROW) {
                    Log.w(TAG, "stream-only pass: $transientInARow Last.fm failures in a row; stopping")
                    break
                }
                continue
            }
            transientInARow = 0
            val info = lookup.info
            info?.let { fillAlbum(row.id, it) }
            val art = info?.bestImageUrl?.takeIf { it.isNotBlank() }
            val ms = info?.durationMs?.takeIf { it > 0 }
            art?.let { artUrl ->
                runCatching { trackDao.fillMissingAlbumArtUrl(row.id, artUrl) }
                    .onSuccess { artFilled++ }
                    .onFailure { e ->
                        Log.w(TAG, "stream-only art fill failed for trackId=${row.id}", e)
                    }
            }
            ms?.let {
                runCatching { trackDao.fillMissingDuration(row.id, it) }
                    .onSuccess { durationFilled++ }
                    .onFailure { e ->
                        Log.w(TAG, "stream-only duration fill failed for trackId=${row.id}", e)
                    }
            }
            // Missing either one leaves the row a candidate: back it off. (A row
            // that only lacked the one it got is no longer a candidate, so the
            // entry is harmless.)
            if (art == null || ms == null) unfillable += row.id
        }
        backoff.recordFailures(LastFmEnrichmentBackoff.Pass.ART_BACKFILL, unfillable, startedAt)
        Log.i(
            TAG,
            "stream-only backfill done: art=$artFilled duration=$durationFilled " +
                "incomplete=${unfillable.size} (backed off)",
        )
    }

    /** Fills [trackId]'s album from [info] where it has none; true when there was one to fill. */
    private suspend fun fillAlbum(trackId: Long, info: LastFmTrackInfo): Boolean {
        val album = info.albumTitle ?: return false
        runCatching { trackDao.fillMissingAlbum(trackId, album, info.albumArtist.orEmpty()) }
            .onFailure { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "fillMissingAlbum failed for trackId=$trackId", e)
            }
        return true
    }

    /**
     * Pass 4: rows with no album get the one Last.fm names. Rows passes 1 and 3
     * asked about this run cost nothing here; the rest are rate-limited like
     * [runArtPass]. A row Last.fm names no album for waits out
     * [LastFmEnrichmentBackoff.Pass.ALBUM_BACKFILL]; five transient failures in
     * a row end the pass.
     */
    private suspend fun runAlbumPass(startedAt: Long) {
        if (!credentials.isConfigured) return
        val candidates = backoff.candidateRows(
            LastFmEnrichmentBackoff.Pass.ALBUM_BACKFILL, BATCH_SIZE, startedAt, { it.id },
        ) { limit -> trackDao.findAlbumBackfillCandidates(limit) }
        if (candidates.isEmpty()) {
            Log.d(TAG, "no tracks need an album")
            return
        }
        var filled = 0
        val unfillable = mutableListOf<Long>()
        var transientInARow = 0
        for (row in candidates) {
            val lookup = lookUp(row.artist, row.title)
            if (lookup.fromNetwork) delay(REQUEST_INTERVAL_MS)
            if (lookup.kind == Lookup.TRANSIENT) {
                if (++transientInARow >= MAX_TRANSIENT_IN_A_ROW) {
                    Log.w(TAG, "album pass: $transientInARow Last.fm failures in a row; stopping")
                    break
                }
                continue
            }
            transientInARow = 0
            if (lookup.info?.let { fillAlbum(row.id, it) } == true) filled++ else unfillable += row.id
        }
        backoff.recordFailures(LastFmEnrichmentBackoff.Pass.ALBUM_BACKFILL, unfillable, startedAt)
        Log.i(TAG, "album backfill done: filled=$filled unknown=${unfillable.size} (backed off)")
    }

    /** The YouTube still for a row with a real video id, else null. */
    private fun youTubeArt(youtubeId: String?): String? =
        // Only an id with the YouTube video-id shape is built into a URL.
        if (YouTubeVideoId.isValid(youtubeId)) "https://i.ytimg.com/vi/$youtubeId/maxresdefault.jpg" else null
}
