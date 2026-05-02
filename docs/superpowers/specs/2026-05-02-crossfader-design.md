# Crossfader (track-to-track crossfade) — Design Spec

**Date:** 2026-05-02
**Status:** Design
**Supersedes:** the deleted `2026-04-24-crossfade-design.md` (had wrong EQ wiring, missing shuffle handling, polling-based trigger)
**Related:** GitHub issue #16 (juv3nal)

## Problem

Track-to-track playback in Stash currently performs a hard cut — when one track ends or the user taps next/previous, audio stops abruptly and the next track starts at full volume. juv3nal requested a smooth crossfade on **both** automatic transitions and manual skips. Stash currently builds one `ExoPlayer` (`StashPlaybackService.kt:76-85`) and has no facility to overlap two streams.

## Goals

- Smooth audio crossfade between consecutive tracks
- Cover both **automatic** transitions (track 1 ends → track 2 begins) and **manual** transitions (user taps next or previous)
- User-controllable: a Settings toggle (**ON** by default) and duration slider (1–12 s, default **2 s**)
- True overlap of audio (both tracks playing simultaneously, fading out / in with equal-power curves) — not a fade-out-then-fade-in with silence between
- Preserve every existing feature: queue management, MediaSession metadata for notification + lockscreen + Bluetooth, the renderer-level Stash EQ (`StashRenderersFactory`), audio focus / `becomingNoisy` handling, the auto-recovery on `PlaybackException`
- Shuffle and repeat modes still work correctly
- No measurable regression for users who turn the toggle off — the disabled-path is identical to today's behaviour

## Non-goals

- Eliminating leading silence on individual tracks ("dead space" before the audio starts)
- Crossfading the search-tab `PreviewPlayer` (snippets are too short)
- Per-source / per-codec crossfade configuration
- Beat-matching, gain-matching, or replay-gain normalisation
- Visualising crossfade progress in the UI
- DJ-style manual mix slider (different feature; out of scope)

## Design

### 1. Architecture — `CrossfadingPlayer` wrapper

A new class **`CrossfadingPlayer`** lives at `core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt` and wraps **two** `ExoPlayer` instances ("A" and "B") behind Media3's `Player` interface. The `MediaSession` is built with the wrapper as its single `Player`; controllers (system notification, lockscreen, Bluetooth, Wear OS, `MediaController`) only ever see one Player.

```
StashPlaybackService
  └── MediaSession.Builder(this, crossfadingPlayer)
       └── CrossfadingPlayer (extends ForwardingPlayer)
            ├── playerA: ExoPlayer
            ├── playerB: ExoPlayer
            ├── activeRef: AtomicReference<ExoPlayer>
            ├── nextRef:   AtomicReference<ExoPlayer>
            ├── mediaItems: List<MediaItem>          (canonical queue)
            ├── currentIndex: Int
            ├── shuffleOrder: IntArray                (precomputed; rebuilt when shuffle/queue changes)
            ├── repeatMode: Int                       (mirrors playerA)
            ├── currentEnabled: Boolean              (cached from prefs)
            └── currentDurationMs: Long              (cached from prefs)
```

The wrapper extends Media3's `ForwardingPlayer(playerA)`, then overrides only the methods that need crossfade orchestration. **`ForwardingPlayer` footgun:** the underlying `wrappedPlayer` is fixed at construction. Because the wrapper rotates the active player on every fade-swap, any getter / setter that should reflect "current active" must be explicitly overridden — never assume an un-overridden method "just works" after a swap. Section 3 lists every method the wrapper overrides.

**Why a wrapper Player and not subclass `ExoPlayer`:** `ExoPlayer` is `final` in Media3. The Player interface is the documented integration point.

**Both players share construction:** same `LoadControl`, same `AudioAttributes`, `setHandleAudioBecomingNoisy(true)`, `setWakeMode(C.WAKE_MODE_LOCAL)`, **same `StashRenderersFactory(ctx, eqController)` instance** (so the renderer-level EQ chain applies to both — see §4), and the same `audioSessionId`. **One difference:** only `playerA` is built with `handleAudioFocus = true`. `playerB` never makes a focus request — two players asking for focus would thrash. PlayerB inherits ducking/loss/regain implicitly from the same audio session.

**Each player holds exactly one MediaItem at a time.** The wrapper owns the canonical queue. When the wrapper accepts `setMediaItems(list, startIndex, startPositionMs)`:

1. `activePlayer.setMediaItem(list[startIndex]); activePlayer.prepare(); activePlayer.seekTo(startPositionMs)`
2. If a "next" item is computable (see §5 for shuffle-aware logic): `nextPlayer.setMediaItem(nextItem); nextPlayer.prepare(); nextPlayer.volume = 0f` — `play()` is NOT called yet
3. The wrapper exposes `mediaItemCount`, `getMediaItemAt(i)`, `currentMediaItemIndex` from its own queue, **not** from either underlying player

**Why not let each player hold the full queue?** Two reasons. First, ExoPlayer's native auto-advance would fight the wrapper's crossfade orchestration — both players would try to advance independently. Second, per-player volume is per-player as a whole, so each player needs to own one track at a time to allow a clean envelope.

### 2. State machine + lifecycle

```
SINGLE_ACTIVE          ← steady state. activePlayer plays at vol 1.0; nextPlayer is silently preloaded.
    │
    ├─ (auto: scheduled trigger fires)         →  CROSSFADING
    ├─ (manual: user taps next/prev)           →  CROSSFADING
    └─ (no next item OR crossfade disabled)    →  stays SINGLE_ACTIVE; native auto-advance / hard cut handles end-of-track

CROSSFADING            ← both playing. activePlayer ramps 1→0, nextPlayer ramps 0→1, both over crossfadeMs.
    │
    ├─ (ramp completes)         →  swap roles → SINGLE_ACTIVE on (formerly) nextPlayer
    ├─ (user skips again)       →  cancel ramp, snap incoming to 1.0, start fresh ramp to new target  (rebound)
    └─ (player error on either) →  abort to SINGLE_ACTIVE on whichever player is healthy
```

**Auto-trigger — scheduled, not polled.** Polling every 250 ms (the original spec's approach) means up to 250 ms latency on the start of the fade — for a 4 s fade that's a 6 % error, audible on some files. The wrapper instead schedules a one-shot wake-up:

- Whenever the active player enters `STATE_READY` with a known `duration`, schedule `triggerCrossfade()` at `(duration - currentPosition - crossfadeMs)` ms via a `Handler` bound to the playback `Looper` (`activePlayer.applicationLooper`).
- **Re-arm** on: `seekTo`, `onPlayWhenReadyChanged`, `onPlaybackStateChanged → READY`, `onMediaItemTransition`, settings duration change.
- **Cancel** on: pause, stop, disable toggle, manual skip (the skip handler triggers crossfade itself).
- The handler runs on the playback looper, so volume writes and `play()` calls all land on the right thread.

**Preload timing:**
- After entering `SINGLE_ACTIVE`, the next track is preloaded onto `nextPlayer` immediately
- `nextPlayer.setMediaItem(nextItem); prepare(); volume = 0f` — does NOT call `play()` until the crossfade fires
- Local files prepare in <500 ms typically, so the cheapest case has plenty of time

**Volume curve — equal-power (cosine):**
- `activeVolume = cos(t · π / 2)` where `t` ramps 0.0 → 1.0 over `crossfadeMs`
- `nextVolume   = sin(t · π / 2)`
- Midpoint t=0.5: both volumes ≈ 0.707 — perceived loudness stays constant
- Implemented as a coroutine on a `Looper`-bound dispatcher, ticking every ~50 ms (20 fps)
- Linear ramps cause an audible mid-fade dip; equal-power is the standard fix used by Spotify, Apple Music, Audacity, and DAWs

**Edge cases:**
- **Crossfade disabled mid-fade.** Cancel ramp, snap `active.volume = 0`, `next.volume = 1.0`, complete role-swap, → `SINGLE_ACTIVE`.
- **Track shorter than 2× `crossfadeMs`.** Auto-trigger guard: skip the fade — instant cut. Manual skip on a too-short track also hard-cuts.
- **Manual skip during in-flight fade (rebound).** Cancel ramp. Snap previously-incoming to 1.0; it becomes the new active. Load the new target on the now-idle other player at vol 0. Start a fresh fade. (Equivalent to "the user committed to track B; now blend B → C.")
- **End-of-queue during fade.** Active fades to silence; next is empty; after fade `isPlaying = false`. Repeat-all causes the wrap-around track to load on the next player as normal.
- **`seekTo(positionMs)` during fade.** Treat as: "user wants to jump on the incoming track." Apply seek to `nextPlayer`, snap volumes 0/1, complete role-swap. (During CROSSFADING the wrapper already reports `currentMediaItem` as the incoming track per §3.)
- **`repeat = ONE`.** Crossfade-to-self is weird. When `repeatMode == REPEAT_MODE_ONE`, suppress the crossfade — let the natural loop happen with a hard cut.

### 3. MediaSession integration — forwarding rules

The MediaSession holds the wrapper. Controllers read state through it.

**During `SINGLE_ACTIVE`:** trivial. `ForwardingPlayer` already delegates getters/setters to the wrapped player. The active player IS the wrapped player.

**During `CROSSFADING`:** the wrapper overrides these Player methods so external observers see a coherent "we are now playing the new track" view:

| Method | Behavior during CROSSFADING |
|---|---|
| `getCurrentMediaItem()` | Return `nextPlayer.currentMediaItem` |
| `getCurrentPosition()` | Return `nextPlayer.currentPosition` |
| `getDuration()` | Return `nextPlayer.duration` |
| `isPlaying()` | Return `true` |
| `getPlaybackState()` | Return `nextPlayer.playbackState` |
| `getCurrentMediaItemIndex()` | Return the resolved next-index from §5 |
| `hasNextMediaItem()` | Computed via §5 (one past the incoming) |
| `hasPreviousMediaItem()` | `currentIndex >= 0` (the outgoing is "previous") |
| `play()` | Call `play()` on **both** underlying players |
| `pause()` | Call `pause()` on both |
| `stop()` | Call `stop()` on both, abort fade, → SINGLE_ACTIVE |
| `seekTo(ms)` | Apply to `nextPlayer`; abort fade (snap 0/1) |
| `seekToNext()` | Trigger rebound per §2 |
| `seekToPrevious()` | Trigger rebound to previous per §2 |
| `setVolume(v)` | Multiplier applied to BOTH underlying players' current ramp volumes |
| `setShuffleModeEnabled(b)` | Set on both players; rebuild `shuffleOrder` (§5); re-arm trigger |
| `setRepeatMode(m)` | Set on both players; re-arm trigger |

**Listener event forwarding.** The wrapper holds its own `Player.Listener` collection (subscribers: the MediaSession, the controller-side listener in `PlayerRepositoryImpl`). Internal listeners on both underlying players re-emit through the wrapper:

- **`onMediaItemTransition(item, reason)`** — synthesised by the wrapper at the role-swap moment. Reason: `MEDIA_ITEM_TRANSITION_REASON_AUTO` for natural advance, `MEDIA_ITEM_TRANSITION_REASON_SEEK` for manual skip.
- **`onPlaybackStateChanged(state)`** — mirrors `nextPlayer` during fade, `activePlayer` otherwise.
- **`onIsPlayingChanged(isPlaying)`** — `true` if either underlying is playing.
- **`onPlayerError(error)`** — fires from either underlying. Wrapper aborts the fade, falls back to whichever player is healthy. Re-emits the error so `PlayerRepositoryImpl`'s existing rescue (`seekToNextMediaItem; prepare; play`) still runs.
- **`onAvailableCommandsChanged`** — recomputed from wrapper's queue + state.

### 4. EQ integration — renderer-level, not session-level

**Critical correction vs. the deleted spec.** Stash's EQ is implemented as `AudioProcessor`s injected via `StashRenderersFactory.buildAudioSink` (`StashRenderersFactory.kt:29-47`). It is **renderer-level**: each `ExoPlayer` instance gets its own audio sink built with the same processors that read from a shared `EqController`. It is NOT the system `Equalizer` audio effect tied to `audioSessionId`.

**Implementation:**
- Both `playerA` and `playerB` are built with `setRenderersFactory(StashRenderersFactory(this, eqController))` — passing the **same singleton** `EqController` (Hilt already provides this).
- Each player gets its own audio sink, but both sinks reference the same processors which read from the same `EqController` state.
- EQ enable / disable / band changes propagate to both players atomically because they share the controller.
- `audioSessionId` is still set the same on both players (low-cost, helps with debug attribution and any future system-effect integrations) but is not load-bearing for EQ correctness.

**No EqualizerManager `initialize()` call from the wrapper** — that's still `StashPlaybackService`'s responsibility, called once after both players are built. Nothing changes there.

### 5. Shuffle-aware "next" resolution

**The bug the deleted spec missed.** Naïve `currentIndex + 1` returns the queue-order-next, but with `shuffleModeEnabled = true` ExoPlayer plays a different order. The wrapper would crossfade to the wrong track.

**Approach: maintain a precomputed shuffle-order list inside the wrapper.**

- The wrapper holds `shuffleOrder: IntArray` — a permutation of `0..mediaItems.lastIndex`. In linear (non-shuffle) mode this is `[0,1,2,...]`.
- Rebuilt whenever `setMediaItems` is called or `setShuffleModeEnabled` is toggled. Rebuild uses `Random.shuffle()` for shuffle mode, identity for linear.
- "Next index" lookup: find `currentIndex` in `shuffleOrder`, return `shuffleOrder[i+1]` (or wrap to `shuffleOrder[0]` if `repeatMode == REPEAT_MODE_ALL`).
- "Previous index" same logic, reversed.

**Why this rather than `Timeline.getNextWindowIndex`:** each underlying ExoPlayer holds a single-item timeline at any moment, so its `getNextWindowIndex` returns `INDEX_UNSET`. Synthesising a multi-item Timeline just to query it is more code than the explicit list approach.

**Synced with each underlying player.** When the wrapper sets `shuffleModeEnabled = true` on itself, it also calls `playerA.setShuffleModeEnabled(true)` and `playerB.setShuffleModeEnabled(true)` — even though each only has one item. This keeps controllers' view of the shuffle flag consistent.

### 6. Settings UI + persistence

**New "Playback" section in Settings**, inserted between the existing "Audio Quality" section and "Audio Effects" section in `SettingsScreen.kt`:

```
─── Playback ─────────────────────────────────────
  Crossfade                           [ ON  ⬤ ]

  Crossfade duration                    2 sec
  ├─●──────────────────────────────────────┤
  1s                                    12s
──────────────────────────────────────────────────
```

The duration slider is hidden when the toggle is OFF (collapses gracefully).

**New composable** `feature/settings/src/main/kotlin/com/stash/feature/settings/components/PlaybackSection.kt`. Mirrors `EqualizerSection.kt` styling (Material3 + GlassCard per the design system) — **study `EqualizerSection.kt` before writing this** to match the existing visual language.

**New preference store** `core/data/src/main/kotlin/com/stash/core/data/prefs/CrossfadePreferences.kt`. Same DataStore + Hilt pattern as `YouTubeHistoryPreference`. Persisted values:
- `crossfade_enabled: Boolean`, default **`true`** (ON — opt-in to silence rather than opt-in to feature)
- `crossfade_duration_ms: Long`, default **`2_000`** (2 s — short enough to feel polished without feeling like a feature)

The clamp `coerceIn(1_000, 12_000)` is exposed as a pure-function companion (`clampDurationMs`) so it can be unit-tested without DataStore.

**Wiring to the player:**
- `CrossfadingPlayer` is `@Inject`-constructed with `CrossfadePreferences`
- It collects `enabled` and `durationMs` flows into cached `Boolean` / `Long` fields (avoids `runBlocking` in synchronous skip handlers)
- Toggle off mid-fade → cancel in-flight ramp, snap to single-active
- Slider change → takes effect on the *next* crossfade (in-flight one keeps its original duration to avoid weird mid-fade speed changes)

**`SettingsViewModel.kt`** gets two new methods (`setCrossfadeEnabled`, `setCrossfadeDurationSeconds`) that write to the prefs.

**Slider granularity:** step = 1 second, range 1–12 s. Below 1 s: barely perceptible. Above 12 s: too much of each track lost to the fade.

## Touch points

| File | Change |
|---|---|
| `core/media/.../service/CrossfadingPlayer.kt` (new) | The wrapper class. Owns 2 players, queue, state machine, ramp coroutine, scheduled trigger, shuffle order |
| `core/media/.../service/StashPlaybackService.kt` | In `onCreate`: build 2 ExoPlayers (same renderers factory + load control + audio attrs), pass the wrapper to `MediaSession.Builder`. In `onDestroy`: wrapper's `release()` cascades |
| `core/data/.../prefs/CrossfadePreferences.kt` (new) | DataStore wrapper. Pattern: copy `YouTubeHistoryPreference.kt` |
| `feature/settings/.../components/PlaybackSection.kt` (new) | Composable: section header + toggle + conditional slider |
| `feature/settings/.../SettingsScreen.kt` | Insert `PlaybackSection(...)` between Audio Quality and Audio Effects |
| `feature/settings/.../SettingsUiState.kt` | Add `crossfadeEnabled: Boolean`, `crossfadeDurationSeconds: Int` |
| `feature/settings/.../SettingsViewModel.kt` | Inject `CrossfadePreferences`; add two setters; collect prefs into UI state |
| `core/media/.../service/CrossfadingPlayerTest.kt` (new) | Unit tests for state machine, volume curve, shuffle, scheduling, edge cases |
| `core/data/.../prefs/CrossfadePreferencesClampTest.kt` (new) | Pure clamp tests (no DataStore round-trip — project doesn't test those) |

Estimated total: 5 new files, 3 modified files. Largest single file: `CrossfadingPlayer.kt` (~500 LOC).

## Testing

### Unit tests — `CrossfadingPlayerTest`

JVM, JUnit 4, mockk (already in `:core:data`; add to `:core:media` test deps). Use `StandardTestDispatcher`.

1. `setMediaItems preloads next on nextPlayer at vol 0`
2. `setMediaItems with single item preloads nothing`
3. `getMediaItemAt returns from canonical queue`
4. `auto-trigger schedules at duration - position - crossfadeMs`
5. `auto-trigger does NOT fire when crossfade disabled`
6. `auto-trigger does NOT fire when track shorter than 2× crossfadeMs`
7. `auto-trigger does NOT fire when repeatMode = ONE`
8. `manual seekToNext during SINGLE_ACTIVE starts crossfade`
9. `manual seekToPrevious during SINGLE_ACTIVE starts crossfade to previous`
10. `seekToNext when crossfade disabled does instant cut`
11. `equal-power curve at t=0.5 → both ≈ 0.707` (tolerance 0.001)
12. `equal-power curve endpoints: t=0 → (1,0), t=1 → (0,1)`
13. `rebound: skip during fade snaps incoming to 1, loads next-next on the freed player`
14. `crossfade disabled mid-fade snaps next to vol 1`
15. `seekTo within current track during fade aborts fade and seeks incoming`
16. `play during CROSSFADING affects both players`
17. `error on either player aborts fade`
18. `end-of-queue during fade fades out cleanly`
19. `shuffle on: nextIndex follows shuffleOrder, not queue order`
20. `setShuffleModeEnabled rebuilds shuffleOrder and re-arms trigger`

### Unit tests — `CrossfadePreferencesClampTest`

1. `clamp passes through value within range (1000, 4000, 12000)`
2. `clamp raises below minimum (0, 500, -1000) → 1000`
3. `clamp lowers above maximum (20000, MAX_VALUE) → 12000`

(No DataStore round-trip tests; the project doesn't test that layer for any other preference.)

### Manual acceptance (on dev's device)

1. **Toggle OFF.** Hard cuts; identical to today.
2. **Toggle ON, 2 s default.** Smooth blend on every transition; no perceptible volume dip mid-fade.
3. **Manual next mid-track.** 2 s fade kicks in; no glitch.
4. **Rebound: tap next twice in quick succession.** Smooth — no audio pop, no double-fade.
5. **Slider 1 s → 12 s.** Each step takes effect on the next fade; in-flight one keeps its original duration.
6. **Toggle OFF mid-fade.** In-flight fade snaps cleanly; subsequent transitions hard-cut.
7. **Equalizer.** Open Audio Effects, change EQ during a fade — change applies to both halves audibly.
8. **Shuffle ON.** Crossfade plays the shuffled-next track, not the queue-order-next.
9. **Repeat-one ON.** No crossfade — track loops with hard cut.
10. **Repeat-all ON, last track.** Wrap-around: fades into queue[0].
11. **Bluetooth headphones.** Same flow; no skip/stutter.
12. **Lockscreen / system notification.** Track metadata updates correctly when each crossfade starts.
13. **Five tracks back-to-back.** Each transition smooth; metadata correct throughout.
14. **Phone call mid-playback.** Audio focus loss → both players pause via session. Call ends → resume. No focus thrash.

### Integration risk areas

- **MediaSession state during fade.** Notification + lockscreen + Bluetooth all read from the wrapper. Forwarding rules in §3 must be exactly right.
- **EQ continuity.** Renderer-level EQ requires both players to be built with the same `eqController` instance (§4).
- **Audio focus.** `handleAudioFocus = true` only on `playerA`; ducking and call-loss must propagate to both.
- **Looper threading.** Volume writes, `play()` / `pause()` calls, and ramp ticks must all run on `applicationLooper` to avoid Media3's "wrong thread" assertions.

## Observability

`Log.d("Crossfade", "...")` markers at state transitions:
- `auto-trigger scheduled: in ${ms}ms for ${nextTitle}`
- `auto-trigger fired: starting fade, duration=${ms}ms`
- `manual-skip: fading to ${nextTitle}, duration=${ms}ms`
- `rebound: skipped during fade, new target=${nextNextTitle}`
- `fade complete: now playing ${title} (active = playerA/B)`
- `EQ continuity verified: both players using eqController@${hash}` (debug-only one-time log on construct)

Plus existing `Log.w` for `onPlayerError`. Debug-level so production logcat stays clean.

## Risks & rollback

| Risk | Mitigation |
|---|---|
| MediaSession state inconsistency during fade | Forwarding rules §3 + lockscreen / Bluetooth manual checks in test §13 |
| EQ silent on incoming track during fade | Single source-of-truth `eqController` shared across both `StashRenderersFactory` instances; one-time debug log verifies it |
| Audio focus thrash with two players | Only `playerA` requests focus; `playerB` inherits via shared session |
| Battery cost of two decoders | Second decoder runs only during the fade window (~2 s out of every several-min track ≈ <1% extra CPU on average) |
| Crossfade + `onPlayerError` rescue | Wrapper aborts fade on either player's error before re-emitting; existing rescue runs against the wrapper unchanged |
| Shuffle: wrong-track fade-in | Precomputed `shuffleOrder` rebuilt on shuffle toggle / queue change (§5) |
| Repeat-one infinite fade-to-self | Auto-trigger + manual-skip both check `repeatMode == REPEAT_MODE_ONE` and hard-cut |
| Looper threading violations | Ramp coroutine on `applicationLooper.asCoroutineDispatcher()`; Handler-based scheduled trigger on the same looper |
| ON-by-default surprises existing users | Changelog flags it. Power users can flip in Settings → Playback in 2 taps. Worst-case feedback is "turn it off" — low blast radius for a beta-stage app |

**Rollback:** revert the release commit + the wrapper-class commit. Two ExoPlayers revert to one; the wrapper class becomes dead code (delete in a follow-up). DataStore preferences stay in users' caches but are silently ignored. Settings UI tile becomes unreachable. Reversible in seconds.

## Out of scope

- Eliminating leading silence on individual tracks
- Beat-matching, gain-matching, replay-gain
- Per-source / per-codec configuration
- Crossfade for `PreviewPlayer`
- Network streaming
- Visualising fade progress in the UI
- DJ-style manual mix slider
