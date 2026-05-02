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

**Both players share construction:** same `LoadControl`, same `AudioAttributes`, `setHandleAudioBecomingNoisy(true)`, `setWakeMode(C.WAKE_MODE_LOCAL)`, **same `StashRenderersFactory(ctx, eqController)` instance** (so the renderer-level EQ chain applies to both — see §4), and the same `audioSessionId`. **One difference:** only `playerA` is built with `handleAudioFocus = true`. `playerB` is built with `handleAudioFocus = false` — two players independently requesting focus on the same session causes thrash and ambiguous loss/regain semantics. The wrapper instead **explicitly propagates** focus events from playerA to playerB; see §7 (Audio focus propagation).

**Cached preference values are `@Volatile`.** `currentEnabled: Boolean`, `currentDurationMs: Long`, and `currentRepeatMode: Int` are written by Flow collectors on a background dispatcher and read on the playback Looper. Mark them `@Volatile` (or wrap in `AtomicReference` for the Boolean if simpler).

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
- **Manual skip during in-flight fade (rebound).** Cancel ramp. Snap previously-incoming to 1.0; it becomes the new active. **Synchronously bump `currentIndex` to the previously-incoming's index** (so any subsequent §5 next-resolution sees the correct base). Load the new target (resolved via §5 from the bumped `currentIndex`) on the now-idle other player at vol 0. Start a fresh fade. (Equivalent to "the user committed to track B; now blend B → C.")
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
| `setVolume(v)` | See the queue-mutation sub-table below — multiplier stored as `userVolume` and applied on every ramp tick |
| `setShuffleModeEnabled(b)` | Set on both players; rebuild `shuffleOrder` (§5); re-arm trigger |
| `setRepeatMode(m)` | Set on both players; re-arm trigger |

**Queue-mutation methods.** The wrapper owns the canonical queue, so every mutation must update `mediaItems` and the `shuffleOrder` list, plus reconcile what each underlying player currently holds. `PlayerRepositoryImpl` calls `addMediaItem(item)`, `addMediaItem(index, item)`, `removeMediaItem(i)`, `moveMediaItem(from, to)`, `setMediaItems(...)`, and `seekToDefaultPosition(index)`:

| Method | Wrapper behaviour |
|---|---|
| `addMediaItems(index, items)` (and `addMediaItem` overloads) | Insert into `mediaItems` at `index`; rebuild `shuffleOrder` (preserving relative order of existing items in shuffle mode); if the inserted item is the new "next" per §5, preload it onto `nextPlayer` at vol 0 (replacing whatever was preloaded) |
| `removeMediaItem(i)` / `removeMediaItems(from, to)` | Remove from `mediaItems`; rebuild `shuffleOrder`. If `i == currentIndex`: equivalent to a forced skip-to-next (uses the standard manual-skip path so the user gets a crossfade, or a hard cut if disabled). If `i == nextIndex`: re-resolve next per §5 and replace `nextPlayer`'s preloaded item |
| `moveMediaItem(from, to)` | Reorder `mediaItems`; rebuild `shuffleOrder`; re-resolve next per §5; if next changed, replace `nextPlayer`'s preloaded item |
| `replaceMediaItem(index, item)` | Update `mediaItems[index]`; if `index == currentIndex` it's a forced "play this now" (route through manual-skip semantics); if `index == nextIndex` swap `nextPlayer`'s preloaded item |
| `clearMediaItems()` | Clear `mediaItems` + `shuffleOrder`; stop both players; `clearMediaItems()` on both; → SINGLE_ACTIVE with `isPlaying = false` |
| `seekToDefaultPosition(mediaItemIndex)` | Treat as a manual jump to `mediaItemIndex` — uses the same path as `seekToNext`/`seekToPrevious` but to an arbitrary index |
| `seekTo(mediaItemIndex, positionMs)` | Same as `seekToDefaultPosition(mediaItemIndex)` followed by `seekTo(positionMs)` on the resulting active player |
| `getMediaItemCount()` / `getCurrentMediaItem()` / `getMediaItemAt(i)` | Already covered (§1, §3) — return from canonical queue |
| `getCurrentTimeline()` | Synthesise a `Timeline` from the canonical queue (one window per item, with each item's metadata + duration where known). External controllers and the system notification query `currentTimeline` to render the queue UI; returning `playerA.currentTimeline` would show only one item. Implementation: extend `androidx.media3.common.Timeline` with a small `CanonicalQueueTimeline` whose windows mirror `mediaItems`. Window duration is unknown for not-yet-prepared items — that's fine; controllers handle `C.TIME_UNSET` |
| `getBufferedPosition()` / `getContentPosition()` / `getContentDuration()` | Forward to the active player during SINGLE_ACTIVE; to `nextPlayer` during CROSSFADING |
| `getPlayWhenReady()` / `getShuffleModeEnabled()` / `getRepeatMode()` | Forward to `playerA` (kept in sync via setters) |
| `setVolume(v)` (extension of §3 row) | Store `userVolume: Float` in the wrapper. Every ramp tick computes `playerA.volume = userVolume * activeVol` and `playerB.volume = userVolume * incomingVol`. During SINGLE_ACTIVE the active player's volume is set to `userVolume` directly. This preserves the user's master-volume gesture even mid-fade |

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

### 7. Audio focus propagation

Two `ExoPlayer`s sharing an `audioSessionId` do **not** share an `AudioFocusRequest`. With `playerB` built `handleAudioFocus = false`, playerB neither requests focus nor reacts to focus changes — meaning during a phone call (focus loss) playerA pauses but playerB keeps playing if it's mid-fade. This is unacceptable.

**Solution:** the wrapper attaches a `Player.Listener` to `playerA` and propagates focus-driven state changes to `playerB`:

- **`onPlayWhenReadyChanged(playWhenReady, reason)`** — when `reason in { PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS, PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS_TRANSIENT, PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY }` and `playWhenReady == false`: call `playerB.playWhenReady = false`. When focus returns and playerA resumes (`reason == AUDIO_FOCUS_LOSS_TRANSIENT` ending → playWhenReady = true), and the wrapper is in CROSSFADING state, call `playerB.playWhenReady = true` to resume the overlap.

**Ducking — a known V1 limitation.** Transient duck (e.g., a notification ping) attenuates playerA's output internally inside `DefaultAudioSink` without firing `Player.Listener.onVolumeChanged` (which only fires on user-initiated `setVolume` calls). The wrapper has no clean way to observe the duck and apply the same attenuation to playerB. **Mitigation:** ducks last ~3 s and are rare; the 2 s default crossfade window makes overlap during a duck statistically uncommon. **Workaround if user reports asymmetric loudness during a notification mid-fade:** add an `AudioManager.OnAudioFocusChangeListener` registered alongside playerA's focus, listening for `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` and applying a one-shot 0.2× scale to playerB's ramp output for the duration of the duck. Defer to V2 unless reported.

**Wrapper `setPlayWhenReady(b)`** (user-driven play / pause): apply to whichever players the current state needs (active during SINGLE_ACTIVE, both during CROSSFADING) — already covered by the §3 forwarding rules. The focus-driven path is distinct because it originates from playerA's listener, not the wrapper's API.

**Why not just `handleAudioFocus = true` on both?** Two simultaneous `AudioManager.requestAudioFocus` calls in the same process are usually deduplicated by AudioManager, but Media3's internal focus state machine assumes single ownership. Empirically, granting focus to two ExoPlayers in the same process produces sporadic "transient loss" callbacks on whichever lost the race. Single-owner-with-explicit-propagation avoids this entirely.

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

**Scheduler seam.** The auto-trigger uses `Handler.postDelayed` on the playback Looper, which is unmockable in plain JVM tests. Introduce a small `CrossfadeScheduler` interface with one production implementation (`HandlerCrossfadeScheduler`) and a test fake that stores pending tasks for assertions:

```kotlin
interface CrossfadeScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): Cancellable
}
```

The wrapper takes `CrossfadeScheduler` via constructor. Tests inject a fake; production injection (Hilt) provides the Handler-backed one bound to `applicationLooper`. Every test that asserts trigger timing checks the fake's pending-task list.

Same approach for the volume ramp: a `CrossfadeClock` interface (`fun nowMs(): Long`) lets tests advance virtual time without `delay()`.

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
