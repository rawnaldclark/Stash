# YouTube Sync Pagination & Parallelism — Design Spec

**Date:** 2026-04-27
**Status:** Draft (pending spec review)
**Owner:** rawnaldclark

## Problem

The YouTube Music sync pipeline silently truncates every multi-page surface:

- **Liked Songs** returns ~24 tracks instead of 2000+.
- **Home mixes** (Discover Mix, Daily Mixes, Supermix, etc.) return ~25 tracks
  instead of the full mix length.
- **User-saved playlists** are capped to whatever fits in InnerTube's first
  browse response (~100 items).

Root cause: `YTMusicApiClient.getLikedSongs()`, `getPlaylistTracks()`, and
`getUserPlaylists()` each issue a single `InnerTubeClient.browse(browseId)`
call and parse only the embedded shelf. None of them read or follow the
`continuations[].nextContinuationData.continuation` token that InnerTube
returns alongside the first batch. There is zero continuation handling
anywhere in the `data/ytmusic` module — `grep -r continuation data/ytmusic`
returns no matches.

Spotify's parallel surfaces don't have this gap because `getLikedSongs(limit,
offset)` runs in a real pagination loop in `PlaylistFetchWorker.kt:282-300`.

## Why this matters beyond "more tracks"

YouTube's `getPlaylistTracks` response carries the **canonical `videoId`** for
each track inline. A track sourced from YT Music goes straight to download
with no search-and-match step — no risk of grabbing the wrong version, no
match-score threshold, no failed-download fallback. This is a strict accuracy
advantage over the Spotify pipeline (which has to search YouTube for each
track and is prone to mismatches like "Black Skinhead" → "BLKKK SKKKN HEAD").

Fixing pagination unlocks that advantage for ~2000+ tracks per user instead
of the current ~24.

## Goals

1. **Correctness:** every multi-page YouTube surface returns its full track
   list, bounded only by what InnerTube actually exposes.
2. **Latency:** parallelize the per-mix and per-playlist fetch loops to cut
   wall-clock sync time.
3. **Observability:** surface partial syncs (continuation failure or count
   mismatch) so users and diagnostics can see when data is incomplete.
4. **Bounded scope:** no behavior change for Spotify, no changes to download
   or matching pipelines, no new abstractions beyond what this work needs.

## Non-goals

- Spotify pagination audit (deferred — separate work if needed).
- Spotify→YT matching improvements ("Black Skinhead" class of bug — separate
  work).
- Streaming JSON parser, response cache, telemetry overhaul, generic
  `Paginator` abstraction. All over-engineering for this fix.
- New UI for partial-sync indication. The DB column lands; the visual
  indicator is future work.
- Per-track download throttling, retry tuning, or DiffWorker changes.

## Architecture

Three layers change. Nothing else.

### Layer 1 — `InnerTubeClient` (data/ytmusic/.../InnerTubeClient.kt)

**New overload** for continuation requests:

```kotlin
suspend fun browse(continuation: String): JsonObject?
```

Posts to `https://music.youtube.com/youtubei/v1/browse?ctoken={token}&continuation={token}&type=next&prettyPrint=false`
with the same `context` body but no `browseId` field. All other auth /
header / variant logic from the existing `executeRequest` is reused.

**Per-sync auth cache.** Add two `@Volatile` fields and helpers:

```kotlin
@Volatile private var cachedCookie: String? = null
@Volatile private var cachedSapiSid: String? = null
@Volatile private var cachedAuthHeader: String? = null

fun beginSyncSession()  // resolve cookie + SAPISID + auth header once, populate cache
fun endSyncSession()    // clear cache (called from PlaylistFetchWorker.doWork finally block)
```

`executeRequest` reads from the cache when populated, falls back to the
existing per-call resolution otherwise (so non-sync callers like
`searchCanonical` and `playerForAudio` keep working unchanged). On any HTTP
401 response, the cache is cleared so the next call re-resolves (likely
forcing the user through re-auth).

### Layer 2 — `YTMusicApiClient` (data/ytmusic/.../YTMusicApiClient.kt)

**New private helper:**

```kotlin
private suspend fun <T> paginateBrowse(
    initialResponse: JsonObject,
    parsePage: (JsonObject) -> List<T>,
): PaginationResult<T>
```

Walks `continuations[0].nextContinuationData.continuation` from the initial
response via `extractContinuationToken` (one helper used for both the
initial-response and continuation-response shapes — see below), calls
`innerTubeClient.browse(token)` for each subsequent page, parses with the
supplied lambda, accumulates items. Returns:

```kotlin
data class PaginationResult<T>(
    val items: List<T>,
    val pagesFetched: Int,
    val partial: Boolean,        // true if a page failed or MAX_PAGES hit
    val partialReason: String?,  // human-readable: "page 4 failed after 2 retries", etc.
)
```

**Token extraction (one helper for both response shapes):**

```kotlin
private fun extractContinuationToken(response: JsonObject): String?
```

Looks first under the initial-response shelf paths (twoColumn or singleColumn
→ musicPlaylistShelfRenderer.continuations[0].nextContinuationData.continuation),
then under the continuation-response shape (continuationContents.{musicPlaylistShelfContinuation
| musicShelfContinuation}.continuations[0].nextContinuationData.continuation).
Returns null if no token found.

**Continuation-page parser:**

```kotlin
private fun parseContinuationPage(response: JsonObject): List<YTMusicTrack>
```

Reads `continuationContents.musicPlaylistShelfContinuation.contents[]` (or
`musicShelfContinuation.contents[]` for liked-songs path) and dispatches each
item to the existing `parseTrackFromRenderer`. No new track-parsing code —
just a different shelf location.

**Public API changes:**

```kotlin
suspend fun getLikedSongs(): SyncResult<PagedTracks>
suspend fun getPlaylistTracks(playlistId: String): SyncResult<PagedTracks>
suspend fun getUserPlaylists(): SyncResult<PagedPlaylists>
```

`getHomeMixes()` is **unchanged**. The home feed is a single carousel of mix
metadata (not a paginated track shelf) and InnerTube does not return
continuation tokens for it. The shape of `parseMixesFromHome` is unaffected
by this work.

Where:

```kotlin
data class PagedTracks(
    val tracks: List<YTMusicTrack>,
    val expectedCount: Int?,    // from playlist header, null if unparseable
    val partial: Boolean,
    val partialReason: String?,
)

data class PagedPlaylists(
    val playlists: List<YTMusicPlaylist>,
    val partial: Boolean,
    val partialReason: String?,
)
```

`PagedPlaylists` has no `expectedCount` because the library page doesn't
publish a total.

**Header `expectedCount` extraction.** New helper:

```kotlin
private fun extractExpectedTrackCount(response: JsonObject): Int?
```

Reads (in order):
1. `header.musicEditablePlaylistDetailHeaderRenderer.header.musicResponsiveHeaderRenderer.secondSubtitle.runs[].text`
2. `header.musicDetailHeaderRenderer.secondSubtitle.runs[].text`

Parses for `"X songs"` / `"X tracks"` / `"X videos"` (case-insensitive,
comma-stripped). Returns null on any parse failure.

**Verification step (inside each public method):** after `paginateBrowse`
returns, compare `tracks.size` to `expectedCount`. If `expectedCount != null`
and `tracks.size < expectedCount * 0.95`, mark `partial = true` and append
to `partialReason`.

### Layer 3 — `PlaylistFetchWorker.fetchYouTubePlaylists` (core/data/.../PlaylistFetchWorker.kt:420)

**Begin/end auth session** at the top and bottom of the function:

```kotlin
private suspend fun fetchYouTubePlaylists(syncId: Long, diagnostics: MutableList<SyncStepResult>) {
    innerTubeClient.beginSyncSession()
    try {
        // existing fetch logic, refactored to use parallelism (below)
    } finally {
        innerTubeClient.endSyncSession()
    }
}
```

`InnerTubeClient` is injected into `PlaylistFetchWorker` for this — currently
the worker only holds `ytMusicApiClient`, so we add `private val innerTubeClient: InnerTubeClient`
to the constructor and Hilt provider.

**Parallelize per-mix and per-playlist fetches:**

```kotlin
val sem = Semaphore(MAX_PARALLEL_FETCHES)  // 3
coroutineScope {
    homeMixes.mapIndexed { index, mix ->
        async {
            sem.withPermit { fetchAndSnapshotYTMix(mix, index, syncId) }
        }
    }.awaitAll()
}
```

Same pattern for the user-playlist loop. The single semaphore is shared
across both groups (mixes + user playlists), so total concurrent in-flight
browse calls during a sync ≤ 3 regardless of which group is running.

`MAX_PARALLEL_FETCHES = 3` is a private const in `PlaylistFetchWorker`.

**Inside one playlist's pagination loop = sequential** (token from page N
drives page N+1).

**Snapshot writes carry the new fields:**

```kotlin
remoteSnapshotDao.insertPlaylistSnapshot(
    RemotePlaylistSnapshotEntity(
        // existing fields unchanged
        trackCount = pagedTracks.tracks.size,
        expectedCount = pagedTracks.expectedCount,    // NEW
        partial = pagedTracks.partial,                // NEW
    )
)
```

**Diagnostics:**

```kotlin
diagnostics.add(SyncStepResult(
    source = "YOUTUBE",
    step = "getLikedSongs",
    status = StepStatus.SUCCESS,  // partial fetch is not a failure
    count = pagedTracks.tracks.size,
    errorMessage = if (pagedTracks.partial) {
        "partial: ${pagedTracks.tracks.size}/${pagedTracks.expectedCount ?: "?"} — ${pagedTracks.partialReason}"
    } else null,
))
```

Status stays SUCCESS for both partial and complete fetches — a partial sync
is degraded data, not a failed step. The `errorMessage` field carries the
partial annotation for the Sync screen, where the existing UI already
renders it under each step row.

## Pagination Loop Semantics

**Loop:**

```
items, token = parseInitialPage(initialResponse)
pages = 1
partial = false
partialReason = null

while (token != null && pages < MAX_PAGES) {
    next, attempts = browseWithRetry(token)
    if (next == null) {
        partial = true
        partialReason = "page ${pages + 1} failed after $attempts attempts"
        break
    }
    items += parseContinuationPage(next)
    token = extractContinuationToken(next)
    pages++
}

if (pages >= MAX_PAGES && token != null) {
    partial = true
    partialReason = "hit MAX_PAGES=$MAX_PAGES safety cap"
}
```

**Constants:**
- `MAX_PAGES = 100` — safety cap on continuation depth (~10K items).
- `RETRY_BACKOFFS_MS = listOf(500L, 1500L)` — two retries with these delays.

**`browseWithRetry(token)` policy:**

| InnerTube response | Action |
|---|---|
| Non-null JsonObject | Return it. |
| `null` due to network/IO error | Retry up to 2 times per `RETRY_BACKOFFS_MS`. |
| `null` due to HTTP 5xx | Retry up to 2 times per `RETRY_BACKOFFS_MS`. |
| `null` due to HTTP 4xx (esp. 401/403) | No retry. Return null. Mark partial. Auth-cache cleared. |
| Throws | Treated as null + retry. |

`InnerTubeClient.executeRequest` already returns `null` on `!resp.isSuccessful`
without distinguishing 4xx vs 5xx vs network. To support the policy table,
`executeRequest` is extended to surface the HTTP status code (or a sentinel
for non-HTTP failures) so the retry helper can decide. Implementation: a
private `executeRequestWithStatus(...): Pair<JsonObject?, Int>` used by both
the new continuation `browse` overload and the existing `browse(browseId)`
path. The original `executeRequest` becomes a thin wrapper that drops the
status code, preserving its current callers.

## Concurrency Model

```
fetchYouTubePlaylists(syncId)
  beginSyncSession()
  try:
    Semaphore(3) shared across:
      ├── parallel: each home mix → fetchAndSnapshotYTMix() → paginateBrowse() [sequential pages inside]
      └── parallel: each user playlist → fetchAndSnapshotYTPlaylist() → paginateBrowse() [sequential pages inside]
    Liked Songs runs sequentially after both parallel groups (one playlist, no benefit from outer parallelism).
  finally:
    endSyncSession()
```

- Outer cap: 3 concurrent `browse` calls in flight at any time during the
  sync.
- Inner pagination loops are sequential by necessity (continuation token
  chain).
- Failure isolation preserved: each `async` block's body is wrapped in the
  same try/catch the existing serial code uses; a failed playlist is logged
  and skipped, siblings continue.
- Auth cache is hit by every concurrent fetch. `@Volatile` fields make
  individual reads/writes safe; the only race is that a 401-driven cache
  clear during one in-flight fetch can briefly overlap with reads from a
  sibling `async`. The worst-case outcome is one extra failed call on a
  sibling before it re-resolves auth from `tokenManager`. Acceptable —
  the alternative (synchronizing the cache) buys nothing once cookies
  have actually expired.

## Verification & Error Handling

**Verification logic (per playlist after pagination):**

```
if (expectedCount != null) {
    if (tracks.size < expectedCount * 0.95) {
        partial = true
        partialReason = appendOrSet(partialReason, "fetched ${tracks.size} of ${expectedCount} expected")
        Log.w(TAG, "$playlistName: short fetch — ${tracks.size}/${expectedCount}")
    }
}
```

**Tolerance rationale:** `expectedCount` from the playlist header includes
videos that get filtered server-side from page contents (region-locked,
deleted, age-restricted). A 5% gap is normal; a 25% gap is a real signal.

**Surfaced in:**
- `RemotePlaylistSnapshotEntity.partial: Boolean` (new column, default 0).
- `RemotePlaylistSnapshotEntity.expectedCount: Int?` (new nullable column).
- `SyncStepResult.errorMessage` carries `"partial: 1247/2000 — fetched 1247 of 2000 expected"`.
- Logged at WARN with playlist name and counts.

**Schema migration (Room v15 → v16):**

```kotlin
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE remote_playlist_snapshots ADD COLUMN partial INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE remote_playlist_snapshots ADD COLUMN expected_count INTEGER DEFAULT NULL")
    }
}
```

Bump `@Database(version = 16, ...)` and register `MIGRATION_15_16` in the
Room builder where existing migrations are registered.

## Testing Approach

**Fixture capture (one-time, manual):**

1. Add a temporary `Log.d` of `responseBody` in `InnerTubeClient.executeRequest`.
2. Run debug build, sync Liked Songs.
3. Capture the first browse response and the first continuation response.
4. Sanitize: scrub cookies, user identifiers, channel IDs that look personal.
   Keep `videoId`, `title`, `artists`, `continuation` tokens.
5. Save to:
   - `data/ytmusic/src/test/resources/fixtures/liked_songs_page1.json`
   - `data/ytmusic/src/test/resources/fixtures/liked_songs_page2.json`
6. Capture an identical pair for a long playlist (e.g., a 500-track user
   playlist) for the `getPlaylistTracks` test:
   - `playlist_long_page1.json`
   - `playlist_long_page2.json`
7. Remove the temporary log.

**Unit tests (`YTMusicApiClientTest`, follows existing `fakeBrowseClient` pattern):**

| Case | Verifies |
|---|---|
| Single-page playlist (no continuation token in response) | Existing one-shot path unchanged; `partial=false`. |
| Two-page playlist (token in page 1, none in page 2) | Token extracted, `browse(continuation)` called, items merged in correct order. |
| Page 2 returns null on first attempt, succeeds on retry | Retry-with-backoff works; final `partial=false`. |
| Page 2 returns null on all 3 attempts | `partial=true`, items kept, `partialReason` mentions retries. |
| Page 2 returns 401 (no retry) | `partial=true`, items kept, no retry attempted, auth cache cleared. |
| Header says 2000, fetched 1500 (>5% short) | `partial=true`, warning logged. |
| Header says 2000, fetched 1990 (within 5%) | `partial=false`, no warning. |
| Header missing entirely | `expectedCount=null`, no verification path runs, `partial=false`. |
| Synthetic infinite-continuation fixture | Stops at `MAX_PAGES=100`, `partial=true`, reason mentions cap. |
| Auth cache: `beginSyncSession` populates, `endSyncSession` clears | Verified via reflection or via spy on `tokenManager.getYouTubeCookie` call count. |

**Worker-level test (`PlaylistFetchWorkerTest` — create if missing):**

- Mock `YTMusicApiClient` returns `PagedTracks(partial=true, expectedCount=2000, tracks=fake1500)`.
- Verify `RemotePlaylistSnapshotEntity` written with `partial=true`, `expectedCount=2000`, `trackCount=1500`.
- Verify `SyncStepResult` carries the partial annotation in `errorMessage`.

**Concurrency test:**

- Mock `YTMusicApiClient` with 8 fake mixes, each adding a small delay.
- Use a counter that records max-concurrent `getPlaylistTracks` invocations.
- Assert max ≤ 3.

**No real-network tests.** Mocked `InnerTubeClient` everywhere.

## File-by-file change summary

| File | Change |
|---|---|
| `data/ytmusic/.../InnerTubeClient.kt` | Add `browse(continuation: String)` overload; add `beginSyncSession`/`endSyncSession` + cache fields; refactor `executeRequest` to expose status code internally. |
| `data/ytmusic/.../YTMusicApiClient.kt` | Add `paginateBrowse`, `extractContinuationToken`, `parseContinuationPage`, `extractExpectedTrackCount`. Change return types of `getLikedSongs`, `getPlaylistTracks`, `getUserPlaylists` to `SyncResult<PagedTracks>` / `SyncResult<PagedPlaylists>`. |
| `data/ytmusic/.../model/YTMusicModels.kt` | Append `PagedTracks` and `PagedPlaylists` data classes alongside existing `YTMusicTrack` / `YTMusicPlaylist` (same file, established convention — no new file needed). |
| `core/data/.../db/entity/RemotePlaylistSnapshotEntity.kt` | Add `partial: Boolean = false`, `expectedCount: Int? = null` columns. |
| `core/data/.../db/StashDatabase.kt` | Bump `version = 16`, add `MIGRATION_15_16`, register it. |
| `core/data/.../sync/workers/PlaylistFetchWorker.kt` | Inject `InnerTubeClient`. Wrap `fetchYouTubePlaylists` in begin/end session. Replace serial loops with `Semaphore(3) + coroutineScope { async { … } }`. Read paged-result fields, write to snapshot rows and diagnostics. |
| `data/ytmusic/src/test/.../YTMusicApiClientTest.kt` | Add pagination, retry, verification, and concurrency-cap tests. |
| `data/ytmusic/src/test/resources/fixtures/` | Add `liked_songs_page1.json`, `liked_songs_page2.json`, `playlist_long_page1.json`, `playlist_long_page2.json`. |
| `core/data/src/test/.../sync/workers/PlaylistFetchWorkerTest.kt` (new or existing) | Add partial-flow + concurrency-cap tests. |

## Risks & open questions

1. **Continuation token shape may vary across YT response versions.** Mitigated
   by capturing real fixtures before coding and by the dual-path `extractContinuationToken`
   helper. If a third shape appears, add it as another fallback.
2. **3 concurrent browse calls may still hit 429 for some users.** If observed,
   drop to 2 with a single-line const change.
3. **Header `trackCount` parsing varies by locale** (English-only regex).
   Acceptable since the app's locale is `en`/`US` (set in `buildContext`).
4. **`PagedTracks` is a return-type change** — every caller of the four
   public API methods must be updated in the same PR. Compile errors will
   guide this; no silent breakage possible.
5. **Auth cache TTL.** No TTL — cache lives only for the duration of one
   sync run (begin/end pair). 401 invalidates immediately. Safe.

## Out of scope (explicitly)

- `getHomeMixes()` is unchanged — see Layer 2 note above.
- Spotify pagination audit.
- Spotify→YT matching improvements.
- DiffWorker changes.
- Download queue changes.
- New UI for partial-sync indication (data lands, visual indicator deferred).
- `Paginator` generic abstraction. Not warranted for 3 callsites.
