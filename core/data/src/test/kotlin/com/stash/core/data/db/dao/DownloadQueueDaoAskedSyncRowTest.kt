package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #761: the queue's own cleanups never drop a download the user asked for. A tap on a song a sync
 * once queued takes over the sync's row (it keeps its `sync_id`), and so does a Retry; the
 * cleanups that drain a sync's leftovers — [DownloadQueueDao.cancelDownloadsWithNoEnabledPlaylist]
 * at every cold start, [DownloadQueueDao.deleteOrphanedQueueEntries] in every sync's reconcile —
 * deleted those rows too, so the tap silently never downloaded. They still drain the sync's own
 * leftovers, and the user's own Cancel and Clear history still apply. Real Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DownloadQueueDaoAskedSyncRowTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: DownloadQueueDao
    private var syncId = 0L

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.downloadQueueDao()
        syncId = db.syncHistoryDao().insert(SyncHistoryEntity())
    }

    @After fun tearDown() = db.close()

    @Test fun `the cold start's cleanup keeps a sync's row the user asked for and drains the sync's own`() = runTest {
        val asked = syncRow(songInSwitchedOffMix(1), DownloadStatus.PENDING, userRequested = true)
        syncRow(songInSwitchedOffMix(2), DownloadStatus.PENDING, userRequested = false)

        assertEquals(1, dao.cancelDownloadsWithNoEnabledPlaylist())

        assertEquals(listOf(asked), remainingIds())
        // ...and a sync's download takes it, as it did before the cleanup dropped it.
        assertEquals(listOf(asked), dao.getAllPendingBySources(listOf("SPOTIFY")).map { it.id })
    }

    @Test fun `a sync's reconcile keeps the user's asks in every state it drains, and drains the rest`() = runTest {
        val states = listOf(
            DownloadStatus.PENDING,
            DownloadStatus.WAITING_FOR_LOSSLESS, // a tap waiting for a lossless copy
            DownloadStatus.FAILED, // a tap waiting for its Retry on Failed downloads
            DownloadStatus.SKIPPED, // a tap the user cancelled: History, until Clear history
        )
        val asked = states.mapIndexed { i, s -> syncRow(songInSwitchedOffMix(10L + i), s, userRequested = true) }
        states.forEachIndexed { i, s -> syncRow(songInSwitchedOffMix(20L + i), s, userRequested = false) }

        assertEquals(states.size, dao.deleteOrphanedQueueEntries())

        assertEquals(asked, remainingIds())
    }

    @Test fun `the user's own Cancel and Clear history still end a spared ask`() = runTest {
        val song = songInSwitchedOffMix(1)
        val asked = syncRow(song, DownloadStatus.PENDING, userRequested = true)
        dao.cancelDownloadsWithNoEnabledPlaylist()
        dao.deleteOrphanedQueueEntries()

        // Cancel on the Downloads screen (DownloadCancellationController's write).
        assertEquals(1, dao.markCancelledIfActive(asked, System.currentTimeMillis(), keptOut = true))
        assertEquals(DownloadStatus.SKIPPED, dao.getStatusById(asked))
        assertEquals(emptyList<Long>(), dao.getAllPendingBySources(listOf("SPOTIFY")).map { it.id })

        // Downloads › Clear history.
        dao.deleteCompleted()
        assertNull(dao.getByTrackId(song))
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    /** A Spotify song whose only playlist is a mix the user hasn't switched on. */
    private suspend fun songInSwitchedOffMix(id: Long): Long {
        db.trackDao().insert(
            TrackEntity(
                id = id,
                title = "Song $id",
                artist = "Artist",
                canonicalTitle = "song $id",
                canonicalArtist = "artist",
                source = MusicSource.SPOTIFY,
            ),
        )
        val mix = db.playlistDao().insert(
            PlaylistEntity(
                name = "Daily Mix $id",
                source = MusicSource.SPOTIFY,
                sourceId = "daily-$id",
                type = PlaylistType.DAILY_MIX,
                syncEnabled = false,
            ),
        )
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = mix, trackId = id, position = 0))
        return id
    }

    private suspend fun syncRow(trackId: Long, status: DownloadStatus, userRequested: Boolean): Long =
        dao.insert(
            DownloadQueueEntity(
                trackId = trackId,
                syncId = syncId,
                status = status,
                searchQuery = "artist - song $trackId",
                userRequested = userRequested,
            ),
        )

    private suspend fun remainingIds(): List<Long> =
        db.query("SELECT id FROM download_queue ORDER BY id", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }
}
