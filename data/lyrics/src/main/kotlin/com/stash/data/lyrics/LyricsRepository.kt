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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of [LyricsRepository.upgradeToTtml]. Only UPGRADED changes stored lyrics. */
enum class TtmlUpgradeResult { UPGRADED, NO_TTML, FAILED, RATE_LIMITED, SKIPPED }

/** Outcome of [LyricsRepository.fetchLyricsNow] (manual "fetch lyrics" run). */
enum class ManualFetchResult { FETCHED, NOT_FOUND, FAILED, SKIPPED }

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
     */
    suspend fun resolveAndStore(query: LyricsQuery): LyricsEntity? {
        val (result, appleCleanMiss) = walkSourcesNotingApple(query)
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
                .onFailure { e -> Log.w(TAG, "Sidecar write failed for trackId=${query.trackId}", e) }
        }
        return entity
    }

    suspend fun resolveTransient(query: LyricsQuery): LyricsResult? = walkSources(query)

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
            if (resolveAndStore(query) != null) ManualFetchResult.FETCHED else ManualFetchResult.NOT_FOUND
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

    private suspend fun walkSources(query: LyricsQuery): LyricsResult? = walkSourcesNotingApple(query).first

    /** [walkSources], plus whether the Apple source was asked and cleanly returned nothing. */
    private suspend fun walkSourcesNotingApple(query: LyricsQuery): Pair<LyricsResult?, Boolean> {
        var firstFailure: Exception? = null
        var appleCleanMiss = false
        for (source in activeSources()) {
            try {
                val result = source.resolve(query)
                if (result != null) return result to appleCleanMiss
                if (source.id == AppleTtmlLyricsSource.SOURCE_ID) appleCleanMiss = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Lyrics source ${source.id} failed for trackId=${query.trackId}", e)
                if (firstFailure == null) firstFailure = e
            }
        }
        firstFailure?.let { throw it }
        return null to appleCleanMiss
    }

    private companion object {
        private const val TAG = "LyricsRepository"
    }
}