package com.stash.core.media.streaming

import android.util.Log
import com.stash.addons.contract.Codec
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.Resource
import com.stash.addons.contract.StreamInfo
import com.stash.addons.contract.StreamRequest
import com.stash.addons.contract.Tier
import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.InstalledAddon
import com.stash.core.addons.Trust
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourceHealthGate
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.addon.AddonRefs
import com.stash.data.download.lossless.forLibrarySource
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The add-ons that can stream a song, as rungs for [StreamSourceRegistry]: lossless rungs at the
 * lossless position ([rungs]), and the lossy pass before JioSaavn ([lossyRungs], add-ons plan
 * phase 4 §2.4). They come from [AddonRegistry] at resolve time, never from `@IntoSet`: add-ons
 * come and go while the app runs.
 *
 * An add-on needs `stream` and a way to find a song: `resolve`, or `search` matched on the phone
 * ([AddonRefs]), which is how every BitChord-style and most Eclipse add-ons work.
 *
 * With no add-on of the user's and no generic default, both lists are empty and the chain is the
 * one the app had before add-ons.
 */
@Singleton
class AddonStreamRungs @Inject constructor(
    private val registry: AddonRegistry,
    private val refs: AddonRefs,
    /**
     * The content-health gate: an add-on whose stream the player refused ([AddonStreamCheck]), or
     * whose file a download refused, is set aside there for its cooldown and is no rung until then.
     */
    private val health: LosslessSourceHealthGate = LosslessSourceHealthGate(),
) {
    /**
     * The lossless rungs, in the registry's order (user-added first); [StreamSourceRegistry] places
     * them by the play order. Empty, with no work done, when none are on. The first-party Tidal add-on is never one: the Tidal rung plays it
     * ([AddonRegistry.generic]). An add-on that is set aside ([health]) is left out.
     */
    fun rungs(): List<AddonStreamRung> {
        val addons = registry.generic()
        if (addons.isEmpty()) return emptyList()
        return addons.filter { canStreamLossless(it) && !setAside(it) }.map { AddonStreamRung(it, refs) }
    }

    /** Set aside in the content-health gate (its `addon:<id>` key, also its download source's id): not asked, in either pass. */
    private fun setAside(addon: InstalledAddon): Boolean {
        val key = AddonStreamRung.ORIGIN_PREFIX + addon.id
        if (!health.isDegraded(key)) {
            saidSetAside.remove(key)
            return false
        }
        // Said once per cooldown, not on every resolve of every pass (review-888 n5).
        if (saidSetAside.add(key)) Log.d(TAG, "skipping $key: set aside (content-health cooldown)")
        return true
    }

    /** The add-ons [setAside] has already said are set aside, while they still are. */
    private val saidSetAside: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * True when an add-on that is on can answer a stream as a DASH manifest. The player then
     * resolves a placeholder before it picks its media source ([AddonPlayback.resolveBeforeSourcePick]):
     * a manifest cannot be fed to the progressive source a placeholder otherwise gets. No request
     * and no I/O: the registry's list as it stands.
     */
    fun mayAnswerDash(): Boolean =
        registry.generic().any { canStream(it) && Delivery.DASH in it.manifest.stream!!.knownManifests }

    /** The lossy pass's rungs (phase 4 §2.4), in the registry's order. Empty, with no work done, when none are on. Never one set aside. */
    fun lossyRungs(): List<AddonStreamRung> {
        val addons = registry.generic()
        if (addons.isEmpty()) return emptyList()
        return addons.filter { canStreamLossy(it) && !setAside(it) }.map { AddonStreamRung(it, refs) }
    }

    internal companion object {
        private const val TAG = "AddonStreamRung"

        /** Streams, finds songs (resolve or search), and plays in a delivery the player takes. */
        private fun canStream(addon: InstalledAddon): Boolean {
            val m = addon.manifest
            if (!m.offers(Resource.STREAM)) return false
            if (!m.offers(Resource.RESOLVE) && !m.offers(Resource.SEARCH)) return false
            val caps = m.stream ?: return false
            return caps.knownManifests.any { it == Delivery.NONE || it == Delivery.DASH }
        }

        /** [canStream], and can answer a lossless tier (or doesn't say which tiers it has). */
        fun canStreamLossless(addon: InstalledAddon): Boolean {
            if (!canStream(addon)) return false
            val tiers = addon.manifest.stream!!.knownTiers
            return tiers.isEmpty() || tiers.any { it.isLossless }
        }

        /** [canStream], and can answer a lossy tier (or doesn't say which tiers it has). */
        fun canStreamLossy(addon: InstalledAddon): Boolean {
            if (!canStream(addon)) return false
            val tiers = addon.manifest.stream!!.knownTiers
            return tiers.isEmpty() || tiers.any { !it.isLossless }
        }
    }
}

/**
 * One add-on as a stream rung: its ref, `/resolve` or a matched `/search` for the song
 * ([AddonRefs]), then `/stream/{id}`. [name] is the lossless chain's label and the
 * [StreamUrl.origin], `addon:<manifest id>`; the lossy pass labels it [lossyName].
 *
 * An answer that needs request headers is not played yet: the player's HTTP sources would send
 * them to every origin a DASH manifest names, and SPEC.md 5.4 allows them on the stream URL's
 * origin only.
 */
class AddonStreamRung internal constructor(
    val addon: InstalledAddon,
    private val refs: AddonRefs,
) {
    val name: String = ORIGIN_PREFIX + addon.id

    /** The lossy pass's chain label: never counted as a lossless serve. */
    val lossyName: String = LOSSY_PREFIX + name

    /** Added by the user. Where it is asked is the play order's call (`PlayOrder`). */
    val userAdded: Boolean get() = addon.userAdded

    /** A metered add-on (its manifest lists `quota`): a look-ahead never spends its allowance. */
    val spendsQuota: Boolean get() = addon.offers(Resource.QUOTA)

    /** The lossless pass: a lossless stream at the streaming tier, or null. */
    suspend fun resolve(track: TrackEntity, tier: LosslessQualityTier?): StreamUrl? =
        play(track, AddonRefs.Pass.LOSSLESS, request(tier))

    /** The lossy pass (phase 4 §2.4): a lossy stream at [quality] (320 asks `high`, Data saver `low`), or null. */
    suspend fun resolveLossy(track: TrackEntity, quality: LossyQuality): StreamUrl? =
        play(track, AddonRefs.Pass.LOSSY, lossyRequest(quality))

    private suspend fun play(track: TrackEntity, pass: AddonRefs.Pass, request: StreamRequest): StreamUrl? {
        val q = TrackQuery(
            artist = track.artist,
            title = track.title,
            album = track.album.takeIf { it.isNotBlank() },
            isrc = track.isrc?.takeIf { it.isNotBlank() },
            durationMs = track.durationMs,
            explicit = track.explicit,
        ).forLibrarySource(track.source, track.musicVideoType, hasVideoId = !track.youtubeId.isNullOrBlank())
        // An add-on Stash doesn't run gets a short budget on a play: the contract's own timeouts
        // (15 s to find a song, 15 s for its stream) would let one slow server hold every song for
        // half a minute, and a slow answer never opens its breaker. Past the budget the play moves
        // on, and the overrun counts against the add-on like a timeout.
        val answer = if (addon.trust == Trust.UNOFFICIAL) {
            withTimeoutOrNull(UNOFFICIAL_PLAY_BUDGET_MS) { refs.stream(addon, q, pass, request) } ?: run {
                Log.i(TAG, "$name took over ${UNOFFICIAL_PLAY_BUDGET_MS / 1000} s; the play moves on")
                refs.tooSlow(addon)
                return null
            }
        } else {
            refs.stream(addon, q, pass, request)
        }
        val streamed = answer as? AddonRefs.Streamed.Ok ?: return null
        val info = streamed.info
        if (!info.headers.isNullOrEmpty()) {
            Log.d(TAG, "$name answer needs request headers; not played yet")
            return null
        }
        val url = toStreamUrl(info, streamed.ref.artwork, name) ?: run {
            Log.d(TAG, "$name answer has already expired; not played")
            return null
        }
        // The song's length for the player's check of this stream (AddonStreamCheck's length rule):
        // what the resolve asked with (a placeholder's library row), else what the add-on's own matched
        // item says. A Search tap or a radio song with no length of its own is judged by it.
        return url.copy(songMs = track.durationMs.takeIf { it > 0 } ?: streamed.ref.durationMs?.takeIf { it > 0 })
    }

    companion object {
        private const val TAG = "AddonStreamRung"

        /** [StreamUrl.origin] and chain label prefix of an add-on rung. */
        const val ORIGIN_PREFIX = "addon:"

        /** The lossy pass's chain label prefix (`lossy:addon:<id>`); its streams keep the [ORIGIN_PREFIX] origin. */
        const val LOSSY_PREFIX = "lossy:"

        /**
         * How long a play waits for an add-on Stash doesn't run, for finding the song and its
         * stream together, in each pass. Long enough for a search, one "still preparing" wait
         * (3 s) and the stream on a slow connection; short enough that the play goes on to the
         * next source while the listener is still waiting for it.
         */
        const val UNOFFICIAL_PLAY_BUDGET_MS = 6_000L

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

        /** The lossy pass's range (phase 4 §2.4): 320 (no Data saver) is `high` only; Data saver `low` only. */
        fun lossyRequest(quality: LossyQuality): StreamRequest {
            val tier = if (quality == LossyQuality.FULL) Tier.HIGH else Tier.LOW
            return StreamRequest(tier = tier, minTier = tier, manifest = listOf(Delivery.NONE, Delivery.DASH))
        }

        /**
         * The player's codec name. A lossy codec is named as such, never `flac` (plan §5.6).
         * Unknown: `flac`, the only codec a v1 answer without one can be (SPEC.md 5.2 defaults it
         * from the tier); a lossy answer without one is named by [toStreamUrl] from its tier.
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
                // A lossy answer that names no codec must not be labelled FLAC: the player's
                // extractor sniffs the bytes, and the quality line says "lossy".
                codec = if (info.codec == null && !info.tier.isLossless) "aac" else codecName(info.codec),
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
