package com.stash.feature.record.lastfm

import com.google.common.truth.Truth.assertThat
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.RecordFlags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A hole asked again (lastfm.ts `recheckHoles`), where the vectors can't go: a run cut short keeps what it read, page by page, and
 * goes on from there; and a run asks its holes [ImportScrobbles.HOLE_PAGES] pages at most.
 */
class HolesTest {
    private val t0 = 1_714_564_800L
    private val later = t0 + 700 * 86_400L

    private fun six(i: Int) = t0 + i * 6 * 3600L
    private val all = (0 until 1400).map { Scrobble("Six $it", "Band", uts = six(it)) }

    private fun row(s: Scrobble) = ArchivePlay(id = Scrobbles.id(s), t = s.t, a = s.a, s = s.uts * 1000, ms = 210_000, f = RecordFlags.LASTFM or RecordFlags.ESTIMATED)

    /** An archive holding all but the hole, done, the hole found long ago. */
    private fun withHole(kept: List<Scrobble>, hole: LastfmMark.Hole, newest: Long) = MemoryScrobbleStore(
        kept.map(::row),
        LastfmMark("me", to = later - 86_400, next = 9, pages = 8, done = true, newest = newest, added = kept.size, covered = 0, at = later - 86_400, holes = listOf(hole)),
    )

    /** Last.fm: the window below `to` (and from `from`), newest first, 200 a page. */
    private fun reader(list: List<Scrobble>, asks: MutableList<Pair<Int, Long>>? = null, onAsk: (Int, Long) -> Unit = { _, _ -> }) = PageReader { page, to, from ->
        asks?.add(page to to)
        onAsk(page, to)
        val window = list.filter { it.uts <= to && (from == null || it.uts >= from) }.sortedByDescending { it.uts }
        HistoryPage(window.drop((page - 1) * 200).take(200), page, (window.size + 199) / 200, window.size)
    }

    @Test fun `a hole asked again and cut short keeps what it read, page by page, and the next run goes on from there`() = runTest {
        val kept = all.take(200) + all.drop(1200)
        val store = withHole(kept, LastfmMark.Hole(six(199), six(1200), 0), six(1399))
        val history = all + Scrobble("New 1", "Band", uts = later - 50)
        // Stopped (the system's ten minutes, the app's death) as the hole's second page is asked
        var holeAsks = 0
        val cut = reader(history) { _, to -> if (to == six(1200) + 1 && ++holeAsks == 2) throw CancellationException("stopped") }
        val stopped = try {
            ImportScrobbles.run(store, cut, "me", later, gapMs = 0, busyMs = 0)
            null
        } catch (e: CancellationException) {
            e
        }
        assertThat(stopped).isNotNull()
        // What is new came in first; the hole's first page is in, and the hole kept narrowed below it
        assertThat(store.rows.values.any { it.t == "New 1" }).isTrue()
        assertThat(store.mark!!.holes).containsExactly(LastfmMark.Hole(six(199), six(1001), 0))
        // The next run asks below that, not the first pages again
        val asks = mutableListOf<Pair<Int, Long>>()
        val m = ImportScrobbles.run(store, reader(history, asks), "me", later + 3600, gapMs = 0, busyMs = 0)
        assertThat(asks.filter { it.second <= six(1200) + 1 }.map { it.second }.toSet()).containsExactly(six(1001) + 1)
        assertThat(m.holes).isNull()
        assertThat(store.rows.size).isEqualTo(1401)
    }

    @Test fun `a run asks its holes HOLE_PAGES pages at most, all holes together`() = runTest {
        val store = withHole(emptyList(), LastfmMark.Hole(0, later - 2 * 86_400, 0), later - 2 * 86_400)
        val old = (0 until 30_000).map { Scrobble("Old $it", "Band", uts = t0 + it * 60L) }
        val asks = mutableListOf<Pair<Int, Long>>()
        val m = ImportScrobbles.run(store, reader(old, asks), "me", later, gapMs = 0, busyMs = 0)
        assertThat(asks.count { it.second == later - 2 * 86_400 + 1 }).isEqualTo(ImportScrobbles.HOLE_PAGES)
        // Kept, narrowed below what came
        assertThat(m.holes!!.single().to).isEqualTo(old.sortedByDescending { it.uts }[ImportScrobbles.HOLE_PAGES * 200 - 1].uts)
    }
}
