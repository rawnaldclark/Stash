package com.stash.feature.record.archive

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.WebLibraryExportDao
import com.stash.core.data.db.dao.WebLibraryExportDao.ListenRow
import com.stash.core.data.db.dao.WebLibraryExportDao.SkipRow
import com.stash.core.data.weblibrary.WebLibraryListens
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.random.Random

/**
 * The phone's plays and skips streamed as the record's archive: the same rows the web makes from this phone's export (its
 * mapping, then the web's rules), in chunks, whatever the page size.
 */
class OwnArchiveTest {
    private val now = 1_792_756_800_000L
    private val t = 1_790_856_000_000L

    /** A DAO that pages exactly as the queries do: newest first by (time, id), from the cursor. */
    private fun dao(plays: List<ListenRow>, skips: List<SkipRow> = emptyList()): WebLibraryExportDao = mockk {
        coEvery { ownPlaysBefore(any(), any(), any()) } answers {
            val at = firstArg<Long>()
            val id = secondArg<Long>()
            plays.filter { it.startedAt > 0 && it.startedAt <= at && (it.startedAt < at || it.id < id) }
                .sortedWith(compareByDescending<ListenRow> { it.startedAt }.thenByDescending { it.id })
                .take(thirdArg())
        }
        coEvery { ownSkipsBefore(any(), any(), any()) } answers {
            val at = firstArg<Long>()
            val id = secondArg<Long>()
            skips.filter { it.skippedAt > 0 && it.skippedAt <= at && (it.skippedAt < at || it.id < id) }
                .sortedWith(compareByDescending<SkipRow> { it.skippedAt }.thenByDescending { it.id })
                .take(thirdArg())
        }
    }

    private fun play(id: Long, title: String, at: Long, artist: String = "Band", album: String = "", listened: Long? = 180_000, duration: Long = 200_000) =
        ListenRow(id, title, artist, album, at, listened, duration)

    private fun skip(id: Long, title: String, at: Long, position: Long, artist: String = "Band") = SkipRow(id, title, artist, "", at, position)

    private class Out {
        val chunks = mutableListOf<Pair<List<ArchivePlay>, Boolean>>()
        val rows get() = chunks.flatMap { it.first }
    }

    private suspend fun OwnArchive.collect(chunk: Int): Out = Out().also { o -> stream(chunk) { rows, last -> o.chunks += rows to last } }

    @Test fun `nothing played, one empty chunk, the last`() = runTest {
        val o = OwnArchive(dao(emptyList()), now = { now }).collect(3)
        assertThat(o.chunks).containsExactly(emptyList<ArchivePlay>() to true)
    }

    @Test fun `chunks of the size asked, only the last marked, never an empty one after a full one`() = runTest {
        val plays = (1..5L).map { play(it, "S$it", t + it * 1000) }
        val o = OwnArchive(dao(plays), now = { now }, pageSize = 2).collect(2)
        assertThat(o.chunks.map { it.first.size to it.second }).containsExactly(2 to false, 2 to false, 1 to true).inOrder()
        val even = OwnArchive(dao(plays.take(4)), now = { now }).collect(2)
        assertThat(even.chunks.map { it.first.size to it.second }).containsExactly(2 to false, 2 to true).inOrder()
    }

    @Test fun `each row as the web keeps it, its time, else the song's length, else a guess`() = runTest {
        val o = OwnArchive(
            dao(
                listOf(
                    play(1, "Timed", t, album = "LP", listened = 90_000),
                    play(2, "Length", t + 1, listened = null),
                    play(3, "Guess", t + 2, listened = 0, duration = 0),
                ),
                listOf(skip(4, "Skip", t + 10_000, position = 5_000)),
            ),
            now = { now },
        ).collect(10)
        val byTitle = o.rows.associateBy { it.t }
        assertThat(byTitle.getValue("Timed")).isEqualTo(ArchivePlay(playId("Timed", "Band", t), "Timed", "Band", "LP", null, t, 90_000, RecordFlags.STASH))
        assertThat(byTitle.getValue("Length").let { it.ms to it.f }).isEqualTo(200_000L to (RecordFlags.STASH or RecordFlags.ESTIMATED))
        assertThat(byTitle.getValue("Guess").let { it.ms to it.f }).isEqualTo(GUESS_MS to (RecordFlags.STASH or RecordFlags.ESTIMATED))
        // a skip starts its position before it was skipped
        assertThat(byTitle.getValue("Skip").let { Triple(it.s, it.ms, it.f) }).isEqualTo(Triple(t + 5_000, 5_000L, RecordFlags.STASH or RecordFlags.SKIPPED))
        assertThat(o.rows.last().t).isEqualTo("Skip") // skips after the plays
    }

    @Test fun `one id once, across a page boundary, a play takes it before a skip`() = runTest {
        val o = OwnArchive(
            dao(
                listOf(play(1, "song", t, listened = 1), play(2, "Song", t, listened = 2), play(3, "Other", t - 5, listened = 3)),
                listOf(skip(4, "SONG", t + 4, position = 4, artist = "band"), skip(5, "Skipped", t + 9_000, position = 5)),
            ),
            now = { now },
            pageSize = 1,
        ).collect(10)
        // "Song" < "song" in UTF-16 order: planPhone keeps it; the skip with the same words and start goes
        assertThat(o.rows.map { it.t to it.ms }).containsExactly("Song" to 2L, "Other" to 3L, "Skipped" to 5L).inOrder()
    }

    @Test fun `the export's cap, the newest plays, counted as the export counts them`() = runTest {
        val plays = (1..6L).map { play(it, "S$it", t + it) } + play(7, "   ", t + 100) // no words: never an entry
        val o = OwnArchive(dao(plays), now = { now }, maxPlays = 3, pageSize = 2).collect(10)
        assertThat(o.rows.map { it.t }).containsExactly("S6", "S5", "S4").inOrder()
    }

    @Test fun `rows the web would drop are dropped, no start, a time out of range`() = runTest {
        val o = OwnArchive(
            dao(listOf(play(1, "Ancient", PhoneRows.MIN_T - 1), play(2, "Future", now + 3 * 86_400_000L), play(3, "Fine", t))),
            now = { now },
        ).collect(10)
        assertThat(o.rows.map { it.t }).containsExactly("Fine")
    }

    @Test fun `the same rows as the web's pipeline on the export, for a messy history`() = runTest {
        val r = Random(26)
        val words = listOf("Ivy", "ivy", "IVY!", "Nights", "Café", "Cafe", "Song (feat. X)", "Song", "  ", "Ünder")
        val artists = listOf("Frank Ocean", "frank ocean", "Band", "Bänd")
        val starts = (0 until 40).map { t + r.nextLong(0, 30) * 60_000 } // many plays share a start
        val plays = (1..300L).map { id ->
            play(id, words[r.nextInt(words.size)], starts[r.nextInt(starts.size)], artists[r.nextInt(artists.size)],
                listened = if (r.nextInt(4) == 0) null else r.nextLong(0, 400_000), duration = r.nextLong(0, 300_000))
        }
        val skips = (301..400L).map { id -> skip(id, words[r.nextInt(words.size)], starts[r.nextInt(starts.size)] + r.nextLong(0, 4) * 1000, r.nextLong(0, 4) * 1000, artists[r.nextInt(artists.size)]) }

        val streamed = OwnArchive(dao(plays, skips), now = { now }, pageSize = 7).collect(13).rows

        // What the web does with this phone's export: the file's entries, newest first, then phoneRows and planPhone
        val filePlays = plays.sortedWith(compareByDescending<ListenRow> { it.startedAt }.thenByDescending { it.id })
            .mapNotNull(WebLibraryListens::play)
            .mapNotNull { PhoneRows.read(it.t, it.a, it.al, it.s, it.ms, it.d, now, skip = false) }
        val fileSkips = skips.sortedWith(compareByDescending<SkipRow> { it.skippedAt }.thenByDescending { it.id })
            .mapNotNull(WebLibraryListens::skip)
            .mapNotNull { PhoneRows.read(it.t, it.a, it.al, it.s, it.ms, null, now, skip = true) }
        val web = PhoneRows.planPhone(filePlays, fileSkips)

        assertThat(streamed.sortedBy { it.id }).isEqualTo(web.sortedBy { it.id })
        assertThat(streamed.map { it.id }).containsNoDuplicates()
    }
}
