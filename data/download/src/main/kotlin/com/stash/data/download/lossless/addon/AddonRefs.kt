package com.stash.data.download.lossless.addon

import android.util.Log
import com.stash.addons.contract.Descriptor
import com.stash.addons.contract.ErrorCode
import com.stash.addons.contract.ResolveQuery
import com.stash.addons.contract.Resource
import com.stash.addons.contract.StreamInfo
import com.stash.addons.contract.StreamRequest
import com.stash.addons.contract.Tier
import com.stash.addons.contract.Track
import com.stash.core.addons.AddonEclipse
import com.stash.core.addons.AddonFailure
import com.stash.core.addons.AddonResult
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.InstalledAddon
import com.stash.core.data.db.dao.AddonRefDao
import com.stash.core.data.db.entity.AddonRefEntity
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.qobuz.QobuzCandidateMatcher
import com.stash.data.download.lossless.tidal.TidalClient
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What an add-on said about a song, kept so it is not asked again (add-ons plan phase 4 §2.4,
 * SPEC.md 12): the one helper behind the generic stream rungs and download sources.
 *
 * **Finding the add-on's id for a song** ([find]), in this order:
 *  1. **A ref**: a kept match in `addon_refs` under the add-on's id and the song key the Tidal
 *     rung uses ([TidalClient.cacheKey]). Matches last 30 days, misses 14, as Tidal's do.
 *  2. **`/resolve`**, for an add-on that has it, asking for a recording at [Pass.minTier] or better.
 *  3. **Matching on the phone**, for one that can only search (every BitChord-style add-on, most
 *     Eclipse ones): `/search "<artist> <title>"`, scored by the app's own [QobuzCandidateMatcher]
 *     as the Tidal rung scores its rows, rows with the song's ISRC first.
 *
 * **Then the stream** ([stream]): `/stream/{id}`, or `/download/{id}` for a file to keep. A
 * `not_found` there drops the ref; `quality_unavailable` stamps a miss for that pass only (a lossless
 * miss never blocks the lossy pass, as Tidal's AAC stamp works). An answer outside the asked range
 * on the lossless pass is held in memory for the lossy pass of the same play ([HeldAnswers]), so
 * the add-on isn't asked twice. Outages, pauses and rate limits are never misses.
 *
 * Nothing here logs a URL or a token: lines carry the add-on id and the song's row id at most.
 */
@Singleton
class AddonRefs @Inject constructor(
    private val client: HttpAddonClient,
    private val refs: AddonRefDao,
) {
    /** Wall clock; tests drive it. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    /** Which kind of answer a lookup is for: its miss stamp, and the lowest tier it accepts. */
    enum class Pass(val minTier: Tier) {
        /** The lossless rungs and lossless downloads: a recording the add-on can give at `lossless` or better. */
        LOSSLESS(Tier.LOSSLESS),

        /** The lossy pass before JioSaavn (phase 4 §2.4). */
        LOSSY(Tier.LOW),
    }

    /** The add-on's own item for a song: [remoteId] is what `/stream` takes. */
    data class Ref(val remoteId: String, val artwork: String?, val isrc: String?, val confidence: Float) {
        override fun toString(): String = "Ref(confidence=$confidence)"
    }

    /** What [find] came to. */
    sealed interface Found {
        data class Hit(val ref: Ref) : Found

        /** The add-on has nothing for this song in this pass: asked, or a fresh stamp says so. */
        data object Miss : Found

        /** Not asked or no answer (paused, outage, rate limited): nothing is stamped. */
        data object Unavailable : Found
    }

    /** What [stream] came to. */
    sealed interface Streamed {
        data class Ok(val info: StreamInfo, val ref: Ref) : Streamed
        data object Miss : Streamed
        data object Unavailable : Streamed
    }

    /** The add-on id for [query] on [addon] in [pass]; see the class doc. [paced]: background work (downloads). */
    suspend fun find(addon: InstalledAddon, query: TrackQuery, pass: Pass, paced: Boolean = false): Found {
        val key = TidalClient.cacheKey(query)
        val rid = rowId(addon.id)
        val now = nowMs()
        val row = read(rid, key)
        if (row != null) {
            if (row.remoteId != null && now - row.resolvedAt < TidalClient.MATCH_TTL_MS) {
                if (missFresh(row, pass, now)) return Found.Miss
                Log.i(TAG, "ref hit ${addon.id}")
                // A row kept before cover art stopped being taken from such add-ons may still hold one.
                return Found.Hit(Ref(row.remoteId!!, row.artwork.takeIf { keepsArtwork(addon) }, null, row.confidence))
            }
            // No match at all (a search found none): every pass, for 14 days.
            if (row.remoteId == null && row.resolvedAt > 0 && now - row.resolvedAt < TidalClient.MISS_TTL_MS) return Found.Miss
            // No item at this pass's tier floor (a resolve miss): this pass only.
            if (row.remoteId == null && missFresh(row, pass, now)) return Found.Miss
        }
        // An Eclipse add-on whose only resolver is `/resolve-isrc` can't place a song with no ISRC,
        // nor one its ISRC misses (releases carry different ISRCs): its search can.
        val isrcOnly = addon.eclipse?.resolveViaIsrc == true
        val canResolve = addon.offers(Resource.RESOLVE) && !(isrcOnly && query.isrc.isNullOrBlank())
        if (canResolve) {
            val found = viaResolve(addon, rid, query, key, pass, paced, row)
            if (!(isrcOnly && found == Found.Miss && addon.offers(Resource.SEARCH))) return found
        }
        return if (addon.offers(Resource.SEARCH)) viaSearch(addon, rid, query, key, paced) else Found.Unavailable
    }

    /**
     * [find], then `/download/{id}` (when [download] and the add-on lists it) or `/stream/{id}` at
     * [request], with the stamps and the held answer of the class doc.
     */
    suspend fun stream(
        addon: InstalledAddon,
        query: TrackQuery,
        pass: Pass,
        request: StreamRequest,
        paced: Boolean = false,
        download: Boolean = false,
    ): Streamed {
        val key = TidalClient.cacheKey(query)
        val rid = rowId(addon.id)
        // The lossless pass of this play already had a lossy answer: use it, ask nothing.
        if (pass == Pass.LOSSY) {
            held.take(rid, key, nowMs())?.let { (info, ref) ->
                if (request.allows(info)) {
                    Log.i(TAG, "held answer used ${addon.id}")
                    return Streamed.Ok(info, ref)
                }
            }
        }
        val ref = when (val f = find(addon, query, pass, paced)) {
            is Found.Hit -> f.ref
            Found.Miss -> return Streamed.Miss
            Found.Unavailable -> return Streamed.Unavailable
        }
        val answer = if (download && addon.offers(Resource.DOWNLOAD)) {
            client.download(addon, ref.remoteId, request, paced)
        } else {
            client.stream(addon, ref.remoteId, request, paced)
        }
        return when (answer) {
            is AddonResult.Ok -> Streamed.Ok(answer.value, ref)
            is AddonResult.Failed -> {
                val f = answer.failure
                when {
                    f is AddonFailure.Refused && f.code == ErrorCode.NOT_FOUND -> {
                        // The add-on no longer knows that id: the ref is dropped, the next play looks again.
                        Log.d(TAG, "ref dropped ${addon.id} (not_found)")
                        write(rid) { refs.delete(rid, key) }
                        Streamed.Miss
                    }
                    f is AddonFailure.Refused && (f.code == ErrorCode.QUALITY_UNAVAILABLE || f.code == ErrorCode.REGION_UNAVAILABLE) -> {
                        stampMiss(rid, key, pass)
                        if (pass == Pass.LOSSLESS) f.held?.let { held.put(rid, key, it.info, ref, nowMs()) }
                        Streamed.Miss
                    }
                    f is AddonFailure.InvalidResponse && f.reason == AddonEclipse.PRIVATE_ADDRESS -> {
                        Log.i(TAG, "addon:${addon.id} stream refused (private address)")
                        Streamed.Unavailable
                    }
                    else -> Streamed.Unavailable
                }
            }
        }
    }

    /** A play stopped waiting for [addon]: counted against it like a timeout ([HttpAddonClient.reportTooSlow]). */
    suspend fun tooSlow(addon: InstalledAddon) = client.reportTooSlow(addon.id)

    /** Forgets every ref of [addonId], so a later add-on with the same id never inherits them (phase 4 §2.4). */
    suspend fun forget(addonId: String) {
        val rid = rowId(addonId)
        held.forget(rid)
        write(rid) { refs.deleteFor(rid) }
    }

    // ---------------------------------------------------------------------------------------------- lookups

    private suspend fun viaResolve(addon: InstalledAddon, rid: String, query: TrackQuery, key: String, pass: Pass, paced: Boolean, row: AddonRefEntity?): Found {
        val descriptor = Descriptor(
            title = query.title,
            artist = query.artist,
            album = query.album,
            durationMs = query.durationMs?.takeIf { query.durationReliable && it > 0 },
            isrc = query.isrc,
            explicit = query.explicit,
        )
        // Rule 7: the lossless pass asks for a recording it can stream lossless, or none.
        val minTier = pass.minTier.takeIf { it > Tier.LOW }
        return when (val r = client.resolve(addon, ResolveQuery(descriptor, minTier = minTier), paced)) {
            is AddonResult.Ok -> {
                val item = r.value
                if (item == null) {
                    if (minTier == null) {
                        write(rid) { refs.upsert(AddonRefEntity(rid, key, remoteId = null, resolvedAt = nowMs())) }
                    } else {
                        stampMiss(rid, key, pass, row)
                    }
                    Found.Miss
                } else {
                    val conf = if (!query.isrc.isNullOrBlank() && item.isrc.equals(query.isrc, ignoreCase = true)) 1f else RESOLVE_CONFIDENCE
                    Found.Hit(keep(addon, rid, key, item, conf))
                }
            }
            is AddonResult.Failed -> if (r.failure.isMiss) {
                stampMiss(rid, key, pass, row)
                Found.Miss
            } else {
                Found.Unavailable
            }
        }
    }

    private suspend fun viaSearch(addon: InstalledAddon, rid: String, query: TrackQuery, key: String, paced: Boolean): Found {
        val rows = when (val r = client.search(addon, "${query.artist} ${query.title}".trim(), limit = SEARCH_LIMIT, paced = paced)) {
            is AddonResult.Ok -> r.value.tracks
            is AddonResult.Failed -> return if (r.failure.isMiss) noMatch(rid, key) else Found.Unavailable
        }
        // The row's own recording when the add-on lists it: a twin matching on words and length
        // would otherwise beat it (the Tidal rung learnt this, TidalClient.fetch).
        val sameRecording = query.isrc?.takeIf { it.isNotBlank() }
            ?.let { isrc -> rows.filter { it.isrc.equals(isrc, ignoreCase = true) } }
            .orEmpty()
        val best = QobuzCandidateMatcher.pickBest(
            query,
            sameRecording.ifEmpty { rows }.map { it to confidence(query, it) },
            explicit = { it.explicit },
        ) { t -> t.durationMs?.let { ((it + 500) / 1000).toInt() } ?: 0 }
        if (best == null) {
            Log.i(TAG, "no confident match among ${rows.size} on ${addon.id}")
            return noMatch(rid, key)
        }
        val (item, conf) = best
        Log.i(TAG, "matched by search ${addon.id} conf=$conf")
        return Found.Hit(keep(addon, rid, key, item, conf))
    }

    private suspend fun noMatch(addonId: String, key: String): Found {
        write(addonId) { refs.upsert(AddonRefEntity(addonId, key, remoteId = null, resolvedAt = nowMs())) }
        return Found.Miss
    }

    private suspend fun keep(addon: InstalledAddon, addonId: String, key: String, item: Track, confidence: Float): Ref {
        val art = item.artwork?.maxByOrNull { it.width ?: 0 }?.url?.takeIf { keepsArtwork(addon) }
        write(addonId) {
            refs.upsert(
                AddonRefEntity(
                    addonId = addonId,
                    songKey = key,
                    remoteId = item.id,
                    title = item.title,
                    artist = item.artist,
                    artwork = art,
                    confidence = confidence,
                    resolvedAt = nowMs(),
                ),
            )
        }
        return Ref(item.id, art, item.isrc, confidence)
    }

    private fun confidence(query: TrackQuery, t: Track): Float =
        QobuzCandidateMatcher.confidence(
            query = query,
            candTitle = t.title,
            candArtist = t.artist,
            candIsrc = t.isrc,
            candDurationSec = t.durationMs?.let { ((it + 500) / 1000).toInt() } ?: 0,
            candStreamable = true,
            candAlbum = t.album?.takeIf { it.isNotBlank() },
        )

    // ---------------------------------------------------------------------------------------------- stamps

    /** A pass's miss: `lossless_miss_at` for the lossless pass, `aac_miss_at` (the lossy stamp) for the lossy one. */
    private suspend fun stampMiss(addonId: String, key: String, pass: Pass, current: AddonRefEntity? = null) {
        val now = nowMs()
        val row = current ?: read(addonId, key) ?: AddonRefEntity(addonId, key, remoteId = null, resolvedAt = 0L)
        write(addonId) { refs.upsert(if (pass == Pass.LOSSLESS) row.copy(losslessMissAt = now) else row.copy(aacMissAt = now)) }
    }

    private fun missFresh(row: AddonRefEntity, pass: Pass, now: Long): Boolean {
        val at = (if (pass == Pass.LOSSLESS) row.losslessMissAt else row.aacMissAt) ?: return false
        return now - at < TidalClient.MISS_TTL_MS
    }

    /** Add-ons whose old rows were pruned this run: once each, on the first write. */
    private val pruned = ConcurrentHashMap.newKeySet<String>()

    private suspend fun read(addonId: String, key: String): AddonRefEntity? = try {
        refs.get(addonId, key)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "ref read failed: ${e.javaClass.simpleName}")
        null
    }

    private suspend fun write(addonId: String, block: suspend () -> Unit) {
        try {
            block()
            if (pruned.add(addonId)) refs.deleteOlderThan(addonId, nowMs() - TidalClient.MATCH_TTL_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ref write failed: ${e.javaClass.simpleName}")
        }
    }

    // ---------------------------------------------------------------------------------------------- held answers

    /** Out-of-range answers kept for the lossy pass of the same play: short-lived, in memory only. */
    private val held = HeldAnswers()

    internal class HeldAnswers {
        private data class Entry(val info: StreamInfo, val ref: Ref, val atMs: Long)

        private val map = ConcurrentHashMap<String, Entry>()

        fun put(addonId: String, key: String, info: StreamInfo, ref: Ref, now: Long) {
            if (map.size > MAX_HELD) map.entries.removeIf { now - it.value.atMs > HELD_TTL_MS }
            map["$addonId|$key"] = Entry(info, ref, now)
        }

        /** The held answer, once: a stream URL is for one play. Null when none, or too old or expiring. */
        fun take(addonId: String, key: String, now: Long): Pair<StreamInfo, Ref>? {
            val e = map.remove("$addonId|$key") ?: return null
            if (now - e.atMs > HELD_TTL_MS) return null
            val expires = e.info.expiresAt
            if (expires != null && expires * 1000 - 60_000 <= now) return null
            return e.info to e.ref
        }

        fun forget(addonId: String) {
            map.keys.removeIf { it.startsWith("$addonId|") }
        }
    }

    companion object {
        private const val TAG = "AddonRefs"

        /**
         * The `addon_id` an add-on's rows are kept under: its manifest id, except the id the Tidal
         * rung keeps its own rows under ([TidalClient.REFS_ADDON_ID]), which a third-party add-on
         * may also call itself. Its rows go under `addon:tidal`, so neither ever reads, or removes,
         * the other's.
         */
        internal fun rowId(addonId: String): String =
            if (addonId == TidalClient.REFS_ADDON_ID) "addon:$addonId" else addonId

        /**
         * Whether [addon]'s cover art is used at all (review-882 B1 and M7). Art is an image URL
         * the add-on chose, fetched by the image loader on the app's ordinary client (none of the
         * base rules) and written to the song's row, where it would outlive the add-on: a tracker
         * on every later showing of the song, and, when the add-on serves art under its own
         * tokenised base, the user's token in the library database and every backup of it.
         *
         * So art is taken only from add-ons Stash vouches for: the signed config's defaults. An
         * add-on the user added from a link gives none; the song keeps the art it has. (Part 2 of
         * phase 4 gives add-ons a trust level: this is then "not `Trust.UNOFFICIAL`".) The Tidal
         * rung and the user's own Tidal server never come through here and keep theirs.
         */
        fun keepsArtwork(addon: InstalledAddon): Boolean = !addon.userAdded

        /** `/resolve` answers the exact recording or nothing (SPEC.md 4.3): as sure as a matched search. */
        const val RESOLVE_CONFIDENCE = 0.9f

        /** Rows a search asks for: enough for a song's versions, small enough for a phone. */
        const val SEARCH_LIMIT = 20

        /** How long a held answer waits for the lossy pass: the same play only. */
        const val HELD_TTL_MS = 2 * 60_000L

        private const val MAX_HELD = 64
    }
}
