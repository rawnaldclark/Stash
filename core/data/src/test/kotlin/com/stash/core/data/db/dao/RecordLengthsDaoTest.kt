package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.RecordLengthEntity
import com.stash.core.data.db.entity.RecordPlayEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * What Your record's lengths pass reads and writes in the database: the scrobbles still guessed by spelling ([RecordPlayDao.guessed]),
 * a spelling timed by its id range ([RecordPlayDao.timeAll]), the scrobble count a run starts from, the songs it couldn't time
 * ([RecordLengthDao]) and the library's own lengths ([WebLibraryExportDao.libraryLengths]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecordLengthsDaoTest {

    private lateinit var db: StashDatabase
    private val plays get() = db.recordPlayDao()
    private val lengths get() = db.recordLengthDao()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    private fun row(id: String, t: String, a: String = "Band", ms: Long = 210_000, f: Int = GUESSED) =
        RecordPlayEntity(id = id, t = t, a = a, s = id.substringAfterLast('|').toLong(), ms = ms, f = f)

    @Test fun `the guessed scrobbles by spelling, and the scrobble count`() = runTest {
        plays.apply(
            listOf(
                row("song|band|1000", "Song"),
                row("song|band|2000", "Song"),
                row("song|band|3000", "SONG"),
                row("song|band|4000", "Song", a = "band"),
                row("song|band|5000", "Song", ms = 200_000, f = 64), // timed already
                row("song|band|6000", "Song", ms = 200_000, f = 2), // a Spotify play
                row("other|band|7000", "Other", f = 2 or 128), // estimated, but no scrobble
            ),
            emptyList(),
        )
        val got = plays.guessed().map { Triple(it.t, it.a, it.n) }.sortedWith(compareBy({ it.first }, { it.second }))
        assertEquals(listOf(Triple("SONG", "Band", 1), Triple("Song", "Band", 2), Triple("Song", "band", 1)), got)
        assertEquals(5, plays.scrobbleCount())
    }

    @Test fun `a spelling is timed within its title's ids, nothing else moves`() = runTest {
        plays.apply(
            listOf(
                row("song|band|1000", "Song"),
                row("song|band|2000", "Song"),
                row("song|band|3000", "Song", a = "Other Band"),
                row("song|band|4000", "Song", ms = 190_000, f = 64), // timed already: kept as it is
                row("song|band|5000", "Song", ms = 250_000, f = 2 or 34), // a Spotify play
                row("songs|band|6000", "Song"), // another title's range (as if folded so)
            ),
            emptyList(),
        )
        val n = plays.timeAll(listOf(RecordPlayDao.Spelling("Song", "Band", "song|", "song}")), 187_000)
        assertEquals(2, n)
        val by = plays.pageAfter("", 10).associate { it.id to (it.ms to it.f) }
        assertEquals(187_000L to 64, by["song|band|1000"])
        assertEquals(187_000L to 64, by["song|band|2000"])
        assertEquals(210_000L to GUESSED, by["song|band|3000"])
        assertEquals(190_000L to 64, by["song|band|4000"])
        assertEquals(250_000L to (2 or 34), by["song|band|5000"])
        assertEquals(210_000L to GUESSED, by["songs|band|6000"])
        // Timed once, a spelling is out of the guessed rows
        assertEquals(setOf("Song" to "Other Band", "Song" to "Band"), plays.guessed().map { it.t to it.a }.toSet())
    }

    @Test fun `the songs the pass couldn't time - replaced, moved in one step, kept once by key`() = runTest {
        lengths.replace(listOf(RecordLengthEntity("a", RecordLengthEntity.NONE, 10), RecordLengthEntity("b", RecordLengthEntity.FAILED, 20)))
        lengths.apply(put = listOf(RecordLengthEntity("a", RecordLengthEntity.FAILED, 30), RecordLengthEntity("c", RecordLengthEntity.NONE, 40)), drop = listOf("b"))
        assertEquals(
            listOf(RecordLengthEntity("a", RecordLengthEntity.FAILED, 30), RecordLengthEntity("c", RecordLengthEntity.NONE, 40)),
            lengths.all().sortedBy { it.key },
        )
        lengths.replace(listOf(RecordLengthEntity("d", RecordLengthEntity.NONE, 50)))
        assertEquals(listOf("d"), lengths.all().map { it.key })
        lengths.clear()
        assertEquals(emptyList<RecordLengthEntity>(), lengths.all())
    }

    @Test fun `the library's lengths are its liked, listed, downloaded and played songs, 1 s to 6 h, longest per spelling`() = runTest {
        suspend fun track(id: Long, title: String, ms: Long, liked: Boolean = false, downloaded: Boolean = false) =
            db.trackDao().insert(
                TrackEntity(
                    id = id, title = title, artist = "Band", album = "Album", durationMs = ms, isDownloaded = downloaded,
                    stashLikedAt = if (liked) 1L else null, canonicalTitle = title.lowercase(), canonicalArtist = "band",
                ),
            )
        track(1, "Liked", 200_000, liked = true)
        track(2, "Listed", 180_000)
        track(3, "Listed", 181_000) // the same words, longer: the one kept
        track(4, "Taken Out", 170_000)
        track(5, "Played", 160_000)
        track(6, "Downloaded", 150_000, downloaded = true)
        track(7, "Nowhere", 140_000)
        track(8, "Too Long", 6 * 3_600_000L + 1, liked = true)
        track(9, "Six Hours", 6 * 3_600_000L, liked = true)
        track(10, "Too Short", 999, liked = true)
        track(11, "One Second", 1000, liked = true)
        val list = db.playlistDao().insert(
            PlaylistEntity(name = "Mix", source = MusicSource.BOTH, sourceId = "mix", type = PlaylistType.DAILY_MIX, trackCount = 0, syncEnabled = true, isActive = true),
        )
        for ((i, id) in listOf(2L, 3L, 4L).withIndex()) {
            db.playlistDao().insertCrossRef(
                PlaylistTrackCrossRef(playlistId = list, trackId = id, position = i, addedAt = Instant.EPOCH, removedAt = if (id == 4L) Instant.now() else null),
            )
        }
        db.listeningEventDao().insert(ListeningEventEntity(trackId = 5, startedAt = 1_000))
        val got = db.webLibraryExportDao().libraryLengths().associate { it.title to it.durationMs }
        assertEquals(
            mapOf("Liked" to 200_000L, "Listed" to 181_000L, "Played" to 160_000L, "Downloaded" to 150_000L, "Six Hours" to 6 * 3_600_000L, "One Second" to 1000L),
            got,
        )
    }

    private companion object {
        const val GUESSED = 64 or 128
    }
}
