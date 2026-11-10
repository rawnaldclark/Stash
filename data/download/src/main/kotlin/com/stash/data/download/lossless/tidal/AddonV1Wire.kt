package com.stash.data.download.lossless.tidal

import android.util.Log
import com.stash.addons.contract.Codec
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.ErrorCode
import com.stash.addons.contract.StreamInfo
import com.stash.addons.contract.StreamRequest
import com.stash.addons.contract.Tier
import com.stash.addons.contract.Track
import com.stash.core.addons.AddonFailure
import com.stash.core.addons.AddonResult
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.InstalledAddon
import com.stash.data.download.lossless.quota.LosslessQuota
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The v1 wire: the first-party Tidal add-on's Stash Add-ons v1 face (`/v1/<token>/`, plan §4),
 * through [HttpAddonClient], answered in the same neutral shapes as the legacy wire so
 * [TidalClient] makes the same pick and the same lookup from either.
 *
 * Matching stays on the phone (plan §4.6): `/search` rows are scored by the app's own scorer,
 * exactly as on the legacy wire; `/resolve` takes over later, once the add-on's port of the scorer
 * passes the corpus ratchet. `/stream/{id}` asks the SPEC.md 5.2 range for each [TidalStreamMode]:
 *
 * | mode | v1 request | the legacy relay's |
 * |---|---|---|
 * | [TidalStreamMode.LOSSLESS] | `tier=hires&minTier=lossless&manifest=none,dash` | no `aac` |
 * | [TidalStreamMode.LOSSLESS_OR_AAC] | `tier=hires&minTier=high&manifest=none,dash` | `aac=1` |
 * | [TidalStreamMode.AAC_ONLY] | `tier=high&minTier=high&manifest=none` | `aac=only&hires=0` |
 *
 * `tier=hires` even at the CD streaming setting: today's Tidal rung plays what Tidal gives, and
 * this wire must not change that (decision D15 is the owner's, on the add-on's side).
 *
 * Errors read as the relay's did (plan §4.4): `quality_unavailable` is a definitive miss
 * (`lossless_unavailable`), `not_found` and `region_unavailable` short ones (`not_available`);
 * `rate_limited` and a 503 with `retryAfter` are busy for that long; `quota_exceeded` is the spent
 * day, recorded in [LosslessQuota] like the relay's cap body; anything else is a transient error.
 * Answers' `quota` feeds [LosslessQuota] as the relay's `X-Stash-Lossless` headers did.
 *
 * The client's own `Retry-After` gate is off for these calls (`circuit = false`): [TidalClient]
 * keeps the busy circuit, as on the legacy wire, and pacing stays with the Tidal download source.
 */
@Singleton
class AddonV1Wire @Inject constructor(
    private val client: HttpAddonClient,
    private val quota: LosslessQuota,
) {
    /** This wire on [addon]; [clock] is the client's (tests drive it). */
    fun bind(addon: InstalledAddon, clock: () -> Long): TidalWire = Bound(addon, clock)

    private inner class Bound(private val addon: InstalledAddon, private val clock: () -> Long) : TidalWire {
        override val kind = TidalWire.Kind.V1
        override val owner: Boolean = addon.firstParty
        override val serverKey: String = addon.base

        override suspend fun search(q: String): WireReply<List<TidalCandidate>> =
            when (val r = client.search(addon, q, limit = SEARCH_LIMIT, paced = false, circuit = false)) {
                is AddonResult.Ok -> WireReply.Ok(r.value.tracks.map { it.toCandidate() })
                is AddonResult.Failed -> failure(r.failure)
            }

        override suspend fun stream(id: String, mode: TidalStreamMode, title: String, artist: String): WireReply<TidalWireStream> =
            when (val r = client.stream(addon, id, request(mode), paced = false, circuit = false)) {
                is AddonResult.Ok -> {
                    val info = r.value
                    if (owner) info.quota?.let { quota.onQuota(it.used, it.limit, it.resetsAt) }
                    WireReply.Ok(info.toWireStream())
                }
                is AddonResult.Failed -> failure(r.failure)
            }

        private fun failure(f: AddonFailure): WireReply<Nothing> = when (f) {
            is AddonFailure.Refused -> refused(f)
            AddonFailure.Timeout, AddonFailure.Network -> WireReply.Unreachable
            is AddonFailure.InvalidResponse -> WireReply.Error(0)
            AddonFailure.Paused, AddonFailure.NotOffered -> WireReply.Error(0)
        }

        private fun refused(f: AddonFailure.Refused): WireReply<Nothing> {
            val now = clock()
            val retryMs = f.retryAfterSec?.let { it * 1000L }
            return when (f.code) {
                ErrorCode.QUALITY_UNAVAILABLE -> WireReply.NotFound(definitive = true)
                ErrorCode.NOT_FOUND, ErrorCode.REGION_UNAVAILABLE -> WireReply.NotFound(definitive = false)
                ErrorCode.RATE_LIMITED ->
                    WireReply.Busy(now + (retryMs?.coerceIn(TidalClient.MIN_RETRY_AFTER_MS, TidalClient.MAX_RETRY_AFTER_MS) ?: TidalClient.BUSY_BACKOFF_MS))
                // A busy pool always says how long; an unavailable without it is the relay's
                // 502 / 500 read through the face, which the legacy wire treats as an error.
                ErrorCode.UNAVAILABLE -> if (f.status == 503 && retryMs != null) {
                    WireReply.Busy(now + retryMs.coerceIn(TidalClient.BUSY_MIN_RETRY_AFTER_MS, TidalClient.BUSY_MAX_RETRY_AFTER_MS))
                } else {
                    WireReply.Error(f.status)
                }
                ErrorCode.QUOTA_EXCEEDED -> if (owner) {
                    quota.onQuotaExceeded(f.quota?.used, f.quota?.limit, f.quota?.resetsAt, unlimited = f.quota != null && f.quota.limit == null)
                    WireReply.Spent
                } else {
                    WireReply.Busy(now + (retryMs?.coerceIn(TidalClient.MIN_RETRY_AFTER_MS, TidalClient.MAX_RETRY_AFTER_MS) ?: TidalClient.BUSY_BACKOFF_MS))
                }
                ErrorCode.UNAUTHORIZED, ErrorCode.BAD_REQUEST -> {
                    Log.w(TidalClient.TAG, "v1 add-on refused the call (HTTP ${f.status})")
                    WireReply.Error(f.status)
                }
            }
        }
    }

    companion object {
        /** All the rows the relay found, as the legacy wire gets them: the face only slices, it never searches again. */
        const val SEARCH_LIMIT = 200

        /** SPEC.md 5.2's range for each mode (the table in the class doc). */
        fun request(mode: TidalStreamMode): StreamRequest = when (mode) {
            TidalStreamMode.LOSSLESS -> StreamRequest(Tier.HIRES, Tier.LOSSLESS, listOf(Delivery.NONE, Delivery.DASH))
            TidalStreamMode.LOSSLESS_OR_AAC -> StreamRequest(Tier.HIRES, Tier.HIGH, listOf(Delivery.NONE, Delivery.DASH))
            TidalStreamMode.AAC_ONLY -> StreamRequest(Tier.HIGH, Tier.HIGH, listOf(Delivery.NONE))
        }

        internal fun Track.toCandidate() = TidalCandidate(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationSec = durationMs?.let { ((it + 500) / 1000).toInt() } ?: 0,
            artwork = artwork?.maxByOrNull { it.width ?: 0 }?.url,
            lossless = maxTier?.isLossless == true,
            isrc = isrc,
            explicit = explicit,
        )

        /**
         * A v1 answer in the relay's terms. bits/s become kbps. `expiresAt` is passed on for a
         * progressive file (the relay's `expiresAt`); a DASH link's own signed `exp` is what the
         * client counts down, as on the legacy wire, so its `expiresAt` (the same instant) is only
         * used when the link has none.
         */
        internal fun StreamInfo.toWireStream(): TidalWireStream {
            val dash = manifest == Delivery.DASH
            val linkExpiry = url.toHttpUrlOrNull()?.queryParameter("exp") != null
            return TidalWireStream(
                url = url,
                codec = when (codec) {
                    Codec.FLAC -> "flac"
                    Codec.AAC, Codec.HE_AAC -> "aac"
                    null -> if (tier.isLossless) "flac" else "aac"
                    else -> codec.name.lowercase()
                },
                isDash = dash,
                bitDepth = bitDepth ?: 0,
                sampleRate = sampleRate ?: 0,
                bitrateKbps = (bitrate ?: 0) / 1000,
                expiresAtSec = if (dash && linkExpiry) null else expiresAt,
            )
        }
    }
}
