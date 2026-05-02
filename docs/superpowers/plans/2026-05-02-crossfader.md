# Crossfader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement true track-to-track audio crossfade for Stash (auto-transition + manual skip), with a Settings toggle (ON by default, 2 s default duration, 1–12 s range).

**Architecture:** A new `CrossfadingPlayer` extends Media3's `ForwardingPlayer` and wraps two `ExoPlayer` instances ("A" and "B"). It owns a canonical `mediaItems` queue plus a precomputed `shuffleOrder`, runs a `SINGLE_ACTIVE` ↔ `CROSSFADING` state machine, drives an equal-power cosine volume ramp via a Looper-bound coroutine, and synthesises a `CanonicalQueueTimeline` so the system notification's queue UI works. The `MediaSession` is built with the wrapper as its single Player so external observers (notification, lockscreen, Bluetooth) need no awareness.

**Tech Stack:** Kotlin, Android, Media3 1.9.2 (`ExoPlayer`, `MediaSessionService`, `ForwardingPlayer`, `Timeline`), kotlinx-coroutines, DataStore Preferences, Compose Material3, Hilt, JUnit 4 + mockk + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-05-02-crossfader-design.md`

---

## Pre-flight

- [ ] **Create a fresh worktree from current `master`:**

```bash
cd C:/Users/theno/Projects/MP3APK
git worktree add .worktrees/crossfader -b feat/crossfader master
cp local.properties .worktrees/crossfader/local.properties
```

The `cp local.properties` line is required (memory `feedback_worktree_local_properties.md`) — `git worktree add` doesn't carry `local.properties`, so without it Last.fm and the keystore show "Not configured" in debug builds.

**All subsequent tasks operate in:** `C:/Users/theno/Projects/MP3APK/.worktrees/crossfader`. Every `Bash` command must begin with `cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && ...`. Read/Edit/Write tools should use absolute paths rooted at that worktree.

---

## Task 1: `CrossfadePreferences` (DataStore foundation)

Toggle + duration, mirroring the existing `YouTubeHistoryPreference` pattern. Pure clamp logic extracted to a companion fn so it's unit-testable without DataStore.

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/prefs/CrossfadePreferences.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/prefs/CrossfadePreferencesClampTest.kt`

- [ ] **Step 1: Write `CrossfadePreferences`**

```kotlin
package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.crossfadeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "crossfade_preference",
)

@Singleton
class CrossfadePreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val enabled: Flow<Boolean> = context.crossfadeDataStore.data.map { prefs ->
        prefs[KEY_ENABLED] ?: DEFAULT_ENABLED
    }

    val durationMs: Flow<Long> = context.crossfadeDataStore.data.map { prefs ->
        prefs[KEY_DURATION_MS] ?: DEFAULT_DURATION_MS
    }

    suspend fun currentEnabled(): Boolean = enabled.first()
    suspend fun currentDurationMs(): Long = durationMs.first()

    suspend fun setEnabled(value: Boolean) {
        context.crossfadeDataStore.edit { it[KEY_ENABLED] = value }
    }

    suspend fun setDurationMs(value: Long) {
        context.crossfadeDataStore.edit { it[KEY_DURATION_MS] = clampDurationMs(value) }
    }

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("enabled")
        private val KEY_DURATION_MS = longPreferencesKey("duration_ms")

        const val DEFAULT_ENABLED = true        // ON by default — spec §6
        const val DEFAULT_DURATION_MS = 2_000L  // 2 s — spec §6
        const val MIN_DURATION_MS = 1_000L
        const val MAX_DURATION_MS = 12_000L

        fun clampDurationMs(value: Long): Long = value.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }
}
```

- [ ] **Step 2: Write the clamp test**

```kotlin
package com.stash.core.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class CrossfadePreferencesClampTest {

    @Test
    fun `clamp passes through value within range`() {
        assertEquals(2_000L, CrossfadePreferences.clampDurationMs(2_000L))
        assertEquals(1_000L, CrossfadePreferences.clampDurationMs(1_000L))
        assertEquals(12_000L, CrossfadePreferences.clampDurationMs(12_000L))
    }

    @Test
    fun `clamp raises below minimum`() {
        assertEquals(1_000L, CrossfadePreferences.clampDurationMs(500L))
        assertEquals(1_000L, CrossfadePreferences.clampDurationMs(0L))
        assertEquals(1_000L, CrossfadePreferences.clampDurationMs(-1_000L))
    }

    @Test
    fun `clamp lowers above maximum`() {
        assertEquals(12_000L, CrossfadePreferences.clampDurationMs(20_000L))
        assertEquals(12_000L, CrossfadePreferences.clampDurationMs(Long.MAX_VALUE))
    }
}
```

- [ ] **Step 3: Run tests**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
./gradlew :core:data:test --tests "com.stash.core.data.prefs.CrossfadePreferencesClampTest"
```

Expected: 3 tests pass.

- [ ] **Step 4: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
git add core/data/src/main/kotlin/com/stash/core/data/prefs/CrossfadePreferences.kt \
        core/data/src/test/kotlin/com/stash/core/data/prefs/CrossfadePreferencesClampTest.kt && \
git commit -m "feat(prefs): CrossfadePreferences (toggle + duration, default ON 2s, clamped 1-12s)"
```

---

## Task 2: Test seams — `CrossfadeScheduler` + `CrossfadeClock`

The auto-trigger uses `Handler.postDelayed`, and the volume ramp reads system time. Both are unmockable in plain JVM tests. Two tiny interfaces let production use the real Handler / clock and tests use a fake.

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/service/CrossfadeScheduler.kt`
- Create: `core/media/src/main/kotlin/com/stash/core/media/service/CrossfadeClock.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/service/FakeCrossfadeSchedulerTest.kt`

- [ ] **Step 1: Add mockk to `:core:media` test deps**

Edit `core/media/build.gradle.kts` and add to `dependencies { ... }` alongside the existing mockito-kotlin testImplementation:

```kotlin
testImplementation("io.mockk:mockk:1.13.8")
```

- [ ] **Step 2: Build to confirm**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && ./gradlew :core:media:test
```

Expected: BUILD SUCCESSFUL (no new tests yet).

- [ ] **Step 3: Write `CrossfadeScheduler`**

```kotlin
package com.stash.core.media.service

import android.os.Handler

/**
 * Scheduling seam over [Handler.postDelayed]. Production uses the
 * Handler-backed implementation; tests use a fake that exposes pending tasks.
 */
interface CrossfadeScheduler {
    /** Schedule [action] to run after [delayMs]. Returns a handle that cancels the run. */
    fun schedule(delayMs: Long, action: () -> Unit): Cancellable

    fun interface Cancellable { fun cancel() }
}

class HandlerCrossfadeScheduler(private val handler: Handler) : CrossfadeScheduler {
    override fun schedule(delayMs: Long, action: () -> Unit): CrossfadeScheduler.Cancellable {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMs)
        return CrossfadeScheduler.Cancellable { handler.removeCallbacks(runnable) }
    }
}
```

- [ ] **Step 4: Write `CrossfadeClock`**

```kotlin
package com.stash.core.media.service

/** Time source seam. Production reads `System.nanoTime()`; tests advance virtual time. */
fun interface CrossfadeClock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = CrossfadeClock { System.nanoTime() / 1_000_000 }
    }
}
```

- [ ] **Step 5: Write a smoke test for `FakeCrossfadeScheduler`**

A test-only fake the rest of the suite will reuse. Co-locate it in test sources:

```kotlin
package com.stash.core.media.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test fake exposed for use across the CrossfadingPlayerTest suite. */
class FakeCrossfadeScheduler : CrossfadeScheduler {
    data class Pending(val delayMs: Long, val action: () -> Unit, var cancelled: Boolean = false)

    private val pending = mutableListOf<Pending>()
    fun pendingTasks(): List<Pending> = pending.filter { !it.cancelled }
    fun runAll() { pending.filter { !it.cancelled }.forEach { it.action() }; pending.clear() }

    override fun schedule(delayMs: Long, action: () -> Unit): CrossfadeScheduler.Cancellable {
        val task = Pending(delayMs, action)
        pending.add(task)
        return CrossfadeScheduler.Cancellable { task.cancelled = true }
    }
}

class FakeCrossfadeSchedulerTest {

    @Test
    fun `schedules and runs pending tasks in order`() {
        val s = FakeCrossfadeScheduler()
        val log = mutableListOf<String>()
        s.schedule(100L) { log.add("a") }
        s.schedule(200L) { log.add("b") }
        assertEquals(2, s.pendingTasks().size)
        s.runAll()
        assertEquals(listOf("a", "b"), log)
    }

    @Test
    fun `cancel removes task from pending and prevents run`() {
        val s = FakeCrossfadeScheduler()
        val ran = mutableListOf<String>()
        val handle = s.schedule(100L) { ran.add("a") }
        handle.cancel()
        assertTrue(s.pendingTasks().isEmpty())
        s.runAll()
        assertTrue(ran.isEmpty())
    }
}
```

- [ ] **Step 6: Run tests**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
./gradlew :core:media:test --tests "com.stash.core.media.service.FakeCrossfadeSchedulerTest"
```

Expected: 2 tests pass.

- [ ] **Step 7: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
git add core/media/build.gradle.kts \
        core/media/src/main/kotlin/com/stash/core/media/service/CrossfadeScheduler.kt \
        core/media/src/main/kotlin/com/stash/core/media/service/CrossfadeClock.kt \
        core/media/src/test/kotlin/com/stash/core/media/service/FakeCrossfadeSchedulerTest.kt && \
git commit -m "feat(media): test seams (CrossfadeScheduler + CrossfadeClock) for CrossfadingPlayer"
```

---

## Task 3: `CrossfadingPlayer` skeleton — queue, reads, `CanonicalQueueTimeline`

Lays the foundation: `ForwardingPlayer` subclass, two underlying ExoPlayers, canonical `mediaItems` queue + `shuffleOrder`, read-side overrides. Synthesises a `Timeline` so external controllers see the full queue. No volume ramping yet — Task 4. No manual skip — Task 5. No mutations — Task 6.

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/service/CanonicalQueueTimeline.kt`
- Create: `core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/service/CrossfadingPlayerTest.kt` (will grow over later tasks)

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.stash.core.media.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.stash.core.data.prefs.CrossfadePreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadingPlayerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun `setMediaItems puts startIndex item on activePlayer and preloads next on nextPlayer at vol 0`() = runTest(testDispatcher) {
        val playerA = mockExo()
        val playerB = mockExo()
        val crossfade = build(playerA, playerB)

        val items = listOf(item("v1"), item("v2"), item("v3"))
        crossfade.setMediaItems(items, 0, 0L)
        advanceUntilIdle()

        verify { playerA.setMediaItem(items[0]) }
        verify { playerA.prepare() }
        verify { playerB.setMediaItem(items[1]) }
        verify { playerB.prepare() }
        verify { playerB.volume = 0f }
        verify(exactly = 0) { playerB.play() }
        assertEquals(0, crossfade.currentMediaItemIndex)
        assertEquals(3, crossfade.mediaItemCount)
    }

    @Test
    fun `setMediaItems with single item preloads nothing on nextPlayer`() = runTest(testDispatcher) {
        val playerA = mockExo()
        val playerB = mockExo()
        val crossfade = build(playerA, playerB)
        crossfade.setMediaItems(listOf(item("v1")), 0, 0L)
        advanceUntilIdle()
        verify(exactly = 0) { playerB.setMediaItem(any()) }
    }

    @Test
    fun `getMediaItemAt returns from canonical queue`() = runTest(testDispatcher) {
        val crossfade = build(mockExo(), mockExo())
        val items = listOf(item("v1"), item("v2"), item("v3"))
        crossfade.setMediaItems(items, 0, 0L)
        assertEquals("v1", crossfade.getMediaItemAt(0).mediaId)
        assertEquals("v2", crossfade.getMediaItemAt(1).mediaId)
        assertEquals("v3", crossfade.getMediaItemAt(2).mediaId)
    }

    @Test
    fun `currentMediaItem returns active player's item during SINGLE_ACTIVE`() = runTest(testDispatcher) {
        val playerA = mockExo().also { every { it.currentMediaItem } returns item("v1") }
        val crossfade = build(playerA, mockExo())
        crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
        assertEquals("v1", crossfade.currentMediaItem?.mediaId)
    }

    @Test
    fun `hasNextMediaItem true when queue has more items, false at end`() = runTest(testDispatcher) {
        val crossfade = build(mockExo(), mockExo())
        crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
        assertTrue(crossfade.hasNextMediaItem())

        val crossfade2 = build(mockExo(), mockExo())
        crossfade2.setMediaItems(listOf(item("v1")), 0, 0L)
        assertFalse(crossfade2.hasNextMediaItem())
    }

    @Test
    fun `getCurrentTimeline reflects the canonical queue not the active player's single-item timeline`() = runTest(testDispatcher) {
        val crossfade = build(mockExo(), mockExo())
        val items = listOf(item("v1"), item("v2"), item("v3"))
        crossfade.setMediaItems(items, 0, 0L)
        val timeline = crossfade.currentTimeline
        assertEquals(3, timeline.windowCount)
        val window = androidx.media3.common.Timeline.Window()
        timeline.getWindow(0, window)
        assertEquals("v1", window.mediaItem.mediaId)
        timeline.getWindow(1, window)
        assertEquals("v2", window.mediaItem.mediaId)
    }

    // --- helpers (used across all tasks) ----------------------------------

    private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    private fun mockExo(): ExoPlayer = mockk<ExoPlayer>(relaxed = true).also {
        every { it.playbackState } returns Player.STATE_READY
        every { it.isPlaying } returns false
        every { it.currentPosition } returns 0L
        every { it.duration } returns 180_000L
        every { it.currentMediaItem } returns null
        every { it.volume } returns 1.0f
        every { it.shuffleModeEnabled } returns false
        every { it.repeatMode } returns Player.REPEAT_MODE_OFF
    }

    private fun build(
        playerA: ExoPlayer,
        playerB: ExoPlayer,
        enabled: Boolean = true,
        durationMs: Long = 2_000L,
        scheduler: FakeCrossfadeScheduler = FakeCrossfadeScheduler(),
        clock: TestClock = TestClock(),
    ): CrossfadingPlayer {
        val prefs = mockk<CrossfadePreferences>(relaxed = true)
        every { prefs.enabled } returns MutableStateFlow(enabled)
        every { prefs.durationMs } returns MutableStateFlow(durationMs)
        return CrossfadingPlayer(playerA, playerB, prefs, testScope, scheduler, clock)
    }
}

class TestClock(var ms: Long = 0L) : CrossfadeClock {
    override fun nowMs(): Long = ms
    fun advance(by: Long) { ms += by }
}
```

- [ ] **Step 2: Write `CanonicalQueueTimeline`**

```kotlin
package com.stash.core.media.service

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline

/**
 * A Timeline whose windows mirror the wrapper's canonical queue. Each window
 * has unknown duration (`C.TIME_UNSET`) until the underlying player has
 * prepared the corresponding item — controllers handle this gracefully.
 *
 * One window per media item; one period per window (the simplest valid shape
 * for a non-streaming, non-ad timeline).
 */
class CanonicalQueueTimeline(private val items: List<MediaItem>) : Timeline() {

    override fun getWindowCount(): Int = items.size

    override fun getWindow(
        windowIndex: Int,
        window: Window,
        defaultPositionProjectionUs: Long,
    ): Window {
        val item = items[windowIndex]
        window.set(
            /* uid = */ item.mediaId,
            /* mediaItem = */ item,
            /* manifest = */ null,
            /* presentationStartTimeMs = */ C.TIME_UNSET,
            /* windowStartTimeMs = */ C.TIME_UNSET,
            /* elapsedRealtimeEpochOffsetMs = */ C.TIME_UNSET,
            /* isSeekable = */ true,
            /* isDynamic = */ false,
            /* liveConfiguration = */ null,
            /* defaultPositionUs = */ 0L,
            /* durationUs = */ C.TIME_UNSET,
            /* firstPeriodIndex = */ windowIndex,
            /* lastPeriodIndex = */ windowIndex,
            /* positionInFirstPeriodUs = */ 0L,
        )
        return window
    }

    override fun getPeriodCount(): Int = items.size

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        val item = items[periodIndex]
        period.set(
            /* id = */ if (setIds) item.mediaId else null,
            /* uid = */ if (setIds) item.mediaId else null,
            /* windowIndex = */ periodIndex,
            /* durationUs = */ C.TIME_UNSET,
            /* positionInWindowUs = */ 0L,
        )
        return period
    }

    override fun getIndexOfPeriod(uid: Any): Int {
        val s = uid as? String ?: return C.INDEX_UNSET
        val idx = items.indexOfFirst { it.mediaId == s }
        return if (idx < 0) C.INDEX_UNSET else idx
    }

    override fun getUidOfPeriod(periodIndex: Int): Any = items[periodIndex].mediaId
}
```

- [ ] **Step 3: Write the `CrossfadingPlayer` skeleton**

```kotlin
package com.stash.core.media.service

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import com.stash.core.data.prefs.CrossfadePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Wrapper [Player] that crossfades between two [ExoPlayer] instances.
 *
 * Owns the canonical queue. Each underlying player holds exactly one
 * [MediaItem] at a time. The [MediaSession] holds this wrapper as its single
 * Player.
 *
 * See `docs/superpowers/specs/2026-05-02-crossfader-design.md`.
 */
class CrossfadingPlayer(
    private val playerA: ExoPlayer,
    private val playerB: ExoPlayer,
    private val crossfadePreferences: CrossfadePreferences,
    private val scope: CoroutineScope,
    private val scheduler: CrossfadeScheduler,
    private val clock: CrossfadeClock,
) : ForwardingPlayer(playerA) {

    enum class State { SINGLE_ACTIVE, CROSSFADING }

    private val activeRef = AtomicReference<ExoPlayer>(playerA)
    private val nextRef = AtomicReference<ExoPlayer>(playerB)

    private val mediaItems = mutableListOf<MediaItem>()
    private var currentIndex: Int = 0
    private var shuffleOrder: IntArray = IntArray(0)

    @Volatile private var currentEnabled: Boolean = CrossfadePreferences.DEFAULT_ENABLED
    @Volatile private var currentDurationMs: Long = CrossfadePreferences.DEFAULT_DURATION_MS

    @Volatile private var stateValue: State = State.SINGLE_ACTIVE
    val state: State get() = stateValue

    /** User's master volume multiplier — applied to both players' ramp output. */
    @Volatile private var userVolume: Float = 1.0f

    init {
        scope.launch { crossfadePreferences.enabled.collectLatest { currentEnabled = it } }
        scope.launch { crossfadePreferences.durationMs.collectLatest { currentDurationMs = it } }
    }

    fun activePlayer(): ExoPlayer = activeRef.get()
    fun nextPlayer(): ExoPlayer = nextRef.get()

    // ---- Queue management ------------------------------------------------

    override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        mediaItems.clear()
        mediaItems.addAll(items)
        currentIndex = startIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0))
        rebuildShuffleOrder()

        if (items.isEmpty()) return

        activePlayer().setMediaItem(items[currentIndex])
        activePlayer().prepare()
        activePlayer().seekTo(startPositionMs)
        preloadNext()
    }

    override fun setMediaItems(items: List<MediaItem>) = setMediaItems(items, 0, 0L)

    private fun resolveNextIndex(): Int? {
        if (mediaItems.isEmpty()) return null
        val pos = shuffleOrder.indexOf(currentIndex)
        if (pos < 0) return null
        val nextPos = pos + 1
        if (nextPos >= shuffleOrder.size) {
            return if (playerA.repeatMode == Player.REPEAT_MODE_ALL) shuffleOrder[0] else null
        }
        return shuffleOrder[nextPos]
    }

    private fun preloadNext() {
        val nextIdx = resolveNextIndex() ?: return
        nextPlayer().setMediaItem(mediaItems[nextIdx])
        nextPlayer().prepare()
        nextPlayer().volume = 0f
    }

    private fun rebuildShuffleOrder() {
        shuffleOrder = if (playerA.shuffleModeEnabled) {
            (0 until mediaItems.size).toList().shuffled().toIntArray()
        } else {
            IntArray(mediaItems.size) { it }
        }
    }

    // ---- Read-side overrides --------------------------------------------

    override fun getMediaItemCount(): Int = mediaItems.size
    override fun getMediaItemAt(index: Int): MediaItem = mediaItems[index]

    override fun getCurrentMediaItemIndex(): Int = when (stateValue) {
        State.SINGLE_ACTIVE -> currentIndex
        State.CROSSFADING -> resolveNextIndex() ?: currentIndex
    }

    override fun hasNextMediaItem(): Boolean = resolveNextIndex() != null

    override fun hasPreviousMediaItem(): Boolean = currentIndex > 0

    override fun getCurrentMediaItem(): MediaItem? = when (stateValue) {
        State.SINGLE_ACTIVE -> activePlayer().currentMediaItem
        State.CROSSFADING -> nextPlayer().currentMediaItem
    }

    override fun getCurrentPosition(): Long = when (stateValue) {
        State.SINGLE_ACTIVE -> activePlayer().currentPosition
        State.CROSSFADING -> nextPlayer().currentPosition
    }

    override fun getDuration(): Long = when (stateValue) {
        State.SINGLE_ACTIVE -> activePlayer().duration
        State.CROSSFADING -> nextPlayer().duration
    }

    override fun getBufferedPosition(): Long = when (stateValue) {
        State.SINGLE_ACTIVE -> activePlayer().bufferedPosition
        State.CROSSFADING -> nextPlayer().bufferedPosition
    }

    override fun getContentPosition(): Long = when (stateValue) {
        State.SINGLE_ACTIVE -> activePlayer().contentPosition
        State.CROSSFADING -> nextPlayer().contentPosition
    }

    override fun getContentDuration(): Long = when (stateValue) {
        State.SINGLE_ACTIVE -> activePlayer().contentDuration
        State.CROSSFADING -> nextPlayer().contentDuration
    }

    override fun isPlaying(): Boolean = activePlayer().isPlaying || nextPlayer().isPlaying

    override fun getPlaybackState(): Int = when (stateValue) {
        State.SINGLE_ACTIVE -> activePlayer().playbackState
        State.CROSSFADING -> nextPlayer().playbackState
    }

    override fun getCurrentTimeline(): Timeline = CanonicalQueueTimeline(mediaItems.toList())

    // ---- Play / pause / stop --------------------------------------------

    override fun play() {
        when (stateValue) {
            State.SINGLE_ACTIVE -> activePlayer().play()
            State.CROSSFADING -> { activePlayer().play(); nextPlayer().play() }
        }
    }

    override fun pause() {
        when (stateValue) {
            State.SINGLE_ACTIVE -> activePlayer().pause()
            State.CROSSFADING -> { activePlayer().pause(); nextPlayer().pause() }
        }
    }

    override fun stop() {
        playerA.stop()
        playerB.stop()
        stateValue = State.SINGLE_ACTIVE
    }

    override fun release() {
        playerA.release()
        playerB.release()
        super.release()
    }

    override fun setVolume(volume: Float) {
        userVolume = volume.coerceIn(0f, 1f)
        // During SINGLE_ACTIVE: apply directly to active. During CROSSFADING:
        // ramp tick re-applies the multiplier on each frame.
        if (stateValue == State.SINGLE_ACTIVE) {
            activePlayer().volume = userVolume
        }
    }

    override fun getVolume(): Float = userVolume
}
```

- [ ] **Step 4: Run tests**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
./gradlew :core:media:test --tests "com.stash.core.media.service.CrossfadingPlayerTest"
```

Expected: 6 tests pass.

- [ ] **Step 5: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
git add core/media/src/main/kotlin/com/stash/core/media/service/CanonicalQueueTimeline.kt \
        core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt \
        core/media/src/test/kotlin/com/stash/core/media/service/CrossfadingPlayerTest.kt && \
git commit -m "feat(media): CrossfadingPlayer skeleton (queue + reads + CanonicalQueueTimeline)"
```

---

## Task 4: Auto-trigger + equal-power volume ramp + state machine

Adds the position-aware scheduling, the `SINGLE_ACTIVE` ↔ `CROSSFADING` transitions, and the cosine ramp.

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt`
- Modify: `core/media/src/test/kotlin/com/stash/core/media/service/CrossfadingPlayerTest.kt`

- [ ] **Step 1: Add failing tests for the ramp + auto-trigger**

Append to `CrossfadingPlayerTest.kt`:

```kotlin
@Test
fun `equal-power curve at midpoint both volumes ~0707`() {
    assertEquals(0.7071f, CrossfadingPlayer.activeVolumeAtFraction(0.5f), 0.001f)
    assertEquals(0.7071f, CrossfadingPlayer.nextVolumeAtFraction(0.5f), 0.001f)
}

@Test
fun `equal-power curve endpoints`() {
    assertEquals(1.0f, CrossfadingPlayer.activeVolumeAtFraction(0.0f), 0.001f)
    assertEquals(0.0f, CrossfadingPlayer.activeVolumeAtFraction(1.0f), 0.001f)
    assertEquals(0.0f, CrossfadingPlayer.nextVolumeAtFraction(0.0f), 0.001f)
    assertEquals(1.0f, CrossfadingPlayer.nextVolumeAtFraction(1.0f), 0.001f)
}

@Test
fun `auto-trigger schedules at duration - position - crossfadeMs`() = runTest(testDispatcher) {
    val playerA = mockExo()
    val playerB = mockExo()
    every { playerA.duration } returns 60_000L
    every { playerA.currentPosition } returns 10_000L
    every { playerA.isPlaying } returns true
    val sched = FakeCrossfadeScheduler()
    val crossfade = build(playerA, playerB, enabled = true, durationMs = 2_000L, scheduler = sched)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()
    advanceUntilIdle()

    // 60s - 10s - 2s = 48s
    val task = sched.pendingTasks().single()
    assertEquals(48_000L, task.delayMs)
}

@Test
fun `auto-trigger schedules nothing when crossfade disabled`() = runTest(testDispatcher) {
    val sched = FakeCrossfadeScheduler()
    val crossfade = build(mockExo(), mockExo(), enabled = false, scheduler = sched)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()
    advanceUntilIdle()
    assertTrue(sched.pendingTasks().isEmpty())
}

@Test
fun `auto-trigger schedules nothing when track shorter than 2x crossfadeMs`() = runTest(testDispatcher) {
    val playerA = mockExo()
    every { playerA.duration } returns 3_000L  // < 2 * 2000
    every { playerA.isPlaying } returns true
    val sched = FakeCrossfadeScheduler()
    val crossfade = build(playerA, mockExo(), enabled = true, durationMs = 2_000L, scheduler = sched)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()
    advanceUntilIdle()
    assertTrue(sched.pendingTasks().isEmpty())
}

@Test
fun `auto-trigger schedules nothing when repeatMode is ONE`() = runTest(testDispatcher) {
    val playerA = mockExo()
    every { playerA.duration } returns 60_000L
    every { playerA.isPlaying } returns true
    every { playerA.repeatMode } returns Player.REPEAT_MODE_ONE
    val sched = FakeCrossfadeScheduler()
    val crossfade = build(playerA, mockExo(), enabled = true, scheduler = sched)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()
    advanceUntilIdle()
    assertTrue(sched.pendingTasks().isEmpty())
}

@Test
fun `auto-trigger fired enters CROSSFADING and starts incoming player`() = runTest(testDispatcher) {
    val playerA = mockExo()
    val playerB = mockExo()
    every { playerA.duration } returns 60_000L
    every { playerA.isPlaying } returns true
    every { playerB.currentMediaItem } returns item("v2")
    val sched = FakeCrossfadeScheduler()
    val clock = TestClock()
    val crossfade = build(playerA, playerB, enabled = true, durationMs = 2_000L, scheduler = sched, clock = clock)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()
    advanceUntilIdle()

    sched.runAll()  // fire the scheduled trigger
    advanceUntilIdle()

    verify { playerB.play() }
    assertEquals(CrossfadingPlayer.State.CROSSFADING, crossfade.state)
}

@Test
fun `during CROSSFADING currentMediaItem returns next player's item`() = runTest(testDispatcher) {
    val playerA = mockExo()
    val playerB = mockExo()
    every { playerA.duration } returns 60_000L
    every { playerA.isPlaying } returns true
    every { playerB.currentMediaItem } returns item("v2")
    val sched = FakeCrossfadeScheduler()
    val crossfade = build(playerA, playerB, enabled = true, scheduler = sched)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()
    advanceUntilIdle()
    sched.runAll()
    advanceUntilIdle()

    assertEquals("v2", crossfade.currentMediaItem?.mediaId)
}

@Test
fun `seekTo during CROSSFADING aborts fade and seeks the incoming track`() = runTest(testDispatcher) {
    val playerA = mockExo()
    val playerB = mockExo()
    every { playerA.duration } returns 60_000L
    every { playerA.isPlaying } returns true
    val sched = FakeCrossfadeScheduler()
    val crossfade = build(playerA, playerB, enabled = true, scheduler = sched)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()
    advanceUntilIdle()
    sched.runAll()
    advanceUntilIdle()
    assertEquals(CrossfadingPlayer.State.CROSSFADING, crossfade.state)

    crossfade.seekTo(30_000L)
    advanceUntilIdle()

    verify { playerB.seekTo(30_000L) }
    verify(exactly = 0) { playerA.seekTo(30_000L) }
    verify { playerA.volume = 0f }
    verify { playerB.volume = 1f }
    assertEquals(CrossfadingPlayer.State.SINGLE_ACTIVE, crossfade.state)
}

@Test
fun `play and pause during CROSSFADING affect both players`() = runTest(testDispatcher) {
    val playerA = mockExo()
    val playerB = mockExo()
    every { playerA.duration } returns 60_000L
    every { playerA.isPlaying } returns true
    val sched = FakeCrossfadeScheduler()
    val crossfade = build(playerA, playerB, enabled = true, scheduler = sched)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()
    advanceUntilIdle()
    sched.runAll()
    advanceUntilIdle()

    crossfade.pause()
    verify { playerA.pause() }
    verify { playerB.pause() }
}
```

- [ ] **Step 2: Implement the volume curve, scheduling, and ramp**

Add to `CrossfadingPlayer.kt`:

```kotlin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

companion object {
    fun activeVolumeAtFraction(t: Float): Float = cos(t.coerceIn(0f, 1f) * Math.PI.toFloat() / 2f)
    fun nextVolumeAtFraction(t: Float): Float = sin(t.coerceIn(0f, 1f) * Math.PI.toFloat() / 2f)

    private const val RAMP_TICK_MS = 50L
}

private var triggerHandle: CrossfadeScheduler.Cancellable? = null
private var rampJob: Job? = null

/** Cancel any in-flight schedule + ramp. Called on every state change that invalidates them. */
private fun cancelTriggerAndRamp() {
    triggerHandle?.cancel(); triggerHandle = null
    rampJob?.cancel(); rampJob = null
}

/** Compute when the auto-trigger should fire and schedule it. Idempotent. */
private fun scheduleAutoTrigger() {
    triggerHandle?.cancel(); triggerHandle = null

    if (!currentEnabled) return
    if (stateValue != State.SINGLE_ACTIVE) return
    if (resolveNextIndex() == null) return
    if (playerA.repeatMode == Player.REPEAT_MODE_ONE) return

    val active = activePlayer()
    if (!active.isPlaying) return
    val duration = active.duration
    if (duration <= 0L) return
    val crossfadeMs = currentDurationMs
    if (duration < crossfadeMs * 2) return

    val delay = (duration - active.currentPosition - crossfadeMs).coerceAtLeast(0L)
    triggerHandle = scheduler.schedule(delay) { startCrossfade() }
}

/** Begin the equal-power overlap. Caller must already be on SINGLE_ACTIVE. */
private fun startCrossfade() {
    if (stateValue != State.SINGLE_ACTIVE) return
    if (nextPlayer().currentMediaItem == null) return  // nothing preloaded

    val outgoing = activePlayer()
    val incoming = nextPlayer()
    val crossfadeMs = currentDurationMs

    stateValue = State.CROSSFADING
    incoming.volume = 0f
    incoming.play()

    rampJob?.cancel()
    rampJob = scope.launch {
        // Drive `t` by tick count rather than wall-clock so virtual-time
        // tests (`runTest` + `StandardTestDispatcher`) progress the ramp via
        // `delay()` advancement. The `clock` parameter is reserved for any
        // future reschedule-on-resume scenario.
        val totalTicks = (crossfadeMs / RAMP_TICK_MS).coerceAtLeast(1L)
        var ticks = 0L
        while (true) {
            val t = (ticks.toFloat() / totalTicks).coerceIn(0f, 1f)
            outgoing.volume = userVolume * activeVolumeAtFraction(t)
            incoming.volume = userVolume * nextVolumeAtFraction(t)
            if (t >= 1f) break
            delay(RAMP_TICK_MS)
            ticks += 1
        }
        completeCrossfade(outgoing, incoming)
    }
}

private fun completeCrossfade(outgoing: ExoPlayer, incoming: ExoPlayer) {
    val nextIdx = resolveNextIndex() ?: run {
        // Defensive: queue mutated mid-fade. Snap to single-active on whoever is still healthy.
        stateValue = State.SINGLE_ACTIVE
        return
    }
    activeRef.set(incoming)
    nextRef.set(outgoing)
    outgoing.stop()
    outgoing.clearMediaItems()
    currentIndex = nextIdx
    stateValue = State.SINGLE_ACTIVE
    preloadNext()
    scheduleAutoTrigger()
}

// Override seekTo to handle the in-fade case
override fun seekTo(positionMs: Long) {
    when (stateValue) {
        State.SINGLE_ACTIVE -> {
            activePlayer().seekTo(positionMs)
            // Re-arm: the auto-trigger's scheduled delay is now stale.
            scheduleAutoTrigger()
        }
        State.CROSSFADING -> {
            // Abort the fade; land cleanly on the incoming track at the new position.
            rampJob?.cancel()
            val outgoing = activePlayer()
            val incoming = nextPlayer()
            outgoing.volume = 0f
            incoming.volume = 1f
            incoming.seekTo(positionMs)
            val nextIdx = resolveNextIndex() ?: currentIndex
            outgoing.stop()
            outgoing.clearMediaItems()
            activeRef.set(incoming); nextRef.set(outgoing)
            currentIndex = nextIdx
            stateValue = State.SINGLE_ACTIVE
            preloadNext()
            scheduleAutoTrigger()
        }
    }
}
```

Then update `setMediaItems` and `play` to call `scheduleAutoTrigger()` after their existing work, and update `pause`/`stop` to call `cancelTriggerAndRamp()`. Make sure the prefs collectors also call `scheduleAutoTrigger()` after updating cached values (so a duration change re-arms).

- [ ] **Step 3: Run tests**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
./gradlew :core:media:test --tests "com.stash.core.media.service.CrossfadingPlayerTest"
```

Expected: all tests pass (6 from Task 3 + 9 new = 15).

- [ ] **Step 4: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
git add core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt \
        core/media/src/test/kotlin/com/stash/core/media/service/CrossfadingPlayerTest.kt && \
git commit -m "feat(media): equal-power volume ramp + scheduled auto-trigger + seekTo abort"
```

---

## Task 5: Manual skip — `seekToNext` / `seekToPrevious` + rebound

User-driven transitions, with the rebound case (skip during in-flight fade).

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt`
- Modify: `core/media/src/test/kotlin/com/stash/core/media/service/CrossfadingPlayerTest.kt`

- [ ] **Step 1: Add failing tests**

```kotlin
@Test
fun `seekToNext during SINGLE_ACTIVE starts crossfade to next item`() = runTest(testDispatcher) {
    val playerA = mockExo()
    val playerB = mockExo()
    every { playerA.isPlaying } returns true
    val sched = FakeCrossfadeScheduler()
    val crossfade = build(playerA, playerB, enabled = true, scheduler = sched)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()
    advanceUntilIdle()

    crossfade.seekToNext()
    advanceUntilIdle()

    verify { playerB.play() }
    assertEquals(CrossfadingPlayer.State.CROSSFADING, crossfade.state)
}

@Test
fun `seekToPrevious during SINGLE_ACTIVE starts crossfade to previous item`() = runTest(testDispatcher) {
    val playerA = mockExo()
    val playerB = mockExo()
    every { playerA.isPlaying } returns true
    val sched = FakeCrossfadeScheduler()
    val crossfade = build(playerA, playerB, enabled = true, scheduler = sched)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 1, 0L)  // start on v2
    crossfade.play()
    advanceUntilIdle()

    crossfade.seekToPrevious()
    advanceUntilIdle()

    verify { playerB.setMediaItem(item("v1")) }
    verify { playerB.play() }
    assertEquals(CrossfadingPlayer.State.CROSSFADING, crossfade.state)
}

@Test
fun `seekToNext when crossfade disabled does instant cut`() = runTest(testDispatcher) {
    val playerA = mockExo()
    val playerB = mockExo()
    val crossfade = build(playerA, playerB, enabled = false)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()

    crossfade.seekToNext()
    advanceUntilIdle()

    verify { playerA.stop() }
    verify { playerB.volume = 1f }
    verify { playerB.play() }
    assertEquals(CrossfadingPlayer.State.SINGLE_ACTIVE, crossfade.state)
    assertEquals(1, crossfade.currentMediaItemIndex)
}

@Test
fun `rebound skip during crossfade swaps to next-next item`() = runTest(testDispatcher) {
    val playerA = mockExo()
    val playerB = mockExo()
    every { playerA.isPlaying } returns true
    val sched = FakeCrossfadeScheduler()
    val crossfade = build(playerA, playerB, enabled = true, scheduler = sched)
    crossfade.setMediaItems(listOf(item("v1"), item("v2"), item("v3")), 0, 0L)
    crossfade.play()
    advanceUntilIdle()

    // Skip 1 → starts fade A(v1) → B(v2)
    crossfade.seekToNext()
    advanceUntilIdle()
    assertEquals(CrossfadingPlayer.State.CROSSFADING, crossfade.state)

    // Skip 2 (rebound) → v3 should load on whichever player is freed
    crossfade.seekToNext()
    advanceUntilIdle()

    verify { playerA.setMediaItem(item("v3")) }
}
```

- [ ] **Step 2: Implement the skip overrides**

```kotlin
override fun seekToNext() {
    val nextIdx = resolveNextIndex() ?: return
    when (stateValue) {
        State.SINGLE_ACTIVE -> startManualTransitionTo(nextIdx)
        State.CROSSFADING -> rebound(forwards = true)
    }
}

override fun seekToPrevious() {
    val prevIdx = resolvePrevIndex() ?: return
    when (stateValue) {
        State.SINGLE_ACTIVE -> startManualTransitionTo(prevIdx)
        State.CROSSFADING -> rebound(forwards = false)
    }
}

private fun resolvePrevIndex(): Int? {
    if (mediaItems.isEmpty()) return null
    val pos = shuffleOrder.indexOf(currentIndex)
    if (pos <= 0) {
        return if (playerA.repeatMode == Player.REPEAT_MODE_ALL) shuffleOrder.last() else null
    }
    return shuffleOrder[pos - 1]
}

/** Manual transition from SINGLE_ACTIVE: fade to target if enabled, otherwise instant-cut. */
private fun startManualTransitionTo(targetIndex: Int) {
    val target = mediaItems[targetIndex]
    val incoming = nextPlayer()
    val outgoing = activePlayer()

    incoming.setMediaItem(target)
    incoming.prepare()

    if (!currentEnabled) {
        // Instant cut.
        outgoing.stop()
        outgoing.clearMediaItems()
        incoming.volume = userVolume
        incoming.play()
        activeRef.set(incoming); nextRef.set(outgoing)
        currentIndex = targetIndex
        stateValue = State.SINGLE_ACTIVE
        preloadNext()
        scheduleAutoTrigger()
        return
    }

    // Fade — same machinery as auto-trigger, but the next-target is fixed by the caller
    // rather than resolved via §5. We store a one-shot override of completeCrossfade's
    // resolution by setting currentIndex to (targetIndex - 1)... no, we'd lose shuffle info.
    // Cleaner: prepare the incoming, then run the same ramp; in completeCrossfade,
    // override the index resolution by directly setting currentIndex = targetIndex.
    pendingManualTarget = targetIndex
    startCrossfade()
}

@Volatile private var pendingManualTarget: Int? = null

// Update completeCrossfade to consult pendingManualTarget first:
private fun completeCrossfade(outgoing: ExoPlayer, incoming: ExoPlayer) {
    val nextIdx = pendingManualTarget ?: resolveNextIndex() ?: run {
        stateValue = State.SINGLE_ACTIVE
        return
    }
    pendingManualTarget = null
    activeRef.set(incoming)
    nextRef.set(outgoing)
    outgoing.stop()
    outgoing.clearMediaItems()
    currentIndex = nextIdx
    stateValue = State.SINGLE_ACTIVE
    preloadNext()
    scheduleAutoTrigger()
}

/** Skip during in-flight fade: snap previously-incoming to active, fade to new target. */
private fun rebound(forwards: Boolean) {
    rampJob?.cancel()
    val previouslyOutgoing = activePlayer()
    val previouslyIncoming = nextPlayer()
    previouslyOutgoing.stop()
    previouslyOutgoing.clearMediaItems()
    previouslyIncoming.volume = userVolume
    activeRef.set(previouslyIncoming); nextRef.set(previouslyOutgoing)
    // Synchronously bump currentIndex to the previously-incoming's index — spec §2.
    currentIndex = pendingManualTarget ?: resolveNextIndex() ?: currentIndex
    pendingManualTarget = null
    stateValue = State.SINGLE_ACTIVE

    val newTarget = if (forwards) resolveNextIndex() else resolvePrevIndex()
    if (newTarget == null) {
        preloadNext(); scheduleAutoTrigger(); return
    }
    startManualTransitionTo(newTarget)
}
```

- [ ] **Step 3: Run tests**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
./gradlew :core:media:test --tests "com.stash.core.media.service.CrossfadingPlayerTest"
```

Expected: 4 new tests pass + all previous still pass.

- [ ] **Step 4: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
git add core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt \
        core/media/src/test/kotlin/com/stash/core/media/service/CrossfadingPlayerTest.kt && \
git commit -m "feat(media): manual skip (next/prev) + rebound on skip-during-fade"
```

---

## Task 6: Queue mutations + shuffle/repeat sync

Implements `addMediaItem`, `removeMediaItem`, `moveMediaItem`, `replaceMediaItem`, `clearMediaItems`, `setShuffleModeEnabled`, `setRepeatMode`. These are called from `PlayerRepositoryImpl` for "Play next" / "Add to queue" / queue reorder / queue removal flows.

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt`
- Modify: `core/media/src/test/kotlin/com/stash/core/media/service/CrossfadingPlayerTest.kt`

- [ ] **Step 1: Add failing tests**

```kotlin
@Test
fun `addMediaItem appends to canonical queue and updates next-preload if needed`() = runTest(testDispatcher) {
    val playerA = mockExo(); val playerB = mockExo()
    val crossfade = build(playerA, playerB)
    crossfade.setMediaItems(listOf(item("v1")), 0, 0L)  // single item, no next preload
    advanceUntilIdle()

    crossfade.addMediaItem(item("v2"))
    advanceUntilIdle()

    assertEquals(2, crossfade.mediaItemCount)
    verify { playerB.setMediaItem(item("v2")) }  // newly preloaded
}

@Test
fun `removeMediaItem at currentIndex forces a manual skip`() = runTest(testDispatcher) {
    val playerA = mockExo(); val playerB = mockExo()
    every { playerA.isPlaying } returns true
    val crossfade = build(playerA, playerB, enabled = false)  // disabled = instant cut
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()
    advanceUntilIdle()

    crossfade.removeMediaItem(0)  // remove currently-playing
    advanceUntilIdle()

    // Should have transitioned to v2 via instant cut
    verify { playerB.play() }
    assertEquals(1, crossfade.mediaItemCount)
}

@Test
fun `moveMediaItem rebuilds shuffleOrder and re-resolves preload`() = runTest(testDispatcher) {
    val playerA = mockExo(); val playerB = mockExo()
    val crossfade = build(playerA, playerB)
    crossfade.setMediaItems(listOf(item("v1"), item("v2"), item("v3")), 0, 0L)
    advanceUntilIdle()

    // Move v3 to index 1 → next preload should change from v2 to v3
    crossfade.moveMediaItem(2, 1)
    advanceUntilIdle()

    verify { playerB.setMediaItem(item("v3")) }
}

@Test
fun `clearMediaItems stops both players and empties queue`() = runTest(testDispatcher) {
    val playerA = mockExo(); val playerB = mockExo()
    val crossfade = build(playerA, playerB)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)

    crossfade.clearMediaItems()
    advanceUntilIdle()

    verify { playerA.stop() }
    verify { playerB.stop() }
    assertEquals(0, crossfade.mediaItemCount)
}

@Test
fun `setShuffleModeEnabled rebuilds shuffleOrder and propagates to both players`() = runTest(testDispatcher) {
    val playerA = mockExo(); val playerB = mockExo()
    val crossfade = build(playerA, playerB)
    crossfade.setMediaItems(listOf(item("v1"), item("v2"), item("v3")), 0, 0L)

    crossfade.shuffleModeEnabled = true
    advanceUntilIdle()

    verify { playerA.shuffleModeEnabled = true }
    verify { playerB.shuffleModeEnabled = true }
    // Shuffle order is randomized — just assert it's a valid permutation
}
```

- [ ] **Step 2: Implement the mutations**

```kotlin
override fun addMediaItem(mediaItem: MediaItem) {
    mediaItems.add(mediaItem)
    rebuildShuffleOrder()
    refreshNextPreload()
}

override fun addMediaItem(index: Int, mediaItem: MediaItem) {
    mediaItems.add(index.coerceIn(0, mediaItems.size), mediaItem)
    if (index <= currentIndex) currentIndex += 1
    rebuildShuffleOrder()
    refreshNextPreload()
}

override fun addMediaItems(mediaItems: List<MediaItem>) {
    mediaItems.forEach { addMediaItem(it) }
}

override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
    mediaItems.forEachIndexed { i, item -> addMediaItem(index + i, item) }
}

override fun removeMediaItem(index: Int) {
    if (index !in mediaItems.indices) return
    val wasCurrent = (index == currentIndex)
    mediaItems.removeAt(index)
    rebuildShuffleOrder()

    if (mediaItems.isEmpty()) {
        cancelTriggerAndRamp()
        playerA.stop(); playerA.clearMediaItems()
        playerB.stop(); playerB.clearMediaItems()
        currentIndex = 0
        stateValue = State.SINGLE_ACTIVE
        return
    }

    when {
        wasCurrent -> {
            // Force-skip to whatever's now at the slot (via the existing manual-skip path).
            currentIndex = currentIndex.coerceAtMost(mediaItems.lastIndex)
            startManualTransitionTo(currentIndex)
        }
        index < currentIndex -> {
            currentIndex -= 1
            refreshNextPreload()
        }
        else -> refreshNextPreload()
    }
}

override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
    val from = fromIndex.coerceAtLeast(0)
    val to = toIndex.coerceAtMost(mediaItems.size)
    for (i in (to - 1) downTo from) removeMediaItem(i)
}

override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
    if (currentIndex !in mediaItems.indices) return
    val target = newIndex.coerceIn(0, mediaItems.lastIndex)
    val item = mediaItems.removeAt(currentIndex)
    mediaItems.add(target, item)
    // Adjust this.currentIndex
    when {
        currentIndex == this.currentIndex -> this.currentIndex = target
        currentIndex < this.currentIndex && target >= this.currentIndex -> this.currentIndex -= 1
        currentIndex > this.currentIndex && target <= this.currentIndex -> this.currentIndex += 1
    }
    rebuildShuffleOrder()
    refreshNextPreload()
}

override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {
    val items = mediaItems.subList(fromIndex, toIndex).toList()
    for (i in (toIndex - 1) downTo fromIndex) mediaItems.removeAt(i)
    mediaItems.addAll(newIndex.coerceAtMost(mediaItems.size), items)
    rebuildShuffleOrder()
    refreshNextPreload()
}

override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
    if (index !in mediaItems.indices) return
    mediaItems[index] = mediaItem
    if (index == currentIndex) {
        // Force-play the replacement at currentIndex via manual-skip semantics.
        startManualTransitionTo(currentIndex)
    } else {
        refreshNextPreload()
    }
}

override fun clearMediaItems() {
    cancelTriggerAndRamp()
    mediaItems.clear()
    shuffleOrder = IntArray(0)
    currentIndex = 0
    playerA.stop(); playerA.clearMediaItems()
    playerB.stop(); playerB.clearMediaItems()
    stateValue = State.SINGLE_ACTIVE
}

/** Recompute what should be on `nextPlayer` and update if it changed. */
private fun refreshNextPreload() {
    val nextIdx = resolveNextIndex()
    if (nextIdx == null) {
        nextPlayer().clearMediaItems()
        return
    }
    val target = mediaItems[nextIdx]
    val current = nextPlayer().currentMediaItem
    if (current?.mediaId != target.mediaId) {
        nextPlayer().setMediaItem(target)
        nextPlayer().prepare()
        nextPlayer().volume = 0f
    }
    scheduleAutoTrigger()
}

override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
    playerA.shuffleModeEnabled = shuffleModeEnabled
    playerB.shuffleModeEnabled = shuffleModeEnabled
    rebuildShuffleOrder()
    refreshNextPreload()
}

override fun setRepeatMode(repeatMode: Int) {
    playerA.repeatMode = repeatMode
    playerB.repeatMode = repeatMode
    refreshNextPreload()
}
```

- [ ] **Step 3: Run tests**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
./gradlew :core:media:test --tests "com.stash.core.media.service.CrossfadingPlayerTest"
```

Expected: 5 new tests pass + all previous still pass.

- [ ] **Step 4: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
git add core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt \
        core/media/src/test/kotlin/com/stash/core/media/service/CrossfadingPlayerTest.kt && \
git commit -m "feat(media): queue mutations + shuffle/repeat sync (CanonicalQueueTimeline-driven)"
```

---

## Task 7: Audio focus propagation + error handling + listener event forwarding

Three pieces of `Player.Listener` plumbing:
1. Propagate focus-driven pause from playerA to playerB
2. `onPlayerError` aborts the fade and falls back to the healthy player
3. **Synthesise `onMediaItemTransition` at the role-swap moment** so external observers (notification, lockscreen) update track metadata when the fade ends — without this, `MediaSession` shows the old track until the user interacts.

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt`
- Modify: `core/media/src/test/kotlin/com/stash/core/media/service/CrossfadingPlayerTest.kt`

- [ ] **Step 1: Add failing tests**

```kotlin
@Test
fun `setVolume during fade propagates user multiplier to both players' ramp output`() = runTest(testDispatcher) {
    val playerA = mockExo(); val playerB = mockExo()
    every { playerA.duration } returns 60_000L
    every { playerA.isPlaying } returns true
    val sched = FakeCrossfadeScheduler()
    val crossfade = build(playerA, playerB, enabled = true, durationMs = 200L, scheduler = sched)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()
    advanceUntilIdle()
    sched.runAll()
    advanceUntilIdle()  // enters CROSSFADING

    crossfade.volume = 0.5f
    advanceTimeBy(60L)
    advanceUntilIdle()

    // After the user's 0.5x master gain applies, both players' volumes are scaled by 0.5
    // (verified by intercepting any volume write in the second half of the fade).
    verify(atLeast = 1) { playerA.volume = match { it >= 0f && it <= 0.5001f } }
    verify(atLeast = 1) { playerB.volume = match { it >= 0f && it <= 0.5001f } }
}

@Test
fun `fade complete synthesises onMediaItemTransition with REASON_AUTO`() = runTest(testDispatcher) {
    val playerA = mockExo(); val playerB = mockExo()
    every { playerA.duration } returns 60_000L
    every { playerA.isPlaying } returns true
    every { playerB.currentMediaItem } returns item("v2")
    val sched = FakeCrossfadeScheduler()
    val crossfade = build(playerA, playerB, enabled = true, durationMs = 100L, scheduler = sched)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()
    advanceUntilIdle()

    val transitionedItems = mutableListOf<MediaItem?>()
    val transitionReasons = mutableListOf<Int>()
    crossfade.addListener(object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            transitionedItems.add(mediaItem); transitionReasons.add(reason)
        }
    })

    sched.runAll()
    advanceUntilIdle()
    advanceTimeBy(200L)  // ramp completes
    advanceUntilIdle()

    assertEquals(1, transitionedItems.size)
    assertEquals("v2", transitionedItems[0]?.mediaId)
    assertEquals(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, transitionReasons[0])
}

@Test
fun `manual skip synthesises onMediaItemTransition with REASON_SEEK`() = runTest(testDispatcher) {
    val playerA = mockExo(); val playerB = mockExo()
    val crossfade = build(playerA, playerB, enabled = false)  // instant cut
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()

    val reasons = mutableListOf<Int>()
    crossfade.addListener(object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { reasons.add(reason) }
    })

    crossfade.seekToNext()
    advanceUntilIdle()
    assertEquals(listOf(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK), reasons)
}

@Test
fun `error on either player aborts fade and falls back to healthy player`() = runTest(testDispatcher) {
    val playerA = mockExo(); val playerB = mockExo()
    every { playerA.isPlaying } returns true
    val sched = FakeCrossfadeScheduler()
    val crossfade = build(playerA, playerB, enabled = true, scheduler = sched)
    crossfade.setMediaItems(listOf(item("v1"), item("v2")), 0, 0L)
    crossfade.play()
    advanceUntilIdle()
    sched.runAll()
    advanceUntilIdle()
    assertEquals(CrossfadingPlayer.State.CROSSFADING, crossfade.state)

    // Simulate a player error by capturing playerA's listener and invoking onPlayerError.
    // (Test will need to capture the listener registered in init — see implementation for the slot.)
    crossfade.onUnderlyingPlayerError(isPlayerA = true, error = mockk(relaxed = true))
    advanceUntilIdle()

    assertEquals(CrossfadingPlayer.State.SINGLE_ACTIVE, crossfade.state)
}
```

(The test invokes a package-internal hook on the wrapper — Step 2 implementation exposes `internal fun onUnderlyingPlayerError(...)` for testability.)

- [ ] **Step 2: Implement the listener wiring**

```kotlin
import androidx.media3.common.PlaybackException

init {
    // existing collectors ...
    playerA.addListener(object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Focus-driven pause/play propagation per spec §7
            val focusReasons = setOf(
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
            )
            if (reason in focusReasons) {
                playerB.playWhenReady = playWhenReady
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            onUnderlyingPlayerError(isPlayerA = true, error = error)
        }
    })
    playerB.addListener(object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            onUnderlyingPlayerError(isPlayerA = false, error = error)
        }
    })
}

internal fun onUnderlyingPlayerError(isPlayerA: Boolean, error: PlaybackException) {
    rampJob?.cancel()
    val healthy = if (isPlayerA) playerB else playerA
    val broken = if (isPlayerA) playerA else playerB
    broken.stop()
    broken.clearMediaItems()
    activeRef.set(healthy); nextRef.set(broken)
    stateValue = State.SINGLE_ACTIVE
    healthy.volume = userVolume
    preloadNext()
    scheduleAutoTrigger()
    // Note: PlayerRepositoryImpl already has its own rescue path on Player.Listener.onPlayerError;
    // since the wrapper IS the Player it sees, that rescue still fires from the wrapper's own
    // listener forwarding (set up by ForwardingPlayer's default). No re-emission needed here.
}
```

**Listener event forwarding — synthesise `onMediaItemTransition` at swap moments.** `ForwardingPlayer` registers external listeners against `playerA` only, but the wrapper rotates active/next on every fade. External observers must see one synthesised transition event per real transition. Implement this by overriding `addListener`/`removeListener` to maintain the wrapper's own collection, then firing synthesised events at the right moments:

```kotlin
private val externalListeners = java.util.concurrent.CopyOnWriteArrayList<Player.Listener>()

override fun addListener(listener: Player.Listener) {
    externalListeners.add(listener)
    // Don't forward to playerA — we hand-craft every event the wrapper cares about.
}

override fun removeListener(listener: Player.Listener) {
    externalListeners.remove(listener)
}

private fun fireMediaItemTransition(item: MediaItem?, reason: Int) {
    externalListeners.forEach { it.onMediaItemTransition(item, reason) }
}

private fun fireIsPlayingChanged(isPlaying: Boolean) {
    externalListeners.forEach { it.onIsPlayingChanged(isPlaying) }
}

private fun firePlaybackStateChanged(state: Int) {
    externalListeners.forEach { it.onPlaybackStateChanged(state) }
}

private fun firePlayerError(error: PlaybackException) {
    externalListeners.forEach { it.onPlayerError(error) }
}
```

Wire the synthesised events at the right call-sites:

- In `completeCrossfade(...)`: after the role-swap, call `fireMediaItemTransition(currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)`.
- In `startManualTransitionTo(...)` instant-cut path: after the swap, call `fireMediaItemTransition(currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)`.
- In the manual-fade path: after `completeCrossfade` runs (which already fires AUTO above), additionally fire `Player.MEDIA_ITEM_TRANSITION_REASON_SEEK` if `pendingManualTarget != null` was the trigger. Distinguish AUTO vs SEEK by setting a `@Volatile var pendingTransitionReason: Int = MEDIA_ITEM_TRANSITION_REASON_AUTO` before each fade; manual paths set it to SEEK; `completeCrossfade` reads it once and reverts to AUTO.
- In `onUnderlyingPlayerError`: call `firePlayerError(error)` so `PlayerRepositoryImpl`'s rescue path runs.
- In the `init` block, register internal `Player.Listener`s on both underlying players that forward `onIsPlayingChanged` / `onPlaybackStateChanged` to `fireIsPlayingChanged` / `firePlaybackStateChanged` aggregated per the rules in spec §3:
  - `isPlaying` aggregates: `true` if either underlying is playing
  - `playbackState` mirrors `nextPlayer` during CROSSFADING, `activePlayer` otherwise

Update the commit message in Step 4 to: `feat(media): audio focus + error fallback + listener event forwarding`.

- [ ] **Step 3: Run tests**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
./gradlew :core:media:test --tests "com.stash.core.media.service.CrossfadingPlayerTest"
```

Expected: new test passes; all previous still pass.

- [ ] **Step 4: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
git add core/media/src/main/kotlin/com/stash/core/media/service/CrossfadingPlayer.kt \
        core/media/src/test/kotlin/com/stash/core/media/service/CrossfadingPlayerTest.kt && \
git commit -m "feat(media): audio focus + error fallback + listener event forwarding"
```

---

## Task 8: Wire `CrossfadingPlayer` into `StashPlaybackService`

Replace the single `ExoPlayer` with two players + the wrapper. Both players share the renderers factory (so the EQ chain applies to both — spec §4) and the same audio session ID.

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt`

- [ ] **Step 1: Inject `CrossfadePreferences` + add a player scope**

In `StashPlaybackService.kt`:

```kotlin
@Inject lateinit var crossfadePreferences: CrossfadePreferences

private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
```

(Imports: `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.SupervisorJob`, `kotlinx.coroutines.cancel`, `com.stash.core.data.prefs.CrossfadePreferences`.)

- [ ] **Step 2: Build TWO ExoPlayers + the wrapper**

In `onCreate`, replace lines 76-85 (the single-`ExoPlayer.Builder` block) with:

```kotlin
fun buildPlayer(handleAudioFocus: Boolean): ExoPlayer = ExoPlayer.Builder(this)
    .setRenderersFactory(StashRenderersFactory(this, eqController))  // shared eqController — spec §4
    .setLoadControl(loadControl)
    .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ handleAudioFocus)
    .setHandleAudioBecomingNoisy(true)
    .setWakeMode(C.WAKE_MODE_LOCAL)
    .build()

val playerA = buildPlayer(handleAudioFocus = true)
val playerB = buildPlayer(handleAudioFocus = false)

// Same session ID across both — required for audio-effect attribution and any future
// session-level audio effects (the renderer-level Stash EQ doesn't depend on this).
playerA.audioSessionId = audioSessionId
playerB.audioSessionId = audioSessionId

val mainHandler = android.os.Handler(playerA.applicationLooper)
val crossfadingPlayer = CrossfadingPlayer(
    playerA = playerA,
    playerB = playerB,
    crossfadePreferences = crossfadePreferences,
    scope = playerScope,
    scheduler = HandlerCrossfadeScheduler(mainHandler),
    clock = CrossfadeClock.SYSTEM,
)
```

- [ ] **Step 3: Pass the wrapper to MediaSession**

```kotlin
val sessionBuilder = MediaSession.Builder(this, crossfadingPlayer)
    .setCallback(StashSessionCallback())
```

- [ ] **Step 4: Update `onDestroy`**

```kotlin
override fun onDestroy() {
    mediaSession?.run {
        player.release()  // wrapper.release() cascades to both ExoPlayers
        release()
    }
    mediaSession = null
    playerScope.cancel()
    super.onDestroy()
}
```

- [ ] **Step 5: Build the app**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && ./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run all relevant tests for regressions**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
./gradlew :core:media:test :core:data:test
```

Pre-existing baseline failures acceptable; nothing NEW should fail.

- [ ] **Step 7: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
git add core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt && \
git commit -m "feat(media): wire CrossfadingPlayer into StashPlaybackService (2 ExoPlayers, shared EQ)"
```

---

## Task 9: Settings UI — `PlaybackSection` + ViewModel + UiState

Add the user-facing toggle + slider in Settings. Off-state hides the slider.

**Files:**
- Create: `feature/settings/src/main/kotlin/com/stash/feature/settings/components/PlaybackSection.kt`
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsScreen.kt`
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsUiState.kt`
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt`

- [ ] **Step 1: Read the existing patterns**

Before writing, read `feature/settings/.../components/EqualizerSection.kt` and `feature/settings/.../SettingsScreen.kt`. Match their visual language (GlassCard + Material3 extended theme — memory `feedback_stash_design_system.md`). Do NOT freestyle a generic dark-mode style.

- [ ] **Step 2: Add fields to `SettingsUiState`**

```kotlin
val crossfadeEnabled: Boolean = true,
val crossfadeDurationSeconds: Int = 2,
```

- [ ] **Step 3: Add ViewModel methods + collect prefs**

In `SettingsViewModel.kt`, inject `CrossfadePreferences`. Combine its flows into the existing UI-state-flow combine:

```kotlin
combine(
    // ... existing flows
    crossfadePreferences.enabled,
    crossfadePreferences.durationMs,
) { ..., enabled, durationMs ->
    state.copy(
        crossfadeEnabled = enabled,
        crossfadeDurationSeconds = (durationMs / 1000L).toInt(),
    )
}

fun setCrossfadeEnabled(value: Boolean) {
    viewModelScope.launch { crossfadePreferences.setEnabled(value) }
}

fun setCrossfadeDurationSeconds(seconds: Int) {
    viewModelScope.launch { crossfadePreferences.setDurationMs(seconds * 1000L) }
}
```

- [ ] **Step 4: Build `PlaybackSection`**

```kotlin
package com.stash.feature.settings.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
// ... plus whatever imports EqualizerSection.kt uses for GlassCard / theme tokens

@Composable
fun PlaybackSection(
    crossfadeEnabled: Boolean,
    crossfadeDurationSeconds: Int,
    onCrossfadeEnabledChanged: (Boolean) -> Unit,
    onCrossfadeDurationChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Wrap in GlassCard like EqualizerSection.
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
                    steps = 10,
                )
            }
        }
    }
}
```

- [ ] **Step 5: Insert into `SettingsScreen`**

Find the seam between the "Audio Quality" and "Audio Effects" sections (search for `SectionHeader` calls in `SettingsScreen.kt`) and insert:

```kotlin
SectionHeader(title = "Playback")
PlaybackSection(
    crossfadeEnabled = uiState.crossfadeEnabled,
    crossfadeDurationSeconds = uiState.crossfadeDurationSeconds,
    onCrossfadeEnabledChanged = viewModel::setCrossfadeEnabled,
    onCrossfadeDurationChanged = viewModel::setCrossfadeDurationSeconds,
)
```

- [ ] **Step 6: Build**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
./gradlew :feature:settings:assembleDebug :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
git add feature/settings/src/main/kotlin/com/stash/feature/settings/ && \
git commit -m "feat(settings): Crossfade toggle + duration slider in Playback section"
```

---

## Task 10: Device acceptance

Memory `feedback_install_after_fix.md`: compile-pass isn't enough — install and verify on device.

- [ ] **Step 1: Install on device**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
./gradlew :app:installDebug
```

- [ ] **Step 2: Run the manual acceptance flow**

Per spec §Testing → Manual acceptance:

1. **Toggle ON (default), 2 s.** Play All from a playlist with 3+ tracks → smooth blend on every transition. No perceptible volume dip mid-fade.
2. **Toggle OFF.** Play All → hard cuts as today. No regression.
3. **Manual next mid-track (toggle ON).** 2 s fade kicks in; no glitch.
4. **Rebound: tap next twice fast.** Smooth — no pop, no double-fade.
5. **Slider 1 s → 12 s.** Each step takes effect on next fade; in-flight one keeps original duration.
6. **Toggle OFF mid-fade.** In-flight fade snaps cleanly; subsequent transitions hard-cut.
7. **EQ during fade.** Open Audio Effects, change EQ during a fade — change applies to both halves audibly.
8. **Shuffle ON.** Crossfade plays the shuffled-next, not queue-order-next.
9. **Repeat-one ON.** No crossfade — track loops with hard cut.
10. **Repeat-all, last track.** Wraps to queue[0] with crossfade.
11. **Bluetooth headphones (if available).** No skip/stutter; skip via headphone button still triggers manual fade.
12. **Lockscreen / system notification.** Track metadata updates correctly when each fade starts. Notification queue UI shows the full queue (validates `CanonicalQueueTimeline`).
13. **Five tracks back-to-back.** Each transition smooth; metadata correct throughout.
14. **Phone call mid-playback.** Both players pause via session. Call ends → resume.

If anything sounds glitchy, run `adb logcat -s Crossfade StashPlayback ExoPlayer:V` and inspect.

- [ ] **Step 3: Empty commit for the record**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
git commit --allow-empty -m "test: manual device acceptance — crossfader auto + manual + rebound + shuffle"
```

---

## Task 11: Version bump + ship 0.10.0

Memory `feedback_ship_terminology.md`: "ship" = public release (tag + push), not local install. Memory `feedback_release_notes.md`: release body comes from the tagged-commit's message.

The current version is 0.9.0-beta1 per recent commits. This is a meaningful new feature → bump minor: 0.9.0-beta1 → 0.10.0.

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Bump version**

In `app/build.gradle.kts`, update `versionCode` and `versionName`. Confirm the previous value first by reading the file.

- [ ] **Step 2: Release commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/crossfader && \
git add app/build.gradle.kts && \
git commit -m "$(cat <<'EOF'
feat: 0.10.0 — track crossfader (#16)

True audio crossfade between tracks, on both auto-transitions and
manual skips. ON by default with a short 2 s blend; toggle and 1-12 s
duration slider live in Settings → Playback.

Implementation:
- New CrossfadingPlayer wrapper over Media3 ForwardingPlayer
- Owns two ExoPlayer instances; shared StashRenderersFactory means
  the renderer-level EQ applies to both halves of every fade
- Equal-power (cosine) volume ramp keeps perceived loudness flat
- Scheduled one-shot trigger (not polling) for sub-frame-accurate fade start
- Precomputed shuffleOrder so shuffle picks the right next track
- Synthesised CanonicalQueueTimeline so notification queue UI stays correct
- Audio focus propagates from playerA to playerB explicitly

Closes #16.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 3: Merge to master**

```bash
cd C:/Users/theno/Projects/MP3APK && \
git merge --ff-only feat/crossfader && \
git log --oneline -5
```

- [ ] **Step 4: Tag (lightweight) + push**

```bash
cd C:/Users/theno/Projects/MP3APK && \
git tag v0.10.0 && \
git push origin master && \
git push origin v0.10.0
```

**Lightweight tag, not annotated** (no `-a` / `-m`). Memory `feedback_release_notes.md`: the release body comes from the tagged-commit's message, not the tag annotation — so the natural-language notes already in the release commit (Step 2) become the release body when GitHub renders the release page. An annotated tag would compete with that body.

- [ ] **Step 5: GitHub release + close issue**

```bash
cd C:/Users/theno/Projects/MP3APK && \
gh release create v0.10.0 --title "v0.10.0 — Track crossfader" && \
gh issue close 16 --comment "Implemented in [v0.10.0](https://github.com/rawnaldclark/Stash/releases/tag/v0.10.0)."
```

**No `--notes` flag.** GitHub will render the release using the tagged commit's body — exactly what we want, no duplication.

- [ ] **Step 6: Clean up worktree**

```bash
cd C:/Users/theno/Projects/MP3APK && \
git worktree remove .worktrees/crossfader && \
git branch -d feat/crossfader
```

If Windows path-length errors hit, leave the worktree dir for later manual cleanup.

---

## Skills reference

- @superpowers:test-driven-development — strict for Tasks 1–7 (foundation, ramp, manual skip, mutations, focus)
- @superpowers:verification-before-completion — before declaring each task done; do not skip the device acceptance in Task 10
- @superpowers:systematic-debugging — if Task 10 surfaces glitches; logcat with `Log.d("Crossfade", ...)` markers

## Risks / rollback

- **Rollback:** revert the release commit. Two `ExoPlayer`s revert to one; the wrapper class becomes dead code (delete in a follow-up). DataStore preferences stay in users' caches but are silently ignored. Settings UI tile becomes unreachable. Reversible in seconds.
- **MediaSession state desync.** Biggest implementation risk; mitigated by §3 forwarding rules and the lockscreen / notification check in Task 10.
- **EQ silent on incoming track during fade.** Both `ExoPlayer`s built with `setRenderersFactory(StashRenderersFactory(this, eqController))` — verified by ear in Task 10 step 7.
- **Shuffle picks wrong track.** Precomputed `shuffleOrder`; verified by Task 10 step 8.
- **Repeat-one fade-to-self.** Auto-trigger guarded; verified by Task 10 step 9.
- **Audio focus during phone call.** Listener propagation per spec §7; verified by Task 10 step 14.
- **Ducking causes asymmetric loudness mid-fade.** Known V1 limitation per spec §7. Defer to V2 unless user reports.
