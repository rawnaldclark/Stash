package com.stash.core.data.backcover

import com.stash.core.data.db.dao.AlbumBackCoverDao
import com.stash.core.data.db.entity.AlbumBackCoverEntity
import com.stash.core.data.musicbrainz.MusicBrainzClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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
 */
data class AlbumCoverArt(val back: String?, val inside: List<String>)

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
 * Finds an album's back cover and inside pages on the Cover Art Archive and keeps the answer in
 * `album_back_covers`.
 *
 * The lookup (measured 2026-10-24: a back cover for 24 of 25 popular albums, and for the main
 * edition or the first vinyl of every Beatles album tried):
 * 1. The album's release group: from the song's ISRC when it has one (one search, and sure of the
 *    recording), else a title search by artist ([releaseGroupFromSearch]), the plain title first.
 * 2. The release group's cover, which names the album's main edition, and that edition's listing:
 *    its back is the likeliest to match the front on screen. When it has both a back and inside
 *    pages, the lookup ends here, at three or four calls. A group with no art at all ends it sooner.
 * 3. Else its other releases, vinyl first ([rankReleasesForBack]), [PARALLEL_CHECKS] at a time, up
 *    to [MAX_RELEASES_CHECKED], read in rank order until it has both: the first back and the first
 *    inside pages ([insidePages]), which may come from different releases.
 *
 * The back is stored as soon as it is found, with the inside "not looked for yet", so the flip is
 * ready while the walk goes on; a walk cut short (a skip to another album) finishes the next time
 * the album plays. A full answer is kept; a partial one (no back, or nothing inside) is looked up
 * again after [MISS_TTL_MS] (scans are uploaded all the time). A failure (offline, MusicBrainz or
 * the archive down, a reply that can't be read) stores nothing new and holds the album back for
 * [FAILURE_BACKOFF_MS], so a flaky network doesn't repeat the walk on every song. Concurrent calls
 * for one album (the mini player's and Now Playing's ViewModels) run it once.
 */
@Singleton
class AlbumBackCoverRepository @Inject constructor(
    private val dao: AlbumBackCoverDao,
    private val musicBrainz: MusicBrainzClient,
    private val coverArt: CoverArtArchiveClient,
) : AlbumBackCovers {

    /** Test seam: the clock partial answers and failures are aged on. */
    internal var now: () -> Long = System::currentTimeMillis

    private val keyLocks = ConcurrentHashMap<String, Mutex>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    override fun observe(album: BackCoverAlbum): Flow<AlbumCoverArt?> =
        dao.observe(album.key).map { it?.toArt() }.distinctUntilChanged()

    override suspend fun resolve(album: BackCoverAlbum): AlbumCoverArt? {
        val key = album.key
        stored(key)?.let { return it.toArt() }
        val lock = keyLocks.computeIfAbsent(key) { Mutex() }
        try {
            return lock.withLock {
                // Another caller may have finished the walk while this one waited.
                stored(key)?.let { return@withLock it.toArt() }
                val failed = failedAt[key]
                if (failed != null && now() - failed < FAILURE_BACKOFF_MS) return@withLock null
                val found = try {
                    lookUp(album) { back ->
                        dao.upsert(AlbumBackCoverEntity(key, back.url, back.releaseMbid, now(), insideUrls = null))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // LookupFailed, or anything a malformed reply set off: never the app's crash.
                    failedAt[key] = now()
                    return@withLock null
                }
                failedAt.remove(key)
                val row = AlbumBackCoverEntity(
                    albumKey = key,
                    imageUrl = found.back?.url,
                    releaseMbid = found.back?.releaseMbid,
                    attemptedAt = now(),
                    insideUrls = found.inside.joinToString("\n"),
                )
                dao.upsert(row)
                row.toArt()
            }
        } finally {
            if (!lock.isLocked) keyLocks.remove(key, lock)
        }
    }

    /**
     * The stored answer when it still stands: a full one (a back and inside pages), or a partial
     * one younger than [MISS_TTL_MS]. Not one whose inside was never looked for.
     */
    private suspend fun stored(key: String): AlbumBackCoverEntity? =
        dao.get(key)?.takeIf { row ->
            val inside = row.insideUrls ?: return@takeIf false
            (row.imageUrl != null && inside.isNotEmpty()) || now() - row.attemptedAt < MISS_TTL_MS
        }

    private fun AlbumBackCoverEntity.toArt() = AlbumCoverArt(
        back = imageUrl,
        inside = insideUrls?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
    )

    private data class Found(val url: String, val releaseMbid: String)

    private class Walk(val back: Found?, val inside: List<String>)

    /** A call failed: nothing can be concluded about the album. */
    private class LookupFailed : Exception()

    /**
     * The album's back and inside pages, empty when it has neither. [onBack] gets the back the
     * moment it is found, while the walk goes on for the inside. Throws [LookupFailed].
     */
    private suspend fun lookUp(album: BackCoverAlbum, onBack: suspend (Found) -> Unit): Walk {
        val group = releaseGroupOf(album) ?: return Walk(null, emptyList())
        val cover = coverArt.releaseGroup(group) ?: throw LookupFailed()
        if (cover.images.isEmpty() && cover.releaseMbid == null) return Walk(null, emptyList())

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
        val main = cover.releaseMbid
        main?.let { id -> coverArt.release(id)?.let { read(id, it) } }
        if (done()) return Walk(back, inside.orEmpty())

        val releases = musicBrainz.browseReleases(group) ?: throw LookupFailed()
        val candidates = rankReleasesForBack(releases).filter { it != main }.take(MAX_RELEASES_CHECKED)
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
        if (!answered && (main != null || candidates.isNotEmpty())) throw LookupFailed()
        return Walk(back, inside.orEmpty())
    }

    /** The album's MusicBrainz release group: by the song's ISRC first, then by title. Throws [LookupFailed]. */
    private suspend fun releaseGroupOf(album: BackCoverAlbum): String? {
        if (album.isrc != null) {
            val recordings = musicBrainz.searchRecordingsByIsrc(album.isrc) ?: throw LookupFailed()
            releaseGroupFromIsrcSearch(recordings, album.title)?.let { return it }
        }
        // The plain title first, one search for most albums: a phrase search needs every word, so
        // "Abbey Road (Remastered)" finds nothing that "Abbey Road" doesn't. The full title only if
        // the plain one finds no album of that name.
        val plain = plainAlbumTitle(album.title)
        val hits = musicBrainz.searchReleaseGroups(album.artist, plain) ?: throw LookupFailed()
        releaseGroupFromSearch(hits, album.title, album.artist)?.let { return it }
        if (plain == album.title) return null
        val fullHits = musicBrainz.searchReleaseGroups(album.artist, album.title) ?: throw LookupFailed()
        return releaseGroupFromSearch(fullHits, album.title, album.artist)
    }

    companion object {
        /** A partial answer (no back, or nothing inside) is looked up again after this long: 30 days. */
        const val MISS_TTL_MS = 30L * 24 * 60 * 60 * 1000

        /** After a failed lookup, the album waits this long before another: 2 minutes. */
        const val FAILURE_BACKOFF_MS = 2L * 60 * 1000

        /** Releases whose listings are fetched at once. */
        const val PARALLEL_CHECKS = 4

        /** Releases checked at most (an album like Rumours has over a hundred). */
        const val MAX_RELEASES_CHECKED = 12

        /** Inside pages kept at most: a box set's booklet can run past a hundred. */
        const val MAX_INSIDE_PAGES = 40
    }
}
