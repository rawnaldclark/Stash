package com.stash.data.download.lossless.addon

import android.util.Log
import com.stash.addons.contract.Codec
import com.stash.addons.contract.Container
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.Resource
import com.stash.addons.contract.StreamInfo
import com.stash.addons.contract.StreamRequest
import com.stash.addons.contract.Tier
import com.stash.core.addons.AddonRegistry
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
import kotlinx.coroutines.currentCoroutineContext
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
    private val refs: AddonRefs,
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
                    ?: AddonLosslessSource(addon, refs, limiter, prefs).also { cache[addon.id] = it }
            }
        }
    }

    internal companion object {
        /**
         * Finds songs (resolve, or search matched on the phone, phase 4 §2.4), gives a stream or a
         * download, allows downloads (BitChord's `allowDownloads: 0` says no), and can answer a
         * lossless tier.
         *
         * An add-on the user added that can only search is still a source, but a quiet one: a
         * download never searches it ([AddonRefs.matchesBySearch]), so it is asked only for a song
         * a play already matched there.
         */
        fun canDownloadLossless(addon: InstalledAddon): Boolean {
            val m = addon.manifest
            if (!m.offers(Resource.RESOLVE) && !m.offers(Resource.SEARCH)) return false
            if (!m.offers(Resource.DOWNLOAD) && !m.offers(Resource.STREAM)) return false
            if (!addon.allowsDownloads) return false
            val caps = m.stream ?: return false
            val tiers = caps.knownTiers
            if (tiers.isNotEmpty() && tiers.none { it.isLossless }) return false
            return caps.knownManifests.any { it == Delivery.NONE || it == Delivery.DASH }
        }
    }
}

/**
 * One add-on as a download source: its ref, `/resolve` or a matched `/search` for the song
 * ([AddonRefs]), then `/download/{id}` (else `/stream/{id}`) for a lossless file in one
 * progressive file or DASH, the two the downloader takes. Its id is `addon:<manifest id>`; its pacing and breaker are the add-on's
 * own [HttpAddonClient.rateKey] bucket in [AggregatorRateLimiter].
 *
 * An answer that needs request headers is not used yet: the downloader would send them to every
 * origin a DASH manifest names, and SPEC.md 5.4 allows them on the stream URL's origin only.
 */
class AddonLosslessSource internal constructor(
    val addon: InstalledAddon,
    private val refs: AddonRefs,
    private val limiter: AggregatorRateLimiter,
    private val prefs: LosslessSourcePreferences,
) : LosslessSource {

    override val id: String = sourceId(addon.id)

    override val displayName: String = addon.manifest.name

    private val rateKey = HttpAddonClient.rateKey(addon.id)

    override suspend fun isEnabled(): Boolean = !limiter.stateOf(rateKey).isCircuitBroken

    override suspend fun rateLimitState(): RateLimitState = limiter.stateOf(rateKey)

    override suspend fun resolve(query: TrackQuery, bypassRateLimit: Boolean): SourceResult? = try {
        // A caller that asks no add-on (the search preview): no request at all.
        if (currentCoroutineContext()[NoAddonSources.Key] != null) null else lookup(query, paced = !bypassRateLimit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$id resolve threw ${e.javaClass.simpleName}")
        null
    }

    private suspend fun lookup(query: TrackQuery, paced: Boolean): SourceResult? {
        val request = lossless(prefs.qualityTierNow())
        // A download from an add-on the user added never matches by search: only its /resolve, or
        // a ref a play already made (review-882 J3).
        val streamed = refs.stream(
            addon, query, AddonRefs.Pass.LOSSLESS, request,
            paced = paced, download = true, bySearch = AddonRefs.matchesBySearch(addon, download = true),
        ) as? AddonRefs.Streamed.Ok ?: return null
        val info = streamed.info
        val ref = streamed.ref
        if (!info.headers.isNullOrEmpty()) {
            Log.d(TAG, "$id answer needs request headers; not used for downloads yet")
            return null
        }
        return SourceResult(
            sourceId = id,
            downloadUrl = info.url,
            downloadHeaders = emptyMap(),
            format = format(info),
            confidence = if (!query.isrc.isNullOrBlank() && ref.isrc.equals(query.isrc, ignoreCase = true)) 1f else ref.confidence,
            sourceTrackId = ref.remoteId,
            // Null for an add-on Stash doesn't vouch for ([AddonRefs.keepsArtwork]).
            coverArtUrl = ref.artwork,
            isDash = info.manifest == Delivery.DASH,
            maxBytes = ceilingBytes(query.durationMs?.takeIf { query.durationReliable }, info),
        )
    }

    companion object {
        private const val TAG = "AddonLosslessSource"

        /** The [LosslessSource.id] of add-on [addonId]. */
        fun sourceId(addonId: String): String = "addon:$addonId"

        /** A lossless file at the download quality: CD asks for `lossless` only, the others up to `hires`. */
        fun lossless(tier: LosslessQualityTier): StreamRequest = StreamRequest(
            tier = if (tier == LosslessQualityTier.CD) Tier.LOSSLESS else Tier.HIRES,
            minTier = Tier.LOSSLESS,
            manifest = listOf(Delivery.NONE, Delivery.DASH),
        )

        /**
         * The most bytes a file for a song of [durationMs] can be (review-882 M11): the stated
         * format's own PCM size (nothing lossless is larger than the samples it holds), a quarter
         * more for a container and a tail, and 8 MiB of slack. With no format stated: 24-bit /
         * 192 kHz, the largest the app asks for. With no length known: an hour. A 4-minute CD FLAC
         * may be 61 MB (it is about 28); the same song at 24/192 may be 354 MB (about 140).
         */
        fun ceilingBytes(durationMs: Long?, info: StreamInfo): Long {
            val seconds = ((durationMs?.takeIf { it > 0 } ?: UNKNOWN_LENGTH_MS) + 999) / 1000
            val bits = (info.bitDepth?.takeIf { it > 0 } ?: 24).coerceIn(16, 32)
            val rate = (info.sampleRate?.takeIf { it > 0 } ?: 192_000).coerceIn(44_100, 384_000)
            val pcmBytesPerSecond = bits.toLong() / 8 * rate * 2
            return seconds * pcmBytesPerSecond * 5 / 4 + 8L * 1024 * 1024
        }

        private const val UNKNOWN_LENGTH_MS = 60 * 60_000L

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
