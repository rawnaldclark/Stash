# Artist "About" Section — Design

**Status:** Approved (brainstorming complete); revised after spec review round 1
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

- **Bio** — Last.fm `artist.getInfo` (client already wired for other calls).
- **Social links** — no currently-wired source exposes these. **MusicBrainz**
  (`url-rels`) is the clean, free, no-auth structured source, and also bridges to
  Wikidata/Wikimedia for a photo. New integration.
- **Photo** — no free equivalent to Spotify's curated gallery. v1 renders the
  existing artist avatar larger (see §3.3 for the honest limits); a best-effort
  Wikimedia Commons upgrade is **deferred** (§9). **Not** a photoshoot carousel.

## 2. Architecture — a concurrent best-effort supplement

`ArtistCache.fetchAndMerge()` already establishes the pattern:

- `api.getArtist(artistId)` — **REQUIRED**; a real failure propagates to the
  caller's existing cold-miss / stale-refresh handling.
- `supplement.mergeInto(...)` — **best-effort** Qobuz discography supplement,
  wrapped in `withTimeout(SUPPLEMENT_TIMEOUT_MS)`, degrading to YT-only on any
  timeout/exception.

The About data slots in as a **second best-effort supplement**, run
**concurrently** with the Qobuz one (not after it — see the latency note below):

```
fetchAndMerge(artistId):
  yt = api.getArtist(id)                              // REQUIRED (unchanged)
  // Launch both supplements concurrently, each independently timeout-bounded:
  val discographyDeferred = async { qobuz.mergeInto(...) }          // unchanged
  val aboutDeferred        = async { aboutEnricher.enrich(yt.name) } // NEW
  discography = discographyDeferred.awaitBestEffort()  // degrades to YT-only
  about       = aboutDeferred.awaitBestEffort()        // degrades to null
  return yt.copy(albums=…, singles=…, about=about)
```

A new **`ArtistAboutEnricher`** owns all enrichment. It takes only the **artist
name** (ArtistProfile carries no MBID — YT Music doesn't provide one; the MBID is
discovered internally in §3.1). On timeout/failure → `about = null` → the page
renders exactly as today.

**Latency (load-bearing):** on a cold miss, `get()` cannot emit `Fresh` until
`fetchAndMerge` completes, so anything added here gates first paint. Running the
About enricher **concurrently** with the Qobuz supplement, with an About timeout
**≤ `SUPPLEMENT_TIMEOUT_MS`**, keeps worst-case cold-miss first-paint at
`getArtist + max(qobuzTimeout, aboutTimeout)` = **no worse than today's
Qobuz-only path**. (Sequential ordering — enrich *after* the Qobuz timeout —
would add the two timeouts and regress first visits; that is explicitly rejected.)

**Escape hatch (documented, not v1):** if even the bounded concurrent latency is
noticeable, About can be fully decoupled — emit the profile without About, then
load About in `ArtistProfileViewModel` as a separate follow-up flow. This keeps
`ArtistCache` untouched at the cost of a second cache surface; deferred unless
measurement shows a problem.

**Isolation contract:** the About supplement cannot change the behavior of the
required YT fetch or the Qobuz supplement. Its timeout is independent and its
result is purely additive.

## 3. Data sources & the enricher

### 3.1 Bio — `LastFmApiClient.getArtistInfo(name)`

New method calling `artist.getInfo`, returning `bio.content`.

- **Must be added to the Worker allowlist.** The existing proxy
  (`infra/lastfm-proxy/src/index.js`) enforces an `ALLOWED_METHODS` set that does
  **not** include `artist.getinfo`; without adding it, the proxy returns HTTP 400
  and `LastFmApiClient` silently falls back to a **direct** read-key call —
  bypassing the shared cache and consuming the read-key pool per request. §4 adds
  `"artist.getinfo"` to `ALLOWED_METHODS` (its params method/artist/autocorrect
  clear the existing `FORBIDDEN_PARAMS` gate; no other Worker change needed for
  bio).
- **MBID capture:** `getInfo` usually returns the artist's `mbid`. The enricher
  captures it and hands it to MusicBrainz (§3.2) for a precise lookup, avoiding
  name ambiguity.
- **Bio cleanup:** strip Last.fm's trailing
  `<a href="…">Read more on Last.fm</a>` and surrounding whitespace. Then **treat
  empty or placeholder bios as `null`** — Last.fm returns generic boilerplate
  ("<name> is a musical artist.") or empty summaries for long-tail artists; a
  bio that is blank after cleanup, or matches the known placeholder pattern, is
  set to `null` so the §6 "render only when content" gate actually fires.
- **Attribution (CC-BY):** Last.fm bios are CC-BY; the UI keeps a small "via
  Last.fm" label/link on the About section to satisfy attribution.

### 3.2 Social links + photo — MusicBrainz (`MusicBrainzClient`, injectable)

Defined as an **interface** (`MusicBrainzClient`) with an OkHttp impl, so the
enricher test mocks it without touching the network. Lookup flow:

1. If Last.fm gave an MBID →
   `GET /ws/2/artist/{mbid}?inc=url-rels&fmt=json`.
   Else → `GET /ws/2/artist?query=artist:"{name}"&fmt=json` → pick the top match
   **only if** its `score` clears a threshold (e.g. ≥ 90) **and** its `type` is
   `Person`/`Group`; otherwise **skip enrichment** (low confidence → no
   wrong-artist socials). Then fetch that MBID's `inc=url-rels&fmt=json`.
   - Note `&fmt=json` is required (WS/2 defaults to XML), and `inc=url-rels`
     (single token — the earlier `url-rels+url-rels` was a typo).
2. Map each relation to a `kind` **String** by relationship type, preferring the
   stable `type-id` UUID over the display `type` string:
   - `social network` type → detect host: instagram.com → `"instagram"`,
     twitter.com / x.com → `"x"`, tiktok.com → `"tiktok"`, facebook.com →
     `"facebook"`.
   - **`youtube` type** (dedicated, NOT `social network`) → `"youtube"`.
   - `soundcloud` type → `"soundcloud"`; `bandcamp` type → `"bandcamp"`.
   - `official homepage` type → `"website"`.
   - `wikidata` type → captured for the deferred photo step (§3.3), not shown.
   Unknown/duplicate kinds dropped. UI renders a fixed display order and maps each
   known string to an icon, with a globe fallback for any unrecognized string
   (forward-compatible — see §5).

### 3.3 Photo (v1 = larger avatar; Wikimedia upgrade deferred)

- **v1:** render the artist avatar already on the profile (`ArtistProfile`
  `avatarUrl`), larger. Honest caveat: `avatarUrl` is **nullable**, so the photo
  may be absent for some artists — the section handles a null photo (bio/socials
  still render). This means goal #3 delivers modestly until the upgrade lands.
- **Deferred upgrade (§9):** if MusicBrainz returned a `wikidata` relation,
  resolve Wikidata entity → `P18` image → Wikimedia Commons URL. This is **2–3
  hops** (MB `wikidata` rel → `wikidata.org/wiki/Special:EntityData/Q…json` →
  read `claims.P18[0]` filename → build `Special:FilePath/<name>?width=…`), and
  when built will also route its outbound calls through the Worker. Not in v1.

## 4. Worker extension (`infra/lastfm-proxy`)

Two changes to the existing Worker:

1. **Last.fm:** add `"artist.getinfo"` to `ALLOWED_METHODS` so bios are cached +
   pooled (per §3.1).
2. **MusicBrainz:** add a `/mb/*` route that:
   - **Allowlists specific path shapes** — only `/ws/2/artist/{mbid}` and
     `/ws/2/artist?query=…` with `inc=url-rels` and `fmt=json`. Anything else →
     400. (Mirrors the discipline of the existing method-allowlist; prevents an
     open MusicBrainz relay.)
   - Sets a compliant `User-Agent: Stash/<version> ( https://github.com/rawnaldclark/Stash )`
     — the **project URL**, not a personal email (the UA is logged upstream).
   - Caches responses in **KV** with a ~30-day TTL. (KV, not the Cache API, is
     used for the "fetched once for all users" property — the Cache API is
     per-colo and best-effort eviction, so it cannot back that claim.)
   - **Pacing honesty:** a stateless Worker cannot enforce a global ~1 req/s
     across concurrent invocations on its own. The KV cache is the primary
     defense (near-100% hit rate for popular artists collapses origin traffic);
     if bursts on cold misses still trip MusicBrainz's per-IP limit, add
     Cloudflare's Rate-Limiting binding. The design relies on cache-first, not
     in-Worker global throttling.

**Client config:** a dedicated `MUSICBRAINZ_PROXY_URL` BuildConfig value (the
existing `LASTFM_PROXY_URL` is a full URL already ending in `/lastfm`, so it
can't be path-extended). Empty/unset → the client calls MusicBrainz directly with
the same User-Agent and a client-side throttle. The MusicBrainz client is
**net-new code** — it cannot reuse `LastFmApiClient.unsignedGet` (that is
Last.fm-param/cache/breaker-specific).

## 5. Data model — colocated in `data:ytmusic`, forward-compatible

`ArtistProfile` lives in **`data:ytmusic`** (`.../model/SearchAllResults.kt`), and
`core:data` already depends on `data:ytmusic` (one-way). Therefore the About
types are colocated **in `data:ytmusic` next to `ArtistProfile`** — putting them
in `core:data` would force `data:ytmusic → core:data` and create a cycle. The
enricher (in `core:data`) references them fine via the existing dependency.

`ArtistProfile` is persisted as a JSON blob in `artist_profile_cache`. We add one
nullable field with a default:

```kotlin
@Serializable
data class SocialLink(val kind: String, val url: String)  // kind: "instagram","x",…

@Serializable
data class ArtistAbout(
    val bio: String? = null,
    val socials: List<SocialLink> = emptyList(),
    val photoUrl: String? = null,
)

// ArtistProfile gains:
//   val about: ArtistAbout? = null
```

- **Backward-compat (verified):** `ArtistCache` decodes with
  `Json { ignoreUnknownKeys = true }` and `about` has a default, so old blobs
  missing the key deserialize to `about = null` (default-fill is built in). No DB
  migration, no cache-buster; stale entries gain the field on their next 6-hour
  refresh.
- **Forward-compat (why `kind` is a `String`, not an enum):** the cache-hit
  decode (`ArtistCache` read path) is **outside any try/catch**, and
  kotlinx.serialization **throws on unknown enum constants** (not covered by
  `ignoreUnknownKeys`; `coerceInputValues` is not enabled). Since Stash ships as
  sideloaded APKs, an older APK could read a blob written by a newer one; a new
  enum constant would then crash artist load. Storing `kind` as a `String` (UI
  maps known values → icons, globe fallback for the rest) removes that landmine
  entirely.

## 6. UI — About section on `ArtistProfileScreen`

New composable `AboutSection.kt` in `feature/search`, wired into
`contentSections(...)` **after "Fans also like"** (matches Spotify's placement):

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━
 About
 ┌───────────────┐
 │  artist photo │   (larger; hidden if avatar null)
 └───────────────┘
 Bio text that runs a few lines and then
 gets clamped…                      see more ▾

 [ Instagram ]  [ X ]  [ TikTok ]  [ ▶ YouTube ]  [ 🌐 ]
                                       via Last.fm
━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

- Bio clamps to ~4 lines with a "see more" / "see less" toggle.
- Social icons tappable → open via `LocalUriHandler` in `runCatching` (matches
  existing external-link handling). Each `SocialLink.kind` string maps to an icon;
  unknown kinds get a globe icon.
- Renders **only** when `about` has content; if only socials failed, bio still
  shows (and vice-versa); photo omitted when avatar is null. A slim skeleton shows
  only while the profile itself is loading (About is not separately loaded in v1,
  so there is no independent About spinner).
- Small "via Last.fm" attribution when a bio is shown.

## 7. Error handling & degradation

| Failure | Behavior |
| --- | --- |
| Last.fm `getArtistInfo` fails / empty / placeholder | `bio = null`; socials/photo may still populate |
| MusicBrainz lookup fails/times out / low-confidence name match | `socials = []`, photo stays avatar |
| Wikidata/Commons upgrade (deferred) fails | `photoUrl` stays the avatar |
| Whole enricher times out | `about = null`; page identical to today |
| Old cache blob without `about` | deserializes to `about = null`; refreshes on TTL |
| Blob with an unknown `kind` string (newer→older APK) | that link drops / globe icon; no crash |

Enricher timeout is independent and set ≤ `SUPPLEMENT_TIMEOUT_MS`.
`CancellationException` is re-thrown before the catch (matching the existing
`fetchAndMerge` convention) so rapid-navigation teardown isn't masked.

## 8. Testing

- **Last.fm bio parser** — strips "Read more" anchor + whitespace; maps
  empty/placeholder to `null`; extracts `mbid` when present.
- **MusicBrainz `url-rels` mapper** (pure fn) — `youtube`/`soundcloud`/`bandcamp`
  dedicated types map correctly; `social network` host detection →
  instagram/x/tiktok/facebook; `official homepage` → website; unknown/dupes
  dropped; stable order; name-search picks top match only above score+type
  threshold, else skips.
- **Enricher degradation** — partial `ArtistAbout` when one source fails; `null`
  on timeout; re-throws `CancellationException`; concurrency doesn't extend the
  bound beyond `SUPPLEMENT_TIMEOUT_MS`.
- **Backward-compat deserialization** — old `ArtistProfile` JSON without `about`
  → `about = null`; blob with unknown `kind` string does not crash decode.
- **`MusicBrainzClient`** mocked via its interface; `getArtistInfo` mocked via
  mockito-inline (repo already uses it for final Kotlin classes).
- **Worker** — MusicBrainz passthrough sets the User-Agent, rejects non-allowlisted
  paths, and serves from KV on repeat (Miniflare test in the existing
  `infra/lastfm-proxy/test/`, or documented manual verification).
- **UI** — About renders with bio+socials+photo; omits when `about == null`;
  null photo handled; unknown-kind icon falls back to globe; social tap opens URL;
  "see more" expands.

## 9. Scope boundaries (YAGNI)

- No genre/tag chips (explicitly excluded).
- No photo *gallery* / carousel (not available from free sources).
- **Wikimedia P18 photo upgrade deferred** — v1 uses the larger avatar only. The
  §3.3 chain is documented so it isn't rediscovered, but is out of v1.
- No new database table or migration (rides the existing JSON blob + cache).
- No changes to the library-side `ArtistDetailScreen` — Search-side
  `ArtistProfileScreen` only.
- No fully-decoupled About load in v1 (the §2 escape hatch) unless bounded
  concurrent latency measures as a problem.

## 10. Files touched (anticipated)

- `core/data/.../lastfm/LastFmApiClient.kt` — add `getArtistInfo` (cacheable via
  the proxy once the method is allowlisted).
- `data/ytmusic/.../model/` — `ArtistAbout`, `SocialLink`; `ArtistProfile.about`
  field (colocated with `ArtistProfile`).
- `core/data/.../cache/` — new `ArtistAboutEnricher` + `MusicBrainzClient`
  interface/impl; `ArtistCache.fetchAndMerge` runs the enricher concurrently with
  the Qobuz supplement.
- `feature/search/.../AboutSection.kt` (new) + wire into
  `ArtistProfileScreen.contentSections`.
- `infra/lastfm-proxy/src/index.js` — add `artist.getinfo` to `ALLOWED_METHODS`;
  add the allowlisted, KV-cached `/mb/*` MusicBrainz route with the project-URL
  User-Agent.
- Config — `MUSICBRAINZ_PROXY_URL` BuildConfig (mirrors `LASTFM_PROXY_URL`).
- Tests across the above.
