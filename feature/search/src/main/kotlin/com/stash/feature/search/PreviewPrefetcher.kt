package com.stash.feature.search

import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import com.stash.data.download.preview.PreviewUrlCache
import com.stash.data.download.preview.PreviewUrlExtractor
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Warms [PreviewUrlExtractor] for a list of `videoId`s so that a later
 * `extractStreamUrl` call from the UI resolves instantly.
 *
 * [PreviewUrlExtractor] already owns its own InnerTube + yt-dlp semaphores,
 * so this class only orchestrates launches — it does NOT spin up its own
 * concurrency limiter. Dedupe is done against the shared [previewUrlCache]
 * that the UI/VM layer reads from.
 *
 * ### Constructor pattern (Hilt + tests)
 *
 * Hilt requires exactly one `@Inject`-annotated constructor per type. Kotlin
 * permits `@Inject` on a **secondary** constructor: the compiler emits a plain
 * Java constructor that Hilt's processor picks up. We use that pattern so:
 *
 *  - Production DI resolves [PreviewUrlExtractor] + [PreviewUrlCache] via the
 *    secondary `@Inject` constructor, which delegates to the primary using
 *    [PreviewUrlCache.asMutableMap] so the VM and the prefetcher share one
 *    cache (see Task 9 spec §9.3c).
 *  - Tests call the primary constructor directly to inject a test cache and
 *    a [CoroutineScope] tied to `runTest`'s scheduler so launched prefetch
 *    jobs can be drained deterministically via `advanceUntilIdle()`.
 */
class PreviewPrefetcher(
    private val extractor: PreviewUrlExtractor,
    private val previewUrlCache: MutableMap<String, String>,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /**
     * Hilt-visible entry point. Delegates to the primary constructor using
     * the shared [PreviewUrlCache]'s backing map so any URL prefetched here
     * is immediately visible to every [SearchViewModel] / `ArtistProfileViewModel`
     * that reads the cache.
     */
    @Inject
    constructor(extractor: PreviewUrlExtractor, cache: PreviewUrlCache) :
        this(extractor, cache.asMutableMap)

    private val jobs = mutableListOf<Job>()

    /**
     * Launches one coroutine per unique `videoId` not already present in
     * [previewUrlCache]. Failures are logged and swallowed so one bad id can
     * never bring down the prefetch pipeline (nor the enclosing [scope],
     * thanks to `SupervisorJob`). [CancellationException] is re-thrown so
     * structured-concurrency cancellation still propagates correctly.
     */
    fun prefetch(videoIds: List<String>) {
        videoIds
            .filter { it !in previewUrlCache }
            .forEach { id ->
                val job = scope.launch {
                    try {
                        previewUrlCache[id] = extractor.extractStreamUrl(id)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        Log.w(TAG, "prefetch fail $id: ${t.message}")
                    }
                }
                jobs.add(job)
            }
    }

    /**
     * Prefetches the first track of each visible row with a ±3 look-ahead
     * around the viewport so the user can tap slightly off-screen items and
     * still hit a warm cache.
     */
    fun prefetchVisible(listState: LazyListState, items: List<TrackSummary>) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty() || items.isEmpty()) return
        val first = (visible.first().index - 3).coerceAtLeast(0)
        val last = (visible.last().index + 3).coerceAtMost(items.lastIndex)
        if (first > last) return
        prefetch(items.subList(first, last + 1).map { it.videoId })
    }

    /** Cancels every tracked prefetch job and clears the tracking list. */
    fun cancelAll() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    companion object {
        private const val TAG = "PreviewPrefetcher"
    }
}
