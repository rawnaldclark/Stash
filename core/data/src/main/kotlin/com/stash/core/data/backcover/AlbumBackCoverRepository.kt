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

/** The back covers Now Playing flips to, one per album. */
interface AlbumBackCovers {
    /** The album's back cover URL as stored: null until it is looked up, and when it has none. */
    fun observe(album: BackCoverAlbum): Flow<String?>

    /** Looks the album up unless its answer is stored. Returns its back cover URL, null when none. */
    suspend fun resolve(album: BackCoverAlbum): String?

    /** No back covers: the default of a ViewModel under test. */
    object None : AlbumBackCovers {
        override fun observe(album: BackCoverAlbum): Flow<String?> = flowOf(null)
        override suspend fun resolve(album: BackCoverAlbum): String? = null
    }
}

/**
 * Finds an album's back cover on the Cover Art Archive and keeps the answer in `album_back_covers`.
 *
 * The lookup (measured 2026-10-24: a back cover for 24 of 25 popular albums, and for the main
 * edition or the first vinyl of every Beatles album tried):
 * 1. The album's release group: from the song's ISRC when it has one (one search, and sure of the
 *    recording), else a title search by artist ([releaseGroupFromSearch]), the plain title first.
 * 2. The release group's cover, which names the album's main edition, and that edition's listing:
 *    its back is the likeliest to match the front on screen, and usually ends the lookup here, at
 *    three or four calls. A group with no art at all ends it sooner.
 * 3. Else its other releases, vinyl first ([rankReleasesForBack]), [PARALLEL_CHECKS] at a time, up
 *    to [MAX_RELEASES_CHECKED], read in rank order and stopping at the first back.
 *
 * A miss is stored and retried after [MISS_TTL_MS] (scans are uploaded all the time); a found back
 * is kept. A failure (offline, MusicBrainz or the archive down, a reply that can't be read) stores
 * nothing and holds the album back for [FAILURE_BACKOFF_MS], so a flaky network doesn't repeat the
 * walk on every song. Concurrent calls for one album (the mini player's and Now Playing's
 * ViewModels) run it once.
 */
@Singleton
class AlbumBackCoverRepository @Inject constructor(
    private val dao: AlbumBackCoverDao,
    private val musicBrainz: MusicBrainzClient,
    private val coverArt: CoverArtArchiveClient,
) : AlbumBackCovers {

    /** Test seam: the clock misses and failures are aged on. */
    internal var now: () -> Long = System::currentTimeMillis

    private val keyLocks = ConcurrentHashMap<String, Mutex>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    override fun observe(album: BackCoverAlbum): Flow<String?> =
        dao.observe(album.key).map { it?.imageUrl }.distinctUntilChanged()

    override suspend fun resolve(album: BackCoverAlbum): String? {
        val key = album.key
        stored(key)?.let { return it.imageUrl }
        val lock = keyLocks.computeIfAbsent(key) { Mutex() }
        try {
            return lock.withLock {
                // Another caller may have finished the walk while this one waited.
                stored(key)?.let { return@withLock it.imageUrl }
                val failed = failedAt[key]
                if (failed != null && now() - failed < FAILURE_BACKOFF_MS) return@withLock null
                val found = try {
                    lookUp(album)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // LookupFailed, or anything a malformed reply set off: never the app's crash.
                    failedAt[key] = now()
                    return@withLock null
                }
                failedAt.remove(key)
                dao.upsert(AlbumBackCoverEntity(key, found?.url, found?.releaseMbid, now()))
                found?.url
            }
        } finally {
            if (!lock.isLocked) keyLocks.remove(key, lock)
        }
    }

    /** The stored answer when it still stands: a back cover, or a miss younger than [MISS_TTL_MS]. */
    private suspend fun stored(key: String): AlbumBackCoverEntity? =
        dao.get(key)?.takeIf { it.imageUrl != null || now() - it.attemptedAt < MISS_TTL_MS }

    private data class Found(val url: String, val releaseMbid: String)

    /** A call failed: nothing can be concluded about the album. */
    private class LookupFailed : Exception()

    /** The album's back cover, null when it has none. Throws [LookupFailed]. */
    private suspend fun lookUp(album: BackCoverAlbum): Found? {
        val group = releaseGroupOf(album) ?: return null
        val cover = coverArt.releaseGroup(group) ?: throw LookupFailed()
        if (cover.images.isEmpty() && cover.releaseMbid == null) return null // no art anywhere in it
        val main = cover.releaseMbid
        // The main edition alone first: when it has a back, its releases needn't even be listed.
        val mainListing = main?.let { coverArt.release(it) }
        mainListing?.let { listing -> backCoverImage(listing)?.let { return Found(it.url, main) } }
        val releases = musicBrainz.browseReleases(group) ?: throw LookupFailed()
        val candidates = rankReleasesForBack(releases).filter { it != main }.take(MAX_RELEASES_CHECKED)
        var answered = mainListing != null
        for (batch in candidates.chunked(PARALLEL_CHECKS)) {
            val hit = coroutineScope {
                val pending = batch.map { id -> id to async { coverArt.release(id) } }
                var found: Found? = null
                // In rank order, so a better-ranked back wins over a faster reply; the first back
                // cancels the slower listings still out.
                for ((id, listing) in pending) {
                    val answer = listing.await() ?: continue
                    answered = true
                    backCoverImage(answer)?.let { found = Found(it.url, id) }
                    if (found != null) break
                }
                pending.forEach { it.second.cancel() }
                found
            }
            if (hit != null) return hit
        }
        if (!answered && (main != null || candidates.isNotEmpty())) throw LookupFailed()
        return null
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
        /** A miss is looked up again after this long: 30 days. */
        const val MISS_TTL_MS = 30L * 24 * 60 * 60 * 1000

        /** After a failed lookup, the album waits this long before another: 2 minutes. */
        const val FAILURE_BACKOFF_MS = 2L * 60 * 1000

        /** Releases whose listings are fetched at once. */
        const val PARALLEL_CHECKS = 4

        /** Releases checked for a back at most (an album like Rumours has over a hundred). */
        const val MAX_RELEASES_CHECKED = 12
    }
}
