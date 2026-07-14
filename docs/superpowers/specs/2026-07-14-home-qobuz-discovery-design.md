# Home Qobuz Discovery — Design (Phase 2)

**Date:** 2026-07-14
**Status:** Draft for review
**Branch:** (Phase 2 of the Premium Crisp redesign; base = `feat/design-language-home-redesign` / master after PR #267 merges)

## 1. Goal

Fill the discovery-first Home tab with real Qobuz-sourced content. Today a fresh
app shows only the Discover hero (materialized Daily Discover). This adds three
horizontal content rows — **New Releases**, **Community Playlists**, **Top
Albums** — plus a **genre chip row** that re-filters all three. Tapping any card
opens it in the existing album/playlist detail screen and plays lossless.

This is the deferred Phase 2 of `2026-07-13-design-language-and-home-redesign.md`
(§ "Home = discovery-first IA: chips → hero → album rows → Qobuz playlists → top
albums"). The earlier "no cheap data path" note applied to *personalized* recs
(needs Last.fm history). Generic Qobuz featured content is reachable through the
existing qbdlx token pool.

## 2. Why this is mostly wiring (feasibility, confirmed)

- **Data:** `QbdlxApiClient` already calls `https://www.qobuz.com/api.json/0.2/`
  with a pooled `X-User-Auth-Token` via a private `get(url, token)`. The featured
  endpoints are **unsigned** (only `track/getFileUrl` needs `QbdlxSigner`), so
  they reuse `get()` verbatim. Token via `QbdlxCredentialStore.activeToken()`.
- **Components:** Plan 1 of the redesign already added `AlbumSquareCard` (FLAC
  badge + press-scale), `RankedAlbumRow`, and `CrispChipRow` to `core:ui` — all
  currently unused. The rows just need data.
- **Album tap:** `AlbumDiscoveryScreen` already loads + plays Qobuz-native albums
  (`AlbumSource.QOBUZ` → `AlbumCache.get` → `QobuzAlbumFetcher.getAlbum` → qbdlx
  stream). Zero new detail work for albums.
- **Playlist tap:** a Qobuz playlist is shaped like an album detail (title + art +
  tracklist). Reuse the same screen via a new `AlbumSource.QOBUZ_PLAYLIST` variant
  + a `getPlaylist()` fetcher mapping `playlist/get` into `AlbumDetail`. No new
  screen.

## 3. Information architecture (Home, top → bottom)

1. Existing Home chrome (lossless prompt / tip jar / backfill banner) — unchanged.
2. **Genre chip row** (`CrispChipRow`) — `All` (default) + a curated static genre
   set. Selecting a chip re-fetches all three rows for that `genre_id`.
3. **Discover hero** (`DiscoverHeroCard`) — unchanged; not genre-filtered.
4. **New Releases** — horizontal `AlbumSquareCard` row.
5. **Community Playlists** — horizontal playlist cards (`AlbumSquareCard`, art +
   title + curator).
6. **Top Albums** — `RankedAlbumList(items, onClick)` (public wrapper; numbered
   1..N). Map each best-seller to `RankedAlbumUi(rank = index+1, title, artist,
   artUrl, movement = null)` — Qobuz `best-sellers` carries no chart-position
   delta, so the up/down movement indicator is omitted.

Row order is fixed. Each row renders only when it has ≥1 item; a row that errors
or returns empty is simply omitted (discovery is non-critical — never blocks Home).

## 4. Data sources (Qobuz API, via `QbdlxApiClient`)

All GET, unsigned, `app_id` + `X-User-Auth-Token`. `genre_id` omitted = all genres.
Param/field names and response shapes are **verified against the live API** —
see §14 for the confirmed contract.

| Row | Endpoint | Key params |
|-----|----------|-----------|
| New Releases | `album/getFeatured` | `type=new-releases-full`, `genre_id`, `limit=20` |
| Top Albums | `album/getFeatured` | `type=best-sellers`, `genre_id`, `limit=20` |
| Community Playlists | `playlist/getFeatured` | `type=editor-picks`, `genre_ids`, `limit=15` |
| Playlist detail | `playlist/get` | `playlist_id`, `extra=tracks`, `limit=500` |
| Genre list (probe only) | `genre/list` | — (to pin chip IDs) |

Response mapping (expected):
- `album/getFeatured` → `albums.items[]`: `id`, `title`, `artist.name`,
  `image.{large,small,thumbnail}`, `release_date_original` → `AlbumSummary`.
- `playlist/getFeatured` → `playlists.items[]`: `id`, `name`, `owner.name`,
  `images`/`image_rectangle`, `tracks_count` → new `PlaylistSummary`.
- `playlist/get` → `name`, `owner.name`, `images`, `tracks.items[]`
  (`id`, `title`, `performer.name`, `duration`, `album.image`) → `AlbumDetail`
  (reusing the same model the album path returns).

## 5. Genre chips

Curated **static** list (lazier + stabler than a dynamic `genre/list` fetch at
runtime), with **real Qobuz `genre_id`s confirmed against live `genre/list`**
(§14). English labels, ours; the IDs are what matter (Qobuz returns localized
names):

| Chip | genre_id |
|------|----------|
| All | (none) |
| Pop/Rock | 112 |
| Hip-Hop | 133 |
| Electronic | 64 |
| Jazz | 80 |
| Classical | 10 |
| Soul/R&B | 127 |
| Metal | 116 |

`All` = no `genre_id` param.

Selecting a chip updates a single `genreFilter: StateFlow<GenreFilter>` in
`HomeViewModel`; the three row flows are derived from it (a `flatMapLatest` per
row keyed by genre), so one tap re-fetches all three. Default = `All`.

## 6. Caching & refresh

Featured content changes ~daily; genre re-taps must not hammer the API.
- In-memory cache in the discovery repository keyed by `"$type:$genreId"`, TTL
  ~3h, mirroring `AlbumCache`'s TTL+in-flight-dedupe pattern. Cold app start does
  one fetch per visible row per selected genre.
- No disk persistence in Phase 2 (YAGNI — a cold start fetching 3 rows is cheap;
  add Room-backed cache only if it measurably hurts).
- Re-selecting a previously-viewed genre within the TTL is served from memory.

## 7. Failure handling

Discovery is non-critical and must never degrade Home.
- Token via `activeToken()`; on `QbdlxAuthException` (401) rotate once
  (`markDead` + `activeToken()`); on exhaustion, fail soft.
- Any failure (network, parse, empty) → that row emits empty → row hidden. No
  error card on Home (the hero + chrome still render). This is NOT the download
  path, so it must **not** touch `AggregatorRateLimiter` / the download breaker.
- Log at `w` for diagnostics; never crash or surface a blocking error.

## 8. Playlist detail (the one new surface)

Add `AlbumSource.QOBUZ_PLAYLIST` to the enum. `AlbumCache.get(id, QOBUZ_PLAYLIST)`
routes to `QobuzAlbumFetcher.getPlaylist(id)` → `playlist/get?extra=tracks` →
mapped into `AlbumDetail` (`title=name`, `artist=owner.name`, `tracks=…`). The
existing `AlbumDiscoveryScreen` + `AlbumDiscoveryViewModel` render + play it
unchanged (the QOBUZ play path already synthesizes tracks + `ensureTrackPersisted`
+ streams via qbdlx). The "artist" line shows the curator — acceptable.

## 9. Navigation

Reuse the existing `SearchAlbumRoute` (browseId/title/artist/thumbnailUrl/year/
source). This is a verbatim reuse of the `onNavigateToAlbum = { album ->
navController.navigate(SearchAlbumRoute(browseId = album.id, … source =
album.source)) }` pattern already wired for ≥2 screens in `StashNavHost.kt`.
Album card tap → an `AlbumSummary` with `source = QOBUZ`. Playlist card tap →
the same callback with `source = QOBUZ_PLAYLIST` and `id = playlistId`.

## 10. Module / file layout

- `data/download/.../qbdlx/QbdlxApiClient.kt` — add `getFeaturedAlbums(type,
  genreId, token)`, `getFeaturedPlaylists(type, genreId, token)`,
  `getPlaylist(playlistId, token)`. (Unsigned; reuse `get()`.)
- `data/download/.../qbdlx/QbdlxCatalogModels.kt` — add featured response models
  + `QbdlxPlaylistItem` / playlist-detail models.
- `core/data/.../discovery/HomeDiscoveryRepository.kt` (new) — token acquisition,
  the 3 fetch methods, in-memory TTL cache, `AlbumSummary`/`PlaylistSummary`
  mapping, fail-soft.
- `core/data/.../discovery/GenreCatalog.kt` (new) — the curated static genre list
  (label + pinned id).
- `core/data/.../discography/QobuzAlbumFetcher.kt` — add `getPlaylist(id):
  AlbumDetail`.
- `core/data/.../cache/AlbumCache.kt` — route `QOBUZ_PLAYLIST`.
- `data/ytmusic/.../model/SearchAllResults.kt` — add `QOBUZ_PLAYLIST` to
  `AlbumSource`; add `PlaylistSummary`.
- `feature/home/.../HomeViewModel.kt` + `HomeUiState.kt` — `genreFilter`, three
  row flows, `onSelectGenre`, `onNavigateToAlbum`.
- `feature/home/.../HomeScreen.kt` — render chip row + three content rows.
- `app/.../StashNavHost.kt` — wire `onNavigateToAlbum`.

## 11. Testing strategy

- `QbdlxApiClient` new methods: MockWebServer, assert URL/params + parse a
  captured sample body (from the Task 1 probe) into models.
- `HomeDiscoveryRepository`: cache TTL hit/miss, genre keying, fail-soft on
  error (row → empty, breaker untouched), 401 → rotate.
- `QobuzAlbumFetcher.getPlaylist`: maps `playlist/get` JSON → `AlbumDetail`.
- `HomeViewModel`: genre selection re-derives all three rows; empty row hidden.
- Reuse `infra_preexisting_matcher_test_failures` `--tests` filters; don't gate
  on the flaky `:core:media` network test.

## 12. Open items — RESOLVED (live probe 2026-07-14, see §14)

All open items were confirmed by probing the real Qobuz API with a pooled token
(the plaintext pool lives in `local.properties`; app_id + one token, no device
needed). No device probe task is required in the plan.

1. ✅ Param name: `genre_id` (singular) works for `album/getFeatured`
   (`genre_id=112` and `genre_ids=112` both return 312). Use `genre_id`.
2. ✅ Types return data: `new-releases-full` (total 482), `best-sellers`
   (total 500), `editor-picks` (total 6360).
3. ✅ Field names confirmed (§14).
4. ✅ `genre/list` → 13 genres, real IDs pinned in §5.
5. ✅ A pooled token authorizes featured + `playlist/get` (all 200-OK).
   Without a token they 401 ("User authentication is required").

## 14. Verified API contract (probed 2026-07-14)

Base `https://www.qobuz.com/api.json/0.2/`, headers `X-App-Id` +
`X-User-Auth-Token`, `app_id` query param. All unsigned.

**`album/getFeatured?type=<t>&genre_id=<id>&limit=<n>`** → `{albums:{total,items:[]}}`.
Each item: `id` (Long), `title` (String), `image.{small(230),thumbnail(50),large(600),back}`,
`artist.{name,id}`, `genre.name`, `release_date_original` (`"2026-07-10"` → year =
first 4 chars), `hires`, `tracks_count`, `duration`. Art: use `image.large` (600px).

**`playlist/getFeatured?type=editor-picks&genre_id=<id>&limit=<n>`** →
`{playlists:{total,items:[]}}`. Each item: `id` (Long), `name`, `owner.name`,
`tracks_count`, `duration`, `images300:[String]` (square covers, 300px),
`image_rectangle`, `images150`, `genres`. Card art: `images300[0]`.

**`playlist/get?playlist_id=<id>&extra=tracks&limit=500`** → top-level `name`,
`owner.name`, `tracks_count`, `duration`, `images300:[String]`, `image_rectangle`,
`tracks:{total,items:[]}`. Each track: `id`, `title`, `performer.name`,
`duration` (sec), `album.{title, image.large}`, `isrc`, `hires`. Maps into
`AlbumDetail`: `title`=playlist name, `artist`=owner.name, `thumbnailUrl`=
`images300[0]`, each track → `(title, artist=performer.name, durationSeconds=
duration, thumbnailUrl=album.image.large)`.

**`genre/list?limit=30`** → `{genres:{total,items:[{id,name}]}}`; 13 top-level
genres. IDs pinned in §5. (Probe-only; not called at runtime.)

## 13. Out of scope (Phase 3+)

- Personalized/tailored rows (needs Last.fm history + a resolver spike).
- Disk-persisted discovery cache.
- Infinite scroll / "see all" per row (Phase 2 shows a capped horizontal row).
- Download-all from a Qobuz playlist (tracks lack videoId; existing screen guard).
