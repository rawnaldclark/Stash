package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
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
import java.time.Instant

/**
 * Behavioural tests for the atomic mix-membership writers
 * [PlaylistDao.replaceMixMembership] and [PlaylistDao.createMixWithMembership]
 * (audit: materializeMix FK-787 torn-prefix guard). The worker-level MockK
 * tests can only observe the DAO surface — they cannot prove the @Transaction
 * actually rolls back — so the atomicity guarantee is verified here against a
 * real in-memory database with foreign keys enforced (a cross-ref insert for a
 * missing track raises SQLITE_CONSTRAINT_FOREIGNKEY 787, as the sibling
 * DiscoveryQueueDao cap test already relies on).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistDaoMixMembershipTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: PlaylistDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.playlistDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `replaceMixMembership swaps ordered membership, name and count`() = runTest {
        db.trackDao().insert(track(1L))
        db.trackDao().insert(track(2L))
        db.trackDao().insert(track(3L))
        val id = dao.insert(mix("Original", "stash_mix_1"))
        dao.replaceMixMembership(id, listOf(1L, 2L), "Original", Instant.EPOCH)

        dao.replaceMixMembership(id, listOf(3L, 1L), "Renamed", Instant.EPOCH)

        assertEquals(listOf(3L, 1L), dao.getOrderedTrackIdsForPlaylist(id))
        assertEquals("Renamed", dao.getById(id)!!.name)
        assertEquals(2, dao.getById(id)!!.trackCount)
    }

    @Test fun `replaceMixMembership rolls back the whole swap when a track id is missing`() = runTest {
        db.trackDao().insert(track(1L))
        db.trackDao().insert(track(2L))
        val id = dao.insert(mix("Original", "stash_mix_1"))
        dao.replaceMixMembership(id, listOf(1L, 2L), "Original", Instant.EPOCH)

        // 999 has no track row → its cross-ref insert raises FK 787 mid-swap,
        // after the clear + rename + first insert have already run.
        val result = runCatching {
            dao.replaceMixMembership(id, listOf(1L, 999L), "Renamed", Instant.EPOCH)
        }

        assertTrue("the missing-track swap must fail", result.isFailure)
        // The entire rewrite rolled back: the PREVIOUS membership, name and
        // count survive intact — not a torn prefix of [1] under "Renamed".
        assertEquals(listOf(1L, 2L), dao.getOrderedTrackIdsForPlaylist(id))
        assertEquals("Original", dao.getById(id)!!.name)
        assertEquals(2, dao.getById(id)!!.trackCount)
    }

    @Test fun `createMixWithMembership creates, populates and returns the new id`() = runTest {
        db.trackDao().insert(track(1L))
        db.trackDao().insert(track(2L))

        val id = dao.createMixWithMembership(
            mix("Fresh", "stash_mix_2"),
            listOf(1L, 2L),
            Instant.EPOCH,
        )

        assertEquals(listOf(1L, 2L), dao.getOrderedTrackIdsForPlaylist(id))
        assertEquals(2, dao.getById(id)!!.trackCount)
    }

    @Test fun `createMixWithMembership rolls back the whole create when a track id is missing`() = runTest {
        db.trackDao().insert(track(1L))

        val result = runCatching {
            dao.createMixWithMembership(
                mix("Fresh", "stash_mix_2"),
                listOf(1L, 999L),
                Instant.EPOCH,
            )
        }

        assertTrue("the missing-track create must fail", result.isFailure)
        // The playlist row inserted inside the same txn rolled back too — no
        // half-populated ghost mix is left behind for a later sweep to find.
        assertNull(dao.findBySourceId("stash_mix_2"))
    }

    @Test fun `refreshMixCover keeps the cover while no song has art, then builds the mosaic`() = runTest {
        db.trackDao().insert(track(1L))
        db.trackDao().insert(track(2L))
        val id = dao.createMixWithMembership(mix("Daily Discover", "stash_mix_1"), listOf(1L, 2L), Instant.EPOCH)

        dao.refreshMixCover(id)
        assertNull(dao.getById(id)!!.artUrl)

        db.trackDao().fillMissingAlbumArtUrl(2L, "https://img/2.png")
        db.trackDao().fillMissingAlbumArtUrl(1L, "https://img/1.png")
        dao.refreshMixCover(id)

        // In song order, whichever song got its art first.
        assertEquals("https://img/1.png|https://img/2.png", dao.getById(id)!!.artUrl)
    }

    @Test fun `getStashMixPlaylistIds lists only Stash Mixes`() = runTest {
        val mixId = dao.insert(mix("Daily Discover", "stash_mix_1"))
        dao.insert(mix("Mine", "mine").copy(type = PlaylistType.CUSTOM))

        assertEquals(listOf(mixId), dao.getStashMixPlaylistIds())
    }

    private fun mix(name: String, sourceId: String) = PlaylistEntity(
        name = name,
        source = MusicSource.BOTH,
        sourceId = sourceId,
        type = PlaylistType.STASH_MIX,
        syncEnabled = true,
        isActive = true,
    )

    private fun track(id: Long) = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        canonicalTitle = "track $id",
        canonicalArtist = "artist $id",
        isDownloaded = true,
        isStreamable = false,
    )
}
