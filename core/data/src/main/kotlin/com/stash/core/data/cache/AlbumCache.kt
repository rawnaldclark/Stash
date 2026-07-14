package com.stash.core.data.cache

import com.stash.core.data.discography.QobuzAlbumFetcher
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.AlbumDetail
import com.stash.data.ytmusic.model.AlbumSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * [QobuzAlbumFetcher] (Qobuz album id). Entries are keyed by `"$source:$id"`
 * so a numeric Qobuz album id can never collide with a YT browseId.
 */
@Singleton
open class AlbumCache @Inject constructor(
    private val api: YTMusicApiClient,
    private val qobuzFetcher: QobuzAlbumFetcher,
) {
    private data class Entry(val detail: AlbumDetail, val fetchedAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val keyLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun get(id: String, source: AlbumSource = AlbumSource.YOUTUBE): AlbumDetail {
        val key = cacheKey(id, source)
        val cached = entries[key]
        if (cached != null && !isStale(cached)) return cached.detail

        // Serialize per-key fetches so concurrent gets for the same album
        // result in exactly one network call.
        val lock = keyLocks.computeIfAbsent(key) { Mutex() }
        return lock.withLock {
            // Re-check after acquiring the lock — maybe someone else filled it.
            val afterLock = entries[key]
            if (afterLock != null && !isStale(afterLock)) return@withLock afterLock.detail

            val fresh = when (source) {
                AlbumSource.QOBUZ -> qobuzFetcher.getAlbum(id)
                AlbumSource.QOBUZ_PLAYLIST -> qobuzFetcher.getPlaylist(id)
                AlbumSource.YOUTUBE -> api.getAlbum(id)
            }
            entries[key] = Entry(fresh, now())
            fresh
        }
    }

    fun invalidate(id: String, source: AlbumSource = AlbumSource.YOUTUBE) {
        entries.remove(cacheKey(id, source))
    }

    private fun cacheKey(id: String, source: AlbumSource) = "$source:$id"

    private fun isStale(entry: Entry): Boolean =
        now() - entry.fetchedAt > TTL_MS

    internal open fun now(): Long = System.currentTimeMillis()

    companion object {
        internal const val TTL_MS = 30 * 60_000L
    }
}
