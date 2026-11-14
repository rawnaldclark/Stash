package com.stash.core.media.streaming

import android.util.Log
import com.stash.addons.contract.Codec
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.Descriptor
import com.stash.addons.contract.ResolveQuery
import com.stash.addons.contract.Resource
import com.stash.addons.contract.StreamInfo
import com.stash.addons.contract.StreamRequest
import com.stash.addons.contract.Tier
import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.AddonResult
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.InstalledAddon
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.forLibrarySource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The add-ons that can stream a song lossless, as rungs for [StreamSourceRegistry]'s lossless
 * position. They come from [AddonRegistry] at resolve time, never from `@IntoSet`: add-ons come
 * and go while the app runs.
 *
 * With no add-on of the user's and no generic default, [rungs] is empty and the chain is the
 * one the app had before add-ons.
 */
@Singleton
class AddonStreamRungs @Inject constructor(
    private val registry: AddonRegistry,
    private val client: HttpAddonClient,
) {
    /**
     * The add-on rungs, in the registry's order (user-added first). Empty, with no work done, when
     * none are on. The first-party Tidal add-on is never one: the Tidal rung plays it
     * ([AddonRegistry.generic]).
     */
    fun rungs(): List<AddonStreamRung> {
        val addons = registry.generic()
        if (addons.isEmpty()) return emptyList()
        return addons.filter(::canStreamLossless).map { AddonStreamRung(it, client) }
    }

    internal companion object {
        /** Resolves, streams, and can answer a lossless tier in a delivery the player takes. */
        fun canStreamLossless(addon: InstalledAddon): Boolean {
            val m = addon.manifest
            if (!m.offers(Resource.RESOLVE) || !m.offers(Resource.STREAM)) return false
            val caps = m.stream ?: return false
            val tiers = caps.knownTiers
            if (tiers.isNotEmpty() && tiers.none { it.isLossless }) return false
            return caps.knownManifests.any { it == Delivery.NONE || it == Delivery.DASH }
        }
    }
}

/**
 * One add-on as a stream rung: `/resolve` for the exact recording, then `/stream/{id}` for a
 * lossless stream (one progressive file, or DASH). [name] is the chain's label and the
 * [StreamUrl.origin], `addon:<manifest id>`.
 *
 * An answer that needs request headers is not played yet: the player's HTTP sources would send
 * them to every origin a DASH manifest names, and SPEC.md 5.4 allows them on the stream URL's
 * origin only.
 */
class AddonStreamRung internal constructor(
    val addon: InstalledAddon,
    private val client: HttpAddonClient,
) {
    val name: String = ORIGIN_PREFIX + addon.id

    /**
     * Added by the user: asked ahead of Qobuz on every play. Unlike the user's own Tidal server,
     * which leads only above CD and only without the user's own Qobuz, there is no condition.
     */
    val userAdded: Boolean get() = addon.userAdded

    /** A metered add-on (its manifest lists `quota`): a look-ahead never spends its allowance. */
    val spendsQuota: Boolean get() = addon.offers(Resource.QUOTA)

    suspend fun resolve(track: TrackEntity, tier: LosslessQualityTier?): StreamUrl? {
        val q = TrackQuery(
            artist = track.artist,
            title = track.title,
            album = track.album.takeIf { it.isNotBlank() },
            isrc = track.isrc?.takeIf { it.isNotBlank() },
            durationMs = track.durationMs,
            explicit = track.explicit,
        ).forLibrarySource(track.source, track.musicVideoType, hasVideoId = !track.youtubeId.isNullOrBlank())
        val descriptor = Descriptor(
            title = q.title,
            artist = q.artist,
            album = q.album,
            durationMs = q.durationMs?.takeIf { q.durationReliable && it > 0 },
            isrc = q.isrc,
            explicit = q.explicit,
        )
        // Rule 7: a lossless rung asks for a recording it can stream lossless, or none.
        val item = when (val r = client.resolve(addon, ResolveQuery(descriptor, minTier = Tier.LOSSLESS))) {
            is AddonResult.Ok -> r.value ?: return null
            is AddonResult.Failed -> return null
        }
        val info = client.stream(addon, item.id, request(tier)).valueOrNull() ?: return null
        if (!info.headers.isNullOrEmpty()) {
            Log.d(TAG, "$name answer needs request headers; not played yet")
            return null
        }
        return toStreamUrl(info, item.artwork?.maxByOrNull { it.width ?: 0 }?.url, name) ?: run {
            Log.d(TAG, "$name answer has already expired; not played")
            null
        }
    }

    companion object {
        private const val TAG = "AddonStreamRung"

        /** [StreamUrl.origin] and chain label prefix of an add-on rung. */
        const val ORIGIN_PREFIX = "addon:"

        /** SPEC.md 5.3: with no `expiresAt`, a stream is good for 30 minutes. */
        private const val DEFAULT_TTL_MS = 30 * 60_000L

        /** And never cached past an hour, as the Tidal rung's URLs aren't. */
        private const val MAX_TTL_MS = 60 * 60_000L

        /** Lossless at the streaming quality: CD asks for `lossless` only, the others up to `hires`. */
        fun request(tier: LosslessQualityTier?): StreamRequest = StreamRequest(
            tier = if (tier == LosslessQualityTier.CD) Tier.LOSSLESS else Tier.HIRES,
            minTier = Tier.LOSSLESS,
            manifest = listOf(Delivery.NONE, Delivery.DASH),
        )

        /**
         * The player's codec name. A lossy codec is named as such, never `flac` (plan §5.6): no rung
         * asks below `lossless` yet, but one that does must not label AAC as FLAC. Unknown: `flac`,
         * the only codec a v1 answer without one can be (SPEC.md 5.2 defaults it from the tier).
         */
        internal fun codecName(codec: Codec?): String = when (codec) {
            Codec.ALAC -> "alac"
            Codec.PCM -> "wav"
            Codec.AAC, Codec.HE_AAC -> "aac"
            Codec.MP3 -> "mp3"
            Codec.OPUS -> "opus"
            Codec.VORBIS -> "vorbis"
            Codec.EAC3_JOC -> "eac3"
            Codec.FLAC, null -> "flac"
        }

        /** Null for an answer whose `expiresAt` has already passed: that URL is dead. */
        internal fun toStreamUrl(info: StreamInfo, coverArtUrl: String?, origin: String, nowMs: Long = System.currentTimeMillis()): StreamUrl? {
            val expiresAtSec = info.expiresAt
            if (expiresAtSec != null && expiresAtSec * 1000 <= nowMs) return null
            // SPEC.md 5.3: cache until `expiresAt - 60 s`; one closer than that is held to now
            // (played once, never cached).
            val expires = expiresAtSec?.let { it * 1000 - 60_000 } ?: (nowMs + DEFAULT_TTL_MS)
            return StreamUrl(
                url = info.url,
                expiresAtMs = expires.coerceIn(nowMs, nowMs + MAX_TTL_MS),
                codec = codecName(info.codec),
                bitsPerSample = info.bitDepth?.takeIf { it > 0 },
                sampleRateHz = info.sampleRate?.takeIf { it > 0 },
                bitrateKbps = info.bitrate?.let { it / 1000 }?.takeIf { it > 0 },
                coverArtUrl = coverArtUrl,
                origin = origin,
                isDash = info.manifest == Delivery.DASH,
            )
        }
    }
}
