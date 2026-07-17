# Calm Sync Sources — Implementation Plan (plan 2 of 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Sync tab's endless-scroll source cards into a compact stats dashboard, move playlist management to a dedicated full-screen search-first screen, and add a "Hide from Home" capability (a new persisted flag) so surfaced mixes can be hidden from the Home rails.

**Architecture:** Add a `hideFromHome` boolean to the playlist entity/domain (migration 33→34). Home reads it (one filter in the rail classifier) and writes it (a mix-card long-press "Hide from Home", now enabled on all four rails). The Sync source card drops its inline playlist list and shows numbers + mode + "Manage ›"; a new `ManagePlaylistsScreen` hosts the search-first list, reusing the existing toggle-row + searchable-list composables (widened to `internal`).

**Tech Stack:** Kotlin, Jetpack Compose, Room, Hilt, JUnit4 + Truth, Room `MigrationTestHelper`.

**Spec:** `docs/superpowers/specs/2026-07-17-home-mixes-and-calm-sync-sources-design.md` (§3). Depends on plan 1 (mixes-on-Home) being merged — plan 1's rails/action-sheet are what this extends.

**Branch:** continue on `feat/library-redesign`.

**Verified current state (from a code-extraction pass):** DB version is **exactly 33** (`MIGRATION_32_33` = `sync_history.streaming_mode`; plan 1 added no migration), so the next migration is **`MIGRATION_33_34`** → version 34. `exportSchema=true`, so a `34.json` must be committed. `MixRailCard.kt:63` already declares `onLongPress: (() -> Unit)? = null` wired to `combinedClickable.onLongClick` — enabling hide on streaming cards is call-site-only. The Home action sheet already pools all four rails when resolving `actionSheetMixId`, so a streaming mix id resolves; `isStashMix`/`isCustom` both evaluate false for a streaming mix, so only Open + a new Hide row render for it (correct by construction).

---

## File Structure

**Modify (data layer — Task 1):**
- `core/data/.../db/entity/PlaylistEntity.kt` — add `hide_from_home` column.
- `core/model/.../Playlist.kt` — add `hideFromHome: Boolean`.
- `core/data/.../mapper/PlaylistMapper.kt` — both directions.
- `core/data/.../db/dao/PlaylistDao.kt` — `setHideFromHome`.
- `core/data/.../db/StashDatabase.kt` — `MIGRATION_33_34`, `version = 34`.
- `core/data/.../di/DatabaseModule.kt` — register the migration.
- Commit generated `core/data/schemas/.../34.json`.

**Modify (Home — Task 2):** `feature/home/.../HomeViewModel.kt` (filter + inject `PlaylistDao` + `setHideFromHome` — no `MusicRepository` change), `feature/home/.../HomeScreen.kt` (Hide row + 3 streaming long-press wires).

**Modify (Sync VM — Task 3):** `feature/sync/.../SyncViewModel.kt` (hideFromHome on the ui models + mappers + `onToggleHideFromHome`).

**Modify (dashboard — Task 4):** `feature/sync/.../components/SourcePreferencesCard.kt` + `feature/sync/.../SyncScreen.kt` (card → dashboard + Manage row); `app/.../StashNavHost.kt` (route).

**Create (Task 5):** `feature/sync/.../ManagePlaylistsScreen.kt`. Modify `SyncScreen.kt` to widen `SpotifySyncToggleRow`/`SearchablePlaylistList` to `internal` (or lift to a shared file).

---

## Task 1: `hideFromHome` persisted flag (migration 33→34)

**Files:** `PlaylistEntity.kt`, `Playlist.kt`, `PlaylistMapper.kt`, `PlaylistDao.kt`, `StashDatabase.kt`, `DatabaseModule.kt`, `schemas/.../34.json`, migration test.

- [ ] **Step 1: Write the failing migration test** — mirror `MigrationV32V33Test.kt` EXACTLY (it uses plain JUnit `org.junit.Assert.*`, NOT Truth, and `@Config(manifest = Config.NONE, sdk = [33])`, `@RunWith(RobolectricTestRunner)`, 4-arg `MigrationTestHelper`):
```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

@Test
fun migrate33To34_addsHideFromHomeColumn_defaultsZero() {
    helper.createDatabase(TEST_DB, 33).apply {
        execSQL("INSERT INTO playlists (name, source, source_id, type, sync_enabled, date_added) " +
                "VALUES ('X', 'SPOTIFY', 's1', 'CUSTOM', 1, 0)")  // match the real v33 playlists cols
        close()
    }
    val db = helper.runMigrationsAndValidate(TEST_DB, 34, true, StashDatabase.MIGRATION_33_34)
    db.query("SELECT hide_from_home FROM playlists LIMIT 1").use { c ->
        assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
    }
}
```
> Read the real v33 `playlists` `createSql` in `schemas/.../33.json` and match the INSERT columns exactly (the sketch's list is illustrative). Copy `MigrationV32V33Test.kt`'s exact scaffold (helper construction, `TEST_DB` constant, imports).

- [ ] **Step 2: Run it, verify FAIL** — `./gradlew :core:data:testDebugUnitTest --tests "*MigrationV33V34*"` (no `MIGRATION_33_34`).

- [ ] **Step 3: Add the column** in `PlaylistEntity.kt` (after `dateAdded`, ~:73):
```kotlin
    @ColumnInfo(name = "hide_from_home", defaultValue = "0")
    val hideFromHome: Boolean = false,
```
Add `val hideFromHome: Boolean = false` to `Playlist.kt` (near `syncEnabled`). Add `hideFromHome = hideFromHome` to BOTH mapper directions in `PlaylistMapper.kt` (`toDomain` ~:31, `toEntity` ~:55).

- [ ] **Step 4: DAO** — in `PlaylistDao.kt` (~:303, mirror `updateSyncEnabled`):
```kotlin
    @Query("UPDATE playlists SET hide_from_home = :hidden WHERE id = :playlistId")
    suspend fun setHideFromHome(playlistId: Long, hidden: Boolean)
```

- [ ] **Step 5: Migration + version** — in `StashDatabase.kt` bump `version = 34` (~:82), add after `MIGRATION_32_33` (~:869):
```kotlin
        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN hide_from_home INTEGER NOT NULL DEFAULT 0")
            }
        }
```
Register `StashDatabase.MIGRATION_33_34` in `DatabaseModule.addMigrations(...)` (~:66).

- [ ] **Step 6: Run the test, verify PASS** — same command. Room regenerates `34.json` (exportSchema) — confirm `hide_from_home` present + `version:34`; commit it. (If the first test run FNFEs because `34.json` isn't generated yet, run once to generate then re-run — same as the 33.json note in plan A.)

- [ ] **Step 7: Commit** — `feat(sync): persist hideFromHome flag on playlists (migration 33→34)`. Stage explicit paths incl. the generated `34.json`. (`git commit -F -` heredoc via Bash; never `git add -A`.)

> Consistency gate: `@ColumnInfo(defaultValue="0")` + migration `DEFAULT 0` + Kotlin default `false` must all agree, or Room schema validation fails.

---

## Task 2: Home reads + writes hideFromHome

**Files:** `HomeViewModel.kt`, `HomeScreen.kt`.

- [ ] **Step 1: Read-filter** — in `HomeViewModel.kt` at the rail classifier loop (~:229), filter hidden mixes before classification:
```kotlin
    for (p in playlists.filter { !it.hideFromHome }) {
```
One filter covers all four rails (every playlist routes through this loop). (Compiles only after Task 1 adds `Playlist.hideFromHome`.)

- [ ] **Step 2: VM write** — `HomeViewModel` currently injects only `MusicRepository` (an interface), which has no playlist-write passthrough. **Inject `PlaylistDao` directly into `HomeViewModel`** (exactly as `SyncViewModel` does — `PlaylistDao` is Hilt-provided) and call the DAO from Task 1:
```kotlin
// constructor: add `private val playlistDao: com.stash.core.data.db.dao.PlaylistDao,`
    fun setHideFromHome(playlistId: Long, hidden: Boolean) {
        viewModelScope.launch { playlistDao.setHideFromHome(playlistId, hidden) }
    }
```
This is one file (no `MusicRepository` interface+impl plumbing) and matches Task 3's pattern.

- [ ] **Step 3: Hide row in the action sheet** — in `HomeScreen.kt` (~:552, before the `Open` `MixActionRow`), add an ungated row (icon `Icons.Filled.RemoveCircleOutline` — already imported ~:65). NOTE `MixActionRow`'s signature has a trailing non-function `tint` param, so `onClick` must be a **named arg** (match the existing call sites — no trailing lambda):
```kotlin
    MixActionRow(
        icon = Icons.Filled.RemoveCircleOutline,
        label = "Hide from Home",
        onClick = { viewModel.setHideFromHome(id, true); actionSheetMixId = null },
    )
```
Ungated so it applies to any rail's mix (streaming + Your mixes). (Confirm `MixActionRow`'s exact param names against its definition in HomeScreen.kt.)

- [ ] **Step 4: Enable long-press on the streaming rails** — in `HomeScreen.kt`, add `onLongPress = { actionSheetMixId = m.id }` to the `MixRailCard(...)` calls in the madeForYou (~:366), radios (~:380), and moodDecades (~:394) rails — identical to the yourMixes rail (~:412). No change to `MixRailCard.kt`.

- [ ] **Step 5: (Optional) test** — add a classifier-filter test if a HomeViewModel harness exists: a `hideFromHome=true` playlist is dropped from all four rails. Else rely on device Task 6. (The pure `mixRail` is already tested; the filter is a one-liner.)

- [ ] **Step 6: Compile** — `./gradlew :feature:home:compileDebugKotlin`.
- [ ] **Step 7: Commit** — `feat(home): hide mixes from Home (filter + long-press Hide action on all rails)`.

---

## Task 3: SyncViewModel hideFromHome plumbing

**Files:** `SyncViewModel.kt`.

- [ ] **Step 1:** Add `val hideFromHome: Boolean = false` to `SpotifySyncPlaylist` and `YouTubeSyncPlaylist` (~:68-90).
- [ ] **Step 2:** Add `hideFromHome = e.hideFromHome` to both entity→ui mappers (~:568-576 Spotify, ~:589-597 YouTube).
- [ ] **Step 3:** Add `fun onToggleHideFromHome(playlistId: Long, hidden: Boolean)` mirroring `onTogglePlaylistSync` (~:303) but **without** the `downloadQueueDao.cancelDownloadsWithNoEnabledPlaylist()` sweep (hiding is unrelated to downloads):
```kotlin
    fun onToggleHideFromHome(playlistId: Long, hidden: Boolean) {
        viewModelScope.launch { playlistDao.setHideFromHome(playlistId, hidden) }
    }
```
- [ ] **Step 4: Compile** — `./gradlew :feature:sync:compileDebugKotlin`.
- [ ] **Step 5: Commit** — `feat(sync): expose hideFromHome on sync playlist models + toggle`.

---

## Task 4: Source card → stats dashboard + "Manage ›"

**Files:** `SourcePreferencesCard.kt`, `SyncScreen.kt`, `StashNavHost.kt`.

The card currently expands to an inline playlist list (the maze). Make it a **compact dashboard** that never expands into a list; add a "Manage ›" row that navigates to `ManagePlaylistsScreen` (Task 5).

- [ ] **Step 1:** Rework `SourcePreferencesCard` (or its call sites) so the body is always the summary (statusPills + summaryLine + the mode chips) plus a **"Manage ›"** row — remove the `expanded`/`expandedContent` list behavior. Keep: the Connected pill (header), the `SummaryPills` (count roll-ups), the summary line, and the `SyncModeChipRow` (Refresh/Accumulate — relocate it from the old `*ExpandedContent` onto the card body). Drop: `SpotifyExpandedContent`/`YouTubeExpandedContent`'s inline Liked/Mixes/Your-Playlists list rendering (that logic moves to Task 5's screen). Consider promoting the source stats (mixes-auto count, synced/total, liked) into a small numbers row per the design (`spotifyPlaylists.count { it.type == DAILY_MIX }`, the existing enabled/total, liked count) — derive client-side, no VM change.
- [ ] **Step 2:** In `SyncScreen.kt`, update the Spotify (~:188-216) and YouTube (~:223-253) `item {}` blocks: drop the `expandedContent = { *ExpandedContent(...) }`; add `onManage = { onManageSource(SyncSource.SPOTIFY / YOUTUBE) }`. Keep the summary-line + SummaryPills. Delete the now-unused `SpotifyExpandedContent`/`YouTubeExpandedContent` (their reusable leaves — `SyncModeChipRow`, `StudioOnlyToggleRow`, `SearchablePlaylistList`, `SpotifySyncToggleRow` — are kept/reused; the mode chips move onto the card, the studio-only toggle moves to the Manage screen).
- [ ] **Step 3:** Thread `onManageSource` from `SyncScreen`'s params → `StashNavHost` → `navController.navigate(ManagePlaylistsRoute(source.name))` (route added in Task 5).
- [ ] **Step 4: Compile** — `:feature:sync` + `:app`.
- [ ] **Step 5: Commit** — `feat(sync): source cards become a compact dashboard with Manage ›`.

> Scope note: keep it lazy — do NOT try to also virtualize anything; the whole point is the list no longer lives on the landing. The Schedule / Recent-syncs sections below now sit right under two short cards.

---

## Task 5: `ManagePlaylistsScreen` (full-screen, search-first)

**Files:** Create `feature/sync/.../ManagePlaylistsScreen.kt`; modify `SyncScreen.kt` (widen leaves), `StashNavHost.kt`, `TopLevelDestination.kt` (route).

- [ ] **Step 1: Widen the reusable leaves.** In `SyncScreen.kt`, change `private fun SpotifySyncToggleRow(...)` (~:606) and `private fun SearchablePlaylistList(...)` (~:832) to `internal fun` so the new screen file (same module) can call them. (Or lift both into a `feature/sync/.../components/PlaylistToggleList.kt` — pick whichever is cleaner; `internal` is the smaller diff.)
- [ ] **Step 2: Route** — add `@Serializable data class ManagePlaylistsRoute(val source: String)` in `TopLevelDestination.kt` (mirror the other type-safe routes); add a `composable<ManagePlaylistsRoute>` in `StashNavHost.kt` that reads the source arg and shows the screen with `hiltViewModel<SyncViewModel>()` (scoped to the nav entry — its flows re-derive).
- [ ] **Step 3: The screen.** `ManagePlaylistsScreen(source: SyncSource, onBack, viewModel = hiltViewModel())`:
  - Scaffold + TopAppBar (back + "Spotify playlists" / "YouTube Music playlists").
  - A **pinned search field** at the top (reuse/adapt `SearchablePlaylistList`'s filter, but promote the field to cover the whole screen). A segment filter **All · Synced · Off**.
  - Sections from `uiState.<source>Playlists`:
    - **Liked** — a `SpotifySyncToggleRow` for the `LIKED_SONGS` item(s), wired to `onTogglePlaylistSync`.
    - **Mixes (auto)** — a summary header "N mixes · surfaced on Home" (`count { it.type == DAILY_MIX }`), then the `DAILY_MIX` items each with a **hide-from-Home** toggle (wired to `viewModel.onToggleHideFromHome(id, hidden)` — Task 3), so a mix can be hidden/unhidden here as well as via Home long-press. (No per-mix *sync* toggle — mixes auto-sync.)
    - **Your playlists (synced/total)** — a real lazy list (`LazyColumn` items) of the `CUSTOM` playlists as `SpotifySyncToggleRow` wired to `onTogglePlaylistSync`, with an **Enable all / none** action.
  - For YouTube, also surface the **Studio-only** toggle (moved from the old expanded content) in a small header.
- [ ] **Step 4: Compile** — `:feature:sync` + `:app`.
- [ ] **Step 5: Commit** — `feat(sync): dedicated search-first Manage playlists screen`.

> YAGNI: no fancy A-Z jump bars; the pinned search is the "jump" mechanism. Keep the mixes section a simple list with a hide toggle — the design's headline win is that the maze is gone from the landing.

---

## Task 6: Device verification

- [ ] **Step 1:** `./gradlew :app:installDebug`. (Screencap is black on this device — capture via `screenrecord --time-limit 1` + `ffmpeg -frames:v 1 -update 1`. Daily phone — guard taps on `dumpsys window mCurrentFocus` = `com.stash.app.debug`.)
- [ ] **Step 2: Migration** — launch (33→34 runs on the existing DB); confirm no crash, and existing playlists still load (hide_from_home defaults 0 = all visible).
- [ ] **Step 3: Sync dashboard** — the Spotify/YouTube cards are short (numbers + mode chips + Manage ›); no inline playlist list; Schedule + Recent Syncs are reachable without a long scroll.
- [ ] **Step 4: Manage screen** — tap "Manage ›" → the full-screen list opens; the pinned search filters; toggling a custom playlist's sync works; Enable all/none works; the mixes section shows the count + a hide toggle.
- [ ] **Step 5: Hide from Home** — long-press a mix card on Home (any rail) → "Hide from Home" → it disappears from the rail; confirm it's hidden (and unhide via the Manage screen mixes section restores it).
- [ ] **Step 6:** Report PASS/FAIL with captured frames (Sync dashboard + Manage screen + a Home rail before/after hide).

---

## Verification checklist (whole plan)

- [ ] `:core:data:testDebugUnitTest --tests "*MigrationV33V34*"` green; `34.json` committed.
- [ ] `:feature:home`, `:feature:sync`, `:app`, `:core:data` compile.
- [ ] Device: dashboard replaces the maze; Manage screen search-first; Hide from Home works both directions.
