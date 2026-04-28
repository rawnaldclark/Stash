# Sync Tab Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current 1322-line `SyncScreen.kt` with a redesigned tab matching the brainstormed mockup — gradient hero card with last-sync stats, brand-bar Source Preferences cards with status pills, natural-language Schedule sentence with tappable day/time/network chips, and a new days-of-week scheduling field that the WorkManager-backed scheduler honors.

**Architecture:** Decompose `SyncScreen.kt` into one orchestrator + ~8 focused component files. Add a `DayOfWeekSet` value class wrapping a 7-bit `Int` to express which days the auto-sync runs. Extend `SyncPreferencesManager` and `SyncScheduler` to round-trip the bitmask, advancing the next-firing-time calculation past disabled days. UI changes use existing primitives (GlassCard, StashTheme.extendedColors, MaterialTheme typography) — no new theme tokens.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Hilt + AssistedInject, AndroidX DataStore Preferences, AndroidX WorkManager, kotlinx.coroutines, JUnit 4 + mockito-kotlin.

**Spec:** `docs/superpowers/specs/2026-04-28-sync-tab-redesign.md`

---

## Pre-flight

The work continues in the existing `feat/yt-sync-pagination` worktree. This branch already carries the YT pagination overhaul + studio-only filter feature. Recent HEAD: `eff378f docs(spec): apply spec-reviewer advisories…`

**All subsequent tasks operate in:** `C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination`. Every Bash command must begin with `cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ...`. Read/Edit/Write tools should use absolute paths rooted at that worktree.

- [ ] Verify clean baseline:

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :core:data:testDebugUnitTest :data:ytmusic:testDebugUnitTest :feature:sync:compileDebugKotlin --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] Confirm orientation in the existing files:

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && wc -l feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt core/data/src/main/kotlin/com/stash/core/data/sync/SyncPreferencesManager.kt core/data/src/main/kotlin/com/stash/core/data/sync/SyncScheduler.kt
```

Expected: SyncScreen.kt ~1322, SyncViewModel.kt ~458, SyncPreferencesManager.kt ~167, SyncScheduler.kt ~170. Numbers don't have to match exactly — the plan is robust to small offsets.

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && grep -n "scheduleDailySync\|computeDelayToNextSync" core/data/src/main/kotlin/com/stash/core/data/sync/SyncScheduler.kt core/data/src/main/kotlin/com/stash/core/data/sync/BootReceiver.kt feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt
```

Confirms `scheduleDailySync(hour, minute, wifiOnly)` is called from `BootReceiver` and `SyncViewModel`, and that `computeDelayToNextSync` is the day-advance integration point.

---

## Task 1: `DayOfWeekSet` value class + unit tests

**Verified facts:**
- Pure logic, no Android or Compose dependencies — lives in `:core:data` so both the worker pipeline and feature modules can use it.
- Wraps `Int` as a `@JvmInline value class`. Bit layout: bit 0 = Monday, bit 6 = Sunday, matching `java.time.DayOfWeek.value - 1` (DayOfWeek.MONDAY.value == 1, so `value - 1 == 0`). Confirm by running the bit-mapping test in step 1.
- The full default `0b1111111 == 127` means "every day", giving back-compat semantics for any caller that doesn't know about days.

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/sync/DayOfWeekSet.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/sync/DayOfWeekSetTest.kt` (new)

- [ ] **Step 1: Write the failing tests**

Create `core/data/src/test/kotlin/com/stash/core/data/sync/DayOfWeekSetTest.kt`:

```kotlin
package com.stash.core.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class DayOfWeekSetTest {

    @Test fun `bit 0 is Monday`() {
        assertEquals(0, DayOfWeek.MONDAY.value - 1)
        assertTrue(DayOfWeekSet(0b0000001).contains(DayOfWeek.MONDAY))
        assertFalse(DayOfWeekSet(0b0000001).contains(DayOfWeek.TUESDAY))
    }

    @Test fun `bit 6 is Sunday`() {
        assertEquals(6, DayOfWeek.SUNDAY.value - 1)
        assertTrue(DayOfWeekSet(0b1000000).contains(DayOfWeek.SUNDAY))
        assertFalse(DayOfWeekSet(0b1000000).contains(DayOfWeek.SATURDAY))
    }

    @Test fun `with toggles a single bit and leaves others alone`() {
        val mwf = DayOfWeekSet(0b0010101) // Mon, Wed, Fri
        val withTue = mwf.with(DayOfWeek.TUESDAY, on = true)
        assertEquals(DayOfWeekSet(0b0010111), withTue)
        val withoutMon = mwf.with(DayOfWeek.MONDAY, on = false)
        assertEquals(DayOfWeekSet(0b0010100), withoutMon)
    }

    @Test fun `isDaily true on 127`() {
        assertTrue(DayOfWeekSet(0b1111111).isDaily)
        assertFalse(DayOfWeekSet(0b1111110).isDaily)
    }

    @Test fun `isWeekdays true on 31 only`() {
        // Bits 0..4 = Mon..Fri = 0b0011111 = 31
        assertTrue(DayOfWeekSet(0b0011111).isWeekdays)
        assertFalse(DayOfWeekSet(0b0111111).isWeekdays) // includes Sat
        assertFalse(DayOfWeekSet(0b0010111).isWeekdays) // missing Thu
    }

    @Test fun `isWeekends true on 96 only`() {
        // Bits 5..6 = Sat, Sun = 0b1100000 = 96
        assertTrue(DayOfWeekSet(0b1100000).isWeekends)
        assertFalse(DayOfWeekSet(0b1100001).isWeekends) // includes Mon
        assertFalse(DayOfWeekSet(0b0100000).isWeekends) // only Sat
    }

    @Test fun `presetLabel returns daily weekdays weekends or compact`() {
        assertEquals("daily", DayOfWeekSet(0b1111111).presetLabel())
        assertEquals("weekdays", DayOfWeekSet(0b0011111).presetLabel())
        assertEquals("weekends", DayOfWeekSet(0b1100000).presetLabel())
        // Custom: Mon Wed Fri → compact label
        assertEquals("Mon · Wed · Fri", DayOfWeekSet(0b0010101).presetLabel())
        // Custom: just Tue
        assertEquals("Tue", DayOfWeekSet(0b0000010).presetLabel())
        // Empty
        assertEquals("none", DayOfWeekSet(0).presetLabel())
    }

    @Test fun `isEmpty true only on 0`() {
        assertTrue(DayOfWeekSet(0).isEmpty)
        assertFalse(DayOfWeekSet(1).isEmpty)
        assertFalse(DayOfWeekSet(127).isEmpty)
    }

    @Test fun `count returns number of set bits`() {
        assertEquals(0, DayOfWeekSet(0).count)
        assertEquals(7, DayOfWeekSet(127).count)
        assertEquals(3, DayOfWeekSet(0b0010101).count)
    }
}
```

- [ ] **Step 2: Run the tests — expect compile error**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.DayOfWeekSetTest" --console=plain
```

Expected: compile error — `DayOfWeekSet` does not exist.

- [ ] **Step 3: Implement the value class**

Create `core/data/src/main/kotlin/com/stash/core/data/sync/DayOfWeekSet.kt`:

```kotlin
package com.stash.core.data.sync

import java.time.DayOfWeek

/**
 * Set of days of the week, encoded as a 7-bit bitmask.
 *
 * Bit layout: bit 0 = Monday, bit 1 = Tuesday … bit 6 = Sunday. This matches
 * `java.time.DayOfWeek.value - 1` (where MONDAY.value == 1).
 *
 * Used to express which days the auto-sync should run. The default
 * 0b1111111 (127) means every day, matching the prior "always daily" behavior
 * of [SyncScheduler.scheduleDailySync] before this field existed.
 */
@JvmInline
value class DayOfWeekSet(val bitmask: Int) {

    /** True if [day] is in the set. */
    fun contains(day: DayOfWeek): Boolean =
        (bitmask shr (day.value - 1)) and 1 == 1

    /** Returns a new set with [day] toggled to [on]. */
    fun with(day: DayOfWeek, on: Boolean): DayOfWeekSet {
        val mask = 1 shl (day.value - 1)
        return DayOfWeekSet(if (on) bitmask or mask else bitmask and mask.inv())
    }

    /** True if no days are set. UI shows the "pick at least one day" hint when this is true. */
    val isEmpty: Boolean get() = bitmask == 0

    /** True when all 7 days are set. */
    val isDaily: Boolean get() = bitmask == 0b1111111

    /** True when exactly Monday-Friday is set (bits 0..4). */
    val isWeekdays: Boolean get() = bitmask == 0b0011111

    /** True when exactly Saturday-Sunday is set (bits 5..6). */
    val isWeekends: Boolean get() = bitmask == 0b1100000

    /** Number of days set. */
    val count: Int get() = Integer.bitCount(bitmask)

    /**
     * Human-readable label for the schedule sentence:
     *  - `"daily"` when all 7 set
     *  - `"weekdays"` when Mon-Fri only
     *  - `"weekends"` when Sat-Sun only
     *  - `"none"` when empty
     *  - otherwise compact 3-letter abbreviations joined by ` · ` (e.g. `"Mon · Wed · Fri"`)
     */
    fun presetLabel(): String = when {
        isDaily -> "daily"
        isWeekdays -> "weekdays"
        isWeekends -> "weekends"
        isEmpty -> "none"
        else -> compactLabel()
    }

    /**
     * Always renders as the 3-letter abbreviations regardless of whether the
     * bitmask matches a preset. Useful for the day-picker panel header.
     */
    fun compactLabel(): String = ABBREV_BY_DAY
        .filter { (day, _) -> contains(day) }
        .joinToString(" · ") { (_, abbrev) -> abbrev }

    companion object {
        val EVERY_DAY = DayOfWeekSet(0b1111111)
        val WEEKDAYS = DayOfWeekSet(0b0011111)
        val WEEKENDS = DayOfWeekSet(0b1100000)
        val NONE = DayOfWeekSet(0)

        /** Mon-first ordering for compact-label rendering. */
        private val ABBREV_BY_DAY: List<Pair<DayOfWeek, String>> = listOf(
            DayOfWeek.MONDAY to "Mon",
            DayOfWeek.TUESDAY to "Tue",
            DayOfWeek.WEDNESDAY to "Wed",
            DayOfWeek.THURSDAY to "Thu",
            DayOfWeek.FRIDAY to "Fri",
            DayOfWeek.SATURDAY to "Sat",
            DayOfWeek.SUNDAY to "Sun",
        )
    }
}
```

- [ ] **Step 4: Run the tests — expect PASS**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.DayOfWeekSetTest" --console=plain
```

Expected: 8 tests, PASS.

- [ ] **Step 5: Run the full `:core:data` suite — no regressions**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :core:data:testDebugUnitTest --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add core/data/src/main/kotlin/com/stash/core/data/sync/DayOfWeekSet.kt \
        core/data/src/test/kotlin/com/stash/core/data/sync/DayOfWeekSetTest.kt
git commit -m "feat(sync): DayOfWeekSet value class for day-of-week scheduling

Wraps a 7-bit Int. Bit 0=Mon, 6=Sun (matches java.time.DayOfWeek.value - 1).
Helpers: contains, with, isEmpty, isDaily, isWeekdays, isWeekends, count,
presetLabel, compactLabel. 8 unit tests cover bit-mapping, toggle,
preset detection, and label rendering."
```

---

## Task 2: `syncDays` preference

**Verified facts:**
- `SyncPreferencesManager` already has the DataStore-key + flow + setter pattern from prior tasks (e.g. `youtubeLikedStudioOnly` from the studio-only filter feature). The new pref follows the same shape.
- Default value `127` (every day) gives back-compat for installs that never wrote the key.

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/SyncPreferencesManager.kt`

- [ ] **Step 1: Add the DataStore key**

In the `Keys` private object:

```kotlin
val SYNC_DAYS = intPreferencesKey("sync_days")
```

`intPreferencesKey` should already be imported.

- [ ] **Step 2: Add the field to `SyncPreferences`**

Append to the data class with kdoc:

```kotlin
/**
 * 7-bit bitmask of days the auto-sync should run. Bit 0 = Monday … bit 6 = Sunday.
 * Default 0b1111111 (127) = every day, matching the prior daily behavior.
 * 0 means no day is enabled — UI surfaces a "pick at least one day" hint and
 * the scheduler does not enqueue work.
 */
val syncDays: Int = 0b1111111,
```

- [ ] **Step 3: Resolve in the combined `preferences` flow**

In the `preferences: Flow<SyncPreferences>` block, add the new field to the `SyncPreferences(...)` constructor call:

```kotlin
syncDays = prefs[Keys.SYNC_DAYS] ?: 0b1111111,
```

- [ ] **Step 4: Add a per-pref `Flow` for workers**

Right below the existing `youtubeLikedStudioOnly: Flow<Boolean>` (added in the studio-only filter feature):

```kotlin
/**
 * Reactive stream of the days-of-week bitmask. Read via .first() inside
 * [SyncScheduler] when computing the next firing time. Default 127 = every day.
 */
val syncDays: Flow<Int> =
    context.syncPrefsDataStore.data.map { it[Keys.SYNC_DAYS] ?: 0b1111111 }
```

- [ ] **Step 5: Add the setter**

```kotlin
/** Persist the days-of-week bitmask. UI passes [DayOfWeekSet.bitmask]. */
suspend fun setSyncDays(bitmask: Int) {
    context.syncPrefsDataStore.edit { it[Keys.SYNC_DAYS] = bitmask }
}
```

- [ ] **Step 6: Compile + run existing tests**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :core:data:testDebugUnitTest --console=plain
```

Expected: BUILD SUCCESSFUL. Purely additive.

- [ ] **Step 7: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add core/data/src/main/kotlin/com/stash/core/data/sync/SyncPreferencesManager.kt
git commit -m "feat(sync): add syncDays preference (Int bitmask, default 127)

DataStore-backed key sync_days. Default 127 = every day, matching prior
behavior for back-compat. Plumbed via SyncPreferences data class, narrow
Flow, and a setter. Mirrors the youtubeLikedStudioOnly pattern."
```

---

## Task 3: Day-aware scheduling in `SyncScheduler`

**Verified facts:**
- `computeDelayToNextSync(hour, minute)` is at `core/data/src/main/kotlin/com/stash/core/data/sync/SyncScheduler.kt:107-122`. It computes the next firing time using `java.util.Calendar`, advancing one day if today's target has already passed.
- `scheduleDailySync(hour, minute, wifiOnly)` at line 58 calls `computeDelayToNextSync` then enqueues the chain. Two callers: `BootReceiver:38` (passes Spotify-side hour/minute, which is the same as YT-side because they share the schedule), and `SyncViewModel:245` and `:258`.
- The day-advance loop iterates at most 7 days. We need a `Calendar`-aware check that maps the Calendar's day-of-week to our bitmask (Calendar uses Sunday=1..Saturday=7; DayOfWeek uses Monday=1..Sunday=7 — the conversion is `(calendarDow + 5) % 7` or just use `LocalDateTime.now().dayOfWeek`).
- Bitmask == 0 → return null delay; the caller treats null as "do not enqueue."
- Tests are pure JVM (no Android required) — use `Clock.fixed(...)` to mock current time so tests are deterministic.

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/SyncScheduler.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/sync/SyncSchedulerComputeTest.kt` (new, pure-JVM unit tests)

- [ ] **Step 1: Refactor `computeDelayToNextSync` to accept a `Clock` for testability**

Today's implementation calls `Calendar.getInstance()` directly, which is hard to test. Extract the time source so tests can inject a fixed clock:

In `SyncScheduler.kt`, change the signature and body:

```kotlin
/**
 * Computes the number of milliseconds until the next firing time at
 * [hour]:[minute] on a day enabled in [days].
 *
 * If the target time at hour:minute has already passed today (or today is
 * not enabled in [days]), advances day by day until a matching enabled day
 * is found, up to 7 hops.
 *
 * Returns null if [days] is empty (bitmask == 0) — caller should not enqueue.
 *
 * @param hour     Hour of day (0-23).
 * @param minute   Minute of hour (0-59).
 * @param days     Day-of-week bitmask. Defaults to every day for back-compat.
 * @param clock    Time source. Defaults to system clock; tests inject a fixed clock.
 */
fun computeDelayToNextSync(
    hour: Int,
    minute: Int,
    days: DayOfWeekSet = DayOfWeekSet.EVERY_DAY,
    clock: java.time.Clock = java.time.Clock.systemDefaultZone(),
): Long? {
    if (days.isEmpty) return null

    val zone = clock.zone
    val now = java.time.ZonedDateTime.now(clock)
    var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)

    // If today's target has already passed, advance to tomorrow before checking days.
    if (!target.isAfter(now)) {
        target = target.plusDays(1)
    }
    // Advance day-by-day until we land on an enabled day. At most 7 hops since
    // bitmask is non-empty.
    var hops = 0
    while (!days.contains(target.dayOfWeek) && hops < 7) {
        target = target.plusDays(1)
        hops++
    }
    if (hops >= 7) return null  // defensive — should be unreachable

    return java.time.Duration.between(now, target).toMillis()
}
```

(Add `import com.stash.core.data.sync.DayOfWeekSet` if not already in scope — same package, so no import needed.)

- [ ] **Step 2: Update `scheduleDailySync` to accept and forward the bitmask**

Change the public method:

```kotlin
fun scheduleDailySync(
    hour: Int,
    minute: Int,
    wifiOnly: Boolean = true,
    days: DayOfWeekSet = DayOfWeekSet.EVERY_DAY,
) {
    val delayMs = computeDelayToNextSync(hour, minute, days)
    if (delayMs == null) {
        Log.d(TAG, "scheduleDailySync: no enabled days, skipping")
        cancelSync()  // ensure no stale request lingers
        return
    }
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(
            if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
        )
        .setRequiresBatteryNotLow(true)
        .build()
    enqueueChain(initialDelayMs = delayMs, constraints = constraints)
}
```

Also delete the legacy `Calendar`-based implementation of `computeDelayToNextSync` from this file (it was replaced by the `Clock`-based version in Step 1) and remove the now-unused `import java.util.Calendar`.

- [ ] **Step 3: Write failing tests**

Create `core/data/src/test/kotlin/com/stash/core/data/sync/SyncSchedulerComputeTest.kt`:

```kotlin
package com.stash.core.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Unit tests for SyncScheduler.computeDelayToNextSync — exercises the day-advance
 * algorithm via a mocked Clock. We instantiate SyncScheduler with null context
 * because the tested method does not touch Android APIs.
 */
class SyncSchedulerComputeTest {

    private val zone = ZoneId.of("UTC")

    /** Returns a fixed Clock at [year]-[month]-[day] [hour]:[minute] UTC. */
    private fun clockAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Clock {
        val instant = LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant()
        return Clock.fixed(instant, zone)
    }

    /** A SyncScheduler instance whose deps are mocked — the tested method does not touch them. */
    private val scheduler = SyncScheduler(
        context = org.mockito.kotlin.mock(),
        syncStateManager = org.mockito.kotlin.mock(),
    )

    @Test fun `daily mask, current is Mon 10am, target 6am — schedules Tue 6am`() {
        // 2026-04-27 is a Monday.
        val clock = clockAt(2026, 4, 27, 10, 0)
        val delay = scheduler.computeDelayToNextSync(6, 0, DayOfWeekSet.EVERY_DAY, clock)
        // Tomorrow (Tue) at 6am = +20h
        assertEquals(Duration.ofHours(20).toMillis(), delay)
    }

    @Test fun `daily mask, current is Mon 5am, target 6am — schedules today 6am`() {
        val clock = clockAt(2026, 4, 27, 5, 0)
        val delay = scheduler.computeDelayToNextSync(6, 0, DayOfWeekSet.EVERY_DAY, clock)
        assertEquals(Duration.ofHours(1).toMillis(), delay)
    }

    @Test fun `weekdays mask, current is Fri 10am — schedules Mon 6am`() {
        // 2026-05-01 is a Friday.
        val clock = clockAt(2026, 5, 1, 10, 0)
        val delay = scheduler.computeDelayToNextSync(6, 0, DayOfWeekSet.WEEKDAYS, clock)
        // Sat 6am skipped, Sun 6am skipped, Mon 6am = Sat + Sun + 20h = 2 days + 20h = 68h
        assertEquals(Duration.ofHours(68).toMillis(), delay)
    }

    @Test fun `weekends mask, current is Mon 10am — schedules Sat 6am`() {
        val clock = clockAt(2026, 4, 27, 10, 0)
        val delay = scheduler.computeDelayToNextSync(6, 0, DayOfWeekSet.WEEKENDS, clock)
        // Mon 10am to Sat 6am: 4 full days (Tue, Wed, Thu, Fri) + 20h = 4 * 24 + 20 = 116h
        assertEquals(Duration.ofHours(116).toMillis(), delay)
    }

    @Test fun `Sunday-only mask, current is Mon 10am — schedules next Sun 6am`() {
        val clock = clockAt(2026, 4, 27, 10, 0)
        val delay = scheduler.computeDelayToNextSync(6, 0, DayOfWeekSet(0b1000000), clock)
        // Mon 10am → next Sun 6am: 6 days minus 4 hours = 5d 20h = 140h
        assertEquals(Duration.ofHours(140).toMillis(), delay)
    }

    @Test fun `empty bitmask returns null`() {
        val clock = clockAt(2026, 4, 27, 10, 0)
        val delay = scheduler.computeDelayToNextSync(6, 0, DayOfWeekSet.NONE, clock)
        assertNull(delay)
    }

    @Test fun `all delays are positive`() {
        val clock = clockAt(2026, 4, 27, 10, 0)
        listOf(
            DayOfWeekSet.EVERY_DAY,
            DayOfWeekSet.WEEKDAYS,
            DayOfWeekSet.WEEKENDS,
            DayOfWeekSet(0b1000000),
        ).forEach { mask ->
            val delay = scheduler.computeDelayToNextSync(6, 0, mask, clock)!!
            assertTrue("delay for $mask must be positive, got $delay", delay > 0)
        }
    }
}
```

The test instantiates `SyncScheduler` with `mockito-kotlin` mocks for both deps — neither is touched by the tested method, but mocks are safer than null-casts in case a future `init {}` ever reaches a field. `mockito-kotlin` is already a test-implementation dependency on this module.

- [ ] **Step 4: Run the new test — expect PASS**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.SyncSchedulerComputeTest" --console=plain
```

Expected: 7 tests, PASS.

- [ ] **Step 5: Update the two callers**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && grep -n "scheduleDailySync" core/data/src/main/kotlin/com/stash/core/data/sync/BootReceiver.kt feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt
```

In `BootReceiver.kt` (around line 38), find the existing call and change it to pass the persisted day bitmask:

```kotlin
syncScheduler.scheduleDailySync(
    hour = prefs.syncHour,
    minute = prefs.syncMinute,
    wifiOnly = prefs.wifiOnly,
    days = DayOfWeekSet(prefs.syncDays),
)
```

(Add `import com.stash.core.data.sync.DayOfWeekSet` at the top of the file if needed.)

In `SyncViewModel.kt` (around lines 245 and 258 — there are two call sites), make the same change:

```kotlin
syncScheduler.scheduleDailySync(
    hour = hour,
    minute = minute,
    wifiOnly = prefs.wifiOnly,
    days = DayOfWeekSet(prefs.syncDays),
)
```

If a call uses `prefs.wifiOnly` already, just append `days = DayOfWeekSet(prefs.syncDays)` as a new argument. If a call uses different parameter names, adapt. The default of `EVERY_DAY` on `scheduleDailySync` makes this back-compat — call sites that aren't updated still compile but never honor the user's day selection. **Both call sites must be updated.**

- [ ] **Step 6: Run the full `:core:data` and `:feature:sync` test/compile**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :core:data:testDebugUnitTest :feature:sync:compileDebugKotlin --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Verify the app still assembles**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :app:assembleDebug --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add core/data/src/main/kotlin/com/stash/core/data/sync/SyncScheduler.kt \
        core/data/src/main/kotlin/com/stash/core/data/sync/BootReceiver.kt \
        feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt \
        core/data/src/test/kotlin/com/stash/core/data/sync/SyncSchedulerComputeTest.kt
git commit -m "feat(sync): SyncScheduler honors days-of-week bitmask

computeDelayToNextSync now takes a DayOfWeekSet and a Clock; advances
day-by-day past disabled days, returns null on empty bitmask. Two
callers (BootReceiver, SyncViewModel) pass syncDays from preferences.
Refactored from Calendar to java.time for clock-injectable testing.
7 unit tests cover daily / weekdays / weekends / single-day / empty cases."
```

---

## Task 4: `SyncViewModel` exposes `syncDays`

**Verified facts:**
- The ViewModel already observes other prefs via `observeSyncPreferences()` (or whatever the existing observer function is named — confirm by grep).
- `_uiState.update { it.copy(...) }` is the established mutation pattern (per Task 4 of the studio-only filter plan, this was confirmed).

**Files:**
- Modify: `feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt`

- [ ] **Step 1: Add the field to `SyncUiState`**

After the `youtubeLikedStudioOnly: Boolean = false` field added by the studio-only feature, add:

```kotlin
/**
 * Days-of-week bitmask for the auto-sync schedule. Bit 0 = Mon … bit 6 = Sun.
 * Default 127 = every day.
 */
val syncDays: Int = 0b1111111,
```

- [ ] **Step 2: Add the setter**

```kotlin
/** Persists the days-of-week selection. UI passes the new bitmask. */
fun onSyncDaysChanged(bitmask: Int) {
    viewModelScope.launch {
        syncPreferencesManager.setSyncDays(bitmask)
    }
}
```

- [ ] **Step 3: Observe the new pref**

Inside the existing `observeSyncPreferences()` (or the equivalent observer, find via grep), add a parallel collect block:

```kotlin
viewModelScope.launch {
    syncPreferencesManager.syncDays.collect { bitmask ->
        _uiState.update { it.copy(syncDays = bitmask) }
    }
}
```

- [ ] **Step 4: Compile**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :feature:sync:compileDebugKotlin --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt
git commit -m "feat(sync): expose syncDays on SyncViewModel

Mirrors the youtubeLikedStudioOnly pattern: field on SyncUiState,
setter delegating to SyncPreferencesManager, observer in
observeSyncPreferences."
```

---

## Task 5: `SyncHeroCard` — gradient hero replacing the plain Sync Now button

**Verified facts:**
- The existing `SyncActionSection` (line ~687 of `SyncScreen.kt`) renders the Sync Now button + multi-phase progress widget. The Hero card absorbs both. Treat the inner progress widget as a black box: keep its phase rendering logic, just relocate it.
- `phaseLabel(phase: SyncPhase)` (line ~785) is part of the progress widget. Move it with the progress code.
- The current `SourceCard` Row at the top of `SyncScreen` (around line 111-136) is removed — connection state migrates to the new Source Preferences cards (Task 6).
- `GlassCard` exists at `core/ui/src/main/kotlin/com/stash/core/ui/components/GlassCard.kt` with default 16dp padding.
- `SyncHistoryEntity` (or whatever DB row type) drives the "last sync" stats — read it via the existing `viewModel.uiState` flow. If a `lastSync` field doesn't already exist on `SyncUiState`, add it (compute from the most-recent `syncHistory` list element).

**Files:**
- Create: `feature/sync/src/main/kotlin/com/stash/feature/sync/components/SyncHeroCard.kt`
- Modify: `feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt`
- Modify: `feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt` (only if a `lastSync` projection isn't already on `SyncUiState`)

- [ ] **Step 1: Read existing code to identify reusable bits**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && grep -n "SyncActionSection\|phaseLabel\|SyncPhase\|overallProgress" feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt | head -20
```

Identify: the function signature of `SyncActionSection`, the `SyncPhase` enum, the `overallProgress` field on `SyncUiState`, and where `phaseLabel` is defined.

- [ ] **Step 2: Create `SyncHeroCard.kt`**

```kotlin
package com.stash.feature.sync.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/**
 * Gradient-tinted hero card carrying last-sync metadata + the Sync Now button.
 * When a sync is running, the button is replaced by [progressContent] (typically
 * the multi-phase progress widget that used to live in SyncActionSection).
 *
 * @param lastSyncRelativeTime  e.g. "2 hours ago", "Yesterday", or "Never synced".
 * @param lastSyncTrackCount    Total tracks in the most recent sync, or null if never.
 * @param healthLabel           "healthy" / "partial" / "failed" — rendered as a small chip.
 * @param healthColor           Tint for the health chip (success / warning / error).
 * @param isSyncing             True while a sync is running.
 * @param onSyncNow             Tapped when isSyncing == false.
 * @param progressContent       Shown when isSyncing == true, in place of the button.
 */
@Composable
fun SyncHeroCard(
    lastSyncRelativeTime: String,
    lastSyncTrackCount: Int?,
    healthLabel: String,
    healthColor: androidx.compose.ui.graphics.Color,
    isSyncing: Boolean,
    onSyncNow: () -> Unit,
    progressContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val purple = MaterialTheme.colorScheme.primary
    val cyan = StashTheme.extendedColors.cyan
    val gradient = Brush.linearGradient(
        colors = listOf(
            purple.copy(alpha = 0.18f),
            cyan.copy(alpha = 0.08f),
        ),
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = androidx.compose.ui.graphics.Color.Transparent,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, purple.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .background(gradient, RoundedCornerShape(18.dp))
                .padding(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LAST SYNC",
                        style = MaterialTheme.typography.labelSmall,
                        color = StashTheme.extendedColors.purpleLight,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    val body = when {
                        lastSyncTrackCount == null -> "Never synced"
                        else -> "$lastSyncRelativeTime · $lastSyncTrackCount tracks"
                    }
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (lastSyncTrackCount != null) {
                    Text(
                        text = healthLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = healthColor,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            if (isSyncing) {
                progressContent()
            } else {
                Button(
                    onClick = onSyncNow,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = purple,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Sync Now",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 3: Wire `SyncHeroCard` into `SyncScreen`**

In `SyncScreen.kt`:

1. **Remove** the "Connected Sources" Text label and the `Row { SourceCard(Spotify); SourceCard(YouTube) }` (around lines 111-136). The connection chip moves into Source Preferences cards in Task 6.
2. **Replace** the `item { SyncActionSection(...) }` (around line 138-147) with:

```kotlin
item {
    SyncHeroCard(
        lastSyncRelativeTime = uiState.lastSyncRelativeTime,
        lastSyncTrackCount = uiState.lastSyncTrackCount,
        healthLabel = uiState.lastSyncHealthLabel,
        healthColor = uiState.lastSyncHealthColor,
        isSyncing = uiState.isSyncing,
        onSyncNow = viewModel::onSyncNow,
        progressContent = {
            SyncActionProgress(
                phase = uiState.syncPhase,
                progress = uiState.overallProgress,
                onStopSync = viewModel::onStopSync,
            )
        },
    )
}
```

3. **Extract** the inner progress widget. Take the old `SyncActionSection` body, drop the Sync Now button (the Hero handles that), and rename it `SyncActionProgress`. Move it to the bottom of `SyncScreen.kt` (or to a new `feature/sync/.../components/SyncActionProgress.kt` if it's >50 lines).
4. **Delete** the now-unused `SourceCard` composable (line ~651) — its sole callers were the removed top tiles.

- [ ] **Step 4: Add UI-state projections to `SyncViewModel`**

If `SyncUiState` doesn't already carry `lastSyncRelativeTime`, `lastSyncTrackCount`, `lastSyncHealthLabel`, `lastSyncHealthColor`, derive them from the existing sync-history flow inside the ViewModel:

```kotlin
// Inside SyncUiState — append fields:
val lastSyncRelativeTime: String = "",
val lastSyncTrackCount: Int? = null,
val lastSyncHealthLabel: String = "",
val lastSyncHealthColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
```

```kotlin
// Inside the ViewModel, derive from the existing syncHistory flow:
private fun observeLastSync() {
    viewModelScope.launch {
        // Adjust 'syncHistoryFlow' to whatever the current name is — find via grep.
        syncHistoryFlow.collect { history ->
            val latest = history.firstOrNull()
            _uiState.update { state ->
                state.copy(
                    lastSyncRelativeTime = latest?.let { formatRelativeTime(it.timestamp) } ?: "",
                    lastSyncTrackCount = latest?.totalTracks,
                    lastSyncHealthLabel = when {
                        latest == null -> ""
                        latest.errorMessage != null -> "× failed"
                        latest.partial -> "! partial"
                        else -> "✓ healthy"
                    },
                    lastSyncHealthColor = when {
                        latest == null -> androidx.compose.ui.graphics.Color.Transparent
                        latest.errorMessage != null -> /* StashError */ androidx.compose.ui.graphics.Color(0xFFEF4444)
                        latest.partial -> /* StashWarning */ androidx.compose.ui.graphics.Color(0xFFF59E0B)
                        else -> /* StashSuccess */ androidx.compose.ui.graphics.Color(0xFF10B981)
                    },
                )
            }
        }
    }
}
```

`formatRelativeTime` should already exist somewhere (the existing `SyncHistoryRow` formats timestamps). Reuse it. If it doesn't, write a small helper that returns `"just now"` / `"5 min ago"` / `"2h ago"` / `"Yesterday"` / `"Apr 26"`. Place it next to `SyncHistoryRow` in whatever utilities file currently houses date formatting.

Call `observeLastSync()` from the ViewModel's `init { ... }` block.

- [ ] **Step 5: Compile + assemble**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :feature:sync:compileDebugKotlin :app:assembleDebug --console=plain
```

Expected: BUILD SUCCESSFUL. Visual check (manual install) is deferred to Task 10.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add feature/sync/src/main/kotlin/com/stash/feature/sync/components/SyncHeroCard.kt \
        feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt \
        feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt
git commit -m "feat(sync): SyncHeroCard with last-sync stats + Sync Now button

Replaces the standalone Connected Sources tile row + SyncActionSection.
Connection state migrates into the upcoming Source Preferences cards.
The multi-phase progress widget is preserved and embedded inside the
hero when isSyncing == true."
```

---

## Task 6: `SourcePreferencesCard` + `StatusPill`

**Verified facts:**
- The existing Spotify and YouTube preference cards are in-line in `SyncScreen.kt` (around lines 173-380 and 380-540 respectively, give or take after Task 5's edits). Each is a custom `Surface` with collapsed/expanded states and per-section `SyncToggleRow` calls.
- The new card is a generic component parameterized by source name, brand color, status pills, and an `expandedContent` slot. Both Spotify and YouTube call sites pass their own pills + expanded-content.
- The expanded view's category-row design is described in the spec; the inside of each category row reuses the existing `SpotifySyncToggleRow` and `StudioOnlyToggleRow` composables already in the codebase. **Do not redesign those rows in this task.**

**Files:**
- Create: `feature/sync/src/main/kotlin/com/stash/feature/sync/components/StatusPill.kt`
- Create: `feature/sync/src/main/kotlin/com/stash/feature/sync/components/SourcePreferencesCard.kt`
- Modify: `feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt`

- [ ] **Step 1: Create `StatusPill.kt`**

```kotlin
package com.stash.feature.sync.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Small status pill used in source preferences card summaries.
 *
 * Three visual variants:
 * - Brand-tinted (provide [brandColor]): translucent brand color background, brand-color text.
 * - Purple-tinted (no brandColor, primary == true): translucent primary background, primary text.
 * - Muted (no brandColor, primary == false): faint white background, secondary text.
 */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    brandColor: Color? = null,
    primary: Boolean = false,
) {
    val (background, foreground, border) = when {
        brandColor != null -> Triple(brandColor.copy(alpha = 0.15f), brandColor, brandColor.copy(alpha = 0.0f))
        primary -> Triple(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
        )
        else -> Triple(
            Color.White.copy(alpha = 0.05f),
            MaterialTheme.colorScheme.onSurfaceVariant,
            Color.Transparent,
        )
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        modifier = modifier
            .background(background, RoundedCornerShape(999.dp))
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
```

- [ ] **Step 2: Create `SourcePreferencesCard.kt`**

```kotlin
package com.stash.feature.sync.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/**
 * Generic Source Preferences card. Used by both Spotify and YouTube.
 *
 * Collapsed: brand bar + name + connection chip + status-pill row + summary line.
 * Expanded: same header + [expandedContent] slot.
 *
 * @param name             "Spotify" / "YouTube Music"
 * @param brandColor       Brand bar color (spotifyGreen or youtubeRed)
 * @param connected        Whether the source is connected (green Connected chip vs red Disconnected)
 * @param statusPills      Up to 4-5 [StatusPill] composables. Caller decides content + tints.
 * @param summaryLine      e.g. "5 of 35 playlists · 1,247 tracks"
 * @param expandedContent  Body shown when the card is expanded.
 */
@Composable
fun SourcePreferencesCard(
    name: String,
    brandColor: Color,
    connected: Boolean,
    statusPills: @Composable () -> Unit,
    summaryLine: String,
    expandedContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val ec = StashTheme.extendedColors

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        color = ec.glassBackground,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, ec.glassBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(intrinsicSize = androidx.compose.foundation.layout.IntrinsicSize.Min)
                .animateContentSize(),
        ) {
            // Brand bar
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .padding(vertical = 14.dp)
                    .background(brandColor, RoundedCornerShape(3.dp)),
            )
            Column(modifier = Modifier.padding(14.dp).fillMaxWidth()) {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusPill(
                            text = if (connected) "Connected" else "Disconnected",
                            brandColor = if (connected) ec.success else androidx.compose.ui.graphics.Color(0xFFEF4444),
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (!expanded) {
                    // Status pills row
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        statusPills()
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = summaryLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    expandedContent()
                }
            }
        }
    }
}
```

- [ ] **Step 3: Replace the inline Spotify card in `SyncScreen.kt`**

Find the existing Spotify Source Preferences `item { ... }` block (around line 173 per the spec; find via grep `"Spotify Sync Preferences"`). Replace its body with:

```kotlin
SourcePreferencesCard(
    name = "Spotify",
    brandColor = StashTheme.extendedColors.spotifyGreen,
    connected = uiState.spotifyConnected,
    statusPills = {
        // Render the four pills based on uiState.spotifyPlaylists state and uiState.spotifySyncMode.
        // Liked / Mixes / Custom / Sync mode chip.
        // Caller decides which to show; helper functions can derive counts.
        SpotifySummaryPills(uiState)
    },
    summaryLine = uiState.spotifySummaryLine, // e.g. "5 of 35 playlists · 1,247 tracks"
    expandedContent = {
        SpotifyExpandedContent(uiState, viewModel)
    },
)
```

Where:
- `SpotifySummaryPills(uiState: SyncUiState)` is a small `@Composable` you extract to keep the call site readable. It calls `StatusPill` four times. Place it as a private `@Composable` at the bottom of `SyncScreen.kt` or in `SourcePreferencesCard.kt`.
- `SpotifyExpandedContent(uiState, viewModel)` wraps **the existing expanded body** that the original card had — `SyncModeChipRow` plus the per-category `SpotifySyncToggleRow` calls. **Do not redesign the inner toggle rows in this task.** Lift them as-is.
- `uiState.spotifySummaryLine` is a derived property; add it to `SyncUiState` and compute it in the ViewModel from `spotifyPlaylists` (count enabled / total + sum of `trackCount`).

- [ ] **Step 4: Replace the inline YouTube card the same way**

Same pattern. The YouTube version's `statusPills` includes the new "Liked · Studio only" combined chip when both Liked sync and the studio-only filter are on. Pass `uiState.youtubeLikedStudioOnly` to a `YoutubeSummaryPills` helper.

The expanded body for YouTube includes the existing `StudioOnlyToggleRow` from the studio-only filter feature — lift it unchanged.

- [ ] **Step 5: Compile + assemble**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :feature:sync:compileDebugKotlin :app:assembleDebug --console=plain
```

Expected: BUILD SUCCESSFUL. The cards now look new but expand to the same content.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add feature/sync/src/main/kotlin/com/stash/feature/sync/components/StatusPill.kt \
        feature/sync/src/main/kotlin/com/stash/feature/sync/components/SourcePreferencesCard.kt \
        feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt \
        feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt
git commit -m "feat(sync): SourcePreferencesCard with brand bar + status pills

New generic Source card composable used for both Spotify and YouTube.
Collapsed: brand bar + name + Connected chip + 4 status pills + summary
line. Expanded: existing per-category toggle content lifted unchanged.
Connection state moves here from the deleted top-tile row."
```

---

## Task 7: New `ScheduleCard` with `DayOfWeekPanel` + `SyncTimeBottomSheet`

**Verified facts:**
- The existing `ScheduleCard` is at `SyncScreen.kt:799-924` and uses a `Button` → `Dialog(TimePicker)` flow. The new card is the natural-language sentence pattern from the brainstorm.
- `ModalBottomSheet` is in `androidx.compose.material3` (stable). It accepts content as a slot — wrap the existing `TimePicker` in it.
- The day-toggle UI must allow zero-state: deselecting all 7 days yields an empty bitmask, which the schedule card surfaces as an error sentence ("Not scheduled — pick at least one day").

**Files:**
- Create: `feature/sync/src/main/kotlin/com/stash/feature/sync/components/DayOfWeekPanel.kt`
- Create: `feature/sync/src/main/kotlin/com/stash/feature/sync/components/SyncTimeBottomSheet.kt`
- Create: `feature/sync/src/main/kotlin/com/stash/feature/sync/components/ScheduleCard.kt`
- Modify: `feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt` (replace old `ScheduleCard` call site)

- [ ] **Step 1: Create `DayOfWeekPanel.kt`**

```kotlin
package com.stash.feature.sync.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stash.core.data.sync.DayOfWeekSet
import com.stash.core.ui.theme.StashTheme
import java.time.DayOfWeek

@Composable
fun DayOfWeekPanel(
    selection: DayOfWeekSet,
    onSelectionChanged: (DayOfWeekSet) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ec = StashTheme.extendedColors
    val purple = MaterialTheme.colorScheme.primary

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = purple.copy(alpha = 0.06f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, purple.copy(alpha = 0.25f)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "REPEAT ON",
                style = MaterialTheme.typography.labelSmall,
                color = StashTheme.extendedColors.purpleLight,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                DayOfWeek.values().forEach { day ->
                    DayCircle(
                        label = day.shortLabel(),
                        on = selection.contains(day),
                        onToggle = { newOn -> onSelectionChanged(selection.with(day, newOn)) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PresetChip("Daily", selection.isDaily) { onSelectionChanged(DayOfWeekSet.EVERY_DAY) }
                PresetChip("Weekdays", selection.isWeekdays) { onSelectionChanged(DayOfWeekSet.WEEKDAYS) }
                PresetChip("Weekends", selection.isWeekends) { onSelectionChanged(DayOfWeekSet.WEEKENDS) }
            }
        }
    }
}

@Composable
private fun DayCircle(label: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    val purple = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(
                color = if (on) purple else Color.White.copy(alpha = 0.04f),
                shape = CircleShape,
            )
            .border(
                width = 1.dp,
                color = if (on) purple else Color.White.copy(alpha = 0.08f),
                shape = CircleShape,
            )
            .clickable { onToggle(!on) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val purple = MaterialTheme.colorScheme.primary
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) purple else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                if (selected) purple.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f),
                RoundedCornerShape(999.dp),
            )
            .border(
                1.dp,
                if (selected) purple.copy(alpha = 0.4f) else Color.Transparent,
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

private fun DayOfWeek.shortLabel(): String = when (this) {
    DayOfWeek.MONDAY -> "M"
    DayOfWeek.TUESDAY -> "T"
    DayOfWeek.WEDNESDAY -> "W"
    DayOfWeek.THURSDAY -> "T"
    DayOfWeek.FRIDAY -> "F"
    DayOfWeek.SATURDAY -> "S"
    DayOfWeek.SUNDAY -> "S"
}
```

- [ ] **Step 2: Create `SyncTimeBottomSheet.kt`**

```kotlin
package com.stash.feature.sync.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * ModalBottomSheet wrapper around Material 3 TimePicker. Replaces the old
 * Dialog-based TimePicker.
 *
 * If the sheet is dismissed without confirming, no state change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncTimeBottomSheet(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val timeState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false,
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Sync time",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                TimePicker(
                    state = timeState,
                    colors = TimePickerDefaults.colors(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = {
                    onConfirm(timeState.hour, timeState.minute)
                }) {
                    Text("Set")
                }
            }
        }
    }
}
```

- [ ] **Step 3: Create `ScheduleCard.kt`**

```kotlin
package com.stash.feature.sync.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stash.core.data.sync.DayOfWeekSet
import com.stash.core.ui.components.GlassCard

@Composable
fun ScheduleCard(
    autoSyncEnabled: Boolean,
    syncDays: DayOfWeekSet,
    syncHour: Int,
    syncMinute: Int,
    wifiOnly: Boolean,
    onToggleAutoSync: () -> Unit,
    onSyncDaysChanged: (DayOfWeekSet) -> Unit,
    onTimeChanged: (Int, Int) -> Unit,
    onToggleWifiOnly: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var daysExpanded by remember { mutableStateOf(false) }
    var showTimeSheet by remember { mutableStateOf(false) }
    val emptyDays = syncDays.isEmpty
    val purple = MaterialTheme.colorScheme.primary
    val errorColor = androidx.compose.ui.graphics.Color(0xFFEF4444)

    GlassCard(modifier = modifier) {
        Column(modifier = Modifier.animateContentSize()) {
            // Auto-sync row — never dimmed, so users can always re-enable
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-sync", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = autoSyncEnabled,
                    onCheckedChange = { onToggleAutoSync() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = purple,
                    ),
                )
            }
            Spacer(Modifier.height(12.dp))
            // Everything below dims when auto-sync is off (sentence, days panel, hint)
            Column(modifier = Modifier.alpha(if (autoSyncEnabled) 1f else 0.5f)) {

            // Sentence
            if (emptyDays) {
                Text(
                    text = "Not scheduled — pick at least one day",
                    style = MaterialTheme.typography.bodyMedium,
                    color = errorColor,
                    modifier = Modifier.clickable { daysExpanded = true },
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sync ", style = MaterialTheme.typography.bodyMedium)
                    ScheduleChip(
                        text = syncDays.presetLabel(),
                        active = daysExpanded,
                        onClick = { daysExpanded = !daysExpanded },
                    )
                    Text(" at ", style = MaterialTheme.typography.bodyMedium)
                    ScheduleChip(
                        text = formatTime(syncHour, syncMinute),
                        active = false,
                        onClick = { showTimeSheet = true },
                    )
                    Text(" on ", style = MaterialTheme.typography.bodyMedium)
                    ScheduleChip(
                        text = if (wifiOnly) "Wi-Fi only" else "any network",
                        active = false,
                        onClick = { onToggleWifiOnly() },
                        muted = true,
                    )
                }
            }

            // Inline days panel
            if (daysExpanded) {
                Spacer(Modifier.height(10.dp))
                DayOfWeekPanel(
                    selection = syncDays,
                    onSelectionChanged = onSyncDaysChanged,
                )
            }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Tap any chip to change",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } // end of dimmed Column
        }
    }

    if (showTimeSheet) {
        SyncTimeBottomSheet(
            initialHour = syncHour,
            initialMinute = syncMinute,
            onConfirm = { hour, minute ->
                onTimeChanged(hour, minute)
                showTimeSheet = false
            },
            onDismiss = { showTimeSheet = false },
        )
    }
}

@Composable
private fun ScheduleChip(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    muted: Boolean = false,
) {
    val purple = MaterialTheme.colorScheme.primary
    val (bg, fg, border) = when {
        muted -> Triple(Color.White.copy(alpha = 0.05f), MaterialTheme.colorScheme.onSurfaceVariant, Color.Transparent)
        active -> Triple(purple.copy(alpha = 0.28f), purple, purple.copy(alpha = 0.55f))
        else -> Triple(purple.copy(alpha = 0.18f), purple, purple.copy(alpha = 0.35f))
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = fg,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(bg, RoundedCornerShape(999.dp))
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

private fun formatTime(hour: Int, minute: Int): String {
    val period = if (hour < 12) "AM" else "PM"
    val display = when (hour) {
        0 -> 12
        in 13..23 -> hour - 12
        else -> hour
    }
    return String.format("%d:%02d %s", display, minute, period)
}
```

- [ ] **Step 4: Replace the inline `ScheduleCard` call in `SyncScreen.kt`**

Find the existing `ScheduleCard(...)` invocation (around line 491 — search for `ScheduleCard`). Replace its call signature with the new component:

```kotlin
ScheduleCard(
    autoSyncEnabled = uiState.syncPreferences.autoSyncEnabled,
    syncDays = DayOfWeekSet(uiState.syncDays),
    syncHour = uiState.syncPreferences.syncHour,
    syncMinute = uiState.syncPreferences.syncMinute,
    wifiOnly = uiState.syncPreferences.wifiOnly,
    onToggleAutoSync = viewModel::onToggleAutoSync,
    onSyncDaysChanged = { viewModel.onSyncDaysChanged(it.bitmask) },
    onTimeChanged = viewModel::onTimeSelected,
    onToggleWifiOnly = viewModel::onToggleWifiOnly,
)
```

(Adjust to whatever the existing setter names are for the auto-sync, time, and wifi-only toggles. Find via grep.)

- [ ] **Step 5: Delete the old `ScheduleCard` private composable** (around line 800) and the old `TimePickerDialog` (around line 928). Both are replaced by the new components.

- [ ] **Step 6: Compile + assemble**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :feature:sync:compileDebugKotlin :app:assembleDebug --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add feature/sync/src/main/kotlin/com/stash/feature/sync/components/DayOfWeekPanel.kt \
        feature/sync/src/main/kotlin/com/stash/feature/sync/components/SyncTimeBottomSheet.kt \
        feature/sync/src/main/kotlin/com/stash/feature/sync/components/ScheduleCard.kt \
        feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt
git commit -m "feat(sync): natural-language ScheduleCard with day picker + bottom-sheet TimePicker

Replaces the Dialog-based time button with a sentence pattern:
'Sync <days> at <time> on <network>'. Each underlined chunk is a tappable
chip. Days chip pulls open an inline DayOfWeekPanel (7 day-circles +
Daily/Weekdays/Weekends presets). Time chip opens a ModalBottomSheet
TimePicker. Empty bitmask surfaces 'Not scheduled — pick at least one day'."
```

---

## Task 8: `RecentSyncsCard` consolidation

**Verified facts:**
- Today, each `SyncHistoryRow` (line ~975) renders inside its own GlassCard via the LazyColumn `items(uiState.syncHistory) { ... }` block (line ~536).
- The redesign consolidates them into a single GlassCard with internal dividers.

**Files:**
- Create: `feature/sync/src/main/kotlin/com/stash/feature/sync/components/RecentSyncsCard.kt`
- Modify: `feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt`

- [ ] **Step 1: Create `RecentSyncsCard.kt`**

```kotlin
package com.stash.feature.sync.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.theme.StashTheme

/**
 * Caller adapts whatever its own SyncHistoryInfo / SyncHistoryEntity shape is.
 * Pass a list of pre-shaped rows so this component stays decoupled from DB types.
 */
data class RecentSyncRow(
    val id: Long,
    val timestamp: String,        // "Today, 6:02 AM"
    val summary: String,          // "35 playlists · 1,821 tracks"
    val status: SyncRowStatus,
    val onClick: () -> Unit,
)

enum class SyncRowStatus { HEALTHY, PARTIAL, FAILED }

@Composable
fun RecentSyncsCard(rows: List<RecentSyncRow>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return  // section is hidden on empty
    GlassCard(modifier = modifier) {
        Column {
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.05f)),
                    )
                }
                RecentSyncRowItem(row)
            }
        }
    }
}

@Composable
private fun RecentSyncRowItem(row: RecentSyncRow) {
    val ec = StashTheme.extendedColors
    val (dotColor, marker) = when (row.status) {
        SyncRowStatus.HEALTHY -> ec.success to "✓"
        SyncRowStatus.PARTIAL -> ec.warning to "!"
        SyncRowStatus.FAILED -> Color(0xFFEF4444) to "×"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = row.onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape),
        )
        Spacer(Modifier.padding(horizontal = 5.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(row.timestamp, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = row.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(marker, style = MaterialTheme.typography.titleMedium, color = dotColor)
    }
}
```

- [ ] **Step 2: Replace the `items(uiState.syncHistory)` loop in `SyncScreen.kt`**

Find the `LazyColumn` items block that calls `SyncHistoryRow(sync)` (around line 536). Replace with a single `item { ... }` that maps `uiState.syncHistory` to `RecentSyncRow`s and passes them to `RecentSyncsCard`:

```kotlin
item {
    val rows = uiState.syncHistory.map { sync ->
        RecentSyncRow(
            id = sync.id,
            timestamp = formatRelativeTime(sync.startedAt), // reuse from existing SyncHistoryRow
            summary = "${sync.playlistCount} playlists · ${sync.trackCount} tracks", // adapt names
            status = when {
                sync.errorMessage != null -> SyncRowStatus.FAILED
                sync.partial -> SyncRowStatus.PARTIAL
                else -> SyncRowStatus.HEALTHY
            },
            onClick = { /* preserve whatever the existing SyncHistoryRow did, e.g. nav to detail */ },
        )
    }
    RecentSyncsCard(rows)
}
```

(Adjust field names — find via grep `SyncHistoryInfo` to see the actual model.)

- [ ] **Step 3: Delete the old `SyncHistoryRow` private composable** (line ~976) — it's no longer referenced.

- [ ] **Step 4: Compile + assemble**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :feature:sync:compileDebugKotlin :app:assembleDebug --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add feature/sync/src/main/kotlin/com/stash/feature/sync/components/RecentSyncsCard.kt \
        feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt
git commit -m "feat(sync): RecentSyncsCard consolidates per-row cards into one card

Single GlassCard with internal dividers. Each row: leading colored dot,
timestamp, summary line, trailing status mark (✓/!/×). Hidden when empty."
```

---

## Task 9: Section labels + Library Maintenance polish

**Verified facts:**
- The new design uses small uppercase section labels (`SOURCES`, `SCHEDULE`, `RECENT SYNCS`, `LIBRARY`) between cards. These are plain `Text` composables, no new component needed.
- `LibraryMaintenanceCard` (line ~554) keeps its functionality. The redesign tightens its row layout.

**Files:**
- Modify: `feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt`

- [ ] **Step 1: Add the section labels**

Insert section-label `item { ... }` blocks immediately before each major section in the `LazyColumn`:

```kotlin
item { SyncSectionLabel("Sources") }
// ... source preferences items ...

item { SyncSectionLabel("Schedule") }
// ... schedule item ...

item { SyncSectionLabel("Recent syncs") }
// ... recent syncs item ...

item { SyncSectionLabel("Library") }
// ... library maintenance item ...
```

Define `SyncSectionLabel` as a private composable in `SyncScreen.kt`:

```kotlin
@Composable
private fun SyncSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.7.sp,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}
```

- [ ] **Step 2: Tighten `LibraryMaintenanceCard` rows (optional polish)**

Read the existing `LibraryMaintenanceCard` (line ~554). For each action row, ensure the layout is: leading `Icon` + `Column` (title + subtitle) + trailing pill button. If the rows already match the spec's "tighter row" ideal, no change needed — note this in the commit message.

If polishing: replace large `Button(onClick = ...) { Text(...) }` action buttons with smaller chip-style buttons:

```kotlin
Box(
    modifier = Modifier
        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
        .clickable { /* existing onClick */ }
        .padding(horizontal = 12.dp, vertical = 6.dp),
) {
    Text("Run", style = MaterialTheme.typography.labelLarge)
}
```

- [ ] **Step 3: Compile + assemble**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :feature:sync:compileDebugKotlin :app:assembleDebug --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt
git commit -m "feat(sync): section labels + Library Maintenance row polish"
```

---

## Task 10: Manual on-device verification

**Verified facts:**
- This redesign is testable only end-to-end on a real device. The unit tests cover `DayOfWeekSet` and the day-advance scheduling math; everything else is visual.
- The branch already has the studio-only filter manual verification (Task 7 of the prior plan) pending. This redesign does not block that — both can be verified in the same session.

**Files:** none (manual).

- [ ] **Step 1: Build and install**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :app:installDebug --console=plain
```

Expected: BUILD SUCCESSFUL, "Installed on 1 device."

- [ ] **Step 2: Visual walk-through**

Open the Stash debug build. On the Sync tab, confirm:

1. **Header reads "Sync"** at the top.
2. **No "Connected Sources" tile row at the top** (the two small tiles that used to appear above the Sync Now button are gone).
3. **Hero card visible** with a gradient (purple → cyan tint), `LAST SYNC` eyebrow, body line `"<time> · <count> tracks"`, health indicator on the right (green `✓ healthy` if the most recent sync succeeded), and a full-width purple `Sync Now` button.
4. **Sources section** with the new card style: brand bar on the left (green for Spotify, red for YouTube), `Connected` chip in the header, four status pills, summary line. Tap a card to confirm it expands inline; the existing per-category toggle content appears.
5. **Schedule section** with the natural-language sentence: `"Sync <days> at <time> on <network>"`. Auto-sync switch on top.
   - Tap the days chip → inline panel opens with M T W T F S S circles + Daily/Weekdays/Weekends presets.
   - Tap individual day circles → bitmask updates, sentence label updates (`weekdays`, `daily`, `Mon · Wed · Fri`, etc.).
   - Deselect all 7 days → sentence becomes red `"Not scheduled — pick at least one day"`.
   - Tap the time chip → bottom sheet rises with TimePicker. Pick a new time, tap Set, confirm sentence updates.
   - Tap the Wi-Fi chip → label cycles between `Wi-Fi only` / `any network`.
6. **Recent syncs section** is a single GlassCard with rows separated by thin dividers.
7. **Library section** has `LibraryMaintenanceCard` with the tightened row layout (if Task 9 polish was applied).

- [ ] **Step 3: Verify days-of-week persistence**

Set the schedule to weekdays only. Force-stop the app (`adb shell am force-stop com.stash.app.debug`), relaunch, navigate to Sync. Confirm the days chip still says `weekdays`.

- [ ] **Step 4: Verify scheduling honors days**

Set the schedule to Sundays only at a near-future time (e.g. 2 minutes from now if today is a Sunday, otherwise pick a Sunday hour:minute). Read the WorkManager state via:

```bash
adb shell dumpsys jobscheduler | grep -A5 stash
```

Or simpler: set the schedule to a far-future Sunday-only, force-stop, relaunch, dump the DataStore-backed sync_preferences:

```bash
adb exec-out run-as com.stash.app.debug cat files/datastore/sync_preferences.preferences_pb 2>&1 | xxd | head -20
```

Confirm the bitmask `0b1000000 = 64` is encoded (look for the `sync_days` int with value 64).

- [ ] **Step 5: Verify the empty-bitmask state stops scheduling**

Toggle all 7 days off. Confirm the sentence shows the red `Not scheduled` message. Trigger a Sync Now manually — confirm it still runs (manual sync ignores the schedule).

Optionally, advance the device clock past the original scheduled time and confirm no auto-sync fires. (This is optional and skippable on a normal user device.)

- [ ] **Step 6: Verify nothing else regressed**

- Sync Now still works.
- Spotify and YouTube preferences expand and the existing per-category toggles still flip individual playlists.
- Studio-only toggle inside the YouTube expanded view is still present and still functions (last manual verification of the prior plan).
- Sync history still navigates to detail view on row tap.
- Library Maintenance Cleanup row still triggers cleanup.

---

## Closing

After Task 10, the branch `feat/yt-sync-pagination` carries:

- The full pagination overhaul (commits `0030379..afdc679`)
- The LM endpoint fix
- The studio-only filter (5 commits)
- This redesign (10 commits, one per task above)

That's a substantial branch. Use `superpowers:finishing-a-development-branch` to merge to master, or split the branch with `git switch -c feat/sync-tab-redesign HEAD~10 ; git switch feat/yt-sync-pagination ; git reset --hard HEAD~10` (fish out the redesign onto its own PR if you want a cleaner reviewable history). Either approach lands the same code.
