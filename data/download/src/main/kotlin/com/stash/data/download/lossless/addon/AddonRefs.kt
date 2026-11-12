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
import com.stash.core.addons.AddonDialect
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
 *     v1 promises the same song or nothing (SPEC.md 4.3). An Eclipse-family `/resolve` never
 *     made that promise, so its item is checked like a search row: [statesLength], then the
 *     matcher's own floor.
 *  3. **Matching on the phone**, for one that can only search (every BitChord-style add-on, most
 *     Eclipse ones): `/search "<artist> <title>"`, scored by the app's own [QobuzCandidateMatcher]
 *     as the Tidal rung scores its rows, rows with the song's ISRC first. When the song's own
 *     length is known, a row that states no length is no candidate ([statesLength]): the matcher
 *     was tuned on catalogs whose rows always carry one, and words alone pass too easily.
 *
 * **Matching by search is for plays only** when the user added the add-on (add-ons plan phase 4
 * §2.16, review-882 J3): a download asks such an add-on only through its `/resolve` or a ref a
 * play already made ([stream]'s `bySearch`). Otherwise a library sync would send a server Stash
 * doesn't run the artist and title of every song the lossless sources miss.
 *
 * **Then the stream** ([stream]): `/stream/{id}`, or `/download/{id}` for a file to keep. A
 * `not_found` there drops the ref (and, for a ref made a moment ago, stamps the pass: the add-on
 * lists the song and cannot play it); `quality_unavailable` stamps a miss for that pass only (a
 * lossless miss never blocks the lossy pass, as Tidal's AAC stamp works). An answer outside the
 * asked range on the lossless pass is held in memory for the lossy pass of the same play
 * ([HeldAnswers]), so the add-on isn't asked twice. Outages, pauses, rate limits and a search that
 * did not answer are never misses.
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
        /** [fresh]: made by this lookup (a resolve or a search just now), not read from a kept ref. */
        data class Hit(val ref: Ref, val fresh: Boolean = false) : Found

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

    /**
     * The add-on id for [query] on [addon] in [pass]; see the class doc. [paced]: background work
     * (downloads). [bySearch] false: a ref or `/resolve` only, never a search matched on the phone.
     */
    suspend fun find(addon: InstalledAddon, query: TrackQuery, pass: Pass, paced: Boolean = false, bySearch: Boolean = true): Found {
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
        val searches = addon.offers(Resource.SEARCH)
        if (canResolve) {
            // An ISRC miss from an add-on that can also search is not the last word: its search
            // is. Nothing is stamped for it, or a download (which may not search) would close the
            // song to the play that could.
            val found = viaResolve(addon, rid, query, key, pass, paced, row, stampMisses = !(isrcOnly && searches))
            if (!(isrcOnly && found == Found.Miss && searches)) return found
        }
        if (searches && !bySearch) {
            // A download, and an add-on the user added: its search is for plays only (review-882 J3).
            Log.d(TAG, "not searched ${addon.id}: a download asks an add-on the user added by /resolve or a kept ref only")
            return Found.Unavailable
        }
        return if (searches) viaSearch(addon, rid, query, key, pass, paced, row) else Found.Unavailable
    }

    /**
     * [find], then `/download/{id}` (when [download] and the add-on lists it) or `/stream/{id}` at
     * [request], with the stamps and the held answer of the class doc. [bySearch]: as [find];
     * [matchesBySearch] says what a caller passes.
     */
    suspend fun stream(
        addon: InstalledAddon,
        query: TrackQuery,
        pass: Pass,
        request: StreamRequest,
        paced: Boolean = false,
        download: Boolean = false,
        bySearch: Boolean = true,
    ): Streamed {
        val key = TidalClient.cacheKey(query)
        val rid = rowId(addon.id)
        // The lossless pass of this play already had a lossy answer: use it, ask nothing.
        if (pass == Pass.LOSSY) {
            held.take(rid, key, nowMs())?.let { (info, ref) ->
                if (HttpAddonClient.answers(addon.dialect, request, info)) {
                    Log.i(TAG, "held answer used ${addon.id}")
                    return Streamed.Ok(info, ref)
                }
            }
        }
        val hit = when (val f = find(addon, query, pass, paced, bySearch)) {
            is Found.Hit -> f
            Found.Miss -> return Streamed.Miss
            Found.Unavailable -> return Streamed.Unavailable
        }
        val ref = hit.ref
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
                        // The add-on no longer knows that id: the ref is dropped, the next play looks
                        // again. What the row says of either pass stays.
                        Log.d(TAG, "ref dropped ${addon.id} (not_found)")
                        val stamps = read(rid, key)?.let {
                            AddonRefEntity(rid, key, remoteId = null, resolvedAt = 0L, losslessMissAt = it.losslessMissAt, aacMissAt = it.aacMissAt)
                        }
                        write(rid) { refs.delete(rid, key) }
                        when {
                            // A ref made a moment ago and already unknown: the add-on lists the song
                            // and cannot play it (its catalog has it, its upstream doesn't). Remembered
                            // for this pass, or every play would search and ask again (review-882 M3).
                            hit.fresh -> stampMiss(rid, key, pass, stamps)
                            stamps != null && (stamps.losslessMissAt != null || stamps.aacMissAt != null) -> write(rid) { refs.upsert(stamps) }
                        }
                        Streamed.Miss
                    }
                    f is AddonFailure.Refused && (f.code == ErrorCode.QUALITY_UNAVAILABLE || f.code == ErrorCode.REGION_UNAVAILABLE) -> {
                        // An Eclipse-family answer that does not say what it is, refused under Data
                        // saver, is not this song's miss: the same answer plays at the 320 setting,
                        // and the lossy stamp would close that for 14 days (review-882 J5).
                        val unlabelled = pass == Pass.LOSSY && addon.dialect == AddonDialect.ECLIPSE &&
                            f.held?.info?.let(AddonEclipse::tierIsDefault) == true
                        if (!unlabelled) stampMiss(rid, key, pass)
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

    /** Forgets every ref of [addonId], so a later add-on with the same id never inherits them (phase 4 §2.4). */
    suspend fun forget(addonId: String) {
        val rid = rowId(addonId)
        held.forget(rid)
        write(rid) { refs.deleteFor(rid) }
    }

    // ---------------------------------------------------------------------------------------------- lookups

    private suspend fun viaResolve(
        addon: InstalledAddon, rid: String, query: TrackQuery, key: String, pass: Pass, paced: Boolean, row: AddonRefEntity?,
        stampMisses: Boolean = true,
    ): Found {
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
                // An Eclipse-family item is a candidate like any search row; v1's is the song or null.
                val checked = addon.dialect == AddonDialect.ECLIPSE && addon.eclipse?.resolveViaIsrc != true
                val score = r.value?.takeIf { checked }?.let { if (statesLength(query, it)) confidence(query, it) else 0f }
                val item = r.value?.takeIf { score == null || score >= QobuzCandidateMatcher.MIN_CONFIDENCE }
                if (r.value != null && item == null) Log.i(TAG, "resolve item refused ${addon.id}: not the song asked for")
                if (item == null) {
                    if (!stampMisses) {
                        // Nothing kept: the caller goes on to the add-on's search, or leaves it to a play.
                    } else if (minTier == null) {
                        write(rid) { refs.upsert(AddonRefEntity(rid, key, remoteId = null, resolvedAt = nowMs())) }
                    } else {
                        stampMiss(rid, key, pass, row)
                    }
                    Found.Miss
                } else {
                    val conf = if (!query.isrc.isNullOrBlank() && item.isrc.equals(query.isrc, ignoreCase = true)) 1f else score ?: RESOLVE_CONFIDENCE
                    Found.Hit(keep(addon, rid, key, item, conf, otherPassOf = row, pass = pass), fresh = true)
                }
            }
            is AddonResult.Failed -> if (r.failure.isMiss) {
                if (stampMisses) stampMiss(rid, key, pass, row)
                Found.Miss
            } else {
                Found.Unavailable
            }
        }
    }

    private suspend fun viaSearch(
        addon: InstalledAddon, rid: String, query: TrackQuery, key: String, pass: Pass, paced: Boolean, row: AddonRefEntity?,
    ): Found {
        val listed = when (val r = client.search(addon, "${query.artist} ${query.title}".trim(), limit = SEARCH_LIMIT, paced = paced)) {
            is AddonResult.Ok -> r.value.tracks
            // A search has no "no such song" status: its miss is 200 with no rows. Anything else
            // (a 404 is what a path-token server answers for a mistyped or lapsed token, or
            // mid-deploy) is not remembered against the song (review-882 M3).
            is AddonResult.Failed -> return Found.Unavailable
        }
        val rows = listed.filter { statesLength(query, it) }
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
            Log.i(TAG, "no confident match among ${listed.size} on ${addon.id}")
            return noMatch(rid, key)
        }
        val (item, conf) = best
        Log.i(TAG, "matched by search ${addon.id} conf=$conf")
        return Found.Hit(keep(addon, rid, key, item, conf, otherPassOf = row, pass = pass), fresh = true)
    }

    private suspend fun noMatch(addonId: String, key: String): Found {
        write(addonId) { refs.upsert(AddonRefEntity(addonId, key, remoteId = null, resolvedAt = nowMs())) }
        return Found.Miss
    }

    /**
     * Keeps [item] as the song's ref. [otherPassOf]: the row it replaces, whose miss stamp for the
     * pass that is NOT [pass] carries over: finding the song for one pass says nothing new about
     * the other (review-882 N2: a lossy hit dropped the lossless miss, and the next play asked
     * `/stream` for lossless once more).
     */
    private suspend fun keep(
        addon: InstalledAddon, addonId: String, key: String, item: Track, confidence: Float,
        otherPassOf: AddonRefEntity? = null, pass: Pass? = null,
    ): Ref {
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
                    losslessMissAt = otherPassOf?.losslessMissAt.takeIf { pass == Pass.LOSSY },
                    aacMissAt = otherPassOf?.aacMissAt.takeIf { pass == Pass.LOSSLESS },
                ),
            )
        }
        return Ref(item.id, art, item.isrc, confidence)
    }

    /**
     * Whether [t] may be matched to [query] at all (review-882 J4). With a song length that can be
     * trusted, a row must state its own, so the matcher's length rule has something to judge: with
     * none it scores the length as perfect, and title and artist words alone decide (a 30-second
     * preview, a live take that doesn't say so, another artist's song of the same name). The one
     * thing that places a row without a length is the song's own ISRC.
     */
    private fun statesLength(query: TrackQuery, t: Track): Boolean {
        val songKnown = query.durationReliable && (query.durationMs ?: 0L) > 0
        if (!songKnown || (t.durationMs ?: 0L) > 0) return true
        return !query.isrc.isNullOrBlank() && t.isrc.equals(query.isrc, ignoreCase = true)
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

        /**
         * Whether a lookup for [addon] may match by search (review-882 J3): always for a play; for
         * a download only when Stash vouches for the add-on (a default of the signed config).
         */
        fun matchesBySearch(addon: InstalledAddon, download: Boolean): Boolean = !download || !addon.userAdded

        /** `/resolve` answers the exact recording or nothing (SPEC.md 4.3): as sure as a matched search. */
        const val RESOLVE_CONFIDENCE = 0.9f

        /** Rows a search asks for: enough for a song's versions, small enough for a phone. */
        const val SEARCH_LIMIT = 20

        /** How long a held answer waits for the lossy pass: the same play only. */
        const val HELD_TTL_MS = 2 * 60_000L

        private const val MAX_HELD = 64
    }
}
