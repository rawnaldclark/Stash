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
import com.stash.core.addons.MediaRefusal
import com.stash.core.addons.Trust
import com.stash.core.data.db.dao.AddonRefDao
import com.stash.core.data.db.entity.AddonRefEntity
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.qobuz.QobuzCandidateMatcher
import com.stash.data.download.lossless.tidal.TidalClient
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
 *     as the Tidal rung scores its rows, rows with the song's ISRC first. A row that states no
 *     length is no candidate ([statesLength]), whatever is known of the song's own: the matcher
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

    /**
     * The add-on's own item for a song: [remoteId] is what `/stream` takes. [durationMs]: the
     * item's own length, when this lookup made the match and the item states one (a ref read back
     * from the table has none: there is no column for it).
     */
    data class Ref(val remoteId: String, val artwork: String?, val isrc: String?, val confidence: Float, val durationMs: Long? = null) {
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
        // Its stream was refused on this network's DNS a moment ago (round 3, R3): neither pass asks.
        if (networkMisses["$rid|$key"]?.let { now - it < TidalClient.MISS_TTL_MS } == true) return Found.Miss
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
            // A download that may not search remembers that miss here instead, in memory and for
            // downloads only (round 2, n8): every retry pass asked `/resolve-isrc` again.
            val downloadOnly = isrcOnly && searches && !bySearch
            if (downloadOnly && isrcMisses["$rid|$key"]?.let { now - it < TidalClient.MISS_TTL_MS } == true) return Found.Unavailable
            val found = viaResolve(addon, rid, query, key, pass, paced, row, stampMisses = !(isrcOnly && searches))
            if (downloadOnly && found == Found.Miss) {
                if (isrcMisses.size >= MAX_ISRC_MISSES) isrcMisses.clear()
                isrcMisses["$rid|$key"] = now
            }
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
                    answered(info.url, addon.id, rid, key)
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
            is AddonResult.Ok -> {
                answered(answer.value.url, addon.id, rid, key)
                Streamed.Ok(answer.value, ref)
            }
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

    /** A play stopped waiting for [addon]: counted against it like a timeout ([HttpAddonClient.reportTooSlow]). */
    suspend fun tooSlow(addon: InstalledAddon) = client.reportTooSlow(addon.id)

    // ---------------------------------------------------------------------------------------------- refused on the wire

    /** A stream an add-on answered, by its URL: whose it is, for [refusedOnTheWire]. Memory only. */
    private data class Answered(val addonId: String, val rid: String, val key: String, val atMs: Long)

    private val answeredStreams = ConcurrentHashMap<String, Answered>()

    private fun answered(url: String, addonId: String, rid: String, key: String) {
        val now = nowMs()
        if (answeredStreams.size >= MAX_ANSWERED) {
            answeredStreams.entries.removeIf { now - it.value.atMs > ANSWERED_TTL_MS }
            // Still full (a long sync): the oldest quarter goes, and the newest stay, among them
            // the playing song's. Clearing it all forgot that one too, and its refusal then
            // counted for nothing (review-882 round 3, nit 6).
            if (answeredStreams.size >= MAX_ANSWERED) {
                answeredStreams.entries.sortedBy { it.value.atMs }
                    .take(answeredStreams.size - MAX_ANSWERED + MAX_ANSWERED / 4)
                    .forEach { answeredStreams.remove(it.key, it.value) }
            }
        }
        answeredStreams[streamKey(url)] = Answered(addonId, rid, key, now)
    }

    /**
     * A stream's URL as the media client names it when it reports a refusal: OkHttp's own
     * spelling of it (a default port dropped, the host in lower case), which is not always the
     * text the add-on sent.
     */
    private fun streamKey(url: String): String = url.toHttpUrlOrNull()?.toString() ?: url

    /**
     * The add-on media client refused to fetch the stream at [streamUrl] (review-882 round 2, F7):
     * it pointed at the user's own network, through a redirect, a name that resolves there or a
     * DASH segment, or it broke the URL rule. The refusal comes after the rung has "served", so
     * without this the song failed on every play for the 30 days its ref lives, though a later
     * source could play it. The ref is dropped and both passes miss: the refusal is about where
     * the add-on keeps the song, not about a quality. A URL this class did not hand out (or
     * handed out too long ago to remember) changes nothing.
     *
     * How long the miss holds follows [why]. A refusal of the URL itself ([MediaRefusal.URL]) is
     * true on any network: both passes are stamped in the table, for 14 days. A host whose every
     * DNS answer was private ([MediaRefusal.NO_PUBLIC_ADDRESS]) is what this network's resolver
     * said, and a blocker or a work network's DNS says it of a public CDN too: the miss is kept
     * in memory only, as n8's is, and what the row said of either pass stays (round 3, R3).
     */
    suspend fun refusedOnTheWire(streamUrl: String, why: MediaRefusal) {
        val stream = answeredStreams.remove(streamKey(streamUrl)) ?: return
        Log.i(TAG, "media refused on the wire ${stream.addonId} (${why.name.lowercase()}): its ref is dropped and the song is left to the next source")
        held.forgetSong(stream.rid, stream.key)
        val now = nowMs()
        when (why) {
            MediaRefusal.URL -> write(stream.rid) {
                refs.upsert(AddonRefEntity(stream.rid, stream.key, remoteId = null, resolvedAt = 0L, losslessMissAt = now, aacMissAt = now))
            }
            MediaRefusal.NO_PUBLIC_ADDRESS -> {
                if (networkMisses.size >= MAX_NETWORK_MISSES) networkMisses.clear()
                networkMisses["${stream.rid}|${stream.key}"] = now
                // The ref goes; a miss either pass had on record stays (as for `not_found`).
                val stamps = read(stream.rid, stream.key)?.takeIf { it.losslessMissAt != null || it.aacMissAt != null }
                write(stream.rid) {
                    if (stamps == null) {
                        refs.delete(stream.rid, stream.key)
                    } else {
                        refs.upsert(
                            AddonRefEntity(stream.rid, stream.key, remoteId = null, resolvedAt = 0L, losslessMissAt = stamps.losslessMissAt, aacMissAt = stamps.aacMissAt),
                        )
                    }
                }
            }
        }
    }

    /**
     * A song whose stream from an add-on was refused because its host had no public DNS answer
     * here ([refusedOnTheWire]), by add-on and song: when. Both passes miss while it holds. Memory
     * only: the next start, perhaps on another network, asks the add-on again.
     */
    private val networkMisses = ConcurrentHashMap<String, Long>()

    /** Forgets every ref of [addonId], so a later add-on with the same id never inherits them (phase 4 §2.4). */
    suspend fun forget(addonId: String) {
        val rid = rowId(addonId)
        held.forget(rid)
        answeredStreams.values.removeIf { it.rid == rid }
        isrcMisses.keys.removeIf { it.startsWith("$rid|") }
        networkMisses.keys.removeIf { it.startsWith("$rid|") }
        write(rid) { refs.deleteFor(rid) }
    }

    /** A download's `/resolve-isrc` miss from an add-on that can also search, by add-on and song: when ([find]). Memory only. */
    private val isrcMisses = ConcurrentHashMap<String, Long>()

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
        return Ref(item.id, art, item.isrc, confidence, item.durationMs?.takeIf { it > 0 })
    }

    /**
     * Whether [t] may be matched to [query] at all (review-882 J4). A row must state its own
     * length, so the matcher's length rule has something to judge: with none it scores the length
     * as perfect, and title and artist words alone decide (a 30-second preview, a live take that
     * doesn't say so, another artist's song of the same name). That holds whatever is known of the
     * song's own length (round 2): one that is not trusted, a video's, is still held loosely
     * against the row's, and words alone are not a match for any add-on: one the user added and
     * the signed config's defaults alike (round 3, nit 2). The one thing that places a row
     * without a length is the song's own ISRC.
     */
    private fun statesLength(query: TrackQuery, t: Track): Boolean {
        if ((t.durationMs ?: 0L) > 0) return true
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

        fun forgetSong(addonId: String, key: String) {
            map.remove("$addonId|$key")
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
         * So art is taken only from add-ons Stash vouches for ([Trust]): the signed config's
         * defaults, and an add-on of the signed index, which is Official whoever added it. An
         * add-on Stash doesn't run gives none; the song keeps the art it has. The Tidal rung and
         * the user's own Tidal server never come through here and keep theirs.
         */
        fun keepsArtwork(addon: InstalledAddon): Boolean = addon.trust != Trust.UNOFFICIAL

        /**
         * Whether a lookup for [addon] may match by search (review-882 J3): always for a play; for
         * a download only when Stash vouches for the add-on (a default of the signed config).
         */
        fun matchesBySearch(addon: InstalledAddon, download: Boolean): Boolean = !download || !addon.userAdded

        /** `/resolve` answers the exact recording or nothing (SPEC.md 4.3): as sure as a matched search. */
        const val RESOLVE_CONFIDENCE = 0.9f

        /** Rows a search asks for: enough for a song's versions, small enough for a phone. */
        const val SEARCH_LIMIT = 20

        /** How many download misses of ISRC-only resolvers are remembered ([find]); past it the memory starts over. */
        private const val MAX_ISRC_MISSES = 4_096

        /** How many songs a refusal by this network's DNS is remembered for ([refusedOnTheWire]). */
        private const val MAX_NETWORK_MISSES = 4_096

        /** How many answered streams are remembered for [refusedOnTheWire], and for how long (a stream's URL lives an hour at most). */
        private const val MAX_ANSWERED = 512
        private const val ANSWERED_TTL_MS = 60 * 60_000L

        /** How long a held answer waits for the lossy pass: the same play only. */
        const val HELD_TTL_MS = 2 * 60_000L

        private const val MAX_HELD = 64
    }
}
