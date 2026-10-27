package com.stash.feature.record.lastfm

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.lastfm.recap.LastFmSongLength
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.RecordFlags
import com.stash.feature.record.imports.RecordWrites
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

/**
 * The lengths pass: a slice stopped, the service's runs and what the screens see of them, its limits (three failures in a row,
 * the daily cap, a rate limit), and its reader. Busy waits are real (in the test's time) and the clock moves with them: a frozen
 * clock with no busy wait is what hid a streak that never reached three.
 */
class RecordLengthsTest {
    private val t0 = 1_714_564_800L
    private val nowS = 1_800_000_000L

    /** [n] guessed scrobbles of [songs] songs ("Song 0"…), as the import writes them with nothing known. */
    private fun guessed(n: Int, songs: Int = n): List<ArchivePlay> = (0 until n).map { i ->
        val sc = Scrobble("Song ${i % songs}", "Band", uts = t0 + i * 60L)
        ArchivePlay(id = Scrobbles.id(sc), t = sc.t, a = sc.a, s = sc.uts * 1000, ms = 210_000, f = RecordFlags.LASTFM or RecordFlags.ESTIMATED)
    }

    // ── a slice stopped ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `stopped before it began, a slice reads and writes nothing`() = runTest {
        val store = MemoryLengthsStore(guessed(3))
        val asked = mutableListOf<String>()
        var reached = false
        val job = launch {
            cancel() // the pass stopped while it waited for the lock
            Lengths.fill(store, { _, t -> asked += t; LengthAnswer(200_000) }, nowS, gapMs = 0)
            reached = true
        }
        job.join()
        assertThat(job.isCancelled).isTrue()
        assertThat(reached).isFalse()
        assertThat(asked).isEmpty()
        assertThat(store.saves).isEmpty()
    }

    @Test fun `stopped part way, what it learnt is written and the stop goes on`() = runTest {
        val store = MemoryLengthsStore(guessed(4))
        val gate = CompletableDeferred<Unit>()
        val asked = mutableListOf<String>()
        val timed = mutableListOf<Int>()
        val look = LengthReader { _, t ->
            asked += t
            when (t) {
                "Song 0" -> LengthAnswer(200_000)
                "Song 1" -> LengthAnswer(null)
                else -> gate.await().let { LengthAnswer(1) }
            }
        }
        val job = launch { Lengths.fill(store, look, nowS, gapMs = 0, onTimed = { timed += it }) }
        runCurrent()
        assertThat(asked).containsExactly("Song 0", "Song 1", "Song 2").inOrder()
        // Nothing written yet: a slice writes once, at its end
        assertThat(store.saves).isEmpty()
        job.cancel()
        advanceUntilIdle()
        assertThat(store.saves).containsExactly(LengthsMark(none = mapOf(Scrobbles.songKey("Song 1", "Band") to nowS), timed = 1, at = nowS))
        assertThat(store.rows.values.count { it.f and RecordFlags.ESTIMATED == 0 }).isEqualTo(1)
        assertThat(timed).containsExactly(1)
    }

    @Test fun `a slice holds the lock only so long - the next ask waits for the next slice`() = runTest {
        val store = MemoryLengthsStore(guessed(3))
        var clock = 0L
        val look = LengthReader { _, _ -> clock += 15_000; LengthAnswer(200_000) }
        val run = Lengths.fill(store, look, nowS, maxMs = 20_000, gapMs = 0, clock = { clock })
        assertThat(run).isEqualTo(LengthsRun(rows = 2, asked = 2, left = 1))
    }

    @Test fun `a song whose rows aren't there when written is marked as having no length, never left at the front`() = runTest {
        val store = object : LengthsStore by MemoryLengthsStore(guessed(2)) {
            override suspend fun time(songs: List<Pair<List<GuessedSpelling>, Long>>): List<Int> = songs.map { 0 }
            var last: LengthsMark? = null
            override suspend fun setMark(mark: LengthsMark) {
                last = mark
            }
        }
        val run = Lengths.fill(store, { _, _ -> LengthAnswer(200_000) }, nowS, gapMs = 0)
        assertThat(run.rows).isEqualTo(0)
        assertThat(store.last!!.none.keys).containsExactly(Scrobbles.songKey("Song 0", "Band"), Scrobbles.songKey("Song 1", "Band"))
    }

    // ── the service ─────────────────────────────────────────────────────────────────────────────────────────────────

    private inner class Harness(
        val test: TestScope,
        rows: List<ArchivePlay>,
        val look: LengthReader,
        val budget: LookupBudget? = null,
        busyMs: Long = 0,
        clock: (() -> Long)? = null,
        /** What the library times (no lookup). */
        library: Map<String, Long> = emptyMap(),
        /** The Wi-Fi-only setting, as the app's sync preferences give it. */
        wifiOnly: Flow<Boolean> = emptyFlow(),
    ) {
        val store = CountingStore(MemoryLengthsStore(rows))
        val writes = RecordWrites()
        var clock = 0L
        var records = 0
        var changes = 0
        var launches = 0
        var cancels = 0
        val wakes = mutableListOf<Long>()
        /** The waits for a network asked for, by the Wi-Fi-only setting each, and their delays. */
        val networkWaits = mutableListOf<Boolean>()
        val networkDelays = mutableListOf<Long>()
        /** What the screens saw at each change: whether the card was held, and how many times the pass had shown its songs. */
        val seen = mutableListOf<Pair<Boolean, Int>>()
        /** Called as the screens are told of a change (a Stop arriving then, say). */
        var onChanged: () -> Unit = {}
        val lengths: RecordLengths = RecordLengths(
            newStore = { store },
            reader = look,
            library = { library },
            writes = writes,
            recordAfter = { records++ },
            changed = { changes++; seen += writes.lengthsRunning.value to writes.lengthsShown.value; onChanged() },
            launch = { launches++ },
            cancelJob = { cancels++ },
            wakeAt = { wakes += it },
            waitForNetwork = { wifiOnly, delayMs -> networkWaits += wifiOnly; networkDelays += delayMs },
            wifiOnly = wifiOnly,
            budget = budget,
            now = { nowS * 1000 + test.testScheduler.currentTime },
            clock = clock ?: { this.clock },
            gapMs = 0,
            busyMs = busyMs,
        )
    }

    /** [MemoryLengthsStore], counting the plans read from it. */
    private class CountingStore(val memory: MemoryLengthsStore) : LengthsStore by memory {
        var plans = 0

        override suspend fun guessed(): List<GuessedSpelling> = memory.guessed().also { plans++ }

        val rows get() = memory.rows
        val mark get() = memory.mark
        val saves get() = memory.saves
    }

    @Test fun `the pass times every song a slice at a time from one plan, shows them, and lets the card go`() = runTest {
        val h = Harness(this, guessed(450, songs = 450), { _, _ -> LengthAnswer(200_000) })
        h.lengths.start()
        assertThat(h.launches).isEqualTo(1)
        h.lengths.run()
        // 450 songs: slices of 200, 200 and 50, planned once; the first shown at once, the end shown again (the clock never moved)
        assertThat(h.store.plans).isEqualTo(1)
        assertThat(h.store.saves).hasSize(3)
        assertThat(h.store.rows.values.none { it.f and RecordFlags.ESTIMATED != 0 }).isTrue()
        assertThat(h.records).isEqualTo(2)
        assertThat(h.seen).containsExactly(true to 1, true to 2).inOrder()
        assertThat(h.writes.lengthsRunning.value).isFalse()
        assertThat(h.store.mark!!.timed).isEqualTo(450)
        assertThat(h.wakes).isEmpty()
    }

    @Test fun `while the pass runs the hours are worked out again at most every two minutes, and at its end`() = runTest {
        val h = Harness(this, guessed(1000, songs = 1000), { _, _ -> LengthAnswer(200_000) })
        // Each ask takes 30 s of the clock: a slice ends after 20 s, so one song a slice
        val slow = RecordLengths(
            newStore = { h.store }, reader = { a, t -> h.clock += 30_000; h.look.look(a, t) }, library = { emptyMap() }, writes = h.writes,
            recordAfter = { h.records++ }, changed = { h.changes++ }, launch = {}, cancelJob = {}, now = { nowS * 1000 }, clock = { h.clock },
            gapMs = 0, busyMs = 0,
        )
        slow.run()
        // Shown at the first slice that timed songs, then once 2 minutes have passed (every 4th slice of 30 s), and at the end
        assertThat(h.store.rows.values.count { it.f and RecordFlags.ESTIMATED == 0 }).isEqualTo(1000)
        assertThat(h.records).isEqualTo(1 + 999 / 4 + 1)
        assertThat(h.changes).isEqualTo(h.records)
    }

    @Test fun `three songs failing in a row end the run, however the slices fall - real busy waits, a clock that moves`() = runTest {
        val asked = mutableListOf<String>()
        // Last.fm down: every ask busy. A busy song waits 10 s (the test's time) before its second try; slices end after 20 s
        val h = Harness(
            this, guessed(10), { _, t -> asked += t; throw LastfmError(LastfmFailure.BUSY) },
            busyMs = ImportScrobbles.BUSY_WAIT_MS, clock = { testScheduler.currentTime },
        )
        h.lengths.run()
        assertThat(asked.toSet()).hasSize(Lengths.FAILS_IN_A_ROW)
        assertThat(asked).hasSize(Lengths.FAILS_IN_A_ROW * Lengths.LENGTH_TRIES)
        assertThat(h.store.mark!!.failed).hasSize(Lengths.FAILS_IN_A_ROW)
        // The run is over: it doesn't come back by itself (the next Last.fm run or import starts it)
        assertThat(h.wakes).isEmpty()
        assertThat(h.records).isEqualTo(0)
    }

    @Test fun `a writer stops the pass and waits for it to let go of the lock, and the record reads what it timed`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(this, guessed(3), { _, t -> if (t == "Song 1") gate.await(); LengthAnswer(200_000) })
        val pass = launch { h.lengths.run() }
        runCurrent()
        assertThat(h.writes.mutex.isLocked).isTrue()
        assertThat(h.writes.lengthsRunning.value).isTrue()
        val paused = async { h.lengths.pause() }
        runCurrent()
        assertThat(paused.await()).isTrue()
        pass.join()
        assertThat(h.writes.mutex.isLocked).isFalse()
        assertThat(h.cancels).isEqualTo(1)
        // Song 0 was timed in the slice it stopped: written on the way out, the record told (the card let go)
        assertThat(h.seen).containsExactly(false to 0)
        assertThat(h.records).isEqualTo(0)
        assertThat(h.store.mark!!.timed).isEqualTo(1)
        // Nothing running: a pause says so
        assertThat(h.lengths.pause()).isFalse()
    }

    @Test fun `the person's Stop ends the pass and nothing runs again by itself`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(this, guessed(3), { _, t -> if (t == "Song 1") gate.await(); LengthAnswer(200_000) })
        val pass = launch { h.lengths.run() }
        runCurrent()
        h.lengths.stop()
        pass.join()
        assertThat(h.cancels).isEqualTo(1)
        assertThat(h.launches).isEqualTo(0)
        assertThat(h.wakes).isEmpty()
        assertThat(h.writes.mutex.isLocked).isFalse()
    }

    @Test fun `a pass stopped while it waits for the lock reads and writes nothing`() = runTest {
        val h = Harness(this, guessed(3), { _, _ -> LengthAnswer(200_000) })
        val writer = CompletableDeferred<Unit>()
        val holder = launch { h.writes.mutex.withLock { writer.await() } }
        runCurrent()
        val pass = launch { h.lengths.run() }
        runCurrent()
        h.lengths.stop()
        writer.complete(Unit)
        holder.join()
        pass.join()
        assertThat(h.store.plans).isEqualTo(0)
        assertThat(h.store.saves).isEmpty()
        assertThat(h.records).isEqualTo(0)
        assertThat(h.changes).isEqualTo(0)
    }

    @Test fun `a run stopped but still unwinding never lets the card go under the run after it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var first = true
        val h = Harness(this, guessed(3), { _, _ ->
            if (first) {
                first = false
                // An ask that takes its time to end, stop or not
                withContext(NonCancellable) { gate.await() }
            }
            CompletableDeferred<Unit>().await()
            LengthAnswer(200_000)
        })
        val old = launch { h.lengths.run() }
        runCurrent()
        h.lengths.stop()
        // A new run begins while the old one is still in its ask
        val next = launch { h.lengths.run() }
        runCurrent()
        assertThat(h.writes.lengthsRunning.value).isTrue()
        gate.complete(Unit)
        old.join()
        runCurrent()
        // The old run ended: the card is still held for the new one
        assertThat(h.writes.lengthsRunning.value).isTrue()
        next.cancel()
        advanceUntilIdle()
        assertThat(h.writes.lengthsRunning.value).isFalse()
    }

    // ── the day's lookups, and rate limits ───────────────────────────────────────────────────────────────────────────

    private class Saved(var value: String? = null)

    @Test fun `the daily cap holds the pass, which comes back when the day frees up, most played first`() = runTest {
        val saved = Saved()
        val budget = LookupBudget(5, load = { saved.value }, save = { saved.value = it })
        // Ten songs, the first five played more
        val rows = guessed(15, songs = 10)
        val asked = mutableListOf<String>()
        val inner = LengthReader { _, t -> asked += t; LengthAnswer(200_000, hit = false) }
        val nowMs = { nowS * 1000 + testScheduler.currentTime }
        val h = Harness(this, rows, BudgetedLengthReader(inner, budget, now = nowMs), budget = budget)
        h.lengths.run()
        assertThat(asked).hasSize(5)
        assertThat(asked.take(5).toSet()).containsExactly("Song 0", "Song 1", "Song 2", "Song 3", "Song 4")
        assertThat(h.wakes).hasSize(1)
        assertThat(h.wakes.single()).isGreaterThan(nowMs() + 23 * 3_600_000L)
        assertThat(h.wakes.single()).isAtMost(nowMs() + 25 * 3_600_000L)
        // Kept: a run started before then asks nothing and waits again, but still plans and times what needs no lookup (the
        // library knows a song now)
        val known = Scrobbles.songKey("Song 9", "Band")
        val again = Harness(
            this, rows, BudgetedLengthReader(inner, LookupBudget(5, { saved.value }, { saved.value = it }), now = nowMs),
            budget = LookupBudget(5, { saved.value }, { saved.value = it }), library = mapOf(known to 200_000L),
        )
        again.lengths.run()
        assertThat(again.store.plans).isEqualTo(1)
        assertThat(asked).hasSize(5)
        assertThat(again.wakes).hasSize(1)
        assertThat(again.store.rows.values.filter { it.t == "Song 9" }.map { it.f and RecordFlags.ESTIMATED }).containsExactly(0)
        assertThat(again.store.mark!!.failed).isEmpty()
    }

    @Test fun `a Stop as a held run ends asks for no wake-up - the wake goes in the same step as the run's token`() = runTest {
        val budget = LookupBudget(1, load = { null }, save = {})
        val nowMs = { nowS * 1000 + testScheduler.currentTime }
        val h = Harness(this, guessed(3), BudgetedLengthReader({ _, _ -> LengthAnswer(200_000, hit = false) }, budget, now = nowMs), budget = budget)
        // The Stop lands as the run tells the screens what it timed, just before it ends held by the day's cap
        h.onChanged = { h.lengths.stop() }
        h.lengths.run()
        assertThat(h.changes).isAtLeast(1)
        assertThat(h.wakes).isEmpty()
        // Not stopped: the same run asks for its wake-up
        val held = Harness(this, guessed(3), BudgetedLengthReader({ _, _ -> LengthAnswer(200_000, hit = false) }, LookupBudget(1, { null }, {}), now = nowMs))
        held.lengths.run()
        assertThat(held.wakes).hasSize(1)
    }

    @Test fun `no network the setting allows - the free fill runs, nothing is asked or marked, and the pass waits for one`() = runTest {
        val asked = mutableListOf<String>()
        val inner = LengthReader { _, t -> asked += t; LengthAnswer(200_000, hit = false) }
        val known = Scrobbles.songKey("Song 2", "Band")
        val h = Harness(this, guessed(3), GatedLengthReader(inner) { LengthHold.NETWORK }, library = mapOf(known to 187_000L), wifiOnly = flowOf(false))
        h.lengths.run()
        assertThat(asked).isEmpty()
        assertThat(h.store.rows.values.filter { it.f and RecordFlags.ESTIMATED == 0 }.map { it.t }).containsExactly("Song 2")
        assertThat(h.store.mark!!.failed).isEmpty()
        assertThat(h.store.mark!!.none).isEmpty()
        assertThat(h.wakes).isEmpty()
        // Back once there is a network, any when the app isn't Wi-Fi only
        assertThat(h.networkWaits).containsExactly(false)

        // Wi-Fi only (the setting unread: the stricter), and a Stop as it ends: no wait asked for
        val strict = Harness(this, guessed(3), GatedLengthReader(inner) { LengthHold.NETWORK })
        strict.lengths.run()
        assertThat(strict.networkWaits).containsExactly(true)
        val stopped = Harness(this, guessed(3), GatedLengthReader(inner) { LengthHold.NETWORK }, library = mapOf(known to 187_000L))
        stopped.onChanged = { stopped.lengths.stop() }
        stopped.lengths.run()
        assertThat(stopped.networkWaits).isEmpty()
    }

    @Test fun `runs in a row held for a network back off, so a network the job takes but the lookups can't use never loops`() = runTest {
        var online = false
        val h = Harness(this, guessed(3), GatedLengthReader({ _, _ -> LengthAnswer(200_000, hit = false) }) { if (online) null else LengthHold.NETWORK })
        repeat(4) { h.lengths.run() }
        val m = 60_000L
        assertThat(h.networkDelays).containsExactly(0L, 15 * m, 30 * m, 60 * m).inOrder()
        // A run that could ask starts the count again
        online = true
        h.lengths.run()
        online = false
        h.store.memory.rows.putAll(guessed(4).associateBy { it.id })
        h.lengths.run()
        assertThat(h.networkDelays).containsExactly(0L, 15 * m, 30 * m, 60 * m, 0L).inOrder()
        assertThat(RecordLengths.networkBackoffMs(40)).isEqualTo(RecordLengths.NETWORK_BACKOFF_MAX_MS)
    }

    @Test fun `no proxy to ask through - no song is marked, nothing comes back by itself, and the free fill still runs`() = runTest {
        var asks = 0
        val known = Scrobbles.songKey("Song 1", "Band")
        val h = Harness(this, guessed(3), ProxyLengthReader(ask = { _, _ -> asks++; LastFmSongLength.NoProxy }), library = mapOf(known to 187_000L))
        h.lengths.run()
        assertThat(asks).isEqualTo(1)
        assertThat(h.store.mark!!.failed).isEmpty()
        assertThat(h.store.mark!!.failures).isEmpty()
        assertThat(h.store.rows.values.filter { it.f and RecordFlags.ESTIMATED == 0 }.map { it.t }).containsExactly("Song 1")
        assertThat(h.wakes).isEmpty()
        assertThat(h.networkWaits).isEmpty()
        // The gate says so before any ask
        val gated = Harness(this, guessed(3), GatedLengthReader({ _, _ -> asks++; LengthAnswer(1) }) { LengthHold.NO_PROXY })
        gated.lengths.run()
        assertThat(asks).isEqualTo(1)
        assertThat(gated.store.mark!!.failed).isEmpty()
    }

    @Test fun `answers the phone had don't count against the day`() = runTest {
        val budget = LookupBudget(2, load = { null }, save = {})
        val asked = mutableListOf<String>()
        val h = Harness(this, guessed(6), BudgetedLengthReader({ _, t -> asked += t; LengthAnswer(200_000, hit = true) }, budget), budget = budget)
        h.lengths.run()
        assertThat(asked).hasSize(6)
        assertThat(h.wakes).isEmpty()
    }

    @Test fun `a rate limit stops the pass at once, marks no song, and holds every ask until it lifts`() = runTest {
        val saved = Saved()
        val budget = LookupBudget(2_000, load = { saved.value }, save = { saved.value = it })
        var answers = 0
        val proxy = ProxyLengthReader(ask = { _, _ -> if (answers++ == 1) LastFmSongLength.Limited else LastFmSongLength.Known(200_000, cached = false) }, now = { nowS * 1000 })
        val h = Harness(this, guessed(5), BudgetedLengthReader(proxy, budget, now = { nowS * 1000 }), budget = budget)
        h.lengths.run()
        // Song 0 timed, Song 1 limited: no second try, not marked, and the run comes back in half an hour
        assertThat(answers).isEqualTo(2)
        assertThat(h.store.mark!!.failed).isEmpty()
        assertThat(h.store.rows.values.count { it.f and RecordFlags.ESTIMATED == 0 }).isEqualTo(1)
        assertThat(h.wakes).containsExactly(nowS * 1000 + RecordLengths.LIMITED_WAIT_MS)
        // The hold is kept: until then, no ask goes out
        assertThat(LookupBudget(2_000, { saved.value }, {}).retryAt(nowS * 1000 + 60_000)).isEqualTo(nowS * 1000 + RecordLengths.LIMITED_WAIT_MS)
    }

    @Test fun `the budget's rolling day - spent, kept, freed an hour at a time`() {
        val hour = 3_600_000L
        val t = 1_800_000_000_000L / hour * hour // on the hour
        val saved = Saved()
        val b = LookupBudget(3, load = { saved.value }, save = { saved.value = it })
        assertThat(b.left(t)).isEqualTo(3)
        assertThat(b.retryAt(t)).isNull()
        b.spend(t)
        b.spend(t + hour + 1)
        b.spend(t + 2 * hour + 1)
        assertThat(b.left(t + 3 * hour)).isEqualTo(0)
        // Free once the first hour's lookups are a whole day past that hour's end
        assertThat(b.retryAt(t + 3 * hour)).isEqualTo(t + hour + 24 * hour)
        b.flush()
        val read = LookupBudget(3, load = { saved.value }, save = {})
        assertThat(read.left(t + 3 * hour)).isEqualTo(0)
        assertThat(read.left(t + 25 * hour)).isEqualTo(1)
        assertThat(read.left(t + 28 * hour)).isEqualTo(3)
        // A rate limit holds it whatever is left
        read.block(t + 30 * hour)
        assertThat(read.retryAt(t + 29 * hour + 40 * 60_000L)).isEqualTo(t + 30 * hour)
        assertThat(read.retryAt(t + 30 * hour)).isNull()
    }

    @Test fun `the budget never trusts a clock that went back - hours ahead of now dropped, a hold at most half an hour`() {
        val hour = 3_600_000L
        val t = 1_800_000_000_000L / hour * hour
        val saved = Saved()
        // Spent and held under a clock ten days ahead (set by hand, then put right)
        val ahead = LookupBudget(3, load = { saved.value }, save = { saved.value = it })
        repeat(3) { ahead.spend(t + 240 * hour) }
        ahead.block(t + 240 * hour + 10 * 60_000L)
        ahead.flush()
        val b = LookupBudget(3, load = { saved.value }, save = { saved.value = it })
        // Now, back at t: the hours from the future are gone, and the hold is half an hour from now at most
        assertThat(b.left(t)).isEqualTo(3)
        assertThat(b.retryAt(t)).isEqualTo(t + RecordLengths.LIMITED_WAIT_MS)
        assertThat(b.retryAt(t + RecordLengths.LIMITED_WAIT_MS)).isNull()
        // Written back so: read again, the same
        b.flush()
        assertThat(LookupBudget(3, { saved.value }, {}).retryAt(t + 60_000L)).isEqualTo(t + RecordLengths.LIMITED_WAIT_MS)
        // An hour just begun (the clock a little ahead of the hour's bucket) is kept
        val near = LookupBudget(1, load = { null }, save = {})
        near.spend(t + hour)
        assertThat(near.left(t + 1)).isEqualTo(0)
    }

    // ── the reader ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the reader - a length, none, busy, a rate limit, a missing proxy - and no session needed`() = runTest {
        var answer: LastFmSongLength = LastFmSongLength.Known(201_000, cached = true)
        val reader = ProxyLengthReader(ask = { _, _ -> answer }, now = { 1_000L })
        assertThat(reader.look("Band", "Song")).isEqualTo(LengthAnswer(201_000, hit = true))
        answer = LastFmSongLength.None(cached = false)
        assertThat(reader.look("Band", "Song")).isEqualTo(LengthAnswer(null, hit = false))
        suspend fun error(): LastfmError? = try {
            reader.look("Band", "Song")
            null
        } catch (e: LastfmError) {
            e
        }
        answer = LastFmSongLength.Busy
        assertThat(error()!!.kind).isEqualTo(LastfmFailure.BUSY)
        assertThat(error()!!.limited).isFalse()
        answer = LastFmSongLength.Limited
        assertThat(error()!!.limited).isTrue()
        assertThat(error()!!.retryAt).isEqualTo(1_000L + RecordLengths.LIMITED_WAIT_MS)
        answer = LastFmSongLength.Unavailable
        assertThat(error()!!.kind).isEqualTo(LastfmFailure.UNAVAILABLE)
        assertThat(error()!!.hold).isNull()
        // No proxy in this build: a hold, never a song's failure
        answer = LastFmSongLength.NoProxy
        assertThat(error()!!.hold).isEqualTo(LengthHold.NO_PROXY)
    }
}
