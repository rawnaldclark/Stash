# Playlist Auto-Advance Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix GitHub issue #17 — "Play All" auto-advance silently fails on a subset of users' devices, leaving the next track loaded but not playing and the play button non-functional.

**Architecture:** Two surgical changes — (1) disable hardware audio offload in `StashPlaybackService` to remove the most likely cause (offload sink stalls during track transitions on certain device/codec combos), (2) add a defensive `prepare()` call in `PlayerRepositoryImpl.onMediaItemTransition` when the new track lands in `STATE_IDLE`, with a `Log.w` line that surfaces every rescue. Belt + braces: best case the offload removal alone fixes everything; worst case the defensive `prepare()` rescues other unknown failure modes and tells us so via logs.

**Tech Stack:** Kotlin, Android, Media3 1.9.2 (`MediaController` + `MediaSessionService` + `ExoPlayer`).

**Spec:** `docs/superpowers/specs/2026-04-24-auto-advance-fix-design.md`

---

## Pre-flight

- [ ] Create a fresh worktree from `master` (avoids interference with the in-flight `preview-latency-fix` worktree):

```bash
cd C:/Users/theno/Projects/MP3APK
git worktree add .worktrees/auto-advance-fix -b fix/auto-advance master
cp local.properties .worktrees/auto-advance-fix/local.properties
```

Memory note `feedback_worktree_local_properties.md`: `git worktree add` doesn't carry `local.properties`; the `cp` line above prevents the Last.fm/keystore "Not configured" debug-build symptom.

**All subsequent tasks operate in:** `C:/Users/theno/Projects/MP3APK/.worktrees/auto-advance-fix`. Every Bash command must begin with `cd C:/Users/theno/Projects/MP3APK/.worktrees/auto-advance-fix && ...`. Read/Edit tools should use absolute paths rooted at that worktree.

---

## Task 1: Disable hardware audio offload

**Verified facts:**
- `StashPlaybackService.kt` lives at `core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt`
- Offload is enabled at lines 85-92 inside `onCreate()`:
  ```kotlin
  player.trackSelectionParameters = player.trackSelectionParameters
      .buildUpon()
      .setAudioOffloadPreferences(
          AudioOffloadPreferences.Builder()
              .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
              .build()
      )
      .build()
  ```
- The unused-after-removal import is at line 9: `import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences`
- No tests exist that exercise this block; nothing else in the codebase references `AudioOffloadPreferences`

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt`

- [ ] **Step 1: Read the file to confirm line numbers**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/auto-advance-fix && \
grep -n "setAudioOffloadPreferences\|AudioOffloadPreferences" core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt
```

Expected: 3 matches — the import (line 9), the `setAudioOffloadPreferences(` opener (around line 87), the inner `AudioOffloadPreferences.Builder()` and `.AUDIO_OFFLOAD_MODE_ENABLED` references. If line numbers shifted, adjust the edit accordingly.

- [ ] **Step 2: Delete the offload block + import**

In `StashPlaybackService.kt`:

1. **Delete the import** (top of file, around line 9):
```kotlin
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
```

2. **Delete the entire block in `onCreate()`** (around lines 83-92, which includes the leading `// Enable hardware audio offload — ...` comment if present):
```kotlin
// Enable hardware audio offload — delegates decoding to the DSP,
// reducing CPU wake-ups, jitter, and power consumption.
player.trackSelectionParameters = player.trackSelectionParameters
    .buildUpon()
    .setAudioOffloadPreferences(
        AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
            .build()
    )
    .build()
```

The `player.audioSessionId = audioSessionId` line that follows the block stays — it's unrelated to offload.

- [ ] **Step 3: Build-check**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/auto-advance-fix && \
./gradlew :core:media:assembleDebug
```

Expected: BUILD SUCCESSFUL. If you see `Unresolved reference: AudioOffloadPreferences`, you missed a usage; grep again to find it.

- [ ] **Step 4: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/auto-advance-fix && \
git add core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt && \
git commit -m "fix(playback): disable hardware audio offload — fixes #17 stuck auto-advance"
```

Verify branch: `git branch --show-current` → `fix/auto-advance`.

---

## Task 2: Defensive `prepare()` in `onMediaItemTransition`

**Verified facts:**
- `PlayerRepositoryImpl.kt` at `core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt`
- Existing `onMediaItemTransition` listener (lines 218-220):
  ```kotlin
  override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
      controllerDeferred?.let { updateState(it) }
  }
  ```
- `Player` is already imported (line 11): `import androidx.media3.common.Player`
- `Log` is already imported (line 7): `import android.util.Log`
- `TAG = "StashPlayer"` is in the companion object (line 319)
- `controller.playbackState` and `controller.currentMediaItem` are MediaController properties; `controller.prepare()` is the same method already used in the existing `onPlayerError` recovery (line 269)

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt:218-220`

- [ ] **Step 1: Replace the `onMediaItemTransition` body**

Locate the existing listener and replace its body:

```kotlin
override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
    val controller = controllerDeferred ?: return
    // Defense in depth: the existing onPlayerError recovery catches
    // PlaybackException-driven failures, but some failure modes (audio
    // offload sink stalls before we removed offload, plus any future
    // codec/format edge case) can leave the player in STATE_IDLE on the
    // next track WITHOUT firing onPlayerError. The user-visible symptom
    // is "next song appears, play button does nothing." A single
    // prepare() call is a no-op when the player is already READY and
    // rescues the IDLE case automatically.
    if (controller.playbackState == Player.STATE_IDLE && controller.currentMediaItem != null) {
        Log.w(TAG, "onMediaItemTransition landed in STATE_IDLE — defensive prepare()")
        controller.prepare()
    }
    updateState(controller)
}
```

Notes:
- No new imports needed — `Player`, `Log`, `MediaItem`, `MediaController` are all imported already
- The `controllerDeferred?.let` pattern in the existing body is preserved as `val controller = controllerDeferred ?: return` for clarity
- `updateState(controller)` is still called at the end so UI state stays in sync (same as before)

- [ ] **Step 2: Build-check**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/auto-advance-fix && \
./gradlew :core:media:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run any existing tests for this module**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/auto-advance-fix && \
./gradlew :core:media:test
```

Expected: green (existing tests should not be affected; no new tests added per the spec — this change is at the audio-pipeline level, not unit-testable).

- [ ] **Step 4: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/auto-advance-fix && \
git add core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt && \
git commit -m "fix(playback): defensive prepare() on transitions that land in STATE_IDLE"
```

---

## Task 3: Device acceptance

This is not a Gradle task — it exercises real ExoPlayer + MediaSession behavior.

- [ ] **Step 1: Build + install debug APK**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/auto-advance-fix && \
./gradlew :app:installDebug
```

If `No connected devices`, plug your phone in (USB debugging on, "File transfer" mode) and re-run. The release blocks until installation succeeds.

- [ ] **Step 2: Acceptance flow on device**

The dev couldn't reproduce the original bug, so this is **regression testing** — verify nothing got worse for the dev's normal flow:

1. **Play All from Home tab** on any playlist with ≥3 downloaded tracks → Track 1 plays, advances to track 2 cleanly, advances to track 3 cleanly.
2. **Play All from PlaylistDetail screen** → same expectation.
3. **Manual play/pause/skip during playback** → all unchanged.
4. **Equalizer + audio effects** → still work (offload removal doesn't affect the audio session ID that effects bind to).
5. **Bluetooth headphones (if available)** → playback works, AVRCP transport controls work.

If any of the above regresses, STOP and investigate. The fix removes a perf optimization, not a correctness mechanism — regression here would be unexpected.

- [ ] **Step 3: Logcat sanity check (optional but recommended)**

In a separate terminal during step 2:
```bash
adb logcat -s StashPlayer:V ExoPlayer:V -v time
```

You should NOT see the line `onMediaItemTransition landed in STATE_IDLE — defensive prepare()` during normal playback on the dev's device. If you do, the rescue is firing on the dev's hardware too — interesting data point, file follow-up.

- [ ] **Step 4: Empty commit recording acceptance**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/auto-advance-fix && \
git commit --allow-empty -m "test: manual device acceptance — auto-advance unchanged for dev's flow"
```

---

## Task 4: Version bump + release 0.6.3

**Note on version:** at the time of writing, `master` is at `v0.6.2`. The preview-latency fix (`fix/preview-latency` branch) was tagged for `0.6.3` but is on hold pending diagnosis. Since this auto-advance fix is shipping next, it claims `0.6.3`. If the latency work resumes later it will be `0.6.4`.

**Files:**
- Modify: `app/build.gradle.kts` (`versionCode` + `versionName`)

- [ ] **Step 1: Bump version**

In `app/build.gradle.kts`, change:
```kotlin
versionCode = 30
versionName = "0.6.2"
```
to:
```kotlin
versionCode = 31
versionName = "0.6.3"
```

- [ ] **Step 2: Release commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/auto-advance-fix && \
git add app/build.gradle.kts && \
git commit -m "$(cat <<'EOF'
feat: 0.6.3 — auto-advance fix (#17)

Some users reported that "Play All" plays the first track but then
gets stuck — the next track loads but doesn't begin playing, and the
play button stops responding until you tap next or previous.

Root cause: hardware audio offload (enabled in v0.3.2 for battery
savings) silently fails track-to-track transitions on a subset of
device/codec combinations, especially when a playlist mixes Opus and
AAC tracks. ExoPlayer doesn't surface these failures as exceptions,
so the existing recovery handler never ran.

Fix:
- Disable hardware audio offload entirely (matches what Spotify,
  Apple Music, NewPipe, Symfonium ship with by default)
- Add a defensive prepare() in onMediaItemTransition for any future
  failure mode that lands the player in STATE_IDLE — surfaces a
  Log.w on every rescue so regressions are visible

Battery impact: negligible (<1% CPU on modern phones).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 3: Merge to master**

```bash
cd C:/Users/theno/Projects/MP3APK && \
git merge --ff-only fix/auto-advance && \
git log --oneline -5
```

Expected: fast-forward succeeds (we cut from master, no divergence).

- [ ] **Step 4: Tag + push**

```bash
cd C:/Users/theno/Projects/MP3APK && \
git tag -a v0.6.3 -m "v0.6.3 — auto-advance fix (#17)" && \
git push origin master && \
git push origin v0.6.3
```

- [ ] **Step 5: GitHub release + close issue**

```bash
cd C:/Users/theno/Projects/MP3APK && \
gh release create v0.6.3 --title "v0.6.3 — Auto-advance fix" --notes "$(cat <<'EOF'
Fixes [#17](https://github.com/rawnaldclark/Stash/issues/17): Playlists/Mixes do not auto-advance to the next song.

**Root cause:** hardware audio offload (enabled in v0.3.2 for battery savings) silently fails track-to-track transitions on a subset of device/codec combinations. ExoPlayer doesn't surface these failures as exceptions, so the existing recovery handler never ran.

**Fix:**
- Disable hardware audio offload entirely (matches Spotify / Apple Music / NewPipe defaults)
- Add a defensive `prepare()` in `onMediaItemTransition` for any future failure mode that lands the player in `STATE_IDLE` — auto-rescues + emits a `Log.w` so regressions are visible

If you were affected by #17, this should fix you. If you still see auto-advance failures after upgrading, please reopen the issue with `adb logcat -s StashPlayer` output.

**Battery impact:** negligible (<1% CPU increase on modern phones).
EOF
)"
```

Then close issue #17 with a comment pointing at the release:
```bash
cd C:/Users/theno/Projects/MP3APK && \
gh issue close 17 --comment "Fixed in v0.6.3 — see release notes. Please reopen if it recurs after upgrading."
```

- [ ] **Step 6: Clean up worktree**

```bash
cd C:/Users/theno/Projects/MP3APK && \
git worktree remove .worktrees/auto-advance-fix && \
git branch -d fix/auto-advance
```

If the worktree remove fails with a Windows path-length error (known issue with deep Gradle build directories), use `git worktree remove --force` or leave the directory for later manual cleanup — the release is already out.

---

## Skills reference

- @superpowers:verification-before-completion — run before declaring each task done; do not skip the device acceptance in Task 3
- @superpowers:systematic-debugging — if Task 3 surfaces a regression on the dev's flow

## Risks / rollback

- **Rollback:** `git revert v0.6.3..v0.6.2` then bump to a fresh patch version. Users on 0.6.3 see the offload re-enabled; auto-advance bug returns. Acceptable rollback risk.
- **CPU/battery regression:** documented as negligible. If users report worse battery: ship 0.6.4 that adds a Settings toggle to opt back into offload (rejected from this scope per the spec).
- **`Log.w` "rescue" lines fire on dev's device:** unexpected data point — would mean defensive `prepare()` is firing for some other reason. Investigate as follow-up.
