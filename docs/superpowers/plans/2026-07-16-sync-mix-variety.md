# Sync Mix Variety — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pull more of the user's algorithmic mixes for "flavor" (Your Top Songs, Blend, mood/Made-For-You mixes on Spotify; broader personalized radios on YouTube — reusing existing API calls), and make newly-discovered algorithmic mixes appear **automatically** by defaulting them to enabled **only in Online mode** (so nothing auto-downloads).

**Architecture:** Two independent widenings of the existing fetch allowlists (no new network code — both platforms already return this data in the same response we currently discard), plus one change to the playlist-creation default in `DiffWorker` gated on streaming mode. Everything a mix needs is already fetched; we stop throwing it away and stop hiding it.

**Tech Stack:** Kotlin, Room, Hilt/Dagger, JUnit + mockito-kotlin.

**Branch:** its own branch off the receipts work (or off `master` once receipts merge) — this touches source clients, not the receipts UI. Suggested: `feat/sync-mix-variety`.

**Key risk (from investigation self-critique):**
- **Spotify name-matching is localized-fragile** — the existing English-name allowlist already fails for non-English accounts. Widen by **owner identity** (`ownerId == "spotify"`) where possible, not more English strings.
- **Default-on must not auto-download.** Gate the enabled-default on `streamingPreference.current()` so it only fires in Online mode (surface, cheap). Offline stays opt-in.

---

## File Structure

**Modify:**
- `data/spotify/.../SpotifyApiClient.kt` — widen `parseHomeFeedForSpotifyMixes` (owner-based), raise `sectionItemsLimit`.
- `data/ytmusic/.../YTMusicApiClient.kt` — widen `isAllowedMixPlaylist`.
- `core/data/.../sync/workers/DiffWorker.kt:250-265` — Online-gated default-on for `DAILY_MIX` playlists.

**Test:**
- `data/spotify/src/test/.../SpotifyMixFilterTest.kt` (new) — extract the keep/reject predicate to a pure function and test it.
- `data/ytmusic/src/test/.../YTMixFilterTest.kt` (new) — pure `isAllowedMixPlaylist` cases.
- `core/data/src/test/.../DiffWorkerDefaultOnTest.kt` (new) — DAILY_MIX default-on only when Online.

---

## Task 1: Widen the Spotify mix filter (owner-based, not more English names)

**Files:**
- Modify: `data/spotify/.../SpotifyApiClient.kt:99-114` (constants), `:398` (`sectionItemsLimit`), `:475` (filter)
- Test: `data/spotify/src/test/kotlin/com/stash/data/spotify/SpotifyMixFilterTest.kt`

The keep decision at `:475` runs *before* owner extraction (`:479`). Refactor so owner is known first, then extract a pure predicate.

- [ ] **Step 1: Write the failing test** for a pure `isSpotifyMix(name, ownerId)`:

```kotlin
@Test fun keepsDailyMixes()        = assertTrue(isSpotifyMix("Daily Mix 3", "spotify"))
@Test fun keepsNamedMixes()        = assertTrue(isSpotifyMix("Discover Weekly", "spotify"))
@Test fun keepsYourTopSongs()      = assertTrue(isSpotifyMix("Your Top Songs 2025", "spotify"))
@Test fun keepsBlend()             = assertTrue(isSpotifyMix("Rawn + Alex", "spotify")) // Blend, spotify-owned
@Test fun keepsMadeForYouMood()    = assertTrue(isSpotifyMix("Chill Mix", "spotify"))
@Test fun rejectsUserOwnedCustom() = assertFalse(isSpotifyMix("My Road Trip", "rawnaldclark"))
@Test fun rejectsEditorialNonMix() = assertFalse(isSpotifyMix("Today's Top Hits", "spotify")) // spotify-owned but a flagship editorial playlist, not personalized — see note
```

> Note on the last case: a pure spotify-owner rule would also pull flagship editorial playlists. Because these items come from the personalized **home** feed sections (not Browse/editorial), in practice spotify-owned home items are personalized. Decide during Step 3 whether to (a) accept all spotify-owned home items (simplest, may include an occasional editorial card) or (b) keep an owner rule + a small deny-set of known flagship names. Start with (a) and let Task 4 device-verify what actually arrives; tighten only if junk shows up. `// ponytail: owner rule first; add a deny-set only if device shows editorial leakage.`

- [ ] **Step 2: Run it, verify it fails** — `./gradlew :data:spotify:testDebugUnitTest --tests "*SpotifyMixFilter*"`. Expected: compile error.

- [ ] **Step 3: Extract + widen the predicate.** Move owner extraction (`:479-482`) above the keep check, and replace `:475-476` with a call to a companion-level pure function:
```kotlin
internal fun isSpotifyMix(name: String, ownerId: String): Boolean {
    if (DAILY_MIX_REGEX.matches(name)) return true
    if (name.lowercase() in SPOTIFY_MIX_NAMES) return true
    // Owner-based catch-all for personalized home items (locale-proof).
    return ownerId == "spotify"
}
```
Keep `SPOTIFY_MIX_NAMES`/`DAILY_MIX_REGEX` as the fast path for non-owner-tagged cases.

- [ ] **Step 4: Raise the section cap** at `:398` from `sectionItemsLimit=20` to `sectionItemsLimit=40` so large home feeds don't truncate before a mix section.

- [ ] **Step 5: Run the test, verify it passes.** Expected: PASS (accept the (a)-vs-(b) decision reflected in the editorial test).

- [ ] **Step 6: Commit** — `feat(sync): widen Spotify mix fetch to owner-based personalized playlists`.

---

## Task 2: Widen the YouTube mix filter

**Files:**
- Modify: `data/ytmusic/.../YTMusicApiClient.kt:988`
- Test: `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMixFilterTest.kt`

- [ ] **Step 1: Write the failing test:**
```kotlin
@Test fun keepsDailyAndDiscover() = assertTrue(isAllowedMixPlaylist("RDTMAK5uy_abc"))
@Test fun keepsMyMix()            = assertTrue(isAllowedMixPlaylist("RDMM"))
@Test fun keepsPersonalRadio()    = assertTrue(isAllowedMixPlaylist("RDAT1234"))   // newly allowed
@Test fun keepsEmMix()            = assertTrue(isAllowedMixPlaylist("RDEMxyz"))     // newly allowed
@Test fun rejectsAlbum()          = assertFalse(isAllowedMixPlaylist("OLAK5uy_abc"))
@Test fun rejectsUserPlaylist()   = assertFalse(isAllowedMixPlaylist("VLPLabc"))
@Test fun rejectsChannel()        = assertFalse(isAllowedMixPlaylist("UCabc"))
@Test fun rejectsAlbumBrowse()    = assertFalse(isAllowedMixPlaylist("MPREabc"))
```

- [ ] **Step 2: Run it, verify it fails** — `./gradlew :data:ytmusic:testDebugUnitTest --tests "*YTMixFilter*"`. Expected: FAIL (`RDAT`/`RDEM` rejected today).

- [ ] **Step 3: Widen `isAllowedMixPlaylist`** — accept broader personalized radio prefixes while keeping the explicit rejects. **Change `private` → `internal`** so `YTMixFilterTest` (separate class) can call it, exactly as Task 1 does for `isSpotifyMix`:
```kotlin
internal fun isAllowedMixPlaylist(playlistId: String): Boolean {
    // Explicit rejects first (albums, user playlists, channels, album content).
    if (playlistId.startsWith("MPRE") ||
        playlistId.startsWith("UC") ||
        playlistId.startsWith("VLPL") || playlistId.startsWith("PL") ||
        playlistId.startsWith("OLAK5uy_")) return false
    // Personalized mixes/radios + built-ins.
    return playlistId.startsWith("RD") || playlistId.startsWith("VLRD") ||
        playlistId == "RDMM" || playlistId == "LM"
}
```
Update the KDoc (`:971-994`) to reflect the broadened rule.

- [ ] **Step 4: Run the test, verify it passes.** Expected: PASS.

- [ ] **Step 5: Commit** — `feat(sync): widen YouTube mix fetch to broader personalized radios`.

> Optional follow-up (separate task, not required for v1): pull sibling browse endpoints `FEmusic_mixed_for_you` / `FEmusic_moods_and_genres` via the existing `innerTubeClient.browse()`. Log-and-defer; only add if the user wants mood carousels too.

---

## Task 3: Default algorithmic mixes to enabled — Online mode only

**Files:**
- Modify: `core/data/.../sync/workers/DiffWorker.kt:250-265` (call site) + a new top-level `internal fun`
- Test: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/DefaultSyncEnabledTest.kt`

**Why a pure helper, not a `DiffWorker` unit test:** the reviewer confirmed `findOrCreatePlaylist` and `processPlaylist` are both **private**, there are **no** existing `*DiffWorker*` tests to mirror, and constructing `DiffWorker` needs 12 injected deps + 2 `@Assisted` params. Testing the rule through the worker is disproportionate. Extract the one-line decision into a pure, `internal` function and test *that* — the worker just calls it. `DiffWorker` already reads `streamingPreference.current()` at `:93` and passes `streamingMode` into `processPlaylist` at `:305`, so the value is already in scope at the `:250` new-playlist branch.

Algorithmic mixes are typed `PlaylistType.DAILY_MIX` (`PlaylistFetchWorker.kt:277` Spotify, `:657` YouTube); user playlists are `CUSTOM`, liked are `LIKED_SONGS` — so `== DAILY_MIX` targets exactly the mix set.

- [ ] **Step 1: Write the failing test** for the pure helper:
```kotlin
import com.google.common.truth.Truth.assertThat  // Truth IS available in :core:data tests

@Test fun `daily mix enabled only in online mode`() {
    assertThat(defaultSyncEnabled(PlaylistType.DAILY_MIX, online = true)).isTrue()
    assertThat(defaultSyncEnabled(PlaylistType.DAILY_MIX, online = false)).isFalse()
}
@Test fun `custom playlist always opt-in`() {
    assertThat(defaultSyncEnabled(PlaylistType.CUSTOM, online = true)).isFalse()
    assertThat(defaultSyncEnabled(PlaylistType.CUSTOM, online = false)).isFalse()
}
@Test fun `liked songs always opt-in`() {
    assertThat(defaultSyncEnabled(PlaylistType.LIKED_SONGS, online = true)).isFalse()
}
```

- [ ] **Step 2: Run it, verify it fails** — `./gradlew :core:data:testDebugUnitTest --tests "*DefaultSyncEnabled*"`. Expected: compile error (no `defaultSyncEnabled`).

- [ ] **Step 3: Add the helper + call it.** Add a top-level `internal fun` in `DiffWorker.kt` (or a small sibling file in the same package):
```kotlin
/**
 * A newly-discovered playlist's initial [PlaylistEntity.syncEnabled].
 * Algorithmic mixes (DAILY_MIX) auto-enable in Online mode so they surface
 * immediately with no download. Everything else — and every playlist in
 * Offline mode — stays opt-in: the first Sync Now is a discovery pass that
 * downloads nothing unasked.
 */
internal fun defaultSyncEnabled(type: PlaylistType, online: Boolean): Boolean =
    type == PlaylistType.DAILY_MIX && online
```
Then at `DiffWorker.kt:264`, replace `syncEnabled = false` with:
```kotlin
            syncEnabled = defaultSyncEnabled(snapshot.playlistType, streamingMode),
```
`streamingMode` is the boolean already read at `:93` and threaded to `processPlaylist` (`:305`); ensure the new-playlist branch (`findOrCreatePlaylist`) has access to it — pass it down from `processPlaylist` if the signature doesn't already carry it.

- [ ] **Step 4: Run the test, verify it passes.** Expected: PASS.

- [ ] **Step 5: Verify the safety property holds** (no code, just re-read): in Offline mode `defaultSyncEnabled` returns false → `DiffWorker.kt:113` (`if (!syncEnabled) continue`) skips the playlist before any `download_queue` insert → **nothing auto-downloads**. In Online mode the enqueue is skipped anyway (`:438`), so an enabled DAILY_MIX only surfaces. Confirm both by reading, then proceed.

- [ ] **Step 6: Commit** — `feat(sync): auto-surface algorithmic mixes in online mode (offline stays opt-in)`.

---

## Task 4: Device verification

- [ ] **Step 1: Build + install** — `./gradlew :app:installDebug`.

- [ ] **Step 2: Online sync** — in Online mode, Sync Now. On Library → Playlists, confirm **more** algorithmic mixes now appear automatically (Discover Weekly / Your Top Songs / Blend / mood mixes for Spotify; broader mixes for YouTube) without manually toggling them on. Eyeball the YouTube additions too — the widened `RD*`/`VLRD*` rule could admit video-seeded station cards (e.g. `RDAMVM…`) if the home carousels surface them; if any junk radios appear, tighten the prefix set.

- [ ] **Step 3: Check the diagnostics** — expand the newest receipt; confirm the mix steps still succeed and more items are counted. Optionally pull the DB (`adb exec-out run-as com.stash.app.debug cat databases/stash.db`) and check `SELECT name, type, sync_enabled FROM playlists WHERE type='DAILY_MIX'` — new mixes should be `sync_enabled=1`.

- [ ] **Step 4: Offline safety check** — switch to Offline mode, Sync Now, confirm newly-discovered mixes arrive **opt-in** (`sync_enabled=0`) and nothing auto-downloads unexpectedly (watch `download_queue` / storage).

- [ ] **Step 5: Editorial-leakage check** — scan the new Spotify mixes for any non-personalized flagship editorial playlist (the Task 1 (a)-vs-(b) risk). If junk appears, add the small deny-set from Task 1's note.

- [ ] **Step 6: Report** PASS/FAIL with a captured Playlists-grid frame + the `playlists` query output.

---

## Verification checklist (whole plan)

- [ ] `./gradlew :data:spotify:testDebugUnitTest :data:ytmusic:testDebugUnitTest :core:data:testDebugUnitTest` green (`--tests` filters; skip known-flaky suites per memory).
- [ ] Device: Online sync surfaces more mixes automatically; Offline sync keeps them opt-in with no surprise downloads.
- [ ] No editorial/junk leakage in the Spotify mix list (or deny-set added).
