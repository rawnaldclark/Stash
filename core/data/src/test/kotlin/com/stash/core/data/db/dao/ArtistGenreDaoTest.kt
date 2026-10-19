package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.ArtistGenreEntity
import com.stash.core.data.db.entity.ArtistGenreEntity.Companion.FAILED_RETRY_MS
import com.stash.core.data.db.entity.ArtistGenreEntity.Companion.STATUS_FAILED
import com.stash.core.data.db.entity.ArtistGenreEntity.Companion.STATUS_TAGGED
import com.stash.core.data.db.entity.ArtistGenreEntity.Companion.STATUS_UNTAGGABLE
import com.stash.core.data.db.entity.ArtistGenreEntity.Companion.TAGGED_REFRESH_MS
import com.stash.core.data.db.entity.ArtistGenreEntity.Companion.UNTAGGABLE_RETRY_MS
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `ArtistGenreDao`: which listened artists [ArtistGenreDao.missingArtists] hands the worker (keyed
 * like the cache, most-listened first, retry ages honoured), and the NOCASE upsert.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ArtistGenreDaoTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: ArtistGenreDao

    private val now = 1_800_000_000_000L
    private var nextTrackId = 1L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.artistGenreDao()
    }

    @After fun tearDown() = db.close()

    /** A song by [artist] listened to [plays] times; returns its id. */
    private suspend fun listened(artist: String, plays: Int, origin: String? = null): Long {
        val id = nextTrackId++
        db.trackDao().insert(
            TrackEntity(
                id = id, title = "Song $id", artist = artist,
                canonicalTitle = "song $id", canonicalArtist = artist.lowercase(),
            ),
        )
        repeat(plays) { i ->
            db.listeningEventDao().insert(
                ListeningEventEntity(trackId = id, startedAt = now - 1_000L * (i + 1), originDevice = origin),
            )
        }
        return id
    }

    private fun row(name: String, status: String, fetchedAt: Long, genres: String = "[]") =
        ArtistGenreEntity(artistName = name, genres = genres, status = status, fetchedAt = fetchedAt)

    @Test fun `no history means nothing to fetch`() = runTest {
        assertTrue(dao.missingArtists(limit = 300, now = now).isEmpty())
    }

    @Test fun `most-listened artists come first, ties by name`() = runTest {
        listened("Bee", plays = 2)
        listened("Ant", plays = 2)
        listened("Cat", plays = 5)

        assertEquals(listOf("Cat", "Ant", "Bee"), dao.missingArtists(limit = 300, now = now))
    }

    @Test fun `credits collapse to the primary artist and add their listens`() = runTest {
        listened("Drake", plays = 1)
        listened("Drake, 21 Savage", plays = 1)
        listened("drake feat. Rihanna", plays = 1)
        listened("Future", plays = 2)

        // Drake: 3 listens across three credits, one key; Future: 2.
        assertEquals(listOf("Drake", "Future"), dao.missingArtists(limit = 300, now = now))
    }

    @Test fun `allowlisted comma names stay whole`() = runTest {
        listened("Earth, Wind & Fire", plays = 1)

        assertEquals(listOf("Earth, Wind & Fire"), dao.missingArtists(limit = 300, now = now))
    }

    @Test fun `blank artists are skipped and plays from another device count`() = runTest {
        listened("   ", plays = 4)
        listened("Web Artist", plays = 1, origin = "d_web00000000000")

        assertEquals(listOf("Web Artist"), dao.missingArtists(limit = 300, now = now))
    }

    @Test fun `limit caps the batch`() = runTest {
        listened("A", plays = 3)
        listened("B", plays = 2)
        listened("C", plays = 1)

        assertEquals(listOf("A", "B"), dao.missingArtists(limit = 2, now = now))
        assertTrue(dao.missingArtists(limit = 0, now = now).isEmpty())
    }

    @Test fun `tagged artists are not fetched again within the refresh age, whatever the case`() = runTest {
        listened("Drake", plays = 1)
        listened("Future", plays = 1)
        dao.upsertAll(listOf(row("DRAKE", STATUS_TAGGED, fetchedAt = now - TAGGED_REFRESH_MS + 60_000)))

        assertEquals(listOf("Future"), dao.missingArtists(limit = 300, now = now))
    }

    @Test fun `tagged artists are refreshed after 180 days`() = runTest {
        listened("Drake", plays = 1)
        dao.upsertAll(listOf(row("Drake", STATUS_TAGGED, fetchedAt = now - TAGGED_REFRESH_MS - 60_000)))

        assertEquals(listOf("Drake"), dao.missingArtists(limit = 300, now = now))
    }

    @Test fun `Clear history also empties the genre cache`() = runTest {
        listened("Drake", plays = 2)
        dao.upsertAll(listOf(row("Drake", STATUS_TAGGED, fetchedAt = now)))
        // The repository as Hilt builds it.
        val history = com.stash.core.data.listening.ListeningHistoryRepository(db.listeningHistoryDao(), dao)

        assertEquals(2, history.deleteAll())

        assertTrue(dao.observeAll().first().isEmpty())
    }

    @Test fun `an untaggable artist waits its retry age`() = runTest {
        listened("Fresh Miss", plays = 1)
        listened("Old Miss", plays = 1)
        dao.upsertAll(
            listOf(
                row("Fresh Miss", STATUS_UNTAGGABLE, fetchedAt = now - UNTAGGABLE_RETRY_MS + 60_000),
                row("Old Miss", STATUS_UNTAGGABLE, fetchedAt = now - UNTAGGABLE_RETRY_MS - 60_000),
            ),
        )

        assertEquals(listOf("Old Miss"), dao.missingArtists(limit = 300, now = now))
    }

    @Test fun `a failed artist is retried after a day`() = runTest {
        listened("Recent Fail", plays = 1)
        listened("Old Fail", plays = 1)
        dao.upsertAll(
            listOf(
                row("Recent Fail", STATUS_FAILED, fetchedAt = now - FAILED_RETRY_MS + 60_000),
                row("Old Fail", STATUS_FAILED, fetchedAt = now - FAILED_RETRY_MS - 60_000),
            ),
        )

        assertEquals(listOf("Old Fail"), dao.missingArtists(limit = 300, now = now))
    }

    @Test fun `upsert replaces a row case-insensitively and get finds it in any case`() = runTest {
        dao.upsertAll(listOf(row("Drake", STATUS_UNTAGGABLE, fetchedAt = 1L)))
        dao.upsertAll(listOf(row("drake", STATUS_TAGGED, fetchedAt = 2L, genres = """[{"g":"hip-hop","w":1.0}]""")))

        val all = dao.observeAll().first()
        assertEquals(1, all.size)
        assertEquals(STATUS_TAGGED, all.single().status)
        assertEquals(STATUS_TAGGED, dao.get("DRAKE")?.status)
        assertNull(dao.get("Future"))
    }

    @Test fun `an empty upsert is a no-op`() = runTest {
        dao.upsertAll(emptyList())
        assertTrue(dao.observeAll().first().isEmpty())
    }
}
