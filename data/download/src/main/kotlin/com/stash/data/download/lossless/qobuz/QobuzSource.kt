package com.stash.data.download.lossless.qobuz

import android.util.Log
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * [LosslessSource] backed by the Qobuz catalog via the public squid.wtf
 * proxy (`qobuz.squid.wtf/api`). Searches for the requested track,
 * scores candidates by ISRC / title / artist / duration agreement,
 * and resolves the best match to a signed FLAC download URL.
 *
 * No user credentials required — squid.wtf's operator runs the upstream
 * Qobuz subscription on everyone's behalf. Because of that, this source
 * relies hard on [AggregatorRateLimiter] to keep Stash from accidentally
 * DDoSing what is, structurally, one paid Qobuz account serving an
 * unbounded user base. Conservative defaults are deliberate.
 *
 * Quality default is [QobuzQuality.FLAC_HIRES_192] — squid.wtf passes
 * "highest available <= requested" through from upstream Qobuz, so
 * asking for 24/192 always gets the best the source has. Quality
 * preferences (and a per-track region override via `tokenCountry`)
 * can be threaded through later via a settings layer.
 */
@Singleton
class QobuzSource @Inject constructor(
    private val apiClient: QobuzApiClient,
    private val rateLimiter: AggregatorRateLimiter,
) : LosslessSource {

    override val id: String = SOURCE_ID

    override val displayName: String = "Qobuz (via squid.wtf)"

    override suspend fun isEnabled(): Boolean {
        // No credentials gate any more — the only reason to skip this
        // source is the circuit breaker. Rate-limit *back-pressure*
        // (no tokens right now) is not a reason to flip enabled to
        // false; acquire() handles that with a brief wait.
        return !rateLimiter.stateOf(id).isCircuitBroken
    }

    override suspend fun resolve(query: TrackQuery): SourceResult? {
        // 1. Search squid.wtf for candidates. ISRC is Qobuz's best
        // index key — when we have one, send it as the query directly.
        val searchTerm = query.isrc ?: "${query.artist} ${query.title}"
        val searchData = callLimited { apiClient.search(searchTerm) }
            ?: return null

        val candidates = searchData.tracks?.items.orEmpty()
        if (candidates.isEmpty()) return null

        // 2. Score and pick the best candidate that crosses the
        // confidence threshold.
        val scored = candidates.map { it to confidence(query, it) }
        val best = scored
            .filter { it.second >= MIN_CONFIDENCE }
            .maxByOrNull { it.second }

        if (best == null) {
            // Log the top 3 rejected candidates with scores — without
            // this it's impossible to see *why* a search returned
            // results but no match crossed the threshold (the
            // common case for multi-artist tracks where Spotify's
            // expanded artist name doesn't jaccard-match Qobuz's
            // canonical short form).
            val top = scored.sortedByDescending { it.second }.take(3)
            Log.d(
                TAG,
                "no candidate above threshold ($MIN_CONFIDENCE) for '${query.artist} - ${query.title}': " +
                    top.joinToString(", ") { (c, s) ->
                        "[${"%.2f".format(s)} '${c.title}' by '${c.performer?.name}']"
                    },
            )
            return null
        }

        // 3. Resolve to a signed download URL. squid.wtf returns 403
        // when the track is non-streamable in the deployment's region;
        // callLimited swallows the exception and returns null so we
        // fall through to the next source cleanly.
        val requestedQuality = QobuzQuality.FLAC_HIRES_192
        val download = callLimited {
            apiClient.getFileUrl(best.first.id, requestedQuality)
        } ?: return null

        if (download.url.isNullOrEmpty()) {
            Log.d(TAG, "download-music returned empty url for ${best.first.id}")
            return null
        }

        return SourceResult(
            sourceId = id,
            downloadUrl = download.url,
            // squid.wtf's CDN URLs are pre-signed query strings — no
            // extra headers needed for the actual file fetch.
            downloadHeaders = emptyMap(),
            format = AudioFormat(
                // squid.wtf strips the upstream `mime_type`; map from
                // the requested format_id since Qobuz returns the
                // matching codec for each.
                codec = if (requestedQuality == QobuzQuality.MP3_320) "mp3" else "flac",
                // Bitrate left at 0 — FLAC is variable; the
                // canonical value comes from AudioDurationExtractor
                // after the file's on disk.
                bitrateKbps = 0,
                sampleRateHz = (best.first.maximumSamplingRate * 1000f).toInt(),
                bitsPerSample = best.first.maximumBitDepth,
            ),
            confidence = best.second,
            sourceTrackId = best.first.id.toString(),
        )
    }

    override suspend fun rateLimitState(): RateLimitState = rateLimiter.stateOf(id)

    // ── Internals ───────────────────────────────────────────────────────

    /**
     * Wraps an API call with rate-limiter bookkeeping. Returns null on
     * any failure mode (rate-limit denial, exception, circuit-break)
     * so callers can simply `?: return null` to skip cleanly.
     */
    private suspend fun <T> callLimited(block: suspend () -> T): T? {
        if (!rateLimiter.acquire(id)) return null
        return try {
            val result = block()
            rateLimiter.reportSuccess(id)
            result
        } catch (e: QobuzApiException) {
            if (e.status == 429) rateLimiter.reportRateLimited(id)
            else rateLimiter.reportFailure(id)
            Log.w(TAG, "squid.wtf API call failed: $e")
            null
        } catch (e: Exception) {
            rateLimiter.reportFailure(id)
            Log.w(TAG, "squid.wtf call threw: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Confidence score on [0.0, 1.0]. ISRC equality short-circuits to
     * 0.95 — same recording, same master, by definition. Otherwise
     * combines token-overlap on title and artist with a duration
     * penalty that downweights mismatched cuts (live, extended,
     * edits) without hard-rejecting them.
     */
    private fun confidence(query: TrackQuery, candidate: QobuzTrack): Float {
        if (!candidate.streamable) return 0f

        // ISRC match → highest possible non-1.0 score.
        val queryIsrc = query.isrc?.takeIf { it.isNotBlank() }
        val candidateIsrc = candidate.isrc?.takeIf { it.isNotBlank() }
        if (queryIsrc != null && candidateIsrc != null &&
            queryIsrc.equals(candidateIsrc, ignoreCase = true)
        ) {
            return 0.95f
        }

        val titleSim = jaccard(normalize(query.title), normalize(candidate.title))
        // Spotify often expands artist names with collaborators/eras
        // ("Diana Ross and the Supremes", "Joey Bada$$ feat. Jay
        // Electronica") while Qobuz indexes the canonical short form
        // ("The Supremes", "Joey Bada$$"). Plain jaccard penalises
        // these subset matches harshly — so we take the higher of
        // jaccard and subset-coverage. Subset coverage is gated on a
        // minimum smaller-set size to avoid spurious 1-word matches
        // (e.g. an artist named "Love" matching every track with
        // "Love" in the artist string).
        val artistSim = artistSimilarity(
            normalize(query.artist),
            normalize(candidate.performer?.name.orEmpty()),
        )

        // Duration similarity. Skip the penalty when query duration is
        // unknown (Stash sometimes lacks duration for stub tracks).
        val durationFactor: Float = run {
            val queryMs = query.durationMs ?: return@run 1.0f
            if (queryMs <= 0 || candidate.duration <= 0) return@run 1.0f
            val candidateMs = candidate.duration * 1000L
            val drift = abs(queryMs - candidateMs).toDouble() / queryMs.toDouble()
            when {
                drift < 0.05 -> 1.0f      // <5% off — same recording almost certainly
                drift < 0.10 -> 0.85f     // 5-10% — typical compression-vs-original variance
                drift < 0.20 -> 0.6f      // 10-20% — possibly different cut
                else -> 0.3f              // dramatic mismatch (live vs studio etc.)
            }
        }

        return (titleSim * artistSim * durationFactor)
    }

    companion object {
        /** Per LosslessSource KDoc convention: `squid_<catalog>`. */
        const val SOURCE_ID = "squid_qobuz"
        private const val TAG = "QobuzSource"

        /** Threshold below which a candidate is rejected outright. */
        private const val MIN_CONFIDENCE = 0.5f

        // ── Pure-function helpers (kept package-internal for testing) ─

        /**
         * Lowercase + strip parenthetical content, "feat./featuring"
         * suffixes, and non-alphanumeric characters; collapse whitespace.
         * Keeps Unicode letters/digits so non-Latin titles still tokenize
         * sensibly.
         */
        internal fun normalize(s: String): String =
            s.lowercase()
                .replace(Regex("\\([^)]*\\)"), " ")
                .replace(Regex("\\[[^]]*\\]"), " ")
                .replace(Regex("(?i)\\b(feat\\.?|ft\\.?|featuring)\\b.*"), " ")
                // Elide within-word marks (straight + curly apostrophes)
                // BEFORE the punctuation-to-space pass, otherwise
                // contractions like "don't" tokenize as "don t" instead
                // of "dont" and pollute the Jaccard set.
                .replace(Regex("[''`]"), "")
                .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        /** Jaccard similarity on whitespace-tokenized strings. */
        internal fun jaccard(a: String, b: String): Float {
            val setA = a.split(" ").filter { it.isNotEmpty() }.toSet()
            val setB = b.split(" ").filter { it.isNotEmpty() }.toSet()
            if (setA.isEmpty() || setB.isEmpty()) return 0f
            val intersection = setA.intersect(setB).size.toFloat()
            val union = setA.union(setB).size.toFloat()
            return intersection / union
        }

        /**
         * Artist-aware similarity: max of plain jaccard and
         * subset-coverage. Returns 1.0 when the smaller artist string
         * is fully contained in the larger AND at least one shared
         * token is "distinctive" (length > 3). This is the common
         * Spotify-expansion vs Qobuz-canonical pattern:
         *
         *  - Spotify: "Diana Ross and the Supremes" → Qobuz: "The Supremes"
         *  - Spotify: "Joey Bada$$ feat. Jay Electronica" → Qobuz: "Joey Bada$$"
         *  - Spotify: "Ghostemane, Shakewell, Pouya" → Qobuz: "Ghostemane"
         *
         * Includes the single-canonical-artist case (Qobuz indexes
         * one lead artist where Spotify expands to a featuring list).
         * "Distinctive" gating guards against generic 1-3 char tokens
         * ("Air", "U2") spuriously matching unrelated acts that
         * happen to share that token.
         *
         * Final fallback is plain jaccard, so unrelated artists with
         * partial token overlap still score reasonably.
         */
        internal fun artistSimilarity(a: String, b: String): Float {
            val setA = a.split(" ").filter { it.isNotEmpty() }.toSet()
            val setB = b.split(" ").filter { it.isNotEmpty() }.toSet()
            if (setA.isEmpty() || setB.isEmpty()) return 0f

            val intersection = setA.intersect(setB)
            val union = setA.union(setB)
            val jaccardScore = intersection.size.toFloat() / union.size.toFloat()

            val smallerSize = minOf(setA.size, setB.size)
            val smallerFullyCovered = intersection.size == smallerSize
            val hasDistinctiveOverlap = intersection.any { it.length > 3 }

            val coverageScore = if (smallerFullyCovered && hasDistinctiveOverlap) {
                1.0f
            } else {
                0f
            }

            return maxOf(jaccardScore, coverageScore)
        }
    }
}
