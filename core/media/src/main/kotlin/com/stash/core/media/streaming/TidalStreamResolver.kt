package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.bitchord.BitchordClient
import com.stash.data.download.lossless.forLibrarySource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stream-URL resolver for Tidal lossless via the user's self-hosted BitChord
 * Lossless Relay ([BitchordClient]). The second lossless rung: after qbdlx
 * (Qobuz), before the lossy JioSaavn / YouTube rungs.
 *
 * Returns either a progressive FLAC URL or, for most Hi-Res tracks, a DASH MPD
 * ([StreamUrl.isDash]); the server patches the fMP4 init segment's zero sample
 * rate itself and re-signs it inside the MPD, so the client plays the MPD as-is
 * through a DASH media source — see [StashMediaSourceFactory].
 *
 * Expiry: the server's signed `exp`, capped below an hour ([BitchordClient]),
 * which is what [StreamUrlCache] counts down.
 *
 * Returns null when no server is configured, there is no confident match, or the
 * track has no FLAC on Tidal.
 */
@Singleton
class TidalStreamResolver @Inject constructor(
    private val client: BitchordClient,
    private val losslessPrefs: com.stash.data.download.lossless.LosslessSourcePreferences,
) {
    /** A server is configured (non-blocking; see [BitchordClient.configured]). */
    fun enabledHint(): Boolean = client.configured.value

    /**
     * True when serving [track] would have to ask the relay: nothing cached about it for
     * this mode and the pool is not busy. Speculative resolves (next-up prefetch) read this
     * and leave Tidal-dependent tracks for the real play; no request is made here.
     */
    suspend fun needsRelay(track: TrackEntity): Boolean =
        client.lookup(track.toQuery(), allowAac = losslessPrefs.tidalAacFallbackNow(), cacheOnly = true) ==
            com.stash.data.download.lossless.bitchord.TidalLookup.NeedsRelay

    /**
     * [needsRelay] for the "Prefer Tidal AAC while streaming" rung ([resolveAacOnly]): true
     * when an `?aac=only` lookup for [track] would have to ask the relay. No request is made.
     */
    suspend fun needsRelayAacOnly(track: TrackEntity): Boolean =
        client.lookup(track.toQuery(), allowAac = true, cacheOnly = true, aacOnly = true) ==
            com.stash.data.download.lossless.bitchord.TidalLookup.NeedsRelay

    /**
     * Tidal's 320 kbps AAC for [track] even when Tidal has it in FLAC: the opt-in
     * "Prefer Tidal AAC while streaming" rung, which runs ahead of Qobuz to save data.
     *
     * The same request the AAC 320 download mode makes (`?aac=only&hires=0`, see
     * [BitchordClient.lookup]'s `aacOnly`), with its own in-flight key and its own miss stamp,
     * so an AAC-only miss never hides the FLAC lookup the Tidal lossless rung makes, nor the
     * other way round. It counts toward the daily allowance like any Tidal lookup; a spent
     * day, a busy pool, a miss or an error return null at once and the chain goes on.
     *
     * Anything but AAC is refused: a self-hosted server that predates `aac=only` answers as if
     * the flag weren't there, and a FLAC from here would skip Qobuz while saving no data.
     */
    suspend fun resolveAacOnly(track: TrackEntity): StreamUrl? {
        if (!client.isConfigured()) return null
        val lookup = client.lookup(track.toQuery(), allowAac = true, aacOnly = true)
        val s = (lookup as? com.stash.data.download.lossless.bitchord.TidalLookup.Found)?.stream ?: run {
            Log.d(TAG, "aac_only no_result id=${track.id} (${lookup.javaClass.simpleName})")
            return null
        }
        if (!s.codec.equals("aac", ignoreCase = true)) {
            Log.i(TAG, "aac_only answered '${s.codec}' for id=${track.id}; not AAC, leaving it to the chain")
            return null
        }
        Log.i(TAG, "aac_only resolved id=${track.id} ${s.bitrateKbps} kbps")
        return StreamUrl(
            url = s.url,
            expiresAtMs = s.expiresAtMs,
            codec = s.codec,
            bitsPerSample = null,
            sampleRateHz = s.sampleRateHz.takeIf { it > 0 },
            bitrateKbps = s.bitrateKbps.takeIf { it > 0 },
            coverArtUrl = s.coverArtUrl,
            origin = ORIGIN,
            isDash = s.isDash,
        )
    }

    private fun TrackEntity.toQuery() = TrackQuery(
        artist = artist,
        title = title,
        album = album.takeIf { it.isNotBlank() },
        isrc = isrc?.takeIf { it.isNotBlank() },
        durationMs = durationMs,
    ).forLibrarySource(source, musicVideoType)

    suspend fun resolve(track: TrackEntity): StreamUrl? {
        if (!client.isConfigured()) return null
        val query = track.toQuery()
        // allowAac: a CD-only track streams as Tidal's 320 kbps AAC rather than
        // falling through to the lossy rungs below (JioSaavn / YouTube) — unless
        // the user switched "Tidal AAC when no FLAC" off (Tidal = FLAC only).
        val allowAac = losslessPrefs.tidalAacFallbackNow()
        val s = client.resolve(query, allowAac = allowAac) ?: run {
            Log.d(TAG, "no_result id=${track.id}")
            return null
        }
        return StreamUrl(
            url = s.url,
            expiresAtMs = s.expiresAtMs,
            codec = s.codec,
            bitsPerSample = s.bitDepth.takeIf { it > 0 && s.codec == "flac" },
            sampleRateHz = s.sampleRateHz.takeIf { it > 0 },
            bitrateKbps = s.bitrateKbps.takeIf { it > 0 },
            coverArtUrl = s.coverArtUrl,
            origin = ORIGIN,
            isDash = s.isDash,
        )
    }

    companion object {
        private const val TAG = "TidalStreamResolver"
        const val ORIGIN = "tidal"
    }
}
