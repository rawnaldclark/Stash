# Artist "About" Section — Design

**Status:** Approved (brainstorming complete, pending spec review)
**Date:** 2026-07-12
**Branch:** `feat/artist-about-section`

## 1. Problem & Goal

The Search-side artist page (`ArtistProfileScreen`) shows Hero → Popular → Albums →
Singles & EPs → "Fans also like." It has no equivalent to Spotify's **About**
section. We want to add an About section surfacing:

1. **Artist bio** — a real biography paragraph with expand/collapse.
2. **Social media links** — Instagram / X / TikTok / YouTube / official site,
   tappable to open the artist's real profiles.
3. **Artist photo** — a larger artist image.

Genres/tags chips were considered and **excluded** from this scope.

### Feasibility framing (why the sources are what they are)

Spotify's About (bio, curated photo gallery, social links) is powered by
Spotify's own proprietary data and label-supplied assets and is **not**
retrievable. The realistic free sources:

- **Bio** — Last.fm `artist.getInfo` (client already wired for other calls) or
  YT description. Easy.
- **Social links** — no currently-wired source exposes these. **MusicBrainz**
  (`url-rels`) is the clean, free, no-auth structured source, and also bridges to
  Wikidata/Wikimedia for a photo. New integration.
- **Photo** — no free equivalent to Spotify's curated gallery. We guarantee the
  existing artist avatar shown larger, with a best-effort Wikimedia Commons
  upgrade. **Not** a photoshoot carousel.

## 2. Architecture — a second best-effort supplement

`ArtistCache.fetchAndMerge()` already establishes the exact pattern we need:

- `api.getArtist(artistId)` — **REQUIRED**; a real failure propagates to the
  caller's existing cold-miss / stale-refresh handling.
- `supplement.mergeInto(...)` — **best-effort** Qobuz discography supplement,
  wrapped in `withTimeout(SUPPLEMENT_TIMEOUT_MS)` and degrading to YT-only on any
  timeout/exception (never escapes, never breaks the page).

The About data slots in as a **second best-effort supplement** alongside the
Qobuz one:

```
fetchAndMerge(artistId):
  yt          = api.getArtist(id)                       // REQUIRED (unchanged)
  discography = qobuz.mergeInto(...)                    // best-effort (unchanged)
  about       = aboutEnricher.enrich(yt.name, yt.mbid?) // NEW, best-effort, timeout-bounded
  return yt.copy(albums=…, singles=…, about=about)
```

A new **`ArtistAboutEnricher`** owns all enrichment. `ArtistCache` calls it in a
`try/withTimeout` mirroring the Qobuz supplement: on timeout/failure →
`about = null` → the page renders exactly as today. The enricher is a single unit
with one job (produce an `ArtistAbout?` from an artist name + optional MBID),
independently testable, depending only on `LastFmApiClient` and a MusicBrainz
client.

**Isolation contract:** nothing about the About supplement can change the
behavior of the required YT fetch or the existing Qobuz supplement. Its timeout
is independent and its result is purely additive.

## 3. Data sources & the enricher

### 3.1 Bio — `LastFmApiClient.getArtistInfo(name)`

New method calling `artist.getInfo`. Returns `bio.content`, with Last.fm's
trailing `<a href="…">Read more on Last.fm</a>` and surrounding whitespace
stripped. Routes through the existing Last.fm proxy Worker when
`LASTFM_PROXY_URL` is configured, else direct (same as all other Last.fm reads).
`getInfo` frequently returns the artist's **MBID** (`mbid` field) — captured and
passed to MusicBrainz for a precise lookup (avoids name-ambiguity).

### 3.2 Social links + photo — MusicBrainz via the Worker

Lookup flow:

1. If Last.fm gave an MBID → `GET /ws/2/artist/{mbid}?inc=url-rels+url-rels`
   (JSON). Else → `GET /ws/2/artist?query=artist:"{name}"` → take the top-scoring
   match's MBID → the same `inc=url-rels` fetch.
2. Map each relation `type` to a `SocialKind`:
   - `social network` URLs → detect host: instagram.com → INSTAGRAM,
     twitter.com/x.com → X, tiktok.com → TIKTOK, youtube.com → YOUTUBE.
   - `official homepage` → WEBSITE.
   - `wikidata` relation → captured for the photo step (§3.3).
   Unknown/duplicate kinds are dropped. Order is fixed (Instagram, X, TikTok,
   YouTube, Website) for stable UI.

### 3.3 Photo (layered, never blocks)

- **Primary (guaranteed):** the artist avatar already present on the profile
  (`ArtistProfile` hero avatar), rendered larger. Zero new hops, always works.
- **Best-effort upgrade:** if MusicBrainz returned a Wikidata relation, resolve
  Wikidata entity → `P18` (image) → construct the Wikimedia Commons file URL.
  On success, `photoUrl` becomes that image; on any failure it stays the avatar.
  This upgrade is optional and may be implemented in a later phase without a model
  change (the field already defaults to the avatar).

## 4. Worker extension (`infra/lastfm-proxy`)

Add one route (e.g. `/mb/*`) to the **existing** Worker:

- Forwards to `musicbrainz.org/ws/2/...` with a compliant
  `User-Agent: Stash/<version> ( <contact> )` header (MusicBrainz requires this).
- Paces requests to respect MusicBrainz's ~1 req/sec guidance.
- Caches responses (KV or Cache API) with a **long TTL (~30 days)** — bio/socials
  change rarely, so a popular artist is fetched from MusicBrainz once for all
  users. This is the whole reason for the proxy: the shared cache decouples
  MusicBrainz load from install count, exactly like the Last.fm proxy decouples
  Last.fm load.

**Client reach:** the app calls the MusicBrainz endpoint at the same proxy base
it already uses; empty/unset config → client-direct fallback with the same
throttle + User-Agent. (Exact config knob — reuse the Last.fm proxy base with a
path, vs. a dedicated `MUSICBRAINZ_PROXY_URL` — is an implementation detail
decided in the plan; behavior is identical.)

## 5. Data model — backward-compatible, no migration

`ArtistProfile` is persisted as a JSON blob in `artist_profile_cache`
(`ArtistProfileCacheEntity`: `artist_id`, `json`, `fetched_at`). We add one
nullable field with a default:

```kotlin
@Serializable
enum class SocialKind { INSTAGRAM, X, TIKTOK, YOUTUBE, WEBSITE }

@Serializable
data class SocialLink(val kind: SocialKind, val url: String)

@Serializable
data class ArtistAbout(
    val bio: String? = null,
    val socials: List<SocialLink> = emptyList(),
    val photoUrl: String? = null,
)

// ArtistProfile gains:
//   val about: ArtistAbout? = null
```

The `= null` / `emptyList()` defaults make **old cached blobs deserialize
cleanly** (kotlinx.serialization uses the default for missing fields) — no DB
migration, no cache-buster. Stale entries gain the field on their next 6-hour
refresh (`TTL_MS`).

## 6. UI — About section on `ArtistProfileScreen`

A new section rendered by `contentSections(...)`, placed **after "Fans also
like"** (matches Spotify's lower placement):

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━
 About
 ┌───────────────┐
 │  artist photo │   (larger image; falls back to avatar)
 └───────────────┘
 Bio text that runs a few lines and then
 gets clamped…                      see more ▾

 [ Instagram ]  [ X ]  [ TikTok ]  [ ▶ YouTube ]  [ 🌐 ]
━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

- Bio clamps to ~4 lines with a "see more" / "see less" toggle
  (`maxLines` + local expanded state).
- Social icons are tappable → open via `LocalUriHandler` (wrapped in
  `runCatching`, consistent with existing external-link handling).
- The section renders **only** when `about` has content. If only socials failed,
  bio still shows (and vice-versa). A slim skeleton shows while the profile is in
  the loading state; if enrichment failed entirely, the section is simply absent.
- New composable(s) live in `feature/search` next to the other sections
  (`AboutSection.kt`), consistent with `PopularTracksSection`, `RelatedArtistsRow`.

## 7. Error handling & degradation

| Failure | Behavior |
| --- | --- |
| Last.fm `getArtistInfo` fails | `bio = null`; socials/photo may still populate |
| MusicBrainz lookup fails/times out | `socials = []`, photo stays avatar |
| Wikidata/Commons upgrade fails | `photoUrl` stays the avatar |
| Whole enricher times out | `about = null`; page identical to today |
| Old cache blob without `about` | deserializes to `about = null`; refreshes on TTL |

Enricher timeout is independent of the Qobuz `SUPPLEMENT_TIMEOUT_MS` (its own
bound), and cancellation is re-thrown before the catch (per the existing
convention in `fetchAndMerge`, so rapid-navigation teardown isn't masked).

## 8. Testing

- **Last.fm bio parser** — strips the "Read more on Last.fm" anchor + whitespace;
  handles empty/missing bio.
- **MusicBrainz `url-rels` mapper** — host detection → correct `SocialKind`;
  drops unknowns/dupes; stable ordering; picks top-scored artist on name search.
- **Enricher degradation** — returns partial `ArtistAbout` when one source fails;
  returns `null`/omits on timeout; re-throws `CancellationException`.
- **Backward-compat deserialization** — an old `ArtistProfile` JSON string
  without an `about` key deserializes with `about = null`.
- **Worker** — MusicBrainz passthrough sets the User-Agent and serves from cache
  on a repeat request (small unit or documented manual verification).
- **UI** — About section renders with bio+socials+photo; omits entirely when
  `about == null`; social icon tap opens the URL; "see more" expands.

## 9. Scope boundaries (YAGNI)

- No genre/tag chips (explicitly excluded).
- No photo *gallery* / carousel (not available from free sources).
- No new database table or migration (rides the existing JSON blob + cache).
- No changes to the library-side `ArtistDetailScreen` — this is the Search-side
  `ArtistProfileScreen` only.
- The Wikimedia photo upgrade is optional/best-effort and may be deferred to a
  follow-up without any model change.

## 10. Files touched (anticipated)

- `core/data/.../lastfm/LastFmApiClient.kt` — add `getArtistInfo`.
- `core/data/.../cache/` — new `ArtistAboutEnricher` + MusicBrainz client;
  `ArtistCache.fetchAndMerge` calls it best-effort.
- `core/data/.../` model — `ArtistAbout`, `SocialLink`, `SocialKind`;
  `ArtistProfile.about` field.
- `feature/search/.../AboutSection.kt` (new) + wire into
  `ArtistProfileScreen.contentSections`.
- `infra/lastfm-proxy/src/index.js` — MusicBrainz passthrough route + cache.
- Tests across the above.
