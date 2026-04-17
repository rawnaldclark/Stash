package com.stash.data.download.preview

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide preview-URL cache shared by [PreviewPrefetcher] and any
 * ViewModel that kicks a preview. Backed by a [ConcurrentHashMap] so
 * multiple VMs can read/write from different coroutines safely.
 *
 * The cache is intentionally unbounded — preview URLs are small strings
 * and the cache is cleared on process death. If we ever need a size cap
 * we'll swap in an `LruCache` behind the same public API.
 *
 * Introduced as a Task 8 follow-up: the prefetcher and the search VM used
 * to hold independent local `mutableMapOf` caches, so prefetched URLs
 * were invisible to the VM (and vice versa). Now the single `@Singleton`
 * instance is injected into both call sites.
 */
@Singleton
class PreviewUrlCache @Inject constructor() {
    private val map: MutableMap<String, String> = ConcurrentHashMap()

    operator fun get(id: String): String? = map[id]
    operator fun set(id: String, url: String) { map[id] = url }
    operator fun contains(id: String): Boolean = id in map
    fun clear() { map.clear() }

    /**
     * Exposed map view used by `PreviewPrefetcher`'s primary constructor so
     * the prefetcher and the cache share the same backing storage. Marked
     * `@PublishedApi internal`-style (public so Kotlin's module-boundary
     * `internal` doesn't block cross-module access) but the public surface
     * ([get]/[set]/[contains]/[clear]) is what callers should normally use.
     */
    val asMutableMap: MutableMap<String, String> get() = map
}
