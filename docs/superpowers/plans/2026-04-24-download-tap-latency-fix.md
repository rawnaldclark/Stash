# Download-Tap & Preview Latency Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the post-search ~60-second UI starvation caused by zombie yt-dlp prefetch calls, so tap responses (download / preview) land in <200ms on every search.

**Architecture:** Three coordinated changes — (1) new `PreviewUrlExtractor.extractForPrefetch(id): String?` that only tries InnerTube, (2) hedged race in `extractStreamUrl` so yt-dlp is delayed 500ms and cancelled before its JNI call starts, (3) two-phase `PreviewPrefetcher` that uses the new prefetch entry point and serializes yt-dlp fallbacks with a mutex — plus a 5h TTL on `PreviewUrlCache` to close the stale-URL gap proactively.

**Tech Stack:** Kotlin, Android (Compose), Hilt, kotlinx-coroutines (Dispatchers.IO, Mutex, Semaphore, delay), JUnit 4, Mockito/Mockito-Kotlin, kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-04-24-download-tap-latency-design.md`

---

## Pre-flight

- [ ] From repo root on current master, create a fresh worktree + branch:

```bash
cd C:/Users/theno/Projects/MP3APK
git worktree add .worktrees/preview-latency-fix -b fix/preview-latency master
cp local.properties .worktrees/preview-latency-fix/local.properties
```

Feature-memory note: per `feedback_worktree_local_properties.md`, `git worktree add` does NOT copy `local.properties` — the explicit `cp` above prevents Last.fm / keystore lookups from showing "Not configured" in the debug build.

**All subsequent tasks operate in:** `C:/Users/theno/Projects/MP3APK/.worktrees/preview-latency-fix`. Every Bash command must begin with `cd C:/Users/theno/Projects/MP3APK/.worktrees/preview-latency-fix && ...`. Compose / Hilt / Room compile there with no extra setup.

---

## Task 1: Add `extractForPrefetch` to `PreviewUrlExtractor`

**Verified facts:**
- `PreviewUrlExtractor` lives at `data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt`
- `extractViaInnerTube(id): String?` is already private in the class and returns null on any failure (no throw)
- `INNERTUBE_CONCURRENCY = 8` / `innerTubeSemaphore` already enforce the parallel cap
- Existing test file: `data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlExtractorTest.kt` (extend, don't replace)

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlExtractorTest.kt`

- [ ] **Step 1: Write failing tests**

Open the existing test file. Add these tests alongside the existing ones (preserving whatever setup/test-hook infrastructure is already there).

The new tests use assertions not already imported in the existing test file — add these imports at the top if missing:
```kotlin
import kotlin.test.assertFailsWith
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
```

```kotlin
@Test
fun `extractForPrefetch returns InnerTube URL on success`() = runTest {
    val extractor = buildExtractorWithInnerTubeStub(returning = "https://example.com/audio.m4a")
    val url = extractor.extractForPrefetch("abc123")
    assertEquals("https://example.com/audio.m4a", url)
}

@Test
fun `extractForPrefetch returns null when InnerTube returns null`() = runTest {
    val extractor = buildExtractorWithInnerTubeStub(returning = null)
    val url = extractor.extractForPrefetch("abc123")
    assertNull(url)
}

@Test
fun `extractForPrefetch returns null when InnerTube throws non-cancellation`() = runTest {
    val extractor = buildExtractorWithInnerTubeStub(throwing = RuntimeException("boom"))
    val url = extractor.extractForPrefetch("abc123")
    assertNull(url)
}

@Test
fun `extractForPrefetch propagates CancellationException`() = runTest {
    val extractor = buildExtractorWithInnerTubeStub(throwing = CancellationException("cancelled"))
    assertFailsWith<CancellationException> {
        extractor.extractForPrefetch("abc123")
    }
}

@Test
fun `extractForPrefetch never invokes yt-dlp`() = runTest {
    var ytDlpCalled = false
    val extractor = buildExtractorWithInnerTubeStub(
        returning = null,
        ytDlpObserver = { ytDlpCalled = true },
    )
    extractor.extractForPrefetch("abc123")
    assertFalse(ytDlpCalled)
}
```

**If the existing test file doesn't have a `buildExtractorWithInnerTubeStub` helper:** read the existing test setup to see how extractor instances are constructed (most likely direct constructor with mocked deps). Add a test-local helper that:
- Mocks `innerTubeClient.playerForAudio(id)` to produce a JsonObject whose `streamingData.adaptiveFormats` yields the expected URL (for success cases) or returns null / throws
- Mocks `ytDlpManager` with relaxed mock — should never be invoked for these tests
- Mocks `tokenManager` with relaxed mock
- Uses a real `context` mock if needed (the `extractViaInnerTube` path doesn't touch Context — it just calls `innerTubeClient`)

Study the existing tests in the same file to mirror their style — don't reinvent the wheel.

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/preview-latency-fix && \
./gradlew :data:download:test --tests "com.stash.data.download.preview.PreviewUrlExtractorTest"
```

Expected: the new tests FAIL with "unresolved reference: extractForPrefetch".

- [ ] **Step 3: Implement `extractForPrefetch`**

Add this method to `PreviewUrlExtractor`, immediately after `extractStreamUrl` (around line ~196):

```kotlin
/**
 * Prefetch-only extraction. Tries InnerTube once; returns null on any
 * failure (including null return, timeouts, and non-cancellation throws).
 * Never invokes yt-dlp, so fanning this out across many tracks from the
 * prefetcher cannot saturate CPU.
 *
 * Callers responsible for null handling — PreviewPrefetcher falls through
 * to a serialized yt-dlp prefetch phase for ids where this returns null.
 */
suspend fun extractForPrefetch(videoId: String): String? {
    val t0 = System.currentTimeMillis()
    Log.d("LATDIAG", "extract-prefetch-start videoId=$videoId")
    return try {
        innerTubeSemaphore.acquire()
        try {
            val result = runCatching { extractViaInnerTube(videoId) }.getOrElse { t ->
                if (t is CancellationException) throw t
                Log.w(TAG, "extractForPrefetch: InnerTube threw for $videoId: ${t.message}")
                null
            }
            val dt = System.currentTimeMillis() - t0
            Log.d("LATDIAG", "extract-prefetch-end videoId=$videoId dt=${dt}ms outcome=${if (result != null) "url" else "null"}")
            result
        } finally {
            innerTubeSemaphore.release()
        }
    } catch (t: CancellationException) {
        Log.d("LATDIAG", "extract-prefetch-cancel videoId=$videoId dt=${System.currentTimeMillis() - t0}ms")
        throw t
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/preview-latency-fix && \
./gradlew :data:download:test --tests "com.stash.data.download.preview.PreviewUrlExtractorTest"
```

Expected: all tests PASS (existing + new).

- [ ] **Step 5: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/preview-latency-fix && \
git add data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt \
        data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlExtractorTest.kt && \
git commit -m "feat(preview): add extractForPrefetch — InnerTube-only extractor path"
```

Verify branch: `git branch --show-current` → `fix/preview-latency`.

---

## Task 2: Hedge yt-dlp start in `race()` (+ extend `raceForTest`)

**Verified facts:**
- `race()` is currently `private suspend` on `PreviewUrlExtractor.Companion` at lines 117-150
- Both `inner` and `yt` asyncs launch simultaneously; `yt.cancel()` doesn't stop a JNI call already in flight
- `raceForTest` currently has this signature (lines 92-99): `internal suspend fun raceForTest(hooks: TestHooks, videoId: String): String`
- It passes `itSem = innerTubeSemaphore, ytSem = ytDlpSemaphore` into `race()` — no hedge param

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlExtractorTest.kt`

- [ ] **Step 1: Write failing tests**

Add these tests to the existing test file:

```kotlin
@Test
fun `race with InnerTube quick-win does not invoke yt-dlp`() = runTest {
    var ytDlpInvocations = 0
    val hooks = object : PreviewUrlExtractor.TestHooks {
        override suspend fun innerTubeExtract(id: String): String? {
            delay(100)  // InnerTube takes 100ms, well under 500ms hedge
            return "https://inner.example/$id"
        }
        override suspend fun ytDlpExtract(id: String): String {
            ytDlpInvocations++
            return "https://yt.example/$id"
        }
    }
    val url = PreviewUrlExtractor.raceForTest(hooks, "v1", hedgeDelayMs = 500)
    assertEquals("https://inner.example/v1", url)
    assertEquals(0, ytDlpInvocations)
}

@Test
fun `race starts yt-dlp after hedge when InnerTube is slow`() = runTest {
    var ytDlpInvocations = 0
    val hooks = object : PreviewUrlExtractor.TestHooks {
        override suspend fun innerTubeExtract(id: String): String? {
            delay(2_000)
            return null  // slow and ultimately null → yt-dlp wins
        }
        override suspend fun ytDlpExtract(id: String): String {
            ytDlpInvocations++
            return "https://yt.example/$id"
        }
    }
    val url = PreviewUrlExtractor.raceForTest(hooks, "v1", hedgeDelayMs = 500)
    assertEquals("https://yt.example/v1", url)
    assertEquals(1, ytDlpInvocations)
}

@Test
fun `race falls back to yt-dlp when InnerTube throws`() = runTest {
    var ytDlpInvocations = 0
    val hooks = object : PreviewUrlExtractor.TestHooks {
        override suspend fun innerTubeExtract(id: String): String? {
            delay(50)
            throw RuntimeException("innertube boom")
        }
        override suspend fun ytDlpExtract(id: String): String {
            ytDlpInvocations++
            return "https://yt.example/$id"
        }
    }
    val url = PreviewUrlExtractor.raceForTest(hooks, "v1", hedgeDelayMs = 500)
    assertEquals("https://yt.example/v1", url)
    assertEquals(1, ytDlpInvocations)
}

@Test
fun `race with hedge zero matches old simultaneous-start behavior`() = runTest {
    // Defensive test: confirms existing tests that pass hedgeDelayMs=0 still
    // see both extractors race in parallel (old behavior).
    var innerStarted = false
    var ytStarted = false
    val hooks = object : PreviewUrlExtractor.TestHooks {
        override suspend fun innerTubeExtract(id: String): String? {
            innerStarted = true
            delay(200)
            return "https://inner.example/$id"
        }
        override suspend fun ytDlpExtract(id: String): String {
            ytStarted = true
            delay(500)
            return "https://yt.example/$id"
        }
    }
    val url = PreviewUrlExtractor.raceForTest(hooks, "v1", hedgeDelayMs = 0)
    assertEquals("https://inner.example/v1", url)
    assertTrue(innerStarted)
    assertTrue(ytStarted)  // started simultaneously since hedge = 0
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/preview-latency-fix && \
./gradlew :data:download:test --tests "com.stash.data.download.preview.PreviewUrlExtractorTest"
```

Expected: FAIL — `raceForTest` doesn't accept `hedgeDelayMs`.

- [ ] **Step 3: Update `race()` + `raceForTest` + add constant**

In `PreviewUrlExtractor.kt` companion object:

1. Add the hedge constant near other timing constants:
```kotlin
/** Delay before starting yt-dlp in the race. If InnerTube returns within
 *  this window, yt-dlp's JNI call never launches. */
private const val YTDLP_HEDGE_DELAY_MS = 500L
```

2. Update `race(...)` signature to accept `hedgeDelayMs`:
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
        if (hedgeDelayMs > 0) delay(hedgeDelayMs)
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

3. Extend `raceForTest` to accept + forward `hedgeDelayMs`:
```kotlin
internal suspend fun raceForTest(
    hooks: TestHooks,
    videoId: String,
    hedgeDelayMs: Long = YTDLP_HEDGE_DELAY_MS,
): String = race(
    videoId = videoId,
    innerTubeExtract = hooks::innerTubeExtract,
    ytDlpExtract = hooks::ytDlpExtract,
    itSem = innerTubeSemaphore,
    ytSem = ytDlpSemaphore,
    hedgeDelayMs = hedgeDelayMs,
)
```

The existing callsite in `extractStreamUrl` doesn't need to change — it calls `race(...)` without a `hedgeDelayMs` arg, picking up the new 500ms default.

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/preview-latency-fix && \
./gradlew :data:download:test --tests "com.stash.data.download.preview.PreviewUrlExtractorTest"
```

**Known pre-existing test to update:** `race cancels ytdlp when innertube wins` (in the same test file) asserts `ytDlpCancelled.get() == true` after the race. With the new 500ms default hedge, the yt-dlp lambda body never runs (the cancel fires during the hedge delay before the yt-dlp lambda executes). Update this test to pass `hedgeDelayMs = 0` so it continues to exercise the old simultaneous-start contract.

Scan the file for any other call to `raceForTest(...)` without a hedge arg — any test asserting yt-dlp body execution (e.g., `ytDlpStarted = true`) under a fast InnerTube win needs the same `hedgeDelayMs = 0` override.

Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/preview-latency-fix && \
git add data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt \
        data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlExtractorTest.kt && \
git commit -m "fix(preview): hedge yt-dlp start by 500ms so InnerTube quick-wins skip yt-dlp entirely"
```

---

## Task 3: Two-phase prefetch in `PreviewPrefetcher`

**Verified facts:**
- `PreviewPrefetcher` lives at `feature/search/src/main/kotlin/com/stash/feature/search/PreviewPrefetcher.kt`
- Current `prefetch(videoIds: List<String>)` body (line 65-79) launches one coroutine per id that calls `extractor.extractStreamUrl(id)` and writes to `previewUrlCache[id]`
- Existing test file: `feature/search/src/test/kotlin/com/stash/feature/search/PreviewPrefetcherTest.kt`
- `extractor.extractViaYtDlpForRetry(id)` is the public yt-dlp-only call we use for phase 2

**Files:**
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/PreviewPrefetcher.kt`
- Test: `feature/search/src/test/kotlin/com/stash/feature/search/PreviewPrefetcherTest.kt`

- [ ] **Step 1: Read the existing PreviewPrefetcherTest.kt**

Skim the file to see which test style it uses (mockito-kotlin vs mockk) and how it constructs test `PreviewPrefetcher` instances. Reuse its scaffolding.

- [ ] **Step 2: Write failing tests**

Add these tests to the existing file (update existing tests if they assert behavior that changes):

```kotlin
@Test
fun `phase 1 caches InnerTube result when extractForPrefetch succeeds`() = runTest {
    val extractor = mock<PreviewUrlExtractor> {
        onBlocking { extractForPrefetch("v1") } doReturn "https://inner/v1"
    }
    val cache = mutableMapOf<String, String>()
    val prefetcher = PreviewPrefetcher(extractor, cache, this)

    prefetcher.prefetch(listOf("v1"))
    advanceUntilIdle()

    assertEquals("https://inner/v1", cache["v1"])
    verifyBlocking(extractor, never()) { extractViaYtDlpForRetry(any()) }
}

@Test
fun `phase 2 uses yt-dlp when extractForPrefetch returns null`() = runTest {
    val extractor = mock<PreviewUrlExtractor> {
        onBlocking { extractForPrefetch("v1") } doReturn null
        onBlocking { extractViaYtDlpForRetry("v1") } doReturn "https://yt/v1"
    }
    val cache = mutableMapOf<String, String>()
    val prefetcher = PreviewPrefetcher(extractor, cache, this)

    prefetcher.prefetch(listOf("v1"))
    advanceUntilIdle()

    assertEquals("https://yt/v1", cache["v1"])
}

@Test
fun `phase 2 is serialized across multiple ciphered ids`() = runTest {
    var concurrentYtDlp = 0
    var maxConcurrentYtDlp = 0
    val extractor = mock<PreviewUrlExtractor> {
        onBlocking { extractForPrefetch(any()) } doReturn null
        onBlocking { extractViaYtDlpForRetry(any()) } doAnswer {
            concurrentYtDlp++
            maxConcurrentYtDlp = maxOf(maxConcurrentYtDlp, concurrentYtDlp)
            // Simulate yt-dlp taking time so we can observe serialization
            runBlocking { delay(100) }
            concurrentYtDlp--
            "https://yt/${it.arguments[0]}"
        }
    }
    val cache = mutableMapOf<String, String>()
    val prefetcher = PreviewPrefetcher(extractor, cache, this)

    prefetcher.prefetch(listOf("v1", "v2", "v3"))
    advanceUntilIdle()

    assertEquals(3, cache.size)
    assertEquals(1, maxConcurrentYtDlp)  // serialized
}

@Test
fun `phase 2 short-circuits if cache is filled while waiting for mutex`() = runTest {
    // Declare cache BEFORE the mock so the doAnswer lambda below can close
    // over it (Kotlin requires the symbol to be in scope at lambda creation).
    val cache = mutableMapOf<String, String>()
    val extractor = mock<PreviewUrlExtractor> {
        onBlocking { extractForPrefetch("v1") } doReturn null
        onBlocking { extractForPrefetch("v2") } doReturn null
        onBlocking { extractViaYtDlpForRetry("v1") } doAnswer {
            // While v1's yt-dlp is running, simulate v2 getting filled
            // by an on-demand tap via the shared cache.
            cache["v2"] = "https://ondemand/v2"
            "https://yt/v1"
        }
        // v2's yt-dlp should NEVER run because cache was filled while
        // v2's phase-2 job was waiting on the mutex.
        onBlocking { extractViaYtDlpForRetry("v2") } doThrow AssertionError("should not be called")
    }
    val prefetcher = PreviewPrefetcher(extractor, cache, this)

    prefetcher.prefetch(listOf("v1", "v2"))
    advanceUntilIdle()

    assertEquals("https://yt/v1", cache["v1"])
    assertEquals("https://ondemand/v2", cache["v2"])
    verifyBlocking(extractor, times(1)) { extractViaYtDlpForRetry("v1") }
}

@Test
fun `phase 2 failure is logged but does not poison the scope`() = runTest {
    val extractor = mock<PreviewUrlExtractor> {
        onBlocking { extractForPrefetch("v1") } doReturn null
        onBlocking { extractViaYtDlpForRetry("v1") } doThrow RuntimeException("yt boom")
        onBlocking { extractForPrefetch("v2") } doReturn "https://inner/v2"
    }
    val cache = mutableMapOf<String, String>()
    val prefetcher = PreviewPrefetcher(extractor, cache, this)

    prefetcher.prefetch(listOf("v1", "v2"))
    advanceUntilIdle()

    // v1 failed, v2 still succeeded
    assertFalse("v1" in cache)
    assertEquals("https://inner/v2", cache["v2"])
}

@Test
fun `already-cached ids are skipped`() = runTest {
    val extractor = mock<PreviewUrlExtractor>(stubbing = { /* should never be called */ })
    val cache = mutableMapOf("v1" to "https://existing/v1")
    val prefetcher = PreviewPrefetcher(extractor, cache, this)

    prefetcher.prefetch(listOf("v1"))
    advanceUntilIdle()

    assertEquals("https://existing/v1", cache["v1"])
    verifyBlocking(extractor, never()) { extractForPrefetch(any()) }
    verifyBlocking(extractor, never()) { extractViaYtDlpForRetry(any()) }
}
```

If the file already has tests that assume `prefetch` calls `extractStreamUrl`, those become stale — update them to verify the new behavior (phase 1 uses `extractForPrefetch`, phase 2 uses `extractViaYtDlpForRetry`). Do not leave dead tests.

- [ ] **Step 3: Run tests to verify they fail**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/preview-latency-fix && \
./gradlew :feature:search:test --tests "com.stash.feature.search.PreviewPrefetcherTest"
```

Expected: FAIL — prefetcher still calls `extractStreamUrl`.

- [ ] **Step 4: Implement the two-phase body**

Replace `prefetch(...)` in `PreviewPrefetcher.kt`:

```kotlin
// Class-level mutex — at most one yt-dlp prefetch runs concurrently per
// install. On-demand yt-dlp calls (from extractStreamUrl) go through the
// extractor's own ytDlpSemaphore(2) independently, so an on-demand tap
// is not blocked by a running phase-2 prefetch.
private val ytDlpPrefetchMutex = Mutex()

fun prefetch(videoIds: List<String>) {
    videoIds
        .filter { it !in previewUrlCache }
        .forEach { id ->
            val job = scope.launch {
                // Phase 1 — InnerTube-only, parallel via the extractor's semaphore
                val innerTubeUrl = try {
                    extractor.extractForPrefetch(id)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.w(TAG, "InnerTube prefetch threw for $id: ${t.message}")
                    null
                }
                if (innerTubeUrl != null) {
                    previewUrlCache[id] = innerTubeUrl
                    return@launch
                }
                Log.w(TAG, "InnerTube prefetch returned null for $id — queuing yt-dlp fallback")

                // Phase 2 — serialized yt-dlp fallback
                ytDlpPrefetchMutex.withLock {
                    if (id in previewUrlCache) return@launch  // on-demand raced us
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
```

Add imports:
```kotlin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
```

`prefetchVisible(...)` and `cancelAll()` unchanged.

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/preview-latency-fix && \
./gradlew :feature:search:test --tests "com.stash.feature.search.PreviewPrefetcherTest"
```

Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/preview-latency-fix && \
git add feature/search/src/main/kotlin/com/stash/feature/search/PreviewPrefetcher.kt \
        feature/search/src/test/kotlin/com/stash/feature/search/PreviewPrefetcherTest.kt && \
git commit -m "feat(search): two-phase prefetch — InnerTube parallel, yt-dlp serialized"
```

---

## Task 4: TTL on `PreviewUrlCache`

**Verified facts:**
- `PreviewUrlCache` at `data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlCache.kt`, backed by `ConcurrentHashMap<String, String>`
- Public surface: `get`, `set`, `contains`, `clear`, `asMutableMap`
- `asMutableMap` is used by `PreviewPrefetcher`'s `@Inject` secondary constructor (line 53-54 of PreviewPrefetcher.kt)

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlCache.kt`
- Test (new): `data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlCacheTtlTest.kt`

- [ ] **Step 1: Write failing tests**

Create `data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlCacheTtlTest.kt`:

```kotlin
package com.stash.data.download.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TTL behavior of PreviewUrlCache. Uses an injected clock via a test-only
 * constructor overload so we don't sleep real-world time in tests.
 */
class PreviewUrlCacheTtlTest {

    private var now = 0L
    private val clock = { now }

    @Test
    fun `fresh entry survives read`() {
        val cache = PreviewUrlCache(clock)
        cache["v1"] = "url1"
        assertEquals("url1", cache["v1"])
        assertTrue("v1" in cache)
    }

    @Test
    fun `entry older than TTL evicts on read`() {
        val cache = PreviewUrlCache(clock)
        cache["v1"] = "url1"
        now += 5L * 60 * 60 * 1000 + 1  // 5h + 1ms
        assertNull(cache["v1"])
        assertFalse("v1" in cache)
    }

    @Test
    fun `re-setting a key resets timestamp`() {
        val cache = PreviewUrlCache(clock)
        cache["v1"] = "url1"
        now += 4L * 60 * 60 * 1000  // 4h — still fresh
        cache["v1"] = "url2"
        now += 2L * 60 * 60 * 1000  // another 2h (total 6h since first write, 2h since reset)
        assertEquals("url2", cache["v1"])  // reset survived; 6h total is irrelevant
    }

    @Test
    fun `clear empties cache`() {
        val cache = PreviewUrlCache(clock)
        cache["v1"] = "url1"
        cache.clear()
        assertNull(cache["v1"])
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/preview-latency-fix && \
./gradlew :data:download:test --tests "com.stash.data.download.preview.PreviewUrlCacheTtlTest"
```

Expected: FAIL — `PreviewUrlCache` doesn't accept a clock; entries don't expire.

- [ ] **Step 3: Rewrite `PreviewUrlCache`**

Replace the whole file with:

```kotlin
package com.stash.data.download.preview

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide preview-URL cache shared by [PreviewPrefetcher] and any
 * ViewModel that kicks a preview.
 *
 * TTL: entries are evicted lazily on read after [TTL_MS] (5 hours —
 * safety margin under YouTube's ~6h InnerTube URL expiry). Re-setting
 * a key resets the timestamp. Process death also clears the cache.
 *
 * Thread-safe via [ConcurrentHashMap]. [compute]-style atomic eviction
 * keeps `get` correct under concurrent `set`.
 */
@Singleton
class PreviewUrlCache internal constructor(
    private val clock: () -> Long,
) {
    @Inject
    constructor() : this(clock = System::currentTimeMillis)

    private data class Entry(val url: String, val cachedAtMs: Long)
    private val map: MutableMap<String, Entry> = ConcurrentHashMap()

    operator fun get(id: String): String? {
        val entry = map[id] ?: return null
        if (clock() - entry.cachedAtMs > TTL_MS) {
            map.remove(id, entry)  // atomic compare-and-remove
            return null
        }
        return entry.url
    }

    operator fun set(id: String, url: String) {
        map[id] = Entry(url, clock())
    }

    operator fun contains(id: String): Boolean = get(id) != null

    fun clear() { map.clear() }

    /**
     * Map adapter for [PreviewPrefetcher]'s primary constructor, which
     * uses raw `Map<String, String>` semantics. Put/get/contains go
     * through the TTL-aware path so a prefetcher write sets a fresh
     * timestamp and a read sees expiry.
     *
     * `entries`/`keys`/`values` are unsupported — the prefetcher only
     * uses `put`, `get`, and `contains`. If a future caller needs them,
     * migrate off the adapter and onto the public [PreviewUrlCache] API.
     */
    val asMutableMap: MutableMap<String, String> get() = object : AbstractMutableMap<String, String>() {
        override fun put(key: String, value: String): String? {
            val prev = map.put(key, Entry(value, clock()))
            return prev?.url
        }
        override fun get(key: String): String? = this@PreviewUrlCache[key]
        override fun containsKey(key: String): Boolean = key in this@PreviewUrlCache

        override val entries: MutableSet<MutableMap.MutableEntry<String, String>>
            get() = error("PreviewUrlCache.asMutableMap.entries is not supported")
    }

    companion object {
        /** 5 hours — safety margin under YouTube's observed ~6h InnerTube URL expiry. */
        private const val TTL_MS = 5L * 60 * 60 * 1000
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/preview-latency-fix && \
./gradlew :data:download:test --tests "com.stash.data.download.preview.PreviewUrlCacheTtlTest"
```

Expected: all new tests PASS. Also re-run the broader `:data:download:test` suite and `:feature:search:test` to confirm no regressions:

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/preview-latency-fix && \
./gradlew :data:download:test :feature:search:test
```

- [ ] **Step 5: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/preview-latency-fix && \
git add data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlCache.kt \
        data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlCacheTtlTest.kt && \
git commit -m "feat(preview): 5h TTL on PreviewUrlCache to guard against stale InnerTube URLs"
```

---

## Task 5: Device acceptance (manual)

- [ ] **Step 1: Install debug build**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/preview-latency-fix && \
./gradlew :app:installDebug
```

Expected: BUILD SUCCESSFUL, APK installed on connected device.

- [ ] **Step 2: Run acceptance flow on device**

1. **Rapid-fire search test.** Perform 5 different searches back-to-back. After each result page loads, tap the download button on the top result **immediately**. Spinner should appear within ~200ms every time. Green checkmark on completion.
2. **Preview tap test.** After each of the 5 searches, tap the preview button on the top result **immediately**. First tap should start playback within ~2 seconds. No repeated presses needed.
3. **Ciphered-track coverage.** Search for a track that's likely ciphered (older songs, live recordings, etc.). Wait ~30 seconds after results render (letting phase-2 yt-dlp complete). Then tap preview on that track — should play within ~2s (from cache) on first tap.

- [ ] **Step 3: Inspect logcat for observability**

With the device still connected:
```bash
adb logcat -s PreviewPrefetcher LATDIAG -v time
```
While you repeat a search + tap flow, confirm:
- Every `InnerTube prefetch returned null for <id>` is followed by either a `yt-dlp prefetch failed` or silence (meaning it succeeded)
- No silent gaps — every failure path emits a log line
- `LATDIAG extract-prefetch-end` lines appear with reasonable durations (typically <2s for InnerTube success)

- [ ] **Step 4: Commit (empty, for the record)**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/preview-latency-fix && \
git commit --allow-empty -m "test: manual device acceptance — tap latency <200ms, preview <2s"
```

**If anything fails:** STOP. Do not proceed to release. Investigate via `@superpowers:systematic-debugging` skill, fix, re-verify.

---

## Task 6: Version bump + release 0.6.3

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Bump version**

In `app/build.gradle.kts`, change:
```kotlin
versionCode = 30
versionName = "0.6.2"
```
to:
```kotlin
versionCode = 31
versionName = "0.6.3"
```

- [ ] **Step 2: Release commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/preview-latency-fix && \
git add app/build.gradle.kts && \
git commit -m "$(cat <<'EOF'
feat: 0.6.3 — download/preview tap latency fix

After every search, the download and preview buttons no longer
lag for ~60 seconds. Tap-to-spinner now lands in <200ms.

Root cause: the preview prefetcher's race launched yt-dlp in
parallel with InnerTube for every prefetched track, and yt-dlp's
JNI call couldn't be cancelled cooperatively — so 10 zombie
yt-dlp processes ran in the background every search, saturating
CPU and starving the main thread.

Fix:
- Hedge yt-dlp start by 500ms in the race — InnerTube almost
  always wins before yt-dlp launches, skipping its JNI call
  entirely.
- Split prefetch into an InnerTube-only fast path with a
  serialized yt-dlp fallback for ciphered tracks. At most one
  yt-dlp prefetch runs at a time.
- 5h TTL on PreviewUrlCache so next-day playback doesn't hit
  stale InnerTube URLs.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 3: Merge to master**

```bash
cd C:/Users/theno/Projects/MP3APK && \
git merge --ff-only fix/preview-latency 2>&1 && \
git log --oneline -3
```

- [ ] **Step 4: Tag + push**

```bash
cd C:/Users/theno/Projects/MP3APK && \
git tag -a v0.6.3 -m "v0.6.3 — tap latency fix" && \
git push origin master && \
git push origin v0.6.3
```

- [ ] **Step 5: GitHub release**

```bash
cd C:/Users/theno/Projects/MP3APK && \
gh release create v0.6.3 --title "v0.6.3 — Tap latency fix" --notes "$(cat <<'EOF'
After every search, the download and preview buttons no longer lag for ~60 seconds. Tap-to-spinner now lands in <200ms and previews play on first tap.

**Root cause:** every search kicked off ~10 yt-dlp prefetches alongside InnerTube. InnerTube almost always won, but yt-dlp's JNI call couldn't be cancelled, so 10 zombie processes ran in the background per search, saturating CPU.

**Fix:**
- Hedge yt-dlp start by 500ms — InnerTube usually wins first, and yt-dlp's JNI call never launches
- Prefetcher now uses an InnerTube-only fast path; ciphered tracks fall through to a strictly serialized yt-dlp background queue (one at a time)
- PreviewUrlCache gains a 5-hour TTL to guard against stale-URL playback failures
EOF
)"
```

- [ ] **Step 6: Clean up**

```bash
cd C:/Users/theno/Projects/MP3APK && \
git worktree remove .worktrees/preview-latency-fix && \
git branch -d fix/preview-latency
```

If the worktree remove fails with a filename-too-long error (Windows path depth), use `git worktree remove --force .worktrees/preview-latency-fix` or leave the directory for the user to clean manually — the release is already out.

---

## Skills reference

- @superpowers:test-driven-development — follow strictly for Tasks 1-4
- @superpowers:verification-before-completion — run before declaring each task done
- @superpowers:systematic-debugging — if Task 5 device acceptance fails

## Risks / rollback

- **Rollback:** `git revert v0.6.3..v0.6.2` + bump version. Users on 0.6.3 will see the prefetch storm return — harmless, just back to pre-fix behavior.
- **TTL clock skew:** if a user manually changes system time by >5h, cache entries may evict unexpectedly. Acceptable — re-extraction is <2s.
- **Phase-2 yt-dlp backlog under heavy scrolling:** if a user scrolls through 100 tracks and most are ciphered, phase-2 queue grows. Cancellation via `cancelAll()` (fired on new search from `runSearch`) drains it. Existing test covers the serialization; no known pathology.
