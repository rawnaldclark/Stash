# Premium Crisp Design Language + Home Redesign — Design

**Status:** Approved in brainstorming (visual companion); pending spec review
**Date:** 2026-07-13
**Branch:** `feat/design-language-home-redesign`
**Sub-project 1 of a multi-part UI/UX modernization.** Siblings (separate specs): (2) Now Playing redesign, (3) propagate the language to Library/Search/Settings.

## 1. Problem & Goal

The app's visual foundation is strong (Space Grotesk + Inter, a committed near-black canvas, a violet/cyan brand, a glass system, a non-generic cream/lavender light theme). What reads as "dated / MVP" lives *above the tokens*: (a) information architecture — Home is a flat stack of near-identical carousels that mixes *library* content into a *discovery* surface; (b) layout/hierarchy — uniform cards, no editorial rhythm; (c) motion/depth — little spatial continuity; (d) the two signature screens.

This sub-project establishes a reusable **"Premium Crisp"** design language and applies it to the **Home** screen, re-cast as a pure discovery surface. It is explicitly a *keep-and-elevate* of the existing brand identity, not a rebrand.

**Direction chosen (visual companion):** "Crisp Utilitarian," elevated. Dense, fast, scannable — Spotify-grade efficiency — but lifted above a clone by *precision*: exact spacing, strict type rhythm, restrained color, quality signals (FLAC/Qobuz marks), ranked charts. Atmosphere/glass is deliberately *reserved* for the Now Playing screen (sub-project 2), giving a coherent system: efficient browse, cinematic playback.

## 2. Scope

**In scope:** the Premium Crisp language (principles + shared component treatments), the Home screen redesign (IA + layout + states), and the small Library change to receive the mixes that leave Home.

**Out of scope (own specs/tasks):** Now Playing redesign (sub-project 2); Library/Search/Settings visual propagation (sub-project 3); and — critically — the **net-new data sources** three Home sections need (see §6), which are dependencies, not part of the UI build.

## 3. The "Premium Crisp" Design Language

The language is a set of rules any screen can inherit. Sub-project 1 defines it; Home is its first application.

**3.1 Precision over decoration.** Beauty comes from spacing, alignment, and type rhythm — not effects. A strict spacing scale (multiples of 4dp; section gaps 20dp, intra-section 8–11dp), consistent 8dp card radii, and hairline dividers only where they earn their place.

**3.2 Restraint in color.** Near-black canvas (`StashBackground #06060C`). **Violet is the single primary accent** — active chip, primary play button, selected states. **Cyan is the signal color** — status (Online) and quality (FLAC) only. Album-art-derived color appears *only* in hero/featured surfaces, never in list rows. This restraint is the premium move; color everywhere reads as MVP.

**3.3 One elevation cue.** Content is flat (cards sit on the canvas). The *only* consistent depth is the **frosted, floating chrome**: the mini-player and bottom nav are translucent (`backdrop-filter` blur, ~92% surface) and float above scrolling content with a hairline top border and soft shadow. Heroes and active/pressed states get a subtle shadow. No glass elsewhere.

**3.4 Strict type hierarchy (3 tiers per screen).** Space Grotesk (SemiBold/Bold) for all titles, section headers, and numerals (ranks, counts); Inter for metadata and body. Per screen: one display/header tier, one title tier (card titles), one meta tier (artist/subtitle). Section headers use `titleLarge`/16sp Space Grotesk SemiBold; card titles `bodyMedium` 12–14sp; metadata `bodySmall` 11–12sp in `textSecondary`.

**3.5 Motion — purposeful and fast (200–300ms).** This is the primary "feels dated" fix:
- **Shared-element art expand** for mini-player ⇄ Now Playing (the artwork is the continuous element).
- **Slide + fade** for list → detail (artist/album/playlist).
- **Press-scale** (~0.97) on tappable cards/tiles.
- **Staggered fade-in** of sections on first load (each section fades/rises ~40ms after the previous).
- Easing: standard decelerate for enters, accelerate for exits. No looping/idle animation, no gratuitous parallax. Respect `Reduce Motion` (fall back to instant/opacity-only).

**3.6 Quality signals as identity.** FLAC badge on lossless art, a Qobuz mark on curated Qobuz cards, a source dot where sources differ. These are both useful and on-brand (Stash is the lossless app) — they replace decoration as the thing that makes the UI feel considered.

**3.7 Light theme.** The existing cream/lavender palette applies unchanged; the crisp *layout* is theme-agnostic. Frosted chrome uses the light glass tokens. In scope for parity, not a separate design.

## 4. Home — Information Architecture

Home becomes a **pure discovery surface**. Library content leaves.

**Removed from Home** (→ Library): Daily Mixes, Stash Mixes, Recently Added, the Playlists grid / Create-playlist. These are *your* content and belong in Library (§7).

**New Home order** (personalized → editorial/charts):
1. **Top bar** — STASH wordmark · Online/Offline chip · Settings.
2. **Filter chips** — `For You · New · Albums · Playlists · Charts`. Horizontal, scroll-snapped; the active chip is violet. Chips *scope the same feed* (For You = full mixed scroll; others jump/filter to that section type). Purpose: one Home serves many intents and feels fast. (Decision confirmed in brainstorming: keep.)
3. **Discover hero** — the single bold "for you" moment: the tailored daily discovery, full-width, art-gradient background, one round play button.
4. **New releases for you** — horizontal album row, FLAC-badged.
5. **Albums made for you** — horizontal album row (personalized recommendations).
6. **Curated by Qobuz** — horizontal playlist row, Qobuz-marked.
7. **Top albums this week** — ranked list (Space Grotesk numerals, movement arrows).
8. **Docked chrome** — frosted mini-player + bottom nav (Home · Library · Search · Sync · Settings).

**Explicitly NOT on Home:** "recently played / jump back in" (that's Library; confirmed cut in brainstorming).

## 5. Home — Section & State Specs

Each section is a self-contained composable fed by a slice of `HomeUiState`. All rows are horizontally scrollable `LazyRow`s inside the outer `LazyColumn`.

- **Chips** (`HomeFilterChips`): stateful selection; `For You` default. Non-"For You" chips filter the visible sections to that content type (client-side; no refetch).
- **Discover hero** (`DiscoverHeroCard`): title, subtitle (count + "updated daily"), play → starts the discovery queue. Art-gradient derived from the discovery cover.
- **Album rows** (`AlbumRow` reusing `AlbumSquareCard`): 104dp tiles, title (Space Grotesk) + artist (Inter), FLAC badge overlay when lossless. "More" affordance → a full-list detail screen (reuses the existing detail-view pattern).
- **Qobuz playlists** (`PlaylistRow`): playlist cover + name + subtitle, Qobuz badge.
- **Top albums** (`RankedAlbumList`): numbered rows (rank in Space Grotesk `textTertiary`), 42dp art, title/artist, movement arrow (cyan ▲ / tertiary —).
- **States** — each section renders independently:
  - *Loading:* shimmer skeletons matching each section's shape (reuse `ShimmerPlaceholder`/`ArtistProfileSkeletons` pattern). Sections fade in per §3.5 as their data resolves.
  - *Empty (a section has no data):* the section is **omitted entirely** (no empty headers), mirroring the About-section gate. Home never shows a broken/empty row.
  - *Offline:* sections whose source needs network collapse; any locally-cacheable section (last-fetched Discover, cached charts) shows stale content with the Online/Offline chip reflecting state. Home must render something useful offline, never a blank error screen.
  - *Error (per section):* best-effort — a failed section is omitted, never blocks the others (same contract as `ArtistCache`'s supplements).

## 6. Data provenance & phasing (the load-bearing constraint)

The design is only as good as its content. Current backend readiness per section:

| Section | Source | Readiness |
| --- | --- | --- |
| Discover hero | existing discovery engine (`DiscoveryQueueDao`, recipe pipeline) | ✅ **exists** |
| Albums made for you | Last.fm `user.getTopArtists` → their albums (Qobuz/YT resolve) + discovery queue | 🟡 **assemblable** from wired sources; needs an aggregation layer |
| New releases for you | followed/liked artists' latest albums via qbdlx `getArtistAlbums` (filter by year), OR a Qobuz "new releases" feed | ⚠️ **partial** — derivable from liked artists; no true "featured new releases" endpoint |
| Curated by Qobuz | — | ❌ **net-new** — qbdlx exposes only artist/album/search; no playlist/featured/editorial endpoint. Needs a new data source (a Qobuz browse endpoint if qbdlx can add it, or another provider). |
| Top albums (charts) | Last.fm has `chart.getTopArtists`/`getTopTracks` (not in client yet) but **no album chart**; Qobuz could | ⚠️ **partial/net-new** — needs a new fetch and an album-granularity source |

**Phasing recommendation (to keep this shippable):**
- **Phase 1 (this sub-project's build):** the Premium Crisp language + the Home *shell* + all shared components + motion + the sections whose data is ready or assemblable — **Discover, Albums made for you**, and **New releases** (liked-artists-derived). Sections with no source render nothing (the §5 empty-omit rule makes a partial Home look intentional, not broken).
- **Phase 2 (separate specs, each its own data spike):** **Curated Qobuz playlists** (needs a browse/editorial source) and **Top albums charts** (needs an album-chart source). They slot into the finished Home shell as their data lands — no UI rework, because the section contract already exists.

This phasing is the recommended default; the alternative (block the whole redesign until all five data sources exist) trades a shippable beautiful Home now for a longer, riskier single push. **This is the key decision for review.**

## 7. Library — receiving the mixes (small, required)

Home loses Daily Mixes / Stash Mixes / Recently Added / Playlists; they must land somewhere. Library gains a top section grouping: **Mixes** (Daily + Stash), and the existing **Playlists** and **Recently Added** views stay/move there. This spec covers *relocating* these blocks into Library's existing structure using the same components (no redesign of Library itself — that's sub-project 3). The move must preserve all current behavior (mix generation, playlist create, etc.).

## 8. Android / Compose mapping

- **Design language** lives in `core:ui`:
  - Extend `theme/` (no palette change) with any new semantic tokens (elevation/shadow constants, motion durations/easings as a `StashMotion` object).
  - New/refined shared components as needed: `HomeFilterChips`, `RankedAlbumList`, and treatment updates to `AlbumSquareCard`, `MiniPlayerBar`, and the bottom nav (frosted chrome). Reuse `ShimmerPlaceholder`, `SectionHeader`, `FlacBadge`, `SourceIndicator`.
- **Home** in `feature:home`:
  - `HomeScreen.kt` restructured to the §4 order; each section a focused composable in `feature/home/.../sections/`.
  - `HomeViewModel`/`HomeUiState` reshaped to expose per-section slices + independent loading/empty flags; drop the library-content flows (moved to Library).
- **Library** in `feature:library`: add the relocated mixes/playlists/recently-added blocks.
- **Motion**: shared-element art transition wired at the nav layer (`StashNavHost`) for mini-player ⇄ Now Playing; per-section stagger in `HomeScreen`.

## 9. Testing & verification

- **Unit:** `HomeViewModel` state assembly (per-section slices; a failing/empty section omits, never crashes; offline path yields a non-empty renderable state); chip-filter logic (pure); any new pure mappers.
- **Component behavior:** section-omit gates (empty → nothing rendered), FLAC/Qobuz badge conditions, ranked-list ordering.
- **On-device smoke (human):** Home renders the ready sections with real data; a source outage omits its section cleanly; offline shows cached/graceful content; motion (art expand to Now Playing, section stagger) feels fast and correct; light theme parity.
- Motion and visual polish are validated on-device, not by unit tests.

## 10. Scope boundaries & follow-ups

- **This spec:** Premium Crisp language + Home shell + Phase-1 sections + the Library mixes relocation.
- **Deferred (own specs):** Now Playing redesign (sub-project 2); Library/Search/Settings propagation (sub-project 3); Phase-2 Home data sources — **Curated Qobuz playlists** and **Top albums charts** — each needs its own data-source design/spike before its section can light up.
- **Non-goals:** rebrand, palette/font change, Now Playing, and any screen not named here.
