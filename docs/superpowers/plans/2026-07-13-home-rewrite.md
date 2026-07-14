# Home Rewrite (Discovery-First) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite Home as a pure **discovery** surface in the Premium Crisp language — drop the library content (now in Library per Plan 2), lead with the Discover hero, add a cold-start personalize state, and make the frosted bottom nav read via edge-to-edge.

**Architecture:** Reshape `HomeUiState`/`HomeViewModel` to expose only discovery data + the cold-start signal (removing the mix/playlist/liked flows Plan 2 relocated). Rewrite `HomeScreen` to render the Plan-1 primitives (`DiscoverHeroCard`, and — when data lands later — `CrispChipRow`/`AlbumRow`). Home's `LazyColumn` consumes the bottom window inset so content scrolls behind the translucent nav (the edge-to-edge work deferred from Plan 1).

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Coroutines/Flow, Room. Tests: JUnit + Truth + coroutines-test + mockito-kotlin (all on `:feature:home`'s classpath — it has ViewModel tests today).

**Spec:** `docs/superpowers/specs/2026-07-13-design-language-and-home-redesign.md` §4/§5 (Home IA/states), §3.3 N3 (edge-to-edge), §6 (phasing — HERO-FIRST).

**⚠️ HARD PREREQUISITE: Plan 2 (Library mix port) must be merged/landed on this branch FIRST.** This plan *removes* the mix content + methods from Home; if Library hasn't received them yet, that content is lost from the UI. Do not start until Plan 2's tasks are all ✅.

**Phase-1 scope reality (HERO-FIRST — user decision):** the two album-recommendation rows, Qobuz playlists, and charts are **deferred** (no cheap wired data path — see spec §6). So Phase-1 Home renders: the **Discover hero** (or, cold-start, a **personalize card**) + Home's existing supporter/nudge chrome. Chips are **hidden in Phase 1** (the phase-aware rule: chips show only when >1 section type is populated; with one section, no chip row). The screen is deliberately lean — its value is the discovery-first IA, the Premium Crisp polish, and the edge-to-edge frosted nav. The `AlbumRow`/`CrispChipRow`/`RankedAlbumList` primitives from Plan 1 stay unused until Phase-2 data lands, then slot into the §5 section contract with no rework.

**Repo notes:** Gradle **daemon** (`--stop` once + retry on transient `BindException`; never `--no-daemon`). Always `--tests`. Debug app = `com.stash.app.debug`.

---

## Key facts (verified)

- **`HomeScreen`** signature today: `HomeScreen(modifier, onNavigateToPlaylist, onNavigateToLikedSongs, onNavigateToSettings, onNavigateToMixBuilder, viewModel)`. Wired in `StashNavHost` `composable<HomeRoute>` (lines 54–72).
- **Removed from Home** (Plan 2 relocated the logic to Library): `HomeUiState` fields `stashMixes`/`spotifyMixes`/`youtubeMixes`/`recentlyAdded`/`spotify+youtubeLikedPlaylists`/`liked counts`/`playlists`/`customMixPlaylistIds`/`building+emptyMixIds`/`playlistSortOrder`; `HomeViewModel` the `musicDataFlow` holder + `refreshMix`/`refreshMixIfStale`/`deleteCustomMix`/`editRecipeId`/`createPlaylist`/`previewPlaylistDelete`/`playAllMixes`/`playLikedSongs`/`queue+removeDownloadsForPlaylist`/`deletePlaylist`/`removePlaylist`; `HomeScreen` the mix/playlist/liked cards + sections + the action `ModalBottomSheet` + `MixesSectionHeader`.
- **Kept on Home** (NOT library content — these are Home chrome, out of scope to remove): the tip-jar pill (`tipJar`), lossless prompt (`losslessPrompt`), metadata-backfill banner (`metadataBackfillBanner`), streaming toggle + disclosure, `onNavigateToSettings`. Keep their `HomeViewModel` deps (`tipJarRepository`, `losslessPrefs`, `settingsDeepLinkController`, `streamingPreference`, `metadataBackfillState`).
- **Discover hero source:** the builtin Daily Discover playlist. `recipeDao.getBuiltinPlaylistIds()` → the (single) builtin playlist id → resolve the `Playlist` (name/trackCount/artUrl) from `musicRepository.getAllPlaylists()` (or a by-id read). Play = load its tracks (`getTracksByPlaylist(id)`) → `playerRepository.setQueue`. Hero is **omitted** when the builtin playlist has no tracks (not materialized yet); an omitted hero feeds the cold-start `<2 sections` floor (§5).
- **`:feature:home` deps** already include `core:data` (recipeDao/prefs), `core:media`. Keep `recipeDao` injected (for the hero); drop `discoveryQueueDao`/`downloadNetworkPreference` only if nothing else uses them after the mix methods leave (verify before removing).

---

## File Structure

| File | Responsibility | Task |
| --- | --- | --- |
| `feature/home/.../HomeUiState.kt` (rewrite) | discovery-only state: hero slice + cold-start flag + kept chrome | 1 |
| `feature/home/.../DiscoverHeroState.kt` (new) | hero UI model (title/subtitle/artUrl/playlistId or absent) | 1 |
| `feature/home/.../HomeViewModel.kt` (rewrite) | drop library flows; add hero source + cold-start; keep chrome + play-hero | 2 |
| `feature/home/.../HomeScreen.kt` (rewrite) | Premium Crisp discovery body + cold-start card + edge-to-edge | 3,4 |
| `feature/home/.../PersonalizeCard.kt` (new) | cold-start "connect / sync to personalize" card | 3 |
| `app/.../navigation/StashNavHost.kt` (modify) | trim Home nav callbacks to what remains | 5 |
| `feature/home/src/test/.../HomeViewModelTest.kt` (modify) | hero + cold-start state assembly | 2 |

---

## Task 1: Reshape `HomeUiState` + `DiscoverHeroState`

**Files:**
- Rewrite: `feature/home/src/main/kotlin/com/stash/feature/home/HomeUiState.kt`
- Create: `feature/home/src/main/kotlin/com/stash/feature/home/DiscoverHeroState.kt`
- Test: `feature/home/src/test/kotlin/com/stash/feature/home/HomeUiStateTest.kt`

Strip the relocated fields; keep the chrome fields; add the hero + cold-start signal.

- [ ] **Step 1: Write failing test**
```kotlin
package com.stash.feature.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomeUiStateTest {
    @Test fun `defaults are discovery-shaped`() {
        val s = HomeUiState()
        assertThat(s.hero).isNull()             // no hero until materialized
        assertThat(s.isColdStart).isTrue()      // nothing populated by default
        assertThat(s.isLoading).isTrue()
    }
    @Test fun `cold start is false once a hero exists`() {
        val s = HomeUiState(hero = DiscoverHeroState("Discover", "30 tracks", null, 7L), isLoading = false)
        assertThat(s.isColdStart).isFalse()
    }
}
```

- [ ] **Step 2: Run — fail** — `./gradlew :feature:home:testDebugUnitTest --tests "*HomeUiStateTest*"` → FAIL.

- [ ] **Step 3: Implement.** `DiscoverHeroState.kt`:
```kotlin
package com.stash.feature.home

/** The Discover hero (materialized Daily Discover playlist). Null when not yet available. */
data class DiscoverHeroState(
    val title: String,
    val subtitle: String,
    val artUrl: String?,
    val playlistId: Long,
)
```
Rewrite `HomeUiState` to keep ONLY: `hero: DiscoverHeroState? = null`, `isLoading: Boolean = true`, and the retained chrome (`losslessPrompt`, `tipJar`, `metadataBackfillBanner`). Add:
```kotlin
    /** Cold start: too few discovery sections populated to fill Home — show the personalize card (§5). */
    val isColdStart: Boolean get() = hero == null /* + future: && albumRows.isEmpty() && … */
```
Delete the relocated fields + their derived helpers (`totalLikedCount`, `hasAnyLikedSongs`, `singleLikedSource`, etc.) and `PlaylistSortOrder` (moved to Library in Plan 2 — if Library didn't take it, leave it; verify).

- [ ] **Step 4: Run — pass.** **Step 5: Commit** (`feat(home): discovery-shaped HomeUiState + DiscoverHeroState`).

---

## Task 2: Reshape `HomeViewModel` (drop library flows, add hero + cold-start)

**Files:**
- Rewrite: `feature/home/.../HomeViewModel.kt`
- Test: `feature/home/.../HomeViewModelTest.kt` (modify)

Remove the `musicDataFlow` holder and all relocated methods (listed in Key facts). Keep the chrome init (tip-jar warm-up, lossless, streaming toggle, metadata banner, deep-link). Add a **hero flow**: observe playlists + builtin ids → build `DiscoverHeroState` (or null); assemble `uiState` from hero + chrome. Add `playHero()` (load the builtin playlist's tracks → `setQueue`).

- [ ] **Step 1: Write failing test** — construct `HomeViewModel` with fakes; assert:
  - when the builtin Daily Discover playlist has tracks → `uiState.hero` is non-null with its title/count/art and `isColdStart == false`;
  - when it has no tracks (or absent) → `hero == null`, `isColdStart == true`.
  (Reuse the existing `HomeViewModelTest` fakes/mocks; drop assertions on the removed fields.)
- [ ] **Step 2: Run — fail.**
- [ ] **Step 3: Implement** — delete the relocated methods + `musicDataFlow`; add:
  - a hero flow: `combine(musicRepository.getAllPlaylists(), <builtin ids>)` → find the builtin playlist → map to `DiscoverHeroState` when `trackCount > 0`, else null. (`getBuiltinPlaylistIds()` is `suspend`; read it once into a flow via `flow { emit(...) }` or fold into an existing suspend init — keep it simple, re-read on refresh.)
  - `fun playHero()` → `viewModelScope.launch { val id = uiState.value.hero?.playlistId ?: return@launch; val tracks = musicRepository.getTracksByPlaylist(id).first(); if (tracks.isNotEmpty()) playerRepository.setQueue(tracks.filter { it.filePath != null || streamingOn }, 0) }` (mirror Home's old `playAllMixes` filtering).
  - keep the chrome flows/methods untouched.
  Remove now-unused ctor deps ONLY after grep-confirming nothing else references them (`discoveryQueueDao`, `downloadNetworkPreference` likely go; `recipeDao` STAYS for the hero).
- [ ] **Step 4: Run — pass** (new + surviving `HomeViewModel` tests; delete tests asserting removed behavior).
- [ ] **Step 5: Commit** (`feat(home): HomeViewModel discovery reshape — hero + cold-start, drop library flows`).

---

## Task 3: Rewrite `HomeScreen` (Premium Crisp discovery body + cold-start)

**Files:**
- Rewrite: `feature/home/.../HomeScreen.kt`
- Create: `feature/home/.../PersonalizeCard.kt`

Replace the mix/playlist body with: the retained top chrome (wordmark + Online chip + Settings + tip-jar pill + banners — keep these as-is), then **either** the `DiscoverHeroCard` (Plan 1, `core:ui`) when `hero != null`, **or** the `PersonalizeCard` when `isColdStart`. Loading → the hero's shimmer (`DiscoverHeroCard(loading = true)`). Trim `HomeScreen`'s signature to the surviving callbacks (`onNavigateToSettings`, plus `onNavigateToLikedSongs`/`onNavigateToPlaylist` only if the retained chrome still needs them — likely NOT; verify and drop).

- [ ] **Step 1: Implement `PersonalizeCard.kt`** — a `GlassCard`-style card: "Personalize your Home" + subtitle ("Connect Last.fm or run a sync so we can tailor discovery to you") + a button routing to Settings/Sync. Follow the existing card styling.
- [ ] **Step 2: Rewrite `HomeScreen`** — `LazyColumn` (edge-to-edge in Task 4) with: chrome items (unchanged), then `when { uiState.isLoading -> DiscoverHeroCard(loading=true); uiState.hero != null -> DiscoverHeroCard(label="Daily discovery", title=hero.title, subtitle=hero.subtitle, artUrl=hero.artUrl, onPlay=viewModel::playHero); else -> PersonalizeCard(...) }`. Remove all mix/playlist/liked composables + the action sheet from this file (they live in Library now).
- [ ] **Step 3: Compile** — `./gradlew :feature:home:compileDebugKotlin` → SUCCESS.
- [ ] **Step 4: Commit** (`feat(home): Premium Crisp discovery screen — hero + personalize card`).

---

## Task 4: Edge-to-edge (content scrolls behind the frosted nav)

**Files:**
- Modify: `feature/home/.../HomeScreen.kt` (contentPadding) + `app/.../StashScaffold.kt` (Home-scoped inset)

The deferred Plan-1 N3 work, **scoped to Home only** (other tabs keep the Scaffold `innerPadding`). Home's `LazyColumn` consumes the bottom window inset via `contentPadding` so content scrolls under the translucent nav; the nav's frosting now reads. Approach: pass the bottom inset height into `HomeScreen` (or read `WindowInsets.navigationBars` + the nav/mini-player height) and add it to the `LazyColumn` `contentPadding.bottom`, while ensuring the NavHost no longer double-insets Home's bottom. **Verify the other four tabs are unaffected** (they still get the full `innerPadding`).

- [ ] **Step 1: Implement** the Home-scoped edge-to-edge inset.
- [ ] **Step 2: Compile app** — `./gradlew :app:compileDebugKotlin` → SUCCESS.
- [ ] **Step 3: Device smoke:** on Home, content scrolls **behind** the translucent nav (frosting visibly reads); last item isn't clipped (bottom contentPadding clears the nav). Library/Search/Sync/Settings unchanged (no clipping, no gaps).
- [ ] **Step 4: Commit** (`feat(home): edge-to-edge content under the frosted nav (Home-scoped)`).

---

## Task 5: Trim Home nav wiring

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt` (`composable<HomeRoute>`, lines 54–72)

Home no longer needs `onNavigateToPlaylist`/`onNavigateToLikedSongs`/`onNavigateToMixBuilder` (those were mix/playlist/liked actions, now in Library). Reduce the `HomeScreen` call to the surviving callbacks (`onNavigateToSettings`, plus any the retained chrome still needs). Delete the now-dead lambdas.

- [ ] **Step 1: Implement** — trim the `HomeScreen(...)` call + signature to match Task 3's reduced params.
- [ ] **Step 2: Compile app** → SUCCESS.
- [ ] **Step 3: Commit** (`refactor(home): trim Home nav callbacks to discovery surface`).

---

## Task 6: Full build + device smoke

- [ ] **Step 1:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 2:** `./gradlew :feature:home:testDebugUnitTest --tests "*Home*"` → PASS.
- [ ] **Step 3: Device smoke (human):**
  - Home shows the **Discover hero** (plays the Daily Discover playlist on tap) OR, on a thin/cold library, the **personalize card** — never blank.
  - **No mix/playlist/liked content on Home** (all in Library now); **no duplication** (Daily Discover appears only as the Home hero, not in Library's mixes).
  - Frosted nav reads (content scrolls behind it); other tabs unaffected.
  - Retained chrome (tip-jar pill, lossless/backfill banners, streaming toggle) still works.
  - Light theme parity.
- [ ] **Step 4: Commit** any fixups. Then the sub-project-1 Phase-1 slice is device-verified and ready for `finishing-a-development-branch` (PR).

---

## Out of scope (later)

- Album-recommendation rows + the artist→albums resolver spike; Qobuz playlists; charts + rank persistence — **Phase 2 data work** (each slots into the §5 contract; chips appear once >1 section populates).
- Premium Crisp reskin of Library/Search/Settings — **sub-project 3**.
- Now Playing redesign + the true `SharedTransitionLayout` mini-player↔NowPlaying shared-element + mini-player frosted chrome — **sub-project 2**.
