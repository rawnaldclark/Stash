# Mixes on Home + Calm Sync Sources — Design

**Date:** 2026-07-17
**Status:** Approved (brainstormed with the user via visual companion)
**Design language:** Premium Crisp (continues Home / Library redesign)

## 0. Goal

Two connected UX problems, one vision — make the Spotify/YouTube experience intuitive and beautiful:

1. **Mixes are buried.** All the algorithmic mixes (Spotify Daily Mixes / Discover Weekly / artist radios / mood mixes, YouTube Home Mixes / My Mix) live in a single horizontal shelf hidden at the top of Library → Playlists. After the mix-variety work there are ~62 of them. Move them to **Home**, organized so 62 mixes read as curated, not a dump.
2. **Source cards are a maze.** The Sync tab's Spotify/YouTube source cards expand into a non-virtualized list of every playlist + mix as a toggle row. Finding or managing one playlist means scrolling a maze that also buries Schedule/Recent Syncs. Make the Sync landing a **short dashboard** and move playlist management to a **dedicated, search-first screen**.

Both were validated with the user via high-fidelity mockups (`.superpowers/brainstorm/1458-*/`).

## 1. Scope decisions (locked with user)

- **Moves to Home:** Spotify algorithmic mixes, YouTube algorithmic mixes, **and** the user's own Stash Mixes (+ "Create mix"). All `PlaylistType.DAILY_MIX` (Spotify/YouTube, split by `Playlist.source`) plus `PlaylistType.STASH_MIX`.
- **Stays in Library:** the **Liked Songs** card (Library already has a dedicated Liked tab; a second Home entry point would be redundant). Custom playlists stay in Library → Playlists.
- **Home layout direction:** categorized rails (not one long rail, not group-by-source).
- **"Create mix":** integrated into the Daily Discover hero, not a trailing rail card.
- **Sync sources:** dashboard landing + full-screen (not bottom-sheet) search-first manage view.

---

## 2. Part 1 — Mixes on Home

### 2.1 Home layout (top → bottom)

`HomeScreen.kt` is one `LazyColumn` of `item {}` sections. Insert the mixes **between the Discover hero (`HomeScreen.kt:313-341`) and the first Qobuz discovery row (`:346`)** — personal above generic discovery. New order:

1. App-title/chrome row · Supporter pill · banners · genre chips (unchanged)
2. **Discover hero** — now carries the Create-mix action (§2.2)
3. **"Your Mixes" rails** — four categorized rails (§2.3) *(new)*
4. Qobuz "New Releases" · "Qobuz Playlists" · "Top Albums" (unchanged)

### 2.2 Create-mix on the Daily Discover hero

`DiscoverHeroCard` (`core/ui/.../DiscoverHeroCard.kt:60`) gains a **secondary create action**, chosen treatment = **weighted FAB + ring**:

- Primary: the existing solid white **play** FAB (~52dp), real drop-shadow — plays Daily Discover.
- Secondary: a smaller (~38dp) **hairline ＋ ring** directly beneath it — `1.5dp` translucent-white border, `#ffffff0a` fill, violet-tinted glyph. Opens the create-mix flow (existing `onCreateMix` / mix-builder entry).
- Hierarchy is carried purely by size + depth; no text labels on the hero.

Add an `onCreateMix: () -> Unit` param to `DiscoverHeroCard` (defaulted/nullable so other callers are unaffected). Wire it to the existing mix-builder navigation.

### 2.3 The four rails + classifier

Grouped **by kind, not source** (source shown as a small `SourceIndicator` dot on each card). Each rail follows the canonical Home discovery-row shape (`DiscoveryAlbumRow`, `HomeScreen.kt:603`): `Column { Spacer(16.dp); SectionHeader(title, actionText="See all", onSeeAll); LazyRow(contentPadding=20.dp, spacedBy=12.dp){ mix cards } }`. A rail renders only if non-empty.

| Rail | Contents | Classifier rule (`PlaylistType.DAILY_MIX` unless noted) |
|---|---|---|
| **Made for you** | Daily Mix N, Discover Weekly, Release Radar, On Repeat, Daylist, Time Capsule; YT My Mix N, My Supermix, Discover Mix, Replay/Archive/New Release Mix | name in a known personalized-daily set (reuse the Spotify/YT name + prefix knowledge already in `SpotifyApiClient`/`YTMusicApiClient`) |
| **Radios** | artist/song radios | `name` ends with `"Radio"` |
| **Mood & decades** | mood/decade/themed remainder (Focus Mix, Disco Fever, Motown, 2010s Mix, "This Is X", …) | `DAILY_MIX` not matched by the two rules above |
| **Your mixes** | the user's Stash mixes | `PlaylistType.STASH_MIX` (excluding the builtin Daily Discover, which is the hero) |

The classifier is a **pure function** `mixRail(playlist): MixRail` (testable in isolation, mirrors the Plan B predicate pattern). "See all" on any rail opens a **mix browse screen** (a filterable grid, mirroring the existing "See all playlists" browse) scoped to that rail's mixes — the escape valve so no rail needs to be exhaustive.

### 2.4 Card treatment

Reuse the existing `DailyMixCard` (`LibraryMixesSection.kt:270`, 180×120 glass, source-tinted gradient, `SourceIndicator`, `MixBuildState`) but **reskin to Premium Crisp** (it's flagged as a pre-Premium-Crisp verbatim port). Note the source comment marks that file's reskin as its own sub-project, so the plan must decide **reskin-in-place vs. fork a Home copy**. Alternative: `AlbumSquareCard` (140dp) if a cleaner square is preferred — decide during the plan; keep the source dot + build-state either way. Tap = open/play the mix; long-press = the existing mix action sheet (Stash mixes only).

> **Plan-1 scope note:** in the first plan every classified mix shows on Home (no hiding). The **"Hide from Home"** control and its persisted flag are introduced in **Part 2** (§3.3), which owns the flag + migration *and* both its control surfaces (the manage screen and this card's long-press). This keeps the two-plan split clean — plan 1 never writes a flag it doesn't own.

### 2.5 Data plumbing

The mix slices currently live in `LibraryViewModel.libraryMixDataFlow` (`LibraryViewModel.kt:144-204`), sliced client-side from `musicRepository.getAllPlaylists()` by type/source. **Move (don't duplicate) this into `HomeViewModel`** (which already collects `getAllPlaylists()` for the hero), exposing the four classified rails + Stash mixes + build-state on `HomeUiState`. Reuse existing actions (`playAllMixes`, `refreshMixIfStale`, mix-builder nav).

### 2.6 Library cleanup

- Drop `LibraryMixesSection` from the Playlists grid's `mixesHeader` lambda (`LibraryScreen.kt:511-535`). The grid's items are already `CUSTOM`-only (`LibraryViewModel.kt:224`), so custom playlists, empty-state, and the library header are untouched.
- Keep the `libraryHeader()` (search/sort/filter chips) in that slot.
- Remove the now-unused Library mix `UiState` slices + `libraryMixDataFlow` once Home owns them. The dedicated Library **Liked tab** and its flows are independent and stay.

---

## 3. Part 2 — Calm Sync sources (no maze)

### 3.1 Sync landing = short dashboard

`SourcePreferencesCard` (`components/SourcePreferencesCard.kt:52`) loses its expandable inline playlist list. Each source becomes a **compact summary card**:

- Brand bar + name + `Connected` StatusPill (unchanged)
- A **stat row** of big Space-Grotesk numerals: `N mixes · auto`, `synced / total playlists`, `liked count`
- The **Mix sync mode** chips (Refresh/Accumulate) stay (small, per-source)
- A **`Manage ›`** affordance → the manage screen (§3.2)

No inline list ⇒ the landing never balloons; Schedule and Recent Syncs stay reachable without scrolling past 40 toggle rows.

### 3.2 Manage screen = full-screen, search-first

`Manage ›` navigates to a **new dedicated screen** per source (`ManagePlaylistsScreen`):

- **Pinned search field** at the top — filters across all sections instantly, so finding a playlist is a keystroke, not a scroll. (Promotes the existing `SearchablePlaylistList` filter to the whole screen.)
- **Segment filter:** All · Synced · Off.
- **Sections:**
  - **Liked** — one toggle row (reuse `SpotifySyncToggleRow`, `SyncScreen.kt:606`).
  - **Mixes** — one summary row: "N mixes · surfaced on Home", tap → a mix list where a mix can be **hidden from Home** (no per-mix sync toggles; mixes auto-sync per Plan B). "Hide from Home" is the only per-mix control, matching §2.4's long-press.
  - **Your playlists (synced/total)** — a **real lazy list** (`LazyColumn` items, not an eager `forEach`) of `SpotifySyncToggleRow`, with **Enable all / none**.
- The playlist-sync side reuses existing `SyncViewModel` state (`spotifyPlaylists`/`youTubePlaylists`, `syncMode`, `onTogglePlaylistSync`, `SyncViewModel.kt:92-324`) — a presentation relocation, no new sync plumbing. The **"Hide from Home" flag is genuinely new** (see §3.3): a persisted boolean requiring a schema change, so this plan budgets a Room migration.

### 3.3 The "auto" mixes model + the Hide-from-Home flag (owned by this plan)

Because the mix-variety work auto-enables + surface-onlys mixes, the manage screen does **not** list per-mix sync toggles. The only mix control is **Hide from Home** — the piece that turns 40 mix toggle rows into one calm summary.

**This flag is new work owned entirely by Part 2 / plan 2:**
- A new persisted boolean, e.g. `PlaylistEntity.hideFromHome` (default `false`) — no such field exists today. Requires: a **Room migration 33→34** (`ALTER TABLE playlists ADD COLUMN hide_from_home INTEGER NOT NULL DEFAULT 0`), the `Playlist` domain field, `PlaylistMapper`, and a DAO write path.
- **Two control surfaces, both shipped in this plan:** the manage screen's mix summary (tap a mix → hide it) **and** the mix card's long-press on Home (adds "Hide from Home" to the existing action sheet).
- **One read point:** the Home rail classifier (§2.3) filters out `hideFromHome == true` mixes. Plan 1 has **no such filter** (the column doesn't exist yet); plan 2 adds the column and the classifier filter **together** in the same migration.

---

## 4. Premium Crisp adherence

Near-black canvas; violet primary (`#8B5CF6`) for accents/create; cyan (`#06B6D4`) eyebrows; `spotifyGreen`/`youtubeRed` source dots; Space Grotesk for titles/numerals (the dashboard stats, mix counts), Inter for body; `GlassCard`/glass tokens for surfaces; `RoundedCornerShape(16dp)` cards, `8dp` thumbs; `StashMotion` (180/260ms, `pressScale` 0.97, 40ms section stagger). Recommendation: **extract a shared `CardRail(title, action, content)` composable** in `core:ui` so the mixes rails, Qobuz rows, and future rails converge instead of copy-pasting the Column/Spacer/SectionHeader/LazyRow scaffold a third time.

## 5. Decomposition → two plans

1. **`mixes-on-home`** — hero create action, the four rails + classifier + `CardRail`, card reskin (or Home fork), `HomeViewModel` mix plumbing, mix browse "See all", Library cleanup. **Every classified mix shows on Home** (no hiding yet — the classifier has no `hideFromHome` filter because the flag doesn't exist until plan 2).
2. **`calm-sync-sources`** — source summary dashboard, `ManagePlaylistsScreen` (search-first, off existing `SyncViewModel`), **and the full "Hide from Home" capability**: the new `PlaylistEntity.hideFromHome` column + Room migration 33→34 + mapper + DAO, both control surfaces (manage-screen mix summary + Home card long-press), and the one-line classifier filter update in `HomeViewModel` so hidden mixes drop off the Home rails.

Each is independently shippable and device-verifiable. Suggested order: **mixes-on-home first** (establishes the rails + card + data plumbing), then **calm-sync-sources** (adds the dashboard + manage screen + the hide flag that references those rails). The order is safe because plan 1 ships a complete, usable Home-mixes surface with no dependency on the flag; plan 2 layers the hide capability on top.

## 6. Out of scope

- Changing what the mixes *contain* or how they're fetched/auto-enabled (that shipped in the mix-variety work).
- The Liked experience (stays in Library, unchanged).
- Recent-syncs receipts / sync-mode logic (already shipped).
- A full virtualization rewrite of any other Sync section beyond moving the playlist list to its own lazy screen.
