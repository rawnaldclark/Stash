package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.forLibrarySource
import com.stash.data.download.lossless.qbdlx.QbdlxQobuzSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stream-URL resolver backed by the DIRECT Qobuz API via [QbdlxQobuzSource]
 * (`qbdlx` — tokenless catalog under the web app_id; file URL via the user's
 * account, a custom endpoint, or the configured relays).
 * Counterpart to [QobuzStreamResolver]: same shape, same `etsp` expiry
 * parsing, different upstream operator.
 *
 * Delegates entirely to [QbdlxQobuzSource.resolveImmediate] — search/match
 * live in the source; this only maps [TrackEntity] →
 * [TrackQuery], requests the policy tier, and parses the CDN URL's `etsp`.
 *
 * No [com.stash.data.download.lossless.LosslessSourceHealthGate] gate here
 * (unlike [QobuzStreamResolver]): the proxy sources mark themselves degraded
 * when the proxy returns a preview-sample/lossy URL, but qbdlx hits the real
 * Qobuz API and classifies dead-token / region-lock in the client
 * (`QbdlxResolveResult`), never feeding the health gate — so an `isDegraded`
 * check would be a branch that can never fire.
 * ponytail: gate omitted because qbdlx never records degradation; nothing to gate on.
 *
 * Returns null when:
 *  - qbdlx is not currently enabled for streaming (no connected account,
 *    custom endpoint or relay — see [QbdlxQobuzSource.isEnabledForStreaming]).
 *  - qbdlx has no confident match for the track.
 *  - The returned URL has no `etsp` parameter (un-refreshable URLs aren't
 *    safe to cache — see [KennyyStreamResolver] KDoc).
 */
@Singleton
class QbdlxStreamResolver @Inject constructor(
    private val source: QbdlxQobuzSource,
    private val qualityPolicy: StreamQualityPolicy,
) {
    suspend fun resolve(track: TrackEntity): StreamUrl? {
        Log.d(TAG, "resolve attempt id=${track.id} title='${track.title}'")
        if (!source.isEnabledForStreaming()) {
            Log.d(TAG, "disabled id=${track.id} (no lossless path configured)")
            return null
        }

        val query = TrackQuery(
            artist = track.artist,
            title = track.title,
            album = track.album.takeIf { it.isNotBlank() },
            isrc = track.isrc?.takeIf { it.isNotBlank() },
            durationMs = track.durationMs,
        ).forLibrarySource(track.source)
        val requestedQuality = qualityPolicy.streamingTier().qobuzCode
        val result = source.resolveImmediate(query, requestedQuality) ?: run {
            Log.d(TAG, "no_result id=${track.id}")
            return null
        }
        val etspMs = parseEtspMs(result.downloadUrl) ?: run {
            Log.w(TAG, "no_etsp id=${track.id}")
            return null
        }
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
        const val TAG = "QbdlxStreamResolver"
        const val ORIGIN = "qbdlx"
        val ETSP_REGEX = Regex("""[?&]etsp=(\d+)""")
    }
}
