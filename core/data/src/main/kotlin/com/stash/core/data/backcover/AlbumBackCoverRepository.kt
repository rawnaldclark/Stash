package com.stash.core.data.backcover

import com.stash.core.data.db.dao.AlbumBackCoverDao
import com.stash.core.data.db.entity.AlbumBackCoverEntity
import com.stash.core.data.musicbrainz.MusicBrainzClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 * The lookup, about 4 to 12 calls (measured 2026-10-24: a back cover for 24 of 25 popular albums):
 * 1. The album's release group: from the song's ISRC when it has one (one search, and sure of the
 *    recording), else a title search by artist ([releaseGroupFromSearch]).
 * 2. The release group's cover, which names the album's main edition: its back is checked first,
 *    as the most likely to match the front on screen. A group with no art at all ends here.
 * 3. Its other releases, vinyl first ([rankReleasesForBack]), [PARALLEL_CHECKS] at a time, up to
 *    [MAX_RELEASES_CHECKED].
 *
 * A miss is stored and retried after [MISS_TTL_MS] (scans are uploaded all the time); a found back
 * is kept. A failure (offline, MusicBrainz or the archive down) stores nothing and holds the album
 * back for [FAILURE_BACKOFF_MS], so a flaky network doesn't repeat the walk on every song.
 * Concurrent calls for one album (the mini player's and Now Playing's ViewModels) run it once.
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
                } catch (e: LookupFailed) {
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
        val releases = musicBrainz.browseReleases(group) ?: throw LookupFailed()
        val candidates = (listOfNotNull(cover.releaseMbid) + rankReleasesForBack(releases))
            .distinct()
            .take(MAX_RELEASES_CHECKED)
        var answered = false
        for (batch in candidates.chunked(PARALLEL_CHECKS)) {
            val listings = coroutineScope {
                batch.map { id -> async { id to coverArt.release(id) } }.awaitAll()
            }
            // In rank order, so the main edition's back wins over a faster reply.
            for ((id, listing) in listings) {
                if (listing == null) continue
                answered = true
                backCoverImage(listing)?.let { return Found(it.url, id) }
            }
        }
        if (!answered && candidates.isNotEmpty()) throw LookupFailed()
        return null
    }

    /** The album's MusicBrainz release group: by the song's ISRC first, then by title. Throws [LookupFailed]. */
    private suspend fun releaseGroupOf(album: BackCoverAlbum): String? {
        if (album.isrc != null) {
            val recordings = musicBrainz.searchRecordingsByIsrc(album.isrc) ?: throw LookupFailed()
            releaseGroupFromIsrcSearch(recordings, album.title)?.let { return it }
        }
        val hits = musicBrainz.searchReleaseGroups(album.artist, album.title) ?: throw LookupFailed()
        releaseGroupFromSearch(hits, album.title, album.artist)?.let { return it }
        // A phrase search needs every word: "Rumours (Super Deluxe)" doesn't find "Rumours".
        val plain = plainAlbumTitle(album.title)
        if (plain == album.title) return null
        val plainHits = musicBrainz.searchReleaseGroups(album.artist, plain) ?: throw LookupFailed()
        return releaseGroupFromSearch(plainHits, album.title, album.artist)
    }

    companion object {
        /** A miss is looked up again after this long: 30 days. */
        const val MISS_TTL_MS = 30L * 24 * 60 * 60 * 1000

        /** After a failed lookup, the album waits this long before another: 10 minutes. */
        const val FAILURE_BACKOFF_MS = 10L * 60 * 1000

        /** Releases whose listings are fetched at once. */
        const val PARALLEL_CHECKS = 4

        /** Releases checked for a back at most (an album like Rumours has over a hundred). */
        const val MAX_RELEASES_CHECKED = 12
    }
}
