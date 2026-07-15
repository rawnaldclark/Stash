# Library Redesign — Design

**Date:** 2026-07-15
**Status:** Draft for review
**Design language:** Premium Crisp (continues the merged Home redesign, PR #267 / commit 9e06a9e7)

## 1. Goal

Rebuild the Library tab into a **listen-first** surface for a fully-downloaded, offline collection. Today the tab is dominated by permanent chrome and its browsing is unintuitive; the user's own words: *"I come to my library to listen to offline music… outside of the shuffle button it's basically useless and non-intuitive."*

Fix three concrete problems (all confirmed in code):

1. **Permanent chrome eats ~half the screen.** `LibraryContent` is a non-scrolling `Column` stacking six fixed elements — heading + Import, an import strip, the Shuffle card, the search bar, the tab chips, a sort-chip row, and a source-filter chip row — above a nested per-tab `LazyColumn`. None of it scrolls away.
2. **"Recently downloaded" has no UI.** `LibraryViewModel` still computes `recentlyAdded` into state, but nothing renders it since the Home rewrite. The data pipeline is intact — only the surface is missing.
3. **Browsing is flat.** Four equal tabs plus two chip rows compete for the permanent top space, none of it in the premium card language shipped on Home.

The redesign was validated end-to-end with the user via high-fidelity mockups (`.superpowers/brainstorm/1468-*/library-*.html`).

## 2. The validated design

A single scrolling surface with a **collapsing header**:

```
┌──────────────────────────────────────┐
│ Library                     🔍  ⇅     │  header: title + search + sort/filter icons
│ ┌──────────────────────────────────┐ │
│ │ SHUFFLE YOUR LIBRARY          ▶  │ │  Shuffle hero (violet gradient, Discover-hero
│ │ 1,240 songs · all offline        │ │  sibling). Tap FAB → shuffleLibrary().
│ └──────────────────────────────────┘ │
│ RECENTLY DOWNLOADED                    │  cyan eyebrow
│ [▢FLAC][▢FLAC][▢FLAC][▢FLAC] →        │  horizontal rail of recentlyAdded, FLAC badges
│ ══ Songs  Playlists  Artists  Albums ═│  ← STICKY chips (Premium Crisp CrispChipRow)
│ ♪ In the Hour of Chaos   FLAC   3:58  │  content for the active chip
│ ♪ POWER HOUSE            FLAC   3:11  │
│ …                                      │
└──────────────────────────────────────┘
```

**Scroll behavior:** the header (Shuffle hero + Recently-downloaded rail) **scrolls away**; the category chips **stick** to the top. When scrolled, the sticky strip also shows a **small shuffle button** + the search/sort icons, so shuffle is never more than a tap away. (User chose "hero-forward" arrival — direction A.)

**The four chips swap the content (sticky bar stays):**
- **Songs** — the track list, default sort **Recently added** (so recent downloads *are* the top of the list). Multi-select preserved.
- **Playlists** — a premium 2-col grid: **Liked Songs**, a **Downloads** collection (all offline), **Stash Mixes**, and custom playlists.
- **Artists** — a list with **round** artist art + song counts.
- **Albums** — a square-cover grid.

**Search & sort/filter move off the permanent bars:**
- **🔍** slides an inline search field open over the current list; no permanent bar.
- **⇅** opens **one bottom sheet**: **Sort by** (Recently added · A–Z · Most played · Duration) + **Show only** (All · YouTube · Spotify · FLAC). This absorbs *both* the old sort-chip row and the source/FLAC filter row — "lossless only" stays one tap away but never occupies screen.

## 3. Implementation approach (structure)

Replace the `Column` + nested `LazyColumn` with **one `LazyColumn`** that owns the whole surface, using `stickyHeader` for the chips:

```
LazyColumn {
  item { ShuffleHero(...) }                    // scrolls away
  item { RecentlyDownloadedRail(...) }         // scrolls away (hidden if empty)
  stickyHeader { CategoryChipBar(...) }        // pins; grows a shuffle+search+sort strip when stuck
  when (activeChip) {
    Songs    -> items(tracks) { TrackRow(...) }            // full-width rows
    Artists  -> items(artists) { ArtistRow(...) }          // full-width rows, round art
    Playlists-> items(playlistRows) { Row { 2 × PlaylistCard } }   // manual 2-col grid
    Albums   -> items(albumRows) { Row { 2 × AlbumSquareCard } }   // manual 2-col grid
  }
}
```

**Why manual 2-col rows instead of `LazyVerticalGrid`:** a single `LazyColumn` can't nest a `LazyVerticalGrid`, and we need the hero/rail/sticky-chips to share one scroll with the grid content. Chunking Playlists/Albums into rows of two (`list.chunked(2)`) keeps everything in one `LazyColumn` so `stickyHeader` works and the header collapses naturally. This is a standard Compose idiom for mixed list/grid + sticky headers.

**Collapsing header technique (definition):** the hero + rail are ordinary leading items in the scroll, so they translate off-screen as the user scrolls — no custom `nestedScroll` needed. `stickyHeader` re-pins the chips at the top once they reach it. The "grow a shuffle button when stuck" affordance reads the sticky state (first-visible-item index > rail) to expand the bar.

## 4. Component plan

**Reuse (already built):**
- `CrispChipRow` (core:ui, from Home) → the category chips.
- `AlbumSquareCard` (core:ui) → the Albums grid + Recently-downloaded rail cards.
- `TrackListItem` (core:ui) → Songs rows (already used by the current Tracks tab).
- The `LibraryMixesSection` content (ported this session) → folds into the Playlists grid.

**New (small, Premium Crisp):**
- `ShuffleHeroCard` — violet-gradient hero, total song count + "all offline", play FAB → `shuffleLibrary()`. A sibling of `DiscoverHeroCard`; can share styling.
- `RecentlyDownloadedRail` — horizontal `LazyRow` of `AlbumSquareCard`s from `recentlyAdded` (tap = play the track). Hidden when empty.
- `LibrarySortFilterSheet` — the single ⇅ bottom sheet (sort + source/FLAC).
- `ArtistRow` — round-art list row (or restyle the existing `ArtistsGrid` cell into a list row).

**Relocate / remove:**
- `ShuffleLibraryCard` → replaced by `ShuffleHeroCard`.
- `GlassSearchBar` (permanent) → inline, behind 🔍.
- `TabChipRow` → `CrispChipRow` (sticky).
- `SortChipRow` + `SourceFilterChipRow` → into `LibrarySortFilterSheet`.
- **Import** (`FilledTonalButton "Import"`) → a small **+** action in the header row (or an overflow) — must not be lost.

**Preserve unchanged:**
- Multi-select on the Songs list (`SelectionState`, `SelectionScaffoldOverlay`, batch save/delete) — Tracks-only, exactly as today.
- `LocalImportStrip` (import progress) — renders below the header when a SAF import is running.
- All navigation callbacks (`onNavigateToPlaylist/Artist/Album/LikedSongs/MixBuilder`) and the mix-management actions ported this session.

## 5. Data — mostly already there

`LibraryViewModel` / `LibraryUiState` already expose everything: `tracks`, `playlists`, `stashMixes`, `likedPlaylists`, `recentlyAdded`, `artists`, `albums`, `sortOrder`, `sourceFilter`, `searchQuery`, `shuffleLibrary()`. Minimal additions:

- **Total song count** for the hero (derive from `tracks` or a count query).
- **`SortOrder.DURATION`** — new sort option shown in the sheet (small enum + comparator addition).
- `LibraryTab` enum values are reused as the chip identities (rename cosmetic only; keep 4).

No new repositories, no new network, no schema changes. This is a **presentation-layer rework**.

## 6. Premium Crisp adherence

Near-black canvas; violet hero + accents; cyan for the "RECENTLY DOWNLOADED" eyebrow and FLAC/lossless signals; Space Grotesk for the "Library" title, song counts, and durations; Inter for body; frosted sticky chip bar as the single elevation cue; fast 180/260ms motion for the search slide-open and sheet. Matches Home so the two tabs feel like one app.

## 7. Out of scope

- **Sync tab redesign** — the agreed next project after Library.
- Changing what "downloaded/offline" means, the player, or the sync pipeline.
- New sort/browse data beyond the `DURATION` option.

## 8. Testing

- `LibraryViewModel`: default Songs sort = Recently added; `DURATION` comparator; sort/filter applied through the sheet; hero song count; `recentlyAdded` surfaces. (Unit, mockito-kotlin harness already in the module.)
- Screen structure is Compose UI — validated by device smoke (chrome scrolls away, chips stick, shuffle from hero + from the stuck bar, each chip's content renders, search slide-open, sort/filter sheet incl. FLAC-only, recent-downloads rail).
- Reuse the module's existing `--tests` discipline; don't gate on the pre-existing flaky suites.

## 9. Open items for user review

1. **Import placement** — a small **+** in the header, or tucked in an overflow menu? (Default: **+** in the header.)
2. **"Downloads" card in Playlists** — keep it (a one-tap "play everything offline"), or is it redundant with the Songs chip + Shuffle hero? (Default: keep — it's a useful play-all entry.)
3. **Recently-downloaded rail — tap behavior:** play the track immediately (default), or open its album/context?
4. **Rail length** — last ~12 downloads feels right; adjustable.
