# Sync Pipeline Root Cause Fix — Design Spec (Revised)

**Date:** 2026-03-30
**Status:** Approved (revised after adversarial review + API research)
**Problem:** Both Spotify and YouTube Music sync report success but fetch zero data. Every prior fix addressed symptoms, not root causes.

## Root Cause Analysis

Three smoking guns were identified through adversarial code review and live API research:

### Smoking Gun #1: Spotify's `Get User's Playlists` endpoint was REMOVED

`SpotifyApiClient.getUserPlaylists()` calls `GET /v1/users/{username}/playlists`. **Spotify removed this endpoint in their February 2026 API changes.** It returns 404, which is caught by the try/catch in `getUserPlaylists()` and silently returns `emptyList()`. Since `getDailyMixes()` depends on `getUserPlaylists()`, Daily Mixes always returns empty. This is not a credentials or rate-limit problem — the endpoint no longer exists.

**Source:** [Spotify Web API February 2026 Changelog](https://developer.spotify.com/documentation/web-api/references/changes/february-2026)

### Smoking Gun #2: `isAuthenticated(SPOTIFY)` checks access token expiry, not sp_dc cookie

```kotlin
// TokenManagerImpl.kt line 186 — CURRENT (BROKEN)
AuthService.SPOTIFY -> tokenStore.spotifyToken.first()?.let { !it.isExpired } ?: false
```

The Spotify access token expires in ~1 hour. After that, `isAuthenticated()` returns `false` and `PlaylistFetchWorker` skips Spotify entirely (line 84: `if (isSpotifyAuthenticated)`). The sp_dc cookie stored in `refreshToken` is still valid and `getSpotifyAccessToken()` would auto-refresh it — but `isAuthenticated()` never checks the cookie. So unless the user syncs within 1 hour of connecting, Spotify is silently skipped on every subsequent sync.

### Smoking Gun #3: YouTube CLIENT_VERSION must be date-based, not hardcoded

```kotlin
// InnerTubeClient.kt line 53 — CURRENT (BROKEN)
private const val CLIENT_VERSION = "1.20240101.01.00"
```

The ytmusicapi reference implementation generates CLIENT_VERSION dynamically: `"1.{YYYYMMDD}.01.00"` using today's date. Our hardcoded value from January 2024 is 2+ years stale. InnerTube can reject old client versions with 403 or return different response formats.

**Source:** [ytmusicapi constants.py](https://github.com/sigma67/ytmusicapi) — `YTM_VERSION = "1." + time.strftime("%Y%m%d", time.gmtime()) + ".01.00"`

### Supporting Failures (from adversarial review)

4. **Silent error swallowing:** API client methods catch all exceptions and return `emptyList()`. `PlaylistFetchWorker` never sees an exception and reports `Result.success()` with 0 data. The entire 4-worker chain completes "successfully" with nothing fetched.

5. **Response body connection leak:** `InnerTubeClient.executeRequest()` reads the error body (line 153) but never calls `response.close()` or `response.body?.close()` in the error path. This leaks OkHttp connections across retries.

6. **InnerTube context missing `user` object:** ytmusicapi includes `"user": {}` in the context. Our implementation omits it.

7. **SpotDL credentials at risk:** Spotify's February 2026 Developer Mode changes limit each client ID to 5 authorized users. SpotDL's shared credentials (`5f573c9620494bae87890c0f08a60293`) violate this and could be revoked.

8. **client_credentials NOT immune to 429s:** The comment in `SpotifyAuthManager.kt` claiming immunity is incorrect. Rate limiting is per client ID, not per auth method.

9. **SyncFinalizeWorker destroys evidence:** `remoteSnapshotDao.deleteAllSnapshotsBySyncId(syncId)` removes all snapshot data after sync, making it impossible to diagnose what was fetched.

### Why Every Previous Fix Failed

| Previous fix | Why it didn't help |
|---|---|
| Switch to client_credentials | Still calls the **removed** `/v1/users/{id}/playlists` endpoint |
| Add retry with backoff for 429s | Retrying a removed endpoint won't un-remove it |
| Update client_version to new hardcoded value | Still hardcoded; still calling dead Spotify endpoint |
| Add sp_dc cookie auth | sp_dc works fine, but `isAuthenticated()` checks access token expiry, skipping Spotify after 1 hour |
| Rewrite to GraphQL Partner API | Partially helped, but getDailyMixes still routes through the dead endpoint |

---

## Solution Design

### Implementation Order (revised — fix first, observe second)

**Priority 1: Fix the three smoking guns (makes sync actually work)**
**Priority 2: Fix secondary bugs (connection leaks, context fields)**
**Priority 3: Add diagnostics infrastructure (makes future breaks visible)**
**Priority 4: Auth validation on connect**

---

### Priority 1A: Fix `isAuthenticated(SPOTIFY)`

**File:** `core/auth/src/main/kotlin/com/stash/core/auth/TokenManagerImpl.kt`

**Change:** Check sp_dc cookie existence instead of access token expiry.

```kotlin
// BEFORE (broken — access token expires in ~1 hour)
AuthService.SPOTIFY -> tokenStore.spotifyToken.first()?.let { !it.isExpired } ?: false

// AFTER (correct — sp_dc cookie is the long-lived credential)
AuthService.SPOTIFY -> tokenStore.spotifyToken.first()?.let {
    it.refreshToken.isNotEmpty()  // refreshToken holds the sp_dc cookie
} ?: false
```

**Why this works:** The sp_dc cookie is the actual credential. It lasts weeks/months. The access token is a short-lived derivative that `getSpotifyAccessToken()` auto-refreshes on demand. The auth check should validate the long-lived credential exists, not that the short-lived derivative hasn't expired yet.

### Priority 1B: Replace the dead Spotify endpoint

**File:** `data/spotify/src/main/kotlin/com/stash/data/spotify/SpotifyApiClient.kt`

**Change:** Replace `getUserPlaylists()` which calls the removed `/v1/users/{id}/playlists` endpoint. Move playlist discovery to the GraphQL Partner API using the `libraryV3` query, which uses sp_dc auth and is still functional.

**New approach for `getDailyMixes()`:**
- Use the existing `executeGraphQL()` infrastructure with the `libraryV3` operation
- Query the user's library for playlists owned by "spotify" with names matching "Daily Mix"
- This uses the sp_dc-derived access token + client token (same auth the liked songs path already uses)
- Falls back to empty list if GraphQL fails (same graceful degradation)

**New approach for `getUserPlaylists()` (internal helper):**
- Replace the Web API call with a GraphQL `libraryV3` query
- Parse the response using the existing `parseLibraryResponse()` method
- This eliminates dependency on the removed Web API endpoint AND on client_credentials for playlist enumeration

**What stays the same:**
- `getPlaylistTracks()` two-pronged approach (client_credentials for public track data, GraphQL for private playlists) — the tracks endpoint `/v1/playlists/{id}/tracks` was NOT removed
- `getLikedSongs()` GraphQL approach (already working)
- Client credentials flow (still useful for track fetching, even if not for playlist enumeration)

### Priority 1C: Make YouTube CLIENT_VERSION dynamic

**File:** `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt`

**Change:** Generate CLIENT_VERSION from today's date instead of hardcoding.

```kotlin
// BEFORE (broken — 2+ years stale)
private const val CLIENT_VERSION = "1.20240101.01.00"

// AFTER (matches ytmusicapi's approach)
private val CLIENT_VERSION: String
    get() = "1.${java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)}.01.00"
```

**Why this works:** This is exactly what ytmusicapi does. The InnerTube API uses the version string for client identification, and a current date signals a current web client.

---

### Priority 2: Fix secondary bugs

**2A: Close response body in InnerTubeClient error path**

**File:** `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt`

```kotlin
// In executeRequest(), error path:
if (!response.isSuccessful) {
    val errorBody = response.body?.string()
    Log.e(TAG, "executeRequest: HTTP ${response.code} - $errorBody")
    response.close()  // ADD THIS — prevents connection pool leak
    return null
}
```

Also close response body after successful read:
```kotlin
val responseBody = response.body?.string() ?: run {
    response.close()
    return null
}
response.close()
```

**2B: Add `user` object to InnerTube context**

**File:** `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt`

```kotlin
// In buildContext():
private fun buildContext(): JsonObject = buildJsonObject {
    putJsonObject("client") {
        put("clientName", CLIENT_NAME)
        put("clientVersion", CLIENT_VERSION)
        put("hl", "en")
        put("gl", "US")
    }
    putJsonObject("user") {}  // ADD THIS — matches ytmusicapi
}
```

---

### Priority 3: SyncResult type + diagnostics

Same design as the original spec, with these corrections from the adversarial review:

**3A: SyncResult type**

**Location:** `core/model/src/main/kotlin/com/stash/core/model/SyncResult.kt` (in `core:model` to avoid circular module dependency)

```kotlin
sealed class SyncResult<out T> {
    data class Success<T>(val data: T) : SyncResult<T>()
    data class Empty(val reason: String) : SyncResult<Nothing>()
    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val httpCode: Int? = null,
    ) : SyncResult<Nothing>()
}
```

**Files modified:**
- `SpotifyApiClient.kt`: Sync-path methods return `SyncResult<List<T>>`
  - `getDailyMixes()` -> `SyncResult<List<SpotifyPlaylistItem>>`
  - `getLikedSongs()` -> `SyncResult<List<SpotifyTrackItem>>`
  - `getPlaylistTracks()` -> `SyncResult<List<SpotifyTrackItem>>`
  - Note: `getUserPlaylists()` is internal to `getDailyMixes()` and stays as `List<T>`
- `YTMusicApiClient.kt`: All public methods return `SyncResult<List<T>>`
  - `getHomeMixes()` -> `SyncResult<List<YTMusicPlaylist>>`
  - `getLikedSongs()` -> `SyncResult<List<YTMusicTrack>>`
  - `getPlaylistTracks()` -> `SyncResult<List<YTMusicTrack>>`
- `InnerTubeClient.kt`: `executeRequest()` returns error info instead of `null`
- `PlaylistFetchWorker.kt`: Collects `SyncResult.Error` values. Returns `Result.failure()` if ALL fetches error.

**Implementation note:** All six layers of return type changes (InnerTubeClient -> YTMusicApiClient -> PlaylistFetchWorker, and SpotifyApiClient -> PlaylistFetchWorker) must be done atomically — partial migration will not compile. Add a `SyncResult.getOrEmpty()` extension function during migration if needed:
```kotlin
fun <T> SyncResult<List<T>>.getOrEmpty(): List<T> = when (this) {
    is SyncResult.Success -> data
    else -> emptyList()
}
```

**3B: Sync diagnostics**

**Data model** (in `core:model`, with `@Serializable` annotations):

```kotlin
@Serializable
data class SyncStepResult(
    val service: MusicSource,
    val step: String,
    val status: StepStatus,
    val itemCount: Int = 0,
    val errorMessage: String? = null,
    val httpCode: Int? = null,
)

@Serializable
enum class StepStatus { SUCCESS, EMPTY, ERROR }
```

**Storage:**
- `SyncHistoryEntity` gets a new `diagnostics: String?` column
- Room migration: increment database version by 1, use `ALTER TABLE sync_history ADD COLUMN diagnostics TEXT`
- JSON serialization: `kotlinx.serialization.Json.encodeToString()` when saving, `Json.decodeFromString()` when reading. No Room TypeConverter needed.

**UI:** Sync screen shows step-by-step breakdown:
```
Last sync: Failed
  Spotify Daily Mixes: Error — endpoint removed (404)
  Spotify Liked Songs: Error — sp_dc expired
  YouTube Home Mixes:  Error — HTTP 403 (stale client version)
  YouTube Liked Songs: Error — HTTP 403 (stale client version)
```

**Data flow:** `SyncHistoryDao` -> `SyncViewModel` -> Composable. ViewModel deserializes the `diagnostics` JSON string and maps to UI state.

---

### Priority 4: Auth validation on connect

**Spotify:** After storing sp_dc token and resolving username, `SettingsViewModel` calls `spotifyApiClient.getDailyMixes()` (which now returns `SyncResult`). On error, clears stored credentials and shows the specific error in the cookie dialog.

**YouTube:** After storing cookie, `SettingsViewModel` calls `ytMusicApiClient.getHomeMixes()` (which now returns `SyncResult`). On error, clears stored credentials and shows the error.

**Sequencing for Spotify:** Token storage -> username extraction from JWT -> test API call -> show result. The username must be resolved before the test call because it's needed for GraphQL queries.

**Architecture:** Validation lives in `SettingsViewModel` (not `TokenManagerImpl`) to avoid coupling `core:auth` to `data:spotify` or `data:ytmusic`. `SettingsViewModel` already has access to both `TokenManager` and the API clients via Hilt injection.

---

## Files Changed Summary

| File | Change | Priority |
|------|--------|----------|
| `core/auth/.../TokenManagerImpl.kt` | Fix `isAuthenticated(SPOTIFY)` to check sp_dc cookie | P1 |
| `data/spotify/.../SpotifyApiClient.kt` | Replace dead `/v1/users/{id}/playlists` with GraphQL libraryV3 | P1 |
| `data/ytmusic/.../InnerTubeClient.kt` | Dynamic CLIENT_VERSION, close response body, add `user: {}` to context | P1+P2 |
| `data/ytmusic/.../YTMusicApiClient.kt` | Return `SyncResult` from public methods | P3 |
| `core/model/.../SyncResult.kt` | New — sealed result type | P3 |
| `core/model/.../SyncStepResult.kt` | New — diagnostics data model (@Serializable) | P3 |
| `core/data/.../db/entity/SyncHistoryEntity.kt` | Add `diagnostics` column | P3 |
| `core/data/.../db/StashDatabase.kt` | Room migration (add column) | P3 |
| `core/data/.../sync/workers/PlaylistFetchWorker.kt` | Use SyncResult, collect diagnostics, fail on total error | P3 |
| `core/auth/.../spotify/SpotifyAuthConfig.kt` | Update CLIENT_VERSION if needed | P2 |
| `core/auth/.../spotify/SpotifyAuthManager.kt` | Add logging around username extraction | P2 |
| `feature/settings/.../SettingsViewModel.kt` | Auth validation after connect | P4 |
| `feature/settings/.../components/YouTubeCookieDialog.kt` | Show validation errors | P4 |
| `feature/sync/.../SyncViewModel.kt` | Display diagnostics breakdown | P3 |
| Sync UI composable | Diagnostics display | P3 |

## Out of Scope

- Migrating to official Spotify/YouTube APIs (separate project)
- Download pipeline changes (TrackDownloadWorker, DiffWorker)
- UI redesign beyond diagnostics display
- Automated CLIENT_VERSION detection
- Registering our own Spotify Developer app (future hardening)
- WorkManager ExistingWorkPolicy change (separate concern)
- DiffWorker snapshotId deduplication for YouTube (works correctly by accident today)
