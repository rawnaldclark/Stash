package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.LosslessSourceHealthGate
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.forLibrarySource
import com.stash.data.download.lossless.qobuz.QobuzSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stream-URL resolver backed by `qobuz.squid.wtf` via [QobuzSource].
 * Counterpart to [KennyyStreamResolver] — same shape, same etsp expiry
 * parsing, different upstream operator.
 *
 * Squid serves the same Qobuz catalog as Kennyy but requires a fresh
 * `captcha_verified_at` cookie (~30 minute sliding window) that the
 * user pastes via Settings. Without a fresh cookie, [QobuzSource.isEnabledForStreaming]
 * returns false and this resolver returns null without firing an HTTP
 * call — the streaming registry falls through to the next operator
 * cleanly.
 *
 * Returns null when:
 *  - Squid is not currently enabled for streaming (no cookie, or last
 *    cookie was rejected and the user hasn't pasted a new one).
 *  - Squid has no confident match for the track.
 *  - The returned URL has no `etsp` parameter (un-refreshable URLs
 *    aren't safe to cache — see [KennyyStreamResolver] KDoc).
 */
@Singleton
class QobuzStreamResolver @Inject constructor(
    private val source: QobuzSource,
    private val healthGate: LosslessSourceHealthGate,
    private val qualityPolicy: StreamQualityPolicy,
) {
    suspend fun resolve(track: TrackEntity): StreamUrl? {
        if (healthGate.isDegraded(QobuzSource.SOURCE_ID)) {
            // Content-degraded (preview-sample / lossy downgrade) within the
            // cooldown — skip so the streaming registry fails over.
            Log.d(TAG, "skip id=${track.id} (squid content-degraded)")
            return null
        }
        Log.d(TAG, "resolve attempt id=${track.id} title='${track.title}'")
        if (!source.isEnabledForStreaming()) {
            Log.d(TAG, "disabled id=${track.id} (no cookie or stale)")
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
        const val TAG = "QobuzStreamResolver"
        const val ORIGIN = "squid"
        val ETSP_REGEX = Regex("""[?&]etsp=(\d+)""")
    }
}
