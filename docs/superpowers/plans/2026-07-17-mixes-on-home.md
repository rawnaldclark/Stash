# Mixes on Home — Implementation Plan (plan 1 of 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the Spotify/YouTube/Stash algorithmic mixes out of Library → Playlists and onto Home, organized as four kind-based rails below the Daily Discover hero, with a Create-mix action integrated into the hero.

**Architecture:** A pure `mixRail(playlist)` classifier buckets each mix into one of four rails. A new shared `CardRail` composable + a Premium-Crisp `MixRailCard` render them (the existing `DailyMixCard` is a frozen pre-Premium-Crisp port — leave it; Library drops the whole section). `HomeViewModel` gains the mix plumbing (derived from the `getAllPlaylists()` stream it already collects for the hero); `HomeScreen` slots the rails between the hero and the Qobuz rows. `DiscoverHeroCard` gains an optional secondary "＋ create" action.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room (read-only here), JUnit4 + Truth (`:core:data`/`:feature:*` test convention), mockk where a VM is exercised.

**Spec:** `docs/superpowers/specs/2026-07-17-home-mixes-and-calm-sync-sources-design.md` (§2). **No `hideFromHome` flag in this plan** — every classified mix shows on Home; the hide capability is plan 2.

**Branch:** continue on `feat/library-redesign` (where this session's Home/Library/Sync work lives), or a fresh `feat/mixes-on-home` off it — the executor decides; this session has worked single-branch.

**First decision (settled):** build a **new Premium-Crisp `MixRailCard` in `:core:ui`** rather than reskin the frozen `DailyMixCard`. Rationale: `LibraryMixesSection.kt:262-264` marks that card as a verbatim port whose reskin is a separate sub-project; Library loses the section entirely (Task 8), so there is nothing to reuse it for. Reuse only the small, non-frozen pieces: `SourceIndicator`, `MixBuildState`.

---

## File Structure

**Create:**
- `core/ui/src/main/kotlin/com/stash/core/ui/components/CardRail.kt` — shared `Column{Spacer+SectionHeader+LazyRow}` rail (replaces the copy-pasted `DiscoveryAlbumRow`/`DiscoveryPlaylistRow` scaffold).
- `feature/home/src/main/kotlin/com/stash/feature/home/MixRailCard.kt` — Premium-Crisp mix card (cover, title, source dot, build-state). **In `:feature:home`, not `:core:ui`** — `MixBuildState` lives in `:core:data`, which `:core:ui` correctly does not depend on; the card is Home-only so it lives with Home (decided during Task 3).
- `feature/home/src/main/kotlin/com/stash/feature/home/MixRail.kt` — `enum MixRail` + pure `mixRail(playlist): MixRail?` classifier + `HomeMix` UI model.
- `feature/home/src/main/kotlin/com/stash/feature/home/MixBrowseScreen.kt` — the "See all" grid for one rail.
- `feature/home/src/test/kotlin/com/stash/feature/home/MixRailClassifierTest.kt`

**Modify:**
- `core/ui/.../components/DiscoverHeroCard.kt` — add optional `onCreateMix`; render the play FAB + smaller ＋ ring.
- `feature/home/.../HomeUiState.kt` — add mix-rail fields.
- `feature/home/.../HomeViewModel.kt` — add mix plumbing; fold into `uiState`.
- `feature/home/.../HomeScreen.kt` — render the four rails between hero (:341) and Qobuz rows (:346); wire `onCreateMix` + rail "See all"; nav to `MixBrowseScreen`.
- `feature/library/.../LibraryScreen.kt` — drop `LibraryMixesSection` from the `mixesHeader` lambda (:511-535).
- `feature/library/.../LibraryViewModel.kt` / `LibraryUiState.kt` — remove now-unused mix slices once Home owns them.

---

## Task 1: The `mixRail` classifier (pure, testable)

**Files:**
- Create: `feature/home/.../MixRail.kt`
- Test: `feature/home/src/test/kotlin/com/stash/feature/home/MixRailClassifierTest.kt`

- [ ] **Step 1: Write the failing test.** (`:feature:home` test deps — confirm; JUnit4 + Truth is the repo convention. Use `com.stash.core.model.Playlist`, `PlaylistType`, `MusicSource`.)
```kotlin
import com.google.common.truth.Truth.assertThat
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import org.junit.Test

class MixRailClassifierTest {
    private fun pl(name: String, type: PlaylistType, src: MusicSource = MusicSource.SPOTIFY) =
        Playlist(id = 1, name = name, source = src, type = type)

    @Test fun `stash mix goes to Your mixes`() {
        assertThat(mixRail(pl("My Guitar Mix", PlaylistType.STASH_MIX))).isEqualTo(MixRail.YOUR_MIXES)
    }
    @Test fun `radio names go to Radios`() {
        assertThat(mixRail(pl("David Bowie Radio", PlaylistType.DAILY_MIX))).isEqualTo(MixRail.RADIOS)
    }
    @Test fun `known dailies go to Made for you`() {
        assertThat(mixRail(pl("Discover Weekly", PlaylistType.DAILY_MIX))).isEqualTo(MixRail.MADE_FOR_YOU)
        assertThat(mixRail(pl("Daily Mix 3", PlaylistType.DAILY_MIX))).isEqualTo(MixRail.MADE_FOR_YOU)
        assertThat(mixRail(pl("My Mix 2", PlaylistType.DAILY_MIX, MusicSource.YOUTUBE))).isEqualTo(MixRail.MADE_FOR_YOU)
        assertThat(mixRail(pl("My Supermix", PlaylistType.DAILY_MIX, MusicSource.YOUTUBE))).isEqualTo(MixRail.MADE_FOR_YOU)
    }
    @Test fun `other daily mixes go to Mood and decades`() {
        assertThat(mixRail(pl("Focus Mix", PlaylistType.DAILY_MIX))).isEqualTo(MixRail.MOOD_DECADES)
        assertThat(mixRail(pl("Motown", PlaylistType.DAILY_MIX))).isEqualTo(MixRail.MOOD_DECADES)
        assertThat(mixRail(pl("This Is Rohne", PlaylistType.DAILY_MIX))).isEqualTo(MixRail.MOOD_DECADES)
    }
    @Test fun `non-mix playlists are not classified`() {
        assertThat(mixRail(pl("Road Trip 2024", PlaylistType.CUSTOM))).isNull()
        assertThat(mixRail(pl("Liked Songs", PlaylistType.LIKED_SONGS))).isNull()
    }
    @Test fun `radio precedence beats the mood fallback`() {
        // A DAILY_MIX ending in "Radio" is a radio even though it's not a known daily.
        assertThat(mixRail(pl("Are You Alright? Radio", PlaylistType.DAILY_MIX))).isEqualTo(MixRail.RADIOS)
    }
}
```

- [ ] **Step 2: Run it, verify it FAILS** — `./gradlew :feature:home:testDebugUnitTest --tests "*MixRailClassifier*"` (compile error).

- [ ] **Step 3: Implement `MixRail.kt`:**
```kotlin
package com.stash.feature.home

import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType

/** The four Home mix rails, in display order. */
enum class MixRail { MADE_FOR_YOU, RADIOS, MOOD_DECADES, YOUR_MIXES }

/** Known personalized-daily names (lowercased). Radios + Daily/My-Mix-N are matched by pattern. */
private val MADE_FOR_YOU_NAMES = setOf(
    "discover weekly", "release radar", "on repeat", "repeat rewind", "daylist", "time capsule",
    "my supermix", "discover mix", "replay mix", "archive mix", "new release mix",
)
private val DAILY_OR_MYMIX = Regex("""^(daily mix|my mix)\s*\d+$""", RegexOption.IGNORE_CASE)

/**
 * Which Home rail a playlist belongs to, or null if it isn't a mix.
 * Order matters: STASH_MIX → yours; then within DAILY_MIX, radios win over the
 * made-for-you set, which wins over the mood/decade fallback.
 */
fun mixRail(playlist: Playlist): MixRail? {
    if (playlist.type == PlaylistType.STASH_MIX) return MixRail.YOUR_MIXES
    if (playlist.type != PlaylistType.DAILY_MIX) return null
    val n = playlist.name.trim()
    if (n.endsWith("Radio", ignoreCase = true)) return MixRail.RADIOS
    if (DAILY_OR_MYMIX.matches(n) || n.lowercase() in MADE_FOR_YOU_NAMES) return MixRail.MADE_FOR_YOU
    return MixRail.MOOD_DECADES
}
```

- [ ] **Step 4: Run the test, verify PASS.** Same command.
- [ ] **Step 5: Commit** — `feat(home): mixRail classifier — bucket mixes into four rails`. (`git commit -F -` heredoc via Bash tool; stage explicit paths; NEVER `git add -A`.)

> Note: exclude the builtin Daily Discover STASH_MIX (the hero) upstream in the ViewModel (Task 5), not here — the classifier is purely name/type based, matching the existing Library slicing (`LibraryViewModel.kt:180-182`).

---

## Task 2: `CardRail` shared composable

**Files:**
- Create: `core/ui/.../components/CardRail.kt`

Extract the canonical Home discovery-row scaffold (currently copy-pasted as private `DiscoveryAlbumRow`/`DiscoveryPlaylistRow`, `HomeScreen.kt:603/635`) so mixes rails and Qobuz rows share one implementation.

- [ ] **Step 1:** Create the composable (no unit test — it's a layout wrapper; verified on device Task 9):
```kotlin
package com.stash.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The canonical Home horizontal rail: Spacer + [SectionHeader] + a [LazyRow]
 * of cards. Matches the existing discovery-row rhythm (contentPadding 20dp,
 * inter-card gap 12dp) so every rail on Home reads as one system.
 */
@Composable
fun CardRail(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    content: LazyListScopeContent,
) {
    Column(modifier) {
        Spacer(Modifier.height(16.dp))
        SectionHeader(title = title, actionText = actionText, onActionClick = onActionClick)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
```
`LazyListScopeContent` = `androidx.compose.foundation.lazy.LazyListScope.() -> Unit` (declare a typealias or inline the type). Confirm `SectionHeader`'s exact param names (`title`, `actionText`, `onActionClick`) against `core/ui/.../SectionHeader.kt:16` and adjust.

- [ ] **Step 2: Compile** — `./gradlew :core:ui:compileDebugKotlin`. Expected SUCCESSFUL.
- [ ] **Step 3: Commit** — `feat(ui): CardRail — shared horizontal rail scaffold`.

> Optional (do only if trivial): refactor `HomeScreen`'s two private discovery rows to call `CardRail`. If it risks churn, skip — YAGNI; the new mixes rails use `CardRail` and the Qobuz rows can converge later.

---

## Task 3: `MixRailCard` composable (Premium Crisp)

**Files:**
- Create: `core/ui/.../components/MixRailCard.kt`

A ~140dp card: rounded cover (or brand/source gradient fallback), a source dot (`SourceIndicator`), title, and an optional build-state line (Building… / No tracks). Reuse `MixBuildState` (from `core.data.mix`) + `SourceIndicator`. Match `AlbumSquareCard` tokens (140dp, `RoundedCornerShape(8dp)` thumb, `labelLarge` title, `pressScale`).

- [ ] **Step 1:** Create the composable. Params: `title: String, artUrl: String?, source: MusicSource, buildState: MixBuildState = MixBuildState.READY, onClick: () -> Unit, onLongPress: (() -> Unit)? = null`. Render cover with `AsyncImage`(ArtUrlUpgrader) or a source-tinted gradient fallback; overlay a small `SourceIndicator` dot top-left; title `labelLarge` maxLines 2; when `buildState != READY` show "Building…"/"No tracks" in `labelSmall`/`textTertiary` instead of a subtitle. Use `Modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress)` and `pressScale` via a remembered `MutableInteractionSource` (mirror how `AlbumSquareCard.kt:40` applies `.pressScale(interactionSource)` — it is NOT a bare `Modifier.pressScale()`). Substitute the source dot + build-state for `AlbumSquareCard`'s year/artist subtitle.

> `onLongPress` drives the Stash-mix action sheet (Task 6) — only "Your mixes" cards pass it; streaming mixes leave it null. It's also the seam plan 2 extends with "Hide from Home".

- [ ] **Step 2: Compile** — `./gradlew :core:ui:compileDebugKotlin`.
- [ ] **Step 3:** Add a `@Preview` with one Spotify, one YouTube, and one Building card so the card is inspectable.
- [ ] **Step 4: Commit** — `feat(ui): MixRailCard — Premium Crisp algorithmic-mix card`.

---

## Task 4: Create-mix action on the Daily Discover hero

**Files:**
- Modify: `core/ui/.../components/DiscoverHeroCard.kt:60-163`

Chosen treatment (spec §2.2): the existing white **play** FAB grows to ~52dp; a smaller ~38dp **hairline ＋ ring** sits directly beneath it. New optional param `onCreateMix: (() -> Unit)? = null` — render the ＋ only when non-null (other callers unaffected).

- [ ] **Step 1:** Add the param to the signature:
```kotlin
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onCreateMix: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
```

- [ ] **Step 2:** Replace the single play `Surface` (`:148-160`) with a vertical action stack:
```kotlin
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Surface(onClick = onPlay, shape = CircleShape, color = Color.White,
                    modifier = Modifier.size(52.dp).shadow(6.dp, CircleShape)) {
                    Icon(Icons.Default.PlayArrow, "Play $title", tint = Color.Black,
                        modifier = Modifier.padding(12.dp))
                }
                if (onCreateMix != null) {
                    Surface(onClick = onCreateMix, shape = CircleShape,
                        color = Color.White.copy(alpha = 0.04f),
                        border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.30f)),
                        modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Default.Add, "Create a mix",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                            modifier = Modifier.padding(8.dp))
                    }
                }
            }
```
Add imports: `androidx.compose.foundation.BorderStroke`, `androidx.compose.foundation.layout.Column`(present), `androidx.compose.material.icons.filled.Add`. Keep the shadow import.

- [ ] **Step 3: Compile** — `./gradlew :core:ui:compileDebugKotlin`. Verify the existing `DiscoverHeroCard(loading=true)` and no-create callers still compile (the new param is defaulted/nullable).
- [ ] **Step 4:** Update/extend the hero `@Preview` to show the ＋ ring.
- [ ] **Step 5: Commit** — `feat(ui): DiscoverHeroCard — optional Create-mix ring under the play FAB`.

---

## Task 5: Home mix data plumbing (`HomeUiState` + `HomeViewModel`)

**Files:**
- Modify: `feature/home/.../HomeUiState.kt:22-65`
- Modify: `feature/home/.../HomeViewModel.kt` (hero flow `:161-175`, uiState combine `:233-255`, constructor deps)

The mix slices live in `LibraryViewModel.libraryMixDataFlow` (`:144-204`). **Move the mix derivation to Home.** Both the hero and the rails derive from `getAllPlaylists()` — so fold them into ONE flow to keep the top-level `combine` at its 5-arg typed max.

- [ ] **Step 1:** Add rail state to `HomeUiState` — a `HomeMix` UI model + the four lists + a build-state set:
```kotlin
    val madeForYou: List<HomeMix> = emptyList(),
    val radios: List<HomeMix> = emptyList(),
    val moodDecades: List<HomeMix> = emptyList(),
    val yourMixes: List<HomeMix> = emptyList(),
```
```kotlin
/** A mix as a Home rail card. buildState only meaningful for STASH_MIX. */
data class HomeMix(
    val id: Long, val title: String, val artUrl: String?,
    val source: com.stash.core.model.MusicSource,
    val buildState: com.stash.core.data.mix.MixBuildState = com.stash.core.data.mix.MixBuildState.READY,
)
```

- [ ] **Step 2:** In `HomeViewModel`, replace the standalone `heroFlow` with a combined `homePlaylistFlow` that derives the hero AND the four rails from the same inputs (playlists + builtin ids + recipe build-state). Inject **`discoveryQueueDao`** (new — `recipeDao` is already injected for `getBuiltinPlaylistIds`). `mixBuildState` is a **top-level function** (`com.stash.core.data.mix.mixBuildState`) — import it, don't inject it. Mirror `LibraryViewModel.kt:150-175` for the build-state derivation. Classify via `mixRail(playlist)`, excluding builtin ids from `YOUR_MIXES`. Emit a holder `(hero, madeForYou, radios, moodDecades, yourMixes, customMixPlaylistIds)`; map into `HomeUiState` in the top-level combine (swap `heroFlow` → `homePlaylistFlow`, unpack its fields). Keep the combine at 5 flows. Also expose `customMixPlaylistIds: Set<Long>` on `HomeUiState` so the action sheet (Task 6) knows which "Your mixes" are editable/deletable.

- [ ] **Step 3: Move the Stash-mix actions into `HomeViewModel`.** These currently live only in `LibraryViewModel` and become unreachable once the Library section is removed (Task 8): `refreshMix(playlistId)` (`LibraryViewModel.kt:752`), `deleteCustomMix(playlist: Playlist)` (`:823` — **keep the `Playlist`-typed signature**; the action sheet has the `Playlist`, or look it up from the mix id), `editRecipeId(playlistId)` / the edit-nav trigger (`:853`), and `refreshMixIfStale(playlistId)` (`:839`). Move them (and any repository/dao deps they need — `recipeDao`, `musicRepository`, the mix-refresh worker trigger) to `HomeViewModel`. This is what keeps mix management alive after the move.

- [ ] **Step 4: Compile** — `./gradlew :feature:home:compileDebugKotlin`. Expected SUCCESSFUL.
- [ ] **Step 5:** (Optional, if a HomeViewModel test harness exists) assert the four lists partition a fixture playlist set correctly. If no harness exists, rely on the Task 1 classifier test + device Task 9 (don't stand up a 10-dep VM test — ponytail).
- [ ] **Step 6: Commit** — `feat(home): derive the four mix rails + move Stash-mix actions to Home`.

---

## Task 6: Render the rails on Home + wire hero create + See-all

**Files:**
- Modify: `feature/home/.../HomeScreen.kt` (hero item `:313-341`; insert before `:346`)

- [ ] **Step 1:** Pass `onCreateMix` to the hero (`:313-341` `DiscoverHeroCard(...)` call) → `viewModel`'s mix-builder nav (reuse the existing create-mix entry the Library uses; find `onNavigateToMixBuilder`/`createMix` and thread it through `HomeScreen`'s nav callbacks).

- [ ] **Step 2:** Insert four `item {}` blocks between the hero (`:341`) and the first Qobuz row (`:346`), each rendering a `CardRail` of `MixRailCard`s, shown only when the list is non-empty:
```kotlin
if (uiState.madeForYou.isNotEmpty()) item {
    CardRail(title = "Made for you", actionText = "See all",
        onActionClick = { onSeeAllMixes(MixRail.MADE_FOR_YOU) }) {
        items(uiState.madeForYou, key = { it.id }) { m ->
            MixRailCard(title = m.title, artUrl = m.artUrl, source = m.source,
                buildState = m.buildState, onClick = { onOpenMix(m.id) })
        }
    }
}
// …repeat for radios ("Radios") and moodDecades ("Mood & decades") — onClick only, no long-press.
// The "Your mixes" rail ALSO passes onLongPress to open the Stash-mix action sheet (Step 3):
if (uiState.yourMixes.isNotEmpty()) item {
    CardRail(title = "Your mixes", actionText = "See all",
        onActionClick = { onSeeAllMixes(MixRail.YOUR_MIXES) }) {
        items(uiState.yourMixes, key = { it.id }) { m ->
            MixRailCard(title = m.title, artUrl = m.artUrl, source = m.source,
                buildState = m.buildState,
                onClick = { onOpenMix(m.id) },
                onLongPress = { actionSheetMixId = m.id })   // opens the sheet
        }
    }
}
```
Add `onSeeAllMixes(MixRail)` and `onOpenMix(Long)` to `HomeScreen`'s callback params; wire `onOpenMix` to the existing playlist-open/play nav (also call `refreshMixIfStale(id)` on open, matching Library's behavior), `onSeeAllMixes` to `MixBrowseScreen` (Task 7).

- [ ] **Step 3: Move the Stash-mix action sheet to Home.** The action sheet `ModalBottomSheet` (Refresh [STASH_MIX only] · Edit/Delete [custom mixes only] · Open) lives at `LibraryMixesSection.kt:193-258`, and its row helper `HomeBottomSheetActionRow` at `:805` — grab **both** (they die with Task 8). Move that action-sheet composable into `HomeScreen.kt` (or a sibling `feature/home/.../MixActionSheet.kt`). Drive it from a `var actionSheetMixId by remember { mutableStateOf<Long?>(null) }`; when non-null, show the sheet for that mix, gating Refresh/Edit/Delete on `uiState.customMixPlaylistIds` (same rule as Library, `LibraryMixesSection.kt:139-146`). Wire its rows to the `HomeViewModel` actions moved in Task 5 (`refreshMix`/`editRecipeId`→edit-nav/`deleteCustomMix`/open). This preserves Stash-mix management after the move and gives plan 2 the action sheet to extend with "Hide from Home".

- [ ] **Step 4: Compile** — `./gradlew :feature:home:compileDebugKotlin`.
- [ ] **Step 5: Commit** — `feat(home): render the four mix rails + hero create + Stash-mix action sheet`.

---

## Task 7: "See all" mix browse screen

**Files:**
- Create: `feature/home/.../MixBrowseScreen.kt`

A simple full-screen filterable grid of one rail's mixes (the escape valve so no rail must be exhaustive). Mirror the existing "See all playlists" browse (find it via `onSeeAllPlaylists` in HomeScreen) for nav + grid conventions; reuse `MixRailCard`. Scope: title = the rail's label; a `LazyVerticalGrid` (2 cols) of the rail's `HomeMix`es; back nav. No search in v1 (rails are short; YAGNI — add if a rail ever gets huge).

- [ ] **Step 1:** Create the screen + its nav route; feed it the rail's list (pass the `MixRail` + read from `uiState`, or a dedicated VM getter).
- [ ] **Step 2: Compile** `:feature:home`.
- [ ] **Step 3: Commit** — `feat(home): mix browse (See all) grid per rail`.

---

## Task 8: Library cleanup — drop the mixes section

**Files:**
- Modify: `feature/library/.../LibraryScreen.kt:511-535` (the `mixesHeader` lambda)
- Modify: `feature/library/.../LibraryViewModel.kt:144-204, 221-224` + `LibraryUiState.kt`

- [ ] **Step 1:** In the `mixesHeader` lambda, remove the `LibraryMixesSection(...)` call, leaving `Column { libraryHeader() }` (the grid's items are already CUSTOM-only, `LibraryViewModel.kt:224` — the grid, empty-state, and library header are untouched).
- [ ] **Step 2:** Remove the now-unused mix `UiState` slices (`stashMixes/spotifyMixes/youtubeMixes/likedPlaylists/buildingMixIds/emptyMixIds/customMixPlaylistIds`) and the `libraryMixDataFlow` computation **only if nothing else reads them**. Grep each field first — the dedicated Liked tab (`likedTracks/likedFilter/likedSources`, `LibraryViewModel.kt:341-380`) is independent and MUST stay. `getRecentlyAdded` feeds the Songs recently-downloaded rail — keep that. Delete `LibraryMixesSection.kt` only if it has no remaining callers.
- [ ] **Step 3: Co-remove `playAllMixes`.** `LibraryViewModel.playAllMixes(source)` (`:874-877`) reads `uiState.value.spotifyMixes`/`.youtubeMixes` — deleting those slices breaks its compile. It's orphaned (Home's rails have no "play all"), so delete `playAllMixes` and its wiring: the `onPlayAllMixes` param on `LibraryScreen`/`PlaylistsGrid` (`LibraryScreen.kt:357-361`, call site `:180,526`) and its `StashNavHost` pass-through. Likewise lint-clean the other now-unused mix params (`onRefreshMix/onEditMix/onDeleteMix/onCreateMix`) on `LibraryScreen`/`PlaylistsGrid`.
- [ ] **Step 4: Fix the test source set.** Two Library test files read the removed slices: `LibraryViewModelMixTest.kt` (asserts `stashMixes/spotifyMixes/youtubeMixes/likedPlaylists`) and `LibraryUiStateTest.kt` (asserts their empty defaults). The main-source compile in Step 5 WON'T catch this. Delete the mix-slicing assertions from both (the slicing logic now lives in Home's `MixRailClassifierTest` — Task 1); keep any Liked/recently-added assertions that still apply.
- [ ] **Step 5: Compile + test** — `./gradlew :feature:library:compileDebugKotlin :feature:library:testDebugUnitTest`. Both must pass (the test run is what catches the removed-slice references in the test source set). Fix any dangling references.
- [ ] **Step 6: Commit** — `refactor(library): remove the mixes shelf (moved to Home)`.

---

## Task 9: Device verification

- [ ] **Step 1:** `./gradlew :app:installDebug`. (This device: `adb screencap` returns black — capture via `screenrecord --time-limit 1` + `ffmpeg -frames:v 1 -update 1`. It's the user's daily phone — guard taps on `dumpsys window mCurrentFocus` containing `com.stash.app.debug`.)
- [ ] **Step 2: Home** — confirm the Daily Discover hero shows the play FAB + ＋ ring; below it the four rails (Made for you · Radios · Mood & decades · Your mixes) render with source dots; each rail's "See all" opens the browse grid; Qobuz rows still follow.
- [ ] **Step 3:** Tap the hero ＋ → the create-mix flow opens. Tap a mix card → it opens/plays.
- [ ] **Step 4: Library → Playlists** — confirm the mixes shelf is GONE and only custom playlists remain; the Liked tab still works.
- [ ] **Step 5:** Report PASS/FAIL with a captured Home frame.

---

## Verification checklist (whole plan)

- [ ] `./gradlew :feature:home:testDebugUnitTest --tests "*MixRailClassifier*"` green; `:core:ui`, `:feature:home`, `:feature:library` compile.
- [ ] Device: four rails render on Home, hero create works, mixes gone from Library, Liked intact.
- [ ] No `hideFromHome` anywhere (that's plan 2).
