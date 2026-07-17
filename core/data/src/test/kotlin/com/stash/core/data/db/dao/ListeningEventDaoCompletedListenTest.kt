package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ListeningEventDaoCompletedListenTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: ListeningEventDao
    private lateinit var trackDao: TrackDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.listeningEventDao()
        trackDao = db.trackDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `completed listen inserts event and updates track stats atomically`() = runTest {
        trackDao.insert(track())
        val completedAt = 123_456L

        dao.recordCompletedListen(event(completedAt))

        assertEquals(listOf(event(completedAt).copy(id = 1L)), dao.pendingScrobbles())
        val updatedTrack = requireNotNull(trackDao.getById(TRACK_ID))
        assertEquals(1, updatedTrack.playCount)
        assertEquals(Instant.ofEpochMilli(completedAt), updatedTrack.lastPlayed)
    }

    @Test
    fun `backfill restores stats from existing completed history`() = runTest {
        trackDao.insert(track())
        dao.insert(event(123_456L))
        dao.insert(event(null).copy(startedAt = 234_567L))

        dao.backfillMissingTrackStats()
        dao.backfillMissingTrackStats()

        val updatedTrack = requireNotNull(trackDao.getById(TRACK_ID))
        assertEquals(2, updatedTrack.playCount)
        assertEquals(Instant.ofEpochMilli(234_567L), updatedTrack.lastPlayed)
    }

    @Test
    fun `track update failure rolls back listening event`() = runTest {
        trackDao.insert(track())
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_completed_listen
            BEFORE UPDATE OF play_count ON tracks
            BEGIN SELECT RAISE(ABORT, 'forced track update failure'); END
            """.trimIndent(),
        )

        val result = runCatching { dao.recordCompletedListen(event(123_456L)) }

        assertTrue(result.isFailure)
        assertTrue(dao.pendingScrobbles().isEmpty())
        val unchangedTrack = requireNotNull(trackDao.getById(TRACK_ID))
        assertEquals(0, unchangedTrack.playCount)
        assertEquals(null, unchangedTrack.lastPlayed)
    }

    private fun track() = TrackEntity(
        id = TRACK_ID,
        title = "Track",
        artist = "Artist",
        canonicalTitle = "track",
        canonicalArtist = "artist",
        isDownloaded = true,
    )

    private fun event(completedAt: Long?) = ListeningEventEntity(
        trackId = TRACK_ID,
        startedAt = 100_000L,
        completedAt = completedAt,
    )

    private companion object {
        const val TRACK_ID = 1L
    }
}
