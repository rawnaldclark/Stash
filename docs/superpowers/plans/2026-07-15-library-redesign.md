# Library Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Rebuild the Library tab into a listen-first surface — a collapsing Shuffle hero + Recently-downloaded rail over sticky category chips (Songs/Playlists/Artists/Albums), with search behind an icon and sort+source/FLAC filter behind one sheet.

**Architecture:** A presentation-layer rework of `LibraryScreen`. The data already exists in `LibraryViewModel` (`tracks`, `recentlyAdded`, `playlists`, `stashMixes`, `likedPlaylists`, `artists`, `albums`, `shuffleLibrary()`, sort/filter/search all applied). Replace the non-scrolling `Column` + nested `LazyColumn` with ONE `LazyColumn` using `stickyHeader` for the chips; reuse Home's `CrispChipRow` + existing cell renderers; add three small components and two small ViewModel bits.

**Tech Stack:** Kotlin, Jetpack Compose (Material3, `LazyColumn` + `stickyHeader`), Hilt, Coroutines/Flow, JUnit4 + mockito-kotlin (the module's existing harness).

**Spec:** `docs/superpowers/specs/2026-07-15-library-redesign-design.md`. User-approved defaults: Import → **+** in header; keep the Downloads card; recent-rail tap **plays the track**; rail length **~12**.

---

## File Structure

**New:**
- `core/ui/src/main/kotlin/com/stash/core/ui/components/ShuffleHeroCard.kt` — violet-gradient hero (count + "all offline" + play FAB). Sibling of `DiscoverHeroCard`.
- `feature/library/src/main/kotlin/com/stash/feature/library/LibrarySortFilterSheet.kt` — the one ⇅ sheet (sort + source/FLAC).
- Tests alongside.

**Modified:**
- `feature/library/.../LibraryUiState.kt` — `SortOrder.DURATION`; `librarySongCount: Int`.
- `feature/library/.../LibraryViewModel.kt` — DURATION comparators; expose `librarySongCount` (total downloaded tracks, unfiltered).
- `feature/library/.../LibraryScreen.kt` — the restructure (the bulk of the work): unified `LazyColumn` + sticky chips + hero + rail + inline search + sheet + Import-as-`+`. Reuse the existing `TrackListItem` rows, `PlaylistsGrid`/`ArtistsGrid`/`AlbumsGrid` cell renderers (converted to items), multi-select, `LocalImportStrip`.

**Reused as-is:** `CrispChipRow`, `AlbumSquareCard` (core:ui); `TrackListItem`; the selection system; `LibraryMixesSection` (folds into the Playlists content).

---

### Task 1: ViewModel — DURATION sort + hero song count

**Files:** `LibraryUiState.kt`, `LibraryViewModel.kt`; Test: `feature/library/src/test/kotlin/com/stash/feature/library/LibraryViewModelSortTest.kt` (or extend the existing VM test).

- [ ] **Step 1: Failing tests**
  - Selecting `SortOrder.DURATION` sorts the Songs list by `durationMs` descending.
  - `uiState.librarySongCount` equals the total downloaded-track count regardless of the active source/search filter (it drives the hero, which is about the whole library).

- [ ] **Step 2: Run → fail** (`DURATION` / `librarySongCount` unresolved).

- [ ] **Step 3: Implement**
  - `LibraryUiState.kt`: add `DURATION` to `enum class SortOrder`; add `val librarySongCount: Int = 0`.
  - `LibraryViewModel.kt`: in the track sort `when` (around line 260) add `SortOrder.DURATION -> filteredTracks.sortedByDescending { it.durationMs }`. For playlists/artists/albums `when`s, add a `DURATION ->` branch that falls back to the existing RECENT ordering (Duration is a Songs-only concept; the sheet will only offer it for Songs — but the `when` must stay exhaustive). Populate `librarySongCount` from the unfiltered downloaded-track source (the same set `shuffleLibrary()` seeds from — a count query on `MusicRepository`, or `.size` of the pre-filter track list in the combine).

- [ ] **Step 4: Run → pass.**
- [ ] **Step 5: Commit** — `feat(library): DURATION sort option + librarySongCount for the hero`.

---

### Task 2: `ShuffleHeroCard` (core:ui)

**Files:** Create `core/ui/.../components/ShuffleHeroCard.kt`; Test: a compile/preview + a minimal render assertion if the module has Compose test infra (else preview-only).

- [ ] **Step 1:** Build the composable — signature `ShuffleHeroCard(songCount: Int, onShuffle: () -> Unit, modifier: Modifier = Modifier)`. Violet radial-gradient rounded card (mirror `DiscoverHeroCard`'s shell + `StashElevation`), eyebrow "SHUFFLE YOUR LIBRARY" (secondary/cyan), `songCount` + "songs" in Space Grotesk, sub "all offline · lossless where available", a white circular Play FAB → `onShuffle`. Whole-card click also shuffles.
- [ ] **Step 2:** Build `:core:ui:compileDebugKotlin` → SUCCESS.
- [ ] **Step 3: Commit** — `feat(ui): ShuffleHeroCard for the Library hero`.

---

### Task 3: `LibrarySortFilterSheet`

**Files:** Create `feature/library/.../LibrarySortFilterSheet.kt`.

- [ ] **Step 1:** A `ModalBottomSheet` content composable: **Sort by** (Recently added / A–Z / Most played / Duration → maps to `SortOrder`; show Duration only when the active chip is Songs) and **Show only** (All / YouTube / Spotify / FLAC → `SourceFilter`, as `CrispChipRow` or FilterChips with FLAC in the cyan accent). Params: current `SortOrder`, `SourceFilter`, `showDuration: Boolean`, `onSortSelected`, `onFilterSelected`, `onDismiss`. Reuse the existing `onSortOrderChanged` / `onSourceFilterChanged` VM callbacks.
- [ ] **Step 2:** Build `:feature:library:compileDebugKotlin` → SUCCESS.
- [ ] **Step 3: Commit** — `feat(library): sort & filter bottom sheet (absorbs the two chip rows)`.

---

### Task 4: Restructure `LibraryScreen` — unified LazyColumn + sticky chips

**This is the core task. Read `LibraryScreen.kt` fully first.** The current `LibraryContent` (line ~317) is a `Column` with six pinned children then a per-tab body. Rebuild it as ONE `LazyColumn`.

- [ ] **Step 1: Header row** — keep "Library" title; replace the `FilledTonalButton "Import"` with a compact **+** icon button, and add two icon buttons: 🔍 (toggles inline search) and ⇅ (opens `LibrarySortFilterSheet`). Keep `LocalImportStrip` directly under the header (outside the LazyColumn or as its first item).

- [ ] **Step 2: Inline search** — a `remember` boolean `searchOpen`; when true, render a search `TextField` (reuse `GlassSearchBar`'s field styling) below the header, bound to `state.searchQuery` / `onSearchQueryChanged`; 🔍 toggles it. Remove the always-on `GlassSearchBar`.

- [ ] **Step 3: The LazyColumn** — replace the `Column` body with (hoist a `val listState = rememberLazyListState()` so the sticky bar can detect "stuck"; `stickyHeader` needs `@OptIn(ExperimentalFoundationApi::class)`):
```
LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
    item { ShuffleHeroCard(songCount = state.librarySongCount, onShuffle = onShuffleLibrary, Modifier.padding(...)) }
    if (state.recentlyAdded.isNotEmpty()) item { RecentlyDownloadedRail(state.recentlyAdded.take(12), onTrackClick) }
    stickyHeader { CategoryChipBar(active = state.activeTab, onSelect = onTabSelected, stuck = <first-visible-index past the rail>, onShuffle, onSearchToggle, onSortFilter) }
    when (state.activeTab) {
        TRACKS   -> items(state.tracks, key = { it.id }) { TrackListItem(...) + multi-select wiring }
        ARTISTS  -> items(state.artists) { ArtistRow(...) }          // round art list
        PLAYLISTS-> items(playlistCards.chunked(2)) { Row { it.forEach { PlaylistCard(...) } } }
        ALBUMS   -> items(state.albums.chunked(2)) { Row { it.forEach { AlbumSquareCard(...) } } }
    }
}
```
  - **Reuse the existing cell renderers** from `PlaylistsGrid`/`ArtistsGrid`/`AlbumsGrid`/`TracksTab` — extract each cell into a small `@Composable` (e.g. `PlaylistCard`, `AlbumCard`, `ArtistRow`, keep the existing `TrackListItem` usage) and emit them inside the `LazyColumn` items. Do NOT rewrite their look — lift them.
  - **`RecentlyDownloadedRail`** = a `LazyRow` of `AlbumSquareCard` (art + title + artist, FLAC badge via `isLossless`), tap → `onTrackClick(track)`. Define it in this file (small).
  - **Playlists content is the biggest lift** — today it spans `PlaylistsGrid` (line ~878) *and* `LibraryMixesSection.kt` (825 lines: Liked Songs card, mix cards, action bottom-sheets, building/empty states). Do NOT rewrite these. Emit them into the LazyColumn as a mix of **full-span items** (section labels, the Liked Songs card, the Downloads collection card) and **chunked-2 card rows** (Stash Mixes, custom playlists) by lifting the existing card composables verbatim. Keep every existing action (play/queue/remove/delete/image, mix edit/refresh/delete) and its bottom sheet. If lifting the mixes section into the unified list proves too tangled in one pass, the acceptable fallback is: only the **Songs** chip uses the collapsing LazyColumn, and Playlists/Albums/Artists render their existing grids/list under a non-sticky copy of the hero-less header — but attempt the unified version first (it's what the mockup shows).

- [ ] **Step 4: Preserve multi-select** — the Songs `items` keep the exact `combinedClickable` + `SelectionState` wiring from today's `TracksTab`; the `SelectionScaffoldOverlay` stays in `LibraryScreen`'s root Box; selection still clears on chip change.

- [ ] **Step 5: Delete the dead chrome** — remove `ShuffleLibraryCard`, `TabChipRow`, `SortChipRow`, `SourceFilterChipRow`, and the always-on `GlassSearchBar` call (keep `GlassSearchBar`'s field for the inline search, or inline its styling). Wire ⇅ to the sheet, `+` to the SAF import picker (existing `importPicker`).

- [ ] **Step 6: Build** `:app:compileDebugKotlin` → SUCCESS.
- [ ] **Step 7: Commit** — `feat(library): listen-first surface — collapsing hero + sticky chips + recent-downloads rail`.

---

### Task 5: Sticky-bar shuffle affordance

- [ ] **Step 1:** In `CategoryChipBar`, when `stuck` (derive from `rememberLazyListState().firstVisibleItemIndex` past the hero+rail items), show a compact **shuffle** icon + 🔍 + ⇅ inline with the chips, so shuffle/search/sort stay reachable once the hero has scrolled away. When not stuck, just the chips (icons live in the header).
- [ ] **Step 2: Build** → SUCCESS.
- [ ] **Step 3: Commit** — `feat(library): keep shuffle/search/sort reachable in the stuck chip bar`.

---

### Task 6: Full build, tests, device smoke

- [ ] **Step 1:** `:app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 2:** Targeted unit runs — `:feature:library` VM tests with `--tests` filters (don't gate on pre-existing flaky suites; `--stop` + retry on the BindException).
- [ ] **Step 3:** `:app:installDebug`. **Daily phone — check `topResumedActivity` is idle/`com.stash.app.debug` before screenshotting; restart adb (`kill-server;start-server`) if the device drops.**
- [ ] **Step 4: Smoke checklist:** hero shuffles; recent-downloads rail shows + taps play; scroll → hero/rail leave, chips stick, stuck bar shows shuffle/search/sort; each chip renders (Songs list w/ multi-select, Playlists grid incl. Liked/Downloads/Mixes, Artists round-art list, Albums grid); 🔍 inline search filters; ⇅ sheet sorts + filters (incl. FLAC-only); **+** imports; `LocalImportStrip` still shows during import.

---

## Review & Finish

After all tasks: final review over the diff, then superpowers:finishing-a-development-branch. Branch `feat/library-redesign` (spec already committed there). This is Library only — **Sync redesign is the next, separate project.**
