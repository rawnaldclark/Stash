# Search-Download Orphan Fix — Design Spec

**Date:** 2026-04-24
**Status:** Design
**Related bug:** Tracks downloaded from the Search tab (and Artist Profile) disappear from the app on next launch.

## Problem

`TrackActionsDelegate.handleDownloadSuccess` inserts a `TrackEntity` with `is_downloaded = true` but never links it to any playlist. On the next app launch, `StashApplication.onCreate` → `MusicRepository.runMigrations()` → `cleanOrphanedMixTracks()` executes the query:

```sql
WHERE is_downloaded = 1
  AND source != 'BOTH'
  AND id NOT IN (SELECT track_id FROM playlist_tracks WHERE removed_at IS NULL)
```

The track matches — no playlist membership, `source = YOUTUBE`, downloaded — so the sweeper deletes both the DB row and the audio file. The query was designed to clean up tracks stranded by daily-mix refresh cycles; it makes no distinction between "stranded by refresh" and "never had a playlist."

Reproducing: search a track, tap download, force-close the app, reopen. Track and file are gone.

## Goals

- Search / Artist-Profile downloads survive app restarts.
- Users have a visible, browsable home for one-off downloads.
- Fix is tight in scope — no schema migration, no backfill, no net-new UI concepts.

## Non-goals

- Rescuing tracks already deleted by prior orphan sweeps (their audio files are gone).
- Supporting a separate "Saved" surface distinct from the Mixes card — one destination is enough.
- Handling Spotify-side or SAF-imported downloads differently — those already use `source = BOTH` and are excluded from the sweep.
- Changing the orphan-cleanup query or its semantics.

## Design

### Data model

Add a new enum value to `core/model/Playlist.kt`:

```kotlin
enum class PlaylistType {
    DAILY_MIX, LIKED_SONGS, CUSTOM, STASH_MIX,
    DOWNLOADS_MIX,  // protected system-owned playlist holding manual downloads
}
```

No schema migration required — `Converters.toPlaylistType` stores this as TEXT via `PlaylistType.valueOf(it)`. A downgrade that encounters this value would throw, but the project does not ship downgrades.

### Seeding

One `DOWNLOADS_MIX` playlist exists per install. Seeded by a new idempotent call from `StashApplication.onCreate()` alongside the existing `runMigrations()`:

- Playlist fields:
  - `name = "Your Downloads"`
  - `source = MusicSource.BOTH`
  - `sourceId = "stash_downloads_mix"` — reserved, stable
  - `type = PlaylistType.DOWNLOADS_MIX`
  - `syncEnabled = false`
  - `dateAdded = Instant.now()`
  - `artUrl = null` (cover computed from member tracks, mosaic-style like Daily Mix)
- Implementation: query by `(type = DOWNLOADS_MIX, sourceId = "stash_downloads_mix")`; insert if absent. Safe on every startup.

### Download-path wiring

In `core/media/.../TrackActionsDelegate.kt:285-312`, after `musicRepository.insertTrack(track)`:

1. Resolve the seeded `DOWNLOADS_MIX` playlist's id.
2. Insert a `playlist_tracks` row linking the new track to it.

Preferred surface: a new method on `MusicRepository`, e.g. `linkTrackToDownloadsMix(trackId: Long)`. Encapsulates the lookup + insert in the data layer so the delegate stays focused on download orchestration.

Idempotency: if the link already exists (re-download of an already-downloaded track is guarded earlier in `downloadTrack`, but defense in depth) use `INSERT OR IGNORE` on the `(playlist_id, track_id)` pair.

Error handling: if linking fails after insert succeeds, log and swallow. The track is already in the library; the next successful download will re-run the seeder idempotency check. This preserves user data even under partial-failure scenarios.

### Orphan-cleanup interaction

No change to `MusicRepositoryImpl.cleanOrphanedMixTracks` or `TrackDao.getOrphanedDownloadedTracks`. Once linked, tracks naturally fall out of the orphan set via the existing `NOT IN (SELECT track_id FROM playlist_tracks WHERE removed_at IS NULL)` clause. Zero risk of accidentally deleting linked tracks.

### UI surfacing

**Single surface: Home → "Stash Mixes (beta)" section.**

The existing filter that populates the Stash Mixes section must widen from `PlaylistType.STASH_MIX` to `in setOf(STASH_MIX, DOWNLOADS_MIX)`. The Stash Mix engine ignores `DOWNLOADS_MIX` (the engine only operates on `STASH_MIX`), so rotation never clears our links.

Tapping the card opens the standard `PlaylistDetailScreen`, which already handles playlist display. `PlaylistDetailScreen` gates rename/delete UI behind `type == CUSTOM`, so `DOWNLOADS_MIX` inherits protection automatically.

**Not surfaced elsewhere:**

- Not in Library's PlaylistsGrid.
- Not in Home's Playlists section.
- Not in the Sync screen (its filters explicitly allow only playlists where `syncEnabled` can be toggled — `DOWNLOADS_MIX` has `syncEnabled = false` and should be excluded by an additional `type != DOWNLOADS_MIX` guard if current filters would otherwise include it).

**Recently Added on Home** is driven by `tracks.date_added` and does not filter by playlist type. Downloaded tracks surface there naturally on download.

### Cover art

Reuse the Daily Mix mosaic pattern (2-tile mosaic from recent track covers). Whatever code path populates `artTileUrls` for `DAILY_MIX` extends to `DOWNLOADS_MIX`. If that logic is gated by a `when (type)` branch, add `DOWNLOADS_MIX` to the mosaic-generating branch.

### Protection rules

- **Cannot delete the card itself.** Existing `PlaylistDetailScreen` rename/delete gate (`type == CUSTOM`) already enforces this. Verification during implementation: grep for any `deletePlaylist` API that lacks a `type` guard.
- **"Remove from playlist" on a member track performs a full track delete** (audio file + DB row + art). Rationale: `DOWNLOADS_MIX` is the track's only playlist membership; removing the link would orphan the track and the next launch's sweep would delete it anyway. Collapsing both actions into "delete" is honest about the outcome.

Implementation: in the `PlaylistDetailScreen` long-press menu, when `playlist.type == DOWNLOADS_MIX`, the "Remove from playlist" action invokes the full-track-delete path instead of just unlinking. Label can stay "Remove from playlist" or be renamed to "Delete from library" — single call-site change.

### Scope excluded

- No backfill of existing orphans. Prior orphan sweeps have already deleted their audio files; the DB rows are gone too. Nothing to recover.
- No source-filter exception on Library — `DOWNLOADS_MIX` isn't shown on Library.
- No new Home playlist card. No new Library row.
- No changes to the orphan-cleanup query itself.

## Touch points

| File | Change |
|------|--------|
| `core/model/src/main/kotlin/com/stash/core/model/Playlist.kt` | Add `DOWNLOADS_MIX` to `PlaylistType` |
| `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt` | Add `linkTrackToDownloadsMix(trackId: Long)` + seeder method |
| `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt` | Implement repo additions; call seeder from startup |
| `app/src/main/kotlin/com/stash/app/StashApplication.kt` | Invoke seeder in `onCreate` |
| `core/media/src/main/kotlin/com/stash/core/media/actions/TrackActionsDelegate.kt:285-312` | Call `linkTrackToDownloadsMix` after `insertTrack` |
| `feature/home/.../HomeViewModel.kt` | Widen Stash Mixes section filter to include `DOWNLOADS_MIX` |
| `feature/library/.../PlaylistDetailScreen.kt` | "Remove from playlist" → full delete when `type == DOWNLOADS_MIX` |
| Mosaic-art generator (TBD during implementation) | Include `DOWNLOADS_MIX` in mosaic-tile computation |
| `feature/sync/.../SyncScreen.kt` | Verify `DOWNLOADS_MIX` is excluded from sync-toggle filters |

Estimated size: small. ~7 files, no schema migration, one new enum value, one new repo method.

## Testing

**Unit tests:**
- Repo: seeder is idempotent (running twice leaves exactly one `DOWNLOADS_MIX` row).
- Repo: `linkTrackToDownloadsMix` is idempotent (double-linking same track doesn't fail or duplicate).
- `TrackActionsDelegate`: after `handleDownloadSuccess`, the track has exactly one `playlist_tracks` row pointing to the `DOWNLOADS_MIX` playlist.

**Integration test:**
- Seed DB with a search-downloaded track. Invoke `cleanOrphanedMixTracks()`. Assert track survives.

**Manual acceptance:**
1. Fresh install → "Your Downloads" card appears in Home → Stash Mixes section, empty.
2. Search a track, tap download. Card cover updates to mosaic; card becomes tappable to reveal the track.
3. Force-close app, reopen. Card still present, track still present, audio file still on disk.
4. Tap track → plays. Long-press → "Remove from playlist" → track + file are gone, card remains.
5. Download from Artist Profile → appears in the same card (shared delegate).
6. Clear cache via Android system settings, reopen app. Card + track + file all survive (DB is in `getDatabasePath`, not cache).

## Risks and edge cases

- **Track already in another playlist before download** — Impossible on Search/Artist-Profile downloads since the delegate inserts a brand-new `Track` row each time. No collision.
- **Mix engine touching `DOWNLOADS_MIX`** — Must verify the `StashMixRefreshWorker` filters its queries on `type == STASH_MIX`. If it filters on something broader, tighten it.
- **User deletes the "Your Downloads" card via a code path we miss** — Mitigation: audit all `deletePlaylist` / `DELETE FROM playlists` call sites for `type` guards. Add a repo-level guard (`check(type != DOWNLOADS_MIX)`) as defense-in-depth.
- **Orphaned tracks from prior installs (pre-fix)** — Their files are already deleted; nothing to recover. If a user still has the DB row but not the file, `cleanOrphanedMixTracks` will still delete the row (no file to delete, just the row), which is fine.
- **`source = BOTH` on the playlist entity** — The orphan sweep's `source != 'BOTH'` filter is on the **track**, not the playlist. Tracks in `DOWNLOADS_MIX` keep `source = YOUTUBE` (their actual origin), so the sweep's filter still applies to them individually — but the NOT IN clause prevents deletion via membership. No interaction.

## Open questions (non-blocking)

- Does the Home "Stash Mixes (beta)" section accommodate a second card visually? Needs a quick check during implementation; if layout is single-card, widen to a 2-card horizontal row.
- Exact labeling of the "Remove from playlist" action for `DOWNLOADS_MIX` rows — "Remove from playlist" (honest but misleading) vs "Delete from library" (clearer). Minor copy decision, defer to implementation.
