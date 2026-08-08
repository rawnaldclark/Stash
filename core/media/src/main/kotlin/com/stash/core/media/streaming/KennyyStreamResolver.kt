package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.LosslessSourceHealthGate
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.kennyy.KennyySource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Result of a successful stream-URL lookup. [url] is the signed CDN URL
 * the player should fetch; [expiresAtMs] is the wall-clock instant
 * (epoch-millis, comparable to [System.currentTimeMillis]) at which the
 * signature stops being valid and the CDN starts returning 403.
 *
 * Callers cache the URL keyed by track id with this TTL — the
 * [com.stash.core.media.streaming.StreamUrlCache] (Task 4) and the mid-
 * stream refresh path in [com.stash.core.media.streaming
 * .RefreshingDataSourceFactory] (Task 6) both rely on it.
 */
data class StreamUrl(
    val url: String,
    val expiresAtMs: Long,
    /**
     * Lowercase codec tag from the Qobuz API (`"flac"`, `"mp3"`, etc.).
     * Null when the source didn't surface it.
     */
    val codec: String? = null,
    /** Bits per sample (16, 24). Null/0 for lossy codecs. */
    val bitsPerSample: Int? = null,
    /** Sample rate in Hz. Null when unknown. */
    val sampleRateHz: Int? = null,
    /** Stated bitrate in kbps. Null when unknown. */
    val bitrateKbps: Int? = null,
    /**
     * Studio cover-art URL from the upstream catalog. Used to overwrite
     * YouTube-sourced tracks' Music-Video thumbnails with proper album
     * art whenever a Qobuz match is found at stream time. Null when the
     * source didn't surface it.
     */
    val coverArtUrl: String? = null,
    /**
     * Which resolver served this URL — used by the UI to label tracks
     * that are streaming from a non-lossless fallback (e.g. YouTube)
     * so the user knows quality has degraded from the Qobuz baseline.
     *
     * Conventional values:
     *  - `"jiosaavn"`            - JioSaavn AAC-320 fallback (lossy)
     *  - `"kennyy"` / `"squid"`  — Qobuz catalog (lossless)
     *  - `"youtube"`             — yt-dlp/InnerTube extraction (lossy)
     */
    val origin: String? = null,
)

/**
 * Adapts the existing lossless-download [KennyySource] into a stream-URL
 * resolver. Given a [TrackEntity], builds the same [TrackQuery] the
 * download pipeline would, invokes Kennyy's catalog search + signed-URL
 * lookup, and pulls the `etsp` query parameter out of the resulting URL
 * to compute when the signature expires.
 *
 * `etsp` is the URL signature expiry the qobuz.kennyy.com.br proxy
 * (and qobuz.squid.wtf, same Qobuz-DL codebase) bakes into every
 * download URL. Format is a Unix epoch in *seconds* — multiply by 1000
 * to align with [System.currentTimeMillis]. Past `etsp`, the Akamai CDN
 * returns 403 and the player must re-resolve.
 *
 * Returns null when:
 *  - Kennyy has no confident match for the track (downstream pipeline
 *    should fall through to YouTube, or surface "not streamable").
 *  - The returned URL has no `etsp` parameter. Defensive: a URL we can't
 *    safely refresh is one we shouldn't cache; treat it as unresolved
 *    rather than letting the player hit a non-refreshable 403 later.
 *  - The `etsp` value isn't an integer.
 */
@Singleton
class KennyyStreamResolver @Inject constructor(
    private val source: KennyySource,
    private val healthMonitor: KennyyHealthMonitor,
    private val healthGate: LosslessSourceHealthGate,
    private val qualityPolicy: StreamQualityPolicy,
) {
    suspend fun resolve(track: TrackEntity): StreamUrl? {
        if (healthGate.isDegraded(KennyySource.SOURCE_ID)) {
            // Content-degraded (preview-sample / lossy downgrade) within the
            // cooldown — skip so the streaming registry fails over.
            Log.d(TAG, "skip id=${track.id} (kennyy content-degraded)")
            return null
        }
        if (!healthMonitor.isHealthy.value) {
            Log.d(TAG, "skip id=${track.id} (kennyy unhealthy)")
            return null
        }
        Log.d(TAG, "resolve attempt id=${track.id} title='${track.title}'")
        val query = TrackQuery(
            artist = track.artist,
            title = track.title,
            album = track.album.takeIf { it.isNotBlank() },
            isrc = track.isrc?.takeIf { it.isNotBlank() },
            durationMs = track.durationMs,
        )
        // Use the immediate (rate-limiter-bypassing) path: streaming-tap
        // is user-initiated and must not queue behind background
        // AvailabilityCheckWorker batches that hold the limiter at 1
        // req/s. See KennyySource.resolveImmediate KDoc for rationale.
        val requestedQuality = qualityPolicy.streamingTier().qobuzCode
        val result = try {
            withTimeout(STREAM_RESOLVE_TIMEOUT_MS) { source.resolveImmediate(query, requestedQuality) }
        } catch (e: TimeoutCancellationException) {
            healthMonitor.recordFailure()
            Log.w(TAG, "timeout id=${track.id} after ${STREAM_RESOLVE_TIMEOUT_MS}ms")
            return null
        }
        if (result == null) {
            if (source.lastResolveFailedNetwork) {
                healthMonitor.recordFailure()
                Log.d(TAG, "no_result id=${track.id} (network failure)")
            } else {
                healthMonitor.recordNoMatch()
                Log.d(TAG, "no_result id=${track.id} (no match)")
            }
            return null
        }
        val etspMs = parseEtspMs(result.downloadUrl)
        if (etspMs == null) {
            // Treat unparseable URL as a proxy-side anomaly worth signaling.
            healthMonitor.recordFailure()
            Log.w(TAG, "no_etsp id=${track.id}")
            return null
        }
        healthMonitor.recordSuccess()
        Log.d(
            TAG,
            "resolved id=${track.id} origin=$ORIGIN expiresInSec=${(etspMs - System.currentTimeMillis()) / 1000}",
        )
        return StreamUrl(
            url = result.downloadUrl,
            expiresAtMs = etspMs,
            codec = result.format.codec.takeIf { it.isNotBlank() },
            bitsPerSample = result.format.bitsPerSample.takeIf { it > 0 },
            sampleRateHz = result.format.sampleRateHz.takeIf { it > 0 },
            bitrateKbps = result.format.bitrateKbps.takeIf { it > 0 },
            coverArtUrl = result.coverArtUrl?.takeIf { it.isNotBlank() },
            origin = ORIGIN,
        )
    }

    private fun parseEtspMs(url: String): Long? {
        val match = ETSP_REGEX.find(url) ?: return null
        val secs = match.groupValues[1].toLongOrNull() ?: return null
        return secs * 1000L
    }

    private companion object {
        const val TAG = "KennyyStreamResolver"
        const val ORIGIN = "kennyy"
        const val STREAM_RESOLVE_TIMEOUT_MS = 3_000L
        val ETSP_REGEX = Regex("""[?&]etsp=(\d+)""")
    }
}
