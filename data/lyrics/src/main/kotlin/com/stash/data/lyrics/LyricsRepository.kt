package com.stash.data.lyrics

import android.util.Log
import com.stash.core.common.Clock
import com.stash.core.data.db.dao.LyricsDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.data.prefs.LyricsPreference
import com.stash.core.data.prefs.LyricsSourcePreference
import com.stash.data.lyrics.sidecar.LyricsSidecarWriter
import com.stash.data.lyrics.source.AppleTtmlLyricsSource
import com.stash.data.lyrics.source.LyricsQuery
import com.stash.data.lyrics.source.LyricsResult
import com.stash.data.lyrics.source.LyricsSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of [LyricsRepository.upgradeToTtml]. Only UPGRADED changes stored lyrics. */
enum class TtmlUpgradeResult { UPGRADED, NO_TTML, FAILED, RATE_LIMITED, SKIPPED }

/** Outcome of [LyricsRepository.fetchLyricsNow] (manual "fetch lyrics" run). */
enum class ManualFetchResult { FETCHED, NOT_FOUND, FAILED, SKIPPED }

/**
 * A finished lookup kept in memory: [result] null is a definitive miss (not a failure).
 * [appleCleanMiss]: Apple was asked and cleanly had no TTML (stamped when the answer is stored).
 * [degraded]: a later source answered after an earlier one failed (Apple timed out, LRCLIB hit),
 * so it is kept only briefly: the next ask, minutes on, may get the word-synced version.
 */
class CachedLyrics(
    val result: LyricsResult?,
    val appleCleanMiss: Boolean = false,
    val degraded: Boolean = false,
    val atMs: Long = 0L,
)

/** One walk of the source chain. */
private class WalkOutcome(val result: LyricsResult?, val appleCleanMiss: Boolean, val degraded: Boolean)

/**
 * Sole entrypoint for the lyrics subsystem. Both UI (Now Playing sheet)
 * and workers (post-download + backfill) go through this class.
 *
 * `resolveAndStore` walks the [sources] chain in priority order (Apple TTML -> LRCLIB -> KuGou ->
 * InnerTube; ordering enforced in `LyricsModule.provideLyricsSources`, and Apple is skipped
 * entirely when [LyricsPreference.sourcePreference] is [LyricsSourcePreference.LRC_ONLY] — see
 * [activeSources]), persists the first non-null result to the `lyrics` table, stamps
 * `tracks.lyrics_fetched_at` with the success epoch-millis, and triggers a sidecar write.
 *
 * Sentinel rules (see `LyricsDao` docs):
 * - Successful fetch -> upsert lyrics row + stamp `lyrics_fetched_at = clock.now()`.
 * - Definitive miss across all sources -> no row + stamp `lyrics_fetched_at = 0L`.
 * - Source FAILURE (network/HTTP/parse — the source threw) with no hit from a
 *   later source -> THROWS and leaves the stamp untouched, so the track stays retryable.
 *
 * Sidecar-write failure is logged but does NOT unwind the Room state.
 *
 * One lookup per song: every walk goes through [sharedWalk], keyed by the song (its YouTube id,
 * else title + artist + album + duration). Concurrent asks for the same song with the same timeout
 * profile join the walk in flight, and a finished answer (hit or definitive miss, never a failure)
 * is kept in memory for the process, bounded to [RECENT_MAX] songs; a degraded hit only for
 * [DEGRADED_TTL_MS]. So a song asked about by Now Playing, the prefetch, a second screen, and again
 * once a streamed track is persisted under its library id, costs one network walk. Retry and the
 * manual library-wide fetch pass `fresh` to ask the network again; a source-preference change
 * forgets everything.
 *
 * One store per track: concurrent [resolveAndStore] calls for one track id share one store, so
 * the row, the stamp and the sidecar are written once (on a SAF tree, racing writers otherwise
 * leave "Song (1).lrc" duplicates in the user's music folder).
 */
@Singleton
class LyricsRepository @Inject constructor(
    private val sources: List<@JvmSuppressWildcards LyricsSource>,
    private val lyricsDao: LyricsDao,
    private val trackDao: TrackDao,
    private val sidecarWriter: LyricsSidecarWriter,
    private val clock: Clock,
    private val lyricsPreference: LyricsPreference,
) {

    /** The scope shared walks run in, so a caller leaving (a closed screen) doesn't cancel them. */
    private val walkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val walkLock = Any()

    /** Walks in flight, by song key + timeout profile. Guarded by [walkLock]. */
    private val inFlight = HashMap<String, Deferred<WalkOutcome>>()

    /** Finished walks, by song key, least-recently-used first. Guarded by [walkLock]. */
    private val recent = object : LinkedHashMap<String, CachedLyrics>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLyrics>?): Boolean =
            size > RECENT_MAX
    }

    /** Bumped when the source chain changes: walks started before it aren't remembered. Guarded by [walkLock]. */
    private var generation = 0

    /** Stores in flight, by track id. Guarded by [walkLock]. */
    private val storing = HashMap<Long, Deferred<LyricsEntity?>>()

    /** The current prefetch: its key and job. Guarded by [walkLock]. */
    private var prefetchKey: String? = null
    private var prefetchJob: Job? = null

    fun observe(trackId: Long): Flow<LyricsEntity?> = lyricsDao.observe(trackId)

    fun observeFetchedAt(trackId: Long): Flow<Long?> = trackDao.observeLyricsFetchedAt(trackId)

    suspend fun get(trackId: Long): LyricsEntity? = lyricsDao.get(trackId)

    /**
     * Walks [sources] in order, returns the first non-null [LyricsResult], persists it to Room,
     * stamps `tracks.lyrics_fetched_at`, and triggers a sidecar write.
     *
     * On a definitive all-source miss, stamps `tracks.lyrics_fetched_at = 0L` and returns null
     * without writing a row. If any source FAILED and no later source hit, rethrows that failure
     * without stamping.
     *
     * Single-flight per track id: a call while another store for the same track is running joins
     * it. [skipIfStamped] (the prefetch, Now Playing's own trigger) returns the stored row when the
     * track was stamped meanwhile, so a caller that checked the stamp a moment earlier doesn't
     * write a second time.
     */
    suspend fun resolveAndStore(
        query: LyricsQuery,
        fresh: Boolean = false,
        skipIfStamped: Boolean = false,
    ): LyricsEntity? {
        val trackId = query.trackId
        val store = synchronized(walkLock) {
            storing[trackId] ?: walkScope.async(start = CoroutineStart.LAZY) {
                storeOnce(query, fresh, skipIfStamped)
            }.also { deferred ->
                storing[trackId] = deferred
                deferred.invokeOnCompletion {
                    synchronized(walkLock) { if (storing[trackId] === deferred) storing.remove(trackId) }
                }
            }
        }
        return store.await()
    }

    private suspend fun storeOnce(query: LyricsQuery, fresh: Boolean, skipIfStamped: Boolean): LyricsEntity? {
        if (skipIfStamped && trackDao.observeLyricsFetchedAt(query.trackId).first() != null) {
            return lyricsDao.get(query.trackId)
        }
        val outcome = sharedWalk(query, fresh)
        val result = outcome.result
        val appleCleanMiss = outcome.appleCleanMiss
        if (result == null) {
            trackDao.setLyricsFetchedAt(query.trackId, 0L)
            return null
        }
        // Carries over any sync offset the user already set: upsert replaces the whole row, so
        // without this a rewrite (an Apple upgrade, or Retry) silently reset a manually-tuned
        // offset back to 0.
        val previousRow = lyricsDao.get(query.trackId)
        val now = clock.now()
        val entity = LyricsEntity(
            trackId = query.trackId,
            plainText = result.plainText,
            syncedLrc = result.syncedLrc,
            instrumental = result.instrumental,
            language = result.language,
            source = result.sourceId,
            sourceLyricsId = result.sourceLyricsId,
            fetchedAt = now,
            ttml = result.ttml,
            // Remember "Apple asked, has nothing" so the upgrade pass doesn't re-ask on every
            // release. An Apple error (or LRC-only) keeps the previous stamp — but only a MISS
            // stamp: a row that had TTML before must stay eligible for the upgrade pass.
            ttmlCheckedAt = when {
                result.ttml != null || appleCleanMiss -> now
                previousRow?.ttml == null -> previousRow?.ttmlCheckedAt
                else -> null
            },
            syncOffsetMs = previousRow?.syncOffsetMs ?: 0L,
        )
        lyricsDao.upsert(entity)
        trackDao.setLyricsFetchedAt(query.trackId, now)
        if (!result.instrumental) {
            runCatching { sidecarWriter.write(query.trackId, entity) }
                .onFailure { e ->
                    // The courtesy .lrc beside the audio: a streamed library track has no file to
                    // sit beside, which is expected, not a failure worth a stack trace.
                    if (e is LyricsSidecarWriter.NoDownloadedFileException) {
                        Log.d(TAG, "No sidecar for trackId=${query.trackId}: not downloaded")
                    } else {
                        Log.w(TAG, "Sidecar write failed for trackId=${query.trackId}", e)
                    }
                }
        }
        return entity
    }

    /** A lookup for a track with no library row (a stream). Shares the walk and its memory, see [sharedWalk]. */
    suspend fun resolveTransient(query: LyricsQuery, fresh: Boolean = false): LyricsResult? =
        sharedWalk(query, fresh).result

    /**
     * The finished answer for [query]'s song, if one is in memory: lets a screen show lyrics
     * already in hand at once, with no Loading frame. Null when the song hasn't been looked up.
     */
    fun cached(query: LyricsQuery): CachedLyrics? = synchronized(walkLock) { remembered(songKey(query)) }

    /**
     * Looks the playing song up early, once (the app-wide prefetch). Every Now Playing ViewModel
     * asks (the mini player's and Now Playing's); a request for the song already asked for is
     * ignored, so the work runs once whatever the number of askers. A different song cancels the
     * previous request's wait (a walk already started still finishes, detached, and is
     * remembered). [store]: the track has a library row, so the answer is stored under
     * [LyricsQuery.trackId] (once, see [resolveAndStore]); else it is only remembered. Null
     * [query] stops (the app went to the background, or the track needs nothing).
     *
     * Callers wait out a dwell first (a skip burst never reaches here).
     */
    fun prefetch(query: LyricsQuery?, store: Boolean) {
        synchronized(walkLock) {
            val key = query?.let { if (store) "id:${it.trackId}" else songKey(it) }
            if (key == prefetchKey) return
            prefetchKey = key
            prefetchJob?.cancel()
            prefetchJob = query?.let { q ->
                walkScope.launch {
                    try {
                        if (store) resolveAndStore(q, skipIfStamped = true) else resolveTransient(q)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Best effort: Now Playing's own trigger asks again when it opens.
                        Log.d(TAG, "Prefetch for \"${q.title}\" failed: ${e.message}")
                    }
                }
            }
        }
    }

    suspend fun clearFetchStamp(trackId: Long) = trackDao.setLyricsFetchedAt(trackId, null)

    /**
     * Empty when the user has set LRC-only — nothing to upgrade if Apple is never consulted.
     * Starts just after the id the last upgrade pass reached and wraps round, same reasoning as
     * [trackIdsMissingLyrics]: a pass cut short by the consecutive-failure bail, Cancel, or the OS
     * job cap otherwise re-asks the same head of the list — which stalled every prior instance of
     * this pass when Apple's TTML source was down, since MAX_CONSECUTIVE_FAILURES bails out almost
     * immediately on the same handful of tracks every single run.
     */
    suspend fun trackIdsPendingTtml(): List<Long> {
        if (lyricsPreference.sourcePreference.first() == LyricsSourcePreference.LRC_ONLY) return emptyList()
        val after = lyricsPreference.ttmlUpgradeCursor.first()
        // ponytail: one resume point, not per-track state. Stable sort keeps id order in each half.
        return lyricsDao.trackIdsPendingTtml().sortedBy { it <= after }
    }

    /**
     * Downloaded tracks that have no lyrics (never tried, or an earlier all-source miss), starting
     * just after the one the last manual run reached and wrapping round, so a run cut short
     * (Cancel, app killed, the OS job cap) moves on instead of re-asking the same head of the list.
     */
    suspend fun trackIdsMissingLyrics(): List<Long> {
        val after = lyricsPreference.bulkFetchCursor.first()
        // ponytail: one resume point, not per-track state. Stable sort keeps id order in each half.
        return lyricsDao.trackIdsMissingLyrics().sortedBy { it <= after }
    }

    /**
     * Manual-run path: walks the full source chain for one track and stores the hit. Failures are
     * swallowed into [ManualFetchResult.FAILED] (state untouched, so it stays retryable); a
     * definitive miss re-stamps 0L exactly as [resolveAndStore] always does.
     */
    suspend fun fetchLyricsNow(trackId: Long): ManualFetchResult {
        // First, before the network, so a kill mid-request still moves the next run on.
        lyricsPreference.setBulkFetchCursor(trackId)
        val track = trackDao.getById(trackId) ?: return ManualFetchResult.SKIPPED
        val query = LyricsQuery(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album.ifBlank { null },
            albumArtist = track.albumArtist.ifBlank { null },
            durationMs = track.durationMs.takeIf { it > 0 },
            youtubeVideoId = track.youtubeId,
        )
        return try {
            if (resolveAndStore(query, fresh = true) != null) ManualFetchResult.FETCHED else ManualFetchResult.NOT_FOUND
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Manual lyrics fetch failed for trackId=$trackId", e)
            ManualFetchResult.FAILED
        }
    }

    /**
     * Applies a new [LyricsSourcePreference]. Switching TO [LyricsSourcePreference.LRC_ONLY] wipes
     * every stored row's TTML (column + `.ttml` sidecar) and clears `tracks.lyrics_fetched_at` for
     * those tracks, so they fall back into the existing "missing lyrics" pool. Switching back to
     * APPLE_MUSIC does nothing extra: Apple simply re-enters the chain on the next fetch — no work
     * is queued automatically here.
     */
    suspend fun setSourcePreference(preference: LyricsSourcePreference) {
        // Answers from the old chain must not outlive it (an LRC-only user would get Apple TTML
        // for streams, and a persisted stream would write it back into Room).
        synchronized(walkLock) {
            generation++
            recent.clear()
            inFlight.clear() // still running walks finish for their askers, but aren't remembered
        }
        if (preference == LyricsSourcePreference.LRC_ONLY) {
            val affected = lyricsDao.trackIdsWithTtml()
            affected.forEach { id -> sidecarWriter.deleteTtmlSidecar(id) }
            lyricsDao.clearAllTtml()
            affected.forEach { id -> trackDao.setLyricsFetchedAt(id, null) }
            Log.i(TAG, "Switched to LRC-only: wiped TTML for ${affected.size} track(s), queued for re-fetch")
        }
        lyricsPreference.setSourcePreference(preference)
    }

    /**
     * Backfill path: asks ONLY the Apple TTML source and replaces the stored lyrics + sidecar only
     * on a hit.
     *
     * - hit          -> row upserted (sync offset carried over from [existing]), fetch stamp
     *                   refreshed, sidecar rewritten (keeps the existing .lrc, adds/refreshes .ttml
     *                   beside it) -> UPGRADED
     * - clean miss   -> `ttml_checked_at` stamped, row untouched -> NO_TTML
     * - source threw -> NOTHING written, stays pending -> FAILED (RATE_LIMITED for an HTTP 429)
     * - no row / already TTML / instrumental / source not in chain -> SKIPPED
     */
    suspend fun upgradeToTtml(trackId: Long): TtmlUpgradeResult {
        // First, before the network, so a kill mid-request still moves the next run on.
        lyricsPreference.setTtmlUpgradeCursor(trackId)
        if (lyricsPreference.sourcePreference.first() == LyricsSourcePreference.LRC_ONLY) {
            return TtmlUpgradeResult.SKIPPED
        }
        val apple = sources.firstOrNull { it.id == AppleTtmlLyricsSource.SOURCE_ID }
            ?: return TtmlUpgradeResult.SKIPPED
        val existing = lyricsDao.get(trackId) ?: return TtmlUpgradeResult.SKIPPED
        if (existing.ttml != null || existing.instrumental) return TtmlUpgradeResult.SKIPPED
        val track = trackDao.getById(trackId) ?: return TtmlUpgradeResult.SKIPPED

        val query = LyricsQuery(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album.ifBlank { null },
            albumArtist = track.albumArtist.ifBlank { null },
            durationMs = track.durationMs.takeIf { it > 0 },
            youtubeVideoId = track.youtubeId,
        )
        val result = try {
            apple.resolve(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "TTML upgrade failed for trackId=$trackId", e)
            val rateLimited = (e as? AppleTtmlLyricsSource.HttpStatusException)?.code == 429
            return if (rateLimited) TtmlUpgradeResult.RATE_LIMITED else TtmlUpgradeResult.FAILED
        }

        val now = clock.now()
        if (result == null) {
            lyricsDao.markTtmlChecked(trackId, now)
            return TtmlUpgradeResult.NO_TTML
        }
        val entity = LyricsEntity(
            trackId = trackId,
            plainText = result.plainText,
            syncedLrc = result.syncedLrc,
            instrumental = false,
            language = result.language ?: existing.language,
            source = result.sourceId,
            sourceLyricsId = result.sourceLyricsId,
            fetchedAt = now,
            ttml = result.ttml,
            ttmlCheckedAt = now,
            syncOffsetMs = existing.syncOffsetMs,
        )
        lyricsDao.upsert(entity)
        trackDao.setLyricsFetchedAt(trackId, now)
        if (track.filePath != null) {
            // existing != null above means this is never a "new" fetch — the writer keeps whatever
            // .lrc was already there and adds/refreshes the .ttml beside it.
            runCatching { sidecarWriter.write(trackId, entity) }
                .onFailure { e -> Log.w(TAG, "Sidecar rewrite failed for trackId=$trackId", e) }
        }
        return TtmlUpgradeResult.UPGRADED
    }

    /** Null when no row exists yet (never fetched) — callers treat that as "no offset set". */
    fun observeSyncOffsetMs(trackId: Long): Flow<Long?> = lyricsDao.observeSyncOffsetMs(trackId)

    suspend fun setSyncOffsetMs(trackId: Long, offsetMs: Long) = lyricsDao.setSyncOffsetMs(trackId, offsetMs)

    private suspend fun activeSources(): List<LyricsSource> =
        if (lyricsPreference.sourcePreference.first() == LyricsSourcePreference.LRC_ONLY) {
            sources.filterNot { it.id == AppleTtmlLyricsSource.SOURCE_ID }
        } else {
            sources
        }

    /**
     * The one network walk per song (see the class KDoc). [fresh] skips the memory but still
     * joins a walk already in flight for the same song. A failed walk is not remembered, so the
     * next ask tries again.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // Deferred.getCompleted, read only once complete
    private suspend fun sharedWalk(query: LyricsQuery, fresh: Boolean): WalkOutcome {
        val key = songKey(query)
        // A background walk (long timeouts, a retry) and an interactive one don't share a flight:
        // someone watching the screen shouldn't inherit a worker's 10-second profile.
        val flightKey = key + if (query.interactive) "|i" else "|b"
        val walk = synchronized(walkLock) {
            if (!fresh) remembered(key)?.let { return WalkOutcome(it.result, it.appleCleanMiss, it.degraded) }
            inFlight[flightKey] ?: run {
                val startedIn = generation
                walkScope.async(start = CoroutineStart.LAZY) { walkSourcesNotingApple(query) }.also { deferred ->
                    // Registered before the walk starts, so even an instant answer is filed correctly.
                    inFlight[flightKey] = deferred
                    deferred.invokeOnCompletion { failure ->
                        synchronized(walkLock) {
                            if (inFlight[flightKey] === deferred) inFlight.remove(flightKey)
                            if (failure == null && startedIn == generation) {
                                val o = deferred.getCompleted()
                                recent[key] = CachedLyrics(o.result, o.appleCleanMiss, o.degraded, clock.now())
                            }
                        }
                    }
                }
            }
        }
        return walk.await() // starts the walk if this caller created it
    }

    /** The remembered answer for [key], unless it is a degraded one past [DEGRADED_TTL_MS]. Under [walkLock]. */
    private fun remembered(key: String): CachedLyrics? {
        val cached = recent[key] ?: return null
        if (cached.degraded && clock.now() - cached.atMs > DEGRADED_TTL_MS) {
            recent.remove(key)
            return null
        }
        return cached
    }

    /** Walks the active sources in order: the first hit, plus whether Apple was asked and cleanly returned nothing. */
    private suspend fun walkSourcesNotingApple(query: LyricsQuery): WalkOutcome {
        var firstFailure: Exception? = null
        var appleCleanMiss = false
        for (source in activeSources()) {
            try {
                val result = source.resolve(query)
                if (result != null) return WalkOutcome(result, appleCleanMiss, degraded = firstFailure != null)
                if (source.id == AppleTtmlLyricsSource.SOURCE_ID) appleCleanMiss = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Lyrics source ${source.id} failed for trackId=${query.trackId}", e)
                if (firstFailure == null) firstFailure = e
            }
        }
        firstFailure?.let { throw it }
        return WalkOutcome(null, appleCleanMiss, degraded = false)
    }

    private companion object {
        private const val TAG = "LyricsRepository"

        /**
         * Songs kept in memory: this session's recent ones. Word-synced TTML runs to tens of KB
         * of text per song, so the bound stays small (the Samsung OOM reports, #238/#239).
         */
        private const val RECENT_MAX = 24

        /** How long a degraded answer (a hit after an earlier source failed) is kept. */
        private const val DEGRADED_TTL_MS = 2 * 60_000L

        /**
         * One key per song: the YouTube id when there is one (it survives a stream being
         * persisted under a library id, whose duration may differ or be unknown), else title +
         * artist + album + whole-second duration, case-folded, so two different songs sharing a
         * title ("Intro" on two albums, a studio and a live take) never share an answer.
         */
        fun songKey(query: LyricsQuery): String =
            query.youtubeVideoId?.takeIf { it.isNotBlank() }?.let { "yt:$it" }
                ?: listOf(
                    query.title.trim().lowercase(),
                    query.artist.trim().lowercase(),
                    query.album?.trim()?.lowercase().orEmpty(),
                    query.durationMs?.div(1000)?.toString().orEmpty(),
                ).joinToString(separator = "\u0000", prefix = "ta:")
    }
}