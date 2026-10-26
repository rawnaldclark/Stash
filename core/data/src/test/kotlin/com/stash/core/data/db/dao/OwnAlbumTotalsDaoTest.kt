package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackBlocklistEntity
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [WebLibraryExportDao.ownAlbumTotals]: this phone's plays, one row per artist and album as written, each play counted at its
 * song's length or the web's guess, the same plays [WebLibraryExportDao.ownPlaysBefore] reads (never another device's, never a
 * blocked song's, never one without words), the album played last first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OwnAlbumTotalsDaoTest {

    private lateinit var db: StashDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    private suspend fun track(id: Long, title: String, artist: String, album: String, durationMs: Long = 0, art: String? = null) {
        db.trackDao().insert(
            TrackEntity(
                id = id, title = title, artist = artist, album = album, durationMs = durationMs, albumArtUrl = art,
                canonicalTitle = title.lowercase(), canonicalArtist = artist.lowercase(),
            ),
        )
    }

    private suspend fun play(trackId: Long, at: Long, origin: String? = null) {
        db.listeningEventDao().insert(ListeningEventEntity(trackId = trackId, startedAt = at, originDevice = origin))
    }

    @Test fun `plays are totalled by artist and album, at the song's length or the guess`() = runTest {
        track(1, "Ivy", "Frank Ocean", "Blonde", durationMs = 249_000, art = "https://example.com/blonde.jpg")
        track(2, "Nights", "Frank Ocean", "Blonde") // no length known: 210,000
        track(3, "Borderline", "Tame Impala", "The Slow Rush", durationMs = 237_000)
        track(4, "Blocked", "Somebody", "Gone", durationMs = 100_000)
        track(5, "   ", "Nobody", "Untitled", durationMs = 100_000)
        db.trackBlocklistDao().insert(
            TrackBlocklistEntity(canonicalKey = "somebody|blocked", artist = "Somebody", title = "Blocked", blockedAt = 1, blockedFrom = "OTHER"),
        )
        play(1, T)
        play(1, T + 1_000)
        play(2, T + 2_000)
        play(3, T + 3_000)
        play(3, T + 9_000, origin = "d_web00000000000") // another device's
        play(4, T + 4_000)
        play(5, T + 5_000)
        play(3, 0) // no start

        val totals = db.webLibraryExportDao().ownAlbumTotals()

        assertEquals(listOf("The Slow Rush", "Blonde"), totals.map { it.album })
        val blonde = totals.single { it.album == "Blonde" }
        assertEquals(3, blonde.plays)
        assertEquals(2 * 249_000L + WebLibraryExportDao.GUESS_MS, blonde.listenedMs)
        assertEquals(T, blonde.firstAt)
        assertEquals(T + 2_000, blonde.lastAt)
        assertEquals("https://example.com/blonde.jpg", blonde.albumArtUrl)
        val rush = totals.single { it.album == "The Slow Rush" }
        assertEquals(1, rush.plays)
        assertEquals(237_000L, rush.listenedMs)
        // The same plays the export's own-play count sees
        assertEquals(db.webLibraryExportDao().ownPlayCount(), totals.sumOf { it.plays })
    }

    private companion object {
        const val T = 1_759_838_400_000L
    }
}
