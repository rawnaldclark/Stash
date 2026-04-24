# Crossfade Between Tracks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement true audio crossfade between tracks (auto-transition + manual skip) for GitHub issue #16, with a Settings toggle (off by default) and 1-12 s duration slider.

**Architecture:** New `CrossfadingPlayer` class extending Media3's `ForwardingPlayer`, owning two `ExoPlayer` instances (A and B). The wrapper coordinates per-player volume ramps with equal-power (cosine) curves and forwards Player API calls based on a small SINGLE_ACTIVE / CROSSFADING state machine. The `MediaSession` is built with the wrapper as its single Player, so external observers (notification, lockscreen, Bluetooth, MediaController) see one Player and don't need crossfade awareness.

**Tech Stack:** Kotlin, Android, Media3 1.9.2 (`ExoPlayer`, `MediaSessionService`, `ForwardingPlayer`), kotlinx-coroutines (Mutex, Dispatchers, Job, delay), DataStore Preferences, Compose Material3, Hilt, JUnit 4 + mockito-kotlin + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-04-24-crossfade-design.md`

---

## Pre-flight

- [ ] Create a fresh worktree from current `master`:

```bash
cd C:/Users/theno/Projects/MP3APK
git worktree add .worktrees/crossfade -b feat/crossfade master
cp local.properties .worktrees/crossfade/local.properties
```

Memory `feedback_worktree_local_properties.md`: `git worktree add` doesn't carry `local.properties`; the `cp` line above prevents Last.fm/keystore "Not configured" symptoms in debug builds.

**All subsequent tasks operate in:** `C:/Users/theno/Projects/MP3APK/.worktrees/crossfade`. Every Bash command must begin with `cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfade && ...`. Read/Edit/Write tools should use absolute paths rooted at that worktree.

---

## Task 1: `CrossfadePreferences` (DataStore foundation)

**Verified facts:**
- The codebase already has a DataStore-backed preference pattern: `core/data/src/main/kotlin/com/stash/core/data/prefs/YouTubeHistoryPreference.kt`. Mirror its structure.
- DataStore + `androidx.datastore` is wired into the existing prefs; no new Hilt module needed if you follow the existing pattern (Hilt finds the new class via `@Singleton @Inject constructor`).

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/prefs/CrossfadePreferences.kt`
- Test (new): `core/data/src/test/kotlin/com/stash/core/data/prefs/CrossfadePreferencesTest.kt`

- [ ] **Step 1: Read existing `YouTubeHistoryPreference.kt`** to confirm the exact DataStore + Hilt pattern. Mirror it.

- [ ] **Step 2: Write failing tests**

```kotlin
package com.stash.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import io.mockk.mockk
// ... mirror the imports YouTubeHistoryPreferenceTest uses

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class CrossfadePreferencesTest {

    // The exact DataStore-stubbing pattern depends on how YouTubeHistoryPreferenceTest does it.
    // Most likely: an in-memory FakeDataStore or a real PreferenceDataStoreFactory pointed at a temp file.
    // Read YouTubeHistoryPreferenceTest.kt for the canonical pattern and mirror it exactly.

    @Test
    fun `enabled defaults to false`() = runTest {
        val prefs = buildCrossfadePreferences()
        assertFalse(prefs.enabled.first())
    }

    @Test
    fun `durationMs defaults to 4000`() = runTest {
        val prefs = buildCrossfadePreferences()
        assertEquals(4000L, prefs.durationMs.first())
    }

    @Test
    fun `setEnabled persists and reflects in flow`() = runTest {
        val prefs = buildCrossfadePreferences()
        prefs.setEnabled(true)
        assertTrue(prefs.enabled.first())
        prefs.setEnabled(false)
        assertFalse(prefs.enabled.first())
    }

    @Test
    fun `setDurationMs persists and reflects in flow`() = runTest {
        val prefs = buildCrossfadePreferences()
        prefs.setDurationMs(7000L)
        assertEquals(7000L, prefs.durationMs.first())
    }

    @Test
    fun `setDurationMs clamps below minimum`() = runTest {
        val prefs = buildCrossfadePreferences()
        prefs.setDurationMs(500L)  // below 1000ms minimum
        assertEquals(1000L, prefs.durationMs.first())
    }

    @Test
    fun `setDurationMs clamps above maximum`() = runTest {
        val prefs = buildCrossfadePreferences()
        prefs.setDurationMs(20_000L)  // above 12_000ms maximum
        assertEquals(12_000L, prefs.durationMs.first())
    }

    private fun buildCrossfadePreferences(): CrossfadePreferences {
        // TODO: mirror the pattern from YouTubeHistoryPreferenceTest's setup — most likely
        // a temp-file DataStore or in-memory fake.
        TODO("wire DataStore the same way the existing test does")
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfade && \
./gradlew :core:data:test --tests "com.stash.core.data.prefs.CrossfadePreferencesTest"
```

Expected: FAIL with `Unresolved reference: CrossfadePreferences`.

- [ ] **Step 4: Implement `CrossfadePreferences`**

```kotlin
package com.stash.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User preferences for the track-to-track crossfade feature (#16).
 *
 * Defaults: disabled, with a 4-second crossfade when enabled. The duration
 * is clamped to [MIN_DURATION_MS] .. [MAX_DURATION_MS] (1-12 seconds) on
 * write so the slider UI doesn't have to validate.
 */
@Singleton
class CrossfadePreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val enabled: Flow<Boolean> = dataStore.data.map { it[KEY_ENABLED] ?: DEFAULT_ENABLED }
    val durationMs: Flow<Long> = dataStore.data.map { it[KEY_DURATION_MS] ?: DEFAULT_DURATION_MS }

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { it[KEY_ENABLED] = value }
    }

    suspend fun setDurationMs(value: Long) {
        val clamped = value.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        dataStore.edit { it[KEY_DURATION_MS] = clamped }
    }

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("crossfade_enabled")
        private val KEY_DURATION_MS = longPreferencesKey("crossfade_duration_ms")

        const val DEFAULT_ENABLED = false
        const val DEFAULT_DURATION_MS = 4_000L
        const val MIN_DURATION_MS = 1_000L
        const val MAX_DURATION_MS = 12_000L
    }
}
```

If `YouTubeHistoryPreference.kt` injects a DataStore differently (e.g., a Hilt-provided named instance), mirror that. The constructor parameter type might need to be `DataStore<Preferences>` qualified with a Hilt `@Named(...)` if that's the existing convention.

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfade && \
./gradlew :core:data:test --tests "com.stash.core.data.prefs.CrossfadePreferencesTest"
```

Expected: all 6 tests pass.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfade && \
git add core/data/src/main/kotlin/com/stash/core/data/prefs/CrossfadePreferences.kt \
        core/data/src/test/kotlin/com/stash/core/data/prefs/CrossfadePreferencesTest.kt && \
git commit -m "feat(prefs): CrossfadePreferences (toggle + duration, clamped 1-12s)"
```

Verify branch: `git branch --show-current` → `feat/crossfade`.

---

## Task 2: `CrossfadingPlayer` skeleton + queue + `setMediaItems`

This task builds the wrapper's foundation: extends `ForwardingPlayer`, owns two underlying ExoPlayer instances + a canonical queue, and implements `setMediaItems` (which preloads the next track on `playerB` at volume 0). No volume ramp logic yet — that's Task 3. No manual-skip orchestration — Task 4.

**Verified facts:**
- Media3 1.9.2 ships `androidx.media3.common.ForwardingPlayer` (not deprecated)
- `Player.STATE_*` and `Player.MEDIA_ITEM_TRANSITION_REASON_*` constants are stable across Media3 1.x
- The codebase tests use mockito-kotlin for player mocks (precedent: existing `:core:media:test`)

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt`
- Test (new): `core/media/src/test/kotlin/com/stash/core/media/service/CrossfadingPlayerTest.kt`

- [ ] **Step 1: Write failing tests for queue + setMediaItems**

```kotlin
package com.stash.core.media.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.stash.core.data.prefs.CrossfadePreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadingPlayerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun `setMediaItems puts startIndex item on activePlayer and preloads next on nextPlayer at vol 0`() = runTest {
        val playerA = buildMockExoPlayer()
        val playerB = buildMockExoPlayer()
        val crossfade = buildCrossfadingPlayer(playerA, playerB)

        val items = listOf(item("v1"), item("v2"), item("v3"))
        crossfade.setMediaItems(items, /* startIndex = */ 0, /* startPositionMs = */ 0L)
        advanceUntilIdle()

        // playerA gets the start item + prepare + play (when play() is later called)
        verifyOrder {
            playerA.setMediaItem(items[0])
            playerA.prepare()
        }
        // playerB gets the NEXT item, prepared, vol 0, NOT played
        verify { playerB.setMediaItem(items[1]) }
        verify { playerB.prepare() }
        verify { playerB.volume = 0f }
        verify(exactly = 0) { playerB.play() }

        assertEquals(0, crossfade.currentMediaItemIndex)
        assertEquals(3, crossfade.mediaItemCount)
    }

    @Test
    fun `setMediaItems with startIndex past end preloads nothing on nextPlayer`() = runTest {
        val playerA = buildMockExoPlayer()
        val playerB = buildMockExoPlayer()
        val crossfade = buildCrossfadingPlayer(playerA, playerB)

        val items = listOf(item("v1"))
        crossfade.setMediaItems(items, 0, 0L)
        advanceUntilIdle()

        verify { playerA.setMediaItem(items[0]) }
        verify(exactly = 0) { playerB.setMediaItem(any()) }
    }

    @Test
    fun `getMediaItemAt returns from canonical queue not from underlying player`() = runTest {
        val playerA = buildMockExoPlayer()
        val playerB = buildMockExoPlayer()
        val crossfade = buildCrossfadingPlayer(playerA, playerB)

        val items = listOf(item("v1"), item("v2"), item("v3"))
        crossfade.setMediaItems(items, 0, 0L)

        assertEquals("v1", crossfade.getMediaItemAt(0).mediaId)
        assertEquals("v2", crossfade.getMediaItemAt(1).mediaId)
        assertEquals("v3", crossfade.getMediaItemAt(2).mediaId)
    }

    @Test
    fun `currentMediaItem returns active player's item during SINGLE_ACTIVE`() = runTest {
        val playerA = buildMockExoPlayer()
        val playerB = buildMockExoPlayer()
        every { playerA.currentMediaItem } returns item("v1")

        val crossfade = buildCrossfadingPlayer(playerA, playerB)
        crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)

        assertEquals("v1", crossfade.currentMediaItem?.mediaId)
    }

    @Test
    fun `hasNextMediaItem true when queue has more items`() = runTest {
        val crossfade = buildCrossfadingPlayer(buildMockExoPlayer(), buildMockExoPlayer())
        crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
        assertTrue(crossfade.hasNextMediaItem())
    }

    @Test
    fun `hasNextMediaItem false at end of queue`() = runTest {
        val crossfade = buildCrossfadingPlayer(buildMockExoPlayer(), buildMockExoPlayer())
        crossfade.setMediaItems(listOf(item("v1")), 0, 0L)
        assertFalse(crossfade.hasNextMediaItem())
    }

    // --- helpers ----------------------------------------------------------

    private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    private fun buildMockExoPlayer(): ExoPlayer = mockk<ExoPlayer>(relaxed = true).also {
        every { it.playbackState } returns Player.STATE_READY
        every { it.isPlaying } returns false
        every { it.currentPosition } returns 0L
        every { it.duration } returns 180_000L  // 3 min default
        every { it.currentMediaItem } returns null
        every { it.volume } returns 1.0f
    }

    private fun buildCrossfadingPlayer(
        playerA: ExoPlayer,
        playerB: ExoPlayer,
        enabled: Boolean = false,
        durationMs: Long = 4_000L,
    ): CrossfadingPlayer {
        val prefs = mockk<CrossfadePreferences>(relaxed = true)
        every { prefs.enabled } returns MutableStateFlow(enabled)
        every { prefs.durationMs } returns MutableStateFlow(durationMs)
        return CrossfadingPlayer(playerA, playerB, prefs, testScope)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfade && \
./gradlew :core:media:test --tests "com.stash.core.media.service.CrossfadingPlayerTest"
```

Expected: FAIL — `CrossfadingPlayer` doesn't exist.

- [ ] **Step 3: Implement the wrapper skeleton**

Create `core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt`:

```kotlin
package com.stash.core.media.service

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.stash.core.data.prefs.CrossfadePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Wrapper [Player] that crossfades between two [ExoPlayer] instances.
 *
 * Owns the canonical queue. Each underlying ExoPlayer holds exactly one
 * [MediaItem] at a time so the wrapper can control per-player volume
 * envelopes. The MediaSession holds this wrapper as its single Player —
 * external observers don't need to know two players are running.
 *
 * State machine (full description in spec §2):
 *  - SINGLE_ACTIVE: one player at full volume, the other silently preloaded
 *  - CROSSFADING:   both playing, volumes ramping (cosine) over duration
 *
 * See `docs/superpowers/specs/2026-04-24-crossfade-design.md`.
 */
class CrossfadingPlayer(
    private val playerA: ExoPlayer,
    private val playerB: ExoPlayer,
    private val crossfadePreferences: CrossfadePreferences,
    private val scope: CoroutineScope,
) : ForwardingPlayer(playerA) {

    private enum class State { SINGLE_ACTIVE, CROSSFADING }

    private val _state = MutableStateFlow(State.SINGLE_ACTIVE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val activeRef = AtomicReference<ExoPlayer>(playerA)
    private val nextRef = AtomicReference<ExoPlayer>(playerB)

    /** Canonical queue. Each underlying player holds exactly one item from this list. */
    private val mediaItems = mutableListOf<MediaItem>()
    private var currentIndex: Int = 0

    private fun activePlayer(): ExoPlayer = activeRef.get()
    private fun nextPlayer(): ExoPlayer = nextRef.get()

    // ---- Queue management overrides --------------------------------------

    override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        mediaItems.clear()
        mediaItems.addAll(items)
        currentIndex = startIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0))

        if (items.isEmpty()) return

        // Active gets the start item.
        activePlayer().setMediaItem(items[currentIndex])
        activePlayer().prepare()
        activePlayer().seekTo(startPositionMs)

        // Preload next on the other player at vol 0 (silent).
        preloadNextOnNextPlayer()
    }

    override fun setMediaItems(items: List<MediaItem>) =
        setMediaItems(items, 0, 0L)

    private fun preloadNextOnNextPlayer() {
        val nextItemIndex = currentIndex + 1
        if (nextItemIndex < mediaItems.size) {
            nextPlayer().setMediaItem(mediaItems[nextItemIndex])
            nextPlayer().prepare()
            nextPlayer().volume = 0f
        }
    }

    // ---- Read-side overrides (canonical queue is the source of truth) ----

    override fun getMediaItemCount(): Int = mediaItems.size
    override fun getMediaItemAt(index: Int): MediaItem = mediaItems[index]
    override fun getCurrentMediaItemIndex(): Int = currentIndex
    override fun hasNextMediaItem(): Boolean = currentIndex + 1 < mediaItems.size
    override fun hasPreviousMediaItem(): Boolean = currentIndex > 0

    // currentMediaItem etc. delegate to active player (during SINGLE_ACTIVE);
    // CROSSFADING-state forwarding rules added in Task 3.
    override fun getCurrentMediaItem(): MediaItem? = activePlayer().currentMediaItem
    override fun getCurrentPosition(): Long = activePlayer().currentPosition
    override fun getDuration(): Long = activePlayer().duration
    override fun isPlaying(): Boolean = activePlayer().isPlaying
    override fun getPlaybackState(): Int = activePlayer().playbackState

    // ---- Play/pause delegate to active during SINGLE_ACTIVE --------------
    // (Both-players logic added in Task 3 once we're crossfading.)

    override fun play() { activePlayer().play() }
    override fun pause() { activePlayer().pause() }
    override fun stop() { activePlayer().stop(); nextPlayer().stop() }
}
```

This is intentionally incomplete — Tasks 3 and 4 add the volume ramp + state machine + manual-skip overrides on top.

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfade && \
./gradlew :core:media:test --tests "com.stash.core.media.service.CrossfadingPlayerTest"
```

Expected: all 6 tests pass. Existing `:core:media:test` suite should also stay green.

- [ ] **Step 5: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfade && \
git add core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt \
        core/media/src/test/kotlin/com/stash/core/media/service/CrossfadingPlayerTest.kt && \
git commit -m "feat(media): CrossfadingPlayer skeleton — queue + setMediaItems + preload"
```

---

## Task 3: Volume ramp coroutine + auto-trigger (CROSSFADING state)

Adds the equal-power volume ramp and the position-monitoring coroutine that triggers a crossfade when the active track's remaining time drops below `crossfadeMs`. Implements the CROSSFADING-state forwarding rules from spec §3.

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt`
- Modify: `core/media/src/test/kotlin/com/stash/core/media/service/CrossfadingPlayerTest.kt`

- [ ] **Step 1: Write failing tests**

Add to `CrossfadingPlayerTest.kt`:

```kotlin
@Test
fun `auto-trigger fires when remaining time crosses crossfadeMs`() = runTest(testDispatcher) {
    val playerA = buildMockExoPlayer()
    val playerB = buildMockExoPlayer()
    every { playerA.duration } returns 60_000L
    every { playerA.isPlaying } returns true

    val crossfade = buildCrossfadingPlayer(playerA, playerB, enabled = true, durationMs = 4_000L)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()

    // Position approaching end: 60s - 4s = 56s remaining < 4s crossfade window
    every { playerA.currentPosition } returns 56_500L  // 3.5s remaining
    advanceUntilIdle()

    // The next player should now be playing (crossfade started)
    verify { playerB.play() }
    assertEquals(CrossfadingPlayer.State.CROSSFADING, crossfade.state.value)
}

@Test
fun `auto-trigger does NOT fire when crossfade disabled`() = runTest(testDispatcher) {
    val playerA = buildMockExoPlayer()
    val playerB = buildMockExoPlayer()
    every { playerA.duration } returns 60_000L
    every { playerA.isPlaying } returns true

    val crossfade = buildCrossfadingPlayer(playerA, playerB, enabled = false, durationMs = 4_000L)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()

    every { playerA.currentPosition } returns 58_000L  // 2s remaining, well past threshold
    advanceUntilIdle()

    verify(exactly = 0) { playerB.play() }
    assertEquals(CrossfadingPlayer.State.SINGLE_ACTIVE, crossfade.state.value)
}

@Test
fun `auto-trigger does NOT fire when track shorter than 2x crossfadeMs`() = runTest(testDispatcher) {
    val playerA = buildMockExoPlayer()
    val playerB = buildMockExoPlayer()
    every { playerA.duration } returns 5_000L  // 5s track, 4s crossfade → 5 < 8 → skip
    every { playerA.isPlaying } returns true

    val crossfade = buildCrossfadingPlayer(playerA, playerB, enabled = true, durationMs = 4_000L)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()

    every { playerA.currentPosition } returns 1_500L  // 3.5s remaining < 4s window
    advanceUntilIdle()

    verify(exactly = 0) { playerB.play() }
    assertEquals(CrossfadingPlayer.State.SINGLE_ACTIVE, crossfade.state.value)
}

@Test
fun `equal-power curve at midpoint both volumes ~ 0707`() {
    val midActive = CrossfadingPlayer.activeVolumeAtFraction(0.5f)
    val midNext = CrossfadingPlayer.nextVolumeAtFraction(0.5f)
    assertEquals(0.7071f, midActive, 0.001f)
    assertEquals(0.7071f, midNext, 0.001f)
}

@Test
fun `equal-power curve endpoints`() {
    assertEquals(1.0f, CrossfadingPlayer.activeVolumeAtFraction(0.0f), 0.001f)
    assertEquals(0.0f, CrossfadingPlayer.activeVolumeAtFraction(1.0f), 0.001f)
    assertEquals(0.0f, CrossfadingPlayer.nextVolumeAtFraction(0.0f), 0.001f)
    assertEquals(1.0f, CrossfadingPlayer.nextVolumeAtFraction(1.0f), 0.001f)
}

@Test
fun `during CROSSFADING currentMediaItem returns next player's item`() = runTest(testDispatcher) {
    val playerA = buildMockExoPlayer()
    val playerB = buildMockExoPlayer()
    every { playerA.duration } returns 60_000L
    every { playerA.isPlaying } returns true
    every { playerB.currentMediaItem } returns item("v2")

    val crossfade = buildCrossfadingPlayer(playerA, playerB, enabled = true, durationMs = 4_000L)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()
    every { playerA.currentPosition } returns 57_000L  // crossfade triggers
    advanceUntilIdle()

    // currentMediaItem should now reflect the incoming track.
    assertEquals("v2", crossfade.currentMediaItem?.mediaId)
    assertEquals(1, crossfade.currentMediaItemIndex)
}

@Test
fun `play during CROSSFADING calls play on both players`() = runTest(testDispatcher) {
    // Drive into CROSSFADING state, then verify play() routes to both
    val playerA = buildMockExoPlayer()
    val playerB = buildMockExoPlayer()
    every { playerA.duration } returns 60_000L
    every { playerA.isPlaying } returns true

    val crossfade = buildCrossfadingPlayer(playerA, playerB, enabled = true, durationMs = 4_000L)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()
    every { playerA.currentPosition } returns 57_000L
    advanceUntilIdle()

    crossfade.pause()
    verify { playerA.pause() }
    verify { playerB.pause() }
}
```

- [ ] **Step 2: Run tests, expect FAIL** — they reference `CrossfadingPlayer.activeVolumeAtFraction` (companion fn) and `State.CROSSFADING` behavior that doesn't exist yet.

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfade && \
./gradlew :core:media:test --tests "com.stash.core.media.service.CrossfadingPlayerTest"
```

- [ ] **Step 3: Implement the volume curve + position monitor + state machine**

Add to `CrossfadingPlayer.kt`:

```kotlin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.cos
import kotlin.math.sin

// ---- Companion: volume curve (pure math, easy to unit-test) -------------

companion object {
    /** Equal-power active-track volume at fade fraction t in [0,1]. */
    fun activeVolumeAtFraction(t: Float): Float = cos(t * Math.PI.toFloat() / 2f)

    /** Equal-power incoming-track volume at fade fraction t in [0,1]. */
    fun nextVolumeAtFraction(t: Float): Float = sin(t * Math.PI.toFloat() / 2f)

    private const val POSITION_POLL_MS = 250L
    private const val RAMP_TICK_MS = 50L
}

// ---- State / scope plumbing ---------------------------------------------

private var positionMonitorJob: Job? = null
private var rampJob: Job? = null
private var currentDurationMs: Long = CrossfadePreferences.DEFAULT_DURATION_MS

init {
    // Track preference changes
    scope.launch {
        crossfadePreferences.durationMs.collectLatest { currentDurationMs = it }
    }
    // Position monitor — drives auto-trigger
    positionMonitorJob = scope.launch {
        while (true) {
            delay(POSITION_POLL_MS)
            maybeStartAutoCrossfade()
        }
    }
}

private suspend fun maybeStartAutoCrossfade() {
    if (_state.value != State.SINGLE_ACTIVE) return
    if (!crossfadePreferences.enabled.first()) return  // pull current value
    if (!hasNextMediaItem()) return

    val active = activePlayer()
    if (!active.isPlaying) return
    val duration = active.duration
    if (duration <= 0L) return  // unknown duration — skip

    val crossfadeMs = currentDurationMs
    if (duration < crossfadeMs * 2) return  // track too short — hard cut

    val remaining = duration - active.currentPosition
    if (remaining <= crossfadeMs) {
        startCrossfade()
    }
}

private fun startCrossfade() {
    if (_state.value == State.CROSSFADING) return
    val incomingPlayer = nextPlayer()
    if (incomingPlayer.currentMediaItem == null) return  // nothing preloaded — abort

    _state.value = State.CROSSFADING
    incomingPlayer.volume = 0f
    incomingPlayer.play()
    val crossfadeMs = currentDurationMs

    rampJob?.cancel()
    rampJob = scope.launch {
        val outgoing = activePlayer()
        val incoming = nextPlayer()
        val startNs = System.nanoTime()
        while (true) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            val t = (elapsedMs.toFloat() / crossfadeMs).coerceIn(0f, 1f)
            outgoing.volume = activeVolumeAtFraction(t)
            incoming.volume = nextVolumeAtFraction(t)
            if (t >= 1f) break
            delay(RAMP_TICK_MS)
        }
        completeCrossfade(outgoing, incoming)
    }
}

private fun completeCrossfade(outgoing: ExoPlayer, incoming: ExoPlayer) {
    // Swap roles: incoming becomes new active.
    activeRef.set(incoming)
    nextRef.set(outgoing)
    outgoing.stop()
    outgoing.clearMediaItems()
    currentIndex += 1
    _state.value = State.SINGLE_ACTIVE
    preloadNextOnNextPlayer()
}

// ---- Read-side overrides updated to be CROSSFADING-aware ----------------

override fun getCurrentMediaItem(): MediaItem? =
    when (_state.value) {
        State.SINGLE_ACTIVE -> activePlayer().currentMediaItem
        State.CROSSFADING -> nextPlayer().currentMediaItem  // already showing incoming
    }

override fun getCurrentPosition(): Long =
    when (_state.value) {
        State.SINGLE_ACTIVE -> activePlayer().currentPosition
        State.CROSSFADING -> nextPlayer().currentPosition
    }

override fun getDuration(): Long =
    when (_state.value) {
        State.SINGLE_ACTIVE -> activePlayer().duration
        State.CROSSFADING -> nextPlayer().duration
    }

override fun isPlaying(): Boolean = activePlayer().isPlaying || nextPlayer().isPlaying

override fun getPlaybackState(): Int =
    when (_state.value) {
        State.SINGLE_ACTIVE -> activePlayer().playbackState
        State.CROSSFADING -> nextPlayer().playbackState
    }

override fun getCurrentMediaItemIndex(): Int =
    when (_state.value) {
        State.SINGLE_ACTIVE -> currentIndex
        State.CROSSFADING -> currentIndex + 1  // already counted as advanced
    }

override fun hasNextMediaItem(): Boolean =
    when (_state.value) {
        State.SINGLE_ACTIVE -> currentIndex + 1 < mediaItems.size
        State.CROSSFADING -> currentIndex + 2 < mediaItems.size
    }

// ---- Play/pause/stop overrides updated ----------------------------------

override fun play() {
    when (_state.value) {
        State.SINGLE_ACTIVE -> activePlayer().play()
        State.CROSSFADING -> { activePlayer().play(); nextPlayer().play() }
    }
}

override fun pause() {
    when (_state.value) {
        State.SINGLE_ACTIVE -> activePlayer().pause()
        State.CROSSFADING -> { activePlayer().pause(); nextPlayer().pause() }
    }
}
```

You'll need `kotlinx.coroutines.flow.first` import for `crossfadePreferences.enabled.first()`.

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfade && \
./gradlew :core:media:test --tests "com.stash.core.media.service.CrossfadingPlayerTest"
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfade && \
git add core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt \
        core/media/src/test/kotlin/com/stash/core/media/service/CrossfadingPlayerTest.kt && \
git commit -m "feat(media): equal-power volume ramp + auto-trigger position monitor"
```

---

## Task 4: Manual skip handling (`seekToNext` / `seekToPrevious`) + rebound

Implements explicit skip-driven crossfades, including the "rebound" case where the user skips again while a crossfade is in flight.

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt`
- Modify: `core/media/src/test/kotlin/com/stash/core/media/service/CrossfadingPlayerTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
@Test
fun `manual seekToNext during SINGLE_ACTIVE starts crossfade`() = runTest(testDispatcher) {
    val playerA = buildMockExoPlayer()
    val playerB = buildMockExoPlayer()
    every { playerA.isPlaying } returns true

    val crossfade = buildCrossfadingPlayer(playerA, playerB, enabled = true, durationMs = 4_000L)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()

    crossfade.seekToNext()
    advanceUntilIdle()

    verify { playerB.play() }
    // After fade completes (advanced past 4s), state should be SINGLE_ACTIVE on playerB
    advanceTimeBy(5_000L)
    advanceUntilIdle()
    assertEquals(CrossfadingPlayer.State.SINGLE_ACTIVE, crossfade.state.value)
}

@Test
fun `seekToNext when crossfade disabled does instant cut (no fade)`() = runTest(testDispatcher) {
    val playerA = buildMockExoPlayer()
    val playerB = buildMockExoPlayer()

    val crossfade = buildCrossfadingPlayer(playerA, playerB, enabled = false, durationMs = 4_000L)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()

    crossfade.seekToNext()
    advanceUntilIdle()

    // No crossfade — playerA should stop, playerB should immediately play at full volume.
    verify { playerA.stop() }
    verify { playerB.volume = 1f }
    verify { playerB.play() }
    assertEquals(CrossfadingPlayer.State.SINGLE_ACTIVE, crossfade.state.value)
    assertEquals(1, crossfade.currentMediaItemIndex)
}

@Test
fun `rebound skip during crossfade swaps to next-next item`() = runTest(testDispatcher) {
    val playerA = buildMockExoPlayer()
    val playerB = buildMockExoPlayer()
    every { playerA.isPlaying } returns true

    val crossfade = buildCrossfadingPlayer(playerA, playerB, enabled = true, durationMs = 4_000L)
    crossfade.setMediaItems(listOf(item("v1"), item("v2"), item("v3")), 0, 0L)
    crossfade.play()

    // Skip 1 → starts crossfade A(v1) → B(v2)
    crossfade.seekToNext()
    advanceUntilIdle()
    assertEquals(CrossfadingPlayer.State.CROSSFADING, crossfade.state.value)

    // Skip 2 (rebound) — the new "next" should be v3 on player A
    crossfade.seekToNext()
    advanceUntilIdle()

    // v2 should now be the active (snapped to vol 1, not faded out further)
    // v3 should be loaded on the formerly-active player at vol 0, fading in
    verify { playerA.setMediaItem(item("v3")) }
}

@Test
fun `seekToPrevious during SINGLE_ACTIVE starts crossfade to previous item`() = runTest(testDispatcher) {
    val playerA = buildMockExoPlayer()
    val playerB = buildMockExoPlayer()
    every { playerA.isPlaying } returns true

    val crossfade = buildCrossfadingPlayer(playerA, playerB, enabled = true, durationMs = 4_000L)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 1, 0L)  // start on v2
    crossfade.play()

    crossfade.seekToPrevious()
    advanceUntilIdle()

    verify { playerB.setMediaItem(item("v1")) }
    verify { playerB.play() }
    assertEquals(CrossfadingPlayer.State.CROSSFADING, crossfade.state.value)
}
```

- [ ] **Step 2: Run tests, expect FAIL** — `seekToNext` / `seekToPrevious` currently delegate to the underlying ForwardingPlayer, which doesn't fade.

- [ ] **Step 3: Implement skip overrides**

Add to `CrossfadingPlayer.kt`:

```kotlin
override fun seekToNext() {
    val nextIndex = currentIndex + 1
    if (nextIndex >= mediaItems.size) return  // no next

    when (_state.value) {
        State.SINGLE_ACTIVE -> startManualCrossfadeTo(nextIndex)
        State.CROSSFADING -> rebound(nextIndex + 1)  // skip past the in-progress fade target
    }
}

override fun seekToPrevious() {
    val prevIndex = currentIndex - 1
    if (prevIndex < 0) return  // no previous

    when (_state.value) {
        State.SINGLE_ACTIVE -> startManualCrossfadeTo(prevIndex)
        State.CROSSFADING -> rebound(prevIndex)
    }
}

/**
 * Manual-skip starting from SINGLE_ACTIVE: load the target on the next
 * player, then start the same crossfade ramp as the auto-trigger uses.
 * If crossfade is disabled, instant-cut instead.
 */
private fun startManualCrossfadeTo(targetIndex: Int) {
    val target = mediaItems[targetIndex]
    val incoming = nextPlayer()
    val outgoing = activePlayer()

    incoming.setMediaItem(target)
    incoming.prepare()

    // Honor the disabled toggle even on manual skip — instant cut.
    if (!scope.let { runBlocking { crossfadePreferences.enabled.first() } }) {
        outgoing.stop()
        incoming.volume = 1f
        incoming.play()
        activeRef.set(incoming); nextRef.set(outgoing)
        currentIndex = targetIndex
        outgoing.clearMediaItems()
        preloadNextOnNextPlayer()
        return
    }

    // ... otherwise: start the same ramp as auto-trigger.
    // The startCrossfade() function targets currentIndex+1 by default — for manual
    // skip we need to update currentIndex differently before calling it. Refactor
    // startCrossfade(...) to accept an explicit targetIndex parameter.
    currentIndex = targetIndex - 1  // so completeCrossfade's `currentIndex += 1` lands on target
    startCrossfade()
}

/**
 * Rebound: user skipped while a crossfade was in flight. Cancel the
 * in-flight ramp, snap the incoming track (already partially audible) to
 * full volume, then start a fresh crossfade to the new target.
 */
private fun rebound(newTargetIndex: Int) {
    if (newTargetIndex < 0 || newTargetIndex >= mediaItems.size) return

    rampJob?.cancel()
    val previouslyIncoming = nextPlayer()  // was fading in
    val previouslyOutgoing = activePlayer()  // was fading out

    // Snap previously-incoming to full volume and call it the new active.
    previouslyIncoming.volume = 1f
    previouslyOutgoing.stop()
    previouslyOutgoing.clearMediaItems()
    activeRef.set(previouslyIncoming); nextRef.set(previouslyOutgoing)
    currentIndex += 1  // we passed the rebound origin
    _state.value = State.SINGLE_ACTIVE

    // Now start fresh crossfade from new active → newTargetIndex.
    startManualCrossfadeTo(newTargetIndex)
}
```

Note the `runBlocking { crossfadePreferences.enabled.first() }` is awkward — these manual-skip handlers fire on the main thread synchronously. Either:
- Cache a `currentEnabled: Boolean` member updated from a `scope.launch { crossfadePreferences.enabled.collect { currentEnabled = it } }` (preferred), or
- Accept the `runBlocking` cost (one DataStore read, microseconds)

Pick the cached approach in implementation:

```kotlin
private var currentEnabled: Boolean = CrossfadePreferences.DEFAULT_ENABLED

init {
    scope.launch {
        crossfadePreferences.enabled.collect { currentEnabled = it }
    }
    scope.launch {
        crossfadePreferences.durationMs.collectLatest { currentDurationMs = it }
    }
    // ... position monitor as before
}
```

Then `startManualCrossfadeTo` checks `currentEnabled` directly.

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfade && \
./gradlew :core:media:test --tests "com.stash.core.media.service.CrossfadingPlayerTest"
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfade && \
git add core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt \
        core/media/src/test/kotlin/com/stash/core/media/service/CrossfadingPlayerTest.kt && \
git commit -m "feat(media): manual-skip crossfade + rebound (skip during in-flight fade)"
```

---

## Task 5: Wire `CrossfadingPlayer` into `StashPlaybackService`

Replace the single `ExoPlayer` in the service with two players + the wrapper. Both players share an audio session ID so the equalizer continues to apply.

**Verified facts:**
- Existing `StashPlaybackService.kt:onCreate` builds one ExoPlayer + audioSessionId + EqualizerManager init + MediaSession
- `EqualizerManager.initialize(audioSessionId)` takes a single ID — both players must use the SAME id

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt`

- [ ] **Step 1: Inject CrossfadePreferences into the service**

Add `@Inject lateinit var crossfadePreferences: CrossfadePreferences` near the existing `@Inject lateinit var equalizerManager: EqualizerManager`.

Add an application-scope `CoroutineScope` for the wrapper:
```kotlin
private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
```

- [ ] **Step 2: Build TWO ExoPlayer instances**

In `onCreate`, replace the `val player = ExoPlayer.Builder(this)...build()` with:

```kotlin
val playerA = ExoPlayer.Builder(this)
    .setLoadControl(loadControl)
    .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
    .setHandleAudioBecomingNoisy(true)
    .setWakeMode(C.WAKE_MODE_LOCAL)
    .build()

val playerB = ExoPlayer.Builder(this)
    .setLoadControl(loadControl)
    .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)  // playerA owns focus
    .setHandleAudioBecomingNoisy(true)
    .setWakeMode(C.WAKE_MODE_LOCAL)
    .build()

// Both players use the same audio session ID so the equalizer applies to both.
playerA.audioSessionId = audioSessionId
playerB.audioSessionId = audioSessionId

// Equalizer init is unchanged — it binds to the shared session ID.
equalizerManager.initialize(audioSessionId)

val crossfadingPlayer = CrossfadingPlayer(playerA, playerB, crossfadePreferences, playerScope)
```

- [ ] **Step 3: Pass the wrapper to MediaSession**

```kotlin
val sessionBuilder = MediaSession.Builder(this, crossfadingPlayer)
    .setCallback(StashSessionCallback())
```

Update `onDestroy` to release both players:

```kotlin
override fun onDestroy() {
    equalizerManager.release()
    mediaSession?.run {
        // player here IS the CrossfadingPlayer wrapper; releasing it must
        // release both underlying ExoPlayers. Add a release() override on
        // the wrapper that cascades to playerA + playerB.
        player.release()
        release()
    }
    mediaSession = null
    playerScope.cancel()
    super.onDestroy()
}
```

Make sure `CrossfadingPlayer` overrides `release()`:

```kotlin
override fun release() {
    positionMonitorJob?.cancel()
    rampJob?.cancel()
    playerA.release()
    playerB.release()
    super.release()
}
```

- [ ] **Step 4: Build-check**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfade && \
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run all tests to confirm no regression**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfade && \
./gradlew :core:media:test :core:data:test
```

Pre-existing baseline failures (e.g., `YtLibraryCanonicalizerTest`) acceptable; nothing NEW should fail.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfade && \
git add core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt \
        core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt && \
git commit -m "feat(media): wire CrossfadingPlayer into StashPlaybackService"
```

---

## Task 6: Settings UI (`PlaybackSection` composable + ViewModel + UiState)

Add the user-facing toggle + slider. Off by default; slider hidden when toggle is off.

**Verified facts:**
- `SettingsScreen.kt` has the existing structure with sections at lines ~352 ("Audio Quality") and ~514 ("Audio Effects")
- `EqualizerSection.kt` is the reference composable for styling
- `SettingsViewModel.kt` is the reference for adding new prefs

**Files:**
- Create: `feature/settings/src/main/kotlin/com/stash/feature/settings/components/PlaybackSection.kt`
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsScreen.kt`
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsUiState.kt`
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt`

- [ ] **Step 1: Read `EqualizerSection.kt` and `YouTubeHistorySyncSection.kt`** to understand the canonical composable pattern (Material3 + GlassCard styling per the design memory).

- [ ] **Step 2: Add fields to UiState**

In `SettingsUiState.kt`:
```kotlin
val crossfadeEnabled: Boolean = false,
val crossfadeDurationSeconds: Int = 4,
```

- [ ] **Step 3: Add ViewModel methods + collect prefs**

In `SettingsViewModel.kt`, inject `CrossfadePreferences`. In init / state-flow combine, add:
```kotlin
combine(
    // ... existing flows
    crossfadePreferences.enabled,
    crossfadePreferences.durationMs,
) { ... existing args, crossfadeEnabled, crossfadeDurationMs ->
    state.copy(
        crossfadeEnabled = crossfadeEnabled,
        crossfadeDurationSeconds = (crossfadeDurationMs / 1000).toInt(),
    )
}
```

Add setters:
```kotlin
fun setCrossfadeEnabled(value: Boolean) {
    viewModelScope.launch { crossfadePreferences.setEnabled(value) }
}
fun setCrossfadeDurationSeconds(seconds: Int) {
    viewModelScope.launch { crossfadePreferences.setDurationMs(seconds * 1000L) }
}
```

- [ ] **Step 4: Build the `PlaybackSection` composable**

`feature/settings/src/main/kotlin/com/stash/feature/settings/components/PlaybackSection.kt`:

```kotlin
package com.stash.feature.settings.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
// ... imports following EqualizerSection.kt

@Composable
fun PlaybackSection(
    crossfadeEnabled: Boolean,
    crossfadeDurationSeconds: Int,
    onCrossfadeEnabledChanged: (Boolean) -> Unit,
    onCrossfadeDurationChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Wrap in GlassCard / section styling per EqualizerSection.kt
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Crossfade", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Smooth transitions between tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = crossfadeEnabled,
                onCheckedChange = onCrossfadeEnabledChanged,
            )
        }
        AnimatedVisibility(visible = crossfadeEnabled) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Duration", modifier = Modifier.weight(1f))
                    Text("$crossfadeDurationSeconds sec")
                }
                Slider(
                    value = crossfadeDurationSeconds.toFloat(),
                    onValueChange = { onCrossfadeDurationChanged(it.toInt()) },
                    valueRange = 1f..12f,
                    steps = 10,  // 1, 2, 3, ..., 12
                )
            }
        }
    }
}
```

- [ ] **Step 5: Insert into `SettingsScreen.kt`**

Between the Audio Quality section and the Audio Effects section:

```kotlin
// -- Playback section ----------------------------------------------------
SectionHeader(title = "Playback")
PlaybackSection(
    crossfadeEnabled = uiState.crossfadeEnabled,
    crossfadeDurationSeconds = uiState.crossfadeDurationSeconds,
    onCrossfadeEnabledChanged = viewModel::setCrossfadeEnabled,
    onCrossfadeDurationChanged = viewModel::setCrossfadeDurationSeconds,
)
```

- [ ] **Step 6: Build-check**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfade && \
./gradlew :feature:settings:assembleDebug :app:assembleDebug
```

- [ ] **Step 7: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfade && \
git add feature/settings/src/main/kotlin/com/stash/feature/settings/ && \
git commit -m "feat(settings): Crossfade toggle + duration slider in Playback section"
```

---

## Task 7: Device acceptance

- [ ] **Step 1: Build + install**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfade && \
./gradlew :app:installDebug
```

- [ ] **Step 2: Run acceptance flow on device**

Per spec §Testing → Manual acceptance:

1. **Toggle OFF (default).** Play All from a playlist with 3+ tracks → hard cuts as today. Tap next/previous → instant. Confirms no regression for the default state.
2. **Toggle ON, duration 4s.** Play All → at the end of track 1, audio overlaps with track 2 over 4 seconds. Sounds natural, no perceptible volume dip mid-fade.
3. **Manual skip during crossfade-enabled playback.** Tap next mid-track → 4s crossfade kicks in. Tap previous → fades back to previous track. No glitches.
4. **Rebound: skip during a crossfade.** Tap next, then immediately tap next again before the first crossfade completes → smooth transition, no audio pop.
5. **Slider 1s → 12s.** Each value takes effect on the next crossfade. In-flight crossfade keeps its original duration.
6. **Toggle OFF mid-fade.** In-flight crossfade snaps cleanly. Subsequent transitions are hard cuts.
7. **Equalizer.** Open Audio Effects, change EQ. Confirm EQ still applies through both fade-in and fade-out players (volume changes audible on both halves of a crossfade).
8. **Bluetooth (if available).** Pair headphones. Crossfade still works. Skip via headphone button still triggers manual-skip crossfade.
9. **Lockscreen.** Lock the phone mid-playback. Notification updates with new track metadata when each crossfade starts (because `currentMediaItem` flips to the incoming track when crossfade begins, per spec §3 forwarding rules).
10. **Five tracks back-to-back with crossfade ON.** Each transition smooth; metadata updates correctly.

If anything sounds glitchy or feels wrong, STOP and `adb logcat -s Crossfade StashPlayer ExoPlayer:V` — the `Log.d("Crossfade", ...)` lines from spec §Observability should narrate every state transition.

- [ ] **Step 3: Commit (empty, for the record)**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfade && \
git commit --allow-empty -m "test: manual device acceptance — crossfade auto + manual + rebound"
```

---

## Task 8: Version bump + release 0.7.0

This is a meaningful new feature, so a minor version bump (0.6.x → 0.7.0) is appropriate.

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Bump version**

In `app/build.gradle.kts`:
```kotlin
versionCode = 32
versionName = "0.7.0"
```

- [ ] **Step 2: Release commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfade && \
git add app/build.gradle.kts && \
git commit -m "$(cat <<'EOF'
feat: 0.7.0 — track crossfade (#16)

True audio crossfade between tracks, on both auto-transitions and
manual skips. Off by default; toggle and duration slider (1-12s,
default 4s) live in Settings → Playback.

Implementation:
- New CrossfadingPlayer wrapper extends Media3 ForwardingPlayer
- Owns two ExoPlayer instances; each holds one MediaItem at a time
- Equal-power (cosine) volume ramp keeps perceived loudness constant
  across the overlap
- Both players share an audio session ID so the equalizer applies
  through the entire fade
- State machine handles SINGLE_ACTIVE / CROSSFADING + rebound when
  user skips during an in-flight fade

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 3: Merge to master**

```bash
cd C:/Users/theno/Projects/MP3APK && \
git merge --ff-only feat/crossfade && \
git log --oneline -5
```

- [ ] **Step 4: Tag + push**

```bash
cd C:/Users/theno/Projects/MP3APK && \
git tag -a v0.7.0 -m "v0.7.0 — track crossfade (#16)" && \
git push origin master && \
git push origin v0.7.0
```

- [ ] **Step 5: GitHub release + close issue**

```bash
cd C:/Users/theno/Projects/MP3APK && \
gh release create v0.7.0 --title "v0.7.0 — Track crossfade" --notes "$(cat <<'EOF'
Implements [#16](https://github.com/rawnaldclark/Stash/issues/16): track-to-track crossfade.

Smooth audio transitions between songs — off by default, opt in via Settings → Playback.

**Features:**
- Crossfade fires on both auto-transitions (track ends naturally) AND manual skip (next/previous), per the original request
- Configurable duration: 1-12 seconds (4 second default)
- Equal-power (cosine) volume curves keep perceived loudness constant across the overlap
- Shared audio session ID means your EQ applies to both halves of every crossfade

**Under the hood:** new \`CrossfadingPlayer\` wrapper around Media3's \`ForwardingPlayer\`, owning two ExoPlayer instances. State machine handles the rebound case (skip during a crossfade-in-progress) cleanly.

**Battery impact:** the second decoder runs only during the fade window itself (~4 s out of every several-minute track). Negligible.
EOF
)" && \
gh issue close 16 --comment "Implemented in [v0.7.0](https://github.com/rawnaldclark/Stash/releases/tag/v0.7.0)."
```

- [ ] **Step 6: Clean up worktree**

```bash
cd C:/Users/theno/Projects/MP3APK && \
git worktree remove .worktrees/crossfade && \
git branch -d feat/crossfade
```

If Windows path-length errors hit, leave the worktree dir for later manual cleanup.

---

## Skills reference

- @superpowers:test-driven-development — follow strictly for Tasks 1-4 (foundation, ramp logic, manual skip)
- @superpowers:verification-before-completion — before declaring each task done; do not skip the device acceptance in Task 7
- @superpowers:systematic-debugging — if Task 7 surfaces glitches; logcat with `Log.d("Crossfade", ...)` markers should narrate the issue

## Risks / rollback

- **Rollback:** revert the release commit. Two ExoPlayer instances revert to one; the wrapper class becomes dead code (delete in a follow-up). DataStore preferences stay in users' caches but are silently ignored. Settings UI tile becomes unreachable. No user data touched.
- **MediaSession state desync.** The biggest implementation risk. Mitigated by the forwarding rules in spec §3 + the device-acceptance lockscreen/notification check in Task 7.
- **Equalizer breaks if `audioSessionId` isn't shared between both players.** Task 5 explicitly sets both to the same id; spec §Risks calls this out. Verify by ear during Task 7 step 7.
