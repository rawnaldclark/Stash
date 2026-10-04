package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
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
 * [TrackDao.getLosslessUpgradeCandidates] and [TrackDao.setFlacNoMatchAt]: tracks that
 * came back with no lossless match are skipped for a cooldown and sorted after untried
 * ones, so a cancelled or paced sweep resumes instead of restarting from the same tracks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDaoFlacUpgradeCandidatesTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: TrackDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.trackDao()
    }

    @After fun tearDown() { db.close() }

    private suspend fun insertOpus(id: Long, flacNoMatchAt: Long? = null): Long = dao.insert(
        TrackEntity(
            id = id,
            title = "Track $id",
            artist = "Artist",
            filePath = "/music/artist/track-$id.opus",
            fileFormat = "opus",
            isDownloaded = true,
            canonicalTitle = "track $id",
            canonicalArtist = "artist",
            flacNoMatchAt = flacNoMatchAt,
        ),
    )

    @Test
    fun `a track that found no match inside the cooldown is skipped`() = runTest {
        val fresh = insertOpus(1L)
        insertOpus(2L, flacNoMatchAt = 9_000L)

        // retryBefore 5_000: the stamp at 9_000 is newer, so still cooling down.
        assertEquals(listOf(fresh), dao.getLosslessUpgradeCandidates(retryBefore = 5_000L).map { it.id })
    }

    @Test
    fun `a no-match track comes back once the cooldown has passed`() = runTest {
        insertOpus(1L, flacNoMatchAt = 1_000L)

        assertEquals(listOf(1L), dao.getLosslessUpgradeCandidates(retryBefore = 5_000L).map { it.id })
    }

    @Test
    fun `untried tracks come first, then the oldest no-match`() = runTest {
        insertOpus(1L, flacNoMatchAt = 3_000L)
        insertOpus(2L, flacNoMatchAt = 1_000L)
        insertOpus(3L)

        assertEquals(
            listOf(3L, 2L, 1L),
            dao.getLosslessUpgradeCandidates(retryBefore = 10_000L).map { it.id },
        )
    }

    @Test
    fun `stamping a track takes it out of the candidates until the cooldown ends`() = runTest {
        val id = insertOpus(1L)

        dao.setFlacNoMatchAt(id, 9_000L)

        assertEquals(emptyList<Long>(), dao.getLosslessUpgradeCandidates(retryBefore = 5_000L).map { it.id })
    }

    @Test
    fun `clearing the stamp makes the track a candidate again`() = runTest {
        val id = insertOpus(1L, flacNoMatchAt = 9_000L)

        dao.setFlacNoMatchAt(id, null)

        assertEquals(listOf(id), dao.getLosslessUpgradeCandidates(retryBefore = 5_000L).map { it.id })
    }
}