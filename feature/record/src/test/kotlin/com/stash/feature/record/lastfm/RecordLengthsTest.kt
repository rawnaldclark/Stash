package com.stash.feature.record.lastfm

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.lastfm.recap.LastFmSongLength
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.RecordFlags
import com.stash.feature.record.imports.RecordWrites
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The lengths pass: a slice stopped, the service's slices and what the screens see of them, and its reader. */
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

    @Test fun `stopped part way, what it learnt is saved and the stop goes on`() = runTest {
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
        job.cancel()
        advanceUntilIdle()
        // The miss saved as it came, and once more on the way out (the timed count with it)
        assertThat(store.saves.last()).isEqualTo(LengthsMark(none = mapOf(Scrobbles.songKey("Song 1", "Band") to nowS), timed = 1, at = nowS))
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

    // ── the service ─────────────────────────────────────────────────────────────────────────────────────────────────

    private inner class Harness(val test: TestScope, rows: List<ArchivePlay>, val look: LengthReader) {
        val store = MemoryLengthsStore(rows)
        val writes = RecordWrites()
        var clock = 0L
        var records = 0
        var changes = 0
        var launches = 0
        var cancels = 0
        /** What the screens saw at each change: whether the card was held, and how many times the pass had shown its songs. */
        val seen = mutableListOf<Pair<Boolean, Int>>()
        val lengths = RecordLengths(
            newStore = { store },
            reader = look,
            library = { emptyMap() },
            writes = writes,
            recordAfter = { records++ },
            changed = { changes++; seen += writes.lengthsRunning.value to writes.lengthsShown.value },
            launch = { launches++ },
            cancelJob = { cancels++ },
            now = { nowS * 1000 },
            clock = { clock },
            gapMs = 0,
            busyMs = 0,
        )
    }

    @Test fun `the pass times every song a slice at a time, shows them, and lets the card go`() = runTest {
        val h = Harness(this, guessed(450, songs = 450)) { _, _ -> LengthAnswer(200_000) }
        h.lengths.start()
        assertThat(h.launches).isEqualTo(1)
        h.lengths.run()
        // 450 songs: slices of 200, 200 and 50; the first shown at once, the end shown again (the clock never moved)
        assertThat(h.store.rows.values.none { it.f and RecordFlags.ESTIMATED != 0 }).isTrue()
        assertThat(h.records).isEqualTo(2)
        assertThat(h.seen).containsExactly(true to 1, true to 2).inOrder()
        assertThat(h.writes.lengthsRunning.value).isFalse()
        assertThat(h.store.mark!!.timed).isEqualTo(450)
    }

    @Test fun `while the pass runs the hours are worked out again at most every two minutes, and at its end`() = runTest {
        val h = Harness(this, guessed(1000, songs = 1000)) { _, _ -> LengthAnswer(200_000) }
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

    @Test fun `a writer stops the pass and waits for it to let go of the lock, and the record reads what it timed`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(this, guessed(3)) { _, t -> if (t == "Song 1") gate.await(); LengthAnswer(200_000) }
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
        // Song 0 was timed in the slice it stopped: not shown then, so the record is told on the way out (the card let go)
        assertThat(h.seen).containsExactly(false to 0)
        assertThat(h.records).isEqualTo(0)
        assertThat(h.store.mark!!.timed).isEqualTo(1)
        // Nothing running: a pause says so
        assertThat(h.lengths.pause()).isFalse()
    }

    @Test fun `the person's Stop ends the pass and nothing runs again by itself`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(this, guessed(3)) { _, t -> if (t == "Song 1") gate.await(); LengthAnswer(200_000) }
        val pass = launch { h.lengths.run() }
        runCurrent()
        h.lengths.stop()
        pass.join()
        assertThat(h.cancels).isEqualTo(1)
        assertThat(h.launches).isEqualTo(0)
        assertThat(h.writes.mutex.isLocked).isFalse()
    }

    @Test fun `a pass stopped while it waits for the lock reads and writes nothing`() = runTest {
        val h = Harness(this, guessed(3)) { _, _ -> LengthAnswer(200_000) }
        val writer = CompletableDeferred<Unit>()
        val holder = launch { h.writes.mutex.withLock { writer.await() } }
        runCurrent()
        val pass = launch { h.lengths.run() }
        runCurrent()
        h.lengths.stop()
        writer.complete(Unit)
        holder.join()
        pass.join()
        assertThat(h.store.saves).isEmpty()
        assertThat(h.records).isEqualTo(0)
        assertThat(h.changes).isEqualTo(0)
    }

    @Test fun `Last fm down - three songs failing in a row stop the pass until it is asked again`() = runTest {
        val h = Harness(this, guessed(10)) { _, _ -> throw LastfmError(LastfmFailure.BUSY) }
        h.lengths.run()
        assertThat(h.store.mark!!.failed).hasSize(Lengths.FAILS_IN_A_ROW)
        assertThat(h.records).isEqualTo(0)
        assertThat(h.changes).isEqualTo(0)
    }

    // ── the reader ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the reader - a length, none, busy, a missing proxy, and no account`() = runTest {
        var connected = true
        var answer: LastFmSongLength = LastFmSongLength.Known(201_000, cached = true)
        val reader = ProxyLengthReader(connected = { connected }, ask = { _, _ -> answer })
        assertThat(reader.look("Band", "Song")).isEqualTo(LengthAnswer(201_000, hit = true))
        answer = LastFmSongLength.None(cached = false)
        assertThat(reader.look("Band", "Song")).isEqualTo(LengthAnswer(null, hit = false))
        suspend fun kind(): LastfmFailure? = try {
            reader.look("Band", "Song")
            null
        } catch (e: LastfmError) {
            e.kind
        }
        answer = LastFmSongLength.Busy
        assertThat(kind()).isEqualTo(LastfmFailure.BUSY)
        answer = LastFmSongLength.Unavailable
        assertThat(kind()).isEqualTo(LastfmFailure.UNAVAILABLE)
        connected = false
        answer = LastFmSongLength.Known(1, cached = false)
        assertThat(kind()).isEqualTo(LastfmFailure.RECONNECT)
    }
}
