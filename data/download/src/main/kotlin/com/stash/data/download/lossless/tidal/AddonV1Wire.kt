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
import java.util.concurrent.atomic.AtomicInteger
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
 * **CD means CD (decision D15, the owner's "yes" of 2026-11-11).** When the listener's quality
 * setting for this lookup is CD (the streaming tier of the network they are on, or the download
 * tier), the first two rows ask `tier=lossless` instead of `tier=hires`: the add-on then answers
 * Tidal's CD FLAC (16-bit/44.1 kHz) even for a track Tidal also has in Hi-Res. On this wire only:
 * the legacy relay has no such lane, and plays what Tidal gives at every setting, as it always did.
 * The add-on must run its CD lane (`V1_LOSSLESS_TIER_CD_ONLY = "1"`, as deployed): without it a
 * Hi-Res track asked at `tier=lossless` is refused as `quality_unavailable`, a 14-day miss here.
 *
 * Errors read as the relay's did (plan §4.4): `quality_unavailable` is a definitive miss
 * (`lossless_unavailable`), `not_found` and `region_unavailable` short ones (`not_available`);
 * `rate_limited` and a 503 with `retryAfter` are busy for that long; `quota_exceeded` is the spent
 * day, recorded in [LosslessQuota] like the relay's cap body; anything else is a transient error.
 * A track Tidal refuses (the relay's 502) arrives as HTTP 502 `unavailable` with `retryAfter` 15
 * (stash-addon-tidal 40ab038): that one lookup fails, as on the legacy wire, and nothing pauses.
 * Answers' `quota` feeds [LosslessQuota] as the relay's `X-Stash-Lossless` headers did.
 *
 * **A face that stops reading our calls** ([broken]): a 401, a 400 or an answer that breaks the
 * contract is a hard failure. [HARD_FAILURE_LIMIT] of them in a row on one route, with no good
 * answer from that route between, and [TidalWires] sets this wire aside for the legacy relay until
 * the next applied config. Counted per route, because a lookup is a `/search` and then a
 * `/stream`: a face whose `/stream` refuses every call still answers every `/search`.
 *
 * **Downloads ask `/stream/{id}` too**, though the manifest lists `download` (SPEC.md 4.5;
 * review-881 N6, left on purpose). A play and a download of one song share one in-flight lookup
 * here ([TidalClient.lookup]), which is the relay's budget; asking `/download` for one of them
 * would split that into two requests, and the add-on serves both routes from the same code.
 *
 * The client's own `Retry-After` gate is off for these calls (`circuit = false`): [TidalClient]
 * keeps the busy circuit, as on the legacy wire, and pacing stays with the Tidal download source.
 */
@Singleton
class AddonV1Wire @Inject constructor(
    private val client: HttpAddonClient,
    private val quota: LosslessQuota,
) {
    /** Hard failures in a row, per route: see the class doc. */
    private val searchHardFailures = AtomicInteger()
    private val streamHardFailures = AtomicInteger()

    /** A route of the face has failed hard [HARD_FAILURE_LIMIT] times in a row. */
    val broken: Boolean
        get() = searchHardFailures.get() >= HARD_FAILURE_LIMIT || streamHardFailures.get() >= HARD_FAILURE_LIMIT

    /** Starts both counts over: [TidalWires] gives the wire another go after an applied config. */
    fun forgive() {
        searchHardFailures.set(0)
        streamHardFailures.set(0)
    }

    /**
     * What [failure] (null: a good answer) says about the face on one route. A good answer, a miss
     * and a busy or spent answer are all the face reading our call: the count starts over. A 401, a
     * 400 and an invalid answer count. No answer at all, or a 5xx, says nothing either way.
     */
    private fun heard(count: AtomicInteger, failure: AddonFailure?) {
        when (failure) {
            null -> count.set(0)
            is AddonFailure.InvalidResponse -> count.incrementAndGet()
            is AddonFailure.Refused -> when (failure.code) {
                ErrorCode.UNAUTHORIZED, ErrorCode.BAD_REQUEST -> count.incrementAndGet()
                ErrorCode.UNAVAILABLE -> Unit
                else -> count.set(0)
            }
            else -> Unit
        }
    }

    /** This wire on [addon]; [clock] is the client's (tests drive it). */
    fun bind(addon: InstalledAddon, clock: () -> Long): TidalWire = Bound(addon, clock)

    private inner class Bound(private val addon: InstalledAddon, private val clock: () -> Long) : TidalWire {
        override val kind = TidalWire.Kind.V1
        override val owner: Boolean = addon.firstParty
        override val serverKey: String = addon.base

        override suspend fun search(q: String): WireReply<List<TidalCandidate>> {
            val r = client.search(addon, q, limit = SEARCH_LIMIT, paced = false, circuit = false)
            heard(searchHardFailures, (r as? AddonResult.Failed)?.failure)
            return when (r) {
                is AddonResult.Ok -> WireReply.Ok(r.value.tracks.map { it.toCandidate() })
                is AddonResult.Failed -> failure(r.failure)
            }
        }

        override suspend fun stream(
            id: String, mode: TidalStreamMode, title: String, artist: String, cdOnly: Boolean,
        ): WireReply<TidalWireStream> {
            val r = client.stream(addon, id, request(mode, cdOnly), paced = false, circuit = false)
            heard(streamHardFailures, (r as? AddonResult.Failed)?.failure)
            return when (r) {
                is AddonResult.Ok -> {
                    val info = r.value
                    if (owner) info.quota?.let { quota.onQuota(it.used, it.limit, it.resetsAt) }
                    WireReply.Ok(info.toWireStream())
                }
                is AddonResult.Failed -> failure(r.failure)
            }
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
                // A busy pool is a 503 that says how long. Anything else unavailable is this one
                // lookup's failure, as on the legacy wire: the relay's 500 (a 503 with no
                // `retryAfter` through the face) and a track Tidal refuses (the relay's 502, which
                // the face keeps as HTTP 502 with `retryAfter` 15, so it can't be taken for a
                // busy pool and pause Tidal on the whole phone; review-881 M3).
                ErrorCode.UNAVAILABLE -> if (f.status == 503 && retryMs != null) {
                    WireReply.Busy(now + retryMs.coerceIn(TidalClient.BUSY_MIN_RETRY_AFTER_MS, TidalClient.BUSY_MAX_RETRY_AFTER_MS))
                } else {
                    WireReply.Error(f.status)
                }
                ErrorCode.QUOTA_EXCEEDED -> if (owner) {
                    val q = f.quota
                    quota.onQuotaExceeded(q?.used, q?.limit, q?.resetsAt, unlimited = q != null && q.limit == null)
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

        /** Hard failures in a row, on one route, before the wire is set aside ([broken]). */
        const val HARD_FAILURE_LIMIT = 3

        /**
         * SPEC.md 5.2's range for each mode (the table in the class doc). [cdOnly]: the listener's
         * quality setting is CD, so nothing above `lossless` is asked for (decision D15).
         */
        fun request(mode: TidalStreamMode, cdOnly: Boolean = false): StreamRequest {
            val best = if (cdOnly) Tier.LOSSLESS else Tier.HIRES
            return when (mode) {
                TidalStreamMode.LOSSLESS -> StreamRequest(best, Tier.LOSSLESS, listOf(Delivery.NONE, Delivery.DASH))
                TidalStreamMode.LOSSLESS_OR_AAC -> StreamRequest(best, Tier.HIGH, listOf(Delivery.NONE, Delivery.DASH))
                TidalStreamMode.AAC_ONLY -> StreamRequest(Tier.HIGH, Tier.HIGH, listOf(Delivery.NONE))
            }
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
            val c = codec
            return TidalWireStream(
                url = url,
                codec = when (c) {
                    Codec.FLAC -> "flac"
                    Codec.AAC, Codec.HE_AAC -> "aac"
                    null -> if (tier.isLossless) "flac" else "aac"
                    else -> c.name.lowercase()
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
