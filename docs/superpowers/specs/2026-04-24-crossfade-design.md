# Crossfade Between Tracks — Design Spec

**Date:** 2026-04-24
**Status:** Design
**Related:** GitHub issue #16 (juv3nal)

## Problem

Track-to-track playback in Stash currently performs a hard cut — when one track ends (or the user taps next/previous), audio stops abruptly and the next track starts at full volume. juv3nal requested a crossfade effect to smooth these transitions, explicitly asking for it on **both** automatic transitions and manual skips ("Most streaming apps do it only when the tracks transition from one to another, however it would be amazing if this effect can be applied also when tracks are manually changed"). The dev replied that the feature would be added.

## Goals

- Smooth audio crossfade between consecutive tracks
- Cover both **automatic** transitions (track 1 ends → track 2 begins) and **manual** transitions (user taps next or previous)
- User-controllable: a Settings toggle (off by default) plus a duration slider (1–12 seconds, default 4 s)
- Use a true overlap of audio (both tracks playing simultaneously, fading out / in with equal-power curves) — not a fade-out-then-fade-in with silence between
- Preserve existing playback features: queue management, MediaSession metadata for notification + lockscreen + Bluetooth, the existing auto-recovery from `PlaybackException`s, and the defensive `prepare()` rescue from issue #17
- No regression for users who leave the toggle off — default playback path stays exactly as today

## Non-goals

- Eliminating the "~2-3 seconds dead space" some tracks have before playback (separate concern; deferred)
- Crossfading the search-tab `PreviewPlayer` (previews are short snippets — N/A)
- Per-source or per-codec crossfade configuration (over-engineering for V1)
- Visualizing crossfade progress in the UI (no waveform / progress bar enhancement)
- Beat-matching, gain matching, or replay-gain normalization (out of scope)
- Network streaming considerations (current Stash playback is local files only)

## Design

### 1. Architecture — `CrossfadingPlayer` wrapper

A new class **`CrossfadingPlayer`** lives in `core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt` and wraps **two** `ExoPlayer` instances ("A" and "B") behind Media3's `Player` interface. The `MediaSession` is built with the wrapper as its single Player; controllers (`MediaController`, the system notification, lockscreen, Bluetooth, Wear OS) only ever see one Player and don't need to know two are running internally.

```
StashPlaybackService
  └── MediaSession.Builder(this, crossfadingPlayer)
       └── CrossfadingPlayer (extends ForwardingPlayer)
            ├── playerA: ExoPlayer
            ├── playerB: ExoPlayer
            ├── activeRef: AtomicReference<ExoPlayer>  (one of A/B)
            ├── nextRef:   AtomicReference<ExoPlayer>  (the other)
            ├── mediaItems: List<MediaItem>            (canonical queue)
            ├── currentIndex: Int
            ├── crossfadeMs: StateFlow<Long>           (from CrossfadePreferences)
            └── crossfadeEnabled: StateFlow<Boolean>   (from CrossfadePreferences)
```

The wrapper extends Media3's `ForwardingPlayer(initialPlayer)` (which delegates 50+ Player methods to the wrapped player by default), then overrides only the methods that need crossfade orchestration (~6–8 methods listed in section 3 below).

**Why a wrapper Player and not subclass `ExoPlayer`:** `ExoPlayer` is `final` in Media3. The Player interface is the documented integration point for custom playback wrappers. `ForwardingPlayer` is Media3's helper for exactly this pattern.

**Both players share construction:** the same `LoadControl`, `AudioAttributes`, `setHandleAudioBecomingNoisy(true)`, `setWakeMode(C.WAKE_MODE_LOCAL)`, and audio-session-id setup as the existing single `ExoPlayer`. **One difference:** only `playerA` is built with `handleAudioFocus = true`. `playerB` inherits focus from the same audio session and never makes its own focus request, avoiding focus-thrash during overlap.

**Each player holds exactly one MediaItem at a time.** The wrapper owns the canonical queue (`mediaItems`, `currentIndex`). When the wrapper accepts `setMediaItems(list, startIndex, startPositionMs)`:
1. `activePlayer.setMediaItem(list[startIndex]); activePlayer.prepare()`
2. If `list.size > startIndex + 1`: `nextPlayer.setMediaItem(list[startIndex + 1]); nextPlayer.prepare(); nextPlayer.volume = 0f`
3. The wrapper exposes `mediaItemCount`, `getMediaItemAt(i)`, `currentMediaItemIndex` from its own queue, **not** from either underlying player

**Why not let each player hold the full queue?** Two reasons. First, ExoPlayer's native auto-advance would fight the wrapper's crossfade orchestration — both players would try to advance independently. Second, per-player volume control is per-player as a whole; each player needs to own exactly one track at a time so we can shape its volume envelope cleanly.

### 2. State machine + lifecycle

`CrossfadingPlayer` runs a tiny state machine to coordinate fades:

```
SINGLE_ACTIVE          ← steady state. activePlayer plays at vol 1.0; nextPlayer is silently preloaded.
    │
    ├─ (auto: time-to-end ≤ crossfadeMs)  →  CROSSFADING
    ├─ (manual: user taps next/prev)      →  CROSSFADING
    └─ (no next item OR crossfade disabled) → stays SINGLE_ACTIVE; native auto-advance / hard cut handles end-of-track

CROSSFADING            ← both players playing. activePlayer ramps 1→0, nextPlayer ramps 0→1, both over crossfadeMs.
    │
    ├─ (ramp completes)          →  swap roles → SINGLE_ACTIVE on (formerly) nextPlayer
    ├─ (user skips again)        →  cancel current ramp, restart with new "next" → CROSSFADING (rebound)
    └─ (player error on either)  →  abort to SINGLE_ACTIVE on whichever player is still healthy
```

**Position monitoring** (auto trigger):
- A coroutine on the service scope polls `activePlayer.currentPosition` every ~250 ms (cheap; matches the existing `POSITION_UPDATE_INTERVAL_MS = 250L` in `PlayerRepositoryImpl`)
- When `(duration - position) <= crossfadeMs` AND `state == SINGLE_ACTIVE` AND `crossfadeEnabled` AND `currentIndex + 1 < mediaItems.size`, transition to `CROSSFADING`

**Preload timing:**
- After entering `SINGLE_ACTIVE`, the next track is preloaded onto `nextPlayer` immediately (or as soon as the queue has a known next item)
- `nextPlayer.setMediaItem(nextItem); prepare(); volume = 0f` — does NOT call `play()` until the crossfade fires
- Local files prepare in <500 ms typically, so even the cheapest case has plenty of time to be ready before crossfade starts

**Volume curve** (equal-power / cosine):
- `activeVolume = cos(t * π / 2)` where `t` ramps 0.0 → 1.0 over `crossfadeMs`
- `nextVolume   = sin(t * π / 2)`
- At the midpoint t=0.5, both volumes ≈ 0.707 — the equal-power crossover. Total perceived loudness stays roughly constant
- Implemented as a coroutine that updates every ~50 ms (20 fps), calling `player.volume = …` on each underlying ExoPlayer. Linear ramps cause an audible mid-crossfade dip; equal-power is the standard fix used by Spotify, Apple Music, Audacity, and DAWs

**Preloading after a swap:** when the fade completes and roles swap, the (now) `nextPlayer` is idle. As soon as the wrapper knows the next-next track (`currentIndex + 1 < mediaItems.size`), it preloads onto that idle player at volume 0.

**Edge cases handled here:**
- **Crossfade disabled mid-fade.** Settings toggle off → cancel the in-flight ramp coroutine, snap `activePlayer.volume = 0`, snap `nextPlayer.volume = 1.0`, complete the role-swap. State → SINGLE_ACTIVE on the new active. Saves audibly weird mid-fade behavior
- **Track shorter than 2× `crossfadeMs`.** Auto-trigger guard: if `track.duration < crossfadeMs * 2`, skip the crossfade — instant cut. Avoids fade overlap consuming the entire track. Manual skip on a too-short track also hard-cuts
- **Manual skip during a crossfade-in-progress (rebound).** Cancel the current ramp coroutine. The "next" track that was fading in becomes the new "active" (snap to vol 1.0). The new "next-next" track loads on the now-idle other player at vol 0. Start a fresh fade between the (new active, full vol) and the (new next, vol 0)
- **End-of-queue reached during a crossfade.** Let the active player fade out naturally; let the next player (with no track) stay silent. After fade completes, state returns to SINGLE_ACTIVE with playback effectively stopped — `isPlaying = false`
- **`seekTo(positionMs)` within the current track during a crossfade.** Apply the seek to `nextPlayer` only (the user is interacting with the incoming track). Abort the crossfade by snapping `activePlayer.volume = 0` and `nextPlayer.volume = 1.0`. Effectively "fast-forward through the rest of the fade and continue with the new track normally"

### 3. MediaSession integration — forwarding rules

The MediaSession holds the wrapper. Controllers read state through it. The wrapper's forwarding behavior must be principled.

**During `SINGLE_ACTIVE`:** trivial. `ForwardingPlayer` already delegates getters/setters to the wrapped player. The active player IS the wrapped player.

**During `CROSSFADING`:** the wrapper overrides the following Player methods so external observers see a coherent "we are playing the new track now" view:

| Method | Behavior during CROSSFADING |
|---|---|
| `currentMediaItem` | Return `nextPlayer.currentMediaItem` (the incoming track) |
| `currentPosition` | Return `nextPlayer.currentPosition` |
| `duration` | Return `nextPlayer.duration` |
| `isPlaying` | Return `true` (we are playing audio, with two sources) |
| `playbackState` | Return `nextPlayer.playbackState` (typically READY) |
| `currentMediaItemIndex` | Return `currentIndex + 1` (the new track's index in the wrapper's queue) |
| `hasNextMediaItem` | Return `currentIndex + 2 < mediaItems.size` |
| `hasPreviousMediaItem` | Return `currentIndex >= 0` (there's always a "previous" once you've started — the track that's fading out) |
| `play()` | Call `play()` on **both** underlying players |
| `pause()` | Call `pause()` on **both** |
| `stop()` | Call `stop()` on both, abort crossfade, return to SINGLE_ACTIVE |
| `seekTo(positionMs)` | Apply to `nextPlayer` only; abort crossfade (snap volumes 0/1) |
| `seekToNext()` | Trigger rebound crossfade per section 2 |
| `seekToPrevious()` | Trigger rebound crossfade to the *previous* item per section 2 |
| `setVolume(v)` | Multiplier applied to BOTH underlying players' current ramp volumes — preserves user's master-volume gesture even mid-fade |

**Listener event forwarding:**
The wrapper holds its own `Player.Listener` collection (subscribers are external — the MediaSession + the controller-side listener in `PlayerRepositoryImpl`). It internally listens to BOTH underlying players and re-emits events through the wrapper, with these aggregation rules:

- **`onMediaItemTransition(item, reason)`** — fires once per real transition. Synthesized by the wrapper at the moment of role-swap (when crossfade ends, OR when a swap happens without crossfade due to settings disabled / track too short). The reason argument is `MEDIA_ITEM_TRANSITION_REASON_AUTO` for natural advances and `MEDIA_ITEM_TRANSITION_REASON_SEEK` for manual skips
- **`onPlaybackStateChanged(state)`** — mirrors `nextPlayer` during crossfade, `activePlayer` otherwise
- **`onIsPlayingChanged(isPlaying)`** — aggregates: `true` if either underlying is playing
- **`onPlayerError(error)`** — fires from either underlying. The wrapper aborts the crossfade and falls back to SINGLE_ACTIVE on whichever player is still healthy. Then re-emits the error so `PlayerRepositoryImpl`'s existing rescue handler still runs (`seekToNextMediaItem; prepare; play`)
- **`onAvailableCommandsChanged(...)`** — recomputed from the wrapper's own queue + state, not delegated

### 4. Settings UI + persistence

**New "Playback" section in Settings**, inserted between the existing "Audio Quality" section (line 352 of `SettingsScreen.kt`) and the existing "Audio Effects" section (line 514):

```
─── Playback ──────────────────────────────────────
  Crossfade                              [ OFF ⬤ ]
  
  Crossfade duration                       4 sec
  ├──────●─────────────────────────────────────┤
  1s                                        12s
───────────────────────────────────────────────────
```

The duration slider is hidden when the toggle is OFF (collapses gracefully — no dead UI when feature is disabled).

**New composable** `feature/settings/src/main/kotlin/com/stash/feature/settings/components/PlaybackSection.kt`. Mirrors the existing `EqualizerSection.kt` styling (Material3 + GlassCard per the design memory `feedback_stash_design_system.md` — study `EqualizerSection.kt` for the exact pattern before sketching).

**New preference store** `core/data/src/main/kotlin/com/stash/core/data/prefs/CrossfadePreferences.kt`. Same DataStore + Hilt injection pattern as the existing `DownloadNetworkPreference` and `YouTubeHistoryPreference` — copy that scaffolding. Two persisted values:
- `crossfade_enabled: Boolean`, default `false` (off — opt-in, no surprise behavior change)
- `crossfade_duration_ms: Long`, default `4000` (4 seconds — median of major music apps: Spotify 5 s, Apple Music 6 s, Poweramp 4 s)

**Wiring to the player:**
- `CrossfadingPlayer` is `@Inject`-constructed with `CrossfadePreferences`
- It exposes `crossfadeEnabled: StateFlow<Boolean>` and `crossfadeDurationMs: StateFlow<Long>` derived from the prefs (collected in the wrapper's own scope)
- Settings changes propagate live: toggle off mid-fade → cancel the in-flight ramp, snap to single-active. Slider change → takes effect on the *next* crossfade (in-flight one keeps its original duration to avoid weird mid-fade speed changes)

**`SettingsViewModel.kt`** gets two new methods (`setCrossfadeEnabled(Boolean)`, `setCrossfadeDurationSeconds(Int)`) that write to the prefs. The toggle/slider widgets bind to these.

**Slider granularity:**
- Step = 1 second, range 1–12 s
- Below 1 s: barely perceptible. Above 12 s: too much of each track is lost to the fade

## Touch points

| File | Change |
|---|---|
| `core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt` (new) | The wrapper class. Owns 2 ExoPlayer instances, queue, state machine, volume ramping coroutine |
| `core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt` | In `onCreate`: build 2 ExoPlayer instances (with current settings) + `CrossfadingPlayer(playerA, playerB, crossfadePreferences)`. Pass the wrapper to `MediaSession.Builder(this, wrapper)` instead of the raw player |
| `core/data/src/main/kotlin/com/stash/core/data/prefs/CrossfadePreferences.kt` (new) | DataStore wrapper for the toggle + duration. Pattern: copy `YouTubeHistoryPreference.kt` |
| `core/data/src/main/kotlin/com/stash/core/data/di/PreferencesModule.kt` (or wherever prefs are bound) | Provide `CrossfadePreferences` to Hilt graph |
| `feature/settings/src/main/kotlin/com/stash/feature/settings/components/PlaybackSection.kt` (new) | The composable: section header + toggle + conditional slider |
| `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsScreen.kt` | Insert `PlaybackSection(...)` between Audio Quality and Audio Effects sections |
| `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsUiState.kt` | Add `crossfadeEnabled: Boolean` + `crossfadeDurationSeconds: Int` fields |
| `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt` | Add two setters that write to `CrossfadePreferences`; collect prefs into the UI state |
| `core/media/src/test/kotlin/com/stash/core/media/service/CrossfadingPlayerTest.kt` (new) | Unit tests for the wrapper's state machine, volume curve, and edge cases |
| `core/data/src/test/kotlin/com/stash/core/data/prefs/CrossfadePreferencesTest.kt` (new) | DataStore round-trip tests |

Estimated total: ~6 new files, ~4 modified files. Largest single file: `CrossfadingPlayer.kt` (~400-500 LOC).

## Testing

### Unit tests

**`CrossfadingPlayerTest`** (JVM, JUnit 4 + mockk + kotlinx-coroutines-test, `StandardTestDispatcher`):

1. `setMediaItems preloads the next track onto nextPlayer at volume 0`
2. `auto-trigger fires when remaining time crosses crossfadeMs`
3. `auto-trigger does NOT fire when crossfade is disabled`
4. `auto-trigger does NOT fire when track shorter than 2× crossfadeMs (hard cut)`
5. `manual seekToNext during SINGLE_ACTIVE starts crossfade`
6. `manual seekToPrevious during SINGLE_ACTIVE starts crossfade to previous item`
7. `equal-power volume curve: at t=0.5, both volumes ≈ 0.707` (within 0.001 tolerance)
8. `rebound: skip during crossfade swaps the right players` — A→B in flight, skip → B becomes active, C is the new next
9. `crossfade disabled mid-fade snaps to next at full volume`
10. `seekTo within the current track during crossfade aborts crossfade and seeks the incoming track`
11. `play/pause during crossfade affects both players`
12. `error on either player aborts crossfade and re-emits via wrapper listener`
13. `end-of-queue during crossfade fades out cleanly with no next track`

**`CrossfadePreferencesTest`** (JVM):
1. `crossfadeEnabled defaults to false`
2. `crossfadeDurationMs defaults to 4000`
3. `set + read round-trip for both`
4. `flow emits update on write`

### Manual acceptance (on dev's device)

The dev couldn't reproduce the original auto-advance bug; same goes here — these checks are about quality/feel, not correctness:

1. **4 s crossfade, two contrasting tracks (slow → fast).** Fade should sound natural, no perceptible volume dip mid-fade
2. **Tap next mid-track during a long song.** Crossfade kicks in immediately; no glitch
3. **Tap next during a crossfade-in-progress (rebound).** Smooth — no audio pop, no double-fade
4. **Bluetooth headphones (if available).** Same flow; verify no skip/stutter (BT codec latency can interact with rapid volume ramps)
5. **Toggle crossfade OFF mid-playback.** In-flight crossfade snaps cleanly; subsequent transitions are hard cuts
6. **Toggle crossfade ON, change slider 1→12 s.** Each step takes effect on the next crossfade (in-flight one keeps its original duration)
7. **Five tracks back-to-back with crossfade ON.** Each transition smooth; metadata in the system notification updates correctly to each new track
8. **Lockscreen + Bluetooth controls.** Skip via lockscreen / BT button → still triggers a manual-skip crossfade (the wrapper's overridden `seekToNext` is the integration point)

### Integration risk areas

- **MediaSession state during crossfade.** The biggest correctness risk: notification + lockscreen + Bluetooth all read state from the wrapper. The forwarding rules in section 3 must be exactly right or the notification will show the wrong track / position
- **Equalizer / audio effects.** Currently bound to a single audio session ID generated in `StashPlaybackService.onCreate`. Both players need to use the SAME audio session ID so the EQ applies to both during crossfade
- **Audio focus loss / regain (calls, notifications).** ExoPlayer's automatic ducking applies to the player with `handleAudioFocus = true` (playerA's setting). When focus returns, both players need to resume — verify by simulating a phone call mid-playback

## Observability

Four `Log.d("Crossfade", "...")` markers at state transitions:
- "auto-trigger: starting crossfade for $nextTitle, duration=${ms}ms"
- "manual-skip: starting crossfade for $nextTitle, duration=${ms}ms"
- "rebound: skipped during crossfade, new target=$nextNextTitle"
- "fade complete: now playing $title (active player swapped to playerA/playerB)"

Plus existing `Log.w` for `onPlayerError` paths. Debug-level so production logcat stays clean; valuable when triaging "crossfade sounds weird" reports.

## Risks & rollback

- **MediaSession state inconsistency during crossfade.** Mitigation: forwarding rules in section 3, plus integration testing with Bluetooth + lockscreen + notification panel
- **Audio focus thrash with two players.** Mitigation: `handleAudioFocus = true` on `playerA` only; `playerB` shares the same audio session ID and inherits focus implicitly
- **Battery cost during heavy listening.** Crossfade duration > 0 only doubles decode work for the fade window itself (e.g., 4 s out of every ~3 min track ≈ 2 % extra CPU during the fade window). Negligible net impact
- **Edge case: crossfade interacts with the `onPlayerError` rescue** added in v0.6.3. Mitigation: the wrapper aborts its crossfade on either player's error before re-emitting; the existing rescue then runs against the wrapper unchanged
- **Equalizer breaks if the two players use different audio session IDs.** Mitigation: share one audio session ID across both players during construction; verified in unit test that both `audioSessionId` properties are equal
- **Track-too-short edge case (e.g., 8-second voice memo with 12 s crossfade setting).** Auto-trigger is guarded against this; manual skip on a too-short track also hard-cuts. Tested in unit suite
- **Rollback:** revert the release commit + the wrapper-class commit. Settings preferences stay in the user's DataStore but are silently ignored (no migration needed). Reversible in seconds

## Out of scope

- Eliminating leading silence ("dead space") on individual tracks — separate concern, deferred
- Beat-matching, gain-matching, or replay-gain normalization
- Per-source / per-codec crossfade configuration
- Crossfade for the `PreviewPlayer` (search-tab snippets are too short to benefit)
- Network streaming considerations (Stash plays local files only)
- Visualizing crossfade progress in the UI
