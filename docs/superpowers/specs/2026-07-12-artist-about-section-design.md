# Artist "About" Section — Design

**Status:** Approved (brainstorming complete); revised after spec-review rounds 1 & 2
**Date:** 2026-07-12
**Branch:** `feat/artist-about-section`

## 1. Problem & Goal

The Search-side artist page (`ArtistProfileScreen`) shows Hero → Popular → Albums →
Singles & EPs → "Fans also like." It has no equivalent to Spotify's **About**
section. We want an About section surfacing:

1. **Artist bio** — a real biography paragraph with expand/collapse.
2. **Social media links** — Instagram / X / TikTok / YouTube / official site,
   tappable to open the artist's real profiles.
3. **Artist photo** — a larger artist image.

Genres/tags chips were considered and **excluded**.

### Feasibility framing (why the sources are what they are)

Spotify's About is proprietary and not retrievable. Realistic free sources:

- **Bio** — Last.fm `artist.getInfo` (client already wired for other calls),
  routed through the existing Last.fm proxy Worker (shared API key + caching).
- **Social links + photo bridge** — **MusicBrainz** `url-rels`, the clean free
  no-auth structured source, called **client-direct** (see §4 for why not via the
  Worker). Also yields a Wikidata link used by the deferred photo upgrade.
- **Photo** — no free equivalent to Spotify's gallery. v1 renders the existing
  avatar larger; a Wikimedia Commons upgrade is **deferred** (§9). Not a carousel.

## 2. Architecture — a concurrent best-effort supplement

`ArtistCache.fetchAndMerge()` already establishes the pattern: a **required**
`api.getArtist(id)` plus a **best-effort** Qobuz supplement wrapped in
`withTimeout(SUPPLEMENT_TIMEOUT_MS)` that degrades to YT-only on any
timeout/exception. The About data slots in as a **second best-effort supplement,
run concurrently** with the Qobuz one so it never extends cold-miss first paint.

`fetchAndMerge` is a plain `suspend fun` with no scope, so the two supplements run
inside a `coroutineScope { }` as sibling `async` jobs. **Each `async` catches its
own failures internally** — nothing escapes to cancel the scope:

```
fetchAndMerge(artistId):
  yt = api.getArtist(id)                     // REQUIRED (unchanged)
  return coroutineScope {
    val discographyDeferred = async { runQobuzSupplement(yt) }   // unchanged behavior
    val aboutDeferred       = async { runAboutSupplement(yt.name) } // NEW
    val discography = discographyDeferred.await()   // already degraded to YT-only inside
    val about       = aboutDeferred.await()         // already degraded to null inside
    yt.copy(albums = discography.albums, singles = discography.singles, about = about)
  }

runAboutSupplement(name):
  try { withTimeout(ABOUT_TIMEOUT_MS) { aboutEnricher.enrich(name) } }
  catch (e: TimeoutCancellationException) { null }   // MUST precede the CancellationException catch
  catch (e: CancellationException) { throw e }        // real teardown — never mask
  catch (e: Exception) { null }
```

**Why the catch order matters (load-bearing):** `withTimeout` throws
`TimeoutCancellationException`, which **is a** `CancellationException`. If the
generic `CancellationException` catch came first, an About *timeout* would rethrow
and — as a child job exception — cancel the sibling Qobuz `async` and fail the
whole page. Catching `TimeoutCancellationException` → `null` **before** the
cancellation rethrow (exactly the order the existing Qobuz block uses at
`ArtistCache.kt:183-189`) keeps a timeout local and additive. The generic
`CancellationException` rethrow still preserves structured-concurrency teardown on
real rapid-navigation cancellation.

**Latency:** both `async` start before either `await`, so worst-case cold-miss
first paint = `getArtist + max(qobuzTimeout, aboutTimeout)`. With
`ABOUT_TIMEOUT_MS ≤ SUPPLEMENT_TIMEOUT_MS`, this is **no worse than today's
Qobuz-only path**. (Sequential ordering would add the timeouts and is rejected.)

**Enricher param / test seam:** `ArtistCache` gains an `aboutEnricher`
constructor param following the **exact `NoopDiscographySupplement` pattern**
already used for `supplement` (`ArtistCache.kt:75`) — a no-op default on the
primary constructor so existing cache tests keep compiling, with the real binding
injected via the `@Inject` secondary constructor.

**Escape hatch (documented, not v1):** if even the bounded concurrent latency is
noticeable, About can be loaded separately in `ArtistProfileViewModel`; deferred
unless measurement shows a problem.

## 3. Data sources & the enricher

`ArtistAboutEnricher` takes only the **artist name** (ArtistProfile carries no
MBID; the MBID is discovered internally). It depends on `LastFmApiClient` and a
`MusicBrainzClient` **interface** (OkHttp impl), so its tests mock both.

### 3.1 Bio — `LastFmApiClient.getArtistInfo(name)`

New method calling `artist.getInfo` with **`autocorrect=1`** (matching the other
artist calls) and **`cacheable = true`** (so it takes the proxy path).

- **Worker allowlist (required):** the proxy rejects any method not in
  `ALLOWED_METHODS`; today `artist.getinfo` isn't listed, so the call would 400
  and the client would silently fall back to a **direct read-key** call (bypassing
  the shared cache, burning the read-key pool). §4 adds `"artist.getinfo"` to the
  allowlist. Its params (method/artist/autocorrect/optional mbid) hit none of the
  Worker's `FORBIDDEN_PARAMS`, so no other Worker change is needed for bio.
- **MBID capture:** `getInfo` *sometimes* returns `mbid`, but Last.fm decoupled
  from MusicBrainz years ago — it's **frequently empty and occasionally stale**
  (points at a merged/deleted entity). So MBID is an optimization, not the
  mainline (see §3.2).
- **Bio hygiene:** strip the trailing `<a>Read more on Last.fm</a>` anchor **and**
  other HTML tags/entities; then map **empty or placeholder** bios (blank after
  cleanup, or the generic "<name> is a musical artist." boilerplate) to `null`.
- **Attribution (CC-BY):** the About section shows a small "via Last.fm" label
  when a bio renders.

### 3.2 Social links + photo — `MusicBrainzClient` (client-direct)

Lookup flow, resilient to Last.fm's unreliable MBID:

1. **If** Last.fm returned an MBID → `GET /ws/2/artist/{mbid}?inc=url-rels&fmt=json`.
   **On 404/redirect** (stale MBID) → fall through to name search.
2. **Name search (co-primary path):**
   `GET /ws/2/artist?query=artist:"{escaped-name}"&fmt=json`. The name is
   **Lucene-escaped and URL-encoded** (artist names contain `:`, `"`, `(`, `!`,
   e.g. *Panic! at the Disco*, *(G)I-DLE*, *"Weird Al"* — unescaped they malform
   the query). Accept the top match **only if** `score ≥ 95` **and** `type` is
   `Person`/`Group`; otherwise return no socials (avoids wrong-artist data for
   homonyms). Then fetch that MBID's `inc=url-rels&fmt=json`.
3. **Map relations to a `kind` String** — primarily by **URL host** across all
   `url-rels` (simplest and catches every platform regardless of MB's rel typing):
   instagram.com → `"instagram"`, twitter.com/x.com → `"x"`, tiktok.com →
   `"tiktok"`, facebook.com → `"facebook"`, youtube.com → `"youtube"`,
   soundcloud.com → `"soundcloud"`, bandcamp.com → `"bandcamp"`. Relationship
   **type** is used only for `official homepage` → `"website"` and `wikidata` →
   (captured for the deferred photo step, not shown). **Skip relations with
   `"ended": true`** (dead Myspace-era accounts). Drop unknown/duplicate kinds;
   UI renders a fixed order and maps each known string to an icon, globe fallback
   for anything unrecognized.

`MusicBrainzClient` sends
`User-Agent: Stash/<version> ( https://github.com/rawnaldclark/Stash )` (MB
requires a contact; use the **project URL**, not a personal email) and applies a
small **on-device ~1 req/sec gate**. Per-device residential IPs keep every user
independently far under MusicBrainz's per-IP limit — see §4.

### 3.3 Photo (v1 = larger avatar; Wikimedia upgrade deferred)

- **v1:** the UI shows `about.photoUrl ?: profile.avatarUrl`, rendered larger.
  The enricher does **not** copy the avatar into `photoUrl` (no duplicated data in
  the blob); `photoUrl` means "an *upgraded* photo, if any." Since `avatarUrl` is
  nullable, the photo may be absent — the section handles that (bio/socials still
  render). Goal #3 therefore delivers modestly until the upgrade lands.
- **Deferred upgrade (§9):** MB `wikidata` rel →
  `wikidata.org/wiki/Special:EntityData/{Q}.json` → `claims.P18[0]` filename →
  `commons` `Special:FilePath/{encoded-name}`. Two extra hops, spotty P18
  coverage, and — recorded now so a follow-up doesn't ship it naively — most
  Commons photos are **CC BY-SA and require visible attribution**. Not in v1.

## 4. Worker extension (`infra/lastfm-proxy`) — one line only

The **only** Worker change is adding `"artist.getinfo"` to `ALLOWED_METHODS` (for
bio caching per §3.1). No KV binding, no new route, no `wrangler.toml` change.

**MusicBrainz is client-direct, not proxied** — deliberately:

- MusicBrainz rate-limits **~1 req/sec per IP** with **no API key**. Client-direct
  means each user's device is a distinct residential IP, each independently far
  under the limit — naturally compliant and distributed.
- Routing MB through the Worker would funnel *all* installs behind Cloudflare's
  few egress IPs, collapsing MB's per-IP budget into a single shared one → 503s
  under load, requiring heavy caching to stay compliant. And the current Worker
  caches via `caches.default` (per-colo, best-effort, unreliable on the
  `*.workers.dev` domain it's deployed on), so that cache can't be relied on
  without adding KV — extra infra for negative benefit.
- The Worker's value for **Last.fm** is protecting the **shared API key**;
  MusicBrainz has no key, so that rationale doesn't transfer.

No `MUSICBRAINZ_PROXY_URL` config — the MB client always calls MusicBrainz
directly. (It is net-new code; it can't reuse `LastFmApiClient.unsignedGet`, which
is Last.fm-param/cache/breaker-specific.)

## 5. Data model — colocated in `data:ytmusic`, forward-compatible

`ArtistProfile` lives in **`data:ytmusic`** (`.../model/SearchAllResults.kt`), and
`core:data` depends on `data:ytmusic` one-way. The About types are colocated **in
`data:ytmusic`** next to `ArtistProfile` (placing them in `core:data` would force
`data:ytmusic → core:data` and cycle). The enricher (in `core:data`) references
them fine via the existing edge.

```kotlin
@Serializable
data class SocialLink(val kind: String, val url: String)  // kind: "instagram","x",…

@Serializable
data class ArtistAbout(
    val bio: String? = null,
    val socials: List<SocialLink> = emptyList(),
    val photoUrl: String? = null,   // upgrade only; UI coalesces with avatarUrl
)

// ArtistProfile gains:  val about: ArtistAbout? = null
```

- **Backward-compat (verified):** `ArtistCache` decodes with
  `Json { ignoreUnknownKeys = true }` (`ArtistCache.kt:99`). kotlinx uses a
  property's default when a key is **absent**, with no config needed, so old blobs
  → `about = null`. The `ignoreUnknownKeys` codec additionally guarantees the
  reverse (an older app build reading a *newer* blob that contains `about`, i.e.
  downgrade) doesn't throw. `encodeDefaults` is off, so a failed enrichment
  serializes with **no** `about` key — clean round-trip.
- **Forward-compat (why `kind` is a `String`):** the cache-hit decode
  (`ArtistCache.kt:137`) is **outside any try/catch**, and kotlinx **throws on
  unknown enum constants** (no `coerceInputValues`). With sideloaded APKs, an older
  build could read a blob written by a newer one; a new enum value would crash
  artist load. A `String kind` (UI maps → icon, globe fallback) removes that.
- **Enricher returns `null`, not an empty `ArtistAbout`** — so the UI gate is
  simply `about != null` (an all-empty object is not "content").

## 6. UI — About section on `ArtistProfileScreen`

New composable `AboutSection.kt` in `feature/search`, wired into
`contentSections(...)` **after "Fans also like"**:

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━
 About
 ┌───────────────┐
 │  artist photo │   (larger; hidden when avatarUrl null and no upgrade)
 └───────────────┘
 Bio text that runs a few lines and then
 gets clamped…                      see more ▾

 [ Instagram ]  [ X ]  [ TikTok ]  [ ▶ YouTube ]  [ 🌐 ]
                                       via Last.fm
━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

- Bio clamps to ~4 lines with a "see more" / "see less" toggle.
- Photo source = `about.photoUrl ?: profile.avatarUrl`; omitted if both null.
- Social icons tappable → `LocalUriHandler` in `runCatching`. Each `kind` string
  maps to an icon; unknown → globe.
- Section renders **only when `about != null`**; if only socials failed, bio still
  shows (and vice-versa). A slim skeleton shows only while the profile itself
  loads (About isn't separately loaded in v1). "via Last.fm" attribution when a
  bio shows.

## 7. Error handling & degradation

| Failure | Behavior |
| --- | --- |
| Last.fm `getArtistInfo` fails / empty / placeholder | `bio = null`; socials/photo may still populate |
| Stale MBID → 404 | fall back to name search |
| Name match below score/type threshold | `socials = []` (no wrong-artist data) |
| MusicBrainz fails / times out | `socials = []`, photo stays avatar |
| About enricher times out | caught as `TimeoutCancellationException` → `about = null`; Qobuz unaffected; page identical to today |
| Real cancellation (navigation teardown) | rethrown, not masked |
| Old cache blob without `about` | deserializes to `about = null`; refreshes on TTL |
| Blob with unknown `kind` string (newer→older APK) | that link drops / globe icon; no crash |

**Accepted v1 limitation:** a transient enrichment failure persists `about = null`
into a `Fresh` cache row for the 6-hour TTL (SWR won't retry until expiry/eviction).
We do **not** re-enrich on Fresh hits (that would defeat the cache); acceptable for
a bottom-of-page additive section.

## 8. Testing

- **Last.fm bio parser** (pure) — strips anchor + HTML/entities; empty/placeholder
  → `null`; extracts `mbid` when present.
- **MusicBrainz mapper** (pure) — host detection → instagram/x/tiktok/facebook/
  youtube/soundcloud/bandcamp; `official homepage` → website; `ended:true` skipped;
  unknown/dupes dropped; Lucene-escaping of names with `:"(!`; name-search accepts
  only above score+type threshold.
- **Enricher** — partial `ArtistAbout` when one source fails; `null` on timeout;
  stale-MBID→name-search fallback; returns `null` (not empty object) when nothing
  found.
- **`ArtistCache` concurrency** — an About failure/timeout does **not** cancel or
  fail the Qobuz supplement or the page (the isolation contract); cold-miss still
  emits `Fresh`; `NoopArtistAboutEnricher` default keeps existing ctor tests green.
- **Backward/forward-compat deserialization** — old JSON without `about` →
  `about = null`; blob with an unknown `kind` string decodes without crashing.
- **`MusicBrainzClient`** mocked via its interface; `getArtistInfo` via
  mockito-inline (repo already uses it).
- **Worker** — `artist.getinfo` now passes the allowlist and caches (pure-logic
  test in the existing `infra/lastfm-proxy/test/`, or documented manual check).
- **UI** — renders with bio+socials+photo; omits when `about == null`; null photo
  handled; unknown-kind → globe; social tap opens URL; "see more" expands.

## 9. Scope boundaries (YAGNI)

- No genre/tag chips; no photo gallery/carousel.
- **Wikimedia P18 photo upgrade deferred** (v1 = larger avatar only); the §3.3
  chain incl. its **CC BY-SA attribution requirement** is recorded so a follow-up
  doesn't ship it naively.
- No new DB table/migration (rides the JSON blob + existing cache).
- No MusicBrainz Worker route / KV (client-direct, §4).
- No changes to the library-side `ArtistDetailScreen`.
- No decoupled ViewModel About load in v1 (the §2 escape hatch) unless latency
  measures as a problem.
- **Out of scope but noted:** the root cause behind the enum landmine — the
  unguarded decode at `ArtistCache.kt:137` — also affects the pre-existing
  `AlbumSource` enum. The one-time fix (wrap that decode in try/catch → treat an
  undecodable blob as a cache miss and refetch) would cover all present/future
  enums, but is separate hardening deferred out of this feature.

## 10. Files touched (anticipated)

- `core/data/.../lastfm/LastFmApiClient.kt` — add `getArtistInfo` (autocorrect=1,
  cacheable=true).
- `data/ytmusic/.../model/` — `ArtistAbout`, `SocialLink`; `ArtistProfile.about`.
- `core/data/.../cache/` — new `ArtistAboutEnricher` + `NoopArtistAboutEnricher` +
  `MusicBrainzClient` interface/impl; `ArtistCache.fetchAndMerge` runs the enricher
  concurrently with Qobuz (per §2), new no-op-default ctor param.
- `feature/search/.../AboutSection.kt` (new) + wire into
  `ArtistProfileScreen.contentSections`.
- `infra/lastfm-proxy/src/index.js` — add `artist.getinfo` to `ALLOWED_METHODS`
  (only change).
- Tests across the above.
