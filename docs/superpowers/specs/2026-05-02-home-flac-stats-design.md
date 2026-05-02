# Home Sync Card — FLAC Count + Storage Display Design

**Date:** 2026-05-02
**Status:** Design
**Branch:** `feat/squid-webview-captcha` (extends the in-progress lossless work)

## Problem

The Home screen's Sync Status card (`HomeScreen.kt:608-628`) shows four `StatItem`s in a single Row: **Tracks · Spotify · YouTube · Storage**. Once a user has been downloading lossless FLACs for a while, the storage number can get large (the user reported 21.3 GB) and they have no way to see how much of that is FLAC, or how many of their tracks are FLAC. With FLACs being ~10× larger than Opus, the question "what fraction of my storage is lossless?" becomes a real concern.

## Goals

- Surface FLAC track count and FLAC storage on the existing sync card without adding new layout sections
- Display only when the user has at least one FLAC track (so non-lossless users see exactly today's UI)
- Numbers must be accurate — sourced from the same `tracks.file_size_bytes` column already used for the existing storage total
- Reactive — updates automatically as FLAC tracks are downloaded or deleted, no manual refresh

## Non-goals

- One-time fixup pass to retro-detect FLAC among tracks defaulted to `file_format = 'opus'` (separate concern; documented as a known limitation below)
- Per-source FLAC breakdown (Spotify FLAC vs. YouTube FLAC) — out of scope; the lossless pipeline doesn't currently distinguish source for FLAC outputs
- A "library health" / format-distribution screen
- Changing how `file_format` is recorded for new downloads — the existing `TrackDao.kt:297` `setFormatAndQuality` mechanism already handles this correctly for FLAC

## Design

### 1. Architecture overview — three-layer change

```
Room layer    →  TrackDao.getFlacCount() + getFlacStorageBytes()  (NEW Flow queries)
Repository    →  MusicRepository.getFlacTrackCount() + getFlacStorageBytes()  (NEW interface methods)
ViewModel     →  HomeViewModel.sourceCountsFlow expanded from Pair to SourceCounts(spotify, youtube, flac, flacBytes)
                 propagates into HomeUiState.syncStatus.flacTracks / flacStorageBytes
UI            →  StatItem composable accepts optional subValue: String? = null
                 Tracks   StatItem renders "${flacTracks} FLAC" subValue when > 0
                 Storage  StatItem renders "${formatBytes(flacStorageBytes)} FLAC" subValue when > 0
```

### 2. SQL queries (Room)

Both queries mirror the patterns already used at `TrackDao.kt:444` (count) and `TrackDao.kt:448` (sum), adding only a `file_format = 'flac'` filter. Add to `TrackDao.kt`:

```kotlin
/** Count of downloaded FLAC tracks (reactive). */
@Query("SELECT COUNT(*) FROM tracks WHERE is_downloaded = 1 AND file_format = 'flac'")
fun getFlacCount(): Flow<Int>

/** Sum of file sizes (bytes) for downloaded FLAC tracks (reactive). */
@Query("SELECT COALESCE(SUM(file_size_bytes), 0) FROM tracks WHERE is_downloaded = 1 AND file_format = 'flac'")
fun getFlacStorageBytes(): Flow<Long>
```

The exact-string match against `'flac'` matches the existing convention used for the legacy-format fixup query at `TrackDao.kt:357`. The `is_downloaded = 1` filter matches every other aggregate in the file (queue/pending tracks aren't counted in the user-visible totals).

### 3. Repository surface

`MusicRepository.kt` (interface) and `MusicRepositoryImpl.kt` each gain two methods that pass through to the DAO:

```kotlin
// In MusicRepository (interface)
fun getFlacTrackCount(): Flow<Int>
fun getFlacStorageBytes(): Flow<Long>

// In MusicRepositoryImpl
override fun getFlacTrackCount(): Flow<Int> = trackDao.getFlacCount()
override fun getFlacStorageBytes(): Flow<Long> = trackDao.getFlacStorageBytes()
```

These mirror the existing `getTotalStorageBytes()` shape at `MusicRepository.kt:74` and its impl.

### 4. ViewModel — bundling into `sourceCountsFlow`

`HomeViewModel.kt:81-84` currently has:

```kotlin
private val sourceCountsFlow = combine(
    musicRepository.getSpotifyDownloadedCount(),
    musicRepository.getYouTubeDownloadedCount(),
) { spotify, youtube -> Pair(spotify, youtube) }
```

The top-level `combine(...)` at line 134 takes 5 inputs and the existing comment at `HomeViewModel.kt:69-71` warns: *"keeping the top-level combine at 5 or fewer flows for type safety."* So adding the two FLAC flows directly to the top-level combine would push it to 6 (breaks the typed overload).

**Solution:** expand `sourceCountsFlow` to combine 4 flows, replacing the `Pair` with a new tiny data class `SourceCounts`:

```kotlin
private data class SourceCounts(
    val spotify: Int,
    val youtube: Int,
    val flac: Int,
    val flacBytes: Long,
)

private val sourceCountsFlow = combine(
    musicRepository.getSpotifyDownloadedCount(),
    musicRepository.getYouTubeDownloadedCount(),
    musicRepository.getFlacTrackCount(),
    musicRepository.getFlacStorageBytes(),
) { spotify, youtube, flac, flacBytes ->
    SourceCounts(spotify, youtube, flac, flacBytes)
}
```

Kotlin's typed `combine(flow, flow, flow, flow) { ... }` overload supports 4 inputs cleanly. The top-level uiState `combine` stays at 5 inputs — no other ViewModel changes needed.

In the top-level combine block (`HomeViewModel.kt:140-181`), update the `sourceCounts` consumer:

```kotlin
HomeUiState(
    syncStatus = syncStatus.copy(
        totalTracks = musicData.trackCount,
        spotifyTracks = sourceCounts.spotify,
        youTubeTracks = sourceCounts.youtube,
        totalPlaylists = musicData.playlists.size,
        storageUsedBytes = musicData.storageBytes,
        flacTracks = sourceCounts.flac,                  // NEW
        flacStorageBytes = sourceCounts.flacBytes,       // NEW
    ),
    ...
)
```

### 5. State

`HomeUiState.kt` — extend `SyncStatusInfo` (line 110-121) with two new fields, defaulting to 0:

```kotlin
data class SyncStatusInfo(
    val lastSyncTime: Long? = null,
    val nextSyncTime: Long? = null,
    val totalTracks: Int = 0,
    val spotifyTracks: Int = 0,
    val youTubeTracks: Int = 0,
    val totalPlaylists: Int = 0,
    val storageUsedBytes: Long = 0,
    val flacTracks: Int = 0,                  // NEW
    val flacStorageBytes: Long = 0,           // NEW
    val state: SyncState = SyncState.IDLE,
    val displayStatus: SyncDisplayStatus = SyncDisplayStatus.Idle,
)
```

### 6. UI — StatItem composable + sync card wiring

`HomeScreen.kt:693-706` — extend `StatItem` to accept an optional `subValue`:

```kotlin
@Composable
private fun StatItem(label: String, value: String, subValue: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (subValue != null) {
            Text(
                text = subValue,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
```

The sub-line uses `colorScheme.primary` so the FLAC subset visually links to the parent stat's `primary`-coloured value, while staying smaller (`labelSmall` matches the label below). This is intentional: the FLAC line is "more like a value than a label," because it's a quantity not a category.

`HomeScreen.kt:612-623` — wire the new `subValue` on Tracks + Storage:

```kotlin
StatItem(
    label = "Tracks",
    value = syncStatus.totalTracks.toString(),
    subValue = if (syncStatus.flacTracks > 0) "${syncStatus.flacTracks} FLAC" else null,
)
StatItem(
    label = "Spotify",
    value = syncStatus.spotifyTracks.toString(),
)
StatItem(
    label = "YouTube",
    value = syncStatus.youTubeTracks.toString(),
)
StatItem(
    label = "Storage",
    value = formatBytes(syncStatus.storageUsedBytes),
    subValue = if (syncStatus.flacStorageBytes > 0) "${formatBytes(syncStatus.flacStorageBytes)} FLAC" else null,
)
```

When `flacTracks` and/or `flacStorageBytes` are zero, no sub-line renders → the layout is byte-identical to today for non-lossless users.

### 7. Layout impact

The Row's vertical height grows by one line (`labelSmall` ≈ 14sp + tiny line spacing) but ONLY for users with FLAC tracks. The existing 4-column horizontal arrangement is unchanged. There's no asymmetry concern: only Tracks and Storage get sub-lines, but they're at columns 1 and 4 — bookending the row, which looks balanced. Spotify and YouTube columns just have empty space below their labels, which Compose handles naturally with the `Column(horizontalAlignment = ...)` layout.

## Accuracy contract

The user explicitly asked for accurate numbers. This design delivers:

- **Source of truth:** `tracks.file_size_bytes` is set from the actual filesystem write at download time (`TrackDownloadWorker.kt:244`, `LocalImportCoordinator.kt:181`, `TrackActionsDelegate.kt:304`). It's the real file size, not an estimate.
- **Format identification:** `tracks.file_format` is set to `'flac'` by `TrackDao.setFormatAndQuality(...)` (line 297) when the lossless pipeline produces a FLAC file. New FLAC downloads get the correct format.
- **Aggregate accuracy:** `SUM(file_size_bytes) WHERE file_format = 'flac' AND is_downloaded = 1` is exact relative to what the DB knows.
- **Reactivity:** the Room `Flow<Int>` / `Flow<Long>` re-emit on every relevant insert/update/delete, so the UI updates automatically without manual refresh.

### Known limitation (documented, not fixed by this spec)

Tracks downloaded **before** the format-tracking feature was wired default to `file_format = 'opus'` (see the legacy-fixup comment at `TrackDao.kt:345`). If any such track is actually a FLAC (e.g. imported via `LocalImportCoordinator` before format-detection landed), it will undercount in the FLAC stat — appearing as Opus instead. This is a pre-existing data quality issue that this feature surfaces but does not cause and does not fix.

**Mitigation paths (deferred):**

1. A migration / one-time scan that re-reads the file extension or FLAC magic bytes for tracks at the legacy default. Out of scope here; would need its own spec covering progress UI + cancellation + battery cost.
2. Manual fix: a "scan library" Settings action. Same as (1), just user-triggered.

For the user's stated concern ("must be accurate"): the displayed numbers are accurate to within the historical-data caveat above. If they're worried about a specific FLAC undercount, they can inspect their library by sorting by file size — anything > ~10 MB/min that's labeled non-FLAC is probably a misidentified import.

## Touch points

| File | Change |
|---|---|
| `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt` | +2 `@Query` methods |
| `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt` | +2 interface methods |
| `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt` | +2 one-line impls delegating to DAO |
| `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt` | Replace Pair with `SourceCounts` data class; expand `sourceCountsFlow` to combine 4 flows; add 2 fields to `syncStatus.copy(...)` block |
| `feature/home/src/main/kotlin/com/stash/feature/home/HomeUiState.kt` | +2 fields on `SyncStatusInfo` |
| `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt` | `StatItem` gets `subValue: String?`; wire on Tracks + Storage |

Net: 6 files, ~50 LOC added, 0 LOC removed.

## Testing

### Unit tests

**`TrackDao` tests** — add to wherever the existing DAO tests live (search `TrackDaoTest.kt` or use Robolectric / Room in-memory DB):

1. **`getFlacCount returns count of downloaded FLAC tracks only`** — insert: 3 FLAC downloaded, 2 FLAC not-downloaded, 4 Opus downloaded → expect 3.
2. **`getFlacStorageBytes sums file_size_bytes for downloaded FLAC tracks only`** — same fixture as above, with file sizes; expect sum of the 3 downloaded-FLAC sizes only.
3. **`getFlacCount returns 0 when DB has no FLAC tracks`** — sanity check.
4. **`getFlacStorageBytes returns 0 when DB has no FLAC tracks`** — verifies the `COALESCE(SUM(...), 0)` clause.

The repository-level methods are one-line passthroughs and don't need separate tests.

### Manual acceptance

1. **Pre-feature state (no FLAC tracks downloaded yet):** Open Home. The sync card shows 4 stat items as today, with no sub-text below any of them. Visual height matches the previous build exactly.
2. **After downloading at least one FLAC track:** sub-text appears under Tracks (`"X FLAC"`) and under Storage (`"X.X MB FLAC"` or `"X.X GB FLAC"` depending on size). Spotify + YouTube columns remain unchanged (no sub-text).
3. **Reactivity:** delete a FLAC track via the library; the FLAC count and storage decrement in real-time on the Home sync card without manual refresh.
4. **Zero-state:** if all FLAC tracks are deleted, the sub-text disappears entirely; layout returns to state (1).
5. **Format string check:** numbers match `formatBytes(syncStatus.storageUsedBytes)` formatting (e.g. `"8.5 GB"`, `"123 MB"`, etc.). Sub-line uses the same formatter.

## Risks & rollback

- **Risk:** the DAO query column name `file_format` is wrong. **Mitigation:** verified by reading `TrackDao.kt:357` which uses the same column name in production code. The unit tests will catch any name typo.
- **Risk:** the new `SourceCounts` data class breaks existing consumers. **Mitigation:** `sourceCountsFlow` is `private` to `HomeViewModel`; no external consumers.
- **Risk:** the sub-line's typography or color jars vs. the rest of the card. **Mitigation:** `labelSmall` + `colorScheme.primary` matches `EqualizerSection` and other existing primary-accent text. If the user feels it's too loud, we can switch to `onSurfaceVariant` in a follow-up.
- **Risk:** 0-byte FLAC tracks (e.g. download-pending row that sneaks past `is_downloaded = 1`) inflate the count. **Mitigation:** the DAO filter `is_downloaded = 1` already excludes pending rows; the sum is `COALESCE(SUM, 0)` so no NPE on empty result.
- **Rollback:** revert the single commit. The new DB queries become unused but cause no harm; UI returns to today's exact layout.

## Out of scope

- Retro-detect FLAC files among tracks at legacy `file_format = 'opus'` default
- Per-source FLAC breakdown
- A separate format-distribution / library-health screen
- Estimated FLAC storage extrapolated from track count when `file_size_bytes` is unset (not a real case — every downloaded track has the column populated)
- Changing the existing `formatBytes` helper or its precision rules
