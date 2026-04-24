# Download-Tap & Preview Latency Fix — Design Spec

**Date:** 2026-04-24
**Status:** Design
**Related reports:** After every search, download + preview buttons on the results list feel unresponsive for ~60s; letting the page idle resolves it.

## Problem

When search results render, `SearchViewModel.prefetchTopN(...)` launches one `extractor.extractStreamUrl(id)` coroutine per top-N track to warm the preview-URL cache. That method races InnerTube (fast, ~1-2s) against yt-dlp (slow, ~15-35s, QuickJS + Python runtime) using `async {…}` for each — **both start simultaneously**. When InnerTube wins (>95% of tracks), `yt.cancel()` fires, but the cancel signal is cooperative and yt-dlp's underlying `YoutubeDL.getInstance().execute(...)` is a **blocking JNI call** that runs to completion anyway.

Symptoms:
- Every search triggers ~10 zombie yt-dlp extractions queueing through `YTDLP_CONCURRENCY = 2` — at most two run concurrently, but each still takes ~15-30s of native CPU
- Main thread starves on context-switch pressure → download button onClicks delayed, preview button onClicks delayed, compose recompositions queued
- Letting the page idle for ~60s drains the queue, UI returns to normal

Problems to solve, per user feedback:
1. Buttons must feel instant (<200ms tap-to-spinner) on every track, every search
2. Preview must start in <2s for tracks InnerTube can serve, without requiring repeat presses
3. Every track must eventually prefetch successfully, including ciphered tracks that need yt-dlp — no silent skip
4. No known-issue-in-waiting: the ~6h InnerTube URL TTL must be handled before it causes stale-cache playback failures

## Goals

- Eliminate the CPU storm caused by zombie yt-dlp extractions
- Preserve yt-dlp as a correctness backstop for tracks InnerTube can't serve (on-demand and prefetch)
- Instrument every failure path so regressions surface in logs, not user reports
- Close the InnerTube URL TTL gap proactively

## Non-goals

- Replacing yt-dlp entirely
- Implementing persistent disk cache for preview URLs (in-memory is fine; process death is frequent enough)
- Redesigning the search/artist-profile UI
- Fixing download-queue concurrency for the actual yt-dlp downloads (separate scope)

## Design

### 1. `PreviewUrlExtractor` — split public API

Two entry points:

```kotlin
/**
 * On-demand extraction. Starts InnerTube immediately, starts yt-dlp only
 * after YTDLP_HEDGE_DELAY_MS (default 500ms). InnerTube wins → yt-dlp
 * never launches. InnerTube slow/fails → yt-dlp picks up with minimal
 * extra latency.
 */
suspend fun extractStreamUrl(videoId: String): String

/**
 * Prefetch-only extraction. InnerTube only, no yt-dlp fallback. Returns
 * null if InnerTube can't produce a direct URL. Caller is responsible
 * for handling nulls (see [PreviewPrefetcher]'s phase-2 fallback).
 */
suspend fun extractForPrefetch(videoId: String): String?
```

`extractViaYtDlpForRetry(id)` (already public) stays — used by `PreviewPrefetcher` phase 2 and by the delegate's ExoPlayer-error retry path.

### 2. Hedged race in `extractStreamUrl`

Replace `race(...)` internals with a delayed-start yt-dlp async:

```kotlin
private suspend fun race(
    videoId: String,
    innerTubeExtract: suspend (String) -> String?,
    ytDlpExtract: suspend (String) -> String,
    itSem: Semaphore,
    ytSem: Semaphore,
    hedgeDelayMs: Long = YTDLP_HEDGE_DELAY_MS,
): String = coroutineScope {
    val inner = async {
        itSem.acquire()
        try {
            runCatching { innerTubeExtract(videoId) }.getOrElse {
                if (it is CancellationException) throw it
                null
            }
        } finally { itSem.release() }
    }
    val yt = async {
        delay(hedgeDelayMs)        // cancellable coroutine sleep, no native work
        ytSem.acquire()
        try { ytDlpExtract(videoId) } finally { ytSem.release() }
    }
    val itResult = inner.await()
    if (itResult != null) {
        yt.cancel(CancellationException("InnerTube won the race"))
        itResult
    } else {
        yt.await()
    }
}
```

Constant: `private const val YTDLP_HEDGE_DELAY_MS = 500L`.

Why it works: `delay(500ms)` is pure coroutine-level sleep. When InnerTube returns in <500ms (typical), `yt.cancel()` fires while yt-dlp is still inside the `delay` — no JNI work has started yet, cancel is clean. Only slow-InnerTube or InnerTube-failure cases pay the hedge penalty (~500ms added to yt-dlp's existing latency).

### 3. `PreviewPrefetcher` — two-phase prefetch

```kotlin
class PreviewPrefetcher(
    private val extractor: PreviewUrlExtractor,
    private val previewUrlCache: MutableMap<String, String>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    // One yt-dlp prefetch at a time across the whole app. Phase-2 work is
    // background — it yields the stage to on-demand yt-dlp calls naturally
    // through the existing ytDlpSemaphore.
    private val ytDlpPrefetchMutex = Mutex()

    fun prefetch(videoIds: List<String>) {
        videoIds
            .filter { it !in previewUrlCache }
            .forEach { id ->
                val job = scope.launch {
                    // Phase 1 — InnerTube-only, fast, up to 8 parallel
                    val url = try {
                        extractor.extractForPrefetch(id)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        Log.w(TAG, "InnerTube prefetch threw for $id: ${t.message}")
                        null
                    }
                    if (url != null) {
                        previewUrlCache[id] = url
                        return@launch
                    }
                    Log.w(TAG, "InnerTube prefetch returned null for $id — queuing yt-dlp fallback")

                    // Phase 2 — serialized yt-dlp prefetch
                    ytDlpPrefetchMutex.withLock {
                        if (id in previewUrlCache) return@launch // filled by on-demand while waiting
                        runCatching { extractor.extractViaYtDlpForRetry(id) }
                            .onSuccess { previewUrlCache[id] = it }
                            .onFailure { t ->
                                if (t is CancellationException) throw t
                                Log.w(TAG, "yt-dlp prefetch failed for $id: ${t.message}")
                            }
                    }
                }
                jobs.add(job)
            }
    }
    // prefetchVisible and cancelAll unchanged
}
```

Invariants:
- Phase 1 fans out to `INNERTUBE_CONCURRENCY = 8` parallel calls (existing semaphore), completes in ~1-2s
- Phase 2 is strictly serial per install (`ytDlpPrefetchMutex`), uses the existing `YTDLP_CONCURRENCY = 2` semaphore inside the extractor — so at most one prefetch yt-dlp runs concurrently with at most one on-demand yt-dlp
- Every failure path logs at `Log.w` — nothing fails silently
- Phase 2 is idempotent (re-check cache after acquiring mutex) — lets on-demand fills short-circuit pending background work

### 4. `PreviewUrlCache` — TTL

Replace `MutableMap<String, String>` storage with a TTL-aware wrapper:

```kotlin
@Singleton
class PreviewUrlCache @Inject constructor() {
    private data class Entry(val url: String, val cachedAtMs: Long)
    private val map: MutableMap<String, Entry> = ConcurrentHashMap()

    operator fun get(id: String): String? {
        val entry = map[id] ?: return null
        if (System.currentTimeMillis() - entry.cachedAtMs > TTL_MS) {
            map.remove(id, entry)  // atomic compare-and-remove
            return null
        }
        return entry.url
    }

    operator fun set(id: String, url: String) {
        map[id] = Entry(url, System.currentTimeMillis())
    }

    operator fun contains(id: String): Boolean = get(id) != null
    fun clear() { map.clear() }

    /**
     * Backing-map view for PreviewPrefetcher's primary constructor. The
     * adapter wraps/unwraps Entry so the prefetcher's raw `map[id] = url`
     * still populates a fresh timestamp.
     */
    val asMutableMap: MutableMap<String, String> get() = object : AbstractMutableMap<String, String>() {
        override fun put(key: String, value: String): String? {
            val prev = this@PreviewUrlCache.map.put(key, Entry(value, System.currentTimeMillis()))
            return prev?.url
        }
        override fun get(key: String): String? = this@PreviewUrlCache[key]
        override fun containsKey(key: String): Boolean = key in this@PreviewUrlCache
        override val entries: MutableSet<MutableMap.MutableEntry<String, String>>
            get() = error("PreviewUrlCache.asMutableMap.entries is not supported")
    }

    companion object {
        private const val TTL_MS = 5L * 60 * 60 * 1000  // 5 hours, safely under YouTube's ~6h expiry
    }
}
```

Implementation note: the `asMutableMap` adapter exists only to keep `PreviewPrefetcher`'s primary constructor signature. The cleaner alternative is migrating `PreviewPrefetcher` to call `previewUrlCache.set(id, url)` / `.get(id)` / `in` directly — implementer's choice during coding.

### 5. Wiring change

`PreviewPrefetcher` calls `extractor.extractForPrefetch(id)` (not `extractStreamUrl`). SearchViewModel's `prefetchTopN` and `prefetchVisible` paths unchanged. On-demand preview path (`TrackActionsDelegate.previewTrack → extractor.extractStreamUrl`) unchanged — it still benefits from the hedged race because that's where `extractStreamUrl` lives.

## Touch points

| File | Change |
|------|--------|
| `data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt` | Add `extractForPrefetch(id): String?`; update `race(...)` to hedge yt-dlp by 500ms; add `YTDLP_HEDGE_DELAY_MS` constant; extend `raceForTest` seam to accept `hedgeDelayMs` parameter so tests can advance virtual time deterministically |
| `feature/search/src/main/kotlin/com/stash/feature/search/PreviewPrefetcher.kt` | Two-phase prefetch body; add `ytDlpPrefetchMutex`; log every failure path |
| `data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlCache.kt` | TTL wrapper (`Entry(url, cachedAtMs)`); expire on read; 5h constant |
| `data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlExtractorTest.kt` (likely exists; extend) | Tests for hedge behavior + `extractForPrefetch` |
| `feature/search/src/test/kotlin/com/stash/feature/search/PreviewPrefetcherTest.kt` | Update to verify phase-1 / phase-2 split + logging + mutex serialization |
| New test: `data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlCacheTtlTest.kt` | TTL eviction behavior |

Estimated: 3 production files + 2-3 test files. One commit per file in TDD flow.

## Testing

### Unit — `PreviewUrlExtractor`

Existing `raceForTest` seam parameterizes the hedge delay via overload / default. Tests:

1. **Hedged race does not start yt-dlp when InnerTube wins quickly.** InnerTube stub returns in 100ms; yt-dlp hook counts invocations → assert 0. Uses `StandardTestDispatcher.advanceTimeBy`.
2. **Hedged race starts yt-dlp after hedge when InnerTube is slow.** InnerTube stub delays 2s; advance past 500ms, assert yt-dlp invoked.
3. **Hedged race falls back to yt-dlp when InnerTube returns null.** Unchanged from today.
4. **Hedged race falls back when InnerTube throws non-cancellation.** Unchanged.
5. **`extractForPrefetch` returns InnerTube URL on success.**
6. **`extractForPrefetch` returns null on InnerTube failure, never invokes yt-dlp.**
7. **`extractForPrefetch` propagates CancellationException.**

### Unit — `PreviewPrefetcher`

1. **Phase 1: ids where InnerTube succeeds land in cache, yt-dlp never invoked.**
2. **Phase 2: ids where phase 1 returns null, yt-dlp invoked and result cached.**
3. **Phase 2 is serialized: two concurrent ciphered ids result in exactly 2 sequential yt-dlp calls, not parallel.**
4. **Phase 2 short-circuits if on-demand filled the cache while phase 1 was running** (simulated by filling cache after `extractForPrefetch` returns null but before the mutex is acquired).
5. **Every failure path logs with the id** (capture via logger hook or logcat inspection in integration test).
6. **`cancelAll()` cancels phase 2 work cleanly, releasing the mutex.**

### Unit — `PreviewUrlCache`

1. **Fresh entry survives read.**
2. **Entry older than 5h evicts on read and returns null.**
3. **Re-setting a key resets timestamp.**
4. **`contains` respects TTL (returns false for expired entries).**

### Manual acceptance

On a device:

1. Perform 5 back-to-back searches. Tap download on the top result **immediately** each time — spinner must appear within ~200ms every time.
2. After a search, tap preview on the top result **immediately** — first tap must start playback within ~2s.
3. Confirm a track that's known to be ciphered (if identifiable) still plays preview — phase 2 may have warmed it in background, or on-demand hedged race covers it.
4. Check logcat: `adb logcat -s PreviewPrefetcher`. Confirm the `InnerTube prefetch returned null for <id> — queuing yt-dlp fallback` lines surface when they should; no silent skips.

## Risks and edge cases

- **Phase-2 yt-dlp still runs one at a time — is that fast enough?** Worst case: ~5% of tracks in a 10-result search page are ciphered → 0-2 yt-dlp prefetches per batch → ~30-60s of serial background work. User may tap preview on a ciphered track before phase 2 reached it; on-demand hedged race then runs for that track, competing with phase 2 through the existing `ytDlpSemaphore(2)`. One-at-a-time phase-2 + one on-demand = at most 2 yt-dlp processes, same as today's semaphore cap — but now they're all intentional, not zombies.
- **`asMutableMap` adapter is ugly.** Can be cleaned up by refactoring `PreviewPrefetcher` to use the `PreviewUrlCache` public API directly instead of a raw `MutableMap<String, String>` — note as recommended during implementation.
- **TTL clock is wall-clock** (`System.currentTimeMillis`). If user manually changes system time, cache may evict too early or too late. Acceptable — InnerTube URL expiry is also wall-clock from YouTube's side.
- **Tests that use `raceForTest` without time-advance will break** if hedge default is non-zero. Tests must either pass `hedgeDelayMs = 0` or run under a `TestScope` that can advance virtual time. Noted for implementer.

## Observability

Every failure path logs at `Log.w`:
- InnerTube prefetch null → queuing yt-dlp fallback
- InnerTube prefetch threw → reason
- yt-dlp prefetch threw → reason

Plus existing `LATDIAG` markers around `extract-start` / `extract-end` stay — they cover the on-demand path.

If regressions appear, `adb logcat -s PreviewPrefetcher LATDIAG` shows exactly where.

## Out of scope

- Persistent disk cache for preview URLs — process death clears the cache frequently enough; persistence adds complexity without meaningful benefit
- Rewriting `extractViaYtDlp` to be cancellable (would require patching youtubedl-android's JNI surface — unbounded scope)
- Adjusting concurrency caps (`INNERTUBE_CONCURRENCY`, `YTDLP_CONCURRENCY`) — current values work once we stop leaking yt-dlp calls from prefetch
