# Equalizer Rebuild — Plan 1 (EQ Engine + UI)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the EQ persistence/stacking bug class by replacing `android.media.audiofx.Equalizer` with a custom DSP chain owned by a single state controller. Ship a Spotify-style 5-band graphic EQ UI on top of it.

**Architecture:** UI (Compose) → `EqController` singleton (the only writer of `EqState`) → Media3 `AudioProcessor` chain (built once at ExoPlayer creation; toggles via flag flip, never topology change). Persistence via kotlinx.serialization JSON in a single Preferences DataStore key — atomic per write, default-off on missing key.

**Tech Stack:** Kotlin · Jetpack Compose · Hilt · Media3 ExoPlayer · DataStore Preferences · kotlinx.serialization-json · JUnit + Truth.

**Spec reference:** `docs/superpowers/specs/2026-04-30-equalizer-redesign-design.md`

**Scope (this plan):**
- ✅ Custom DSP: `Biquad`, `PreampProcessor`, `EqProcessor`, `BassShelfProcessor`
- ✅ State owner: `EqController` + `EqState` + `EqStore`
- ✅ Migration from old buggy `EqualizerStore` (silent, force `enabled=false`)
- ✅ New `EqualizerScreen` Compose UI (header toggle, curve preview, 5 sliders, presets, bass slider, pre-amp)
- ✅ Delete old code: `EqualizerManager`, `EqualizerSettings`, `EqualizerStore`, `EqPreset`

**Deferred to Plan 2 (separate spec/plan):**
- ❌ Loudness normalization (`LoudnessProcessor`, `SoftClipLimiter`)
- ❌ ReplayGain at download (`DownloadManager` mods)
- ❌ `LoudnessScanWorker`
- ❌ `tracks.loudness_lufs` DB column
- ❌ "Volume Levelling" segmented control in UI

**Tech notes:**
- Spec calls for proto DataStore. We'll deviate to **kotlinx.serialization JSON in a single Preferences DataStore key** — atomic per write (one key = one transaction), zero new Gradle plugins, leverages the project's existing kotlinx-serialization setup. Functionally equivalent for the bug fix.
- Schema versioning is via a `schemaVersion: Int = 1` field in `EqState`.

---

## File Structure

### New files

```
core/media/src/main/kotlin/com/stash/core/media/equalizer/
  ├─ EqState.kt              # @Serializable data class, defaults, presets
  ├─ PresetCatalog.kt        # built-in presets registry
  ├─ EqStore.kt              # Preferences DataStore wrapper, JSON-encoded
  ├─ EqMigration.kt          # one-shot old→new translator
  ├─ EqController.kt         # @Singleton state owner
  ├─ StashRenderersFactory.kt # custom RenderersFactory wiring AudioProcessors
  └─ dsp/
      ├─ Biquad.kt            # cookbook coefficients + DF2T process
      ├─ PreampProcessor.kt   # ±12 dB master gain
      ├─ EqProcessor.kt       # 5-band biquad cascade, stereo
      └─ BassShelfProcessor.kt # low-shelf at 100 Hz
```

### Modified files

```
core/media/src/main/kotlin/com/stash/core/media/
  ├─ service/StashPlaybackService.kt:75        # use StashRenderersFactory
  └─ preview/PreviewPlayer.kt:195              # use StashRenderersFactory
core/media/src/main/kotlin/com/stash/core/media/di/
  └─ MediaModule.kt                            # provide EqController, AudioProcessors

feature/settings/src/main/kotlin/com/stash/feature/settings/equalizer/
  ├─ EqualizerScreen.kt                        # rewrite per mockup
  └─ EqualizerViewModel.kt                     # talks to EqController
```

### Deleted files (Task 14)

```
core/media/src/main/kotlin/com/stash/core/media/equalizer/
  ├─ EqualizerManager.kt
  ├─ EqualizerSettings.kt
  ├─ EqualizerStore.kt
  └─ EqPreset.kt
```

### Test files (one per source unit)

```
core/media/src/test/kotlin/com/stash/core/media/equalizer/
  ├─ EqStateTest.kt
  ├─ PresetCatalogTest.kt
  ├─ EqStoreTest.kt
  ├─ EqMigrationTest.kt
  ├─ EqControllerTest.kt
  └─ dsp/
      ├─ BiquadTest.kt
      ├─ PreampProcessorTest.kt
      ├─ EqProcessorTest.kt
      └─ BassShelfProcessorTest.kt
```

---

## Task 1 — `EqState` data class + serialization

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/equalizer/EqState.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/equalizer/EqStateTest.kt`

- [ ] **Step 1.1: Write the failing test**

```kotlin
// EqStateTest.kt
package com.stash.core.media.equalizer

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class EqStateTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test fun `default state has enabled=false and zero gains`() {
    val s = EqState()
    assertThat(s.enabled).isFalse()
    assertThat(s.gainsDb).asList().containsExactly(0f, 0f, 0f, 0f, 0f).inOrder()
    assertThat(s.preampDb).isEqualTo(0f)
    assertThat(s.bassBoostDb).isEqualTo(0f)
    assertThat(s.presetId).isEqualTo("flat")
    assertThat(s.customPresets).isEmpty()
    assertThat(s.schemaVersion).isEqualTo(1)
  }

  @Test fun `round-trip JSON preserves all fields`() {
    val original = EqState(
      enabled = true,
      presetId = "rock",
      gainsDb = floatArrayOf(4f, 2f, -1f, 2f, 3f),
      preampDb = -2f,
      bassBoostDb = 5f,
    )
    val encoded = json.encodeToString(EqState.serializer(), original)
    val decoded = json.decodeFromString(EqState.serializer(), encoded)
    assertThat(decoded).isEqualTo(original)
  }

  @Test fun `custom preset is serialisable`() {
    val s = EqState(customPresets = listOf(NamedPreset("u1", "My Mix",
      floatArrayOf(2f, 0f, 0f, 0f, 2f), 0f)))
    val encoded = json.encodeToString(EqState.serializer(), s)
    val decoded = json.decodeFromString(EqState.serializer(), encoded)
    assertThat(decoded.customPresets).hasSize(1)
    assertThat(decoded.customPresets[0].name).isEqualTo("My Mix")
  }
}
```

- [ ] **Step 1.2: Run the test to verify it fails**

Run: `./gradlew :core:media:testDebugUnitTest --tests "*EqStateTest*"`
Expected: FAIL — `EqState` not found.

- [ ] **Step 1.3: Implement `EqState.kt`**

```kotlin
// EqState.kt
package com.stash.core.media.equalizer

import kotlinx.serialization.Serializable

/**
 * Single source of truth for equalizer state.
 *
 * Held by [EqController]; serialised by [EqStore]. Mutations flow through the
 * controller's `update {}` only — UI emits events, processors only read.
 *
 * Defaults are intentionally "everything off" so a missing or corrupted
 * persisted state cannot accidentally enable EQ — the root of the legacy
 * "EQ off but audio still EQ'd" bug.
 */
@Serializable
data class EqState(
  val schemaVersion: Int = 1,
  val enabled: Boolean = false,
  val presetId: String = "flat",
  val gainsDb: FloatArray = floatArrayOf(0f, 0f, 0f, 0f, 0f),
  val preampDb: Float = 0f,
  val bassBoostDb: Float = 0f,
  val customPresets: List<NamedPreset> = emptyList(),
) {
  // FloatArray needs explicit equals/hashCode for data-class semantics.
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is EqState) return false
    return schemaVersion == other.schemaVersion &&
      enabled == other.enabled &&
      presetId == other.presetId &&
      gainsDb.contentEquals(other.gainsDb) &&
      preampDb == other.preampDb &&
      bassBoostDb == other.bassBoostDb &&
      customPresets == other.customPresets
  }
  override fun hashCode(): Int {
    var r = schemaVersion
    r = 31 * r + enabled.hashCode()
    r = 31 * r + presetId.hashCode()
    r = 31 * r + gainsDb.contentHashCode()
    r = 31 * r + preampDb.hashCode()
    r = 31 * r + bassBoostDb.hashCode()
    r = 31 * r + customPresets.hashCode()
    return r
  }
}

@Serializable
data class NamedPreset(
  val id: String,
  val name: String,
  val gainsDb: FloatArray,
  val preampDb: Float,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is NamedPreset) return false
    return id == other.id && name == other.name &&
      gainsDb.contentEquals(other.gainsDb) && preampDb == other.preampDb
  }
  override fun hashCode(): Int {
    var r = id.hashCode()
    r = 31 * r + name.hashCode()
    r = 31 * r + gainsDb.contentHashCode()
    r = 31 * r + preampDb.hashCode()
    return r
  }
}
```

- [ ] **Step 1.4: Run the test to verify it passes**

Run: `./gradlew :core:media:testDebugUnitTest --tests "*EqStateTest*"`
Expected: PASS (3 tests).

- [ ] **Step 1.5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/equalizer/EqState.kt \
        core/media/src/test/kotlin/com/stash/core/media/equalizer/EqStateTest.kt
git commit -m "feat(eq): add EqState data class with defaults and JSON round-trip"
```

---

## Task 2 — `PresetCatalog` (built-in presets)

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/equalizer/PresetCatalog.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/equalizer/PresetCatalogTest.kt`

- [ ] **Step 2.1: Write failing test**

```kotlin
// PresetCatalogTest.kt
package com.stash.core.media.equalizer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PresetCatalogTest {
  @Test fun `flat has all zero gains`() {
    val flat = PresetCatalog.byId("flat")!!
    assertThat(flat.gainsDb.toList()).containsExactly(0f, 0f, 0f, 0f, 0f).inOrder()
  }

  @Test fun `bass preset boosts low bands`() {
    val bass = PresetCatalog.byId("bass")!!
    assertThat(bass.gainsDb[0]).isGreaterThan(0f) // 60 Hz boosted
    assertThat(bass.gainsDb[1]).isGreaterThan(0f) // 230 Hz boosted
  }

  @Test fun `byId returns null for unknown id`() {
    assertThat(PresetCatalog.byId("nope")).isNull()
  }

  @Test fun `all presets have 5-element gain array`() {
    PresetCatalog.builtIn.forEach { p ->
      assertThat(p.gainsDb).hasLength(5)
    }
  }

  @Test fun `built-in ids are unique`() {
    val ids = PresetCatalog.builtIn.map { it.id }
    assertThat(ids).containsNoDuplicates()
  }
}
```

- [ ] **Step 2.2: Run test → FAIL**

`./gradlew :core:media:testDebugUnitTest --tests "*PresetCatalogTest*"`

- [ ] **Step 2.3: Implement `PresetCatalog.kt`**

```kotlin
// PresetCatalog.kt
package com.stash.core.media.equalizer

/**
 * Built-in graphic-EQ presets. Gain values match the spec
 * (docs/superpowers/specs/2026-04-30-equalizer-redesign-design.md §UI).
 *
 * Each preset is a [NamedPreset] for symmetry with user-saved presets;
 * built-in IDs are stable strings so persisted state survives re-orderings.
 */
object PresetCatalog {
  val builtIn: List<NamedPreset> = listOf(
    NamedPreset("flat",       "Flat",         floatArrayOf( 0f,  0f,  0f,  0f,  0f), 0f),
    NamedPreset("bass",       "Bass Boost",   floatArrayOf(+5f, +3f,  0f,  0f,  0f), 0f),
    NamedPreset("treble",     "Treble Boost", floatArrayOf( 0f,  0f,  0f, +3f, +5f), 0f),
    NamedPreset("vocal",      "Vocal",        floatArrayOf(-2f,  0f, +3f, +2f,  0f), 0f),
    NamedPreset("rock",       "Rock",         floatArrayOf(+4f, +2f, -1f, +2f, +3f), 0f),
    NamedPreset("pop",        "Pop",          floatArrayOf(-1f, +2f, +3f, +2f, -1f), 0f),
    NamedPreset("jazz",       "Jazz",         floatArrayOf(+3f, +2f,  0f, +1f, +3f), 0f),
    NamedPreset("classical",  "Classical",    floatArrayOf(+4f, +3f, -2f,  0f, +2f), 0f),
  )

  fun byId(id: String): NamedPreset? = builtIn.firstOrNull { it.id == id }

  /** Combined catalog for UI display; built-ins first, custom after. */
  fun allFor(state: EqState): List<NamedPreset> = builtIn + state.customPresets
}
```

- [ ] **Step 2.4: Run test → PASS**

- [ ] **Step 2.5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/equalizer/PresetCatalog.kt \
        core/media/src/test/kotlin/com/stash/core/media/equalizer/PresetCatalogTest.kt
git commit -m "feat(eq): add built-in preset catalog (flat/bass/rock/etc)"
```

---

## Task 3 — `Biquad` DSP atom

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/equalizer/dsp/Biquad.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/equalizer/dsp/BiquadTest.kt`

- [ ] **Step 3.1: Write failing test**

```kotlin
// BiquadTest.kt
package com.stash.core.media.equalizer.dsp

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class BiquadTest {
  @Test fun `0 dB gain produces identity output`() {
    val bq = Biquad().apply { setPeaking(freqHz = 1000f, gainDb = 0f, q = 1f, sampleRate = 48_000) }
    val input = floatArrayOf(0.5f, -0.3f, 0.8f, -0.2f)
    input.forEachIndexed { i, x ->
      assertThat(bq.process(x)).isWithin(1e-5f).of(x)
    }
  }

  @Test fun `coefficients normalise so a0 == 1`() {
    val bq = Biquad().apply { setPeaking(1000f, +6f, 1f, 48_000) }
    // After internal normalisation, a0 is implicit (1f).
    // Sanity check: feeding a unit impulse produces a finite, bounded response.
    val impulse = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    val out = impulse.map { bq.process(it) }
    out.forEach { assertThat(it.isFinite()).isTrue() }
  }

  @Test fun `peak filter at center freq amplifies steady-state sine`() {
    // Drive pure 1 kHz sine through a +6 dB peak at 1 kHz, expect ~2x amplitude.
    val sr = 48_000
    val freq = 1000f
    val bq = Biquad().apply { setPeaking(freq, +6f, 1f, sr) }
    val nSamples = 4096
    val out = FloatArray(nSamples)
    var maxOut = 0f
    for (i in 0 until nSamples) {
      val x = sin(2.0 * PI * freq * i / sr).toFloat()
      out[i] = bq.process(x)
      // After ~200 samples settling, track max
      if (i > 200) maxOut = maxOf(maxOut, kotlin.math.abs(out[i]))
    }
    // +6 dB ≈ 1.995x amplitude
    assertThat(maxOut).isWithin(0.05f).of(1.995f)
  }

  @Test fun `reset clears delay line state`() {
    val bq = Biquad().apply { setPeaking(1000f, +6f, 1f, 48_000) }
    repeat(100) { bq.process(0.5f) }
    bq.reset()
    // After reset, feeding 0 should produce ~0 (no residual energy from delay line)
    val zeroOut = bq.process(0f)
    assertThat(zeroOut).isWithin(1e-6f).of(0f)
  }
}
```

- [ ] **Step 3.2: Run test → FAIL**

- [ ] **Step 3.3: Implement `Biquad.kt`**

```kotlin
// Biquad.kt
package com.stash.core.media.equalizer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * One biquad peaking filter (the atom of our EQ chain).
 *
 * Coefficients are computed via the W3C Audio EQ Cookbook
 * (https://www.w3.org/TR/audio-eq-cookbook/) — the canonical reference for
 * digital filters used by Web Audio, FabFilter, AutoEq, and effectively
 * every audio app on the planet.
 *
 * Per-sample processing uses Direct Form II Transposed for numerical
 * stability and minimal arithmetic.
 *
 * Not thread-safe. One instance per channel.
 */
class Biquad {
  private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
  private var a1 = 0f; private var a2 = 0f
  private var z1 = 0f; private var z2 = 0f

  /** Configure as a peaking EQ band. */
  fun setPeaking(freqHz: Float, gainDb: Float, q: Float, sampleRate: Int) {
    val A = 10.0.pow((gainDb / 40.0)).toFloat()
    val w0 = (2.0 * PI * freqHz / sampleRate).toFloat()
    val cw = cos(w0)
    val alpha = sin(w0) / (2f * q)

    val nb0 = 1f + alpha * A
    val nb1 = -2f * cw
    val nb2 = 1f - alpha * A
    val na0 = 1f + alpha / A
    val na1 = -2f * cw
    val na2 = 1f - alpha / A

    b0 = nb0 / na0
    b1 = nb1 / na0
    b2 = nb2 / na0
    a1 = na1 / na0
    a2 = na2 / na0
  }

  /** Configure as a low-shelf (used by [BassShelfProcessor]). */
  fun setLowShelf(freqHz: Float, gainDb: Float, q: Float, sampleRate: Int) {
    val A = 10.0.pow((gainDb / 40.0)).toFloat()
    val w0 = (2.0 * PI * freqHz / sampleRate).toFloat()
    val cw = cos(w0)
    val sw = sin(w0)
    val alpha = sw / (2f * q)
    val twoSqrtAalpha = 2f * kotlin.math.sqrt(A) * alpha

    val nb0 = A * ((A + 1f) - (A - 1f) * cw + twoSqrtAalpha)
    val nb1 = 2f * A * ((A - 1f) - (A + 1f) * cw)
    val nb2 = A * ((A + 1f) - (A - 1f) * cw - twoSqrtAalpha)
    val na0 = (A + 1f) + (A - 1f) * cw + twoSqrtAalpha
    val na1 = -2f * ((A - 1f) + (A + 1f) * cw)
    val na2 = (A + 1f) + (A - 1f) * cw - twoSqrtAalpha

    b0 = nb0 / na0
    b1 = nb1 / na0
    b2 = nb2 / na0
    a1 = na1 / na0
    a2 = na2 / na0
  }

  /** Process one sample. Direct Form II Transposed. */
  fun process(x: Float): Float {
    val y = b0 * x + z1
    z1 = b1 * x - a1 * y + z2
    z2 = b2 * x - a2 * y
    return y
  }

  /** Clear delay-line state. Call when sample rate changes or chain is reset. */
  fun reset() { z1 = 0f; z2 = 0f }
}
```

- [ ] **Step 3.4: Run test → PASS**

- [ ] **Step 3.5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/equalizer/dsp/Biquad.kt \
        core/media/src/test/kotlin/com/stash/core/media/equalizer/dsp/BiquadTest.kt
git commit -m "feat(eq): add Biquad DSP atom with peaking + low-shelf coefficients"
```

---

## Task 4 — `EqStore` (Preferences DataStore + JSON)

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/equalizer/EqStore.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/equalizer/EqStoreTest.kt`

- [ ] **Step 4.1: Write failing test**

```kotlin
// EqStoreTest.kt
package com.stash.core.media.equalizer

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class EqStoreTest {
  private lateinit var store: EqStore
  private lateinit var file: File

  @Before fun setUp() {
    val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    file = ctx.preferencesDataStoreFile("eq_state_test")
    val ds = PreferenceDataStoreFactory.create { file }
    store = EqStore(ds)
  }

  @After fun tearDown() { file.delete() }

  @Test fun `read on missing key returns default with enabled false`() = runBlocking {
    val s = store.read()
    assertThat(s.enabled).isFalse()
    assertThat(s).isEqualTo(EqState())
  }

  @Test fun `write then read round-trip`() = runBlocking {
    val original = EqState(enabled = true, presetId = "rock",
      gainsDb = floatArrayOf(4f, 2f, -1f, 2f, 3f), preampDb = -2f, bassBoostDb = 5f)
    store.write(original)
    val restored = store.read()
    assertThat(restored).isEqualTo(original)
  }

  @Test fun `corrupted JSON falls back to default`() = runBlocking {
    store.writeRaw("{ this is not valid json }")
    val s = store.read()
    assertThat(s).isEqualTo(EqState()) // default; enabled=false
  }
}
```

- [ ] **Step 4.2: Run test → FAIL**

- [ ] **Step 4.3: Implement `EqStore.kt`**

```kotlin
// EqStore.kt
package com.stash.core.media.equalizer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Persistence wrapper for [EqState].
 *
 * Stores the entire state as a single kotlinx.serialization JSON string in
 * one Preferences DataStore key. This guarantees atomic writes — a partial
 * write during process death cannot leave EQ in a half-written state.
 *
 * Missing key → default (enabled = false). Corrupted JSON → default.
 * This is the bug-fix anchor: a fresh, partial, or broken store can never
 * produce "EQ effectively on" surprise behavior.
 */
@Singleton
class EqStore @Inject constructor(
  private val dataStore: DataStore<Preferences>,
) {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  suspend fun read(): EqState {
    val raw = dataStore.data.first()[KEY] ?: return EqState()
    return try {
      json.decodeFromString(EqState.serializer(), raw)
    } catch (_: SerializationException) {
      EqState()
    } catch (_: IllegalArgumentException) {
      EqState()
    }
  }

  suspend fun write(state: EqState) {
    dataStore.edit { it[KEY] = json.encodeToString(EqState.serializer(), state) }
  }

  /** Test-only: write a raw string to simulate corruption. */
  internal suspend fun writeRaw(raw: String) {
    dataStore.edit { it[KEY] = raw }
  }

  companion object {
    private val KEY = stringPreferencesKey("eq_state_v1_json")
  }
}
```

- [ ] **Step 4.4: Run test → PASS**

- [ ] **Step 4.5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/equalizer/EqStore.kt \
        core/media/src/test/kotlin/com/stash/core/media/equalizer/EqStoreTest.kt
git commit -m "feat(eq): add EqStore (atomic JSON persistence, default-off on miss)"
```

---

## Task 5 — `EqMigration` (one-shot translator)

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/equalizer/EqMigration.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/equalizer/EqMigrationTest.kt`

- [ ] **Step 5.0: Hard prerequisite — record the actual legacy key names**

Open `core/media/src/main/kotlin/com/stash/core/media/equalizer/EqualizerStore.kt` and copy down the exact string values inside the `Keys` object (e.g. is enabled named `eq_enabled`, `enabled`, or `equalizer_enabled`?). The `LegacyEqualizerStoreImpl` in Step 5.4 must use the same key strings or the migration will silently miss the user's old settings. Skipping this step would cause the migration to look fine but discard old gains.

- [ ] **Step 5.1: Write failing test**

```kotlin
// EqMigrationTest.kt
package com.stash.core.media.equalizer

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

class EqMigrationTest {
  private val newStore = mockk<EqStore>(relaxed = true)
  private val oldStore = mockk<LegacyEqualizerStore>(relaxed = true)

  @Test fun `migration forces enabled=false even if legacy was true`() = runBlocking {
    coEvery { newStore.read() } returns EqState() // not yet migrated; defaults
    coEvery { oldStore.exists() } returns true
    coEvery { oldStore.readLegacy() } returns LegacySettings(
      enabled = true, presetName = "ROCK",
      gains = listOf(400, 200, -100, 200, 300), bassBoostStrength = 500
    )

    EqMigration(newStore, oldStore).migrateIfNeeded()

    coVerify { newStore.write(match { it.enabled == false }) }
  }

  @Test fun `migration preserves gain bands and preset id`() = runBlocking {
    coEvery { newStore.read() } returns EqState()
    coEvery { oldStore.exists() } returns true
    coEvery { oldStore.readLegacy() } returns LegacySettings(
      enabled = true, presetName = "ROCK",
      gains = listOf(400, 200, -100, 200, 300), bassBoostStrength = 500
    )

    EqMigration(newStore, oldStore).migrateIfNeeded()

    coVerify {
      newStore.write(match {
        it.presetId == "rock" &&
        it.gainsDb.contentEquals(floatArrayOf(4f, 2f, -1f, 2f, 3f)) &&
        it.bassBoostDb in 7.4f..7.6f // 500/1000 * 15 = 7.5
      })
    }
  }

  @Test fun `migration is idempotent — skip if already migrated`() = runBlocking {
    coEvery { newStore.read() } returns EqState(presetId = "vocal") // already migrated
    EqMigration(newStore, oldStore).migrateIfNeeded()
    coVerify(exactly = 0) { newStore.write(any()) }
    coVerify(exactly = 0) { oldStore.readLegacy() }
  }

  @Test fun `migration with no legacy data writes default with enabled=false`() = runBlocking {
    coEvery { newStore.read() } returns EqState()
    coEvery { oldStore.exists() } returns false
    EqMigration(newStore, oldStore).migrateIfNeeded()
    // Even with no legacy, write a marker state so we don't re-run
    coVerify { newStore.write(match { it.enabled == false }) }
  }

  @Test fun `migration deletes legacy store on success`() = runBlocking {
    coEvery { newStore.read() } returns EqState()
    coEvery { oldStore.exists() } returns true
    coEvery { oldStore.readLegacy() } returns LegacySettings(
      enabled = false, presetName = "FLAT", gains = listOf(0,0,0,0,0), bassBoostStrength = 0)
    EqMigration(newStore, oldStore).migrateIfNeeded()
    coVerify { oldStore.deleteLegacy() }
  }
}
```

- [ ] **Step 5.2: Run test → FAIL**

- [ ] **Step 5.3: Implement `EqMigration.kt` + minimal `LegacyEqualizerStore` wrapper**

The legacy store wrapper exposes only what migration needs from the old `EqualizerStore`. It's a thin adapter; the real `EqualizerStore` class is deleted in Task 14.

```kotlin
// EqMigration.kt
package com.stash.core.media.equalizer

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the legacy equalizer DataStore to the new [EqStore].
 *
 * Runs at most once per install (gated by [EqStore.read].presetId being
 * present and non-default, or by an internal "migrated" sentinel).
 *
 * Forces `enabled = false` regardless of the legacy value. The legacy
 * code's stuck-on bug means a true value can't be trusted; user re-enables
 * via the new UI when they want.
 */
@Singleton
class EqMigration @Inject constructor(
  private val newStore: EqStore,
  private val legacyStore: LegacyEqualizerStore,
) {
  suspend fun migrateIfNeeded() {
    // Heuristic: if the new store already has an explicit non-default
    // preset id OR enabled was ever flipped, we've migrated. We add a
    // sentinel preset id "_migrated" only when no legacy data exists.
    val current = newStore.read()
    if (current.presetId != "flat" || current != EqState()) return // already migrated

    val migrated = if (legacyStore.exists()) {
      val legacy = legacyStore.readLegacy()
      EqState(
        enabled = false,                               // force-off
        presetId = mapLegacyPresetName(legacy.presetName),
        gainsDb = adaptGains(legacy.gains),
        bassBoostDb = legacy.bassBoostStrength.coerceIn(0, 1000) / 1000f * 15f,
      )
    } else {
      EqState(enabled = false) // default state
    }

    newStore.write(migrated)
    if (legacyStore.exists()) legacyStore.deleteLegacy()
  }

  private fun mapLegacyPresetName(name: String): String = when (name.uppercase()) {
    "FLAT"          -> "flat"
    "BASS_BOOST"    -> "bass"
    "TREBLE_BOOST"  -> "treble"
    "VOCAL"         -> "vocal"
    "ROCK"          -> "rock"
    "POP"           -> "pop"
    "JAZZ"          -> "jazz"
    "CLASSICAL"     -> "classical"
    else            -> "flat"
  }

  /** Resample legacy gain array (could be 5..10 entries in millibels) to 5 entries in dB. */
  private fun adaptGains(legacy: List<Int>): FloatArray {
    val mb = if (legacy.size == 5) legacy
            else List(5) { i ->
              val ratio = i.toFloat() * (legacy.size - 1) / 4f
              val lo = legacy[ratio.toInt().coerceIn(0, legacy.lastIndex)]
              val hi = legacy[(ratio.toInt() + 1).coerceIn(0, legacy.lastIndex)]
              val frac = ratio - ratio.toInt()
              (lo + (hi - lo) * frac).toInt()
            }
    return mb.map { (it / 100f).coerceIn(-12f, 12f) }.toFloatArray()
  }
}

/** Legacy adapter — read-only view of the old equalizer_prefs DataStore. */
interface LegacyEqualizerStore {
  suspend fun exists(): Boolean
  suspend fun readLegacy(): LegacySettings
  suspend fun deleteLegacy()
}

data class LegacySettings(
  val enabled: Boolean,
  val presetName: String,
  val gains: List<Int>,        // millibels
  val bassBoostStrength: Int,  // 0..1000
)
```

- [ ] **Step 5.4: Implement legacy adapter against the existing `EqualizerStore`**

`LegacyEqualizerStore` is implemented by reading directly from the old `equalizer_prefs` DataStore at the file level (so we don't depend on the soon-to-be-deleted `EqualizerStore` class). Concrete impl uses Preferences keys named in the legacy file.

```kotlin
// LegacyEqualizerStoreImpl.kt — colocated in EqMigration.kt or its own file
package com.stash.core.media.equalizer

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val Context.legacyEqDataStore by preferencesDataStore("equalizer_prefs")

@Singleton
class LegacyEqualizerStoreImpl @Inject constructor(
  @ApplicationContext private val context: Context,
) : LegacyEqualizerStore {

  private val K_ENABLED = booleanPreferencesKey("eq_enabled")
  private val K_PRESET  = stringPreferencesKey("eq_preset")
  private val K_BASS    = intPreferencesKey("bass_boost_strength")
  // Per-band gains: "eq_gain_0" .. "eq_gain_9"
  private fun gainKey(i: Int) = intPreferencesKey("eq_gain_$i")

  override suspend fun exists(): Boolean {
    val prefs = context.legacyEqDataStore.data.first()
    return prefs[K_ENABLED] != null || prefs[K_PRESET] != null
  }

  override suspend fun readLegacy(): LegacySettings {
    val prefs = context.legacyEqDataStore.data.first()
    val gains = (0 until 10).mapNotNull { prefs[gainKey(it)] }
    return LegacySettings(
      enabled = prefs[K_ENABLED] ?: false,
      presetName = prefs[K_PRESET] ?: "FLAT",
      gains = gains.ifEmpty { listOf(0, 0, 0, 0, 0) },
      bassBoostStrength = prefs[K_BASS] ?: 0,
    )
  }

  override suspend fun deleteLegacy() {
    context.legacyEqDataStore.edit { it.clear() }
  }
}
```

> **Note:** the actual key names used by the legacy `EqualizerStore` may differ. Before merging, open `core/media/.../equalizer/EqualizerStore.kt` and confirm the `Keys` object's string names — adjust the `K_ENABLED`, `K_PRESET`, `K_BASS`, and `gainKey()` constants to match.

- [ ] **Step 5.5: Run test → PASS**

- [ ] **Step 5.6: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/equalizer/EqMigration.kt \
        core/media/src/main/kotlin/com/stash/core/media/equalizer/LegacyEqualizerStoreImpl.kt \
        core/media/src/test/kotlin/com/stash/core/media/equalizer/EqMigrationTest.kt
git commit -m "feat(eq): migrate legacy equalizer_prefs into new EqStore (force off)"
```

---

## Task 6 — `EqController` (state owner singleton)

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/equalizer/EqController.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/equalizer/EqControllerTest.kt`

- [ ] **Step 6.1: Write failing test**

```kotlin
// EqControllerTest.kt
package com.stash.core.media.equalizer

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EqControllerTest {
  private val store = mockk<EqStore>(relaxed = true)
  private val migration = mockk<EqMigration>(relaxed = true)

  @Test fun `init reads persisted state synchronously before exposing it`() = runBlocking {
    coEvery { store.read() } returns EqState(enabled = true, presetId = "rock")
    val ctrl = EqController(store, migration)
    ctrl.awaitInit()
    assertThat(ctrl.state.value.enabled).isTrue()
    assertThat(ctrl.state.value.presetId).isEqualTo("rock")
  }

  @Test fun `setEnabled flips the flag and triggers persist`() = runTest {
    coEvery { store.read() } returns EqState()
    val ctrl = EqController(store, migration, scope = backgroundScope)
    ctrl.awaitInit()
    ctrl.setEnabled(true)
    assertThat(ctrl.state.value.enabled).isTrue()
    advanceTimeBy(300) // past 200ms debounce
    coVerify { store.write(match { it.enabled == true }) }
  }

  @Test fun `setBandGain clamps to spec range`() = runTest {
    coEvery { store.read() } returns EqState()
    val ctrl = EqController(store, migration, scope = backgroundScope)
    ctrl.awaitInit()
    ctrl.setBandGain(0, 100f) // out of range
    assertThat(ctrl.state.value.gainsDb[0]).isEqualTo(12f)
    ctrl.setBandGain(0, -100f)
    assertThat(ctrl.state.value.gainsDb[0]).isEqualTo(-12f)
  }

  @Test fun `setPreset updates gains from catalog`() = runTest {
    coEvery { store.read() } returns EqState()
    val ctrl = EqController(store, migration, scope = backgroundScope)
    ctrl.awaitInit()
    ctrl.setPreset("rock")
    val expected = PresetCatalog.byId("rock")!!.gainsDb
    assertThat(ctrl.state.value.gainsDb.contentEquals(expected)).isTrue()
    assertThat(ctrl.state.value.presetId).isEqualTo("rock")
  }

  @Test fun `flush forces immediate persist regardless of debounce`() = runTest {
    coEvery { store.read() } returns EqState()
    val ctrl = EqController(store, migration, scope = backgroundScope)
    ctrl.awaitInit()
    ctrl.setEnabled(true)
    ctrl.flush() // simulate app onPause
    coVerify { store.write(any()) }
  }
}
```

- [ ] **Step 6.2: Run test → FAIL**

- [ ] **Step 6.3: Implement `EqController.kt`**

```kotlin
// EqController.kt
package com.stash.core.media.equalizer

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The single writer of [EqState]. UI emits events here; AudioProcessors
 * read [state] on every buffer.
 *
 * Construction performs a synchronous (`runBlocking`) read from disk so
 * the controller is fully restored before any AudioProcessor is built —
 * the Hilt graph guarantees ordering by declaring controller as a
 * constructor dependency of the processors. This is what kills the
 * legacy init race.
 *
 * Persistence is debounced 200 ms so slider drags don't flood DataStore;
 * [flush] is called from app `onPause` to force an immediate write.
 */
@Singleton
class EqController @Inject constructor(
  private val store: EqStore,
  private val migration: EqMigration,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
  private val _state = MutableStateFlow(EqState())
  val state: StateFlow<EqState> = _state.asStateFlow()

  private var pendingWrite: Job? = null
  @Volatile private var initDone = false

  init {
    runBlocking {
      migration.migrateIfNeeded()
      _state.value = store.read()
      initDone = true
    }
  }

  /** Test helper. */
  internal suspend fun awaitInit() {
    while (!initDone) delay(1)
  }

  // -- Public mutators ------------------------------------------------------

  fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

  fun setBandGain(bandIndex: Int, dB: Float) = update { s ->
    val clamped = dB.coerceIn(-12f, 12f)
    val newGains = s.gainsDb.copyOf().also { it[bandIndex] = clamped }
    s.copy(gainsDb = newGains, presetId = "custom")
  }

  fun setPreampDb(dB: Float) = update { it.copy(preampDb = dB.coerceIn(-12f, 12f)) }

  fun setBassBoostDb(dB: Float) = update { it.copy(bassBoostDb = dB.coerceIn(0f, 15f)) }

  fun setPreset(id: String) = update { s ->
    val preset = PresetCatalog.byId(id) ?: s.customPresets.firstOrNull { it.id == id }
    if (preset == null) s
    else s.copy(presetId = id, gainsDb = preset.gainsDb.copyOf(), preampDb = preset.preampDb)
  }

  fun saveCurrentAsPreset(name: String) = update { s ->
    val newPreset = NamedPreset(
      id = "u_" + System.currentTimeMillis(),
      name = name,
      gainsDb = s.gainsDb.copyOf(),
      preampDb = s.preampDb,
    )
    s.copy(customPresets = s.customPresets + newPreset, presetId = newPreset.id)
  }

  fun deleteCustomPreset(id: String) = update { s ->
    s.copy(customPresets = s.customPresets.filterNot { it.id == id })
  }

  /** Force an immediate persist — call from app pause/stop. */
  suspend fun flush() {
    pendingWrite?.cancel()
    store.write(_state.value)
  }

  // -- Internals ------------------------------------------------------------

  private fun update(transform: (EqState) -> EqState) {
    _state.value = transform(_state.value)
    pendingWrite?.cancel()
    pendingWrite = scope.launch {
      delay(DEBOUNCE_MS)
      store.write(_state.value)
    }
  }

  companion object {
    private const val DEBOUNCE_MS = 200L
  }
}
```

- [ ] **Step 6.4: Run test → PASS**

- [ ] **Step 6.5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/equalizer/EqController.kt \
        core/media/src/test/kotlin/com/stash/core/media/equalizer/EqControllerTest.kt
git commit -m "feat(eq): add EqController (single writer, debounced persist, sync init)"
```

---

## Task 7 — `PreampProcessor`

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/equalizer/dsp/PreampProcessor.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/equalizer/dsp/PreampProcessorTest.kt`

- [ ] **Step 7.1: Write failing test**

```kotlin
// PreampProcessorTest.kt
package com.stash.core.media.equalizer.dsp

import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.EqState

class PreampProcessorTest {
  private fun controllerWithState(state: EqState): EqController {
    val ctrl = mockk<EqController>()
    every { ctrl.state } returns MutableStateFlow(state)
    return ctrl
  }

  private fun pcmBuffer(samples: FloatArray): ByteBuffer {
    val bb = ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.nativeOrder())
    samples.forEach { bb.putFloat(it) }
    bb.flip()
    return bb
  }

  @Test fun `disabled state passes input through unchanged`() {
    val p = PreampProcessor(controllerWithState(EqState(enabled = false, preampDb = 6f)))
    p.configure(AudioFormat(48_000, 2, 4 /* PCM_FLOAT */))
    p.flush()
    val input = pcmBuffer(floatArrayOf(0.5f, -0.3f))
    p.queueInput(input)
    val output = p.getOutput()
    val outF = FloatArray(2).also { for (i in it.indices) it[i] = output.float }
    assertThat(outF).usingTolerance(1e-6f).containsExactly(0.5f, -0.3f).inOrder()
  }

  @Test fun `enabled with +6 dB doubles amplitude`() {
    val p = PreampProcessor(controllerWithState(EqState(enabled = true, preampDb = 6f)))
    p.configure(AudioFormat(48_000, 2, 4))
    p.flush()
    val input = pcmBuffer(floatArrayOf(0.5f, -0.3f))
    p.queueInput(input)
    val output = p.getOutput()
    val outF = FloatArray(2).also { for (i in it.indices) it[i] = output.float }
    // 10^(6/20) ≈ 1.995
    assertThat(outF[0]).isWithin(0.01f).of(0.5f * 1.995f)
    assertThat(outF[1]).isWithin(0.01f).of(-0.3f * 1.995f)
  }
}
```

- [ ] **Step 7.2: Run test → FAIL**

- [ ] **Step 7.3: Implement `PreampProcessor.kt`**

```kotlin
// PreampProcessor.kt
package com.stash.core.media.equalizer.dsp

import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.C
import com.stash.core.media.equalizer.EqController
import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * Master gain stage at the head of the EQ chain.
 *
 * On bypass (`!state.enabled || preampDb == 0`) returns the input buffer
 * unchanged — bit-perfect passthrough.
 *
 * Allocates only at [onConfigure]; the per-buffer hot path is allocation-free.
 */
class PreampProcessor(
  private val controller: EqController,
) : BaseAudioProcessor() {

  override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT)
      throw UnhandledAudioFormatException(inputAudioFormat)
    return inputAudioFormat
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val state = controller.state.value
    if (!state.enabled || state.preampDb == 0f) {
      // Bypass — emit the input buffer directly. BaseAudioProcessor's
      // contract: when the output IS the input, set the output buffer
      // pointer; ExoPlayer reads input position from input buffer.
      val out = replaceOutputBuffer(inputBuffer.remaining())
      while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
      out.flip()
      return
    }
    val gain = 10f.pow(state.preampDb / 20f)
    val out = replaceOutputBuffer(inputBuffer.remaining())
    while (inputBuffer.hasRemaining()) {
      val b0 = inputBuffer.get(); val b1 = inputBuffer.get()
      val b2 = inputBuffer.get(); val b3 = inputBuffer.get()
      val intBits = (b0.toInt() and 0xFF) or
                    ((b1.toInt() and 0xFF) shl 8) or
                    ((b2.toInt() and 0xFF) shl 16) or
                    ((b3.toInt() and 0xFF) shl 24)
      val sample = Float.fromBits(intBits) * gain
      out.putFloat(sample)
    }
    out.flip()
  }
}
```

> **Note:** Media3 `BaseAudioProcessor.replaceOutputBuffer(int)` returns a `ByteBuffer` to fill; the framework manages the underlying allocation across `flush()` / `onConfigure()` calls so per-buffer allocations don't churn GC. The byte-by-byte float reconstitution above honours little-endian ByteBuffer ordering used by Media3 PCM_FLOAT.

- [ ] **Step 7.4: Run test → PASS**

- [ ] **Step 7.5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/equalizer/dsp/PreampProcessor.kt \
        core/media/src/test/kotlin/com/stash/core/media/equalizer/dsp/PreampProcessorTest.kt
git commit -m "feat(eq): add PreampProcessor (master gain with bit-perfect bypass)"
```

---

## Task 8 — `EqProcessor` (5-band biquad cascade)

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/equalizer/dsp/EqProcessor.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/equalizer/dsp/EqProcessorTest.kt`

- [ ] **Step 8.1: Write failing test**

```kotlin
// EqProcessorTest.kt
package com.stash.core.media.equalizer.dsp

import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.EqState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class EqProcessorTest {

  private fun ctrl(state: EqState) = mockk<EqController>().also {
    every { it.state } returns MutableStateFlow(state)
  }

  private fun bufFromSamples(samples: FloatArray): ByteBuffer =
    ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.nativeOrder()).also { bb ->
      samples.forEach { bb.putFloat(it) }
      bb.flip()
    }

  @Test fun `bypasses when enabled is false`() {
    val p = EqProcessor(ctrl(EqState(enabled = false, gainsDb = floatArrayOf(6f, 0f, 0f, 0f, 0f))))
    p.configure(AudioFormat(48_000, 2, 4))
    p.flush()
    val samples = floatArrayOf(0.5f, -0.5f, 0.5f, -0.5f)
    val out = readAll(p, bufFromSamples(samples))
    assertThat(out).usingTolerance(1e-6f).containsExactlyElementsIn(samples.toTypedArray()).inOrder()
  }

  @Test fun `bypasses when all gains are zero`() {
    val p = EqProcessor(ctrl(EqState(enabled = true, gainsDb = floatArrayOf(0f,0f,0f,0f,0f))))
    p.configure(AudioFormat(48_000, 2, 4))
    p.flush()
    val samples = floatArrayOf(0.5f, -0.5f)
    val out = readAll(p, bufFromSamples(samples))
    assertThat(out).usingTolerance(1e-6f).containsExactlyElementsIn(samples.toTypedArray()).inOrder()
  }

  @Test fun `boosting band 1 amplifies a sine at 60 Hz mono`() {
    val p = EqProcessor(ctrl(EqState(enabled = true, gainsDb = floatArrayOf(+12f, 0f, 0f, 0f, 0f))))
    p.configure(AudioFormat(48_000, 1, 4))
    p.flush()
    val sr = 48_000; val freq = 60.0
    val samples = FloatArray(8192) { i -> sin(2.0 * PI * freq * i / sr).toFloat() }
    val out = readAll(p, bufFromSamples(samples))
    // Skip filter settling
    var maxOut = 0f
    for (i in 1024 until out.size) maxOut = maxOf(maxOut, abs(out[i]))
    // +12 dB ≈ 3.98x
    assertThat(maxOut).isWithin(0.2f).of(3.98f)
  }

  private fun readAll(p: EqProcessor, input: ByteBuffer): FloatArray {
    p.queueInput(input)
    val out = p.getOutput()
    val list = mutableListOf<Float>()
    while (out.hasRemaining()) list.add(out.float)
    return list.toFloatArray()
  }
}
```

- [ ] **Step 8.2: Run test → FAIL**

- [ ] **Step 8.3: Implement `EqProcessor.kt`**

```kotlin
// EqProcessor.kt
package com.stash.core.media.equalizer.dsp

import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.C
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.EqState
import java.nio.ByteBuffer

/**
 * 5-band peaking EQ. One [Biquad] per band per channel; coefficients
 * recompute when [EqState.gainsDb] or sample rate changes (NEVER per-sample).
 *
 * Bypass when EQ is disabled or all gains are 0 — returns input unchanged.
 */
class EqProcessor(
  private val controller: EqController,
) : BaseAudioProcessor() {

  private var sampleRate = 0
  private var channels = 0

  // [channel][band] biquad cascade. Lazily resized in onConfigure.
  private lateinit var filters: Array<Array<Biquad>>
  private var lastAppliedGains: FloatArray = floatArrayOf()

  override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT)
      throw UnhandledAudioFormatException(inputAudioFormat)
    sampleRate = inputAudioFormat.sampleRate
    channels = inputAudioFormat.channelCount
    filters = Array(channels) { Array(BAND_FREQS.size) { Biquad() } }
    rebuildCoefficients(controller.state.value.gainsDb)
    return inputAudioFormat
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val state = controller.state.value
    if (!state.enabled || isFlat(state.gainsDb)) {
      passthrough(inputBuffer); return
    }
    if (!state.gainsDb.contentEquals(lastAppliedGains)) {
      rebuildCoefficients(state.gainsDb)
    }

    val out = replaceOutputBuffer(inputBuffer.remaining())
    while (inputBuffer.hasRemaining()) {
      for (ch in 0 until channels) {
        var sample = inputBuffer.float
        val chFilters = filters[ch]
        for (band in chFilters.indices) sample = chFilters[band].process(sample)
        out.putFloat(sample)
      }
    }
    out.flip()
  }

  override fun onFlush() {
    if (::filters.isInitialized) filters.forEach { row -> row.forEach { it.reset() } }
  }

  private fun rebuildCoefficients(gains: FloatArray) {
    for (ch in 0 until channels) {
      for (b in BAND_FREQS.indices) {
        filters[ch][b].setPeaking(BAND_FREQS[b], gains[b], BAND_Q, sampleRate)
      }
    }
    lastAppliedGains = gains.copyOf()
  }

  private fun passthrough(inputBuffer: ByteBuffer) {
    val out = replaceOutputBuffer(inputBuffer.remaining())
    while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
    out.flip()
  }

  private fun isFlat(gains: FloatArray): Boolean = gains.all { it == 0f }

  companion object {
    val BAND_FREQS: FloatArray = floatArrayOf(60f, 230f, 910f, 3_600f, 14_000f)
    const val BAND_Q = 1f
  }
}
```

- [ ] **Step 8.4: Run test → PASS**

- [ ] **Step 8.5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/equalizer/dsp/EqProcessor.kt \
        core/media/src/test/kotlin/com/stash/core/media/equalizer/dsp/EqProcessorTest.kt
git commit -m "feat(eq): add EqProcessor (5-band biquad cascade with bypass)"
```

---

## Task 9 — `BassShelfProcessor`

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/equalizer/dsp/BassShelfProcessor.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/equalizer/dsp/BassShelfProcessorTest.kt`

- [ ] **Step 9.1: Write failing test**

```kotlin
// BassShelfProcessorTest.kt
package com.stash.core.media.equalizer.dsp

import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.EqState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class BassShelfProcessorTest {
  private fun ctrl(state: EqState) = mockk<EqController>().also {
    every { it.state } returns MutableStateFlow(state)
  }

  @Test fun `bypasses when bassBoostDb is 0`() {
    val p = BassShelfProcessor(ctrl(EqState(enabled = true, bassBoostDb = 0f)))
    p.configure(AudioFormat(48_000, 1, 4))
    p.flush()
    val s = floatArrayOf(0.3f, -0.3f, 0.3f, -0.3f)
    val out = collect(p, bb(s))
    assertThat(out).usingTolerance(1e-6f).containsExactlyElementsIn(s.toTypedArray()).inOrder()
  }

  @Test fun `boosts a 60 Hz sine when bassBoostDb is set`() {
    val p = BassShelfProcessor(ctrl(EqState(enabled = true, bassBoostDb = 12f)))
    p.configure(AudioFormat(48_000, 1, 4))
    p.flush()
    val sr = 48_000
    val sine = FloatArray(8192) { i -> sin(2.0 * PI * 60.0 * i / sr).toFloat() }
    val out = collect(p, bb(sine))
    var peak = 0f
    for (i in 1024 until out.size) peak = maxOf(peak, abs(out[i]))
    // +12 dB shelf at 100 Hz, sine at 60 Hz → near full +12 dB
    assertThat(peak).isGreaterThan(2.5f) // > +8 dB
  }

  private fun bb(samples: FloatArray): ByteBuffer = ByteBuffer.allocateDirect(samples.size * 4)
    .order(ByteOrder.nativeOrder()).also { bb -> samples.forEach { bb.putFloat(it) }; bb.flip() }

  private fun collect(p: BassShelfProcessor, input: ByteBuffer): FloatArray {
    p.queueInput(input)
    val o = p.getOutput()
    val list = mutableListOf<Float>()
    while (o.hasRemaining()) list.add(o.float)
    return list.toFloatArray()
  }
}
```

- [ ] **Step 9.2: Run test → FAIL**

- [ ] **Step 9.3: Implement `BassShelfProcessor.kt`**

```kotlin
// BassShelfProcessor.kt
package com.stash.core.media.equalizer.dsp

import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.C
import com.stash.core.media.equalizer.EqController
import java.nio.ByteBuffer

/**
 * Dedicated low-shelf at 100 Hz, separate from EQ band 1 so bass-boost
 * can be controlled independently without redirecting EQ math.
 *
 * Bypass when bassBoostDb is 0 — returns input unchanged.
 */
class BassShelfProcessor(
  private val controller: EqController,
) : BaseAudioProcessor() {

  private var sampleRate = 0
  private var channels = 0
  private lateinit var filters: Array<Biquad>
  private var lastAppliedGain = -999f

  override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT)
      throw UnhandledAudioFormatException(inputAudioFormat)
    sampleRate = inputAudioFormat.sampleRate
    channels = inputAudioFormat.channelCount
    filters = Array(channels) { Biquad() }
    rebuildIfNeeded(controller.state.value.bassBoostDb, force = true)
    return inputAudioFormat
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val state = controller.state.value
    val gain = state.bassBoostDb
    if (!state.enabled || gain == 0f) { passthrough(inputBuffer); return }
    rebuildIfNeeded(gain)
    val out = replaceOutputBuffer(inputBuffer.remaining())
    while (inputBuffer.hasRemaining()) {
      for (ch in 0 until channels) {
        out.putFloat(filters[ch].process(inputBuffer.float))
      }
    }
    out.flip()
  }

  override fun onFlush() {
    if (::filters.isInitialized) filters.forEach { it.reset() }
  }

  private fun rebuildIfNeeded(gain: Float, force: Boolean = false) {
    if (!force && gain == lastAppliedGain) return
    for (ch in 0 until channels) filters[ch].setLowShelf(SHELF_FREQ, gain, SHELF_Q, sampleRate)
    lastAppliedGain = gain
  }

  private fun passthrough(inputBuffer: ByteBuffer) {
    val out = replaceOutputBuffer(inputBuffer.remaining())
    while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
    out.flip()
  }

  companion object {
    const val SHELF_FREQ = 100f
    const val SHELF_Q = 0.7f
  }
}
```

- [ ] **Step 9.4: Run test → PASS**

- [ ] **Step 9.5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/equalizer/dsp/BassShelfProcessor.kt \
        core/media/src/test/kotlin/com/stash/core/media/equalizer/dsp/BassShelfProcessorTest.kt
git commit -m "feat(eq): add BassShelfProcessor (low-shelf at 100 Hz, bypass-when-zero)"
```

---

## Task 10 — `StashRenderersFactory` + ExoPlayer wiring

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/equalizer/StashRenderersFactory.kt`
- Modify: `core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt:75`
- Modify: `core/media/src/main/kotlin/com/stash/core/media/preview/PreviewPlayer.kt:195`

- [ ] **Step 10.1: Implement `StashRenderersFactory.kt`**

```kotlin
// StashRenderersFactory.kt
package com.stash.core.media.equalizer

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.stash.core.media.equalizer.dsp.BassShelfProcessor
import com.stash.core.media.equalizer.dsp.EqProcessor
import com.stash.core.media.equalizer.dsp.PreampProcessor

/**
 * Custom RenderersFactory that builds an audio sink with our EQ chain.
 *
 * The chain is built ONCE per ExoPlayer instance. Toggling EQ enabled is a
 * flag flip read by each processor on every buffer — never a topology
 * change. This is what makes "stacking on re-enable" structurally
 * impossible.
 */
class StashRenderersFactory(
  context: Context,
  private val eqController: EqController,
) : DefaultRenderersFactory(context) {

  override fun buildAudioSink(
    context: Context,
    enableFloatOutput: Boolean,
    enableAudioTrackPlaybackParams: Boolean,
  ): AudioSink {
    val processors: Array<AudioProcessor> = arrayOf(
      PreampProcessor(eqController),
      EqProcessor(eqController),
      BassShelfProcessor(eqController),
    )
    return DefaultAudioSink.Builder(context)
      .setEnableFloatOutput(true) // EQ math is float; force float output
      .setAudioProcessors(processors)
      .build()
  }
}
```

- [ ] **Step 10.2: Modify `StashPlaybackService.kt` to use the factory**

Replace the existing `ExoPlayer.Builder(this)...build()` chain (around line 75) so it injects the controller and uses the new factory:

```kotlin
// StashPlaybackService.kt — at the top, ensure these injects
@Inject lateinit var eqController: EqController

// In onCreate, replace the player construction
val player = ExoPlayer.Builder(this)
  .setRenderersFactory(StashRenderersFactory(this, eqController))
  // ...existing builder calls (LoadControl, AudioAttributes, etc.) preserved...
  .build()
```

- [ ] **Step 10.3: Modify `PreviewPlayer.kt:195` similarly**

```kotlin
// PreviewPlayer.kt — apply the same factory swap to the preview ExoPlayer.Builder
return exoPlayer ?: ExoPlayer.Builder(context)
  .setRenderersFactory(StashRenderersFactory(context, eqController))
  // ...existing config...
  .build().also { exoPlayer = it }
```

- [ ] **Step 10.4: Verify build compiles**

```bash
./gradlew :core:media:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10.5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/equalizer/StashRenderersFactory.kt \
        core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt \
        core/media/src/main/kotlin/com/stash/core/media/preview/PreviewPlayer.kt
git commit -m "feat(eq): wire EQ AudioProcessor chain into ExoPlayer renderers factory"
```

---

## Task 11 — Hilt module updates

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/di/MediaModule.kt` (or wherever the existing `EqualizerManager` was provided)

- [ ] **Step 11.1: Locate the existing Hilt module**

Search for the binding of `EqualizerManager`:
```bash
grep -rn "EqualizerManager" core/media/src/main/kotlin/com/stash/core/media/di/
```

- [ ] **Step 11.2: Replace `EqualizerManager` provision with the new bindings**

```kotlin
// MediaModule.kt — inside @Module
@Provides @Singleton
fun provideEqStore(
  @ApplicationContext context: Context,
): EqStore = EqStore(context.eqDataStore) // see DataStore extension below

@Provides @Singleton
fun provideLegacyEqualizerStore(
  impl: LegacyEqualizerStoreImpl
): LegacyEqualizerStore = impl

// EqMigration and EqController are @Singleton + @Inject constructor — no @Provides needed
// Remove any old @Provides for EqualizerManager.
```

Top-level extension for the new DataStore — place this OUTSIDE the `@Module` class, at file scope:

```kotlin
// MediaModule.kt — file-scope, OUTSIDE the @Module object/class
private val Context.eqDataStore by preferencesDataStore(name = "eq_state_v1")
```

- [ ] **Step 11.3: Verify Hilt graph builds**

```bash
./gradlew :app:hiltAggregateDepsDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 11.4: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/di/MediaModule.kt
git commit -m "feat(eq): wire Hilt module for EqController, EqStore, migration"
```

---

## Task 12 — Rewrite `EqualizerViewModel`

**Files:**
- Modify (full rewrite): `feature/settings/src/main/kotlin/com/stash/feature/settings/equalizer/EqualizerViewModel.kt`
- Test: `feature/settings/src/test/kotlin/com/stash/feature/settings/equalizer/EqualizerViewModelTest.kt`

- [ ] **Step 12.1: Write failing test**

```kotlin
// EqualizerViewModelTest.kt
package com.stash.feature.settings.equalizer

import com.google.common.truth.Truth.assertThat
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.EqState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

class EqualizerViewModelTest {
  private val state = MutableStateFlow(EqState())
  private val ctrl = mockk<EqController>(relaxed = true).also {
    every { it.state } returns state
  }

  @Test fun `state flow forwards to UI state`() {
    val vm = EqualizerViewModel(ctrl)
    state.value = EqState(enabled = true, presetId = "rock")
    assertThat(vm.uiState.value.enabled).isTrue()
    assertThat(vm.uiState.value.activePresetId).isEqualTo("rock")
  }

  @Test fun `onToggle calls controller setEnabled`() {
    val vm = EqualizerViewModel(ctrl)
    vm.onToggle(true)
    verify { ctrl.setEnabled(true) }
  }

  @Test fun `onBandChanged calls controller setBandGain`() {
    val vm = EqualizerViewModel(ctrl)
    vm.onBandChanged(2, 4.5f)
    verify { ctrl.setBandGain(2, 4.5f) }
  }
}
```

- [ ] **Step 12.2: Run test → FAIL**

- [ ] **Step 12.3: Implement `EqualizerViewModel.kt`**

```kotlin
// EqualizerViewModel.kt
package com.stash.feature.settings.equalizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.NamedPreset
import com.stash.core.media.equalizer.PresetCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class EqUiState(
  val enabled: Boolean = false,
  val gainsDb: FloatArray = FloatArray(5),
  val preampDb: Float = 0f,
  val bassBoostDb: Float = 0f,
  val activePresetId: String = "flat",
  val allPresets: List<NamedPreset> = PresetCatalog.builtIn,
) {
  // FloatArray uses reference equality by default, so override with contentEquals.
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is EqUiState) return false
    return enabled == other.enabled &&
      gainsDb.contentEquals(other.gainsDb) &&
      preampDb == other.preampDb &&
      bassBoostDb == other.bassBoostDb &&
      activePresetId == other.activePresetId &&
      allPresets == other.allPresets
  }
  override fun hashCode(): Int {
    var r = enabled.hashCode()
    r = 31 * r + gainsDb.contentHashCode()
    r = 31 * r + preampDb.hashCode()
    r = 31 * r + bassBoostDb.hashCode()
    r = 31 * r + activePresetId.hashCode()
    r = 31 * r + allPresets.hashCode()
    return r
  }
}

@HiltViewModel
class EqualizerViewModel @Inject constructor(
  private val controller: EqController,
) : ViewModel() {
  val uiState: StateFlow<EqUiState> = controller.state.map { s ->
    EqUiState(
      enabled = s.enabled,
      gainsDb = s.gainsDb,
      preampDb = s.preampDb,
      bassBoostDb = s.bassBoostDb,
      activePresetId = s.presetId,
      allPresets = PresetCatalog.allFor(s),
    )
  }.stateIn(viewModelScope, SharingStarted.Eagerly, EqUiState())

  fun onToggle(enabled: Boolean) = controller.setEnabled(enabled)
  fun onBandChanged(band: Int, dB: Float) = controller.setBandGain(band, dB)
  fun onPreampChanged(dB: Float) = controller.setPreampDb(dB)
  fun onBassBoostChanged(dB: Float) = controller.setBassBoostDb(dB)
  fun onPresetSelected(id: String) = controller.setPreset(id)
  fun onSaveCurrentPreset(name: String) = controller.saveCurrentAsPreset(name)
  fun onDeletePreset(id: String) = controller.deleteCustomPreset(id)
}
```

- [ ] **Step 12.4: Run test → PASS**

- [ ] **Step 12.5: Commit**

```bash
git add feature/settings/src/main/kotlin/com/stash/feature/settings/equalizer/EqualizerViewModel.kt \
        feature/settings/src/test/kotlin/com/stash/feature/settings/equalizer/EqualizerViewModelTest.kt
git commit -m "feat(eq): rewrite EqualizerViewModel to delegate to EqController"
```

---

## Task 13 — Rewrite `EqualizerScreen` (Compose UI)

**Files:**
- Modify (full rewrite): `feature/settings/src/main/kotlin/com/stash/feature/settings/equalizer/EqualizerScreen.kt`

- [ ] **Step 13.1: Implement the new screen**

The layout follows the design spec §UI mockup: header with master toggle, glass card containing the live curve preview + 5 vertical sliders + preset chips, then a Bass Boost card, then a Pre-amp card. **No Volume Levelling card** (Plan 2 scope).

Use Stash's existing `GlassCard` and `StashTheme.extendedColors` to match the design system. Vertical sliders are `Slider` rotated 270°, or use a custom `BandSlider` composable.

```kotlin
// EqualizerScreen.kt — high-level structure (full code in plan)
@Composable
fun EqualizerScreen(
  onNavigateBack: () -> Unit,
  viewModel: EqualizerViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
    // Header: back arrow + title + master toggle
    EqHeader(
      enabled = state.enabled,
      onToggle = viewModel::onToggle,
      onBack = onNavigateBack,
    )
    Spacer(Modifier.height(12.dp))

    // 5-band EQ glass card
    GlassCard {
      Column(Modifier.padding(16.dp)) {
        SectionLabel("5-Band EQ")
        EqCurvePreview(gainsDb = state.gainsDb, bassBoostDb = state.bassBoostDb)
        Spacer(Modifier.height(12.dp))
        BandSliderRow(
          gainsDb = state.gainsDb,
          enabled = state.enabled,
          onBandChanged = viewModel::onBandChanged,
        )
        Spacer(Modifier.height(14.dp))
        PresetChipRow(
          allPresets = state.allPresets,
          activeId = state.activePresetId,
          onPresetSelected = viewModel::onPresetSelected,
          onSavePresetClick = { /* show CreatePresetDialog */ },
        )
      }
    }

    Spacer(Modifier.height(12.dp))
    GlassCard {
      Column(Modifier.padding(16.dp)) {
        SectionLabel("Bass Boost")
        Slider(
          value = state.bassBoostDb,
          onValueChange = viewModel::onBassBoostChanged,
          valueRange = 0f..15f,
          enabled = state.enabled,
        )
      }
    }

    Spacer(Modifier.height(12.dp))
    GlassCard {
      Column(Modifier.padding(16.dp)) {
        SectionLabel("Pre-amp")
        Slider(
          value = state.preampDb,
          onValueChange = viewModel::onPreampChanged,
          valueRange = -12f..12f,
          enabled = state.enabled,
        )
      }
    }
  }
}

@Composable private fun EqHeader(enabled: Boolean, onToggle: (Boolean) -> Unit, onBack: () -> Unit) { /* ... */ }
@Composable private fun EqCurvePreview(gainsDb: FloatArray, bassBoostDb: Float) { /* draws the live curve */ }
@Composable private fun BandSliderRow(gainsDb: FloatArray, enabled: Boolean, onBandChanged: (Int, Float) -> Unit) { /* 5 vertical sliders */ }
@Composable private fun PresetChipRow(allPresets: List<NamedPreset>, activeId: String, onPresetSelected: (String) -> Unit, onSavePresetClick: () -> Unit) { /* horizontal scroll of FilterChip + "+" */ }
```

> **Note:** the curve preview composable computes the response by sampling `Biquad.process()` at log-spaced frequencies and drawing a `Path` — same technique Spotify uses. Keep it lightweight (~64 sample points).

- [ ] **Step 13.2: Run UI smoke test**

Build the app and navigate to the EQ screen:
```bash
./gradlew :app:installDebug && adb shell am start -n com.stash.app/com.stash.app.MainActivity
```
Manually navigate Settings → Audio → Equalizer. Verify the screen renders with no crashes.

- [ ] **Step 13.3: Commit**

```bash
git add feature/settings/src/main/kotlin/com/stash/feature/settings/equalizer/EqualizerScreen.kt
git commit -m "feat(eq): rewrite EqualizerScreen UI (Spotify-style, glass cards)"
```

---

## Task 14 — Delete legacy EQ code

**Files:**
- Delete: `core/media/src/main/kotlin/com/stash/core/media/equalizer/EqualizerManager.kt`
- Delete: `core/media/src/main/kotlin/com/stash/core/media/equalizer/EqualizerSettings.kt`
- Delete: `core/media/src/main/kotlin/com/stash/core/media/equalizer/EqualizerStore.kt`
- Delete: `core/media/src/main/kotlin/com/stash/core/media/equalizer/EqPreset.kt`
- Modify: any callers that referenced these classes

- [ ] **Step 14.1: Find all references to soon-deleted classes**

```bash
grep -rn "EqualizerManager\|EqualizerSettings\|EqualizerStore\|EqPreset" --include="*.kt"
```

- [ ] **Step 14.2: Update each reference site**

Most references should already have been updated in Tasks 11-13 (Hilt module, ViewModel). Anything left over (e.g. logging, debug screens) should be either retired or updated to call `EqController` / `EqState`.

- [ ] **Step 14.3: Delete the four files**

```bash
git rm core/media/src/main/kotlin/com/stash/core/media/equalizer/EqualizerManager.kt \
       core/media/src/main/kotlin/com/stash/core/media/equalizer/EqualizerSettings.kt \
       core/media/src/main/kotlin/com/stash/core/media/equalizer/EqualizerStore.kt \
       core/media/src/main/kotlin/com/stash/core/media/equalizer/EqPreset.kt
```

- [ ] **Step 14.4: Verify the project builds + all unit tests pass**

```bash
./gradlew :core:media:testDebugUnitTest :feature:settings:testDebugUnitTest :app:assembleDebug
```
Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 14.5: Commit**

```bash
git add -u
git commit -m "refactor(eq): delete legacy EqualizerManager/Settings/Store/Preset"
```

---

## Task 15 — Manual QA + bug regression verification

**Files:** none (manual)

- [ ] **Step 15.1: Build & install the debug APK**

```bash
./gradlew :app:installDebug
```

- [ ] **Step 15.2: Reproduce the original bug scenario**

1. Open Stash → Settings → Equalizer.
2. Toggle EQ **on**, drag band 1 (60 Hz) to +6 dB. Play any song — verify audible bass boost.
3. **Force-stop the app** (Settings → Apps → Stash → Force stop).
4. Reopen Stash, play the same song.
   - **Expected (post-fix):** audio is *not* EQ'd. EQ toggle in the UI shows **off**.
   - **Pre-fix bug:** audio was still EQ'd; toggle showed off; re-enabling stacked.
5. Toggle EQ on. Drag band 1 to +6 dB. Verify audible bass boost.
   - Expected: same magnitude as step 2 — no stacking, no doubled gain.

- [ ] **Step 15.3: Reproduce the OOM-kill scenario**

```bash
adb shell am kill com.stash.app
```
Then reopen and play. Same expectations as step 15.2.

- [ ] **Step 15.4: Verify migration**

If you had EQ settings before this build, check that:
- Sliders show your old gain positions (preserved).
- Master toggle is **off** (silent reset).
- Tapping the toggle once restores the EQ exactly as it was.

- [ ] **Step 15.5: Verify presets**

Tap each built-in preset chip. Verify the curve and slider positions update visibly. Tap "+", name a custom preset, verify it appears in the chip row and is selectable.

- [ ] **Step 15.6: If any check fails**

STOP. Open `adb logcat` and copy the relevant tagged output (`EqController`, `Eq*Processor`). Re-engage the planning skill or systematic-debugging — do NOT patch ad-hoc.

- [ ] **Step 15.7: Final commit (if needed)**

```bash
git status # if any in-flight diagnostic changes, commit or revert
```

---

## Task 16 — Open a PR

- [ ] **Step 16.1: Push branch + open PR**

```bash
git push -u origin <branch>
gh pr create --title "feat(eq): rebuild equalizer engine — fix persistence/stacking bug" --body "$(cat <<'EOF'
## Summary
- Replaces `android.media.audiofx.Equalizer` with a custom Media3 `AudioProcessor` chain owned by a single `EqController` — kills the EQ persistence/stacking bug class structurally.
- 5-band graphic EQ matching Spotify centers (60/230/910/3.6k/14k Hz), Q=1.0, biquad cookbook coefficients.
- New Spotify-style EQ screen with live curve preview, master toggle, preset chips, dedicated bass-boost slider, and pre-amp slider.
- Silent migration from legacy `equalizer_prefs` DataStore: gain bands and presets preserved, master toggle force-reset to off (one-time).

## Test plan
- [ ] Unit tests pass: `:core:media:testDebugUnitTest`, `:feature:settings:testDebugUnitTest`
- [ ] Manual: bug regression scenario (Task 15.2) — force-stop, reopen, EQ off + audio off
- [ ] Manual: OOM-kill scenario (Task 15.3) — same expectations
- [ ] Manual: migration (Task 15.4) — old gains preserved, toggle off
- [ ] Manual: preset chips work (Task 15.5)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Glossary (for the executing engineer)

- **AudioProcessor** — Media3 interface for inserting custom DSP into the audio pipeline between decode and output.
- **biquad** — a 5-coefficient digital filter (2 input, 2 feedback, 1 normalisation); the atom of every EQ.
- **DF2T** (Direct Form II Transposed) — a numerically-stable arrangement of the biquad equation; minimises arithmetic and floating-point error accumulation.
- **RBJ Cookbook** — Robert Bristow-Johnson's "Audio EQ Cookbook," W3C-standard reference for biquad coefficient formulas.
- **Q** — bandwidth factor of a peaking filter; higher Q = narrower peak. 1.0 is graphic-EQ standard.
- **dBFS** — decibels relative to digital full scale (loudest signal a digital sample can encode without clipping).
- **PCM_FLOAT** — 32-bit float audio sample format; what Media3 uses internally for headroom.

## Skill references

- `@superpowers:test-driven-development` — for the failing-test-first cadence in every task.
- `@superpowers:verification-before-completion` — before claiming any task complete.
- `@superpowers:systematic-debugging` — if any test fails unexpectedly during execution.

---

**End of Plan 1.** Next plan (Loudness Normalization) lives at `docs/superpowers/plans/2026-XX-XX-equalizer-rebuild-plan-2-loudness.md` once Plan 1 is shipped and dogfooded.
