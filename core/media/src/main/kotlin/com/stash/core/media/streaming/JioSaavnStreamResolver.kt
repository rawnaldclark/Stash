package com.stash.core.media.streaming

import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.jiosaavn.JioSaavnResolver
import com.stash.data.download.lossless.TrackQuery
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Adapts the shared JioSaavn AAC resolver to the player stream contract:
 * 320 kbps, or 96 under Save Data.
 */
@Singleton
class JioSaavnStreamResolver @Inject constructor(
    private val resolver: JioSaavnResolver,
    private val qualityPolicy: StreamQualityPolicy,
) {
    suspend fun resolve(track: TrackEntity): StreamUrl? =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            resolver.resolve(
                TrackQuery(
                    artist = track.artist,
                    title = track.title,
                    album = track.album.takeIf { it.isNotBlank() },
                    durationMs = track.durationMs.takeIf { it > 0 },
                    explicit = track.explicit,
                ),
                // Save Data streams JioSaavn's lowest variant, as it does YouTube's
                // lowest audio. Read through the policy so a flip clears cached URLs.
                kbps = if (qualityPolicy.saveData()) 96 else 320,
                bypassRateLimit = false,
            )?.let { result ->
                StreamUrl(
                    url = result.downloadUrl,
                    expiresAtMs = System.currentTimeMillis() + CACHE_TTL_MS,
                    codec = result.format.codec,
                    sampleRateHz = result.format.sampleRateHz.takeIf { it > 0 },
                    bitrateKbps = result.format.bitrateKbps,
                    coverArtUrl = result.coverArtUrl,
                    origin = ORIGIN,
                )
            }
        }

    companion object {
        const val ORIGIN = "jiosaavn"
        private const val RESOLVE_TIMEOUT_MS = 7_000L
        private const val CACHE_TTL_MS = 60 * 60_000L
    }
}
