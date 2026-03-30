# Sync Pipeline Root Cause Fix — Design Spec

**Date:** 2026-03-30
**Status:** Approved
**Problem:** Both Spotify and YouTube Music sync report success but fetch zero data. Every prior fix addressed symptoms, not root causes.

## Root Cause Analysis

The sync pipeline has three layers of failure:

1. **Silent error swallowing (architectural flaw):** The API client methods (`getDailyMixes`, `getLikedSongs`, `getHomeMixes`, etc.) catch all exceptions and return `emptyList()`. While `PlaylistFetchWorker.doWork()` does have error handling for `SpotifyApiException` and generic `Exception`, the individual fetch helpers (`fetchSpotifyPlaylists`, `fetchYouTubePlaylists`) catch exceptions internally and continue. Since the API clients never throw — they return empty lists — no exception reaches `doWork()`, and it reports `Result.success()` with 0 data. The UI cannot distinguish "no data available" from "every API call failed silently."

2. **Stale API credentials and versions:** YouTube `CLIENT_VERSION` is `1.20240101.01.00` (2+ years old, likely rejected by InnerTube). Spotify uses SpotDL's shared client credentials (potentially revoked or globally rate-limited). sp_dc cookies can expire silently.

3. **Fragile response parsing:** InnerTube response structure changes regularly. Hardcoded navigation paths may no longer match. Spotify GraphQL operation hashes can change.

## Solution Design

### 1. SyncResult Type — Replace Silent Failures

**Location:** New file `core/model/src/main/kotlin/com/stash/core/model/SyncResult.kt`

> **Module placement:** `SyncResult` lives in `core:model` (not `core:data`) because both `data:spotify` and `data:ytmusic` need to return it. Placing it in `core:data` would create a circular dependency since `core:data` already depends on those data modules. `core:model` is a leaf dependency available to all modules.

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
- `SpotifyApiClient.kt`: Sync-path methods return `SyncResult<List<T>>` instead of `List<T>`
  - `getDailyMixes()` → `SyncResult<List<SpotifyPlaylistItem>>`
  - `getLikedSongs()` → `SyncResult<List<SpotifyTrackItem>>`
  - `getPlaylistTracks()` → `SyncResult<List<SpotifyTrackItem>>`
  - Note: `getUserPlaylists()` is only called internally by `getDailyMixes()`, not by the sync pipeline directly. It stays as `List<T>` internally; `getDailyMixes()` wraps the result in `SyncResult`.
- `YTMusicApiClient.kt`: All public methods return `SyncResult<List<T>>`
  - `getHomeMixes()` → `SyncResult<List<YTMusicPlaylist>>`
  - `getLikedSongs()` → `SyncResult<List<YTMusicTrack>>`
  - `getPlaylistTracks()` → `SyncResult<List<YTMusicTrack>>`
- `InnerTubeClient.kt`: `executeRequest()` returns a result with HTTP error info instead of `null`
- `PlaylistFetchWorker.kt`: Collects `SyncResult.Error` values. If ALL fetches error, returns `Result.failure()` instead of `Result.success()` with 0 data.

**Behavior changes:**
- Partial success still works: if YouTube errors but Spotify succeeds, sync continues and reports both outcomes.
- Total failure (all API calls error) now correctly reports `Result.failure()`.
- All errors include the specific reason (HTTP code, exception message, which step failed).

### 2. Sync Diagnostics — Step-Level Reporting

**Data model:**

```kotlin
data class SyncStepResult(
    val service: MusicSource,
    val step: String,
    val status: StepStatus,
    val itemCount: Int = 0,
    val errorMessage: String? = null,
    val httpCode: Int? = null,
)

enum class StepStatus { SUCCESS, EMPTY, ERROR }
```

**Storage:** `SyncHistoryEntity` gets a new `diagnostics: String?` column containing JSON-serialized `List<SyncStepResult>`. Implementation notes:
- Room database migration: increment current version by 1, use `database.execSQL("ALTER TABLE sync_history ADD COLUMN diagnostics TEXT")` in the migration
- JSON serialization: use `kotlinx.serialization.Json.encodeToString()` in the worker when saving, `Json.decodeFromString()` in the ViewModel when reading. No Room TypeConverter needed — store as plain `String?` and deserialize manually.
- `SyncStepResult` and `StepStatus` should be `@Serializable`

**UI changes:**
- Sync screen shows step-by-step breakdown after each sync run.
- Each step shows: service icon, step name, status (checkmark/warning/error), item count or error message.
- Example display:
  ```
  Last sync: Failed
    Spotify Daily Mixes: Error — HTTP 403 (client credentials rejected)
    Spotify Liked Songs: Error — GraphQL returned null
    YouTube Home Mixes:  Error — HTTP 403 (stale client version)
    YouTube Liked Songs: Error — HTTP 403 (stale client version)
  ```

**Files modified:**
- `SyncHistoryEntity.kt`: Add `diagnostics` column
- `SyncHistoryDao.kt`: Update queries if needed
- Room migration in `StashDatabase.kt`
- `PlaylistFetchWorker.kt`: Build and persist diagnostics list
- `SyncViewModel.kt` / sync UI: Display diagnostics breakdown

### 3. Auth Validation — Verify Credentials on Connect

**Spotify validation sequence:**
1. Store sp_dc token and extract username from JWT (existing flow)
2. After username is resolved, call `spotifyApiClient.getDailyMixes()` as a test
3. Since `getDailyMixes()` now returns `SyncResult`, we can inspect success/error
4. Validates: (a) client credentials endpoint works, (b) stored username is correct, (c) Web API responds

**YouTube validation approach:**
- `TokenManagerImpl` does NOT depend on `InnerTubeClient` (that would couple `core:auth` to `data:ytmusic`)
- Instead, validation is done in `SettingsViewModel` which has access to both `TokenManager` and `YTMusicApiClient`
- Flow: `SettingsViewModel.onConnectYouTubeWithCookie()` → store cookie via `TokenManager` → test via `YTMusicApiClient.getHomeMixes()` → if error, clear cookie and show error
- Validates: (a) SAPISID is valid, (b) CLIENT_VERSION is accepted, (c) response is parseable

**Files modified:**
- `TokenManager.kt` / `TokenManagerImpl.kt`: No change to return types (keep `Boolean`). Credential storage stays simple.
- `SettingsViewModel.kt`: Orchestrates validation by calling API clients after storing credentials. On validation failure, calls `tokenManager.clearAuth()` and surfaces the error.
- `YouTubeCookieDialog.kt` / Spotify cookie dialog: Show specific error messages from validation (e.g., "Cookie saved but YouTube Music returned HTTP 403 — client version may be outdated")

### 4. Update Stale API Layer

**YouTube InnerTube:**
- Update `CLIENT_VERSION` to current value from ytmusicapi reference implementation
- Add `"userAgent"` field to InnerTube context object
- Verify response parser paths match current InnerTube format
- Add fallback parse path with descriptive `SyncResult.Error` if primary path fails

**Spotify:**
- Verify SpotDL client credentials still work; if not, register our own Spotify Developer app for client_credentials grant
- Verify JWT username extraction; add logging if username resolves to empty
- Update `CLIENT_VERSION` in `SpotifyAuthConfig` to match current web player

**Files modified:**
- `InnerTubeClient.kt`: Update CLIENT_VERSION, add userAgent to context
- `YTMusicApiClient.kt`: Verify/update response parser navigation paths
- `SpotifyAuthConfig.kt`: Update CLIENT_VERSION
- `SpotifyAuthManager.kt`: Add logging around username extraction
- `SpotifyApiClient.kt`: Verify client credentials flow, add logging

## Implementation Order

1. **SyncResult type + diagnostics** (Sections 1 & 2) — the foundation that makes everything else debuggable
2. **Update stale API layer** (Section 4) — fix the immediate broken components
3. **Auth validation** (Section 3) — prevent storing credentials that don't work

## Files Changed Summary

| File | Change Type |
|------|-------------|
| `core/model/.../SyncResult.kt` | New (in core:model to avoid circular deps) |
| `core/model/.../SyncStepResult.kt` | New (in core:model, @Serializable) |
| `core/data/.../db/entity/SyncHistoryEntity.kt` | Modified (add column) |
| `core/data/.../db/StashDatabase.kt` | Modified (migration) |
| `core/data/.../sync/workers/PlaylistFetchWorker.kt` | Modified (use SyncResult, collect diagnostics) |
| `data/spotify/.../SpotifyApiClient.kt` | Modified (return SyncResult) |
| `data/ytmusic/.../YTMusicApiClient.kt` | Modified (return SyncResult) |
| `data/ytmusic/.../InnerTubeClient.kt` | Modified (update version, return errors) |
| `core/auth/.../TokenManager.kt` | Unchanged (validation stays in SettingsViewModel) |
| `core/auth/.../TokenManagerImpl.kt` | Unchanged for auth validation |
| `core/auth/.../spotify/SpotifyAuthConfig.kt` | Modified (update version) |
| `core/auth/.../spotify/SpotifyAuthManager.kt` | Modified (logging) |
| `feature/settings/.../SettingsViewModel.kt` | Modified (handle validation results) |
| `feature/settings/.../components/YouTubeCookieDialog.kt` | Modified (show validation errors) |
| `feature/sync/.../SyncViewModel.kt` | Modified (display diagnostics) |
| Sync UI composable | Modified (diagnostics display) |

## Out of Scope

- Migrating to official Spotify/YouTube APIs (separate project)
- Download pipeline changes (TrackDownloadWorker, DiffWorker)
- UI redesign beyond diagnostics display
- Automated CLIENT_VERSION detection
