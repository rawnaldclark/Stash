# Home → Library Mix Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move all "library" content (Stash/daily mixes, custom mixes, Liked Songs, playlists, recently-added) and its management logic **into** the Library screen, so the Home rewrite (Plan 3) can drop it and become a pure discovery surface — with each mix appearing exactly once (dedup).

**Architecture:** Port the mix-management flows from `HomeViewModel` into `LibraryViewModel` (which already owns most playlist logic), reworking Library's `combine` into an intermediate-holder pattern (like Home's `musicDataFlow`) to fit `recipeDao` + `discoveryQueueDao` within Kotlin's 5-arg `combine` limit. Add a **Mixes** grouping to Library and filter its existing Playlists tab to user (`CUSTOM`) playlists so nothing shows twice. Mix *generation* (recipes/workers in `core:data`) is untouched — this is a UI+ViewModel port only.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Coroutines/Flow, WorkManager, Room DAOs. Tests: JUnit + Truth + `kotlinx-coroutines-test` + **mockito-kotlin** — all on `:feature:library`'s test classpath. NOTE: `:feature:library` has **no turbine and no mockk** — collect flows with `.first()`/`runTest`, mock with `org.mockito.kotlin` (do not reference turbine/mockk).

**Spec:** `docs/superpowers/specs/2026-07-13-design-language-and-home-redesign.md` §7 (Library port), plus the code-level port map established during planning.

**Sequencing:** This is **Plan 2** of the Home sub-project (Plan 1 = design-language foundation, done). **Plan 3 = Home rewrite** removes the now-relocated content from Home and builds the discovery surface. This plan and Plan 3 land on the same branch, in order; the brief window where mixes appear in BOTH Home and Library is never shipped (the branch isn't merged between them).

**Repo notes:** Gradle **daemon** (never `--no-daemon`; `./gradlew --stop` once + retry on the daemon if a transient `BindException` hits). Always `--tests` filter. `PlaylistType` = `{ DAILY_MIX, LIKED_SONGS, CUSTOM, STASH_MIX, DOWNLOADS_MIX }`.

---

## Key facts (verified against the code)

- **`LibraryViewModel` already owns** `deletePlaylist` (the `deletePlaylistWithCascade` path — Home uses the same), `removePlaylist`, `setPlaylistImage`/`removePlaylistImage`, `playPlaylist`, `addPlaylistToQueue`. These do **not** port.
- **`HomeViewModel` methods that DO port** (mix/recipe-specific, Library lacks them): `refreshMix` (513), `refreshMixIfStale` (600), `editRecipeId` (614), `deleteCustomMix` (584), `playAllMixes` (632), `previewPlaylistDelete` (444) + its `DeletePreview`/`lastCascadeSummary`, and `createPlaylist` (493 — Library has a batch create but no bare `createPlaylist(name)`; add it).
- **Constructor deps to add** to `LibraryViewModel`: `recipeDao: StashMixRecipeDao`, `discoveryQueueDao: DiscoveryQueueDao`, `downloadNetworkPreference: DownloadNetworkPreference`, `streamingPreference: StreamingPreference`, `@ApplicationContext context: Context` (WorkManager). (Home keeps tipJar/lossless/deep-link/metadata — those aren't mixes.)
- **`MixBuildState` + `mixBuildState()` ALREADY live in `core:data`** (`com.stash.core.data.mix.MixBuildState.kt`) — a shared file `:feature:library` already sees via its `core:data` dep. **Do NOT move or re-implement them** — just `import com.stash.core.data.mix.MixBuildState` + `import com.stash.core.data.mix.mixBuildState`. (This corrects an earlier draft that had a "move MixBuildState" task — deleted.)
- **The `buildingMixIds`/`emptyMixIds` computation** (Home `musicDataFlow`, lines 189–215, calling the shared `mixBuildState()`) ports with the combine rework.
- **Spotify/YouTube mix split:** Home exposes `spotifyMixes`/`youtubeMixes` as **separate** `HomeUiState` fields (that's what `playAllMixes` reads). To port `playAllMixes` verbatim, `LibraryUiState` must keep the **same split field names** and replicate Home's exact derivation of those two lists (grep how `HomeViewModel`'s top-level `uiState` combine populates `spotifyMixes`/`youtubeMixes` — port it faithfully). Do NOT merge them into one `dailyMixes` field (that would break the ported method).
- **Dedup:** Library's `uiState.playlists = getAllPlaylists()` includes ALL types today, and `LibraryTab.PLAYLISTS → PlaylistsGrid` renders them. After the port: **Mixes group** = `STASH_MIX`(minus the builtin Daily Discover `playlistId` from `recipeDao.getBuiltinPlaylistIds()`) + `DOWNLOADS_MIX` + `spotifyMixes`/`youtubeMixes` (`DAILY_MIX`) + Liked Songs; **Playlists tab** = `CUSTOM` only.
- **Combine limit:** Library's `uiState` already chains two `.combine`s off a 5-arg base. Adding recipe+discovery flows requires the **intermediate-holder** pattern (mirror Home's `musicDataFlow` `combine(getAllPlaylists, getRecentlyAdded, recipeDao.observeAll, discoveryQueueDao.observeNonFailedCountsByRecipe)`).

---

## File Structure

| File | Responsibility | Task |
| --- | --- | --- |
| `feature/library/.../LibraryUiState.kt` (modify) | add mix/liked/recently-added slices + build-state sets | 1 |
| `feature/library/.../LibraryViewModel.kt` (modify) | DAO injections, combine→holder rework, ported mix methods | 2,3,4 |
| `feature/library/.../LibraryMixesSection.kt` (new, from home cards) | Mixes-group UI (Daily/Stash/custom + Liked) | 5 |
| `feature/library/.../LibraryScreen.kt` (modify) | render Mixes section; filter Playlists tab to CUSTOM | 6 |
| `feature/library/src/test/.../LibraryViewModelMixTest.kt` (new) | state-assembly + dedup + build-state tests | 2,3,4 |

(No `MixBuildState.kt` in this module — it's a shared `core:data` import.)

---

## Task 1: Extend `LibraryUiState` with mix slices

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LibraryUiState.kt`
- Test: `feature/library/src/test/kotlin/com/stash/feature/library/LibraryUiStateTest.kt`

Add the fields Home exposed, so Library can render them. Additive (all defaulted).

- [ ] **Step 1: Write the failing test** (asserts the new fields exist + default empty)
```kotlin
package com.stash.feature.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LibraryUiStateTest {
    @Test fun `mix slices default empty`() {
        val s = LibraryUiState()
        assertThat(s.stashMixes).isEmpty()
        assertThat(s.spotifyMixes).isEmpty()
        assertThat(s.youtubeMixes).isEmpty()
        assertThat(s.likedPlaylists).isEmpty()
        assertThat(s.recentlyAdded).isEmpty()
        assertThat(s.buildingMixIds).isEmpty()
        assertThat(s.customMixPlaylistIds).isEmpty()
    }
}
```

- [ ] **Step 2: Run — fail** — `./gradlew :feature:library:testDebugUnitTest --tests "*LibraryUiStateTest*"` → FAIL (unresolved fields).

- [ ] **Step 3: Add fields** to `LibraryUiState` (after `playlists`):
```kotlin
    /** Recipe-generated Stash Mixes (STASH_MIX minus the builtin Daily Discover). */
    val stashMixes: List<Playlist> = emptyList(),
    /** Spotify daily/imported mixes (DAILY_MIX) — split kept so playAllMixes ports verbatim. */
    val spotifyMixes: List<Playlist> = emptyList(),
    /** YouTube daily/imported mixes (DAILY_MIX). */
    val youtubeMixes: List<Playlist> = emptyList(),
    /** Liked-songs playlists (LIKED_SONGS). */
    val likedPlaylists: List<Playlist> = emptyList(),
    /** Recently downloaded tracks. */
    val recentlyAdded: List<Track> = emptyList(),
    /** Custom-mix playlist ids (user-built recipes) — drives Edit/Delete menu rows. */
    val customMixPlaylistIds: Set<Long> = emptySet(),
    /** Custom-mix playlist ids still populating — card shows "Building…". */
    val buildingMixIds: Set<Long> = emptySet(),
    /** Custom-mix playlist ids whose discovery finished with no tracks. */
    val emptyMixIds: Set<Long> = emptySet(),
```

- [ ] **Step 4: Run — pass.** **Step 5: Commit** (`feat(library): LibraryUiState mix slices`).

---

## Task 2: `LibraryViewModel` — DAO injections + combine→holder rework

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LibraryViewModel.kt`
- Test: `feature/library/src/test/kotlin/com/stash/feature/library/LibraryViewModelMixTest.kt`

Add the constructor deps and fold `recipeDao.observeAll()` + `discoveryQueueDao.observeNonFailedCountsByRecipe()` into a `libraryMixDataFlow` holder (mirror Home's `musicDataFlow`, lines 182–216), then combine that holder into `uiState`. Compute `stashMixes`/`spotifyMixes`/`youtubeMixes`/`likedPlaylists` by `PlaylistType` (replicating Home's exact spotify/youtube split — grep `spotifyMixes`/`youtubeMixes` derivation in `HomeViewModel`), excluding the builtin Daily Discover `playlistId` from `stashMixes`. Import `MixBuildState`/`mixBuildState` from `com.stash.core.data.mix` (shared — no local copy).

> **Avoid double-observing `getAllPlaylists()`:** Library's base `uiState` combine already observes it. Rather than observe it again in the mix holder, derive the mix slices from the **same** `allPlaylists` the base combine already has (fold recipe/discovery into the existing snapshot via the holder pattern), so playlists are observed once.

- [ ] **Step 1: Write failing test** — construct `LibraryViewModel` with fakes (mock `MusicRepository`/`TokenManager`/`recipeDao`/`discoveryQueueDao` via `org.mockito.kotlin`; `runTest`; collect `uiState` with `.first()` — **no turbine, no mockk in this module**). Assert:
  - `stashMixes` contains STASH_MIX playlists **except** the builtin Daily Discover id (from `recipeDao.getBuiltinPlaylistIds()`),
  - `spotifyMixes`/`youtubeMixes` = the DAILY_MIX split (per Home's derivation), `likedPlaylists` = LIKED_SONGS,
  - `customMixPlaylistIds`/`buildingMixIds`/`emptyMixIds` computed as Home did.
  (Follow the existing `LibraryViewModel` test setup if one exists.)
- [ ] **Step 2: Run — fail** (ctor has no `recipeDao`).
- [ ] **Step 3: Implement** — add ctor params (`recipeDao`, `discoveryQueueDao`, `downloadNetworkPreference`, `streamingPreference`, `@ApplicationContext context`); add the `libraryMixDataFlow` combine holder computing the mix sets + build-state (port the `mixBuildState` loop from Home 200–215, calling the shared `mixBuildState()`); combine it into `uiState`; map its outputs into the new `LibraryUiState` fields. Exclude builtin id: `val builtinIds = recipeDao.getBuiltinPlaylistIds().toSet()` → `stashMixes = playlists.filter { it.type == STASH_MIX && it.id !in builtinIds }`.
- [ ] **Step 4: Run — pass** (new test **and** all pre-existing `LibraryViewModel`/Library tests).
- [ ] **Step 5: Commit** (`feat(library): recipe/discovery flows + mix slices in LibraryViewModel`).

---

## Task 3: Port the mix-management methods

**Files:**
- Modify: `feature/library/.../LibraryViewModel.kt`
- Test: extend `LibraryViewModelMixTest.kt`

Port verbatim (adjusting only the `_userMessages`/`viewModelScope` references to Library's): `refreshMix`, `refreshMixIfStale`, `deleteCustomMix`, `editRecipeId`, `playAllMixes` (reads `spotifyMixes`/`youtubeMixes` from Library's uiState — the split fields kept in Task 1 so this ports unchanged), `previewPlaylistDelete` + `DeletePreview` + `lastCascadeSummary`, and a bare `createPlaylist(name)`. Copy the exact bodies from `HomeViewModel` (lines cited in Key facts) — they're self-contained. `refreshMix` brings the WorkManager unique-work observation (needs `context` + the `StashMixRefreshWorker`/`StashDiscoveryWorker` imports Home uses).

- [ ] **Step 1: Write failing tests** for the pure-ish ones: `previewPlaylistDelete` returns correct `willDelete` given a fake repo; `editRecipeId` invokes the callback with the recipe id from a fake `recipeDao`. (The WorkManager-driven `refreshMix` is integration-heavy — cover it by asserting it enqueues without throwing, or leave to device smoke; don't build a WorkManager test harness.)
- [ ] **Step 2–4:** fail → port the method bodies → pass.
- [ ] **Step 5: Commit** (`feat(library): port mix-management methods from Home`).

---

## Task 4: Dedup — filter the Playlists tab to CUSTOM

**Files:**
- Modify: `feature/library/.../LibraryViewModel.kt` (the `sortedPlaylists`/`playlists` mapping, ~line 146/163/199)
- Test: extend `LibraryViewModelMixTest.kt`

Today `uiState.playlists` = all playlist types. Change it to **`CUSTOM` only** so mixes/liked (now shown in the Mixes group) don't also appear in the Playlists tab.

- [ ] **Step 1: Write failing test** — given playlists of every type, `uiState.playlists` contains only `CUSTOM`; mixes/liked are excluded.
- [ ] **Step 2: Run — fail.**
- [ ] **Step 3: Implement** — filter the playlist mapping: `.filter { it.type == PlaylistType.CUSTOM }` before sort. (Keep search-filter behavior.)
- [ ] **Step 4: Run — pass** (+ existing tests). **Step 5: Commit** (`fix(library): Playlists tab shows user playlists only (mixes live in Mixes group)`).

---

## Task 5: `LibraryMixesSection` composable (from Home cards)

**Files:**
- Create: `feature/library/src/main/kotlin/com/stash/feature/library/LibraryMixesSection.kt`

Bring the mix/liked card UI over from Home so Library can render the Mixes group. Move (copy + adapt) `DailyMixCard`, `LikedSongsCard`, `MixesSectionHeader`, `CreateMixCard`, and the mix action `ModalBottomSheet` from `HomeScreen.kt` (grep those names for the bodies) into this file, wiring their callbacks to the Library ViewModel's ported methods. Compile-verified (no Compose UI-test harness). Keep the visual identical for now — the Premium Crisp reskin of Library is **sub-project 3**, out of scope here.

- [ ] **Step 1: Implement** — the section composable takes the mix slices + callbacks; renders the Mixes header + Daily/Stash/custom cards + Liked + Create-mix, plus the long-press action sheet (Refresh/Edit/Delete for custom mixes).
- [ ] **Step 2: Compile** — `./gradlew :feature:library:compileDebugKotlin` → SUCCESS.
- [ ] **Step 3: Commit** (`feat(library): LibraryMixesSection (ported mix cards)`).

---

## Task 6: Wire the Mixes section into `LibraryScreen`

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LibraryScreen.kt`

Render `LibraryMixesSection` at the top of the Library content (above the tabs, or as the PLAYLISTS-tab header — pick per the existing layout; read the screen first). Wire the ViewModel callbacks (`refreshMix`, `deleteCustomMix`, `editRecipeId` → MixBuilder nav, `playAllMixes`, `createPlaylist`, delete-preview dialog). The Playlists tab now shows only user playlists (Task 4).

- [ ] **Step 1: Implement** the wiring.
- [ ] **Step 2: Compile** — `./gradlew :feature:library:compileDebugKotlin` → SUCCESS.
- [ ] **Step 3: Device smoke (human/adb):** Library shows the Mixes group (Daily Discover **absent** — it's the future Home hero; Stash/custom mixes present, no dup in Playlists tab); refresh/edit/delete a custom mix works; Liked Songs opens; create-playlist works.
- [ ] **Step 4: Commit** (`feat(library): render Mixes group in Library`).

---

## Task 7: Full build + regression smoke

- [ ] **Step 1:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (Home still compiles — it still has its own copies until Plan 3 removes them; the port ADDS to Library, doesn't yet subtract from Home).
- [ ] **Step 2:** `./gradlew :feature:library:testDebugUnitTest --tests "*Library*"` → PASS (new + pre-existing).
- [ ] **Step 3: Device smoke:** Library fully functional with mixes; Home unchanged (still shows its mixes too — expected until Plan 3). No crashes, no duplicated playlists in Library's Playlists tab.
- [ ] **Step 4: Commit** any fixups.

---

## Out of scope (Plan 3 / later)

- **Removing** the mix content + methods **from** Home, the Home ViewModel/UiState reshape, the Discover hero, the discovery-first screen rewrite, edge-to-edge, nav callbacks + `SeeAllRoute` — **Plan 3 (Home rewrite)**.
- Premium Crisp **reskin of Library** (this port keeps Library's current look) — **sub-project 3**.
- The two album-recommendation rows + their artist→albums resolver spike, Qobuz playlists, charts — **Phase 2 data work**.
- Any change to mix *generation* (recipes/workers/`core:data`) — untouched by design.
