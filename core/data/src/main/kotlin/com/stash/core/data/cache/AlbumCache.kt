package com.stash.core.data.cache

import com.stash.core.data.discography.QobuzAlbumFetcher
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.AlbumDetail
import com.stash.data.ytmusic.model.AlbumSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory album cache with per-key mutex to prevent duplicate concurrent
 * fetches. Entries expire after [TTL_MS]. No Room persistence — albums are
 * near-static; the tradeoff for not surviving process death is zero migration
 * cost and zero on-disk state.
 *
 * Routes by [AlbumSource]: [AlbumSource.YOUTUBE] albums load from
 * [YTMusicApiClient.getAlbum] (browseId), [AlbumSource.QOBUZ] albums from
 * [QobuzAlbumFetcher] (Qobuz album id), [AlbumSource.YOUTUBE_PLAYLIST]
 * playlists from [YTMusicApiClient.getPlaylistAlbum] (`VL…`). Entries are keyed by `"$source:$id"`
 * so a numeric Qobuz album id can never collide with a YT browseId.
 */
@Singleton
open class AlbumCache @Inject constructor(
    private val api: YTMusicApiClient,
    private val qobuzFetcher: QobuzAlbumFetcher,
) {
    private data class Entry(val detail: AlbumDetail, val fetchedAt: Long)

    /**
     * Access-order LRU of at most [MAX_ENTRIES], guarded by `synchronized`.
     * Unbounded, every album and Qobuz playlist opened stayed in memory for
     * the life of the process, TTL or not, and a media app's process lives
     * for days. Same shape as [ArtistCache]'s memory tier.
     */
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_ENTRIES
    }
    private val keyLocks = ConcurrentHashMap<String, Mutex>()

    private fun cached(key: String): Entry? = synchronized(entries) { entries[key] }

    suspend fun get(id: String, source: AlbumSource = AlbumSource.YOUTUBE): AlbumDetail {
        val key = cacheKey(id, source)
        val cached = cached(key)
        if (cached != null && !isStale(cached)) return cached.detail

        // Serialize per-key fetches so concurrent gets for the same album
        // result in exactly one network call.
        val lock = keyLocks.computeIfAbsent(key) { Mutex() }
        try {
            return fetchLocked(lock, key, id, source)
        } finally {
            // Drop the lock once nobody holds or awaits it, so the lock map
            // does not grow with every album ever opened. A get racing this
            // removal at worst fetches once more; it never sees a wrong album.
            if (!lock.isLocked) keyLocks.remove(key, lock)
        }
    }

    private suspend fun fetchLocked(lock: Mutex, key: String, id: String, source: AlbumSource): AlbumDetail =
        lock.withLock {
            // Re-check after acquiring the lock — maybe someone else filled it.
            val afterLock = cached(key)
            if (afterLock != null && !isStale(afterLock)) return@withLock afterLock.detail

            val fresh = when (source) {
                AlbumSource.QOBUZ -> qobuzFetcher.getAlbum(id)
                AlbumSource.QOBUZ_PLAYLIST -> qobuzFetcher.getPlaylist(id)
                // A playlist that didn't answer, or answered without songs, is a failed load like an album's below
                AlbumSource.YOUTUBE_PLAYLIST -> api.getPlaylistAlbum(id).also {
                    if (it.tracks.isEmpty()) throw IOException("YouTube Music didn't return playlist $id")
                }
                AlbumSource.YOUTUBE -> api.getAlbum(id).also {
                    // getAlbum answers a browse that didn't come back with a blank,
                    // track-less album. Kept, that was an empty page with no Retry
                    // for TTL_MS; it is a failed load, so the next get asks again.
                    if (it.title.isBlank() && it.tracks.isEmpty()) {
                        throw IOException("YouTube Music didn't return album $id")
                    }
                }
            }
            synchronized(entries) { entries[key] = Entry(fresh, now()) }
            fresh
        }

    fun invalidate(id: String, source: AlbumSource = AlbumSource.YOUTUBE) {
        synchronized(entries) { entries.remove(cacheKey(id, source)) }
    }

    private fun cacheKey(id: String, source: AlbumSource) = "$source:$id"

    private fun isStale(entry: Entry): Boolean =
        now() - entry.fetchedAt > TTL_MS

    internal open fun now(): Long = System.currentTimeMillis()

    companion object {
        internal const val TTL_MS = 30 * 60_000L

        /** Albums kept in memory; well past a browsing session's back stack. */
        internal const val MAX_ENTRIES = 50
    }
}
