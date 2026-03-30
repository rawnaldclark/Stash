# Sync Pipeline Root Cause Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the three root causes that make Spotify and YouTube Music sync silently return zero data, then add diagnostics so future breaks are visible.

**Architecture:** Three targeted fixes (Spotify auth check, dead endpoint replacement, YouTube client version) unblock the sync pipeline. Then SyncResult type + diagnostics column provide observability. No structural changes to the WorkManager chain, DI graph, or UI navigation.

**Tech Stack:** Kotlin, Room (version 3 migration via destructive fallback), kotlinx.serialization, OkHttp, Hilt, Jetpack Compose

**Spec:** `docs/superpowers/specs/2026-03-30-sync-pipeline-fix-design.md`

**Scope note:** P4 (auth validation on connect) is deferred to a follow-up plan. This plan covers P1 (root cause fixes), P2 (secondary bugs), and P3 (diagnostics infrastructure). P4 depends on P3's SyncResult type being in place and is a lower priority than getting sync working.

**Spec deviation:** `SyncStepResult.service` uses `String` (not `MusicSource`) to avoid pulling kotlinx.serialization into every `MusicSource` consumer. Values are `"SPOTIFY"` or `"YOUTUBE"`.

---

## File Structure

### New files
| File | Purpose |
|------|---------|
| `core/model/src/main/kotlin/com/stash/core/model/SyncResult.kt` | Sealed result type for API calls |
| `core/model/src/main/kotlin/com/stash/core/model/SyncStepResult.kt` | Per-step diagnostics data class |

### Modified files
| File | What changes |
|------|-------------|
| `core/auth/.../TokenManagerImpl.kt:186` | Fix `isAuthenticated(SPOTIFY)` to check sp_dc cookie |
| `data/spotify/.../SpotifyApiClient.kt:165-262` | Replace dead Web API `getUserPlaylists()` with GraphQL libraryV3 |
| `data/spotify/.../SpotifyApiClient.kt:357-368` | Update `getDailyMixes()` for new getUserPlaylists return |
| `data/ytmusic/.../InnerTubeClient.kt:53` | Dynamic CLIENT_VERSION |
| `data/ytmusic/.../InnerTubeClient.kt:97-104` | Add `user: {}` to context |
| `data/ytmusic/.../InnerTubeClient.kt:150-160` | Close response body in all paths |
| `core/model/build.gradle.kts` | Add serialization plugin + dependency |
| `core/data/.../db/entity/SyncHistoryEntity.kt` | Add `diagnostics` column |
| `core/data/.../db/StashDatabase.kt:41` | Bump version to 3 |
| `core/data/.../sync/workers/PlaylistFetchWorker.kt` | Collect diagnostics, fail on total error |
| `data/spotify/.../SpotifyApiClient.kt` | Return `SyncResult` from sync-path methods |
| `data/ytmusic/.../YTMusicApiClient.kt` | Return `SyncResult` from public methods |

---

## Task 1: Fix `isAuthenticated(SPOTIFY)` — check sp_dc cookie, not access token

**Files:**
- Modify: `core/auth/src/main/kotlin/com/stash/core/auth/TokenManagerImpl.kt:184-191`

This is the most likely single root cause. The access token expires in ~1 hour. After that, `isAuthenticated()` returns false and PlaylistFetchWorker skips Spotify entirely, even though the sp_dc cookie is still valid.

- [ ] **Step 1: Fix the auth check**

In `TokenManagerImpl.kt`, change `isAuthenticated()` for Spotify to check the sp_dc cookie (stored in `refreshToken`) instead of access token expiry:

```kotlin
override suspend fun isAuthenticated(service: AuthService): Boolean {
    return when (service) {
        AuthService.SPOTIFY -> tokenStore.spotifyToken.first()?.let {
            it.refreshToken.isNotEmpty()  // sp_dc cookie is the long-lived credential
        } ?: false
        AuthService.YOUTUBE_MUSIC -> tokenStore.youTubeToken.first()?.let {
            it.refreshToken.isNotEmpty()
        } ?: false
    }
}
```

Note: This matches the YouTube check which already correctly uses `refreshToken.isNotEmpty()`.

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew :core:auth:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/auth/src/main/kotlin/com/stash/core/auth/TokenManagerImpl.kt
git commit -m "fix: check sp_dc cookie existence in isAuthenticated, not access token expiry

The access token expires in ~1 hour, causing isAuthenticated(SPOTIFY)
to return false and PlaylistFetchWorker to skip Spotify on every sync
after the first hour. The sp_dc cookie (refreshToken) is the actual
long-lived credential and getSpotifyAccessToken() auto-refreshes from it."
```

---

## Task 2: Replace dead Spotify `/v1/users/{id}/playlists` endpoint

**Files:**
- Modify: `data/spotify/src/main/kotlin/com/stash/data/spotify/SpotifyApiClient.kt:165-262`

Spotify removed `GET /v1/users/{username}/playlists` in February 2026. The existing `getUserPlaylists()` calls this dead endpoint. Replace it with the GraphQL `libraryV3` query which uses sp_dc auth and is still functional. The `executeGraphQL()` method and `parseLibraryResponse()` parser already exist in this file.

- [ ] **Step 1: Rewrite `getUserPlaylists()` to use GraphQL libraryV3**

Replace the entire `getUserPlaylists()` method body (lines 165-262) with:

```kotlin
/**
 * Fetches the current user's playlists via the GraphQL `libraryV3` operation.
 *
 * Uses sp_dc-derived access token + client token (Prong 2).
 * The previous Web API endpoint (/v1/users/{id}/playlists) was removed
 * by Spotify in February 2026.
 */
suspend fun getUserPlaylists(
    limit: Int = DEFAULT_LIMIT,
    offset: Int = 0,
): List<SpotifyPlaylistItem> = withContext(Dispatchers.IO) {
    Log.d(TAG, "getUserPlaylists: limit=$limit, offset=$offset (via GraphQL libraryV3)")

    try {
        val variables = """
            {
                "filters": ["Playlists"],
                "order": null,
                "textFilter": "",
                "features": ["LIKED_SONGS","YOUR_EPISODES"],
                "limit": $limit,
                "offset": $offset
            }
        """.trimIndent()

        val responseJson = executeGraphQL(
            operationName = "libraryV3",
            variables = variables,
            hash = SpotifyAuthConfig.HASH_LIBRARY_V3,
        )

        if (responseJson != null) {
            val playlists = parseLibraryResponse(responseJson)
            Log.d(TAG, "getUserPlaylists: parsed ${playlists.size} playlists from libraryV3")
            playlists
        } else {
            Log.w(TAG, "getUserPlaylists: GraphQL returned null")
            emptyList()
        }
    } catch (e: Exception) {
        Log.e(TAG, "getUserPlaylists: GraphQL libraryV3 failed", e)
        emptyList()
    }
}
```

Key points:
- Uses `executeGraphQL()` which handles token refresh on 401 and client token re-acquisition on 400
- Uses `parseLibraryResponse()` which already parses `data.me.libraryV3.items` for playlists
- `HASH_LIBRARY_V3` constant already exists in `SpotifyAuthConfig.kt`
- `getDailyMixes()` at line 357 calls `getUserPlaylists()` and filters — no changes needed there

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew :data:spotify:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add data/spotify/src/main/kotlin/com/stash/data/spotify/SpotifyApiClient.kt
git commit -m "fix: replace dead /v1/users/{id}/playlists with GraphQL libraryV3

Spotify removed the Get User's Playlists endpoint in February 2026.
getUserPlaylists() now uses the GraphQL libraryV3 operation via sp_dc
auth, reusing the existing executeGraphQL() and parseLibraryResponse()
infrastructure."
```

---

## Task 3: Make YouTube CLIENT_VERSION dynamic + fix connection leak + add `user` context

**Files:**
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt`

Three changes in one file: (1) dynamic CLIENT_VERSION matching ytmusicapi's approach, (2) close response body to prevent connection pool leak, (3) add `user: {}` to InnerTube context.

- [ ] **Step 1: Make CLIENT_VERSION dynamic**

In the companion object (line 52-53), change from hardcoded const to a computed property:

```kotlin
companion object {
    private const val TAG = "StashYT"
    private const val BASE_URL = "https://music.youtube.com/youtubei/v1"

    /** InnerTube client name for YouTube Music web. */
    private const val CLIENT_NAME = "WEB_REMIX"

    /**
     * InnerTube client version — generated from today's date.
     * This matches the approach used by ytmusicapi (Python reference implementation).
     * InnerTube uses this for client identification; a current date signals a current web client.
     */
    private val CLIENT_VERSION: String
        get() = "1.${java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)}.01.00"

    /** Publicly-known API key used by the YouTube Music web app. */
    private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
}
```

- [ ] **Step 2: Add `user` object to context**

In `buildContext()` (line 97-104), add the empty `user` object:

```kotlin
private fun buildContext(): JsonObject = buildJsonObject {
    putJsonObject("client") {
        put("clientName", CLIENT_NAME)
        put("clientVersion", CLIENT_VERSION)
        put("hl", "en")
        put("gl", "US")
    }
    putJsonObject("user") {}
}
```

- [ ] **Step 3: Close response body in all paths**

In `executeRequest()` (lines 120-161), use `response.use {}` to ensure the body is always closed:

```kotlin
private fun executeRequest(
    url: String,
    body: JsonObject,
    cookie: String?,
): JsonObject? {
    val sapiSid = cookie?.let { cookieHelper.extractSapiSid(it) }

    val fullUrl = if (sapiSid != null) {
        "$url?prettyPrint=false"
    } else {
        "$url?key=$API_KEY&prettyPrint=false"
    }

    Log.d(TAG, "executeRequest: POST $fullUrl (authenticated=${sapiSid != null})")

    val requestBuilder = Request.Builder()
        .url(fullUrl)
        .post(body.toString().toRequestBody(jsonMediaType))
        .header("Content-Type", "application/json")
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    if (sapiSid != null && cookie != null) {
        requestBuilder
            .header("Cookie", cookie)
            .header("Authorization", cookieHelper.generateAuthHeader(sapiSid))
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("X-Goog-AuthUser", "0")
    }

    val response = okHttpClient.newCall(requestBuilder.build()).execute()
    return response.use { resp ->
        if (!resp.isSuccessful) {
            val errorBody = resp.body?.string()
            Log.e(TAG, "executeRequest: HTTP ${resp.code} - $errorBody")
            return@use null
        }

        val responseBody = resp.body?.string() ?: return@use null
        Log.d(TAG, "executeRequest: success, response length=${responseBody.length}")
        json.parseToJsonElement(responseBody).jsonObject
    }
}
```

Using `response.use {}` guarantees the response body is closed whether the block returns normally or throws.

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew :data:ytmusic:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt
git commit -m "fix: dynamic YouTube CLIENT_VERSION, close response body, add user context

CLIENT_VERSION is now date-based (matching ytmusicapi reference impl)
instead of hardcoded to Jan 2024. Response body is properly closed via
use{} to prevent connection pool leaks. Empty user object added to
InnerTube context to match the web client."
```

---

## Task 4: Add SyncResult type to core:model

**Files:**
- Create: `core/model/src/main/kotlin/com/stash/core/model/SyncResult.kt`

This sealed class lets API methods distinguish "no data" from "API error." Lives in `core:model` because both `data:spotify` and `data:ytmusic` need to return it, and `core:model` is a leaf dependency for all modules.

- [ ] **Step 1: Create SyncResult.kt**

```kotlin
package com.stash.core.model

/**
 * Result type for sync API calls that distinguishes between
 * successful data, legitimately empty results, and errors.
 *
 * This replaces the pattern of returning [emptyList] on failure,
 * which made it impossible to distinguish "no new data" from "API broken."
 */
sealed class SyncResult<out T> {
    /** The API call succeeded and returned data. */
    data class Success<T>(val data: T) : SyncResult<T>()

    /** The API call succeeded but there was legitimately no data. */
    data class Empty(val reason: String) : SyncResult<Nothing>()

    /** The API call failed. */
    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val httpCode: Int? = null,
    ) : SyncResult<Nothing>()
}

/** Unwrap a [SyncResult] containing a list, returning empty on non-success. */
fun <T> SyncResult<List<T>>.getOrEmpty(): List<T> = when (this) {
    is SyncResult.Success -> data
    is SyncResult.Empty -> emptyList()
    is SyncResult.Error -> emptyList()
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew :core:model:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/model/src/main/kotlin/com/stash/core/model/SyncResult.kt
git commit -m "feat: add SyncResult sealed type for distinguishing API errors from empty data"
```

---

## Task 5: Add SyncStepResult + serialization to core:model

**Files:**
- Create: `core/model/src/main/kotlin/com/stash/core/model/SyncStepResult.kt`
- Modify: `core/model/build.gradle.kts`

Diagnostics data model. Needs `@Serializable` for JSON persistence in the database.

- [ ] **Step 1: Add serialization plugin to core:model build.gradle.kts**

```kotlin
plugins {
    id("stash.android.library")
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "com.stash.core.model"
}
dependencies {
    implementation(libs.kotlinx.serialization.json)
}
```

- [ ] **Step 2: Create SyncStepResult.kt**

```kotlin
package com.stash.core.model

import kotlinx.serialization.Serializable

/**
 * Records what happened during a single step of the sync pipeline.
 * A list of these is JSON-serialized into [SyncHistoryEntity.diagnostics].
 */
@Serializable
data class SyncStepResult(
    val service: String,
    val step: String,
    val status: StepStatus,
    val itemCount: Int = 0,
    val errorMessage: String? = null,
    val httpCode: Int? = null,
)

@Serializable
enum class StepStatus { SUCCESS, EMPTY, ERROR }
```

Note: `service` is `String` (not `MusicSource`) to avoid making `MusicSource` serializable and pulling serialization into every consumer. Values will be `"SPOTIFY"` or `"YOUTUBE"`.

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew :core:model:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/model/build.gradle.kts
git add core/model/src/main/kotlin/com/stash/core/model/SyncStepResult.kt
git commit -m "feat: add SyncStepResult diagnostics model with serialization"
```

---

## Task 6: Add diagnostics column to SyncHistoryEntity + bump DB version

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/entity/SyncHistoryEntity.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt:41`

The database uses `fallbackToDestructiveMigration()` (DatabaseModule.kt:35), so bumping the version from 2 to 3 will auto-wipe and recreate. No manual migration SQL needed. This is acceptable because the app is in development.

- [ ] **Step 1: Add diagnostics column to SyncHistoryEntity**

Add after the `errorMessage` field (line 42):

```kotlin
@ColumnInfo(name = "diagnostics")
val diagnostics: String? = null,
```

- [ ] **Step 2: Bump database version**

In `StashDatabase.kt` line 41, change `version = 2` to `version = 3`.

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew :core:data:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/entity/SyncHistoryEntity.kt
git add core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt
git commit -m "feat: add diagnostics column to sync_history, bump DB to version 3

Uses fallbackToDestructiveMigration() so no manual migration needed.
The diagnostics column stores JSON-serialized List<SyncStepResult>."
```

---

## Task 7: Wire SyncResult into SpotifyApiClient, YTMusicApiClient, and PlaylistFetchWorker (ATOMIC)

> **Important:** This task modifies SpotifyApiClient, YTMusicApiClient, and PlaylistFetchWorker together because changing return types in the API clients breaks PlaylistFetchWorker's compilation. All three must be updated atomically. Do NOT commit intermediate states.

### Part A: SpotifyApiClient sync-path methods

**Files:**
- Modify: `data/spotify/src/main/kotlin/com/stash/data/spotify/SpotifyApiClient.kt`

Change `getDailyMixes()`, `getLikedSongs()`, and `getPlaylistTracks()` to return `SyncResult`. The internal `getUserPlaylists()` stays as `List<T>` since it's only called by `getDailyMixes()`.

- [ ] **Step 1: Add SyncResult import**

Add to imports:
```kotlin
import com.stash.core.model.SyncResult
```

- [ ] **Step 2: Update `getDailyMixes()` return type**

Replace the `getDailyMixes()` method (lines 357-368):

```kotlin
suspend fun getDailyMixes(): SyncResult<List<SpotifyPlaylistItem>> {
    return try {
        val playlists = getUserPlaylists()
        if (playlists.isEmpty()) {
            SyncResult.Empty("No playlists returned from libraryV3")
        } else {
            val mixes = playlists.filter { playlist ->
                playlist.owner.id == "spotify" && DAILY_MIX_REGEX.matches(playlist.name)
            }
            Log.d(TAG, "getDailyMixes: found ${mixes.size} daily mixes: ${mixes.map { it.name }}")
            if (mixes.isEmpty()) {
                SyncResult.Empty("No Daily Mix playlists found in ${playlists.size} playlists")
            } else {
                SyncResult.Success(mixes)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "getDailyMixes: failed", e)
        SyncResult.Error("getDailyMixes failed: ${e.message}", e)
    }
}
```

- [ ] **Step 3: Update `getLikedSongs()` return type**

Replace the `getLikedSongs()` method (lines 312-345):

```kotlin
suspend fun getLikedSongs(
    limit: Int = DEFAULT_LIMIT,
    offset: Int = 0,
): SyncResult<List<SpotifyTrackItem>> = withContext(Dispatchers.IO) {
    Log.d(TAG, "getLikedSongs: limit=$limit, offset=$offset (via sp_dc GraphQL)")

    try {
        val variables = """
            {
                "uri": "spotify:collection:tracks",
                "offset": $offset,
                "limit": $limit
            }
        """.trimIndent()

        val responseJson = executeGraphQL(
            operationName = "fetchPlaylist",
            variables = variables,
            hash = SpotifyAuthConfig.HASH_FETCH_PLAYLIST,
        )

        if (responseJson != null) {
            val tracks = parsePlaylistTracksGraphQLResponse(responseJson)
            Log.d(TAG, "getLikedSongs: got ${tracks.size} liked songs")
            if (tracks.isEmpty()) {
                SyncResult.Empty("GraphQL returned empty liked songs")
            } else {
                SyncResult.Success(tracks)
            }
        } else {
            SyncResult.Error("getLikedSongs: GraphQL returned null (sp_dc may be expired)")
        }
    } catch (e: Exception) {
        Log.e(TAG, "getLikedSongs: failed", e)
        SyncResult.Error("getLikedSongs failed: ${e.message}", e)
    }
}
```

- [ ] **Step 4: Update `getPlaylistTracks()` return type**

Change the method signature and wrap the result:

```kotlin
suspend fun getPlaylistTracks(playlistId: String): SyncResult<List<SpotifyTrackItem>> {
    Log.d(TAG, "getPlaylistTracks: playlistId=$playlistId")

    // Prong 1: Try client credentials + Web API first
    val webApiTracks = tryGetPlaylistTracksViaWebApi(playlistId)
    if (webApiTracks != null) {
        Log.d(TAG, "getPlaylistTracks: got ${webApiTracks.size} tracks via Web API")
        return SyncResult.Success(webApiTracks)
    }

    // Prong 2: Fall back to sp_dc GraphQL
    Log.d(TAG, "getPlaylistTracks: Web API failed, trying GraphQL fallback")
    val graphqlTracks = tryGetPlaylistTracksViaGraphQL(playlistId)
    if (graphqlTracks != null) {
        Log.d(TAG, "getPlaylistTracks: got ${graphqlTracks.size} tracks via GraphQL")
        return SyncResult.Success(graphqlTracks)
    }

    return SyncResult.Error(
        "getPlaylistTracks: both Web API and GraphQL failed for $playlistId",
        cause = null,  // Individual prong errors are logged above
    )
}

// NOTE: If tryGetPlaylistTracksViaWebApi() throws SpotifyApiException (e.g., 429),
// it should be caught in getPlaylistTracks and re-thrown so doWork() can handle retries.
// Wrap the two-prong logic in a try/catch that rethrows SpotifyApiException:
//   } catch (e: SpotifyApiException) { throw e }
```

Update the full `getPlaylistTracks()` to preserve SpotifyApiException for 429 retry:

```kotlin
suspend fun getPlaylistTracks(playlistId: String): SyncResult<List<SpotifyTrackItem>> {
    Log.d(TAG, "getPlaylistTracks: playlistId=$playlistId")

    try {
        // Prong 1: Try client credentials + Web API first
        val webApiTracks = tryGetPlaylistTracksViaWebApi(playlistId)
        if (webApiTracks != null) {
            Log.d(TAG, "getPlaylistTracks: got ${webApiTracks.size} tracks via Web API")
            return SyncResult.Success(webApiTracks)
        }

        // Prong 2: Fall back to sp_dc GraphQL
        Log.d(TAG, "getPlaylistTracks: Web API failed, trying GraphQL fallback")
        val graphqlTracks = tryGetPlaylistTracksViaGraphQL(playlistId)
        if (graphqlTracks != null) {
            Log.d(TAG, "getPlaylistTracks: got ${graphqlTracks.size} tracks via GraphQL")
            return SyncResult.Success(graphqlTracks)
        }

        return SyncResult.Error("getPlaylistTracks: both Web API and GraphQL failed for $playlistId")
    } catch (e: SpotifyApiException) {
        throw e  // Preserve for doWork() 429 retry handling
    } catch (e: Exception) {
        return SyncResult.Error("getPlaylistTracks failed: ${e.message}", cause = e)
    }
}
```

### Part B: YTMusicApiClient sync-path methods

**Files:**
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt`

- [ ] **Step 1: Add SyncResult import and update `getHomeMixes()`**

```kotlin
import com.stash.core.model.SyncResult

// Update getHomeMixes():
suspend fun getHomeMixes(): SyncResult<List<YTMusicPlaylist>> {
    val response = innerTubeClient.browse(BROWSE_HOME)
    if (response == null) {
        return SyncResult.Error("InnerTube browse($BROWSE_HOME) returned null — check CLIENT_VERSION or cookie")
    }
    val mixes = parseMixesFromHome(response)
    return if (mixes.isEmpty()) {
        SyncResult.Empty("Home feed returned no mixes")
    } else {
        SyncResult.Success(mixes)
    }
}
```

- [ ] **Step 2: Update `getLikedSongs()`**

```kotlin
suspend fun getLikedSongs(): SyncResult<List<YTMusicTrack>> {
    val response = innerTubeClient.browse(BROWSE_LIKED_SONGS)
    if (response == null) {
        return SyncResult.Error("InnerTube browse($BROWSE_LIKED_SONGS) returned null")
    }
    val tracks = parseTracksFromBrowse(response)
    return if (tracks.isEmpty()) {
        SyncResult.Empty("Liked songs returned no tracks")
    } else {
        SyncResult.Success(tracks)
    }
}
```

- [ ] **Step 3: Update `getPlaylistTracks()`**

```kotlin
suspend fun getPlaylistTracks(playlistId: String): SyncResult<List<YTMusicTrack>> {
    val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
    val response = innerTubeClient.browse(browseId)
    if (response == null) {
        return SyncResult.Error("InnerTube browse($browseId) returned null")
    }
    val tracks = parseTracksFromBrowse(response)
    return if (tracks.isEmpty()) {
        SyncResult.Empty("Playlist $playlistId returned no tracks")
    } else {
        SyncResult.Success(tracks)
    }
}
```

### Part C: Update PlaylistFetchWorker to use SyncResult + collect diagnostics

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorker.kt`

This is the critical integration point. The worker needs to unwrap SyncResult, collect diagnostics, persist them, and fail when all API calls error.

- [ ] **Step 1: Add imports and diagnostics collection**

Add imports:
```kotlin
import com.stash.core.model.SyncResult
import com.stash.core.model.SyncStepResult
import com.stash.core.model.StepStatus
import com.stash.core.model.getOrEmpty
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
```

- [ ] **Step 2: Rewrite `fetchSpotifyPlaylists()` to collect diagnostics**

```kotlin
private suspend fun fetchSpotifyPlaylists(syncId: Long, diagnostics: MutableList<SyncStepResult>) {
    Log.d(TAG, "fetchSpotifyPlaylists: starting for syncId=$syncId")

    // Fetch Daily Mixes
    when (val mixesResult = spotifyApiClient.getDailyMixes()) {
        is SyncResult.Success -> {
            val dailyMixes = mixesResult.data
            diagnostics.add(SyncStepResult("SPOTIFY", "getDailyMixes", StepStatus.SUCCESS, dailyMixes.size))

            for (mix in dailyMixes) {
                try {
                    val mixNumber = Regex("""\d+""").find(mix.name)?.value?.toIntOrNull()
                    val playlistSnapshotId = remoteSnapshotDao.insertPlaylistSnapshot(
                        RemotePlaylistSnapshotEntity(
                            syncId = syncId,
                            source = MusicSource.SPOTIFY,
                            sourcePlaylistId = mix.id,
                            playlistName = mix.name,
                            playlistType = PlaylistType.DAILY_MIX,
                            mixNumber = mixNumber,
                            trackCount = mix.tracks?.total ?: 0,
                            artUrl = mix.images?.firstOrNull()?.url,
                        )
                    )

                    when (val tracksResult = spotifyApiClient.getPlaylistTracks(mix.id)) {
                        is SyncResult.Success -> {
                            val trackSnapshots = tracksResult.data.mapIndexedNotNull { index, item ->
                                val track = item.track ?: return@mapIndexedNotNull null
                                RemoteTrackSnapshotEntity(
                                    syncId = syncId,
                                    snapshotPlaylistId = playlistSnapshotId,
                                    title = track.name,
                                    artist = track.artists.joinToString(", ") { it.name },
                                    album = track.album?.name,
                                    durationMs = track.duration_ms,
                                    spotifyUri = track.uri,
                                    albumArtUrl = track.album?.images?.firstOrNull()?.url,
                                    position = index,
                                )
                            }
                            if (trackSnapshots.isNotEmpty()) {
                                remoteSnapshotDao.insertTrackSnapshots(trackSnapshots)
                            }
                            Log.d(TAG, "fetchSpotifyPlaylists: saved ${trackSnapshots.size} tracks for '${mix.name}'")
                        }
                        is SyncResult.Error -> {
                            Log.e(TAG, "fetchSpotifyPlaylists: track fetch failed for '${mix.name}': ${tracksResult.message}")
                            if (tracksResult.cause is SpotifyApiException) throw tracksResult.cause
                        }
                        is SyncResult.Empty -> {
                            Log.d(TAG, "fetchSpotifyPlaylists: no tracks in '${mix.name}': ${tracksResult.reason}")
                        }
                    }
                } catch (e: SpotifyApiException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "fetchSpotifyPlaylists: failed for mix '${mix.name}'", e)
                }
            }
        }
        is SyncResult.Empty -> {
            diagnostics.add(SyncStepResult("SPOTIFY", "getDailyMixes", StepStatus.EMPTY, errorMessage = mixesResult.reason))
            Log.d(TAG, "fetchSpotifyPlaylists: daily mixes empty: ${mixesResult.reason}")
        }
        is SyncResult.Error -> {
            diagnostics.add(SyncStepResult("SPOTIFY", "getDailyMixes", StepStatus.ERROR, errorMessage = mixesResult.message, httpCode = mixesResult.httpCode))
            Log.e(TAG, "fetchSpotifyPlaylists: daily mixes error: ${mixesResult.message}")
        }
    }

    // Fetch Liked Songs
    when (val likedResult = spotifyApiClient.getLikedSongs()) {
        is SyncResult.Success -> {
            val likedSongs = likedResult.data
            diagnostics.add(SyncStepResult("SPOTIFY", "getLikedSongs", StepStatus.SUCCESS, likedSongs.size))

            val likedPlaylistId = remoteSnapshotDao.insertPlaylistSnapshot(
                RemotePlaylistSnapshotEntity(
                    syncId = syncId,
                    source = MusicSource.SPOTIFY,
                    sourcePlaylistId = "spotify_liked_songs",
                    playlistName = "Liked Songs",
                    playlistType = PlaylistType.LIKED_SONGS,
                    trackCount = likedSongs.size,
                )
            )

            val trackSnapshots = likedSongs.mapIndexedNotNull { index, item ->
                val track = item.track ?: return@mapIndexedNotNull null
                RemoteTrackSnapshotEntity(
                    syncId = syncId,
                    snapshotPlaylistId = likedPlaylistId,
                    title = track.name,
                    artist = track.artists.joinToString(", ") { it.name },
                    album = track.album?.name,
                    durationMs = track.duration_ms,
                    spotifyUri = track.uri,
                    albumArtUrl = track.album?.images?.firstOrNull()?.url,
                    position = index,
                )
            }
            if (trackSnapshots.isNotEmpty()) {
                remoteSnapshotDao.insertTrackSnapshots(trackSnapshots)
            }
        }
        is SyncResult.Empty -> {
            diagnostics.add(SyncStepResult("SPOTIFY", "getLikedSongs", StepStatus.EMPTY, errorMessage = likedResult.reason))
        }
        is SyncResult.Error -> {
            diagnostics.add(SyncStepResult("SPOTIFY", "getLikedSongs", StepStatus.ERROR, errorMessage = likedResult.message, httpCode = likedResult.httpCode))
        }
    }
}
```

- [ ] **Step 3: Rewrite `fetchYouTubePlaylists()` to collect diagnostics**

```kotlin
private suspend fun fetchYouTubePlaylists(syncId: Long, diagnostics: MutableList<SyncStepResult>) {
    Log.d(TAG, "fetchYouTubePlaylists: starting for syncId=$syncId")

    // Fetch Home Mixes
    when (val mixesResult = ytMusicApiClient.getHomeMixes()) {
        is SyncResult.Success -> {
            val homeMixes = mixesResult.data
            diagnostics.add(SyncStepResult("YOUTUBE", "getHomeMixes", StepStatus.SUCCESS, homeMixes.size))

            for ((index, mix) in homeMixes.withIndex()) {
                val playlistSnapshotId = remoteSnapshotDao.insertPlaylistSnapshot(
                    RemotePlaylistSnapshotEntity(
                        syncId = syncId,
                        source = MusicSource.YOUTUBE,
                        sourcePlaylistId = mix.playlistId,
                        playlistName = mix.title,
                        playlistType = PlaylistType.DAILY_MIX,
                        mixNumber = index + 1,
                        trackCount = mix.trackCount ?: 0,
                        artUrl = mix.thumbnailUrl,
                    )
                )

                when (val tracksResult = ytMusicApiClient.getPlaylistTracks(mix.playlistId)) {
                    is SyncResult.Success -> {
                        val trackSnapshots = tracksResult.data.mapIndexed { position, track ->
                            RemoteTrackSnapshotEntity(
                                syncId = syncId,
                                snapshotPlaylistId = playlistSnapshotId,
                                title = track.title,
                                artist = track.artists,
                                album = track.album,
                                durationMs = track.durationMs ?: 0L,
                                youtubeId = track.videoId,
                                albumArtUrl = track.thumbnailUrl,
                                position = position,
                            )
                        }
                        if (trackSnapshots.isNotEmpty()) {
                            remoteSnapshotDao.insertTrackSnapshots(trackSnapshots)
                        }
                    }
                    is SyncResult.Error -> Log.e(TAG, "fetchYouTubePlaylists: tracks error for '${mix.title}': ${tracksResult.message}")
                    is SyncResult.Empty -> Log.d(TAG, "fetchYouTubePlaylists: no tracks in '${mix.title}'")
                }
            }
        }
        is SyncResult.Empty -> {
            diagnostics.add(SyncStepResult("YOUTUBE", "getHomeMixes", StepStatus.EMPTY, errorMessage = mixesResult.reason))
        }
        is SyncResult.Error -> {
            diagnostics.add(SyncStepResult("YOUTUBE", "getHomeMixes", StepStatus.ERROR, errorMessage = mixesResult.message, httpCode = mixesResult.httpCode))
        }
    }

    // Fetch Liked Songs
    when (val likedResult = ytMusicApiClient.getLikedSongs()) {
        is SyncResult.Success -> {
            val likedSongs = likedResult.data
            diagnostics.add(SyncStepResult("YOUTUBE", "getLikedSongs", StepStatus.SUCCESS, likedSongs.size))

            val likedPlaylistId = remoteSnapshotDao.insertPlaylistSnapshot(
                RemotePlaylistSnapshotEntity(
                    syncId = syncId,
                    source = MusicSource.YOUTUBE,
                    sourcePlaylistId = "youtube_liked_songs",
                    playlistName = "Liked Songs",
                    playlistType = PlaylistType.LIKED_SONGS,
                    trackCount = likedSongs.size,
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
                    albumArtUrl = track.thumbnailUrl,
                    position = position,
                )
            }
            if (trackSnapshots.isNotEmpty()) {
                remoteSnapshotDao.insertTrackSnapshots(trackSnapshots)
            }
        }
        is SyncResult.Empty -> {
            diagnostics.add(SyncStepResult("YOUTUBE", "getLikedSongs", StepStatus.EMPTY, errorMessage = likedResult.reason))
        }
        is SyncResult.Error -> {
            diagnostics.add(SyncStepResult("YOUTUBE", "getLikedSongs", StepStatus.ERROR, errorMessage = likedResult.message, httpCode = likedResult.httpCode))
        }
    }
}
```

- [ ] **Step 4: Update `doWork()` to pass diagnostics and persist them**

Replace the body of `doWork()`:

```kotlin
override suspend fun doWork(): Result {
    val syncEntry = SyncHistoryEntity(
        status = SyncState.AUTHENTICATING,
        trigger = SyncTrigger.MANUAL,
        startedAt = Instant.now(),
    )
    val syncId = syncHistoryDao.insert(syncEntry)
    val diagnostics = mutableListOf<SyncStepResult>()

    try {
        syncStateManager.onAuthenticating()

        val isSpotifyAuthenticated = tokenManager.isAuthenticated(AuthService.SPOTIFY)
        val isYouTubeAuthenticated = tokenManager.isAuthenticated(AuthService.YOUTUBE_MUSIC)

        if (!isSpotifyAuthenticated && !isYouTubeAuthenticated) {
            syncHistoryDao.updateStatus(
                id = syncId,
                status = SyncState.FAILED,
                completedAt = System.currentTimeMillis(),
                errorMessage = "No authenticated services",
            )
            syncStateManager.onError("No authenticated services")
            return Result.failure(workDataOf(KEY_SYNC_ID to syncId))
        }

        syncStateManager.onFetchingPlaylists()
        syncHistoryDao.updateStatus(syncId, SyncState.FETCHING_PLAYLISTS)

        if (isSpotifyAuthenticated) {
            try {
                fetchSpotifyPlaylists(syncId, diagnostics)
            } catch (e: SpotifyApiException) {
                throw e // Let the outer catch handle retries
            } catch (e: Exception) {
                Log.e(TAG, "Spotify fetch failed", e)
                diagnostics.add(SyncStepResult("SPOTIFY", "fetchSpotifyPlaylists", StepStatus.ERROR, errorMessage = e.message))
            }
        }

        if (isYouTubeAuthenticated) {
            try {
                fetchYouTubePlaylists(syncId, diagnostics)
            } catch (e: Exception) {
                Log.e(TAG, "YouTube fetch failed", e)
                diagnostics.add(SyncStepResult("YOUTUBE", "fetchYouTubePlaylists", StepStatus.ERROR, errorMessage = e.message))
            }
        }

        // Persist diagnostics
        val diagnosticsJson = Json.encodeToString(diagnostics.toList())
        syncHistoryDao.updateDiagnostics(syncId, diagnosticsJson)

        // If ALL steps errored, report failure instead of fake success
        val allErrored = diagnostics.isNotEmpty() && diagnostics.all { it.status == StepStatus.ERROR }
        if (allErrored) {
            val summary = diagnostics.joinToString("; ") { "${it.service}/${it.step}: ${it.errorMessage}" }
            syncHistoryDao.updateStatus(
                id = syncId,
                status = SyncState.FAILED,
                completedAt = System.currentTimeMillis(),
                errorMessage = summary,
            )
            syncStateManager.onError("All API calls failed: $summary")
            return Result.failure(workDataOf(KEY_SYNC_ID to syncId))
        }

        return Result.success(workDataOf(KEY_SYNC_ID to syncId))
    } catch (e: SpotifyApiException) {
        Log.w(TAG, "Spotify API error (HTTP ${e.httpCode}), scheduling retry", e)
        val diagnosticsJson = Json.encodeToString(diagnostics.toList())
        syncHistoryDao.updateDiagnostics(syncId, diagnosticsJson)
        syncHistoryDao.updateStatus(
            id = syncId,
            status = SyncState.FAILED,
            completedAt = System.currentTimeMillis(),
            errorMessage = "Spotify rate limited (HTTP ${e.httpCode}), will retry",
        )
        syncStateManager.onError("Spotify API rate limited, retrying...", e)
        return Result.retry()
    } catch (e: Exception) {
        Log.e(TAG, "Playlist fetch failed", e)
        val diagnosticsJson = Json.encodeToString(diagnostics.toList())
        syncHistoryDao.updateDiagnostics(syncId, diagnosticsJson)
        syncHistoryDao.updateStatus(
            id = syncId,
            status = SyncState.FAILED,
            completedAt = System.currentTimeMillis(),
            errorMessage = e.message,
        )
        syncStateManager.onError("Fetch failed: ${e.message}", e)
        return Result.failure(workDataOf(KEY_SYNC_ID to syncId))
    }
}
```

- [ ] **Step 5: Add `updateDiagnostics` to SyncHistoryDao**

Check `SyncHistoryDao.kt` and add:

```kotlin
@Query("UPDATE sync_history SET diagnostics = :diagnostics WHERE id = :id")
suspend fun updateDiagnostics(id: Long, diagnostics: String?)
```

- [ ] **Step 6: Build the full project**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Build the full project**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (all three files updated atomically)

- [ ] **Step 8: Commit all three files together**

```bash
git add data/spotify/src/main/kotlin/com/stash/data/spotify/SpotifyApiClient.kt
git add data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorker.kt
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/SyncHistoryDao.kt
git commit -m "feat: wire SyncResult through API clients and PlaylistFetchWorker

SpotifyApiClient and YTMusicApiClient sync-path methods now return
SyncResult instead of bare List/emptyList. PlaylistFetchWorker collects
step-by-step diagnostics, persists them as JSON in sync_history, and
returns Result.failure() when ALL API calls error instead of silently
reporting success with zero data."
```

---

## Task 8: Full build verification

**Files:** None (verification only)

- [ ] **Step 1: Clean build**

Run: `./gradlew clean assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Review all changes**

Run: `git log --oneline -9` to see all commits from this plan.
Verify each commit message is clear and accurate.

- [ ] **Step 3: Tag the milestone**

```bash
git tag sync-fix-v1
```
