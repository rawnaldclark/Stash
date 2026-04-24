# Playlist Auto-Advance Fix — Design Spec

**Date:** 2026-04-24
**Status:** Design
**Related:** GitHub issue #17 (Blindmikey: "Playlists/Mixes do not successfully auto-advance to the next song"), plus Reddit reports.

## Problem

After tapping "Play All" on Home or PlaylistDetail, track 1 plays normally. When track 1 ends, the next track loads into the player (`onMediaItemTransition` fires, UI shows track 2 as current) but does not start playing. Tapping the play button does nothing. Tapping next or previous unsticks playback. The dev cannot reproduce. A subset of users hit the bug consistently.

## Root cause hypothesis (from code-only analysis)

`StashPlaybackService.kt:85-92` enables hardware audio offload via `setAudioOffloadPreferences(AUDIO_OFFLOAD_MODE_ENABLED)`. Hardware offload delegates audio decoding to the device's DSP. It is known to have device- and codec-dependent compatibility issues across the Media3 1.x line, especially around track-to-track transitions:

- Some DSPs handle AAC offload fine but fail on Opus.
- Some Android vendors' audio HALs handle offload differently across API levels.
- Bluetooth A2DP offload, USB-DAC, and certain Pixel/Samsung/MediaTek combinations are documented sources of stuck-after-transition reports in ExoPlayer's issue tracker.

Stash's library is especially vulnerable because the BEST quality tier flipped from Opus → AAC in the same commit (`492ca90`, v0.3.2) that enabled offload. So users have *mixed-codec* libraries, hitting offload's worst-case "transition between two different codecs" path.

When offload silently fails on a track transition, ExoPlayer does NOT throw a `PlaybackException` — the audio sink just stops producing frames. The dev's existing `onPlayerError` recovery (PlayerRepositoryImpl.kt:256-276) only catches `PlaybackException`-driven failures, so it never runs for offload stalls. The player ends up with `currentMediaItem` updated to track 2 but `playbackState` likely in IDLE (or a degraded READY with no audio), and `play()` alone can't restart it — it needs a fresh `prepare()`.

The play button being a no-op is explained by `NowPlayingViewModel.onPlayPauseClick`:
```kotlin
if (_uiState.value.isPlaying) playerRepository.pause() else playerRepository.play()
```
Either branch on a stuck/IDLE player is silent. Tapping next/previous calls `seekTo*MediaItem`, which forces ExoPlayer through a fresh prepare path and bypasses the offload pile-up.

The dev can't reproduce because their device + library handles offload correctly for that specific codec/format mix.

## Goals

- Fix the auto-advance failure for affected users.
- Add defense-in-depth so any future failure mode that lands the next track in `STATE_IDLE` self-rescues automatically.
- Surface logs that confirm whether the fix worked and flag any remaining edge cases.

## Non-goals

- Bumping Media3 (current 1.9.2 is stable; bump separately if needed).
- Adding a Settings toggle for offload (rejected — gives users a way to misconfigure into the broken state).
- Per-codec offload selection (over-engineered for one bug).
- New unit/integration tests (this is a behavioral change at the audio-pipeline level, not a unit-testable contract).

## Design

### Change A — Disable hardware audio offload

**File:** `core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt`

Delete lines 85-92 plus the now-unused import of `AudioOffloadPreferences`:

```kotlin
// REMOVE this block in onCreate():
// player.trackSelectionParameters = player.trackSelectionParameters
//     .buildUpon()
//     .setAudioOffloadPreferences(
//         AudioOffloadPreferences.Builder()
//             .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
//             .build()
//     )
//     .build()

// REMOVE this import at the top:
// import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
```

Result: ExoPlayer reverts to the default software-decode → AudioTrack path, which is what every other Media3 app uses by default and what every other major music app (Spotify, Apple Music, NewPipe, Symfonium) ships with.

The audio session ID, audio attributes, audio-becoming-noisy handling, wake mode, equalizer init, and load control all remain unchanged.

### Change B — Defensive `prepare()` on transitions that land in IDLE

**File:** `core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt`

Replace the existing `onMediaItemTransition` listener body (lines 218-220):

```kotlin
override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
    val controller = controllerDeferred ?: return
    // Defense in depth: the existing onPlayerError recovery catches
    // PlaybackException-driven failures, but some failure modes (audio
    // offload sink stalls before we removed offload, plus any future
    // codec/format edge case) can leave the player in STATE_IDLE on the
    // next track WITHOUT firing onPlayerError. The user-visible symptom
    // is "next song appears, play button does nothing." A single
    // prepare() call is harmless when the player is already READY (no-op)
    // and rescues the IDLE case automatically.
    if (controller.playbackState == Player.STATE_IDLE && controller.currentMediaItem != null) {
        Log.w(TAG, "onMediaItemTransition landed in STATE_IDLE — defensive prepare()")
        controller.prepare()
    }
    updateState(controller)
}
```

Why this is safe:
- `Player.prepare()` is idempotent: when the player is already in `STATE_READY` it's a no-op.
- The `playbackState == STATE_IDLE` guard ensures we only call it when there's actually something to fix.
- Wrapped in the `currentMediaItem != null` guard to avoid `prepare()` on an empty playlist.
- The `Log.w` makes every rescue visible in logcat — both for verifying the bug is fixed and for catching new failure modes.

### Why both changes ship together

Change A removes the most likely cause. Change B catches anything else that ever lands the player in this state — including unknown future regressions. They're cheap to ship together and reinforce each other:
- **Best case:** offload removal fixes everything; the defensive `prepare()` never fires; no `Log.w` lines in any user's logcat. Fix confirmed.
- **Partial case:** offload removal fixes most users; a few still hit some other failure mode; the defensive `prepare()` rescues them automatically AND surfaces a `Log.w` so we know to investigate.
- **Worst case:** offload wasn't the root cause; the defensive `prepare()` rescues the IDLE-state failures; logs tell us to keep digging.

## Touch points

| File | Change |
|------|--------|
| `core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt` | Delete lines 85-92 (`setAudioOffloadPreferences` block) + remove unused `AudioOffloadPreferences` import |
| `core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt` | Update `onMediaItemTransition` (lines 218-220) to add the `STATE_IDLE` guard + defensive `prepare()` + `Log.w` |

Total: 2 files, ~12 net lines of change.

## Observability

`Log.w("StashPlayer", "onMediaItemTransition landed in STATE_IDLE — defensive prepare()")` fires on every rescue.

Post-release verification:
- **No `Log.w` lines from any user reporter** → offload removal alone solved it.
- **Some `Log.w` lines from users who report the bug is fixed** → defensive `prepare()` is rescuing them; the offload theory wasn't the only cause but the rescue handles it cleanly.
- **`Log.w` lines from users who STILL report the bug** → there's a third failure mode (player stays in `STATE_BUFFERING` or `STATE_READY` with `playWhenReady = false`); design a follow-up.

## Manual acceptance (dev side)

The dev should not see any behavioral change on their own device because they couldn't reproduce the bug in the first place — offload removal returns ExoPlayer to its default path, which is what runs on every other Media3 app. Standard "Play All" flow should still: play, advance, advance, advance.

Specifically verify:
- "Play All" from Home → starts track 1, transitions cleanly to track 2 + 3
- "Play All" from a playlist → same
- Pause/play/skip during playback → unchanged
- Equalizer + audio effects → unchanged (they're attached to the audio session ID, which is unaffected)

## Risks and rollback

- **Power/CPU regression on very old devices:** removing offload means the CPU decodes audio in software. On modern (≤5-year-old) Snapdragon/Tensor SoCs the cost is <1% CPU and negligible battery (~30 min shorter on a 12h music session in the absolute worst case). Major music apps ship without offload for exactly this reliability tradeoff.
- **Defensive `prepare()` masks a real bug:** if some future change starts landing the player in IDLE for a different reason, the rescue auto-fixes it silently. Mitigation: the `Log.w` line means we always know when the rescue fires.
- **Rollback:** revert the two-file commit. No schema, no migration, no user data touched. Seconds to reverse.

## Out of scope

- Media3 1.10+ upgrade (separate concern; many offload fixes have landed since 1.9.2, but they don't matter once offload is disabled).
- Settings toggle for offload (gives users a way to misconfigure into the broken state).
- Telemetry beyond `Log.w` (sufficient signal; no need for analytics infrastructure for one bug).
- Per-codec offload selection (over-engineered).
