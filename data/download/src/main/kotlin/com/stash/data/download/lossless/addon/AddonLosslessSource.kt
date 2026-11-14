package com.stash.data.download.lossless.addon

import android.util.Log
import com.stash.addons.contract.Codec
import com.stash.addons.contract.Container
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
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The add-ons that can give a lossless file to keep, as [LosslessSource]s, for
 * [com.stash.data.download.lossless.LosslessSourceRegistry.orderedSources] to append after the
 * Hilt-bound sources. They come from [AddonRegistry] at call time, never from `@IntoSet`: add-ons
 * come and go while the app runs.
 *
 * With no add-on of the user's and no generic default, [sources] is empty and the download
 * chain is the one the app had before add-ons.
 */
@Singleton
class AddonDownloadSources @Inject constructor(
    private val registry: AddonRegistry,
    private val client: HttpAddonClient,
    private val limiter: AggregatorRateLimiter,
    private val prefs: LosslessSourcePreferences,
) {
    /** One [AddonLosslessSource] per add-on, kept while its manifest is the same object. */
    private val cache = HashMap<String, AddonLosslessSource>()

    /**
     * The add-ons that can resolve a song and hand over a lossless file, in the registry's
     * order (user-added first). Empty, with no work done, when no add-on is on. The first-party
     * Tidal add-on is never one: the Tidal source downloads it ([AddonRegistry.generic]).
     */
    fun sources(): List<LosslessSource> {
        val addons = registry.generic()
        if (addons.isEmpty()) return emptyList()
        return synchronized(cache) {
            addons.filter(::canDownloadLossless).map { addon ->
                cache[addon.id]?.takeIf { it.addon == addon }
                    ?: AddonLosslessSource(addon, client, limiter, prefs).also { cache[addon.id] = it }
            }
        }
    }

    internal companion object {
        /** Resolves, gives a stream or a download, and says it can answer a lossless tier. */
        fun canDownloadLossless(addon: InstalledAddon): Boolean {
            val m = addon.manifest
            if (!m.offers(Resource.RESOLVE)) return false
            if (!m.offers(Resource.DOWNLOAD) && !m.offers(Resource.STREAM)) return false
            val caps = m.stream ?: return false
            val tiers = caps.knownTiers
            if (tiers.isNotEmpty() && tiers.none { it.isLossless }) return false
            return caps.knownManifests.any { it == Delivery.NONE || it == Delivery.DASH }
        }
    }
}

/**
 * One add-on as a download source: `/resolve` for the exact recording, then `/download/{id}`
 * (else `/stream/{id}`) for a lossless file in one progressive file or DASH, the two the
 * downloader takes. Its id is `addon:<manifest id>`; its pacing and breaker are the add-on's
 * own [HttpAddonClient.rateKey] bucket in [AggregatorRateLimiter].
 *
 * An answer that needs request headers is not used yet: the downloader would send them to every
 * origin a DASH manifest names, and SPEC.md 5.4 allows them on the stream URL's origin only.
 */
class AddonLosslessSource internal constructor(
    val addon: InstalledAddon,
    private val client: HttpAddonClient,
    private val limiter: AggregatorRateLimiter,
    private val prefs: LosslessSourcePreferences,
) : LosslessSource {

    override val id: String = sourceId(addon.id)

    override val displayName: String = addon.manifest.name

    private val rateKey = HttpAddonClient.rateKey(addon.id)

    override suspend fun isEnabled(): Boolean = !limiter.stateOf(rateKey).isCircuitBroken

    override suspend fun rateLimitState(): RateLimitState = limiter.stateOf(rateKey)

    override suspend fun resolve(query: TrackQuery, bypassRateLimit: Boolean): SourceResult? = try {
        lookup(query, paced = !bypassRateLimit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$id resolve threw ${e.javaClass.simpleName}")
        null
    }

    private suspend fun lookup(query: TrackQuery, paced: Boolean): SourceResult? {
        val descriptor = Descriptor(
            title = query.title,
            artist = query.artist,
            album = query.album,
            durationMs = query.durationMs?.takeIf { query.durationReliable && it > 0 },
            isrc = query.isrc,
            explicit = query.explicit,
        )
        // Rule 7: a lossless source asks for a recording it can hand over lossless, or none.
        val item = when (val r = client.resolve(addon, ResolveQuery(descriptor, minTier = Tier.LOSSLESS), paced)) {
            is AddonResult.Ok -> r.value ?: return null
            is AddonResult.Failed -> return null
        }
        val request = lossless(prefs.qualityTierNow())
        val answer = if (addon.offers(Resource.DOWNLOAD)) {
            client.download(addon, item.id, request, paced)
        } else {
            client.stream(addon, item.id, request, paced)
        }
        val info = answer.valueOrNull() ?: return null
        if (!info.headers.isNullOrEmpty()) {
            Log.d(TAG, "$id answer needs request headers; not used for downloads yet")
            return null
        }
        return SourceResult(
            sourceId = id,
            downloadUrl = info.url,
            downloadHeaders = emptyMap(),
            format = format(info),
            confidence = if (!query.isrc.isNullOrBlank() && item.isrc.equals(query.isrc, ignoreCase = true)) 1f else MATCH_CONFIDENCE,
            sourceTrackId = item.id,
            coverArtUrl = item.artwork?.maxByOrNull { it.width ?: 0 }?.url,
            isDash = info.manifest == Delivery.DASH,
        )
    }

    companion object {
        private const val TAG = "AddonLosslessSource"

        /** `/resolve` answers the exact recording or nothing (SPEC.md 4.3): as sure as a matched search. */
        private const val MATCH_CONFIDENCE = 0.9f

        /** The [LosslessSource.id] of add-on [addonId]. */
        fun sourceId(addonId: String): String = "addon:$addonId"

        /** A lossless file at the download quality: CD asks for `lossless` only, the others up to `hires`. */
        fun lossless(tier: LosslessQualityTier): StreamRequest = StreamRequest(
            tier = if (tier == LosslessQualityTier.CD) Tier.LOSSLESS else Tier.HIRES,
            minTier = Tier.LOSSLESS,
            manifest = listOf(Delivery.NONE, Delivery.DASH),
        )

        internal fun format(info: StreamInfo): AudioFormat {
            val codec = when (info.codec) {
                Codec.ALAC -> "alac"
                Codec.PCM -> "wav"
                else -> "flac"
            }
            val ext = when {
                info.codec == Codec.ALAC -> "m4a"
                info.codec == Codec.PCM || info.container == Container.WAV -> "wav"
                else -> "flac"
            }
            return AudioFormat(
                codec = codec,
                bitrateKbps = (info.bitrate ?: 0) / 1000,
                sampleRateHz = info.sampleRate ?: 0,
                bitsPerSample = info.bitDepth ?: 0,
                fileExtension = ext,
            )
        }
    }
}
