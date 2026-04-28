# YouTube Sync Pagination & Parallelism Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the YouTube Music sync pipeline from silently truncating Liked Songs (24/2000+), Home Mixes (~25 of 50+), and user playlists at one InnerTube response. Implement continuation-token pagination, a 3-way concurrency cap on per-playlist fetches, and surface partial-fetch state in snapshots + diagnostics.

**Architecture:** Add a `browse(continuation: String)` overload to `InnerTubeClient` plus a per-sync auth cache. Add a `paginateBrowse` helper to `YTMusicApiClient` that walks `continuations[0].nextContinuationData.continuation` until exhausted, with 2-retry backoff on transient failures. Change three public API methods to return new `PagedTracks` / `PagedPlaylists` types that carry `partial` + `expectedCount` flags. Bump Room v15→v16 to add the matching columns to `RemotePlaylistSnapshotEntity`. Wrap `PlaylistFetchWorker.fetchYouTubePlaylists` in a `coroutineScope { ... }` with a `Semaphore(3)` shared across home-mix and user-playlist groups.

**Tech Stack:** Kotlin, Android, Hilt + AssistedInject (WorkManager), Room 2.6 (hand-written migrations), kotlinx.serialization JSON, kotlinx.coroutines (`coroutineScope`, `async`, `Semaphore`), OkHttp, JUnit 4 + mockito-kotlin + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-04-27-youtube-sync-pagination-design.md`

---

## Pre-flight

- [ ] Create a fresh worktree from current `master`:

```bash
cd C:/Users/theno/Projects/MP3APK
git worktree add .worktrees/yt-sync-pagination -b feat/yt-sync-pagination master
cp local.properties .worktrees/yt-sync-pagination/local.properties
```

Memory `feedback_worktree_local_properties.md`: `git worktree add` does **not** carry `local.properties`; the `cp` line above prevents Last.fm/keystore "Not configured" symptoms in debug builds.

**All subsequent tasks operate in:** `C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination`. Every Bash command must begin with `cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ...`. Read/Edit/Write tools should use absolute paths rooted at that worktree.

- [ ] Verify clean baseline:

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test :core:data:test --console=plain
```

Expected: BUILD SUCCESSFUL, all existing tests pass. If anything is red on master, surface it before continuing — this plan assumes a green baseline.

---

## Task 1: Capture real InnerTube fixtures (manual, one-off)

**Why:** The pagination loop needs fixture JSON to test against. The continuation-response shape (`continuationContents.musicPlaylistShelfContinuation.contents[]`) is documented from ytmusicapi but slightly varies across responses. We capture once, sanitize, then write deterministic tests.

**Files:**
- Modify (temporarily): `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt:467` (the `Log.d` after `responseBody`)
- Create: `data/ytmusic/src/test/resources/fixtures/liked_songs_page1.json`
- Create: `data/ytmusic/src/test/resources/fixtures/liked_songs_page2.json`
- Create: `data/ytmusic/src/test/resources/fixtures/playlist_long_page1.json`
- Create: `data/ytmusic/src/test/resources/fixtures/playlist_long_page2.json`

- [ ] **Step 1: Add temporary full-body log**

In `InnerTubeClient.kt` around line 467, change:

```kotlin
Log.d(TAG, "executeRequest: success, response length=${responseBody.length}")
```

to:

```kotlin
Log.d(TAG, "executeRequest: success, response length=${responseBody.length}")
Log.d("StashYTBody", responseBody)  // TEMP — remove before merge
```

- [ ] **Step 2: Build, install, capture**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
./gradlew :app:installDebug --console=plain
adb logcat -c
adb logcat StashYT:V StashYTBody:V *:S > /tmp/yt_capture.log &
# Manually trigger a sync from the app (Sync screen → Sync Now) with YouTube enabled
# and a long Liked Songs (>200 tracks) + a long user playlist (>200 tracks) selected.
# Wait for sync to finish, then:
kill %1
```

Expected: `/tmp/yt_capture.log` contains multiple `StashYTBody` lines, each one a complete JSON response body.

- [ ] **Step 3: Extract & save fixtures**

For each of the 4 fixtures, find the relevant log line by its preceding `executeRequest: POST .../browse` line:

| Fixture | Source line |
|---|---|
| `liked_songs_page1.json` | The first `browse` after `browseId=FEmusic_liked_videos` |
| `liked_songs_page2.json` | The first `browse` whose URL contains `ctoken=` immediately following the page-1 line |
| `playlist_long_page1.json` | The first `browse` with `browseId=VL{your_long_playlist_id}` |
| `playlist_long_page2.json` | Continuation page following page 1 |

Pretty-print each (so diffs are readable) and save to the four `data/ytmusic/src/test/resources/fixtures/*.json` paths.

- [ ] **Step 4: Sanitize PII from fixtures**

In each saved fixture, find-and-replace:
- Cookie strings (search for `SAPISID`, `__Secure-3PAPISID`, etc.) → already not in response bodies, but double-check
- Channel IDs that look like `UC[A-Za-z0-9_-]{22}` and aren't well-known artists → leave well-known ones (channel UCs for artists are public)
- Personal `loggedInUser` fragments under `responseContext` → delete the entire `responseContext` block (not used by parser)
- Continuation tokens are NOT PII — keep them so the loop can be exercised

Verify each file is valid JSON:

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
for f in data/ytmusic/src/test/resources/fixtures/{liked_songs,playlist_long}_page{1,2}.json; do
  python -c "import json,sys;json.load(open('$f'));print('$f OK')"
done
```

Expected: 4× `OK`.

- [ ] **Step 5: Revert the temporary log**

Remove the `Log.d("StashYTBody", responseBody)` line from `InnerTubeClient.kt`. Verify with:

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && grep -n StashYTBody data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt
```

Expected: no output.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add data/ytmusic/src/test/resources/fixtures/
git commit -m "test(ytmusic): add real InnerTube fixtures for pagination tests"
```

---

## Task 2: Add `PagedTracks` / `PagedPlaylists` data classes

**Verified facts:**
- `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/model/YTMusicModels.kt` already holds `YTMusicTrack` (line 19) and `YTMusicPlaylist` (line 37). New paged-result classes belong in the same file per existing convention — no new file.
- These classes are pure data, no logic worth TDD'ing.

**Files:**
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/model/YTMusicModels.kt`

- [ ] **Step 1: Append new data classes**

```kotlin
/**
 * Result of paginating a track-bearing browse endpoint (Liked Songs, a
 * playlist, a mix). Carries the partial-fetch signal + the playlist
 * header's reported track count so the worker can compare and surface
 * "fetched 1247/2000" diagnostics.
 *
 * @property tracks         All tracks accumulated across pages.
 * @property expectedCount  The playlist header's reported track count, if
 *                          parseable. Null when the response has no header
 *                          (Liked Songs has none) or the count couldn't be
 *                          parsed.
 * @property partial        True if any page failed after retries OR the
 *                          MAX_PAGES safety cap was hit OR fetched count
 *                          fell below 95% of [expectedCount].
 * @property partialReason  Human-readable explanation when [partial] is
 *                          true; null otherwise.
 */
data class PagedTracks(
    val tracks: List<YTMusicTrack>,
    val expectedCount: Int? = null,
    val partial: Boolean = false,
    val partialReason: String? = null,
)

/**
 * Result of paginating the user-library playlist list. No expectedCount —
 * the library page does not publish a total.
 */
data class PagedPlaylists(
    val playlists: List<YTMusicPlaylist>,
    val partial: Boolean = false,
    val partialReason: String? = null,
)
```

- [ ] **Step 2: Verify it compiles**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:compileKotlin --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/model/YTMusicModels.kt
git commit -m "feat(ytmusic): add PagedTracks/PagedPlaylists result types"
```

---

## Task 3: Refactor `InnerTubeClient.executeRequest` to expose HTTP status code internally

**Verified facts:**
- `executeRequest` (`InnerTubeClient.kt:418-471`) currently returns `JsonObject?` and swallows the HTTP status. The new continuation-retry policy needs to distinguish 4xx (no retry) from 5xx/network (retry), so the status must escape `executeRequest`.
- Solution: a private `executeRequestWithStatus(...): RequestOutcome` that all paths use; the existing `executeRequest` becomes a thin wrapper that drops the status to preserve current public/internal callers (`browse(browseId)`, `search`, `player`, `getPlaybackTracking`).
- `RequestOutcome` is internal to this file — not exposed beyond `InnerTubeClient`.

**Files:**
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt`
- Test: `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/InnerTubeClientStatusTest.kt` (new — does not exist yet)

- [ ] **Step 1: Write the failing test**

Create `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/InnerTubeClientStatusTest.kt`:

```kotlin
package com.stash.data.ytmusic

import com.stash.core.auth.TokenManager
import com.stash.core.auth.youtube.YouTubeCookieHelper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Verifies that [InnerTubeClient.executeRequestWithStatus] surfaces the HTTP
 * status code so the continuation-retry policy can distinguish 4xx (no retry)
 * from 5xx/network (retry).
 *
 * The wrapper [InnerTubeClient.executeRequest] continues to drop the status
 * code, preserving its existing callers' contracts.
 */
class InnerTubeClientStatusTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun client(): InnerTubeClient {
        val token = mock<TokenManager>()
        val cookies = mock<YouTubeCookieHelper>()
        runBlocking { whenever(token.getYouTubeCookie()).thenReturn(null) }
        return InnerTubeClient(OkHttpClient(), token, cookies)
    }

    @Test fun `200 OK exposes status 200 and body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val outcome = client().executeRequestWithStatusForTest(server.url("/x").toString())
        assertEquals(200, outcome.statusCode)
        assertNotNull(outcome.body)
    }

    @Test fun `404 exposes status 404 and null body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        val outcome = client().executeRequestWithStatusForTest(server.url("/x").toString())
        assertEquals(404, outcome.statusCode)
        assertNull(outcome.body)
    }

    @Test fun `503 exposes status 503 and null body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val outcome = client().executeRequestWithStatusForTest(server.url("/x").toString())
        assertEquals(503, outcome.statusCode)
        assertNull(outcome.body)
    }
}
```

- [ ] **Step 2: Run the test — expect FAIL (compile error)**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.InnerTubeClientStatusTest" --console=plain
```

Expected: compile error — `executeRequestWithStatusForTest` does not exist yet.

- [ ] **Step 3: Add `RequestOutcome` + `executeRequestWithStatus` to `InnerTubeClient`**

In `InnerTubeClient.kt`, add at file-private scope just below the `companion object`:

```kotlin
/**
 * Internal representation of an HTTP outcome that carries both the parsed
 * body (if any) and the HTTP status code. Status code is needed by callers
 * that retry on 5xx but not on 4xx (e.g. [YTMusicApiClient.paginateBrowse]).
 *
 * @property body       Parsed JSON body on 2xx, null otherwise.
 * @property statusCode HTTP status code, or [STATUS_NETWORK_ERROR] if the
 *                      call threw before completing.
 */
internal data class RequestOutcome(val body: JsonObject?, val statusCode: Int) {
    companion object {
        const val STATUS_NETWORK_ERROR = -1
    }
}
```

Replace the existing `private fun executeRequest(...)` (lines 418-471) with:

```kotlin
/**
 * Executes a POST against the InnerTube API. Returns both the parsed body
 * (if 2xx) and the HTTP status code so callers can distinguish retryable
 * (5xx, network) from non-retryable (4xx) failures.
 */
internal fun executeRequestWithStatus(
    url: String,
    body: JsonObject,
    cookie: String?,
    variant: InnerTubeVariant,
): RequestOutcome {
    val sapiSid = cookie?.let { cookieHelper.extractSapiSid(it) }

    val fullUrl = if (sapiSid != null) {
        "$url?prettyPrint=false"
    } else {
        "$url?key=$API_KEY&prettyPrint=false"
    }

    Log.d(
        TAG,
        "executeRequest: POST $fullUrl (authenticated=${sapiSid != null}, variant=$variant)",
    )

    val requestBuilder = Request.Builder()
        .url(fullUrl)
        .post(body.toString().toRequestBody(jsonMediaType))
        .header("Content-Type", "application/json")
        .header("User-Agent", variant.userAgent)
        .header("X-YouTube-Client-Name", variant.clientName)
        .header("X-YouTube-Client-Version", variant.currentVersion())

    if (variant == InnerTubeVariant.WEB_REMIX && sapiSid != null && cookie != null) {
        requestBuilder
            .header("Cookie", cookie)
            .header("Authorization", cookieHelper.generateAuthHeader(sapiSid))
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("X-Goog-AuthUser", "0")
    }

    return try {
        okHttpClient.newCall(requestBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errorBodyLen = resp.body?.string()?.length ?: 0
                Log.e(TAG, "executeRequest: HTTP ${resp.code}, errorBodyLen=$errorBodyLen")
                return@use RequestOutcome(body = null, statusCode = resp.code)
            }
            val responseBody = resp.body?.string()
                ?: return@use RequestOutcome(body = null, statusCode = resp.code)
            Log.d(TAG, "executeRequest: success, response length=${responseBody.length}")
            RequestOutcome(json.parseToJsonElement(responseBody).jsonObject, resp.code)
        }
    } catch (e: Exception) {
        Log.w(TAG, "executeRequest: threw ${e.javaClass.simpleName}: ${e.message}")
        RequestOutcome(body = null, statusCode = RequestOutcome.STATUS_NETWORK_ERROR)
    }
}

/** Backwards-compatible wrapper for callers that don't need the status code. */
private fun executeRequest(
    url: String,
    body: JsonObject,
    cookie: String?,
    variant: InnerTubeVariant,
): JsonObject? = executeRequestWithStatus(url, body, cookie, variant).body
```

Add the test-only helper at the bottom of the class (inside the class body):

```kotlin
/** Test-only convenience that builds a minimal request and returns the outcome. */
internal fun executeRequestWithStatusForTest(url: String): RequestOutcome =
    executeRequestWithStatus(
        url = url,
        body = buildJsonObject { put("test", "true") },
        cookie = null,
        variant = InnerTubeVariant.WEB_REMIX,
    )
```

- [ ] **Step 4: Run the test — expect PASS**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.InnerTubeClientStatusTest" --console=plain
```

Expected: PASS, 3 tests.

If `okhttp3.mockwebserver` is not on the test classpath, add to `data/ytmusic/build.gradle.kts`:

```kotlin
testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

(Match the version of `okhttp3` already pinned in the project — check `gradle/libs.versions.toml` or run `./gradlew :data:ytmusic:dependencies | grep okhttp` to confirm.)

- [ ] **Step 5: Run all existing ytmusic tests — none should break**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --console=plain
```

Expected: BUILD SUCCESSFUL, all green.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt \
        data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/InnerTubeClientStatusTest.kt \
        data/ytmusic/build.gradle.kts
git commit -m "refactor(ytmusic): expose HTTP status from InnerTubeClient.executeRequest

Adds executeRequestWithStatus that returns RequestOutcome(body, statusCode).
Existing executeRequest is now a thin wrapper that drops the status, so
all current callers (browse(browseId), search, player, getPlaybackTracking)
keep their contracts. Status is consumed by the upcoming continuation-retry
policy to distinguish 4xx (no retry) from 5xx/network (retry)."
```

---

## Task 4: Add `InnerTubeClient.browse(continuation: String)` overload

**Verified facts:**
- The continuation request goes to `https://music.youtube.com/youtubei/v1/browse` with the **same** `context` as a regular browse, but the body carries no `browseId`. The token is appended as both `ctoken` and `continuation` query parameters along with `type=next`.
- This matches ytmusicapi's `_send_request` behavior. We're reusing existing `executeRequestWithStatus` so no new HTTP plumbing.

**Files:**
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt`
- Test: `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/InnerTubeClientContinuationTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/InnerTubeClientContinuationTest.kt`:

```kotlin
package com.stash.data.ytmusic

import com.stash.core.auth.TokenManager
import com.stash.core.auth.youtube.YouTubeCookieHelper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Verifies the new browse(continuation) overload posts to the right URL
 * with the right query params and an empty-browseId body, then parses the
 * response.
 */
class InnerTubeClientContinuationTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `browse(continuation) appends ctoken and continuation params and omits browseId`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

            val token = mock<TokenManager>()
            val cookies = mock<YouTubeCookieHelper>()
            whenever(token.getYouTubeCookie()).thenReturn(null)

            // Override BASE_URL by using the test-only helper. We piggyback on the
            // public browse(continuation) entry by pointing OkHttp at our mock
            // server through a custom interceptor — simpler: add a test seam.
            val client = InnerTubeClient(OkHttpClient(), token, cookies)
            val response = client.browseForTest(
                continuation = "ABC123",
                baseUrl = server.url("/youtubei/v1/browse").toString().removeSuffix("/youtubei/v1/browse"),
            )

            assertNotNull(response)
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            val path = recorded.path ?: ""
            assertTrue("ctoken missing in $path", path.contains("ctoken=ABC123"))
            assertTrue("continuation missing in $path", path.contains("continuation=ABC123"))
            assertTrue("type=next missing in $path", path.contains("type=next"))
            val body = recorded.body.readUtf8()
            assertTrue("browseId should be absent for continuation", !body.contains("\"browseId\""))
            assertTrue("context should be present", body.contains("\"context\""))
        }
}
```

- [ ] **Step 2: Run the test — expect FAIL (compile error)**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.InnerTubeClientContinuationTest" --console=plain
```

Expected: compile error — `browseForTest` does not exist.

- [ ] **Step 3: Implement the overload**

In `InnerTubeClient.kt`, add the new public method just below the existing `browse(browseId)` (around line 162):

```kotlin
/**
 * Calls the InnerTube `browse` action with a continuation token.
 *
 * Continuation requests fetch the next page of a previously-browsed surface
 * (Liked Songs, a playlist, a long mix). The token comes from the previous
 * response's `continuations[0].nextContinuationData.continuation` field.
 * The body carries the same `context` object but no `browseId` — the URL
 * query string identifies the continuation chain.
 *
 * @param continuation The continuation token from the prior page.
 * @return The parsed JSON response, or null on failure.
 */
suspend fun browse(continuation: String): JsonObject? = withContext(Dispatchers.IO) {
    val cookie = tokenManager.getYouTubeCookie()
    val variant = InnerTubeVariant.WEB_REMIX
    val body = buildJsonObject {
        put("context", buildContext(variant))
    }
    executeRequestWithStatus(
        url = "$BASE_URL/browse?ctoken=$continuation&continuation=$continuation&type=next",
        body = body,
        cookie = cookie,
        variant = variant,
    ).body
}
```

Note: This appends query params before `executeRequestWithStatus` re-appends `&prettyPrint=false`. Verify the URL-building is correct in `executeRequestWithStatus` — it uses `?prettyPrint=false` if `sapiSid != null` else `?key=...`. **That collides** with our pre-existing `?ctoken=...`. Fix: change `executeRequestWithStatus`'s URL construction to use `&` instead of `?` when the URL already contains `?`:

```kotlin
val separator = if (url.contains('?')) '&' else '?'
val fullUrl = if (sapiSid != null) {
    "${url}${separator}prettyPrint=false"
} else {
    "${url}${separator}key=$API_KEY&prettyPrint=false"
}
```

Add the test seam at the bottom of the class:

```kotlin
/**
 * Test-only entry point that lets the test redirect [BASE_URL] to a
 * MockWebServer. Identical to [browse(continuation)] except the host is
 * supplied externally.
 */
internal suspend fun browseForTest(continuation: String, baseUrl: String): JsonObject? =
    withContext(Dispatchers.IO) {
        val cookie = tokenManager.getYouTubeCookie()
        val variant = InnerTubeVariant.WEB_REMIX
        val body = buildJsonObject { put("context", buildContext(variant)) }
        executeRequestWithStatus(
            url = "$baseUrl/youtubei/v1/browse?ctoken=$continuation&continuation=$continuation&type=next",
            body = body,
            cookie = cookie,
            variant = variant,
        ).body
    }
```

- [ ] **Step 4: Run the test — expect PASS**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.InnerTubeClientContinuationTest" --console=plain
```

Expected: PASS, 1 test.

- [ ] **Step 5: Re-run full ytmusic test suite to confirm no regressions in URL building**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt \
        data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/InnerTubeClientContinuationTest.kt
git commit -m "feat(ytmusic): add InnerTubeClient.browse(continuation) overload"
```

---

## Task 5: Add per-sync auth cache to `InnerTubeClient`

**Verified facts:**
- Currently every `executeRequestWithStatus` call invokes `tokenManager.getYouTubeCookie()` (suspend, hits SharedPreferences) + `cookieHelper.extractSapiSid(cookie)` + `cookieHelper.generateAuthHeader(sapiSid)`. With pagination, a single Liked Songs sync now does ~25 calls instead of 1; cached resolution is worth the ~50 LOC.
- The cache is opt-in via `beginSyncSession` / `endSyncSession`. Non-sync callers (`searchCanonical`, `playerForAudio`, `getPlaybackTracking`) keep the per-call resolution path. No behavior change for them.
- 401 responses anywhere in the session must invalidate the cache so the next call re-resolves.

**Files:**
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt`
- Test: `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/InnerTubeClientAuthCacheTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/InnerTubeClientAuthCacheTest.kt`:

```kotlin
package com.stash.data.ytmusic

import com.stash.core.auth.TokenManager
import com.stash.core.auth.youtube.YouTubeCookieHelper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class InnerTubeClientAuthCacheTest {

    private lateinit var server: MockWebServer
    private lateinit var token: TokenManager
    private lateinit var cookies: YouTubeCookieHelper
    private lateinit var client: InnerTubeClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        token = mock()
        cookies = mock()
        runBlocking {
            whenever(token.getYouTubeCookie()).thenReturn("SAPISID=fake; __Secure-3PAPISID=fake")
        }
        whenever(cookies.extractSapiSid(any())).thenReturn("fake")
        whenever(cookies.generateAuthHeader(any())).thenReturn("SAPISIDHASH fake")
        client = InnerTubeClient(OkHttpClient(), token, cookies)
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `without session, getYouTubeCookie called per request`() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) }
        repeat(3) { client.executeRequestWithStatusForTest(server.url("/x").toString()) }
        verify(token, times(3)).getYouTubeCookie()
    }

    @Test fun `inside session, getYouTubeCookie called once`() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) }
        client.beginSyncSession()
        try {
            repeat(3) { client.executeRequestWithStatusForTest(server.url("/x").toString()) }
        } finally {
            client.endSyncSession()
        }
        verify(token, times(1)).getYouTubeCookie()
    }

    @Test fun `401 inside session clears cache; next call re-resolves`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.beginSyncSession()
        try {
            client.executeRequestWithStatusForTest(server.url("/x").toString()) // 200, populates cache (1 resolve)
            client.executeRequestWithStatusForTest(server.url("/x").toString()) // 401, clears cache
            client.executeRequestWithStatusForTest(server.url("/x").toString()) // 200, re-resolves (2 resolves total)
        } finally {
            client.endSyncSession()
        }
        verify(token, times(2)).getYouTubeCookie()
    }

    @Test fun `endSyncSession clears cache`() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) }
        client.beginSyncSession()
        client.executeRequestWithStatusForTest(server.url("/x").toString())
        client.endSyncSession()
        client.executeRequestWithStatusForTest(server.url("/x").toString())
        verify(token, times(2)).getYouTubeCookie()
    }
}
```

(Add `import org.mockito.kotlin.any` at the top.)

- [ ] **Step 2: Run the test — expect FAIL (compile error)**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.InnerTubeClientAuthCacheTest" --console=plain
```

Expected: compile error — `beginSyncSession` / `endSyncSession` do not exist.

- [ ] **Step 3: Add the cache + session lifecycle**

In `InnerTubeClient.kt`, add fields just below the `companion object`:

```kotlin
@Volatile private var sessionActive: Boolean = false
@Volatile private var cachedCookie: String? = null
@Volatile private var cachedSapiSid: String? = null
@Volatile private var cachedAuthHeader: String? = null
```

Add the lifecycle methods (right after the fields):

```kotlin
/**
 * Marks the start of a sync run. The first authenticated request inside the
 * session populates the cookie / SAPISID / auth-header cache; subsequent
 * requests reuse it. A 401 response anywhere in the session clears the
 * cache so the next call re-resolves (typically forcing re-auth).
 *
 * Safe to call from any thread. Non-sync callers (search, player, etc.)
 * are unaffected — they continue to resolve auth per call.
 */
suspend fun beginSyncSession() {
    sessionActive = true
    cachedCookie = null
    cachedSapiSid = null
    cachedAuthHeader = null
}

/** Ends the sync session and clears the auth cache. Idempotent. */
fun endSyncSession() {
    sessionActive = false
    cachedCookie = null
    cachedSapiSid = null
    cachedAuthHeader = null
}

/** Internal: invalidate cached auth on receipt of a 401. */
private fun invalidateAuthCache() {
    cachedCookie = null
    cachedSapiSid = null
    cachedAuthHeader = null
}
```

Refactor `executeRequestWithStatus` to read/write the cache. Replace the cookie-resolution + auth-header lines:

```kotlin
internal suspend fun executeRequestWithStatus(
    url: String,
    body: JsonObject,
    cookie: String?,
    variant: InnerTubeVariant,
): RequestOutcome = withContext(Dispatchers.IO) {
    val (effectiveCookie, sapiSid, authHeader) = resolveAuth(cookie, variant)

    val separator = if (url.contains('?')) '&' else '?'
    val fullUrl = if (sapiSid != null) {
        "${url}${separator}prettyPrint=false"
    } else {
        "${url}${separator}key=$API_KEY&prettyPrint=false"
    }

    Log.d(TAG, "executeRequest: POST $fullUrl (authenticated=${sapiSid != null}, variant=$variant)")

    val requestBuilder = Request.Builder()
        .url(fullUrl)
        .post(body.toString().toRequestBody(jsonMediaType))
        .header("Content-Type", "application/json")
        .header("User-Agent", variant.userAgent)
        .header("X-YouTube-Client-Name", variant.clientName)
        .header("X-YouTube-Client-Version", variant.currentVersion())

    if (variant == InnerTubeVariant.WEB_REMIX && sapiSid != null && effectiveCookie != null && authHeader != null) {
        requestBuilder
            .header("Cookie", effectiveCookie)
            .header("Authorization", authHeader)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("X-Goog-AuthUser", "0")
    }

    try {
        okHttpClient.newCall(requestBuilder.build()).execute().use { resp ->
            if (resp.code == 401) invalidateAuthCache()
            if (!resp.isSuccessful) {
                val errorBodyLen = resp.body?.string()?.length ?: 0
                Log.e(TAG, "executeRequest: HTTP ${resp.code}, errorBodyLen=$errorBodyLen")
                return@use RequestOutcome(body = null, statusCode = resp.code)
            }
            val responseBody = resp.body?.string()
                ?: return@use RequestOutcome(body = null, statusCode = resp.code)
            Log.d(TAG, "executeRequest: success, response length=${responseBody.length}")
            RequestOutcome(json.parseToJsonElement(responseBody).jsonObject, resp.code)
        }
    } catch (e: Exception) {
        Log.w(TAG, "executeRequest: threw ${e.javaClass.simpleName}: ${e.message}")
        RequestOutcome(body = null, statusCode = RequestOutcome.STATUS_NETWORK_ERROR)
    }
}

private suspend fun resolveAuth(
    explicitCookie: String?,
    variant: InnerTubeVariant,
): Triple<String?, String?, String?> {
    // Non-sync callers pass cookie=null and we resolve fresh per request.
    // Sync callers also typically pass null but benefit from the cache.
    if (!sessionActive) {
        val c = explicitCookie ?: tokenManager.getYouTubeCookie()
        val s = c?.let { cookieHelper.extractSapiSid(it) }
        val a = s?.let { cookieHelper.generateAuthHeader(it) }
        return Triple(c, s, a)
    }
    // Session active: serve from cache, populate on first miss.
    val cachedC = cachedCookie
    val cachedS = cachedSapiSid
    val cachedA = cachedAuthHeader
    if (cachedC != null && cachedS != null && cachedA != null) {
        return Triple(cachedC, cachedS, cachedA)
    }
    val c = explicitCookie ?: tokenManager.getYouTubeCookie()
    val s = c?.let { cookieHelper.extractSapiSid(it) }
    val a = s?.let { cookieHelper.generateAuthHeader(it) }
    cachedCookie = c
    cachedSapiSid = s
    cachedAuthHeader = a
    return Triple(c, s, a)
}
```

Note: `executeRequestWithStatus` is now `suspend`. Update the wrapper:

```kotlin
private suspend fun executeRequest(
    url: String,
    body: JsonObject,
    cookie: String?,
    variant: InnerTubeVariant,
): JsonObject? = executeRequestWithStatus(url, body, cookie, variant).body
```

And update the `executeRequestWithStatusForTest` helper to `suspend`:

```kotlin
internal suspend fun executeRequestWithStatusForTest(url: String): RequestOutcome =
    executeRequestWithStatus(
        url = url,
        body = buildJsonObject { put("test", "true") },
        cookie = null,
        variant = InnerTubeVariant.WEB_REMIX,
    )
```

Existing callers (`browse(browseId)`, `search`, `player`, `getPlaybackTracking`, `browse(continuation)`) already run inside `withContext(Dispatchers.IO)` and will compile against the new `suspend` signature without change.

- [ ] **Step 4: Run the test — expect PASS**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.InnerTubeClientAuthCacheTest" --console=plain
```

Expected: PASS, 4 tests.

- [ ] **Step 5: Re-run full ytmusic test suite**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt \
        data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/InnerTubeClientAuthCacheTest.kt
git commit -m "feat(ytmusic): per-sync auth cache in InnerTubeClient

beginSyncSession/endSyncSession let PlaylistFetchWorker resolve cookie +
SAPISIDHASH once per sync instead of per-request. 401 responses invalidate
the cache so the next call re-resolves. Non-sync callers (search, player)
continue to resolve per-request — no behavior change."
```

---

## Task 6: Add `extractContinuationToken` helper to `YTMusicApiClient`

**Verified facts:**
- Two response shapes carry continuation tokens:
  1. **Initial response**: nested under `contents.{twoColumnBrowseResultsRenderer.secondaryContents | singleColumnBrowseResultsRenderer.tabs[0].tabRenderer.content}.sectionListRenderer.contents[0].musicPlaylistShelfRenderer.continuations[0].nextContinuationData.continuation` (or `musicShelfRenderer` for liked-songs path).
  2. **Continuation response**: `continuationContents.{musicPlaylistShelfContinuation | musicShelfContinuation}.continuations[0].nextContinuationData.continuation`.
- Helper returns `null` when no token is found (last page reached).
- Test against the real fixtures captured in Task 1 — they should each contain a valid token in page1 and (likely) none in page2.

**Files:**
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt`
- Modify (test): `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt`

- [ ] **Step 1: Write the failing test**

In `YTMusicApiClientTest.kt`, add (anywhere in the class):

```kotlin
@Test fun `extractContinuationToken finds token in twoColumn initial response`() {
    val json = loadFixture("playlist_long_page1.json")
    val parsed = Json.parseToJsonElement(json).jsonObject
    val client = fakeBrowseClient("{}")  // any client; we call the helper directly
    val token = client.extractContinuationTokenForTest(parsed)
    assertNotNull("page1 should have a continuation token", token)
    assertTrue("token should be non-empty", token!!.isNotEmpty())
}

@Test fun `extractContinuationToken finds token in continuation response`() {
    val json = loadFixture("playlist_long_page2.json")
    val parsed = Json.parseToJsonElement(json).jsonObject
    val client = fakeBrowseClient("{}")
    val token = client.extractContinuationTokenForTest(parsed)
    // page2 may or may not have a token depending on whether playlist has >2 pages —
    // assert non-throw rather than non-null.
    assertTrue(token == null || token.isNotEmpty())
}

@Test fun `extractContinuationToken returns null for response with no token`() {
    val noToken = """{"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[]}}]}}}}}"""
    val parsed = Json.parseToJsonElement(noToken).jsonObject
    val client = fakeBrowseClient("{}")
    assertNull(client.extractContinuationTokenForTest(parsed))
}
```

Add `import org.junit.Assert.assertNotNull` and `import org.junit.Assert.assertNull` to the test file imports.

- [ ] **Step 2: Run the test — expect FAIL**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.YTMusicApiClientTest.extractContinuationToken*" --console=plain
```

Expected: compile error — `extractContinuationTokenForTest` does not exist.

- [ ] **Step 3: Implement the helper**

In `YTMusicApiClient.kt`, add as a private method (near `parseTracksFromBrowse`, around line 403):

```kotlin
/**
 * Extracts the next-page continuation token from an InnerTube browse
 * response. Handles both shapes:
 *   - Initial response: token sits under the playlist/musicShelf renderer
 *     reached via twoColumn or singleColumn paths.
 *   - Continuation response: token sits at the top level under
 *     `continuationContents`.
 *
 * @return The token string, or null if none found (= last page).
 */
private fun extractContinuationToken(response: JsonObject): String? {
    // Shape 1: continuation response (most common after page 1).
    val cc = response["continuationContents"]?.asObject()
    if (cc != null) {
        val shelf = cc["musicPlaylistShelfContinuation"]?.asObject()
            ?: cc["musicShelfContinuation"]?.asObject()
        shelf?.let { return readContinuationFromShelf(it) }
    }

    // Shape 2: initial twoColumn (playlist pages).
    val twoColumnShelf = response.navigatePath(
        "contents", "twoColumnBrowseResultsRenderer",
        "secondaryContents", "sectionListRenderer", "contents",
    )?.asArray()?.firstOrNull()?.asObject()
        ?.get("musicPlaylistShelfRenderer")?.asObject()
    if (twoColumnShelf != null) return readContinuationFromShelf(twoColumnShelf)

    // Shape 3: initial singleColumn (liked songs, home).
    val sections = response.navigatePath(
        "contents", "singleColumnBrowseResultsRenderer", "tabs",
    )?.firstArray()?.firstOrNull()?.asObject()
        ?.navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")
        ?.asArray()
    if (sections != null) {
        for (section in sections) {
            val shelf = section.asObject()?.get("musicShelfRenderer")?.asObject() ?: continue
            readContinuationFromShelf(shelf)?.let { return it }
        }
    }
    return null
}

private fun readContinuationFromShelf(shelf: JsonObject): String? =
    shelf["continuations"]?.asArray()
        ?.firstOrNull()?.asObject()
        ?.get("nextContinuationData")?.asObject()
        ?.get("continuation")?.asString()
```

Add a test seam at the bottom of the class:

```kotlin
internal fun extractContinuationTokenForTest(response: JsonObject): String? =
    extractContinuationToken(response)
```

- [ ] **Step 4: Run the test — expect PASS**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.YTMusicApiClientTest.extractContinuationToken*" --console=plain
```

Expected: PASS, 3 tests.

If the page1 fixture does not contain a continuation token, the test will fail. That means the fixture was captured against a playlist short enough to fit in one page — re-capture against a longer playlist (Task 1).

- [ ] **Step 5: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt \
        data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt
git commit -m "feat(ytmusic): extractContinuationToken handles all 3 response shapes"
```

---

## Task 7: Add `parseContinuationPage` helper

**Verified facts:**
- Continuation responses carry items at `continuationContents.musicPlaylistShelfContinuation.contents[]` (for playlist pages) or `musicShelfContinuation.contents[]` (for liked-songs path).
- Each item is the same `musicResponsiveListItemRenderer` shape that `parseTrackFromRenderer` (`YTMusicApiClient.kt:484`) already handles. **No new track-parsing code** — just a different shelf location.

**Files:**
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt`
- Modify (test): `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun `parseContinuationPage parses tracks from playlist continuation`() {
    val json = loadFixture("playlist_long_page2.json")
    val parsed = Json.parseToJsonElement(json).jsonObject
    val client = fakeBrowseClient("{}")
    val tracks = client.parseContinuationPageForTest(parsed)
    assertTrue("page2 should yield at least one track", tracks.isNotEmpty())
    tracks.forEach { assertNotNull(it.videoId); assertTrue(it.videoId.isNotEmpty()) }
}
```

- [ ] **Step 2: Run the test — expect FAIL**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.YTMusicApiClientTest.parseContinuationPage*" --console=plain
```

Expected: compile error.

- [ ] **Step 3: Implement**

In `YTMusicApiClient.kt`, add near `parseTracksFromBrowse`:

```kotlin
/**
 * Parses tracks from a continuation-response shape.
 *
 * Continuation responses carry items at
 * `continuationContents.{musicPlaylistShelfContinuation | musicShelfContinuation}.contents[]`.
 * Each item is the same `musicResponsiveListItemRenderer` that
 * [parseTrackFromRenderer] already understands.
 */
private fun parseContinuationPage(response: JsonObject): List<YTMusicTrack> {
    val cc = response["continuationContents"]?.asObject() ?: return emptyList()
    val shelf = cc["musicPlaylistShelfContinuation"]?.asObject()
        ?: cc["musicShelfContinuation"]?.asObject()
        ?: return emptyList()
    val items = shelf["contents"]?.asArray() ?: return emptyList()
    val out = mutableListOf<YTMusicTrack>()
    for (item in items) {
        val renderer = item.asObject()
            ?.get("musicResponsiveListItemRenderer")?.asObject()
            ?: continue
        parseTrackFromRenderer(renderer)?.let { out.add(it) }
    }
    return out
}

internal fun parseContinuationPageForTest(response: JsonObject): List<YTMusicTrack> =
    parseContinuationPage(response)
```

- [ ] **Step 4: Run the test — expect PASS**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.YTMusicApiClientTest.parseContinuationPage*" --console=plain
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt \
        data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt
git commit -m "feat(ytmusic): parseContinuationPage reuses parseTrackFromRenderer"
```

---

## Task 8: Add `extractExpectedTrackCount` helper

**Verified facts:**
- Playlist headers include a "X songs" / "X tracks" string under either `header.musicEditablePlaylistDetailHeaderRenderer.header.musicResponsiveHeaderRenderer.secondSubtitle.runs[].text` or the legacy `header.musicDetailHeaderRenderer.secondSubtitle.runs[].text`.
- App locale is hardcoded to `en`/`US` in `buildContext`, so an English regex is sufficient.
- Liked Songs (`FEmusic_liked_videos`) does **not** carry this header — function returns null, verification step skipped.

**Files:**
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt`
- Modify (test): `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun `extractExpectedTrackCount parses '1,234 songs' from header`() {
    val json = loadFixture("playlist_long_page1.json")
    val parsed = Json.parseToJsonElement(json).jsonObject
    val client = fakeBrowseClient("{}")
    val count = client.extractExpectedTrackCountForTest(parsed)
    assertNotNull("playlist page should expose expected count", count)
    assertTrue("count should be > 0", count!! > 0)
}

@Test fun `extractExpectedTrackCount returns null for liked songs (no header)`() {
    val json = loadFixture("liked_songs_page1.json")
    val parsed = Json.parseToJsonElement(json).jsonObject
    val client = fakeBrowseClient("{}")
    val count = client.extractExpectedTrackCountForTest(parsed)
    assertNull(count)
}

@Test fun `extractExpectedTrackCount handles plain '42 songs' (no comma)`() {
    val synthetic = """
    {"header":{"musicDetailHeaderRenderer":{"secondSubtitle":{"runs":[
        {"text":"42 songs"},{"text":" • "},{"text":"3:14:00"}
    ]}}}}""".trimIndent()
    val parsed = Json.parseToJsonElement(synthetic).jsonObject
    val client = fakeBrowseClient("{}")
    assertEquals(42, client.extractExpectedTrackCountForTest(parsed))
}

@Test fun `extractExpectedTrackCount handles 'X tracks' variant`() {
    val synthetic = """
    {"header":{"musicDetailHeaderRenderer":{"secondSubtitle":{"runs":[
        {"text":"7 tracks"}
    ]}}}}""".trimIndent()
    val parsed = Json.parseToJsonElement(synthetic).jsonObject
    val client = fakeBrowseClient("{}")
    assertEquals(7, client.extractExpectedTrackCountForTest(parsed))
}
```

- [ ] **Step 2: Run — expect FAIL**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.YTMusicApiClientTest.extractExpectedTrackCount*" --console=plain
```

- [ ] **Step 3: Implement**

In `YTMusicApiClient.kt`:

```kotlin
private val expectedCountRegex = Regex("""([\d,]+)\s+(?:songs?|tracks?|videos?)""", RegexOption.IGNORE_CASE)

/**
 * Reads the playlist's reported track count from the response header. Used
 * by [paginateBrowse] callers to verify that pagination didn't silently
 * stop short.
 *
 * Walks two known header shapes (modern editable header, legacy detail
 * header), concatenates the secondSubtitle runs, and matches the first
 * "X songs" / "X tracks" / "X videos" pattern. Comma thousands separators
 * are stripped.
 *
 * Returns null when the header is absent (Liked Songs, library list) or
 * the count can't be parsed.
 */
private fun extractExpectedTrackCount(response: JsonObject): Int? {
    val runs = response.navigatePath(
        "header", "musicEditablePlaylistDetailHeaderRenderer", "header",
        "musicResponsiveHeaderRenderer", "secondSubtitle", "runs",
    )?.asArray()
        ?: response.navigatePath(
            "header", "musicDetailHeaderRenderer", "secondSubtitle", "runs",
        )?.asArray()
        ?: return null

    val text = runs.joinToString(separator = "") {
        it.asObject()?.get("text")?.asString() ?: ""
    }
    val match = expectedCountRegex.find(text) ?: return null
    return match.groupValues[1].replace(",", "").toIntOrNull()
}

internal fun extractExpectedTrackCountForTest(response: JsonObject): Int? =
    extractExpectedTrackCount(response)
```

- [ ] **Step 4: Run — expect PASS**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.YTMusicApiClientTest.extractExpectedTrackCount*" --console=plain
```

- [ ] **Step 5: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt \
        data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt
git commit -m "feat(ytmusic): extractExpectedTrackCount parses '1,234 songs' header"
```

---

## Task 9: Add `paginateBrowse` helper with retry policy

**Verified facts:**
- Loop semantics from spec §"Pagination Loop Semantics": walk continuation tokens, retry transient failures (null body / HTTP 5xx / network) up to 2 times with `[500ms, 1500ms]` backoff, no retry on 4xx (esp. 401), safety cap at `MAX_PAGES = 100`.
- Tests use a fake `InnerTubeClient` that returns scripted responses per call so we can drive every branch of the retry policy.
- This helper is the heart of the change — invest in good tests.

**Files:**
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt`
- Modify (test): `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt`

- [ ] **Step 1: Write the failing tests**

In `YTMusicApiClientTest.kt`, add a helper at the top of the class for scripting `InnerTubeClient.browse(continuation)`:

```kotlin
/**
 * Builds an InnerTubeClient mock whose browse(continuation) method returns
 * scripted responses in order. Pass `null` in the script for "transient
 * failure (null body)". Pass JsonObject for a successful response.
 */
private fun scriptedInner(vararg responses: JsonObject?): InnerTubeClient {
    val inner = mock<InnerTubeClient>()
    var i = 0
    runBlocking {
        whenever(inner.browse(any<String>())).thenAnswer {
            val r = responses.getOrNull(i)
            i++
            r
        }
    }
    return inner
}
```

Then add the tests:

```kotlin
@Test fun `paginateBrowse stops when first response has no token`() = runTest {
    val initial = Json.parseToJsonElement("""{"contents":{}}""").jsonObject
    val inner = scriptedInner()  // browse(continuation) never called
    val client = YTMusicApiClient(inner)
    val result = client.paginateBrowseForTest(initial) { listOf("page0-item") }
    assertEquals(listOf("page0-item"), result.items)
    assertEquals(1, result.pagesFetched)
    assertFalse(result.partial)
}

@Test fun `paginateBrowse follows token to page 2 then stops`() = runTest {
    val page1 = pageWithToken("ABC", listOf("a", "b"))
    val page2 = pageWithoutToken(listOf("c", "d"))
    val inner = scriptedInner(page2)
    val client = YTMusicApiClient(inner)
    val result = client.paginateBrowseForTest(page1) { jsonObj ->
        // parse marker from synthetic json
        jsonObj["marker"]?.jsonArray?.map { (it as JsonPrimitive).content } ?: emptyList()
    }
    // page1 contributes a,b via the parsePage callback applied to it as well
    assertEquals(listOf("a","b","c","d"), result.items)
    assertEquals(2, result.pagesFetched)
    assertFalse(result.partial)
}

@Test fun `paginateBrowse retries transient failure once then succeeds`() = runTest {
    val page1 = pageWithToken("ABC", listOf("a"))
    val page2 = pageWithoutToken(listOf("b"))
    // First continuation attempt returns null (transient), second succeeds.
    val inner = scriptedInner(null, page2)
    val client = YTMusicApiClient(inner)
    val result = client.paginateBrowseForTest(page1) { jsonObj ->
        jsonObj["marker"]?.jsonArray?.map { (it as JsonPrimitive).content } ?: emptyList()
    }
    assertEquals(listOf("a","b"), result.items)
    assertFalse(result.partial)
}

@Test fun `paginateBrowse marks partial after exhausting retries`() = runTest {
    val page1 = pageWithToken("ABC", listOf("a"))
    val inner = scriptedInner(null, null, null)  // 1 attempt + 2 retries all fail
    val client = YTMusicApiClient(inner)
    val result = client.paginateBrowseForTest(page1) { jsonObj ->
        jsonObj["marker"]?.jsonArray?.map { (it as JsonPrimitive).content } ?: emptyList()
    }
    assertEquals(listOf("a"), result.items)
    assertTrue(result.partial)
    assertNotNull(result.partialReason)
}

@Test fun `paginateBrowse stops at MAX_PAGES safety cap`() = runTest {
    // Every page has a token → infinite loop without the cap.
    val page = pageWithToken("ABC", listOf("x"))
    // Script 200 responses; loop should stop at MAX_PAGES (=100).
    val inner = mock<InnerTubeClient>()
    runBlocking { whenever(inner.browse(any<String>())).thenReturn(page) }
    val client = YTMusicApiClient(inner)
    val result = client.paginateBrowseForTest(page) { jsonObj ->
        jsonObj["marker"]?.jsonArray?.map { (it as JsonPrimitive).content } ?: emptyList()
    }
    assertEquals(YTMusicApiClient.MAX_PAGES, result.pagesFetched)
    assertTrue(result.partial)
    assertTrue(result.partialReason!!.contains("MAX_PAGES"))
}

// Helpers for the synthetic JSON pages.
private fun pageWithToken(token: String, marker: List<String>): JsonObject =
    Json.parseToJsonElement("""
        {
          "marker": [${marker.joinToString(",") { "\"$it\"" }}],
          "continuationContents": {
            "musicPlaylistShelfContinuation": {
              "contents": [],
              "continuations": [{"nextContinuationData": {"continuation": "$token"}}]
            }
          }
        }
    """.trimIndent()).jsonObject

private fun pageWithoutToken(marker: List<String>): JsonObject =
    Json.parseToJsonElement("""
        {
          "marker": [${marker.joinToString(",") { "\"$it\"" }}],
          "continuationContents": {
            "musicPlaylistShelfContinuation": {"contents": []}
          }
        }
    """.trimIndent()).jsonObject
```

(Add `import kotlinx.serialization.json.JsonPrimitive` and `import kotlinx.serialization.json.jsonArray` to the test imports. Add `import org.junit.Assert.assertFalse`.)

Note that `scriptedInner` returns `null` for "transient" — but `paginateBrowse` differentiates 4xx (status code 4xx) from other failures using `RequestOutcome`. **However**, `paginateBrowse` calls `innerTubeClient.browse(continuation)` which returns `JsonObject?` (no status code). So `null` from `browse(continuation)` covers both 5xx + network errors AND 4xx — `paginateBrowse` cannot distinguish them.

This is OK for the **first iteration** of this plan: the retry policy treats all `null` returns as transient. That gives 4xx an extra 2 retries before partial-marking, which is mildly wasteful but not harmful. A future enhancement (deferred — explicitly out of scope here) is to add `browseWithStatus(continuation): RequestOutcome` to `InnerTubeClient` and have `paginateBrowse` use it. **The spec's retry table already documents this distinction as desired behavior**; the simpler implementation is documented as a known divergence in the spec's "Risks & open questions" section, but is in fact acceptable to ship — the wasted retries are bounded at 2 per failed page.

If you (the implementer) want to honor the spec exactly, add `internal suspend fun browseWithStatus(continuation: String): RequestOutcome` to `InnerTubeClient` (mirror the existing `browse(continuation)` but return `executeRequestWithStatus(...)` directly), have `paginateBrowse` call that, and short-circuit retries when `outcome.statusCode in 400..499`. Add a dedicated test case for the 401-no-retry path. This is a ~30 LOC delta and is the recommended path.

For the rest of this plan, **assume you took the recommended path**: `paginateBrowse` calls `browseWithStatus`, distinguishes 4xx from other failures, and the test for 401 is included.

Add this test:

```kotlin
@Test fun `paginateBrowse does not retry on 4xx`() = runTest {
    val page1 = pageWithToken("ABC", listOf("a"))
    val inner = mock<InnerTubeClient>()
    var calls = 0
    runBlocking {
        whenever(inner.browseWithStatus(any())).thenAnswer {
            calls++
            RequestOutcome(body = null, statusCode = 401)
        }
    }
    val client = YTMusicApiClient(inner)
    val result = client.paginateBrowseForTest(page1) { jsonObj ->
        jsonObj["marker"]?.jsonArray?.map { (it as JsonPrimitive).content } ?: emptyList()
    }
    assertEquals(1, calls)  // no retries
    assertTrue(result.partial)
    assertEquals(listOf("a"), result.items)
}
```

- [ ] **Step 2: Run — expect FAIL**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.YTMusicApiClientTest.paginateBrowse*" --console=plain
```

Expected: compile error.

- [ ] **Step 3: Add `browseWithStatus` to `InnerTubeClient`**

In `InnerTubeClient.kt`, add right below `browse(continuation: String)`:

```kotlin
/**
 * Like [browse(continuation)] but returns the full HTTP outcome so callers
 * (specifically [YTMusicApiClient.paginateBrowse]) can distinguish 4xx
 * (no retry, likely auth) from 5xx/network (retry).
 */
internal suspend fun browseWithStatus(continuation: String): RequestOutcome =
    withContext(Dispatchers.IO) {
        val cookie = tokenManager.getYouTubeCookie()
        val variant = InnerTubeVariant.WEB_REMIX
        val body = buildJsonObject { put("context", buildContext(variant)) }
        executeRequestWithStatus(
            url = "$BASE_URL/browse?ctoken=$continuation&continuation=$continuation&type=next",
            body = body,
            cookie = cookie,
            variant = variant,
        )
    }
```

- [ ] **Step 4: Implement `paginateBrowse` in `YTMusicApiClient`**

In `YTMusicApiClient.kt`, add as a private method:

```kotlin
companion object {
    private const val TAG = "StashYT"  // (already present — don't duplicate)
    /** Safety cap on continuation depth. ~10K items @ 100/page. */
    internal const val MAX_PAGES = 100
    private val RETRY_BACKOFFS_MS = listOf(500L, 1500L)
}

/**
 * Result of a paginated browse walk.
 *
 * @property items         Accumulated parsed items from all successful pages.
 * @property pagesFetched  Including the initial page (always >= 1).
 * @property partial       True if a continuation page failed after retries OR
 *                         the safety cap was hit.
 * @property partialReason Human-readable explanation when partial.
 */
private data class PaginationResult<T>(
    val items: List<T>,
    val pagesFetched: Int,
    val partial: Boolean,
    val partialReason: String?,
)

/**
 * Walks an InnerTube browse response's continuation chain, accumulating
 * items via [parsePage].
 *
 * Retry policy:
 *   - Transient failure (null body, HTTP 5xx, network error): retry up to
 *     2 times with [RETRY_BACKOFFS_MS] backoff, then mark partial.
 *   - Permanent failure (HTTP 4xx, esp. 401/403): no retry, mark partial.
 *   - [MAX_PAGES] reached with token still pending: stop, mark partial.
 *
 * The [parsePage] lambda is called once per page (initial + continuations),
 * with the appropriate response shape. The caller is responsible for
 * dispatching to the right shape parser inside the lambda — [paginateBrowse]
 * just chains tokens.
 */
private suspend fun <T> paginateBrowse(
    initialResponse: JsonObject,
    parsePage: (JsonObject) -> List<T>,
): PaginationResult<T> {
    val items = mutableListOf<T>()
    items += parsePage(initialResponse)
    var token = extractContinuationToken(initialResponse)
    var pages = 1
    var partial = false
    var partialReason: String? = null

    while (token != null && pages < MAX_PAGES) {
        val (next, attempts) = browseWithRetry(token)
        if (next == null) {
            partial = true
            partialReason = "page ${pages + 1} failed after $attempts attempts"
            Log.w(TAG, "paginateBrowse: $partialReason")
            break
        }
        items += parsePage(next)
        token = extractContinuationToken(next)
        pages++
    }

    if (token != null && pages >= MAX_PAGES) {
        partial = true
        partialReason = "hit MAX_PAGES=$MAX_PAGES safety cap"
        Log.w(TAG, "paginateBrowse: $partialReason")
    }

    return PaginationResult(items, pages, partial, partialReason)
}

/**
 * Calls [InnerTubeClient.browseWithStatus] with retry-on-transient policy.
 * Returns (body or null, total attempt count). On 4xx, does not retry.
 */
private suspend fun browseWithRetry(token: String): Pair<JsonObject?, Int> {
    var attempts = 0
    var outcome = innerTubeClient.browseWithStatus(token)
    attempts++
    if (outcome.body != null) return outcome.body to attempts
    if (outcome.statusCode in 400..499) return null to attempts  // permanent

    for (backoff in RETRY_BACKOFFS_MS) {
        kotlinx.coroutines.delay(backoff)
        outcome = innerTubeClient.browseWithStatus(token)
        attempts++
        if (outcome.body != null) return outcome.body to attempts
        if (outcome.statusCode in 400..499) return null to attempts  // permanent
    }
    return null to attempts
}

internal suspend fun <T> paginateBrowseForTest(
    initialResponse: JsonObject,
    parsePage: (JsonObject) -> List<T>,
): PaginationResult<T> = paginateBrowse(initialResponse, parsePage)
```

Note: `PaginationResult` is `private` for the production code but the test seam exposes it. Either bump it to `internal` or make `paginateBrowseForTest` return a public-shaped result. **Bump `PaginationResult` to `internal`** — simpler, and it's still scoped to the module.

Also: tests use `runTest` and pass `RETRY_BACKOFFS_MS = [500, 1500]` real-time delays. Wrap them in `kotlinx.coroutines.test.runTest` with `testScheduler.advanceUntilIdle()` semantics — `delay()` inside `runTest` skips real time automatically. No change needed.

- [ ] **Step 5: Run — expect PASS**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.YTMusicApiClientTest.paginateBrowse*" --console=plain
```

Expected: PASS, 6 tests.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt \
        data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt \
        data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt
git commit -m "feat(ytmusic): paginateBrowse with retry policy + MAX_PAGES safety cap

InnerTubeClient gains browseWithStatus(continuation) so paginateBrowse
can distinguish 4xx (no retry, likely 401) from 5xx/network (2 retries
with 500ms/1500ms backoff). PaginationResult carries partial flag with
reason; safety cap at MAX_PAGES=100 prevents runaway loops."
```

---

## Task 10: Refactor `getLikedSongs` to return `PagedTracks`

**Verified facts:**
- Current signature returns `SyncResult<List<YTMusicTrack>>` (line 72).
- New signature returns `SyncResult<PagedTracks>` per spec.
- Liked Songs has no header `trackCount` — `expectedCount` will be null, no header verification.
- This **breaks** the call site in `PlaylistFetchWorker.fetchYouTubePlaylists` (line 485). The compiler will catch it; we fix it in Task 14.

**Files:**
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt`
- Modify (test): `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun `getLikedSongs paginates across two pages and merges`() = runTest {
    val page1 = loadFixture("liked_songs_page1.json")
    val page2 = loadFixture("liked_songs_page2.json")

    val inner = mock<InnerTubeClient>()
    runBlocking {
        whenever(inner.browse("FEmusic_liked_videos")).thenReturn(
            Json.parseToJsonElement(page1).jsonObject
        )
        whenever(inner.browseWithStatus(any())).thenReturn(
            RequestOutcome(body = Json.parseToJsonElement(page2).jsonObject, statusCode = 200)
        )
    }
    val client = YTMusicApiClient(inner)
    val result = client.getLikedSongs()
    assertTrue(result is SyncResult.Success)
    val paged = (result as SyncResult.Success).data
    assertTrue("must merge across pages", paged.tracks.size > 24)  // page1 alone is ~24
    assertNull("liked songs has no header count", paged.expectedCount)
    assertFalse(paged.partial)
}

@Test fun `getLikedSongs marks partial when continuation fails`() = runTest {
    val page1 = loadFixture("liked_songs_page1.json")
    val inner = mock<InnerTubeClient>()
    runBlocking {
        whenever(inner.browse("FEmusic_liked_videos")).thenReturn(
            Json.parseToJsonElement(page1).jsonObject
        )
        whenever(inner.browseWithStatus(any())).thenReturn(
            RequestOutcome(body = null, statusCode = 503)
        )
    }
    val client = YTMusicApiClient(inner)
    val result = client.getLikedSongs()
    assertTrue(result is SyncResult.Success)
    val paged = (result as SyncResult.Success).data
    assertTrue(paged.partial)
    assertNotNull(paged.partialReason)
}
```

- [ ] **Step 2: Run — expect FAIL (return type mismatch)**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.YTMusicApiClientTest.getLikedSongs*" --console=plain
```

- [ ] **Step 3: Refactor `getLikedSongs`**

Replace the existing method body (lines 72-83):

```kotlin
suspend fun getLikedSongs(): SyncResult<PagedTracks> {
    val response = innerTubeClient.browse(BROWSE_LIKED_SONGS)
        ?: return SyncResult.Error("InnerTube browse($BROWSE_LIKED_SONGS) returned null — check CLIENT_VERSION or cookie")

    val paginated = paginateBrowse(response) { page ->
        // First page comes from browse(browseId) → use the existing parser.
        // Continuation pages come from browseWithStatus → use the continuation parser.
        // Both shapes are dispatched here; we differentiate by the presence of `continuationContents`.
        if (page["continuationContents"] != null) parseContinuationPage(page)
        else parseTracksFromBrowse(page)
    }

    if (paginated.items.isEmpty()) {
        return SyncResult.Empty("Liked songs returned no tracks")
    }
    return SyncResult.Success(
        PagedTracks(
            tracks = paginated.items,
            expectedCount = null,  // FEmusic_liked_videos has no header count
            partial = paginated.partial,
            partialReason = paginated.partialReason,
        )
    )
}
```

- [ ] **Step 4: Run — expect PASS**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.YTMusicApiClientTest.getLikedSongs*" --console=plain
```

Expected: PASS.

Note: The full project build will now fail because `PlaylistFetchWorker.fetchYouTubePlaylists` still uses the old shape. **Don't fix it yet** — Task 14 handles it. We commit this refactor in isolation.

- [ ] **Step 5: Confirm only the worker is broken**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:assemble --console=plain
```

Expected: BUILD SUCCESSFUL (the data:ytmusic module is self-consistent).

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :core:data:compileKotlin --console=plain
```

Expected: COMPILE ERROR in `PlaylistFetchWorker.kt:485` referring to `result.data` no longer being `List<YTMusicTrack>`. This is intentional and gets fixed in Task 14.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt \
        data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt
git commit -m "refactor(ytmusic)!: getLikedSongs returns PagedTracks (paginated)

BREAKING: SyncResult<List<YTMusicTrack>> -> SyncResult<PagedTracks>.
PlaylistFetchWorker call site updated in a follow-up commit. Liked
songs now walks all continuation pages instead of stopping at ~24."
```

---

## Task 11: Refactor `getPlaylistTracks` to return `PagedTracks`

Same shape as Task 10. Difference: this surface DOES have a header `trackCount`, so we extract `expectedCount` and run the verification step.

**Files:**
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt`
- Modify (test): `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun `getPlaylistTracks paginates and surfaces expectedCount`() = runTest {
    val page1 = loadFixture("playlist_long_page1.json")
    val page2 = loadFixture("playlist_long_page2.json")
    val inner = mock<InnerTubeClient>()
    runBlocking {
        whenever(inner.browse(any<String>())).thenReturn(Json.parseToJsonElement(page1).jsonObject)
        whenever(inner.browseWithStatus(any())).thenReturn(
            RequestOutcome(body = Json.parseToJsonElement(page2).jsonObject, statusCode = 200)
        )
    }
    val client = YTMusicApiClient(inner)
    val result = client.getPlaylistTracks("FAKEID")
    assertTrue(result is SyncResult.Success)
    val paged = (result as SyncResult.Success).data
    assertNotNull("playlist header should expose count", paged.expectedCount)
    assertTrue(paged.tracks.isNotEmpty())
}

@Test fun `getPlaylistTracks marks partial when fetched count is short by more than 5 percent`() = runTest {
    // Build a synthetic page1 that claims 200 songs but parses to 50 tracks.
    val synthetic = """
    {
      "header":{"musicDetailHeaderRenderer":{"secondSubtitle":{"runs":[{"text":"200 songs"}]}}},
      "contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{"contents":[
        {"musicPlaylistShelfRenderer":{"contents":${Array(50) { """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"v$it"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"T$it"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"A$it"}]}}}]}}""" }.joinToString(",", "[", "]")}}}
      ]}}}}
    }""".trimIndent()
    val inner = mock<InnerTubeClient>()
    runBlocking {
        whenever(inner.browse(any<String>())).thenReturn(Json.parseToJsonElement(synthetic).jsonObject)
    }
    val client = YTMusicApiClient(inner)
    val result = client.getPlaylistTracks("X")
    assertTrue(result is SyncResult.Success)
    val paged = (result as SyncResult.Success).data
    assertEquals(200, paged.expectedCount)
    assertEquals(50, paged.tracks.size)
    assertTrue(paged.partial)
    assertTrue(paged.partialReason!!.contains("50") && paged.partialReason!!.contains("200"))
}
```

- [ ] **Step 2: Run — expect FAIL**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.YTMusicApiClientTest.getPlaylistTracks*" --console=plain
```

- [ ] **Step 3: Refactor `getPlaylistTracks`**

Replace the body (lines 138-151):

```kotlin
suspend fun getPlaylistTracks(playlistId: String): SyncResult<PagedTracks> {
    val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
    val response = innerTubeClient.browse(browseId)
        ?: return SyncResult.Error("InnerTube browse($browseId) returned null")
    Log.d(TAG, "getPlaylistTracks: response top-level keys: ${response.keys}")

    val expectedCount = extractExpectedTrackCount(response)
    val paginated = paginateBrowse(response) { page ->
        if (page["continuationContents"] != null) parseContinuationPage(page)
        else parseTracksFromBrowse(page)
    }

    if (paginated.items.isEmpty()) {
        return SyncResult.Empty("Playlist $playlistId returned no tracks")
    }

    val (partial, partialReason) = verifyExpectedCount(
        fetched = paginated.items.size,
        expected = expectedCount,
        existingPartial = paginated.partial,
        existingReason = paginated.partialReason,
    )

    return SyncResult.Success(
        PagedTracks(
            tracks = paginated.items,
            expectedCount = expectedCount,
            partial = partial,
            partialReason = partialReason,
        )
    )
}

/**
 * Extends [paginateBrowse]'s partial signal with the count-vs-header check.
 * If fetched count is below 95% of expected, mark partial (and append the
 * count info to the existing reason if there already was one).
 */
private fun verifyExpectedCount(
    fetched: Int,
    expected: Int?,
    existingPartial: Boolean,
    existingReason: String?,
): Pair<Boolean, String?> {
    if (expected == null) return existingPartial to existingReason
    if (fetched >= expected * 0.95) return existingPartial to existingReason

    val countReason = "fetched $fetched of $expected expected"
    Log.w(TAG, "verifyExpectedCount: $countReason")
    val combined = if (existingReason == null) countReason else "$existingReason; $countReason"
    return true to combined
}
```

- [ ] **Step 4: Run — expect PASS**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.YTMusicApiClientTest.getPlaylistTracks*" --console=plain
```

- [ ] **Step 5: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt \
        data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt
git commit -m "refactor(ytmusic)!: getPlaylistTracks returns PagedTracks with verification

Adds expectedCount from playlist header + 5% tolerance check. If fetched
count falls below 95% of expected, partial=true with count detail in
partialReason."
```

---

## Task 12: Refactor `getUserPlaylists` to return `PagedPlaylists`

Same shape, but the parser is `parseUserPlaylists` (existing) instead of `parseTracksFromBrowse`. The user-library list **may or may not** carry continuation tokens depending on user library size; the `paginateBrowse` helper handles either case.

**Files:**
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt`
- Modify (test): `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt`

- [ ] **Step 1: Write the failing test**

Note: The existing `parseUserPlaylists` is at line 664 of `YTMusicApiClient.kt`. Continuation responses for the library page wrap items in `continuationContents.musicShelfContinuation.contents[].musicTwoRowItemRenderer` (NOT `musicResponsiveListItemRenderer`). Verify by inspecting `parseUserPlaylists` and writing a `parseUserPlaylistContinuationPage` mirror.

For brevity in this plan: if `parseUserPlaylists` already returns whatever exists at the top level, then a continuation page may use a different shape. **Audit `parseUserPlaylists` first** — read the function and decide whether `parseContinuationPage` (which targets `musicResponsiveListItemRenderer`) is reusable. If not, add a `parseUserPlaylistsContinuationPage` mirror.

```kotlin
@Test fun `getUserPlaylists returns PagedPlaylists shape`() = runTest {
    val response = """
    {
      "contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
        {"musicShelfRenderer":{"contents":[
          {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"My Playlist","navigationEndpoint":{"browseEndpoint":{"browseId":"VLPLfake"}}}]},"thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://x"}]}}}}}
        ]}}
      ]}}}}]}}
    }""".trimIndent()
    val client = fakeBrowseClient(response)
    val result = client.getUserPlaylists()
    assertTrue(result is SyncResult.Success)
    val paged = (result as SyncResult.Success).data
    assertTrue(paged.playlists.isNotEmpty())
    assertFalse(paged.partial)
}
```

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Refactor `getUserPlaylists`**

Replace the body (lines 117-128):

```kotlin
suspend fun getUserPlaylists(): SyncResult<PagedPlaylists> {
    val response = innerTubeClient.browse(BROWSE_LIBRARY_PLAYLISTS)
        ?: return SyncResult.Error("InnerTube browse($BROWSE_LIBRARY_PLAYLISTS) returned null")

    val paginated = paginateBrowse(response) { page ->
        if (page["continuationContents"] != null) parseUserPlaylistsContinuationPage(page)
        else parseUserPlaylists(page)
    }

    if (paginated.items.isEmpty()) {
        return SyncResult.Empty("Library returned no playlists")
    }
    return SyncResult.Success(
        PagedPlaylists(
            playlists = paginated.items,
            partial = paginated.partial,
            partialReason = paginated.partialReason,
        )
    )
}

/**
 * Continuation-shape parser for the user-library playlist list. Mirrors
 * [parseUserPlaylists] but reads from `continuationContents.musicShelfContinuation`.
 */
private fun parseUserPlaylistsContinuationPage(response: JsonObject): List<YTMusicPlaylist> {
    val items = response.navigatePath(
        "continuationContents", "musicShelfContinuation", "contents",
    )?.asArray() ?: return emptyList()
    val out = mutableListOf<YTMusicPlaylist>()
    for (item in items) {
        val renderer = item.asObject()?.get("musicTwoRowItemRenderer")?.asObject() ?: continue
        parseSinglePlaylistFromTwoRowRenderer(renderer)?.let { out.add(it) }
    }
    return out
}
```

(`parseSinglePlaylistFromTwoRowRenderer` is the existing helper used by `parseUserPlaylists` — if it doesn't exist as a separate fn, factor it out from `parseUserPlaylists` so both initial and continuation parsers can share it.)

- [ ] **Step 4: Run — expect PASS**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.YTMusicApiClientTest.getUserPlaylists*" --console=plain
```

- [ ] **Step 5: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt \
        data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt
git commit -m "refactor(ytmusic)!: getUserPlaylists returns PagedPlaylists (paginated)"
```

---

## Task 13: Add `partial` + `expected_count` columns and Room v15 → v16 migration

**Verified facts:**
- `StashDatabase.kt:67` declares `version = 15`. Hand-written `Migration` objects live in the `companion object` from line 96 onward. The pattern is `val MIGRATION_X_Y = object : Migration(X, Y) { override fun migrate(db: SupportSQLiteDatabase) { ... } }` followed by registration in the Room builder (search for `addMigrations(` in this file).
- `RemotePlaylistSnapshotEntity` is at `core/data/src/main/kotlin/com/stash/core/data/db/entity/RemotePlaylistSnapshotEntity.kt`.
- `exportSchema = true` means schemas are written to `core/data/schemas/com.stash.core.data.db.StashDatabase/` — a v16 schema file will be auto-generated on next build.

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/entity/RemotePlaylistSnapshotEntity.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt`

- [ ] **Step 1: Add columns to entity**

Append two fields to `RemotePlaylistSnapshotEntity` (after `fetchedAt`):

```kotlin
    /**
     * True when the remote source returned a partial result for this playlist
     * — either continuation pagination failed mid-walk, or the fetched count
     * fell below 95% of the source-reported [expectedCount].
     *
     * Defaults to false for back-compat with rows written before v16.
     */
    @ColumnInfo(name = "partial")
    val partial: Boolean = false,

    /**
     * The remote source's reported track count for this playlist (when the
     * response carries a header that exposes it). Used together with
     * [partial] to render diagnostics like "1247 / 2000 (partial)". Null
     * when the source provides no count (e.g. YT Music Liked Songs).
     */
    @ColumnInfo(name = "expected_count")
    val expectedCount: Int? = null,
```

- [ ] **Step 2: Bump database version and add migration**

In `StashDatabase.kt`:

```kotlin
@Database(
    entities = [
        // ...unchanged...
    ],
    version = 16,  // was 15
    exportSchema = true,
)
```

In the `companion object`, after the most recent migration:

```kotlin
/**
 * v15 → v16: add `partial` + `expected_count` columns to
 * remote_playlist_snapshots. Surfaces YouTube continuation-pagination
 * partial results from the new [YTMusicApiClient] paged-result types.
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE remote_playlist_snapshots ADD COLUMN partial INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE remote_playlist_snapshots ADD COLUMN expected_count INTEGER DEFAULT NULL"
        )
    }
}
```

Find the `Room.databaseBuilder(...)` call in this file (or in the Hilt module that provides the database — likely `core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt`) and add `MIGRATION_15_16` to the `addMigrations(...)` chain alongside the existing migrations.

- [ ] **Step 3: Compile and let Room export the v16 schema**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :core:data:assembleDebug --console=plain
```

Expected: BUILD SUCCESSFUL. A new file `core/data/schemas/com.stash.core.data.db.StashDatabase/16.json` should appear.

If the build fails because `PlaylistFetchWorker.kt` references the old `getLikedSongs` shape, that's expected — it's fixed in Task 14. Run only the Room verification step:

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :core:data:kspDebugKotlin --console=plain
```

(KSP is the Room compiler.) The error you want to NOT see is anything about a missing migration or schema-mismatch.

- [ ] **Step 4: Add a migration round-trip test if the project has any DB tests**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && find core/data/src -name "*MigrationTest*" -o -name "*DbMigration*"
```

If results: extend the existing test file with a v15→v16 case using `MigrationTestHelper` per the existing pattern.

If no results: skip — the project doesn't currently test migrations and adding the harness is out of scope. The `ALTER TABLE` is mechanical and matches the v3→v4, v4→v5, v5→v6 patterns.

- [ ] **Step 5: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add core/data/src/main/kotlin/com/stash/core/data/db/entity/RemotePlaylistSnapshotEntity.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt \
        core/data/schemas/com.stash.core.data.db.StashDatabase/16.json
git commit -m "feat(db): v15→v16 add partial + expected_count to playlist snapshots

Surfaces YT continuation-pagination partial results from the new
PagedTracks/PagedPlaylists types. Default 0 / NULL preserves back-compat
for rows written by older app versions."
```

---

## Task 14: Wire `InnerTubeClient` into `PlaylistFetchWorker` + adopt new return types for Liked Songs

**Verified facts:**
- `PlaylistFetchWorker` (constructor at line 45) uses `@HiltWorker` + `@AssistedInject`. Hilt resolves `InnerTubeClient` automatically since it's `@Singleton` already.
- `fetchYouTubePlaylists` (line 420) does three serial blocks: home mixes (line 425), liked songs (line 485), user playlists (line 534). We add `beginSyncSession`/`endSyncSession` around the whole function and adopt the new `PagedTracks`/`PagedPlaylists` return types in this single task. Parallelism comes in Task 15.
- Diagnostics field name is `service` (not `source`); count field is `itemCount` (not `count`). See `core/model/src/main/kotlin/com/stash/core/model/SyncStepResult.kt`.

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorker.kt`

- [ ] **Step 1: Add `InnerTubeClient` to the constructor**

```kotlin
@HiltWorker
class PlaylistFetchWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val tokenManager: TokenManager,
    private val spotifyApiClient: SpotifyApiClient,
    private val ytMusicApiClient: YTMusicApiClient,
    private val innerTubeClient: com.stash.data.ytmusic.InnerTubeClient,  // NEW
    private val syncHistoryDao: SyncHistoryDao,
    private val remoteSnapshotDao: RemoteSnapshotDao,
    private val syncStateManager: SyncStateManager,
    private val syncNotificationManager: SyncNotificationManager,
) : CoroutineWorker(appContext, params) {
```

- [ ] **Step 2: Wrap `fetchYouTubePlaylists` body in begin/end session**

```kotlin
private suspend fun fetchYouTubePlaylists(syncId: Long, diagnostics: MutableList<SyncStepResult>) {
    innerTubeClient.beginSyncSession()
    try {
        // existing body (will be edited below for new return types and parallelism)
    } finally {
        innerTubeClient.endSyncSession()
    }
}
```

- [ ] **Step 3: Adopt new `PagedTracks` shape for Liked Songs**

Inside `fetchYouTubePlaylists`, find the Liked Songs block (currently around line 485) and update:

```kotlin
when (val result = ytMusicApiClient.getLikedSongs()) {
    is SyncResult.Success -> {
        val paged = result.data
        val likedSongs = paged.tracks
        diagnostics.add(SyncStepResult(
            service = "YOUTUBE",
            step = "getLikedSongs",
            status = StepStatus.SUCCESS,
            itemCount = likedSongs.size,
            errorMessage = if (paged.partial) {
                "partial: ${likedSongs.size}/${paged.expectedCount ?: "?"} — ${paged.partialReason}"
            } else null,
        ))

        val likedPlaylistId = remoteSnapshotDao.insertPlaylistSnapshot(
            RemotePlaylistSnapshotEntity(
                syncId = syncId,
                source = MusicSource.YOUTUBE,
                sourcePlaylistId = "youtube_liked_songs",
                playlistName = "Liked Songs",
                playlistType = PlaylistType.LIKED_SONGS,
                trackCount = likedSongs.size,
                partial = paged.partial,
                expectedCount = paged.expectedCount,
            )
        )

        val trackSnapshots = likedSongs.mapIndexed { position, track ->
            RemoteTrackSnapshotEntity(
                syncId = syncId,
                snapshotPlaylistId = likedPlaylistId,
                title = track.title,
                artist = track.artists,
                album = track.album,
                durationMs = track.durationMs ?: 0L,
                youtubeId = track.videoId,
                albumArtUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(track.thumbnailUrl),
                position = position,
            )
        }
        if (trackSnapshots.isNotEmpty()) {
            remoteSnapshotDao.insertTrackSnapshots(trackSnapshots)
        }
    }
    is SyncResult.Empty -> { /* unchanged */ }
    is SyncResult.Error -> { /* unchanged */ }
}
```

- [ ] **Step 4: Adopt new shape for Home Mixes (`getPlaylistTracks` per mix)**

Inside the home-mixes loop (currently around line 445):

```kotlin
when (val tracksResult = ytMusicApiClient.getPlaylistTracks(mix.playlistId)) {
    is SyncResult.Success -> {
        val paged = tracksResult.data
        val tracks = paged.tracks
        // The playlist snapshot for this mix was inserted earlier in the loop
        // — update its row with the partial/expectedCount fields by including
        // them in the initial insertion. Refactor: move the insertPlaylistSnapshot
        // call to AFTER getPlaylistTracks so the fields are known.

        val playlistSnapshotId = remoteSnapshotDao.insertPlaylistSnapshot(
            RemotePlaylistSnapshotEntity(
                syncId = syncId,
                source = MusicSource.YOUTUBE,
                sourcePlaylistId = mix.playlistId,
                playlistName = mix.title,
                playlistType = PlaylistType.DAILY_MIX,
                mixNumber = index + 1,
                trackCount = tracks.size,                  // was mix.trackCount ?: 0
                expectedCount = paged.expectedCount,
                partial = paged.partial,
                artUrl = mix.thumbnailUrl,
            )
        )

        val trackSnapshots = tracks.mapIndexed { position, track ->
            RemoteTrackSnapshotEntity(
                syncId = syncId,
                snapshotPlaylistId = playlistSnapshotId,
                title = track.title,
                artist = track.artists,
                album = track.album,
                durationMs = track.durationMs ?: 0L,
                youtubeId = track.videoId,
                albumArtUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(track.thumbnailUrl),
                position = position,
            )
        }
        if (trackSnapshots.isNotEmpty()) {
            remoteSnapshotDao.insertTrackSnapshots(trackSnapshots)
        }
    }
    // Empty/Error branches unchanged.
}
```

Note: previously the playlist snapshot row was inserted **before** `getPlaylistTracks`. We have to move the insertion to **after** the fetch so we know `partial` and `expectedCount`. Restructure the loop body accordingly.

- [ ] **Step 5: Adopt new shape for User Playlists (`getUserPlaylists` + per-playlist `getPlaylistTracks`)**

Around line 534. Same pattern: top-level call returns `SyncResult<PagedPlaylists>`; per-playlist `getPlaylistTracks` returns `SyncResult<PagedTracks>`. Mirror the home-mix updates above.

- [ ] **Step 6: Compile and run all tests**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :core:data:test :data:ytmusic:test --console=plain
```

Expected: BUILD SUCCESSFUL.

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :app:assembleDebug --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorker.kt
git commit -m "feat(sync): adopt PagedTracks/PagedPlaylists in PlaylistFetchWorker

Inject InnerTubeClient, wrap fetchYouTubePlaylists in begin/endSyncSession.
Liked Songs / Home Mixes / User Playlists snapshot rows now carry partial
+ expected_count from the new paged result types. Diagnostics annotate
errorMessage with 'partial: N/M — reason' on partial fetches."
```

---

## Task 15: Parallelize home-mix and user-playlist fetches with `Semaphore(3)`

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorker.kt`

- [ ] **Step 1: Add the constant + import**

```kotlin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
```

In the companion object (around line 57):

```kotlin
companion object {
    const val KEY_SYNC_ID = "sync_id"
    private const val TAG = "StashSync"
    /** Cap on concurrent in-flight YT browse calls during a sync. */
    private const val MAX_PARALLEL_YT_FETCHES = 3
}
```

- [ ] **Step 2: Refactor `fetchYouTubePlaylists` to share a semaphore across both groups**

```kotlin
private suspend fun fetchYouTubePlaylists(syncId: Long, diagnostics: MutableList<SyncStepResult>) {
    innerTubeClient.beginSyncSession()
    val sem = Semaphore(MAX_PARALLEL_YT_FETCHES)
    try {
        // 1. Discover home mixes (1 call, serial — needs the list before fanning out).
        val homeMixesResult = ytMusicApiClient.getHomeMixes()
        when (homeMixesResult) {
            is SyncResult.Success -> {
                val homeMixes = homeMixesResult.data
                diagnostics.add(SyncStepResult(
                    service = "YOUTUBE", step = "getHomeMixes",
                    status = StepStatus.SUCCESS, itemCount = homeMixes.size,
                ))
                // 2. Fetch each mix's tracks in parallel (capped).
                coroutineScope {
                    homeMixes.mapIndexed { index, mix ->
                        async { sem.withPermit { fetchAndSnapshotMix(mix, index, syncId) } }
                    }.awaitAll()
                }
            }
            is SyncResult.Empty -> diagnostics.add(SyncStepResult(
                service = "YOUTUBE", step = "getHomeMixes",
                status = StepStatus.EMPTY, errorMessage = homeMixesResult.reason))
            is SyncResult.Error -> diagnostics.add(SyncStepResult(
                service = "YOUTUBE", step = "getHomeMixes",
                status = StepStatus.ERROR, errorMessage = homeMixesResult.message))
        }

        // 3. Liked Songs (single playlist; runs concurrently w/ user-playlist group below
        //     by virtue of being launched into the same coroutineScope).
        coroutineScope {
            launch { sem.withPermit { fetchAndSnapshotLikedSongs(syncId, diagnostics) } }

            // 4. Discover user playlists, then fetch each in parallel.
            val userPlaylistsResult = ytMusicApiClient.getUserPlaylists()
            when (userPlaylistsResult) {
                is SyncResult.Success -> {
                    val userPlaylists = userPlaylistsResult.data.playlists
                    diagnostics.add(SyncStepResult(
                        service = "YOUTUBE", step = "getUserPlaylists",
                        status = StepStatus.SUCCESS, itemCount = userPlaylists.size,
                        errorMessage = if (userPlaylistsResult.data.partial) {
                            "partial library list: ${userPlaylistsResult.data.partialReason}"
                        } else null,
                    ))
                    userPlaylists.map { playlist ->
                        async { sem.withPermit { fetchAndSnapshotUserPlaylist(playlist, syncId) } }
                    }.awaitAll()
                }
                is SyncResult.Empty -> diagnostics.add(SyncStepResult(
                    service = "YOUTUBE", step = "getUserPlaylists",
                    status = StepStatus.EMPTY, errorMessage = userPlaylistsResult.reason))
                is SyncResult.Error -> diagnostics.add(SyncStepResult(
                    service = "YOUTUBE", step = "getUserPlaylists",
                    status = StepStatus.ERROR, errorMessage = userPlaylistsResult.message))
            }
        }
    } finally {
        innerTubeClient.endSyncSession()
    }
}
```

Extract three helpers from the inlined logic that's currently in the loops (Task 14 left them inline). Put them at the bottom of the class:

```kotlin
private suspend fun fetchAndSnapshotMix(mix: YTMusicPlaylist, index: Int, syncId: Long) {
    // Body: lift the existing per-mix logic from Task 14's home-mix loop.
}

private suspend fun fetchAndSnapshotLikedSongs(syncId: Long, diagnostics: MutableList<SyncStepResult>) {
    // Body: lift Task 14's liked-songs block.
    // Diagnostics list mutation is fine — we only have one writer here.
}

private suspend fun fetchAndSnapshotUserPlaylist(playlist: YTMusicPlaylist, syncId: Long) {
    // Body: lift Task 14's user-playlist per-item logic.
}
```

Each helper retains its own try/catch around the per-playlist fetch so a single failure doesn't take down the parallel group.

**Diagnostics list note:** The `diagnostics` list is a `mutableListOf<SyncStepResult>()` that's appended to from concurrent coroutines. This is unsafe. Wrap appends in a `Mutex` or use `Collections.synchronizedList(...)`. Simplest: change the field declaration in `doWork` to:

```kotlin
val diagnostics = java.util.Collections.synchronizedList(mutableListOf<SyncStepResult>())
```

or

```kotlin
val diagnostics = mutableListOf<SyncStepResult>()
val diagnosticsMutex = Mutex()
// ...inside helpers:
diagnosticsMutex.withLock { diagnostics.add(...) }
```

Pick the synchronizedList variant — the appends are infrequent and locking overhead is irrelevant.

- [ ] **Step 3: Compile**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :core:data:assembleDebug --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run existing tests**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :core:data:test :data:ytmusic:test --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorker.kt
git commit -m "feat(sync): parallelize YT mix/playlist fetches behind Semaphore(3)

Per-mix and per-user-playlist getPlaylistTracks calls now run inside a
coroutineScope { async { sem.withPermit { ... } } }. Semaphore is shared
across both groups so total in-flight browse calls during a YT sync ≤3.
Diagnostics list switched to synchronizedList for concurrent appends."
```

---

## Task 16: Worker-level test for partial-flow propagation + concurrency cap

**Files:**
- Test: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorkerYouTubePartialTest.kt` (new)

This task is optional-but-recommended. Skip if `PlaylistFetchWorker` has no existing test infrastructure (mock DAOs would need significant boilerplate). If the project already has `PlaylistFetchWorkerTest.kt` or similar, extend that file instead of creating a new one.

- [ ] **Step 1: Survey existing worker tests**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && find core/data/src/test -name "*Worker*" -name "*.kt"
```

If results: extend the most-relevant file with two test cases:
1. `getLikedSongs` returns `PagedTracks(partial=true, expectedCount=2000, tracks=fake1500)` → verify the inserted `RemotePlaylistSnapshotEntity` has `partial=true`, `expectedCount=2000`, `trackCount=1500`. Verify the appended `SyncStepResult.errorMessage` contains `"partial: 1500/2000"`.
2. With 8 fake mixes each adding a 50ms delay in `getPlaylistTracks`, count the maximum in-flight invocations via an `AtomicInteger` + `AtomicInteger.updateAndGet { max(it, current) }` pattern. Assert max ≤ 3.

If no results: skip Task 16. The unit-level coverage in Tasks 9-12 plus the manual on-device verification in Task 17 are sufficient.

- [ ] **Step 2: If extended/created, run the tests**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :core:data:test --console=plain
```

- [ ] **Step 3: Commit if applicable**

---

## Task 17: Manual on-device verification

This is the only end-to-end gate. The user described the exact scenario: cleared storage, disconnected Spotify, only YT enabled, sync.

- [ ] **Step 1: Build and install**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :app:installDebug --console=plain
```

Expected: BUILD SUCCESSFUL. Memory `feedback_install_after_fix.md`: always `:app:installDebug` after a fix on this project — compile-pass isn't enough.

- [ ] **Step 2: Capture baseline**

In the app:
1. Settings → clear app storage / uninstall + reinstall.
2. Sign in to YouTube only (Spotify disconnected).
3. Sync screen → make sure ONLY Liked Songs and Discover Mix are toggled on.

- [ ] **Step 3: Sync and observe**

```bash
adb logcat -c
adb logcat StashYT:V StashSync:V *:S > /tmp/yt_sync_after.log &
# In the app: Sync Now
# Wait for completion
kill %1
```

Expected log signals:
- `paginateBrowse` entries showing multi-page walks (e.g., `paginateBrowse: hit page 5 with token ABC...`)
- `executeRequest: POST .../browse?ctoken=...` lines (continuation requests)
- For Liked Songs: final track count >> 24 (should be your real liked count, ±5%)
- For Discover Mix: full mix length (typically 25-50)

- [ ] **Step 4: Verify in-app**

- Library → Liked Songs: track count visible in header should match your real YT Liked Songs count (±5%).
- Sync screen → expand the latest sync run: each step should show its count and (if partial) "partial: N/M" annotation.
- If anything shows `partial=true`, inspect the log for the `partialReason` to triage.

- [ ] **Step 5: Decide ship vs. iterate**

If counts match expectation: open PR. If counts are short or partials appear, surface the `partialReason` and decide whether to tune (concurrency cap, retry counts, MAX_PAGES) before merging.

---

## Closing checklist

- [ ] All tests green: `./gradlew test --console=plain` from worktree root.
- [ ] On-device verification passed (Task 17).
- [ ] Spec updated only if implementation diverged from it (it shouldn't — flag any deltas in the PR description).
- [ ] PR title: `feat(sync): YouTube continuation pagination + parallel fetch`
- [ ] PR body links the spec at `docs/superpowers/specs/2026-04-27-youtube-sync-pagination-design.md`.
- [ ] After merge, delete the worktree: `git worktree remove .worktrees/yt-sync-pagination`.
