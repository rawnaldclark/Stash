package com.stash.core.data.cache

import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.AlbumDetail
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
 */
@Singleton
open class AlbumCache @Inject constructor(
    private val api: YTMusicApiClient,
) {
    private data class Entry(val detail: AlbumDetail, val fetchedAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val keyLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun get(browseId: String): AlbumDetail {
        val cached = entries[browseId]
        if (cached != null && !isStale(cached)) return cached.detail

        // Serialize per-key fetches so concurrent gets for the same album
        // result in exactly one network call.
        val lock = keyLocks.computeIfAbsent(browseId) { Mutex() }
        return lock.withLock {
            // Re-check after acquiring the lock — maybe someone else filled it.
            val afterLock = entries[browseId]
            if (afterLock != null && !isStale(afterLock)) return@withLock afterLock.detail

            val fresh = api.getAlbum(browseId)
            entries[browseId] = Entry(fresh, now())
            fresh
        }
    }

    fun invalidate(browseId: String) {
        entries.remove(browseId)
    }

    private fun isStale(entry: Entry): Boolean =
        now() - entry.fetchedAt > TTL_MS

    internal open fun now(): Long = System.currentTimeMillis()

    companion object {
        internal const val TTL_MS = 30 * 60_000L
    }
}
