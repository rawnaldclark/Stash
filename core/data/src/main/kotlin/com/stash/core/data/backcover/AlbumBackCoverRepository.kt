package com.stash.core.data.backcover

import com.stash.core.data.db.dao.AlbumBackCoverDao
import com.stash.core.data.db.entity.AlbumBackCoverEntity
import com.stash.core.data.musicbrainz.MusicBrainzClient
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An album's art beyond its front, as image URLs: its [back] cover (null when it has none) and the
 * pages [inside] it (booklet, gatefold panels, inner sleeve), in reading order.
 *
 * Both come from the Cover Art Archive (scans, 1200px) when it has them, else from Discogs (photos
 * and scans by its users, 600px at most): [backFromDiscogs] and [insideFromDiscogs] say which.
 * Discogs' API terms ask for "Data provided by Discogs", linked to [discogsPage] (the release the
 * images come from), next to its images; null when none are shown.
 */
data class AlbumCoverArt(
    val back: String?,
    val inside: List<String>,
    val backFromDiscogs: Boolean = false,
    val insideFromDiscogs: Boolean = false,
    val discogsPage: String? = null,
)

/** The back covers Now Playing flips to and the pages inside them, one set per album. */
interface AlbumBackCovers {
    /** The album's art as stored: null until it is first looked up. */
    fun observe(album: BackCoverAlbum): Flow<AlbumCoverArt?>

    /**
     * Looks the album up unless its answer is stored, and returns it: null when the lookup failed.
     * The back is stored the moment it is found ([observe] sees it), before the walk ends.
     */
    suspend fun resolve(album: BackCoverAlbum): AlbumCoverArt?

    /** No art: the default of a ViewModel under test. */
    object None : AlbumBackCovers {
        override fun observe(album: BackCoverAlbum): Flow<AlbumCoverArt?> = flowOf(null)
        override suspend fun resolve(album: BackCoverAlbum): AlbumCoverArt? = null
    }
}

/**
 * Finds an album's back cover and inside pages on the Cover Art Archive, and on Discogs for what the
 * archive lacks, and keeps the answer in `album_back_covers`.
 *
 * The lookup (measured 2026-10-24: a back cover for 24 of 25 popular albums, and for the main
 * edition or the first vinyl of every Beatles album tried):
 * 1. The album's release group: from the song's ISRC when it has one (one search, and sure of the
 *    recording), else a title search by artist ([releaseGroupFromSearch]), the plain title first.
 * 2. The release group's cover, which names the album's main edition, and that edition's listing:
 *    its back is the likeliest to match the front on screen. When it has both a back and inside
 *    pages, the lookup ends here, at three or four calls. A group with no art at all goes to step 4.
 * 3. Else its other releases, vinyl first ([rankReleasesForBack]), [PARALLEL_CHECKS] at a time, up
 *    to [MAX_RELEASES_CHECKED], read in rank order until it has both: the first back and the first
 *    inside pages ([insidePages]), which may come from different releases.
 * 4. When the archive has no back, or nothing inside: Discogs ([discogsArt]), reached through the
 *    release group's link to its Discogs master (30 of 30 sampled albums had one, 2026-10-24). Its
 *    most-collected vinyl (else CD) release gives what the archive lacked: the back by
 *    [discogsBackIndex] (right for 83% of releases measured), the inside by [discogsInside].
 *
 * The back is stored as soon as it is found, with the inside "not looked for yet", so the flip is
 * ready while the walk goes on; a walk cut short (a skip to another album) finishes the next time
 * the album plays. A full answer from the archive is kept; a partial one (no back, or nothing
 * inside) is looked up again after [MISS_TTL_MS], since scans are uploaded all the time. What came
 * from Discogs is forgotten after [DISCOGS_TTL_MS] (its API terms: no Discogs data older than six
 * hours), the album then looked up again when it next plays. A failure (offline, MusicBrainz or
 * the archive down, a reply that can't be read) stores nothing new and holds the album back for
 * [FAILURE_BACKOFF_MS], so a flaky network doesn't repeat the walk on every song. Concurrent calls
 * for one album (the mini player's and Now Playing's ViewModels) run it once.
 */
@Singleton
class AlbumBackCoverRepository @Inject constructor(
    private val dao: AlbumBackCoverDao,
    private val musicBrainz: MusicBrainzClient,
    private val coverArt: CoverArtArchiveClient,
    private val discogs: DiscogsClient,
) : AlbumBackCovers {

    /** Test seam: the clock partial answers and failures are aged on. */
    internal var now: () -> Long = System::currentTimeMillis

    private val keyLocks = ConcurrentHashMap<String, Mutex>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    override fun observe(album: BackCoverAlbum): Flow<AlbumCoverArt?> = flow {
        // Never a stale Discogs image on screen: forgotten before the row is read.
        forgetStaleDiscogs()
        emitAll(dao.observe(album.key).map { it?.toArt() })
    }.distinctUntilChanged()

    private suspend fun forgetStaleDiscogs() = dao.forgetDiscogsBefore(now() - DISCOGS_TTL_MS)

    override suspend fun resolve(album: BackCoverAlbum): AlbumCoverArt? {
        val key = album.key
        forgetStaleDiscogs()
        stored(key)?.let { return it.toArt() }
        val lock = keyLocks.computeIfAbsent(key) { Mutex() }
        try {
            return lock.withLock {
                // Another caller may have finished the walk while this one waited.
                stored(key)?.let { return@withLock it.toArt() }
                val failed = failedAt[key]
                if (failed != null && now() - failed < FAILURE_BACKOFF_MS) return@withLock null
                val startedNs = System.nanoTime()
                val found = try {
                    lookUp(album) { back ->
                        dao.upsert(AlbumBackCoverEntity(key, back.url, back.releaseMbid, now(), insideUrls = null))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // LookupFailed, or anything a malformed reply set off: never the app's crash.
                    android.util.Log.w(LOG_TAG, "${album.artist} - ${album.title}: lookup failed (${e.javaClass.simpleName})")
                    failedAt[key] = now()
                    return@withLock null
                }
                failedAt.remove(key)
                android.util.Log.i(
                    LOG_TAG,
                    "${album.artist} - ${album.title}: back=${found.back != null}${if (found.backFromDiscogs) " (Discogs)" else ""}, " +
                        "inside=${found.inside.size}${if (found.insideFromDiscogs) " (Discogs)" else ""} " +
                        "in ${(System.nanoTime() - startedNs) / 1_000_000} ms",
                )
                val row = AlbumBackCoverEntity(
                    albumKey = key,
                    imageUrl = found.back?.url,
                    releaseMbid = found.back?.releaseMbid,
                    attemptedAt = now(),
                    insideUrls = found.inside.joinToString("\n"),
                    backFromDiscogs = found.backFromDiscogs,
                    insideFromDiscogs = found.insideFromDiscogs,
                    discogsPage = found.discogsPage,
                )
                dao.upsert(row)
                row.toArt()
            }
        } finally {
            if (!lock.isLocked) keyLocks.remove(key, lock)
        }
    }

    /**
     * The stored answer when it still stands: a full one from the archive (a back and inside
     * pages), or any other younger than [MISS_TTL_MS] (one with Discogs in it is cleared at
     * [DISCOGS_TTL_MS]: [forgetStaleDiscogs]). Not one whose inside was never looked for.
     */
    private suspend fun stored(key: String): AlbumBackCoverEntity? =
        dao.get(key)?.takeIf { row ->
            val inside = row.insideUrls ?: return@takeIf false
            val full = row.imageUrl != null && inside.isNotEmpty() && !row.backFromDiscogs && !row.insideFromDiscogs
            // A partial answer from before the lookup last learned to find more is asked again.
            full || (row.attemptedAt >= MATCHING_CHANGED_AT && now() - row.attemptedAt < MISS_TTL_MS)
        }

    private fun AlbumBackCoverEntity.toArt() = AlbumCoverArt(
        back = imageUrl,
        inside = insideUrls?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
        backFromDiscogs = backFromDiscogs && imageUrl != null,
        insideFromDiscogs = insideFromDiscogs,
        discogsPage = discogsPage,
    )

    /** A back: its URL, and the MusicBrainz release it was scanned from (null for Discogs'). */
    private data class Found(val url: String, val releaseMbid: String?)

    private class Walk(
        val back: Found?,
        val inside: List<String>,
        val backFromDiscogs: Boolean = false,
        val insideFromDiscogs: Boolean = false,
        val discogsPage: String? = null,
    )

    /** What a Discogs release adds: a likely back, the rest of its images, and its page. */
    private class DiscogsArt(val back: String?, val inside: List<String>, val page: String?)

    /** A call failed: nothing can be concluded about the album. */
    private class LookupFailed : Exception()

    private fun lookupFailed(step: String): LookupFailed {
        android.util.Log.w(LOG_TAG, "$step failed")
        return LookupFailed()
    }

    /**
     * The album's back and inside pages, empty when it has neither. [onBack] gets the back the
     * moment it is found, while the walk goes on for the inside. Throws [LookupFailed].
     */
    private suspend fun lookUp(album: BackCoverAlbum, onBack: suspend (Found) -> Unit): Walk {
        val group = releaseGroupOf(album) ?: return Walk(null, emptyList())
        return coroutineScope { walkGroup(group, onBack) }
    }

    private suspend fun CoroutineScope.walkGroup(group: String, onBack: suspend (Found) -> Unit): Walk {
        // The group's releases are listed while the archive is asked for its main edition (the
        // archive is the slow one, 1-6 s through archive.org): cancelled if the main edition
        // answers it all.
        val browse = async { musicBrainz.browseReleases(group) }
        // The group's listing only names the main edition. archive.org fails it now and then (half
        // the calls for Four Tops' Reach Out, 2026-10-25, retries and all): then the releases are
        // checked without it.
        val cover = coverArt.releaseGroup(group)
        if (cover == null) android.util.Log.w(LOG_TAG, "archive release group $group failed: checking its releases")
        // No art at all on the archive: Discogs alone.
        if (cover != null && cover.images.isEmpty() && cover.releaseMbid == null) {
            return withDiscogs(group, browse.await(), null, null)
        }

        var back: Found? = null
        var inside: List<String>? = null
        var answered = false
        suspend fun read(id: String, listing: CaaListing) {
            answered = true
            var foundBack = false
            if (back == null) {
                backCoverImage(listing)?.let {
                    back = Found(it.url, id)
                    foundBack = true
                }
            }
            if (inside == null) {
                insidePages(listing).takeIf { it.isNotEmpty() }?.let { pages ->
                    inside = pages.take(MAX_INSIDE_PAGES).map { it.url }
                }
            }
            // A back that ends the walk is stored once, with the inside, at its end.
            if (foundBack && inside == null) back?.let { onBack(it) }
        }
        fun done() = back != null && inside != null

        // The main edition alone first: when it has both, its releases needn't even be listed.
        val main = cover?.releaseMbid
        main?.let { id -> coverArt.release(id)?.let { read(id, it) } }
        if (done()) {
            browse.cancel()
            return Walk(back, inside.orEmpty())
        }

        val releases = browse.await() ?: throw lookupFailed("MusicBrainz releases of $group")
        // Releases MusicBrainz says hold no more than a front aren't asked about: on Reach Out,
        // seven of its eight official releases are skipped this way (2026-10-25).
        val bare = releasesWithoutMoreArt(releases)
        val candidates = rankReleasesForBack(releases).filter { it != main && it !in bare }.take(MAX_RELEASES_CHECKED)
        for (batch in candidates.chunked(PARALLEL_CHECKS)) {
            coroutineScope {
                val pending = batch.map { id -> id to async { coverArt.release(id) } }
                // In rank order, so a better-ranked release wins over a faster reply; once both are
                // in hand, the slower listings still out are cancelled.
                for ((id, listing) in pending) {
                    listing.await()?.let { read(id, it) }
                    if (done()) break
                }
                pending.forEach { it.second.cancel() }
            }
            if (done()) break
        }
        if (!answered && (main != null || candidates.isNotEmpty())) throw lookupFailed("every archive listing of $group")
        return withDiscogs(group, releases, back, inside)
    }

    /**
     * The archive's [back] and [inside] (null: none found), with Discogs' standing in for what's
     * missing. Throws [LookupFailed] when MusicBrainz or Discogs fails: a back already found was
     * stored by then ([lookUp]'s onBack), and the rest is tried again after the backoff.
     */
    private suspend fun withDiscogs(group: String, releases: JsonObject?, back: Found?, inside: List<String>?): Walk {
        if (back != null && inside != null) return Walk(back, inside)
        val found = discogsArt(group, releases)
        val discogsBack = found?.back?.takeIf { back == null }
        val discogsInside = found?.inside?.takeIf { inside == null && it.isNotEmpty() }
        return Walk(
            back = back ?: discogsBack?.let { Found(it, releaseMbid = null) },
            inside = inside ?: discogsInside.orEmpty(),
            backFromDiscogs = discogsBack != null,
            insideFromDiscogs = discogsInside != null,
            discogsPage = found?.page?.takeIf { discogsBack != null || discogsInside != null },
        )
    }

    /**
     * The album on Discogs: its Discogs master, that master's most-collected vinyl, else CD
     * ([mostCollected]), and from its images the back ([discogsBackIndex]) and the rest
     * ([discogsInside]). The master is the release group's MusicBrainz link, else the master of a
     * Discogs release one of the group's [releases] links to ([discogsReleaseIds]; browsed here when
     * the walk didn't), else that release alone. Null when nothing links to Discogs or the master
     * has no physical release. Two MusicBrainz calls at most and two to four Discogs calls, plus two
     * thumbnails when the back is in doubt ([needsSeamCheck]). Throws [LookupFailed].
     */
    private suspend fun discogsArt(group: String, releases: JsonObject?): DiscogsArt? {
        val rels = musicBrainz.releaseGroupUrlRels(group) ?: throw lookupFailed("MusicBrainz links of $group")
        var linked: DiscogsRelease? = null
        val master = discogsMasterId(rels) ?: run {
            val all = releases ?: musicBrainz.browseReleases(group) ?: throw LookupFailed()
            val id = discogsReleaseIds(all).firstOrNull() ?: return null
            val release = discogs.release(id) ?: throw LookupFailed()
            linked = release
            release.masterId ?: return artOf(release)
        }
        val id = mostCollected(discogs.versions(master, "Vinyl") ?: throw LookupFailed())
            ?: mostCollected(discogs.versions(master, "CD") ?: throw LookupFailed())
            ?: return linked?.let { artOf(it) }
        val release = linked?.takeIf { it.id == id } ?: discogs.release(id) ?: throw LookupFailed()
        return artOf(release)
    }

    /** A Discogs release's back and inside pages, and its page. Throws [LookupFailed]. */
    private suspend fun artOf(release: DiscogsRelease): DiscogsArt {
        val halvesJoin = needsSeamCheck(release) && run {
            val left = discogs.pixels(release.images[1].thumb!!) ?: throw LookupFailed()
            val right = discogs.pixels(release.images[2].thumb!!) ?: throw LookupFailed()
            seamGap(left, right) < HALVES_JOIN_BELOW
        }
        val backIndex = discogsBackIndex(release, halvesJoin)
        return DiscogsArt(
            back = backIndex?.let { release.images[it].url },
            inside = discogsInside(release, backIndex).take(MAX_INSIDE_PAGES).map { it.url },
            page = release.page,
        )
    }

    /** The album's MusicBrainz release group: by the song's ISRC first, then by title. Throws [LookupFailed]. */
    private suspend fun releaseGroupOf(album: BackCoverAlbum): String? {
        if (album.isrc != null) {
            val recordings = musicBrainz.searchRecordingsByIsrc(album.isrc) ?: throw lookupFailed("MusicBrainz ISRC search")
            releaseGroupFromIsrcSearch(recordings, album.title)?.let { return it }
        }
        // The plain title first, one search for most albums: a phrase search needs every word, so
        // "Abbey Road (Remastered)" finds nothing that "Abbey Road" doesn't. The full title only if
        // the plain one finds no album of that name.
        val plain = plainAlbumTitle(album.title)
        val hits = musicBrainz.searchReleaseGroups(album.artist, plain) ?: throw lookupFailed("MusicBrainz title search")
        releaseGroupFromSearch(hits, album.title, album.artist)?.let { return it }
        if (plain != album.title) {
            val fullHits = musicBrainz.searchReleaseGroups(album.artist, album.title) ?: throw LookupFailed()
            releaseGroupFromSearch(fullHits, album.title, album.artist)?.let { return it }
        }
        // An edition titled apart from its album ("OK Computer OKNOTOK 1997 2017") is no release
        // group's title, but its releases' title: one more search, for the albums found no other way.
        val releases = musicBrainz.searchReleases(album.artist, album.title) ?: throw lookupFailed("MusicBrainz release search")
        return releaseGroupFromReleaseSearch(releases, album.title, album.artist)
    }

    companion object {
        /** A partial answer (no back, or nothing inside) is looked up again after this long: 30 days. */
        const val MISS_TTL_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * When the lookup last learned to find more (2026-10-25: titles with an edition or a span of
         * years at the end, editions titled apart from their album, the releases' Discogs links): a partial
         * answer stored before it is looked up again, not held for [MISS_TTL_MS]. Moved forward
         * whenever the matching finds albums it used to miss.
         */
        const val MATCHING_CHANGED_AT = 1_792_915_108_672L

        /** What came from Discogs is kept this long at most: 6 hours, its API terms' limit. */
        const val DISCOGS_TTL_MS = 6L * 60 * 60 * 1000

        /** After a failed lookup, the album waits this long before another: 2 minutes. */
        const val FAILURE_BACKOFF_MS = 2L * 60 * 1000

        /** Releases whose listings are fetched at once. */
        const val PARALLEL_CHECKS = 4

        /** Releases checked at most (an album like Rumours has over a hundred). */
        const val MAX_RELEASES_CHECKED = 12

        /** Inside pages kept at most: a box set's booklet can run past a hundred. */
        const val MAX_INSIDE_PAGES = 40

        private const val LOG_TAG = "BackCover"
    }
}
