package com.stash.data.lyrics

import android.util.Log
import com.stash.core.common.Clock
import com.stash.core.data.db.dao.LyricsDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.data.lyrics.sidecar.LyricsSidecarWriter
import com.stash.data.lyrics.source.LyricsQuery
import com.stash.data.lyrics.source.LyricsResult
import com.stash.data.lyrics.source.LyricsSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sole entrypoint for the lyrics subsystem. Both UI (Now Playing sheet)
 * and workers (post-download + backfill) go through this class.
 *
 * `resolveAndStore` walks the [sources] chain in priority order
 * (LRCLIB -> KuGou -> InnerTube; ordering enforced in `LyricsModule.provideLyricsSources`),
 * persists the first non-null result to the `lyrics` table, stamps
 * `tracks.lyrics_fetched_at` with the success epoch-millis, and
 * triggers a sidecar `.lrc` write for the non-instrumental case.
 *
 * Sentinel rules (see `LyricsDao` docs):
 * - Successful fetch -> upsert lyrics row + stamp `lyrics_fetched_at = clock.now()`.
 * - Definitive miss across all sources -> no row + stamp `lyrics_fetched_at = 0L`.
 *   The 0L sentinel keeps the backfill worker's `WHERE lyrics_fetched_at IS NULL`
 *   predicate honest so it terminates.
 * - Source FAILURE (network/HTTP/parse — the source threw) with no hit from a
 *   later source -> THROWS and leaves the stamp untouched, so the track stays
 *   retryable (worker backoff, next sheet open). Failures must never write the
 *   0L sentinel: conflating them permanently miss-stamped ~72% of the library's
 *   "No lyrics found" tracks (2026-07-05 forensics).
 *
 * Sidecar-write failure is logged but does NOT unwind the Room state.
 * The Room row + stamp are the source of truth; the sidecar is a best-effort
 * courtesy for external players.
 */
@Singleton
class LyricsRepository @Inject constructor(
    private val sources: List<@JvmSuppressWildcards LyricsSource>,
    private val lyricsDao: LyricsDao,
    private val trackDao: TrackDao,
    private val sidecarWriter: LyricsSidecarWriter,
    private val clock: Clock,
) {

    /** Observe the lyrics row for [trackId]. Emits null when no row exists yet. */
    fun observe(trackId: Long): Flow<LyricsEntity?> = lyricsDao.observe(trackId)

    /**
     * Observe the parent track's `lyrics_fetched_at` stamp. The sheet pairs this
     * with [observe] so it reacts the moment a fetch finishes — the stamp is what
     * distinguishes "never tried" (null → Loading) from "tried and missed"
     * (0L → None), and watching it live is what stops the sheet sticking on
     * Loading until a close+reopen re-queries the track.
     */
    fun observeFetchedAt(trackId: Long): Flow<Long?> = trackDao.observeLyricsFetchedAt(trackId)

    /** One-shot read of the lyrics row for [trackId], or null when absent. */
    suspend fun get(trackId: Long): LyricsEntity? = lyricsDao.get(trackId)

    /**
     * Walks [sources] in order, returns the first non-null
     * [com.stash.data.lyrics.source.LyricsResult], persists it to Room,
     * stamps `tracks.lyrics_fetched_at`, and (for non-instrumental hits)
     * triggers a sidecar `.lrc` write.
     *
     * On a definitive all-source miss, stamps `tracks.lyrics_fetched_at = 0L`
     * and returns null without writing a row. If any source FAILED and no
     * later source hit, rethrows that failure without stamping — the caller
     * decides retry policy (worker backoff / sheet Error state).
     */
    suspend fun resolveAndStore(query: LyricsQuery): LyricsEntity? {
        val result = walkSources(query)
        if (result == null) {
            trackDao.setLyricsFetchedAt(query.trackId, 0L)
            return null
        }
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
        )
        lyricsDao.upsert(entity)
        trackDao.setLyricsFetchedAt(query.trackId, now)
        if (!result.instrumental) {
            runCatching { sidecarWriter.write(query.trackId, entity) }
                .onFailure { e ->
                    // Non-fatal: Room row + stamp are the source of truth.
                    Log.w(TAG, "Sidecar write failed for trackId=${query.trackId}", e)
                }
        }
        return entity
    }

    /**
     * Transient source-chain walk for tracks that have no persistent
     * `tracks` row to key against — typically streaming-mode playback
     * where the audio is fetched by URL and never written to the
     * library. Returns the first non-null [LyricsResult] without
     * touching Room or the sidecar writer; caller renders the result
     * directly into its own state.
     *
     * Re-opening the sheet on the same streaming track re-runs the
     * source chain (no cache). LRCLIB is fast (~200ms typical) so the
     * UX cost is acceptable, and avoiding Room means we don't have to
     * invent a fake parent row for the FK CASCADE.
     *
     * Same failure contract as [resolveAndStore]: null = definitive miss,
     * throws when a source failed and nothing hit.
     */
    suspend fun resolveTransient(query: LyricsQuery): LyricsResult? = walkSources(query)

    /**
     * Clears `tracks.lyrics_fetched_at` back to NULL (never tried). The
     * Retry path calls this before re-resolving so the sheet's stamp
     * observer flips to Loading immediately — the visible feedback that
     * was missing when Retry left the 0L sentinel in place.
     */
    suspend fun clearFetchStamp(trackId: Long) = trackDao.setLyricsFetchedAt(trackId, null)

    /**
     * Priority-ordered source walk distinguishing miss from failure: a
     * throwing source is logged and the walk continues (LRCLIB down must
     * not block the InnerTube fallback), but if nothing hits and at least
     * one source failed, the first failure is rethrown — "no lyrics" may
     * only be concluded from sources that actually answered.
     */
    private suspend fun walkSources(query: LyricsQuery): LyricsResult? {
        var firstFailure: Exception? = null
        for (source in sources) {
            try {
                source.resolve(query)?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Lyrics source ${source.id} failed for trackId=${query.trackId}", e)
                if (firstFailure == null) firstFailure = e
            }
        }
        firstFailure?.let { throw it }
        return null
    }

    private companion object {
        private const val TAG = "LyricsRepository"
    }
}
