package com.stash.feature.record.card

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.WebLibraryExportDao.OwnHistoryStamp
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.RecordArchiveSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The card's data is read once per change of the history, never again for the same history (Home coming back). */
class RecordCardSourceTest {
    private fun stamp(plays: Int) = OwnHistoryStamp(plays = plays, lastPlay = plays.toLong(), listenedMs = 0, skips = 0, lastSkip = 0, blocked = 0)

    private fun row(i: Int) = ArchivePlay(id = "t$i|a|$i", t = "T$i", a = "A", s = 1_000L + i, ms = 60_000, f = 256)

    @Test fun `read at once, then once per burst of changes, and kept for the same history`() = runTest {
        val stamps = MutableStateFlow(stamp(3))
        var reads = 0
        val source = RecordCardSource(
            stamps = stamps,
            tracks = MutableStateFlow(0L),
            archive = RecordArchiveSource { _, emit ->
                reads++
                emit((0 until stamps.value.plays).map(::row), true)
            },
            albums = { emptyList() },
            scope = backgroundScope,
            work = StandardTestDispatcher(testScheduler),
            now = { testScheduler.currentTime },
        )
        assertThat(source.data.first().summary.streams).isEqualTo(3)
        assertThat(reads).isEqualTo(1)

        // a burst: a song ends, the next starts, its skip... read once, after it settles
        val seen = mutableListOf<Int>()
        val watching = backgroundScope.launch { source.data.collect { seen += it.summary.streams } }
        runCurrent()
        stamps.value = stamp(4)
        advanceTimeBy(500)
        stamps.value = stamp(5)
        advanceTimeBy(RecordCardSource.SETTLE_MS - 100)
        assertThat(reads).isEqualTo(1)
        advanceTimeBy(200)
        runCurrent()
        assertThat(reads).isEqualTo(2)
        assertThat(seen.last()).isEqualTo(5)
        watching.cancel()

        // nobody watching for a while, then Home again: the same history is not read again
        advanceTimeBy(RecordCardSource.STOP_MS * 2)
        assertThat(source.data.first().summary.streams).isEqualTo(5)
        advanceUntilIdle()
        assertThat(reads).isEqualTo(2)
    }

    @Test fun `a song played here re-reads this phone's plays alone, an import or a removal the imported ones`() = runTest {
        val stamps = MutableStateFlow(stamp(1))
        val importedStamp = MutableStateFlow(10)
        var ownReads = 0
        var importedReads = 0
        val imported = { n: Int -> (0 until n).map { ArchivePlay(id = "i$it|x|$it", t = "I$it", a = "Spotify Artist", s = 5_000L + it, ms = 200_000, f = 34) } }
        val source = RecordCardSource(
            stamps = stamps,
            tracks = MutableStateFlow(0L),
            archive = RecordArchiveSource { _, emit ->
                ownReads++
                emit((0 until stamps.value.plays).map(::row), true)
            },
            albums = { emptyList() },
            scope = backgroundScope,
            work = StandardTestDispatcher(testScheduler),
            now = { testScheduler.currentTime },
            importedStamps = importedStamp,
            imported = RecordArchiveSource { _, emit ->
                importedReads++
                emit(imported(importedStamp.value), true)
            },
        )
        val seen = mutableListOf<Int>()
        backgroundScope.launch { source.data.collect { seen += it.summary.plays } }
        runCurrent()
        assertThat(seen.last()).isEqualTo(11)
        assertThat(listOf(ownReads, importedReads)).containsExactly(1, 1).inOrder()

        // a song played here: this phone's plays read again, the imported part kept
        stamps.value = stamp(2)
        advanceTimeBy(RecordCardSource.SETTLE_MS + 100)
        runCurrent()
        assertThat(seen.last()).isEqualTo(12)
        assertThat(listOf(ownReads, importedReads)).containsExactly(2, 1).inOrder()

        // an import: both parts read again
        importedStamp.value = 20
        advanceTimeBy(RecordCardSource.SETTLE_MS + 100)
        runCurrent()
        assertThat(seen.last()).isEqualTo(22)
        assertThat(listOf(ownReads, importedReads)).containsExactly(3, 2).inOrder()
    }

    @Test fun `while a Last fm run writes, the imported part is held, and read once when it ends`() = runTest {
        val importedStamp = MutableStateFlow(10)
        val paused = MutableStateFlow(true)
        var importedReads = 0
        val source = RecordCardSource(
            stamps = MutableStateFlow(stamp(1)),
            tracks = MutableStateFlow(0L),
            archive = RecordArchiveSource { _, emit -> emit(listOf(row(0)), true) },
            albums = { emptyList() },
            scope = backgroundScope,
            work = StandardTestDispatcher(testScheduler),
            now = { testScheduler.currentTime },
            importedStamps = importedStamp,
            scrobbles = RecordArchiveSource { _, emit ->
                importedReads++
                emit((0 until importedStamp.value).map { ArchivePlay("s$it|x|$it", "S$it", "Band", s = 9_000L + it, ms = 210_000, f = 64 or 128) }, true)
            },
            importsPaused = paused,
        )
        val seen = mutableListOf<Int>()
        backgroundScope.launch { source.data.collect { seen += it.summary.plays } }
        runCurrent()
        // Home opened during a run: a card at once, from what is there
        assertThat(seen.last()).isEqualTo(11)
        // pages written: nothing read again
        for (n in listOf(20, 30, 40)) {
            importedStamp.value = n
            advanceTimeBy(RecordCardSource.SETTLE_MS + 100)
            runCurrent()
        }
        assertThat(importedReads).isEqualTo(1)
        // the run ends: one read, of all of it
        paused.value = false
        advanceTimeBy(RecordCardSource.SETTLE_MS + 100)
        runCurrent()
        assertThat(importedReads).isEqualTo(2)
        assertThat(seen.last()).isEqualTo(41)
    }

    @Test fun `while the lengths pass writes, the plays are held, read each time it shows what it timed, and once it ends`() = runTest {
        // The pass rewrites a scrobble's ms: the database's stamp moves with every song, the pass's word only now and then
        val importedStamp = MutableStateFlow(10)
        val paused = MutableStateFlow(true)
        val shown = MutableStateFlow(0)
        var ms = 210_000L
        var importedReads = 0
        val source = RecordCardSource(
            stamps = MutableStateFlow(stamp(1)),
            tracks = MutableStateFlow(0L),
            archive = RecordArchiveSource { _, emit -> emit(listOf(row(0)), true) },
            albums = { emptyList() },
            scope = backgroundScope,
            work = StandardTestDispatcher(testScheduler),
            now = { testScheduler.currentTime },
            importedStamps = importedStamp,
            scrobbles = RecordArchiveSource { _, emit ->
                importedReads++
                emit((0 until 10).map { ArchivePlay("s$it|x|$it", "S$it", "Band", s = 9_000L + it, ms = ms, f = 64) }, true)
            },
            importsPaused = paused,
            importsShown = shown,
        )
        val seen = mutableListOf<Long>()
        backgroundScope.launch { source.data.collect { seen += it.summary.totalMs } }
        runCurrent()
        assertThat(importedReads).isEqualTo(1)
        // songs timed: nothing read again
        ms = 200_000L
        for (n in 11..14) {
            importedStamp.value = n
            advanceTimeBy(RecordCardSource.SETTLE_MS + 100)
            runCurrent()
        }
        assertThat(importedReads).isEqualTo(1)
        // the pass shows what it timed: one read, of the plays as they are now, though the stamp hasn't moved again
        shown.value = 1
        advanceTimeBy(RecordCardSource.SETTLE_MS + 100)
        runCurrent()
        assertThat(importedReads).isEqualTo(2)
        // more timed, held again; the pass ends: one more read
        ms = 190_000L
        importedStamp.value = 15
        advanceTimeBy(RecordCardSource.SETTLE_MS + 100)
        runCurrent()
        assertThat(importedReads).isEqualTo(2)
        paused.value = false
        advanceTimeBy(RecordCardSource.SETTLE_MS + 100)
        runCurrent()
        assertThat(importedReads).isEqualTo(3)
        assertThat(seen.distinct().size).isEqualTo(3)
    }

    @Test fun `a history that can't be read keeps what the card had`() = runTest {
        val stamps = MutableStateFlow(stamp(1))
        var fail = false
        val source = RecordCardSource(
            stamps = stamps,
            tracks = MutableStateFlow(0L),
            archive = RecordArchiveSource { _, emit ->
                if (fail) error("database gone")
                emit(listOf(row(1)), true)
            },
            albums = { emptyList() },
            scope = backgroundScope,
            work = StandardTestDispatcher(testScheduler),
            now = { testScheduler.currentTime },
        )
        val seen = mutableListOf<Int>()
        backgroundScope.launch { source.data.collect { seen += it.summary.plays } }
        runCurrent()
        fail = true
        stamps.value = stamp(2)
        advanceTimeBy(RecordCardSource.SETTLE_MS * 2)
        runCurrent()
        assertThat(seen).containsExactly(1)
    }

    @Test fun `songs written by a sync are read once the writes settle, and at most once a minute`() = runTest {
        val stamps = MutableStateFlow(stamp(2))
        // Room's flow says "written" each time, even when the value is the same: a shared flow, not a state flow
        val tracks = MutableSharedFlow<Long>(replay = 1).apply { tryEmit(0) }
        var reads = 0
        val source = RecordCardSource(
            stamps = stamps,
            tracks = tracks,
            archive = RecordArchiveSource { _, emit ->
                reads++
                emit((0 until stamps.value.plays).map(::row), true)
            },
            albums = { emptyList() },
            scope = backgroundScope,
            work = StandardTestDispatcher(testScheduler),
            now = { testScheduler.currentTime },
        )
        backgroundScope.launch { source.data.collect {} }
        runCurrent()
        assertThat(reads).isEqualTo(1)

        // a sync: songs written every 100 ms for 3 s, read once, TRACKS_SETTLE_MS after the last write
        val start = testScheduler.currentTime
        repeat(30) {
            tracks.emit(it.toLong())
            advanceTimeBy(100)
        }
        val lastWrite = start + 2_900
        advanceTimeBy(lastWrite + RecordCardSource.TRACKS_SETTLE_MS - testScheduler.currentTime - 1)
        assertThat(reads).isEqualTo(1)
        advanceTimeBy(2)
        runCurrent()
        assertThat(reads).isEqualTo(2)
        val readAt = lastWrite + RecordCardSource.TRACKS_SETTLE_MS

        // written again soon after (the same newest id: a rename): not read again until a minute after that read
        tracks.emit(29)
        advanceTimeBy(readAt + RecordCardSource.TRACKS_GAP_MS - testScheduler.currentTime - 1)
        assertThat(reads).isEqualTo(2)
        advanceTimeBy(2)
        runCurrent()
        assertThat(reads).isEqualTo(3)

        // songs written, then a play before their read: one read, 2 s after the play, and it reads the songs too
        tracks.emit(30)
        advanceTimeBy(1_000)
        stamps.value = stamp(3)
        advanceTimeBy(RecordCardSource.SETTLE_MS + 1)
        runCurrent()
        assertThat(reads).isEqualTo(4)
        advanceUntilIdle()
        assertThat(reads).isEqualTo(4)
    }
}
