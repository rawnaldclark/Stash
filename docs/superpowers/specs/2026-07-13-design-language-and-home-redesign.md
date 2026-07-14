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

**In scope:** the Premium Crisp language (principles + shared component treatments), the Home screen redesign (IA + layout + states), and the **relocation of Home's library content into Library** — which §7 corrects to a genuine UI + ViewModel port (not a "small" change).

**Out of scope (own specs/tasks):** Now Playing redesign (sub-project 2); Library/Search/Settings visual propagation (sub-project 3); and — critically — the **net-new data sources** three Home sections need (see §6), which are dependencies, not part of the UI build.

## 3. The "Premium Crisp" Design Language

The language is a set of rules any screen can inherit. Sub-project 1 defines it; Home is its first application.

**3.1 Precision over decoration.** Beauty comes from spacing, alignment, and type rhythm — not effects. A strict spacing scale (multiples of 4dp; section gaps 20dp, intra-section 8–11dp), consistent 8dp card radii, and hairline dividers only where they earn their place.

**3.2 Restraint in color.** Near-black canvas (`StashBackground #06060C`). **Violet is the single primary accent** — active chip, primary play button, selected states. **Cyan is the signal color** — status (Online) and quality (FLAC) only. Album-art-derived color appears *only* in hero/featured surfaces, never in list rows. This restraint is the premium move; color everywhere reads as MVP.

**3.3 One elevation cue.** Content is flat (cards sit on the canvas). The *only* consistent depth is the **frosted, floating chrome**: the bottom nav (and the docked mini-player) are translucent (~92% surface with a blur, a hairline top border, a soft shadow) and float above scrolling content. Heroes and active/pressed states get a subtle shadow. No glass elsewhere.
> **Component-ownership note (see §8):** the bottom nav lives in `StashScaffold` (in scope). The live mini-player is `feature/nowplaying/MiniPlayer` — a module sub-project 2 owns. In Phase 1 we apply the frosted treatment to the **bottom nav only**; the mini-player's chrome refresh is folded into sub-project 2 to avoid touching that module twice. (`core/ui/MiniPlayerBar` is dead code — no callers — and is NOT the target.)
> **Concrete tokens to pin (net-new, none exist today):** elevation shadows `heroShadow = 8dp`, `chromeShadow = 6dp`, `pressElevation = 2dp`; light-theme frosted uses the existing `StashGlassBackgroundLight`/`StashGlassBorderLight`. Add as named constants in `core:ui` (not ad-hoc dp literals).

**3.4 Strict type hierarchy (3 tiers per screen).** Space Grotesk (SemiBold/Bold) for all titles, section headers, and numerals (ranks, counts); Inter for metadata and body. Per screen: one display/header tier, one title tier (card titles), one meta tier (artist/subtitle). Section headers use `titleLarge`/16sp Space Grotesk SemiBold; card titles `bodyMedium` 12–14sp; metadata `bodySmall` 11–12sp in `textSecondary`.

**3.5 Motion — purposeful and fast.** This is the primary "feels dated" fix. Pin these as named constants in a new `core:ui` `StashMotion` object (none exist today): `durShort = 180ms`, `durMed = 260ms`; `easeEnter = FastOutSlowIn` (decelerate), `easeExit = FastOutLinearIn` (accelerate); `pressScale = 0.97f`; `sectionStagger = 40ms`.
- **Mini-player ⇄ Now Playing (Phase-1 realistic):** keep the existing slide-up/down container transition (already in `StashNavHost`) and add a **cross-fading artwork scale** — the mini art fades as the Now Playing art scales in. A *true* Compose shared-element (`SharedTransitionLayout`/`sharedBounds`) is **deliberately deferred to sub-project 2**: the source (mini-player) lives in the Scaffold `bottomBar` *outside* the `NavHost` while the target is a `NavHost` destination — spanning two subtrees in one `SharedTransitionScope` is net-new (zero `SharedTransition` usage in the repo) and touches the deferred Now Playing artwork. Attempting it here risks rework across a module we're not opening. **Do it in sub-project 2, where both ends are on the table.**
- **Slide + fade** for list → detail (artist/album/playlist) — reuse the existing detail transitions.
- **Press-scale** (`pressScale`) on tappable cards/tiles.
- **Staggered fade-in** of sections on first load (`sectionStagger` between sections).
- No looping/idle animation, no gratuitous parallax. Respect `Reduce Motion` (fall back to instant/opacity-only).

**3.6 Quality signals as identity.** FLAC badge on lossless art, a Qobuz mark on curated Qobuz cards, a source dot where sources differ. These are both useful and on-brand (Stash is the lossless app) — they replace decoration as the thing that makes the UI feel considered.

**3.7 Light theme.** The existing cream/lavender palette applies unchanged; the crisp *layout* is theme-agnostic. Frosted chrome uses the light glass tokens. In scope for parity, not a separate design.

## 4. Home — Information Architecture

Home becomes a **pure discovery surface**. Library content leaves.

**Removed from Home** (→ Library): Daily Mixes, the other Stash Mixes, Recently Added, the Playlists grid / Create-playlist / Create-mix, Liked Songs card. These are *your* content and belong in Library (§7). **Exception:** the builtin **Daily Discover** Stash Mix is *promoted* to the Home hero (§6 finding) and is therefore **excluded from the relocated mix set** — it appears as the Home hero, not in Library's mix list.

**New Home order** (personalized → editorial/charts):
1. **Top bar** — STASH wordmark · Online/Offline chip · Settings.
2. **Filter chips** — dynamically built from the **populated** section types only (never a static list). "For You" is always present and default (= full mixed scroll); a type chip (New / Albums / Playlists / Charts) appears **only when its section has data**, and taps scroll/filter to it. This prevents a chip leading to a blank feed in Phase 1 or offline (finding #11). Active chip is violet.
3. **Discover hero** — the single bold "for you" moment: the **materialized Daily Discover playlist** (a builtin `STASH_MIX`), full-width, art-gradient background; the play button plays that playlist (not "the discovery queue").
4. **New releases for you** — horizontal album row, FLAC-badged.
5. **Albums made for you** — horizontal album row (personalized recommendations).
6. **Curated by Qobuz** — horizontal playlist row, Qobuz-marked. *(Phase 2 — needs a data source; §6.)*
7. **Top albums this week** — ranked list (Space Grotesk numerals, movement arrows). *(Phase 2 — needs a data source + rank persistence; §6.)*
8. **Docked chrome** — frosted bottom nav (Home · Library · Search · Sync · Settings) + the existing mini-player (chrome refresh in sub-project 2, §3.3).

**Chip→section mapping (pin, finding #13):** For You → all sections; New → New releases; Albums → Albums made for you **and** Top albums; Playlists → Curated by Qobuz; Charts → Top albums. A chip is shown only if ≥1 of its mapped sections is populated.

**Explicitly NOT on Home:** "recently played / jump back in" (that's Library; confirmed cut in brainstorming).

## 5. Home — Section & State Specs

Each section is a self-contained composable fed by a slice of `HomeUiState`. All rows are horizontally scrollable `LazyRow`s inside the outer `LazyColumn`.

- **Chips** (`HomeFilterChips`): built from populated section types (§4); `For You` default. Non-"For You" chips filter the visible sections to their mapped type (client-side; no refetch).
- **Discover hero** (`DiscoverHeroCard`): title, subtitle (track count + "updated daily"), play → **plays the materialized Daily Discover playlist** (`STASH_MIX` builtin), not the raw discovery queue. Art-gradient derived from the playlist cover.
- **Album rows** (`AlbumRow` reusing `AlbumSquareCard`): 104dp tiles, title (Space Grotesk) + artist (Inter), FLAC badge overlay when lossless. "See all" → **a net-new see-all list screen** (`SeeAllRoute(sectionType)`); it does not exist today (finding #14). Individual album taps route by origin: a *local* album → `AlbumDetailRoute(name, artist)`; a *Qobuz/new-release* album → Search's `SearchAlbumRoute(browseId, source)`.
- **Qobuz playlists** (`PlaylistRow`): playlist cover + name + subtitle, Qobuz badge.
- **Top albums** (`RankedAlbumList`): numbered rows (rank in Space Grotesk `textTertiary`), 42dp art, title/artist, movement arrow (cyan ▲ / tertiary —).
- **New Home nav callbacks (net-new, finding #15):** `HomeScreen` today exposes only `onNavigateToPlaylist/LikedSongs/Settings/MixBuilder`. This redesign adds `onOpenAlbum(local)`, `onOpenSearchAlbum(browseId, source)`, `onOpenArtist`, `onOpenQobuzPlaylist`, `onSeeAll(sectionType)` — all wired in `StashNavHost` and the `HomeScreen` signature.
- **States** — each section renders independently:
  - *Loading:* shimmer skeletons matching each section's shape (reuse `ShimmerPlaceholder`/`ArtistProfileSkeletons` pattern). Sections fade in per §3.5 as their data resolves.
  - *Empty (a section has no data):* the section is **omitted entirely** (no empty headers), mirroring the About-section gate. Its chip is also hidden (§4). Home never shows a broken/empty row.
  - *Cold start (finding #12):* a brand-new user (no Last.fm connection, thin library) will have most personalized sections omit. Home must **not** collapse to near-blank. Define a cold-start fallback: when < 2 sections are populated, show (a) a compact **"Personalize your Home"** card prompting connect-Last.fm / run a sync, and (b) whatever editorial/charts sections exist (these are non-personalized and populate for everyone once their Phase-2 data lands). Until Phase-2 charts exist, the personalize card + Discover (which works from any synced library) is the cold-start floor.
  - *Offline:* network-backed sections collapse; locally-cacheable ones (Daily Discover playlist, last-fetched rows) show stale content with the Online/Offline chip reflecting state. Home renders something useful offline, never a blank error screen.
  - *Error (per section):* best-effort — a failed section is omitted, never blocks the others (same contract as `ArtistCache`'s supplements).

## 6. Data provenance & phasing (the load-bearing constraint)

The design is only as good as its content. Current backend readiness per section:

| Section | Source | Readiness |
| --- | --- | --- |
| Discover hero | the **builtin Daily Discover `STASH_MIX`** (`StashMixDefaults`), materialized into a playlist by the recipe/discovery pipeline. The `DiscoveryQueueDao` is a *candidate-download queue* feeding mixes — the *playable* object is the materialized playlist. | ✅ **exists** (plays the materialized playlist; §5) |
| Albums made for you | Last.fm `user.getTopArtists` → their albums (Qobuz/YT resolve) + discovery queue | 🟡 **assemblable** — but **precondition:** needs a *connected Last.fm username* (no "followed artists" concept exists; only liked songs / `getAllArtists()` as a fallback seed). Needs an aggregation layer. |
| New releases for you | liked/known artists' latest albums via qbdlx `getArtistAlbums` (year-filter). No "featured new releases" endpoint. | ⚠️ **partial** — derivable, but it's an **N×2 qbdlx call fan-out** (searchArtists → getArtistAlbums per artist) against the small 4-token qbdlx pool. Cache aggressively; bound the artist count per load. |
| Curated by Qobuz | — | ❌ **net-new** — qbdlx exposes only search/artist/album/getFileUrl; no playlist/featured/editorial endpoint. Needs a new data source (a Qobuz browse endpoint if qbdlx can add it, or another provider). |
| Top albums (charts) | Last.fm has **no `chart.*` and no `album.*` methods at all** (verified); no global album chart in the public API. qbdlx has no chart. | ❌ **net-new** — needs a new album-chart source **and** week-over-week **rank persistence** (the ▲/— movement arrows require storing last week's ranks; nothing does this today). |

**Phasing recommendation (to keep this shippable):**
- **Phase 1 (this sub-project's build):** the Premium Crisp language + the Home *shell* + all shared components + motion + the sections whose data is ready or assemblable — **Discover** (✅), **Albums made for you** (🟡, when Last.fm connected — else it omits and the cold-start card shows, §5), **New releases** (⚠️, liked-artist-derived, bounded/cached). The §5 empty-omit + cold-start rules make a partial Home look intentional, not broken.
- **Phase 2 (separate specs, each its own data spike):** **Curated Qobuz playlists** (needs a browse/editorial source) and **Top albums charts** (needs an album-chart source **and** rank-persistence storage). They slot into the finished Home shell as their data lands — no UI rework, because the §5 section contract already exists. Their chips appear only once populated (§4).

This phasing is the recommended default; the alternative (block the whole redesign until all data sources exist) trades a shippable beautiful Home now for a longer, riskier single push. **This is the key decision for review.**

## 7. Library — receiving the mixes (a real port, not a small change)

**Scope reality (corrected per review, findings #5–#7):** Home isn't "trimmed" — `HomeScreen.kt` (~1843 lines) and `HomeViewModel.kt` (~705 lines) are *mostly* the content being removed, so this is a near-total Home rewrite plus a genuine **logic port** into Library, not a cosmetic move. What moves:

- **UI composables** (Home → Library): `DailyMixCard`, `PlaylistGridCard`, `CreatePlaylistCard`, `CreateMixCard`, `LikedSongsCard`, `MixesSectionHeader`, and the mix/playlist **action `ModalBottomSheet`**.
- **ViewModel logic** (`HomeViewModel` → `LibraryViewModel`): `refreshMix`, `refreshMixIfStale`, `deleteCustomMix` + `previewPlaylistDelete` (cascade), `editRecipeId`, `createPlaylist`, `queue/removeDownloadsForPlaylist`, `playAllMixes`, `playLikedSongs`, the `buildingMixIds`/`emptyMixIds` computation, and the **WorkManager unique-work observation** for mix builds.
- **DAO injections** (`LibraryViewModel` currently has none of these): `recipeDao`, `discoveryQueueDao`, and whatever `HomeViewModel` uses for mix refresh.

**Overlap to resolve (finding #6):** Library **already** renders mixes — `LibraryViewModel.uiState.playlists = getAllPlaylists()` includes all playlist types, and `LibraryScreen` has a `PLAYLISTS` tab already grid-rendering them (custom mixes, Daily/Stash Mixes, Liked). So a naive "add a Mixes group" would *duplicate* them. **Decision to pin:** introduce an explicit **Mixes** grouping in Library (Daily + Stash + custom mixes) and have the existing Playlists tab render **user playlists only** (filter out `STASH_MIX`/mix types), so each item appears once. Recently Added moves to Library as its own block. Daily Discover is excluded (it's the Home hero, §4).

**Not entangled (finding #7 — in our favor):** mix *generation* (recipes, discovery workers, `StashMixDefaults`, DAOs) lives entirely in `core:data`, independent of Home's UI. So the cost here is the **UI + ViewModel port**, not the generation machinery — the plan should not over-fear breaking mix creation.

This spec covers the relocation into Library's existing structure/components; it is **not** a visual redesign of Library (that's sub-project 3). All relocated behavior must be preserved.

## 8. Android / Compose mapping

- **Design language** lives in `core:ui`:
  - Extend `theme/` (no palette change): new `StashMotion` object (durations/easings, §3.5) and named elevation/shadow constants (§3.3). `core/ui/MiniPlayerBar.kt` is **dead code (no callers)** — do not target it.
  - New/refined shared components: `HomeFilterChips`, `RankedAlbumList`, `DiscoverHeroCard`, treatment updates to `AlbumSquareCard`. Reuse `ShimmerPlaceholder`, `SectionHeader`, `FlacBadge`, `SourceIndicator`.
  - **Frosted chrome:** the bottom nav in `app/.../navigation/StashScaffold.kt` gets the frosted treatment in Phase 1. The live mini-player is `feature/nowplaying/MiniPlayer.kt` — its chrome refresh is **deferred to sub-project 2** (§3.3) to avoid opening that module here.
- **Home** in `feature:home`:
  - `HomeScreen.kt` restructured to the §4 order; each section a focused composable in `feature/home/.../sections/`. This is a near-total rewrite (the current file is mostly relocated content, §7).
  - `HomeViewModel`/`HomeUiState` reshaped to expose per-section slices + independent loading/empty flags + the cold-start signal; drop the library-content flows (ported to Library, §7).
  - New nav callbacks in the `HomeScreen` signature + `StashNavHost` (§5): album/search-album/artist/qobuz-playlist/see-all.
  - New route: `SeeAllRoute(sectionType)` (net-new list screen, §5).
- **Library** in `feature:library`: receives the ported mixes/playlists/recently-added UI + the ViewModel logic + DAO injections (§7), with the Playlists-tab dedup resolved.
- **Motion**: Phase-1 mini-player ⇄ Now Playing = existing slide + cross-fading art scale (`StashNavHost`); per-section stagger in `HomeScreen`. The true `SharedTransitionLayout` shared-element is **sub-project 2** (§3.5).

## 9. Testing & verification

- **Unit:** `HomeViewModel` state assembly (per-section slices; a failing/empty section omits, never crashes; offline path yields a non-empty renderable state); chip-filter logic (pure); any new pure mappers.
- **Component behavior:** section-omit gates (empty → nothing rendered), FLAC/Qobuz badge conditions, ranked-list ordering.
- **On-device smoke (human):** Home renders the ready sections with real data; a source outage omits its section (and its chip) cleanly; a cold-start user sees the personalize card, not a blank screen; offline shows cached/graceful content; motion (slide + cross-fading art scale to Now Playing, section stagger) feels fast and correct; the Library port preserves mix create/refresh/delete + no duplicated mixes across the Mixes group and Playlists tab; light theme parity.
- Motion and visual polish are validated on-device, not by unit tests.

## 10. Scope boundaries & follow-ups

- **This spec:** Premium Crisp language + Home shell + Phase-1 sections (Discover, Albums made for you, New releases) + the Library port.
- **Deferred to sub-project 2 (Now Playing):** the true `SharedTransitionLayout` mini-player ⇄ Now Playing shared-element (§3.5) and the mini-player's frosted-chrome refresh (§3.3) — both open the `feature:nowplaying` module, so they ride with its redesign rather than touching it twice.
- **Deferred (own specs/spikes):** Library/Search/Settings propagation (sub-project 3); Phase-2 Home data sources — **Curated Qobuz playlists** (needs a Qobuz browse/editorial source) and **Top albums charts** (needs an album-chart source **and** week-over-week rank-persistence storage for the movement arrows). Each lights up its Home section (and chip) without UI rework once its data lands.
- **Non-goals:** rebrand, palette/font change, Now Playing visuals, and any screen not named here.
