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
import com.stash.data.lyrics.source.IsrcLyricsHostBreaker
import com.stash.data.lyrics.source.IsrcTtmlLyricsSource
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.InterruptedIOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of [LyricsRepository.upgradeToTtml]. Only UPGRADED changes stored lyrics.
 * NOT_READY: the live lookup timed out (the server keeps working and caches, so a retry
 * shortly after usually hits the cache). PAUSED: the skip window is open.
 */
enum class TtmlUpgradeResult { UPGRADED, NO_TTML, FAILED, RATE_LIMITED, SKIPPED, NOT_READY, PAUSED }

/**
 * Which word-synced sources [LyricsRepository.upgradeToTtml] asks.
 * - [APPLE]: Apple only. Library Health's "Fetch lyrics" upgrade pass: it never fans out to the
 *   on-demand sources.
 * - [APPLE_THEN_ON_DEMAND]: Apple, then the [LyricsSource.onDemandOnly] sources. The Now Playing
 *   "word-synced lyrics" action for the playing track, and the user's "Re-check library" sweep
 *   ([LyricsRepository.recheckTrack]) for a track Apple never answered.
 * - [ON_DEMAND]: only the on-demand sources. The one follow-up after a played/downloaded track's
 *   lyrics were first stored without word timing, and the re-check sweep for a track Apple already
 *   cleanly missed; a miss stamps nothing (Apple's own check stays the Apple passes' business).
 */
enum class TtmlUpgradeSources { APPLE, APPLE_THEN_ON_DEMAND, ON_DEMAND }

/**
 * One "Re-check library" sweep, as [LyricsRepository.recheckPlan] sees it: the tracks still to ask,
 * newest first, and how many the whole sweep covers (so progress counts on across a resume).
 */
class RecheckPlan(val remaining: List<Long>, val total: Int)

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
 * InnerTube -> lrc.red -> Binimum; ordering enforced in `LyricsModule.provideLyricsSources`. The
 * last two are [LyricsSource.onDemandOnly]: asked only for a [LyricsQuery.onDemand] lookup (a
 * track being played or just downloaded), after every other source, and their failures count as
 * misses. When an earlier source answers without word timing, they get one follow-up try in the
 * background instead ([upgradeOnDemandLater]), so they never delay lyrics. TTML sources are skipped
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
 * One writer per track: [resolveAndStore] writes (row, stamp, sidecar) under a per-track lock, so
 * writes for one track never overlap (on a SAF tree, racing writers otherwise leave "Song (1).lrc"
 * duplicates in the user's music folder), and callers passing `skipIfStamped` (the prefetch, Now
 * Playing's trigger) write nothing once the track is stamped: one write per track. Each caller
 * walks with its own profile and flags, so nobody inherits another caller's slow timeouts or a
 * stamped miss.
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

    /** Per-track write locks, striped: writes for one track never overlap (cheap for others to share a stripe). */
    private val writeLocks = Array(WRITE_STRIPES) { Mutex() }

    private fun writeLock(trackId: Long): Mutex = writeLocks[Math.floorMod(trackId, WRITE_STRIPES.toLong()).toInt()]

    /** Tracks the on-demand follow-up already ran for this process, bounded. Guarded by [walkLock]. */
    private val onDemandTried: MutableSet<Long> = java.util.Collections.newSetFromMap(
        object : LinkedHashMap<Long, Boolean>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?): Boolean =
                size > ON_DEMAND_TRIED_MAX
        },
    )

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
     * The walk is the caller's own (its profile, its [fresh]; joined or remembered per
     * [sharedWalk]). The write is serialised per track and can't be cancelled half-way (a
     * cancelled prefetch never leaves a row without its stamp). [skipIfStamped] (the prefetch,
     * Now Playing's own trigger) returns the stored row instead of writing when the track is
     * stamped, checked again inside the write lock, so concurrent askers write once.
     */
    suspend fun resolveAndStore(
        query: LyricsQuery,
        fresh: Boolean = false,
        skipIfStamped: Boolean = false,
    ): LyricsEntity? {
        if (skipIfStamped && isStamped(query.trackId)) return lyricsDao.get(query.trackId)
        val outcome = sharedWalk(query, fresh)
        val written = withContext(NonCancellable) {
            writeLock(query.trackId).withLock {
                if (skipIfStamped && isStamped(query.trackId)) Written(lyricsDao.get(query.trackId), wantsOnDemandUpgrade = false)
                else write(query, outcome)
            }
        }
        if (written.wantsOnDemandUpgrade) upgradeOnDemandLater(query)
        return written.entity
    }

    /** What [write] stored, and whether the on-demand sources should get their one follow-up try. */
    private class Written(val entity: LyricsEntity?, val wantsOnDemandUpgrade: Boolean)

    private suspend fun isStamped(trackId: Long): Boolean = trackDao.observeLyricsFetchedAt(trackId).first() != null

    private suspend fun write(query: LyricsQuery, outcome: WalkOutcome): Written {
        // TTML only while a TTML source is active: a walk that started before a switch to LRC-only
        // (still asking Apple) must not write TTML back after the wipe. Read at write time.
        val ttmlActive = activeSources().any { it.id in TTML_SOURCE_IDS }
        val result = outcome.result?.let { if (!ttmlActive && it.ttml != null) it.copy(ttml = null) else it }
        val appleCleanMiss = outcome.appleCleanMiss
        if (result == null) {
            trackDao.setLyricsFetchedAt(query.trackId, 0L)
            return Written(null, wantsOnDemandUpgrade = false)
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
        // Lyrics without word timing, stored for the first time for a played/downloaded track (a
        // row already TTML-checked is left alone: no re-check of the library): the on-demand
        // sources weren't asked (an earlier source answered), so they get one follow-up try.
        val wantsOnDemandUpgrade = query.onDemand && query.trackId > 0L && ttmlActive &&
            entity.ttml == null && !entity.instrumental && previousRow?.ttmlCheckedAt == null &&
            sources.any { it.onDemandOnly }
        return Written(entity, wantsOnDemandUpgrade)
    }

    /**
     * The on-demand sources' one follow-up try for a track whose lyrics were just stored without
     * word timing (see [write]). Runs detached, so the lyrics already found show at once; at most
     * once per track per process ([onDemandTried]). A hit upgrades the row (Now Playing observes
     * it); a miss or failure changes nothing.
     */
    private fun upgradeOnDemandLater(query: LyricsQuery) {
        synchronized(walkLock) { if (!onDemandTried.add(query.trackId)) return }
        walkScope.launch {
            try {
                val result = upgradeToTtml(
                    query.trackId,
                    moveCursor = false,
                    interactive = query.interactive,
                    sources = TtmlUpgradeSources.ON_DEMAND,
                )
                Log.d(TAG, "On-demand word-sync try for trackId=${query.trackId}: $result")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "On-demand word-sync try for trackId=${query.trackId} failed: ${e.message}")
            }
        }
    }

    /** A lookup for a track with no library row (a stream). Shares the walk and its memory, see [sharedWalk]. */
    suspend fun resolveTransient(query: LyricsQuery, fresh: Boolean = false): LyricsResult? =
        sharedWalk(query, fresh).result

    /**
     * The finished answer for [query]'s song, if one is in memory: lets a screen show lyrics
     * already in hand at once, with no Loading frame. Null when the song hasn't been looked up.
     */
    fun cached(query: LyricsQuery): CachedLyrics? = synchronized(walkLock) { remembered(memoryKey(query)) }

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
        return lyricsDao.trackIdsPendingTtml().sortedDescending().sortedBy { it >= after }
    }

    /**
     * The user's "Re-check library" sweep (Settings → Playback → Lyrics): every stored row still
     * without word timing, newest first, below the track the sweep last finished. Unlike
     * [trackIdsPendingTtml] it keeps rows Apple already checked, because the on-demand sources
     * (lrc.red, Binimum) were never asked for them. Empty under LRC-only.
     */
    suspend fun recheckPlan(): RecheckPlan {
        if (lyricsPreference.sourcePreference.first() == LyricsSourcePreference.LRC_ONLY) return RecheckPlan(emptyList(), 0)
        val all = lyricsDao.trackIdsWithoutTtml()   // newest first
        val below = lyricsPreference.recheckCursor.first().takeIf { it > 0L } ?: Long.MAX_VALUE
        return RecheckPlan(remaining = all.filter { it < below }, total = all.size)
    }

    /**
     * One track of the re-check sweep. Apple is asked only if it never answered for this track (a
     * clean miss there is kept by `ttml_checked_at`); the on-demand sources are asked either way,
     * under their shared skip window. The cursor moves past the track once it was really asked: a
     * 429 or an open skip window (RATE_LIMITED / PAUSED) leaves it first in line for the next run.
     */
    suspend fun recheckTrack(trackId: Long): TtmlUpgradeResult {
        val appleChecked = lyricsDao.get(trackId)?.ttmlCheckedAt != null
        val result = upgradeToTtml(
            trackId,
            moveCursor = false,
            sources = if (appleChecked) TtmlUpgradeSources.ON_DEMAND else TtmlUpgradeSources.APPLE_THEN_ON_DEMAND,
        )
        if (result != TtmlUpgradeResult.RATE_LIMITED && result != TtmlUpgradeResult.PAUSED) {
            lyricsPreference.setRecheckCursor(trackId)
        }
        return result
    }

    /** The sweep reached the end: stamp "last checked" and start the next one from the top. */
    suspend fun finishRecheck() = lyricsPreference.finishRecheck(clock.now())

    /**
     * Downloaded tracks that have no lyrics (never tried, or an earlier all-source miss), starting
     * just after the one the last manual run reached and wrapping round, so a run cut short
     * (Cancel, app killed, the OS job cap) moves on instead of re-asking the same head of the list.
     */
    suspend fun trackIdsMissingLyrics(): List<Long> {
        val after = lyricsPreference.bulkFetchCursor.first()
        // ponytail: one resume point, not per-track state. Stable sort keeps id order in each half.
        return lyricsDao.trackIdsMissingLyrics().sortedDescending().sortedBy { it >= after }
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
            isrc = track.isrc?.takeIf { it.isNotBlank() },
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
        // Saved BEFORE the wipe: writes re-read the active sources, so one landing during the
        // (on SAF, seconds-long) wipe already drops TTML instead of putting it back.
        lyricsPreference.setSourcePreference(preference)
        if (preference == LyricsSourcePreference.LRC_ONLY) {
            val affected = lyricsDao.trackIdsWithTtml()
            affected.forEach { id -> sidecarWriter.deleteTtmlSidecar(id) }
            lyricsDao.clearAllTtml()
            affected.forEach { id -> trackDao.setLyricsFetchedAt(id, null) }
            Log.i(TAG, "Switched to LRC-only: wiped TTML for ${affected.size} track(s), queued for re-fetch")
        }
        // Answers from the old chain must not outlive it (an LRC-only user would get Apple TTML
        // for streams). Forgotten AFTER the new preference is saved and the wipe is done, so
        // nothing remembered before or during the switch survives it. Walks still
        // running finish for their askers but aren't remembered; writes drop TTML (see write).
        synchronized(walkLock) {
            generation++
            recent.clear()
            inFlight.clear()
        }
    }

    /**
     * Backfill path: asks the word-synced sources picked by [sources] (Apple only by default, as
     * the library-wide pass does) and replaces the stored lyrics + sidecar only on a hit.
     *
     * - hit          -> row upserted (sync offset carried over from [existing]), fetch stamp
     *                   refreshed, sidecar rewritten (keeps the existing .lrc, adds/refreshes .ttml
     *                   beside it) -> UPGRADED
     * - clean miss   -> `ttml_checked_at` stamped, row untouched -> NO_TTML
     * - source threw -> NOTHING written, stays pending -> FAILED (RATE_LIMITED when any asked
     *                   source answered HTTP 429, else by the first failure: PAUSED for a skip
     *                   window, NOT_READY for a timeout)
     * - [TtmlUpgradeSources.ON_DEMAND] clean miss -> nothing stamped -> NO_TTML
     * - no row / already TTML / instrumental / source not in chain -> SKIPPED
     */
    suspend fun upgradeToTtml(
        trackId: Long,
        /** False for a single-track tap: only the library-wide pass moves its own resume point. */
        moveCursor: Boolean = true,
        /** True when someone is watching: 4s timeout, no retry (see AppleTtmlLyricsSource). */
        interactive: Boolean = false,
        sources: TtmlUpgradeSources = TtmlUpgradeSources.APPLE,
    ): TtmlUpgradeResult {
        // First, before the network, so a kill mid-request still moves the next run on.
        if (moveCursor) lyricsPreference.setTtmlUpgradeCursor(trackId)
        if (lyricsPreference.sourcePreference.first() == LyricsSourcePreference.LRC_ONLY) {
            return TtmlUpgradeResult.SKIPPED
        }
        val apple = this.sources.filter { it.id == AppleTtmlLyricsSource.SOURCE_ID }
        val onDemand = this.sources.filter { it.onDemandOnly && it.id in TTML_SOURCE_IDS }
        val ttmlSources = when (sources) {
            TtmlUpgradeSources.APPLE -> apple
            TtmlUpgradeSources.APPLE_THEN_ON_DEMAND -> apple + onDemand
            TtmlUpgradeSources.ON_DEMAND -> onDemand
        }
        if (ttmlSources.isEmpty()) return TtmlUpgradeResult.SKIPPED
        val before = lyricsDao.get(trackId) ?: return TtmlUpgradeResult.SKIPPED
        if (before.ttml != null || before.instrumental) return TtmlUpgradeResult.SKIPPED
        val track = trackDao.getById(trackId) ?: return TtmlUpgradeResult.SKIPPED

        val query = LyricsQuery(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album.ifBlank { null },
            albumArtist = track.albumArtist.ifBlank { null },
            durationMs = track.durationMs.takeIf { it > 0 },
            youtubeVideoId = track.youtubeId,
            isrc = track.isrc?.takeIf { it.isNotBlank() },
            interactive = interactive,
            onDemand = sources != TtmlUpgradeSources.APPLE,
        )
        var found: LyricsResult? = null
        val failures = ArrayList<Exception>(ttmlSources.size)
        for (source in ttmlSources) {
            try {
                found = source.resolve(query)
                if (found != null) break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "TTML upgrade: ${source.id} failed for trackId=$trackId", e)
                failures += e
            }
        }
        val result = found
        if (result == null && failures.isNotEmpty()) return upgradeFailure(failures)

        // The lookup can take a while: re-read the row under the per-track write lock and write
        // from THAT, so an offset (or anything else) changed meanwhile is kept, and a concurrent
        // writer that already stored TTML wins. Not cancellable half-way, as in resolveAndStore.
        return withContext(NonCancellable) {
            writeLock(trackId).withLock {
                val current = lyricsDao.get(trackId) ?: return@withLock TtmlUpgradeResult.SKIPPED
                if (current.ttml != null || current.instrumental) return@withLock TtmlUpgradeResult.SKIPPED
                val now = clock.now()
                if (result == null) {
                    if (sources != TtmlUpgradeSources.ON_DEMAND) lyricsDao.markTtmlChecked(trackId, now)
                    return@withLock TtmlUpgradeResult.NO_TTML
                }
                val entity = LyricsEntity(
                    trackId = trackId,
                    plainText = result.plainText,
                    syncedLrc = result.syncedLrc,
                    instrumental = false,
                    language = result.language ?: current.language,
                    source = result.sourceId,
                    sourceLyricsId = result.sourceLyricsId,
                    fetchedAt = now,
                    ttml = result.ttml,
                    ttmlCheckedAt = now,
                    syncOffsetMs = current.syncOffsetMs,
                )
                lyricsDao.upsert(entity)
                trackDao.setLyricsFetchedAt(trackId, now)
                if (track.filePath != null) {
                    runCatching { sidecarWriter.write(trackId, entity) }
                        .onFailure { e -> Log.w(TAG, "Sidecar rewrite failed for trackId=$trackId", e) }
                }
                TtmlUpgradeResult.UPGRADED
            }
        }
    }

    /** Any HTTP 429 wins (stop asking); otherwise the first failure decides, as with Apple alone. */
    private fun upgradeFailure(failures: List<Exception>): TtmlUpgradeResult {
        if (failures.any { (it as? AppleTtmlLyricsSource.HttpStatusException)?.code == 429 }) {
            return TtmlUpgradeResult.RATE_LIMITED
        }
        return when (failures.first()) {
            is AppleTtmlLyricsSource.AppleSkippedException,
            is IsrcLyricsHostBreaker.SkippedException -> TtmlUpgradeResult.PAUSED
            is InterruptedIOException -> TtmlUpgradeResult.NOT_READY
            else -> TtmlUpgradeResult.FAILED
        }
    }

    /** Null when no row exists yet (never fetched) — callers treat that as "no offset set". */
    fun observeSyncOffsetMs(trackId: Long): Flow<Long?> = lyricsDao.observeSyncOffsetMs(trackId)

    suspend fun setSyncOffsetMs(trackId: Long, offsetMs: Long) = lyricsDao.setSyncOffsetMs(trackId, offsetMs)

    private suspend fun activeSources(): List<LyricsSource> =
        if (lyricsPreference.sourcePreference.first() == LyricsSourcePreference.LRC_ONLY) {
            sources.filterNot { it.id in TTML_SOURCE_IDS }
        } else {
            sources
        }

    /** The sources one walk asks: [activeSources], minus the on-demand ones unless [query] is on-demand. */
    private suspend fun walkSources(query: LyricsQuery): List<LyricsSource> =
        activeSources().let { active -> if (query.onDemand) active else active.filterNot { it.onDemandOnly } }

    /**
     * The one network walk per song (see the class KDoc). [fresh] skips the memory but still
     * joins a walk already in flight for the same song. A failed walk is not remembered, so the
     * next ask tries again.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // Deferred.getCompleted, read only once complete
    private suspend fun sharedWalk(query: LyricsQuery, fresh: Boolean): WalkOutcome {
        val key = memoryKey(query)
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
        for (source in walkSources(query)) {
            try {
                val result = source.resolve(query)
                if (result != null) return WalkOutcome(result, appleCleanMiss, degraded = firstFailure != null)
                if (source.id == AppleTtmlLyricsSource.SOURCE_ID) appleCleanMiss = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (source.onDemandOnly) {
                    // An extra, asked last: its failure is a miss, so a dead host never turns
                    // "no lyrics" into a retryable error that is re-asked on every open.
                    Log.d(TAG, "Lyrics source ${source.id} failed for trackId=${query.trackId}: ${e.message}")
                    continue
                }
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

        /** Stripes of per-track write locks. */
        private const val WRITE_STRIPES = 32

        /** How long a degraded answer (a hit after an earlier source failed) is kept. */
        private const val DEGRADED_TTL_MS = 2 * 60_000L

        /** Sources that produce TTML: all dropped when the user picks LRC-only. */
        private val TTML_SOURCE_IDS = setOf(
            AppleTtmlLyricsSource.SOURCE_ID,
            IsrcTtmlLyricsSource.LRC_RED_ID,
            IsrcTtmlLyricsSource.BINIMUM_ID,
        )

        /** Tracks remembered as having had their on-demand follow-up this process. */
        private const val ON_DEMAND_TRIED_MAX = 256

        /**
         * The in-memory key for a walk: the song, plus whether the on-demand sources were in it,
         * so a library pass's answer (asked without them) is never served to Now Playing as if
         * they had been asked.
         */
        fun memoryKey(query: LyricsQuery): String = songKey(query) + if (query.onDemand) "" else "\u0000pass"

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