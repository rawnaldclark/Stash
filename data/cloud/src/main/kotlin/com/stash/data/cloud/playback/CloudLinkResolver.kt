package com.stash.data.cloud.playback

import com.stash.core.model.CloudProvider
import com.stash.core.model.CloudRef
import com.stash.data.cloud.net.RequestPriority
import com.stash.data.cloud.provider.CloudApiException
import com.stash.data.cloud.provider.CloudLink
import com.stash.data.cloud.provider.CloudStorageProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived links per cloud file, keyed by provider + account + item (never by URL).
 *
 * Every seek makes ExoPlayer reopen its source, and a song is opened again by prefetch, Cast and
 * downloads; asking the provider each time would spend the rate limit on links we already have.
 * An entry lives for its provider's [CloudStorageProvider.linkTtlMs] (OneDrive ~5 min, Dropbox
 * 3.5 h of a 4 h link). [invalidate] drops one the content host just refused.
 */
class CloudLinkCache(private val clock: () -> Long = System::currentTimeMillis) {
    private val entries = ConcurrentHashMap<String, CloudLink>()

    fun get(key: String, ttlMs: Long): CloudLink? {
        val link = entries[key] ?: return null
        if (clock() - link.fetchedAtMs >= ttlMs) {
            entries.remove(key, link)
            return null
        }
        return link
    }

    fun put(key: String, link: CloudLink) {
        entries[key] = link
        if (entries.size > MAX_ENTRIES) {
            // Oldest-first trim; a queue of a few hundred songs never reaches this.
            entries.entries.sortedBy { it.value.fetchedAtMs }.take(entries.size - MAX_ENTRIES).forEach { entries.remove(it.key) }
        }
    }

    fun invalidate(key: String) {
        entries.remove(key)
    }

    companion object {
        const val MAX_ENTRIES = 512

        fun keyOf(ref: CloudRef): String = "${ref.provider.id}:${ref.accountId}:${ref.itemId}"
    }
}

/**
 * Turns a [CloudRef] into a playable/downloadable [CloudLink] through the right provider, with
 * caching and per-file single-flight (three opens of one song at once make one request).
 */
class CloudLinkResolver(
    private val providers: Map<CloudProvider, CloudStorageProvider>,
    private val cache: CloudLinkCache = CloudLinkCache(),
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun resolve(ref: CloudRef, priority: RequestPriority = RequestPriority.INTERACTIVE, fresh: Boolean = false): CloudLink {
        val provider = providers[ref.provider]?.takeIf { it.isConfigured }
            ?: throw CloudApiException(0, "${ref.provider.displayName} isn't available in this build")
        val key = CloudLinkCache.keyOf(ref)
        return locks.getOrPut(key) { Mutex() }.withLock {
            if (fresh) cache.invalidate(key)
            cache.get(key, provider.linkTtlMs) ?: provider.link(ref.accountId, ref.itemId, priority).also { cache.put(key, it) }
        }
    }

    /** Forget the cached link for [ref] (the content host answered 401/403/404/410). */
    fun invalidate(ref: CloudRef) = cache.invalidate(CloudLinkCache.keyOf(ref))
}
