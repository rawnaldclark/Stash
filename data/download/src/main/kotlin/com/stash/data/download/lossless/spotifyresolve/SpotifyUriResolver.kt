package com.stash.data.download.lossless.spotifyresolve

import android.util.Log
import com.stash.core.data.db.dao.SpotifyResolutionDao
import com.stash.core.data.db.entity.SpotifyResolutionEntity
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.matching.ArtistMatching
import com.stash.data.download.matching.SpotifySearchScorer
import com.stash.data.spotify.SpotifyLibrary
import com.stash.data.spotify.SpotifyRateLimitException
import com.stash.data.spotify.SpotifyTrackCandidate
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cache-first orchestrator that resolves a non-Spotify (YouTube-pathway) track
 * to a Spotify track URL, so it can subsequently be routed to the antra
 * lossless source (which only accepts an `open.spotify.com/track/<id>` URL).
 *
 * The flow per [resolveUrl] call:
 *  1. Guard: a track with unknown/zero duration can never be scored safely —
 *     return null with NO search and NO cache write.
 *  2. In-flight coalescing: two concurrent resolves of the same uncached
 *     trackId share ONE search (mirrors `AntraStreamResolver`'s
 *     `Mutex`-guarded `HashMap<Long, CompletableDeferred>` owner/joiner).
 *  3. Cache read of the [SpotifyResolutionDao] side-table:
 *     - MATCHED → return the URL form of the stored `spotify:track:<id>` URI.
 *     - NO_MATCH / TRANSIENT still within their TTL → return null (no API call).
 *     - no row, or an expired row → proceed to search.
 *  4. Search Spotify, score with [SpotifySearchScorer], and cache the outcome:
 *     - accepted → MATCHED (permanent).
 *     - empty / no passer → NO_MATCH (30-day TTL).
 *     - 429 / IO / other error → TRANSIENT (backoff TTL, attempts++), with a
 *       promotion to NO_MATCH once attempts exceed [MAX_TRANSIENT_ATTEMPTS].
 *
 * Correctness intent: NEVER poison the cache with a permanent NO_MATCH on a
 * transient failure (rate limit / network blip) — those are TRANSIENT so the
 * track gets retried once the backoff window elapses.
 */
@Singleton
class SpotifyUriResolver @Inject constructor(
    private val spotify: SpotifyLibrary,
    private val scorer: SpotifySearchScorer,
    private val dao: SpotifyResolutionDao,
) {

    /** Injectable clock; overridden in tests. Defaults to the real wall clock. */
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    /**
     * In-flight resolves keyed by trackId. The first caller for a trackId owns
     * the search/score/cache work and shares its result through the deferred;
     * concurrent callers join (`await`) instead of issuing a second search.
     * Guarded by [inFlightMutex] for atomic get-or-create.
     */
    private val inFlight = HashMap<Long, CompletableDeferred<String?>>()
    private val inFlightMutex = Mutex()

    /**
     * Resolves [track] (a YouTube-pathway track identified by [trackId]) to a
     * `https://open.spotify.com/track/<id>` URL on a confident match, else
     * null. Cache-first: a cached outcome (within TTL) is returned without a
     * network call.
     */
    suspend fun resolveUrl(trackId: Long, track: TrackQuery): String? {
        // (1) Unknown-duration guard FIRST: the scorer cannot accept anything
        // without a duration, so short-circuit before any search or cache write.
        val durationMs = track.durationMs
        if (durationMs == null || durationMs <= 0) return null

        // (2) In-flight coalescing — atomic get-or-create.
        val (shared, isOwner) = inFlightMutex.withLock {
            inFlight[trackId]?.let { it to false }
                ?: CompletableDeferred<String?>().also { inFlight[trackId] = it } to true
        }
        if (!isOwner) {
            Log.d(TAG, "join in-flight resolve trackId=$trackId")
            return shared.await()
        }

        return try {
            val result = resolveUncached(trackId, track)
            shared.complete(result)
            result
        } catch (e: Throwable) {
            // Propagate to joiners as a clean miss; the owner still rethrows.
            shared.complete(null)
            throw e
        } finally {
            inFlightMutex.withLock { inFlight.remove(trackId) }
        }
    }

    /** Owner-only: the cache-read → search → score → cache-write flow. */
    private suspend fun resolveUncached(trackId: Long, track: TrackQuery): String? {
        // (3) Cache read.
        val existing = dao.get(trackId)
        if (existing != null) {
            when (existing.status) {
                STATUS_MATCHED -> existing.spotifyUri?.let { uri ->
                    spotifyUriToUrl(uri)?.let { return it }
                }
                STATUS_NO_MATCH, STATUS_TRANSIENT ->
                    if (nowMs() < existing.expiresAtMs) return null
                // Unknown status / expired row falls through to a fresh search.
            }
        }
        val prevAttempts = if (existing?.status == STATUS_TRANSIENT) existing.attempts else 0

        // (4) Search + score. Use the web-player `searchDesktop` GraphQL
        // operation (Partner API) — the public /v1/search REST endpoint
        // hard-rate-limits both client_credentials and sp_dc tokens (24h/short
        // 429s, confirmed on-device 2026-06-09). searchTracksGraphQL returns []
        // on any failure (incl. a stale persisted-query hash) rather than
        // throwing, so an empty list is treated as a transient miss.
        val candidates: List<SpotifyTrackCandidate> = try {
            spotify.searchTracksGraphQL(buildQuery(track))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SpotifyRateLimitException) {
            val backoffMs = max(MIN_TRANSIENT_TTL_MS, (e.retryAfterSeconds ?: 0L) * 1000)
            cacheTransient(trackId, prevAttempts, backoffMs)
            return null
        } catch (e: IOException) {
            cacheTransient(trackId, prevAttempts, MIN_TRANSIENT_TTL_MS)
            return null
        } catch (e: Exception) {
            // anything else non-cancellation: transient.
            cacheTransient(trackId, prevAttempts, MIN_TRANSIENT_TTL_MS)
            return null
        }
        // Empty candidates from GraphQL = either genuinely no match OR a
        // stale persisted-query hash / token blip. Treat as TRANSIENT (short
        // backoff) NOT a 30-day NO_MATCH, so a hash refresh self-heals fast.
        if (candidates.isEmpty()) {
            cacheTransient(trackId, prevAttempts, MIN_TRANSIENT_TTL_MS)
            return null
        }

        val decision = scorer.pick(track, candidates)
        // One-line outcome log (decision + reason) stays for field debugging;
        // the per-candidate TEMP-DIAG dump from the tuning session is gone.
        Log.d(
            TAG,
            "score '${track.title}' / '${track.artist}' dur=${track.durationMs}ms " +
                "-> ${decision.reason}" +
                (decision.accepted?.let { " id=${it.id}" } ?: ""),
        )
        val accepted = decision.accepted
        return if (accepted != null) {
            cacheMatched(trackId, track, accepted)
            spotifyUriToUrl("spotify:track:${accepted.id}")
        } else {
            cacheNoMatch(trackId)
            null
        }
    }

    private suspend fun cacheMatched(
        trackId: Long,
        track: TrackQuery,
        cand: SpotifyTrackCandidate,
    ) {
        val now = nowMs()
        val durDelta = track.durationMs?.let { abs(it - cand.durationMs) / 1000 }?.toInt()
        dao.upsert(
            SpotifyResolutionEntity(
                trackId = trackId,
                status = STATUS_MATCHED,
                spotifyUri = "spotify:track:${cand.id}",
                matchedIsrc = cand.isrc,
                titleSim = null,
                durDeltaSec = durDelta,
                resolvedAtMs = now,
                expiresAtMs = Long.MAX_VALUE,
                attempts = 1,
            ),
        )
    }

    private suspend fun cacheNoMatch(trackId: Long) {
        val now = nowMs()
        dao.upsert(
            SpotifyResolutionEntity(
                trackId = trackId,
                status = STATUS_NO_MATCH,
                spotifyUri = null,
                matchedIsrc = null,
                titleSim = null,
                durDeltaSec = null,
                resolvedAtMs = now,
                expiresAtMs = now + NO_MATCH_TTL_MS,
                attempts = 1,
            ),
        )
    }

    /**
     * Records a transient failure: TRANSIENT with an incremented attempt count
     * and a backoff TTL — UNLESS the new attempt count exceeds
     * [MAX_TRANSIENT_ATTEMPTS], in which case we give up and promote to a
     * 30-day NO_MATCH so the track stops re-hammering the API.
     */
    private suspend fun cacheTransient(trackId: Long, prevAttempts: Int, backoffMs: Long) {
        val now = nowMs()
        val attempts = prevAttempts + 1
        val entity = if (attempts > MAX_TRANSIENT_ATTEMPTS) {
            SpotifyResolutionEntity(
                trackId = trackId,
                status = STATUS_NO_MATCH,
                spotifyUri = null,
                matchedIsrc = null,
                titleSim = null,
                durDeltaSec = null,
                resolvedAtMs = now,
                expiresAtMs = now + NO_MATCH_TTL_MS,
                attempts = attempts,
            )
        } else {
            SpotifyResolutionEntity(
                trackId = trackId,
                status = STATUS_TRANSIENT,
                spotifyUri = null,
                matchedIsrc = null,
                titleSim = null,
                durDeltaSec = null,
                resolvedAtMs = now,
                expiresAtMs = now + backoffMs,
                attempts = attempts,
            )
        }
        dao.upsert(entity)
    }

    /**
     * Builds the searchDesktop `searchTerm` as PLAIN TEXT ("title artist").
     * searchDesktop is the web-player's free-text search — it does NOT accept
     * the `/v1/search` field-filter syntax (`track:"…" artist:"…"`); passing
     * filters makes it parse the whole thing as a literal title and return 0
     * results. We use the primary artist only (sending the full multi-artist
     * credit surfaces the featured artists' popular tracks instead).
     */
    private fun buildQuery(track: TrackQuery): String {
        // Readable primary artist (split on the separator, keep original text —
        // NOT ArtistMatching.artistParts, which strips to alphanumeric for
        // COMPARISON: "Steely Dan" -> "steelydan").
        val primary = track.artist
            .split(ArtistMatching.ARTIST_PART_SEPARATOR)
            .firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: track.artist.trim()
        return "${track.title} $primary".trim()
    }

    /** "spotify:track:<id>" → "https://open.spotify.com/track/<id>"; null otherwise. */
    private fun spotifyUriToUrl(uri: String): String? {
        val id = uri.removePrefix("spotify:track:").takeIf { it != uri && it.isNotBlank() }
            ?: return null
        return "https://open.spotify.com/track/$id"
    }

    private companion object {
        const val TAG = "SpotifyUriResolver"

        const val STATUS_MATCHED = "MATCHED"
        const val STATUS_NO_MATCH = "NO_MATCH"
        const val STATUS_TRANSIENT = "TRANSIENT"

        /** NO_MATCH is cached for 30 days before a fresh search is allowed. */
        const val NO_MATCH_TTL_MS = 30L * 24 * 60 * 60 * 1000

        /** Floor for a transient backoff window (also the IO/other default). */
        const val MIN_TRANSIENT_TTL_MS = 15L * 60 * 1000

        /** After this many transient attempts we promote to a NO_MATCH. */
        const val MAX_TRANSIENT_ATTEMPTS = 5
    }
}
