# Equalizer Redesign — Design Spec

**Date:** 2026-04-30
**Status:** Brainstormed, awaiting plan
**Author:** rawnaldclark + Claude (brainstorming session)

## Problem

Stash's current equalizer has a class of persistence bugs that produce a uniquely broken user experience:

- User enables EQ, adjusts band gains, force-closes (or background-kills) the app.
- Relaunch: the EQ toggle in the UI displays **off**, but audio is still being equalized.
- Toggling EQ back on **stacks another EQ instance on top** of the residual one — perceptually doubled bands, distortion, etc.

Multiple Android music apps in the wild report the same class of bug (e.g. external GitHub issue: *"Equaliser toggle keeps turning off #22"*). The root cause is the platform `android.media.audiofx.Equalizer` API, not a Stash-specific defect — it is a shared, OS-managed audio effect bus with lifecycle semantics that don't survive process death cleanly.

### Verified failure modes (from code reading)

1. **Wrong default on first read.** `EqualizerStore.kt:87` returns `enabled = true` when the persisted key is missing. Fresh installs and partially-written DataStore boot with EQ effectively-on regardless of UI state.
2. **Init-time race.** `EqualizerManager.initialize()` synchronously sets `eq.enabled = true` at lines 94/102/109 *before* the persisted settings load in a `scope.launch` coroutine. There is a window where hardware EQ is on regardless of user intent.
3. **Orphaned native effects on force-stop.** `audiofx.Equalizer` registers with the system audio framework. On force-stop or OOM kill, `release()` does not run; the native effect *survives the process* and remains bound to the audio session ID.
4. **Stacking on re-enable.** A relaunched process constructs a new `Equalizer(EFFECT_PRIORITY, sessionId)` for the same audio session. The orphan from #3 is still active. Audio passes through both. The new instance's enable/disable doesn't control the orphan.

## Goals

1. **Eliminate the bug class structurally.** No patches that "should" prevent recurrence — the failure modes must be unrepresentable in the new architecture.
2. **Apply premium-music-player best practices.** Match Spotify / Apple Music / Tidal in feature surface and audio fidelity.
3. **Stash-local DSP control.** Stop relying on the platform `audiofx` effect bus.
4. **Migrate user data invisibly.** Existing band gains, presets, and bass-boost level must be preserved across the rebuild. The master toggle force-resets to `off` exactly once, silently — user re-enables in the UI when they want it back.

## Non-Goals

- **Parametric EQ.** v1 is graphic EQ only. Keep the door open architecturally for v2.
- **Per-output profiles** (auto-switch EQ when wired vs Bluetooth headphones connect). Defer.
- **Spectrum visualizer.** Defer.
- **A/B compare.** Defer.
- **Virtualizer / spatial widening.** Drop entirely from current scope.

## Architecture

Three layers with a strict one-way data contract: **one writer, one state, one chain.**

```
UI Layer (Compose)
   ├─ EqualizerScreen  · 5 sliders · curve preview · presets · bass · loudness · pre-amp
   └─ EqualizerViewModel  · collects StateFlow · forwards events to controller

         ↓ events     ↑ state

State Owner (singleton, @ApplicationScope)
   ├─ EqController  · holds StateFlow<EqState> · sole writer · debounced persist
   └─ EqStore  · proto DataStore · atomic typed writes · default-off

         ↓ state snapshot per audio buffer

DSP Layer (Media3 AudioProcessor chain — built once at ExoPlayer creation)
   ├─ PreampGain      · ±12 dB master before EQ
   ├─ BiquadEq        · 5 peaking biquads at 60/230/910/3.6k/14k Hz cascade
   ├─ BassShelf       · low-shelf at 100 Hz · 0..15 dB
   ├─ LoudnessGain    · −14 / −11 LUFS targets · per-track ReplayGain-driven
   └─ SoftClipLimiter · tanh() saturator at chain tail · clamps above −1 dBFS

         ↓ PCM samples

Android AudioTrack → 🔊
```

### Invariants

- **One writer.** Only `EqController` mutates state. UI emits events; processors only read.
- **One state.** `EqState` is the truth. Processors hold no independent flags or coefficients between buffers.
- **One chain.** Built once at `ExoPlayer` creation; never reconstructed. Toggle is a flag flip, not a topology change. **Stacking is unrepresentable.**
- **Default-off.** Missing persisted key → `enabled = false`. New installs are silent until user opts in.
- **Restore-before-render.** `EqController.init { runBlocking { EqStore.read() } }` runs before any AudioProcessor sees its first buffer. Hilt enforces ordering.

### Bug elimination map

| Failure mode (current code) | Why it's structurally impossible in the new design |
|---|---|
| Wrong default on read | `EqStore` returns `enabled=false` on missing key |
| Init-time race vs DataStore | Synchronous read in `EqController.init()` blocks until restored |
| Orphaned native effects on force-stop | Zero `audiofx` instances ever created — all DSP lives in our process |
| Stacking on re-enable | Chain built once; toggle is a Boolean flip; topology never changes |

## Data Model

### `EqState` (in-memory, the single source of truth)

| Field | Type | Range | Default | Notes |
|---|---|---|---|---|
| `enabled` | `Boolean` | — | `false` | Master on/off. Missing key → `false`. |
| `preset` | `PresetId` | built-in or custom UUID | `"flat"` | Pointer; gains derived unless `preset = "custom"`. |
| `gainsDb` | `FloatArray(5)` | −12.0 .. +12.0 | `[0,0,0,0,0]` | Per-band gain at 60 / 230 / 910 / 3.6k / 14k Hz. |
| `preampDb` | `Float` | −12.0 .. +12.0 | `0.0` | Master gain before EQ; prevents clipping when boosting. |
| `bassBoostDb` | `Float` | 0.0 .. +15.0 | `0.0` | Low-shelf at 100 Hz; separate from EQ band 1. |
| `loudnessMode` | enum | `OFF / NORMAL / LOUD` | `OFF` | −14 / −11 LUFS targets. |
| `customPresets` | `List<NamedPreset>` | 0..20 entries | `[]` | Each: UUID + name + gains + preamp. |

### Persistence — Proto DataStore

```protobuf
// core/media/src/main/proto/eq_state.proto
syntax = "proto3";

message EqStateProto {
  int32 schema_version       = 1;  // for future migrations
  bool enabled               = 2;
  string preset_id           = 3;
  repeated float gains_db    = 4;  // always length 5
  float preamp_db            = 5;
  float bass_boost_db        = 6;
  LoudnessMode loudness_mode = 7;
  repeated NamedPreset custom_presets = 8;
}

enum LoudnessMode { OFF = 0; NORMAL = 1; LOUD = 2; }

message NamedPreset {
  string id          = 1;
  string name        = 2;
  repeated float gains_db = 3;
  float preamp_db    = 4;
}
```

**Why proto over Preferences:** atomic typed writes. Preferences DataStore stores fields independently — partial writes during process death can leave EQ in a half-written state. Proto serialises the whole snapshot in one transaction, so either the new state is fully on disk or the old state is.

### Write contract

```
UI event
  → EqController.update { copy(...) }
  → _state.value = new                       (synchronous, processors see next buffer)
  → EqStore.write(new) [debounced 200 ms]    (slider drag doesn't flood DataStore)
  → DataStore atomic flush
```

On app pause / `onStop`, the debounce flushes immediately to ensure no in-flight writes are lost.

### Restore contract

```
EqController @Inject (Hilt-provided as constructor dependency of AudioProcessors)
  → init { runBlocking { EqStore.read() } }
  → _state.value = persisted
  → ExoPlayer constructed AFTER controller (Hilt graph guarantees this ordering)
```

## DSP Layer

All filters use the [W3C Audio EQ Cookbook](https://www.w3.org/TR/audio-eq-cookbook/) ("Robert Bristow-Johnson cookbook") formulas — the canonical reference used by Web Audio API, Chrome, FabFilter, AutoEq, and effectively every audio app on Android.

### Biquad — the atom

```kotlin
fun peakingCoefficients(freqHz: Float, gainDb: Float, q: Float, sr: Int): Coefs {
    val A     = 10f.pow(gainDb / 40f)
    val w0    = 2f * PI * freqHz / sr
    val alpha = sin(w0) / (2f * q)
    val cosw0 = cos(w0)
    return Coefs(
        b0 = 1f + alpha * A,
        b1 = -2f * cosw0,
        b2 = 1f - alpha * A,
        a0 = 1f + alpha / A,
        a1 = -2f * cosw0,
        a2 = 1f - alpha / A,
    ).normalised()  // divide all by a0
}

// Per-sample: Direct Form II Transposed (numerically stable)
fun process(x: Float): Float {
    val y = b0 * x + z1
    z1    = b1 * x - a1 * y + z2
    z2    = b2 * x - a2 * y
    return y
}
```

`z1 / z2` are filter state variables; they keep filter "memory" between samples. Stored as `FloatArray` fields per channel — never reallocated.

### Band layout

| Band | Center | Q | Type | Controls |
|---|---|---|---|---|
| B1 | 60 Hz | 1.0 | Peaking | Sub-bass, kick punch |
| B2 | 230 Hz | 1.0 | Peaking | Bass body, vocal warmth |
| B3 | 910 Hz | 1.0 | Peaking | Midrange, vocal presence |
| B4 | 3.6 kHz | 1.0 | Peaking | Upper mid, vocal clarity |
| B5 | 14 kHz | 1.0 | Peaking | Air, sparkle |

Centers match Spotify Android. Q=1.0 is the graphic-EQ standard so adjacent bands overlap cleanly.

### Coefficient recomputation

- On `EqState` change (slider drag, preset switch, mode toggle) — recompute on next buffer.
- On sample-rate change (Media3 calls `onConfigure()`) — recompute then.
- **Never per-sample.** Per-sample is the 4-multiply biquad inner loop only.

### Bypass

```kotlin
override fun queueInput(buffer: ByteBuffer) {
    if (!controller.state.value.enabled || isFlat()) {
        // Bit-perfect passthrough — output buffer is the input buffer.
        outputBuffer = buffer
        return
    }
    // … process biquad cascade …
}
```

When `enabled = false` *or* every gain is 0, every processor short-circuits. Zero CPU, zero allocation, zero behavior change vs. EQ being absent.

### Performance budget

5 biquads × 2 channels × 48 000 samples/sec × 4 multiplications ≈ **1.9 M mults/sec** ≈ 0.02% of one CPU core. Cost-irrelevant. Avoiding allocation in the hot path is what matters; `z1 / z2` and coefficient arrays are pre-allocated.

## Loudness Normalization

### Targets (Spotify-equivalent)

| Mode | Target | Use case |
|---|---|---|
| Off | (no gain applied) | Bit-perfect passthrough |
| Normal | −14 LUFS | Default for general listening |
| Loud | −11 LUFS | Noisy environments |

### Data source — hybrid (best practice)

**At download time:** add `-replaygain track` to the yt-dlp post-processor. Each download writes `REPLAYGAIN_TRACK_GAIN` and `REPLAYGAIN_ALBUM_GAIN` ID3 tags. Computed once, permanent, industry-standard.

**For existing library:** an idle-time `LoudnessScanWorker` runs ITU-R BS.1770 LUFS measurement on tracks missing the tag. Result cached in a new `loudness_lufs FLOAT` column on `tracks`. Constraints: charging + Wi-Fi (no UI surface — runs invisibly).

**Album vs track gain:** in album playback context, use album gain (preserves intentional dynamics in classical / concept albums). In shuffle / mixed contexts, use track gain.

**Missing data:** if a track has no measured loudness yet, apply 0 dB (no normalization) — never fake a value. Backfill scanner catches it on the next idle cycle.

### Integration

```
Player.Listener.onMediaItemTransition
  → trackDao.getById(id)
  → EqController.setCurrentTrackGain(gainFor(loudness, mode))
  → LoudnessProcessor reads on next audio buffer
```

50 ms gain ramp prevents click between tracks.

### Constants

| Parameter | Value | Why |
|---|---|---|
| Max attenuation | −15 dB | Don't kill quiet podcasts already at −30 LUFS |
| Max boost | +12 dB | Don't blow speakers on very quiet ambient tracks |
| Cross-fade ramp | 50 ms | Smooth gain transitions; prevent click |

### Soft-clip limiter (peak protection)

A `tanh()`-based saturator at the chain tail clamps anything above −1 dBFS. Inaudible until pushed; protects speaker / ear when EQ-boosted-then-loudness-boosted hot tracks would otherwise clip. ~30 LOC.

## UI

### Layout (Compose)

A single `EqualizerScreen` accessible from Settings → Audio → Equalizer (existing entry point retained).

```
┌─────────────────────────────────┐
│  ← Equalizer            [toggle]│  ← header: master on/off
├─────────────────────────────────┤
│  5-BAND EQ                      │
│   ┌─ live curve preview ─┐      │
│   │     ⌒\__/⌒\___       │      │  ← curve updates in real time
│   └──────────────────────┘      │
│                                 │
│   |    |    |    |    |         │
│   ●    ●    ●    ●    ●         │  ← 5 vertical sliders
│   |    |    |    |    |         │
│  60  230  910  3.6k 14k         │
│                                 │
│  [Flat][Custom][Bass][Vocal]... │  ← preset chips + "+" save
├─────────────────────────────────┤
│  BASS BOOST     ▬●━━━━ +5 dB    │  ← dedicated slider
├─────────────────────────────────┤
│  VOLUME LEVELLING               │
│  [ Off ][ Normal ][ Loud ]      │  ← segmented control
├─────────────────────────────────┤
│  PRE-AMP        ━━━●━━━  0 dB   │
└─────────────────────────────────┘
```

### Behaviors

- **Master toggle** — single tap on/off. State always matches what audio is doing (one-state invariant).
- **Live curve preview** — reactive composable that recomputes the response curve from current gains. Spotify and YT Music both ship this; defines premium feel.
- **Slider drag** — emits `EqController.setBandGain(idx, dB)`; debounced 200 ms before persistence.
- **Preset tap** — emits `EqController.setPreset(id)`; resets gains to preset values.
- **Custom preset save (`+` chip)** — opens `CreatePresetDialog`, captures name, calls `EqController.saveCurrentAsPreset(name)`.
- **Bass Boost slider** — independent low-shelf; separate from EQ band 1 because users expect a dedicated bass control.
- **Volume Levelling segmented control** — Off / Normal / Loud, in plain English, no LUFS jargon.
- **Pre-amp slider** — quiet, unobtrusive; helps audiophiles avoid clipping when boosting.

### Built-in presets

Flat (0,0,0,0,0), Bass Boost (+5,+3,0,0,0), Treble Boost (0,0,0,+3,+5), Vocal (-2,0,+3,+2,0), Rock (+4,+2,-1,+2,+3), Pop (-1,+2,+3,+2,-1), Jazz (+3,+2,0,+1,+3), Classical (+4,+3,-2,0,+2 — gentle mid scoop, lift at the extremes).

Each is a record in `PresetCatalog.kt`. Custom presets are appended to `EqState.customPresets` and serialised with the rest of the state.

## Migration

One-shot translator runs on first launch of the new code:

```kotlin
class EqMigration {
    suspend fun migrateIfNeeded() {
        if (newStore.exists()) return  // already migrated
        val old = oldStore.read() ?: return
        newStore.write(EqStateProto.newBuilder()
            .setSchemaVersion(1)
            .setEnabled(false)                          // FORCED OFF — bug-fix anchor
            .setPresetId(mapPresetName(old.preset))
            .addAllGainsDb(adaptGains(old.customGains))  // resample to 5 bands if needed
            .setPreampDb(0f)                             // new field; default
            .setBassBoostDb(old.bassBoostStrength / 1000f * 15f)  // scale 0..1000 → 0..15
            .setLoudnessMode(if (old.loudnessGainMb > 0) NORMAL else OFF)
            .build())
        oldStore.delete()
    }
}
```

| Old (`equalizer_prefs`) | → | New (`eq_state.pb`) | Strategy |
|---|---|---|---|
| `enabled` | → | `enabled` | **Forced `false`** — silent reset, no banner |
| `customGains[5..10]` | → | `gains_db[5]` | Resample to 5 bands if device returned a different count |
| `preset` | → | `preset_id` | Map old enum names to new IDs |
| `bassBoostStrength` (0..1000) | → | `bass_boost_db` (0..15) | Scale linearly |
| `virtualizerStrength` | → | — | Dropped (out of scope) |
| `loudnessGainMb` | → | `loudness_mode` | If > 0 → `NORMAL`; else `OFF` |

The forced reset is silent — no banner, no warning. User opens EQ screen, sees their old gain sliders preserved exactly where they left them, but the master toggle is off. They flip it on if they want it. Clean line between old buggy world and new clean world.

## Module / File Layout

```
core/media/src/main/kotlin/com/stash/core/media/equalizer/
  ─ DELETED: EqualizerManager.kt          # replaced by EqController + processors
  ─ DELETED: EqualizerSettings.kt         # replaced by EqState + proto
  ─ DELETED: EqualizerStore.kt            # replaced by EqStore (proto)
  ─ DELETED: EqPreset.kt                  # reshaped into PresetId + PresetCatalog
  + NEW:     EqController.kt              # single state owner
  + NEW:     EqStore.kt                   # proto DataStore wrapper
  + NEW:     EqMigration.kt               # one-shot old→new translator
  + NEW:     PresetCatalog.kt             # built-in + custom presets
  + NEW:     dsp/
              ├─ Biquad.kt                # cookbook coefficients + DF2T process
              ├─ PreampProcessor.kt       # ~30 LOC
              ├─ EqProcessor.kt           # 5-band cascade, stereo
              ├─ BassShelfProcessor.kt    # low-shelf at 100 Hz
              ├─ LoudnessProcessor.kt     # gain ramp, ReplayGain-driven
              └─ SoftClipLimiter.kt       # tanh() saturator
core/media/src/main/proto/
  + NEW:     eq_state.proto

core/data/src/main/kotlin/com/stash/core/data/db/
  + MODIFIED: TrackEntity.kt              # add loudness_lufs FLOAT? column
  + NEW migration                         # ALTER TABLE tracks ADD COLUMN loudness_lufs

data/download/src/main/kotlin/com/stash/data/download/
  + MODIFIED: DownloadManager.kt          # add -replaygain to ffmpeg post-processor

core/data/src/main/kotlin/com/stash/core/data/sync/workers/
  + NEW:      LoudnessScanWorker.kt       # idle-time BS.1770 backfill

feature/settings/src/main/kotlin/com/stash/feature/settings/
  + REWRITTEN: equalizer/EqualizerScreen.kt   # Spotify-style UI
  + REWRITTEN: equalizer/EqualizerViewModel.kt # talks to EqController
```

## Testing

### Unit tests (must pass before merge)

- **`BiquadTest`** — coefficient calculation matches reference (RBJ cookbook examples within ±1e-6); flat at 0 dB gain produces identity output.
- **`EqProcessorTest`** — chain bypasses entirely on `enabled=false` (verify output buffer === input buffer); single-band boost produces measurable gain at center frequency in FFT.
- **`LoudnessProcessorTest`** — gain ramp over 50 ms; missing-data → 0 dB; max attenuation / boost caps respected.
- **`SoftClipLimiterTest`** — pure sine at 0 dBFS clamps under −1 dBFS; no clipping artifacts.
- **`EqStoreTest`** — round-trip persist + restore preserves all fields; missing key → defaults; corrupted file → defaults.
- **`EqMigrationTest`** — old DataStore with various states migrates to expected proto; force-off invariant holds; old store deleted on success.

### Integration tests

- **`EqControllerIntegrationTest`** — UI events flow to processors; debounced persistence; ExoPlayer-Hilt ordering verified.
- **Bug regression test** — toggle on, simulate process death (recreate components), assert UI/audio agree on `enabled=false` post-migration.

### Manual QA (the bug we're fixing)

1. Fresh install — verify EQ defaults off.
2. Enable EQ, set custom gains, force-stop, relaunch — UI off, audio off (the original bug).
3. Re-enable EQ — gains restored, audio matches UI, no stacking.
4. Enable EQ, kill process via OOM-simulator, relaunch — same as #2.
5. Enable Loudness, play 5 tracks of varying loudness — perceived volume should be uniform.
6. Toggle Loudness off mid-track — clean ramp, no click.

## Risks & Mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| Biquad coefficients off-by-one error | Low | Unit-test against RBJ reference values |
| `runBlocking` in `EqController.init` causes ANR if DataStore I/O is slow | Low | DataStore reads are typically ≤5 ms; if slow, profile and consider lazy initialization with audio-bypass during the gap |
| ReplayGain tags not respected by some devices' yt-dlp builds | Medium | LoudnessScanWorker fills the gap on first idle |
| Migration corrupts user's custom presets | Low | Migration is one-shot; old DataStore retained until new write succeeds |
| Soft-clip limiter audibly affects clean tracks | Very low | Threshold at −1 dBFS; tanh() shape is inaudible until clipping; A/B test against bypass |
| LoudnessScanWorker drains battery / data | Medium | Constraints: charging + Wi-Fi only; one-shot per track; cap at 100 tracks per run |

## Open Questions for Plan Phase

- Exact Hilt scoping for `EqController` (`@Singleton` likely, but verify against ExoPlayer's lifecycle).
- Whether the proto schema_version field needs forward-compat handling now or can wait until v2.
- Whether to expose `currentLoudnessGain` as a debug log so we can verify normalization is doing what we expect during dogfood.
