package com.stash.feature.record.mixes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.RecordTasteDao
import com.stash.core.data.db.entity.RecordTasteEntity
import com.stash.core.model.weblink.SongKey
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.RecordArchiveSource
import com.stash.feature.record.archive.RecordFlags
import com.stash.feature.record.archive.playId
import com.stash.feature.record.taste.SongStat
import com.stash.feature.record.taste.Taste
import com.stash.feature.record.taste.TasteRows
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/**
 * [RecordMixes] over a database it reads through its seams: the taste read once per `computed_at`, kept out as the record shows
 * it (put back, in the library), the library's own rows, and "this week, years ago" over the record's plays stacked as the
 * record stacks them (the export's, this phone's own, then the scrobbles they don't cover; each id once), read once a taste a day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecordMixesTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private val words = ResourceMixWords(ApplicationProvider.getApplicationContext())
    private val day = 86_400_000L
    private val now = java.time.LocalDateTime.of(2026, 7, 4, 15, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val mid2025 = java.time.LocalDateTime.of(2025, 7, 4, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun stat(title: String, artist: String, avoid: Boolean = false, years: Int = 3) = SongStat(
        key = SongKey.songKey(title, artist), title = title, artist = artist, starts = 10, plays = 10, full = 10, chosen = 0, skips = 0,
        years = years, listenedMs = 2_000_000, first = 1_600_000_000_000, last = 1_700_000_000_000, peak = 2, peakAt = 0, weight = 1.0,
        depth = 1.0, avoid = avoid,
    )

    private fun taste(songs: List<SongStat>, computedAt: Long = 1L, constants: List<String> = songs.map { it.key }) = Taste(
        computedAt = computedAt, first = 1_577_836_800_000, last = 1_700_000_000_000, plays = 0, listenedMs = 0, artists = 0,
        songs = songs, eras = emptyList(), constants = constants, lost = emptyList(), obsessions = emptyList(), found = null,
        years = emptyList(), topArtists = emptyList(), seedArtists = emptyList(), yearTops = emptyList(),
    )

    private class Db(var taste: Taste?, var keep: List<String> = emptyList()) {
        val dao: RecordTasteDao = mockk(relaxed = true)
        init {
            coEvery { dao.computedAt() } answers { taste?.computedAt }
            coEvery { dao.whole() } answers { taste?.let { t -> RecordTasteDao.Whole(RecordTasteEntity(v = 1, computedAt = t.computedAt, body = TasteRows.body(t)), TasteRows.songs(t)) } }
            coEvery { dao.keepKeys() } answers { keep }
            every { dao.computedAtFlow() } returns flowOf(null)
            every { dao.keepKeysFlow() } returns flowOf(emptyList())
        }
    }

    private fun row(t: String, a: String, s: Long, f: Int = RecordFlags.REASONS or RecordFlags.DONE, ms: Long = 200_000) =
        ArchivePlay(id = playId(t, a, s), t = t, a = a, s = s, ms = ms, f = f)

    private fun mixes(
        db: Db,
        imported: List<ArchivePlay> = emptyList(),
        own: List<ArchivePlay> = emptyList(),
        library: List<LibrarySong> = emptyList(),
        reads: MutableList<Pair<Long, Long>> = mutableListOf(),
        ownReads: IntArray = IntArray(1),
        blocked: BlockedSongs = BlockedSongs.NONE,
    ) = RecordMixes(
        tastes = db.dao,
        between = { from, to -> reads += from to to; imported.filter { it.s >= from && it.s < to }.shuffled() },
        own = RecordArchiveSource { _, emit -> ownReads[0]++; emit(own, true) },
        library = { library },
        libraryStamp = flowOf(Unit),
        blocked = { blocked },
        io = EmptyCoroutineContext,
    )

    @Test fun `a blocked song is in no mix, and a mix of blocked songs alone is gone`() = runTest {
        val songs = (1..9).map { stat("Song $it", "Band") }
        val db = Db(taste(songs))
        val some = mixes(db, blocked = BlockedSongs { title, _, _ -> title == "Song 3" }).now(words, now, ZoneOffset.UTC)!!
        assertThat(some.find("constants")!!.items.map { it.title }).doesNotContain("Song 3")
        assertThat(some.find("constants")!!.items).hasSize(8)
        val every = mixes(db, blocked = BlockedSongs { _, _, _ -> true }).now(words, now, ZoneOffset.UTC)!!
        assertThat(every.all).isEmpty()
    }

    @Test fun `no taste, no mixes`() = runTest {
        assertThat(mixes(Db(null)).now(words, now, ZoneOffset.UTC)).isNull()
    }

    @Test fun `the taste is read once per computed_at, and again when a new one is written`() = runTest {
        val songs = (1..9).map { stat("Song $it", "Band") }
        val db = Db(taste(songs, computedAt = 10))
        val m = mixes(db)
        m.now(words, now, ZoneOffset.UTC)
        m.now(words, now, ZoneOffset.UTC)
        coVerify(exactly = 1) { db.dao.whole() }
        db.taste = taste(songs, computedAt = 11)
        assertThat(m.now(words, now, ZoneOffset.UTC)!!.taste.computedAt).isEqualTo(11)
        coVerify(exactly = 2) { db.dao.whole() }
    }

    @Test fun `kept out as the record shows it - a song put back and a song in the library play again`() = runTest {
        val songs = (1..7).map { stat("Song $it", "Band") } + stat("Skipped", "Band", avoid = true) + stat("Liked", "Band", avoid = true) +
            stat("Still out", "Band", avoid = true)
        val db = Db(taste(songs), keep = listOf(SongKey.songKey("Skipped", "Band")))
        // nothing put back, nothing liked: 7 songs, no mix
        assertThat(mixes(Db(taste(songs))).now(words, now, ZoneOffset.UTC)!!.all).isEmpty()
        val liked = LibrarySong(trackId = 42, title = "Liked", artist = "Band", album = "LP", durationMs = 181_000, art = "https://cdn-images.dzcdn.net/a.jpg")
        val made = mixes(db, library = listOf(liked)).now(words, now, ZoneOffset.UTC)!!
        val stayed = made.find("constants")!!
        assertThat(stayed.items.map { it.title }).containsExactly("Song 1", "Song 2", "Song 3", "Song 4", "Song 5", "Song 6", "Song 7", "Skipped", "Liked").inOrder()
        // the liked one is your own row (it plays your copy, its cover and length are yours)
        assertThat(stayed.items.single { it.title == "Liked" }.lib).isEqualTo(liked)
        assertThat(stayed.items.single { it.title == "Skipped" }.lib).isNull()
        assertThat(made.taste.songs.single { it.title == "Still out" }.avoid).isTrue()
    }

    @Test fun `this week reads the record as it stacks - covered scrobbles out, each id once, by start then id`() = runTest {
        val songs = listOf(stat("Never", "Avoided", avoid = true))
        val db = Db(taste(songs))
        val spotify = (0 until 6).map { row("Spotify $it", "Band", mid2025 + it * 3_600_000L) }
        val coveredScrobble = row("Phone song", "Band", mid2025 + 10 * 3_600_000L + 60_000, f = RecordFlags.LASTFM or RecordFlags.ESTIMATED)
        val freeScrobble = row("Scrobbled", "Band", mid2025 + 20 * 3_600_000L, f = RecordFlags.LASTFM)
        val avoided = row("Never", "Avoided", mid2025)
        // A phone play with an export play's very id: the export's row is the one kept (the record keeps the first)
        val twin = row("Spotify 0", "Band", mid2025, f = RecordFlags.STASH)
        val phone = row("Phone song", "Band", mid2025 + 10 * 3_600_000L, f = RecordFlags.STASH)
        val phoneTwice = row("Phone two", "Band", mid2025 + 11 * 3_600_000L, f = RecordFlags.STASH)
        val phoneSkip = row("Phone skip", "Band", mid2025 + 12 * 3_600_000L, f = RecordFlags.STASH or RecordFlags.SKIPPED, ms = 10_000)
        // Out of every window: never read
        val longAgo = row("Phone old", "Band", mid2025 - 30 * day, f = RecordFlags.STASH)
        val reads = mutableListOf<Pair<Long, Long>>()
        val ownReads = IntArray(1)
        val m = mixes(
            db,
            imported = spotify + coveredScrobble + freeScrobble + avoided,
            own = listOf(twin, phone, phoneTwice, phoneSkip, longAgo),
            reads = reads,
            ownReads = ownReads,
        )
        val week = m.now(words, now, ZoneOffset.UTC)!!.week!!
        assertThat(week.items.map { it.title }).containsExactly(
            // one play each, by key: the export's six, the phone's two plays (its skip is no listen), the free scrobble
            "Phone song", "Phone two", "Scrobbled", "Spotify 0", "Spotify 1", "Spotify 2", "Spotify 3", "Spotify 4", "Spotify 5",
        ).inOrder()
        assertThat(week.sub).isEqualTo("Around July 4, 2025")
        // a window a past year, back to the taste's first year (2020): six reads of the record by time
        assertThat(reads.map { it.first }).containsExactly(
            mid2025 - 3 * day, mid2025.minusYears(1) - 3 * day, mid2025.minusYears(2) - 3 * day, mid2025.minusYears(3) - 3 * day,
            mid2025.minusYears(4) - 3 * day, mid2025.minusYears(5) - 3 * day,
        ).inOrder()
        // read once a taste a day: the same day reads nothing again
        m.now(words, now + 3_600_000L, ZoneOffset.UTC)
        assertThat(reads).hasSize(6)
        assertThat(ownReads[0]).isEqualTo(1)
        // the next UTC day reads again
        m.now(words, now + day, ZoneOffset.UTC)
        assertThat(ownReads[0]).isEqualTo(2)
    }

    @Test fun `Home's few are picked by the local day`() = runTest {
        val songs = (1..9).map { stat("Song $it", "Band") }
        val db = Db(taste(songs))
        // 23:30 in UTC−5 on 3 July is already 4 July in UTC
        val evening = java.time.LocalDateTime.of(2026, 7, 4, 4, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        val m = mixes(db).now(words, evening, ZoneOffset.ofHours(-5))!!
        assertThat(m.day).isEqualTo(java.time.LocalDate.of(2026, 7, 3).toEpochDay())
        assertThat(mixes(db).now(words, evening, ZoneOffset.UTC)!!.day).isEqualTo(java.time.LocalDate.of(2026, 7, 4).toEpochDay())
    }

    @Test fun `a week line that can't be made loses only this week, never the other mixes`() = runTest {
        val db = Db(taste((1..9).map { stat("Song $it", "Band") }))
        val spotify = (0 until 9).map { row("Spotify $it", "Band", mid2025 + it * 3_600_000L) }
        val m = mixes(db, imported = spotify)
        assertThat(m.now(words, now, ZoneOffset.UTC)!!.week).isNotNull()
        val broken = object : MixWords by words {
            override fun weekOneYear(month0: Int, day: Int, year: Int): String = error("a format this phone can't make")
        }
        val made = m.now(broken, now, ZoneOffset.UTC)!!
        assertThat(made.week).isNull()
        assertThat(made.all.map { it.id }).containsExactly("constants")
    }

    @Test fun `the library is read once while it is the same (likes and your playlists), and again when it changes`() = runTest {
        val db = Db(taste((1..9).map { stat("Song $it", "Band") }))
        var reads = 0
        var stamp = 1
        val m = RecordMixes(
            tastes = db.dao,
            between = { _, _ -> emptyList() },
            own = RecordArchiveSource { _, emit -> emit(emptyList(), true) },
            library = { reads++; listOf(LibrarySong(trackId = 1, title = "Song 1", artist = "Band")) },
            libraryStamp = flowOf(Unit),
            libraryStampNow = { stamp },
            io = EmptyCoroutineContext,
        )
        m.now(words, now, ZoneOffset.UTC)
        val again = m.now(words, now, ZoneOffset.UTC)!!
        assertThat(reads).isEqualTo(1)
        assertThat(again.find("constants")!!.items.first().lib?.trackId).isEqualTo(1L)
        stamp = 2
        m.now(words, now, ZoneOffset.UTC)
        assertThat(reads).isEqualTo(2)
    }

    private fun Long.minusYears(n: Int): Long =
        java.time.Instant.ofEpochMilli(this).atOffset(ZoneOffset.UTC).minusYears(n.toLong()).toInstant().toEpochMilli()
}
