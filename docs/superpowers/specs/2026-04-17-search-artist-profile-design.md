# Search Tab Overhaul — Artist Profile + Multi-Category Results

**Date:** 2026-04-17
**Status:** Design — approved to proceed to implementation plan
**Scope:** `feature/search`, `core/data` (one new Room table), `data/download/preview` (concurrency + race), `app/navigation` (one new route).

---

## 1. Goal & Scope

Transform the current flat track-list Search tab into a Spotify-style sectioned experience with a dedicated **Artist Profile** screen, while holding every user-visible interaction to latency targets that feel native.

**In scope:**

- Multi-category search results (Top result · Songs · Artists · Albums) on the existing `SearchScreen`.
- New `ArtistProfileScreen` reachable from Search, showing: hero, Popular, Albums row, Singles & EPs row, Fans-also-like row.
- Reuse of the existing `AlbumDetailRoute` for album taps from Search and the Artist Profile.
- A layered performance contract (see §4) that governs implementation choices.

**Out of scope (explicit non-goals):**

- Typeahead / autocomplete suggestions while typing.
- Recent-searches history on empty state.
- In-app artist "bio" / About section.
- Music-video shelves.
- Spotify-API enrichment (follower count, genres, verified badges) — the profile is sourced entirely from YouTube Music InnerTube.

---

## 2. Information Architecture

Three screens, one new navigation route.

```
SearchRoute                             (existing, redesigned)
  ├── tap artist row/avatar ──> SearchArtistRoute(artistId, name, avatarUrl?)   (new)
  │                              └── tap album card  ──> AlbumDetailRoute(...)  (existing)
  │                              └── tap track       ──> play preview / download in place
  │                              └── tap related     ──> SearchArtistRoute(...) (navigate)
  └── tap album card       ──> AlbumDetailRoute(...)                              (existing)
```

**Search screen sections (in order):**

1. **Top result** — one tall card (artist or track, whichever InnerTube's `musicShelfRenderer` flagged as top). Tap opens profile or plays preview.
2. **Songs** — up to 4 inline track rows. "See all" link only if more exist.
3. **Artists** — horizontal `LazyRow` of circular `ArtistAvatarCard`s.
4. **Albums** — horizontal `LazyRow` of `AlbumSquareCard`s.

**Artist Profile sections (in order):**

1. Hero — purple radial wash, circular avatar (96 dp), name (Space Grotesk 28 sp), subscribers line, glass chips: **Shuffle** + **Download all popular**.
2. **Popular** — top 10 tracks (vertical list, existing track row composable).
3. **Albums** — horizontal carousel.
4. **Singles & EPs** — horizontal carousel.
5. **Fans also like** — horizontal carousel of related artist avatars.

---

## 3. Data Sources & Flow

### 3.1 Data source decision

YouTube Music (InnerTube) is the **sole** data source for this feature. Rationale: the app downloads YouTube audio; what the user sees on a profile is exactly what is downloadable. No Spotify client work needed.

### 3.2 New client methods on `YTMusicApiClient`

Both live in `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt`. Both `suspend`, `Dispatchers.IO`, wrap network in `try/catch` → `Result`.

```kotlin
// Replaces the narrow HybridSearchExecutor path for Search tab usage;
// existing HybridSearchExecutor remains for download-match internals.
suspend fun searchAll(query: String): SearchAllResults

suspend fun getArtist(browseId: String): ArtistProfile
```

Both wrap `InnerTubeClient.search(...)` and `InnerTubeClient.browse(browseId)`. The `browse` response returns the entire artist page — header, popular shelf, albums shelf, singles shelf, related shelf — in **one** network round trip.

### 3.3 DTOs

```kotlin
sealed interface SearchResultSection {
    data class Top(val item: TopResultItem) : SearchResultSection
    data class Songs(val tracks: List<TrackSummary>) : SearchResultSection
    data class Artists(val artists: List<ArtistSummary>) : SearchResultSection
    data class Albums(val albums: List<AlbumSummary>) : SearchResultSection
}

sealed interface TopResultItem {
    data class ArtistTop(val artist: ArtistSummary) : TopResultItem
    data class TrackTop(val track: TrackSummary) : TopResultItem
}

data class SearchAllResults(val sections: List<SearchResultSection>)

data class ArtistSummary(val id: String, val name: String, val avatarUrl: String?)
data class AlbumSummary(val id: String, val title: String, val artist: String,
                        val thumbnailUrl: String?, val year: String?)
data class TrackSummary(val videoId: String, val title: String, val artist: String,
                        val album: String?, val durationSeconds: Double,
                        val thumbnailUrl: String?)

data class ArtistProfile(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val subscribersText: String?,  // "12K subscribers" — raw string from InnerTube
    val popular: List<TrackSummary>,
    val albums: List<AlbumSummary>,
    val singles: List<AlbumSummary>,
    val related: List<ArtistSummary>,
)
```

### 3.4 Caching — Stale-While-Revalidate

**`ArtistCache` (`@Singleton`)** in `core/data/src/main/kotlin/com/stash/core/data/cache/ArtistCache.kt`:

- Memory: `LinkedHashMap<String, Entry>` capped at 20 (LRU).
- Disk: one Room table `artist_profile_cache(artistId TEXT PK, json TEXT NOT NULL, fetchedAt INTEGER NOT NULL)`.
- TTL: **6 hours**. Entries older than TTL are returned as stale with `isStale = true` and a refresh is scheduled.
- API: `fun get(id: String): Flow<CachedProfile>` — emits cached first (with freshness flag), then live once refreshed.

Migration: Room schema version bump + a single `CREATE TABLE` migration. No backfill; the cache starts empty.

### 3.5 Navigation

New route in `app/navigation/StashNavHost.kt`:

```kotlin
@Serializable
data class SearchArtistRoute(
    val artistId: String,
    val name: String,
    val avatarUrl: String? = null,
)
```

The destination ViewModel reads these nav args, **paints hero immediately from them**, then kicks off the cache fetch.

---

## 4. Performance Contract

Latency targets are **hard requirements**; any implementation that misses these fails review.

### 4.1 Targets

| Interaction | Target (p50) | Target (p95) |
|-------------|--------------|--------------|
| Keystroke → skeleton rows visible | < 100 ms | < 200 ms |
| Debounced query → first real results | < 900 ms | < 1500 ms |
| Tap artist → hero (name+avatar) visible | < 50 ms | < 100 ms |
| Tap artist → full profile painted | < 1 s | < 2 s |
| Tap artist (SWR-cached) → full profile | < 50 ms | < 100 ms |
| Tap preview on Popular → audible | < 500 ms | < 3 s |
| Scroll Albums/Singles row | 60 fps | 60 fps |

### 4.2 Mandatory Implementation Measures

1. **Skeletons everywhere.** `SearchScreen` renders 6 shimmer rows at the first keystroke, not at debounce resolution. `ArtistProfileScreen` renders hero skeleton + 5 popular shimmers + 1 albums-row shimmer before `browse` resolves. New primitive `ShimmerPlaceholder(shape, modifier)` in `core/ui`.
2. **Nav-arg hydration.** `SearchArtistRoute` carries `artistId`, `name`, `avatarUrl` — the hero paints from these in the first frame, then reconciles when data arrives.
3. **SWR artist cache** (see §3.4). Memory + disk; re-entries are instant.
4. **Split concurrency for stream-URL extraction.** `PreviewUrlExtractor` gains two internal `Semaphore`s: `innertubeConcurrency = 8`, `ytdlpConcurrency = 2`. InnerTube is IO-bound and safe to fan out; yt-dlp stays narrow.
5. **Race InnerTube ∥ yt-dlp** in `extractStreamUrl` on cache miss. Use `coroutineScope { async/async + select }`; whichever returns a usable URL first wins; cancel the other.
6. **Pre-extract Popular tracks on Artist Profile load.** `PreviewPrefetcher` (new, feature-scoped) takes the Popular list and warms `previewUrlCache` via the semaphored path.
7. **Viewport prefetch** on Albums / Singles / Related rows. Use `LazyListState.layoutInfo.visibleItemsInfo` + ±3 look-ahead. Prefetch each visible row's first track's stream URL.
8. **Global Coil `ImageLoader`** installed via Hilt. Memory 25% of max heap, disk 250 MB, `crossfade(false)` on search rows (keep crossfade on hero for polish). Use YouTube's URL size knobs: avatars `=w96-h96`, album cards `=w300-h300`, hero `=w1024-h1024`.
9. **Preview-tuned `LoadControl`.** Separate ExoPlayer instance for preview with `minBufferMs=1000`, `bufferForPlaybackMs=250`, `bufferForPlaybackAfterRebufferMs=500`. Shaves ~1–1.5 s off start-to-audio vs the default long-form settings.
10. **OkHttp connection warming** at app start — one HEAD request to `music.youtube.com` during the first 2 s of launch to prime TLS + DNS. Saves ~300 ms on first search.
11. **Search debounce 300 ms** (down from 500). Use `flatMapLatest` to auto-cancel prior work on new keystroke.
12. **Timing instrumentation.** Wrap every target-bearing operation in `SystemClock.elapsedRealtime` bookends, log at `DEBUG` under tag `Perf`. Non-release builds only.

### 4.3 Non-Goals

- No speculative prefetch of every row's stream URL (battery).
- No background yt-dlp invocations outside explicit user intent and Popular pre-extract.
- No offline-mode enhancements.

---

## 5. Screens & Components

All files under `feature/search/src/main/kotlin/com/stash/feature/search/` unless noted.

### 5.1 Search screen (existing, refactored)

`SearchScreen.kt` — replaces the flat `LazyColumn<Track>` with a sectioned list:

- `TopResultCard(item, onClick)`
- `SectionHeader(title, onSeeAll: (() -> Unit)?)`
- inline track rows (existing composable, unchanged)
- `LazyRow` of `ArtistAvatarCard(artist, onClick)`
- `LazyRow` of `AlbumSquareCard(album, onClick)`

`SearchViewModel.kt` — refactored to drive `SearchUiState` via a single `SearchStatus` sealed hierarchy (`Idle | Typing | Loading | Results(List<SearchResultSection>) | Empty | Error`). Query-flow is `queryFlow.debounce(300).distinctUntilChanged().flatMapLatest { client.searchAll(it) }`. Exposes `userMessages: SharedFlow<String>`.

### 5.2 Artist Profile (new)

- `ArtistProfileScreen.kt` — top-level destination composable; collects `ArtistProfileUiState`; hosts Snackbar.
- `ArtistProfileViewModel.kt` — constructor reads `SearchArtistRoute` nav args, pre-seeds `ArtistProfileUiState.hero` from them, subscribes to `ArtistCache.get(id)`, kicks `PreviewPrefetcher` once Popular resolves.
- `ArtistHero.kt` — purple radial wash, circular avatar, name, subscribers line, glass chips (Shuffle, Download all).
- `PopularTracksSection.kt` — 10 rows; reuses the existing preview+download row composable.
- `AlbumsRow.kt`, `SinglesRow.kt` — horizontal `LazyRow` of `AlbumSquareCard`.
- `RelatedArtistsRow.kt` — horizontal `LazyRow` of `ArtistAvatarCard`.

### 5.3 New Reusable Primitives

`core/ui/src/main/kotlin/com/stash/core/ui/components/`:

- `ShimmerPlaceholder(shape, modifier)` — animated alpha gradient.
- `ArtistHeroSkeleton()`, `PopularListSkeleton()`, `AlbumsRowSkeleton()` — composed from `ShimmerPlaceholder`.
- `ArtistAvatarCard(name, avatarUrl, modifier, onClick)` — circular Coil image + centered name below.
- `AlbumSquareCard(title, artist, thumbnailUrl, year, modifier, onClick)` — 140 dp square + text.
- `SectionHeader(title, onSeeAll, modifier)` — section header used by both Search and Artist Profile.

### 5.4 New Feature-Scoped Services

- `PreviewPrefetcher` (`feature/search`) — public API `fun prefetch(videoIds: List<String>)` and `fun prefetchVisible(listState: LazyListState, items: List<TrackSummary>)`. Routes into `PreviewUrlExtractor` with its new semaphores; populates the existing `previewUrlCache`.

### 5.5 Changes Outside `feature/search`

- `data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt` — split `Semaphore`s, add InnerTube∥yt-dlp race, unchanged public API.
- `core/data/src/main/kotlin/com/stash/core/data/db/entity/ArtistProfileCacheEntity.kt` + DAO — new Room entity for the SWR cache.
- `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt` — register entity + bump schema version.
- `app/navigation/StashNavHost.kt` — register `SearchArtistRoute`.

### 5.6 Design Tokens

Reuse existing `StashTheme.extendedColors`: `glassBackground`, `glassBorder`, `elevatedSurface`. Accent `#8B5CF6`. Typography: Space Grotesk for headings, Inter for body. No new theme tokens required. Hero uses the existing ambient-purple radial wash pattern used in `PlaylistDetailScreen`.

---

## 6. State & Error Handling

### 6.1 State summary

| ViewModel | Key state class | Status flags |
|-----------|-----------------|--------------|
| `SearchViewModel` | `SearchUiState(query, status: SearchStatus, downloadingIds, downloadedIds, previewLoading)` | `Idle / Typing / Loading / Results / Empty / Error` |
| `ArtistProfileViewModel` | `ArtistProfileUiState(hero, popular, albums, singles, related, status)` | `Loading / Stale / Fresh / Error` |

### 6.2 Error and empty matrix

| Screen | Condition | UI |
|--------|-----------|----|
| Search | Empty query | Centered music icon + prompt (existing). |
| Search | Loading | 6 shimmer rows. |
| Search | Zero results across all sections | "No matches for '<q>'." |
| Search | Network/parse error | Inline error card + retry button; Snackbar on first failure. |
| Artist Profile | Loading, no cache | Hero skeleton + Popular shimmers + Albums shimmer. |
| Artist Profile | Loading, stale cache | Full cached content + thin progress bar under hero. |
| Artist Profile | `browse` failed | Full-screen error + retry; back button works. |
| Preview | Both InnerTube + yt-dlp failed | Snackbar: "Preview unavailable." Row returns to idle. |
| Download | Failed | Existing failed-match flow handles it. |
| Offline | `ConnectException` | Offline banner; cached artist profiles remain openable. |

### 6.3 Accessibility

- Every `IconButton` has a `contentDescription`.
- Hero text meets WCAG AA 4.5:1 contrast against the purple-wash gradient.
- All tappable rows / cards have minimum 48 dp touch targets.
- Popular rows announce track title, artist, and duration via `semantics { }` block.

---

## 7. Testing

### 7.1 Unit tests

- `YTMusicApiClient.searchAll` — three fixtures (artist-query, track-query, zero-results); asserts section ordering and DTO shape.
- `YTMusicApiClient.getArtist` — two fixtures (rich artist with all shelves, sparse artist with only popular); asserts DTO shape.
- `ArtistCache` — hit (fresh), hit (stale + refresh), miss → fetch → populate, eviction at 20 entries.
- `PreviewUrlExtractor` — (a) semaphore isolation (InnerTube 8 / yt-dlp 2), (b) race completes with whichever returns first, (c) race cancels the loser.
- `SearchViewModel` — flatMapLatest cancels prior query; Snackbar on error.

### 7.2 Instrumented / UI tests

- `SearchScreen` renders skeletons during `Loading`.
- `ArtistProfileScreen` paints hero from nav args before data arrives.
- Tap-to-preview on a Popular track with pre-warmed cache starts audio in < 500 ms (measured with `IdlingResource` around `PreviewPlayer.previewState`).

### 7.3 Manual verification (post-implementation)

- Reproduce the user's previous "Episodes / Lootpack" flow — search, tap artist, confirm profile loads cold within 1 s on their Pixel 6 Pro.
- Tap 10 Popular tracks in quick succession — each starts in < 500 ms.
- Re-open the same artist within 6 h — instant (< 50 ms to painted full profile).

---

## 8. Open Questions (to resolve during implementation)

1. **Artist browseId normalization.** InnerTube search results return artists with a `browseId` prefixed `UC` (channel ID) or `MPLAUC...` (music channel). Implementation must normalize to a single form before cache keys are built.
2. **"Download all popular" concurrency.** Reuse the existing download queue's concurrency (2). Confirm during implementation that the queue correctly sequences 10 extract+download operations without starvation.
3. **Shuffle source.** "Shuffle" on the hero plays a random Popular track via the main Media3 player (NowPlaying pipeline), not the preview player. Confirm that NowPlaying can accept a pre-extracted videoId ephemeral queue without requiring a prior `download`.

---

## 9. Implementation Order (rough)

Order is intentionally safety-first: infrastructure / new endpoints first, UI second, polish last.

1. `YTMusicApiClient.searchAll` + DTOs + unit tests.
2. `YTMusicApiClient.getArtist` + DTOs + unit tests.
3. `ArtistCache` entity + DAO + migration + unit tests.
4. `PreviewUrlExtractor` split concurrency + race + tests.
5. `PreviewPrefetcher` + tests.
6. `ShimmerPlaceholder` + skeleton composables.
7. `ArtistAvatarCard`, `AlbumSquareCard`, `SectionHeader` composables.
8. `SearchArtistRoute` navigation + `ArtistProfileScreen`/`ViewModel`.
9. `SearchScreen` / `SearchViewModel` refactor to sectioned results.
10. Coil global `ImageLoader` + preview-tuned `LoadControl` + connection warming.
11. Timing instrumentation + manual latency verification against §4.1.
