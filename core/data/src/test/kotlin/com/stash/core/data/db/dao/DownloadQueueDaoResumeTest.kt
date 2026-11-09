package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackBlocklistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.flow.first
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
 * The queries the resume run (downloads that continue on their own) reads, against a real database:
 * - [DownloadQueueDao.hasResumableSyncDownload] answers yes exactly when the sync's pending feeder
 *   ([DownloadQueueDao.getAllPendingBySources]) would return a row: failed rows with retries left
 *   ride along with a run but never start one, or a song that keeps failing woke it up forever.
 * - [DownloadQueueDao.refundRetries] gives a retry back to rows a systemic failure sent back.
 * - [DownloadQueueDao.deferInProgressForTrack] defers the claimed row of a song with two rows.
 * - [DownloadQueueDao.revertToPendingKeepingRetries] puts a row back without using a retry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DownloadQueueDaoResumeTest {

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

    @After fun tearDown() { db.close() }

    /** One row per case: the EXISTS query and the pending feeder must agree on every one. */
    @Test fun `hasResumableSyncDownload agrees with the sync's pending feeder`() = runTest {
        val cases = listOf<Pair<String, suspend () -> Unit>>(
            "a pending sync row in a switched-on playlist" to {
                dao.insert(row(track(MusicSource.YOUTUBE, inSyncedPlaylist = true), syncId = syncId))
            },
            "a failed sync row with retries left" to {
                dao.insert(row(track(MusicSource.YOUTUBE, inSyncedPlaylist = true), syncId = syncId, status = DownloadStatus.FAILED, retries = 1))
            },
            "a failed sync row with no retries left" to {
                dao.insert(row(track(MusicSource.YOUTUBE, inSyncedPlaylist = true), syncId = syncId, status = DownloadStatus.FAILED, retries = 3))
            },
            "a sync row whose playlist is switched off" to {
                dao.insert(row(track(MusicSource.YOUTUBE, inSyncedPlaylist = false), syncId = syncId))
            },
            "a tapped row outside any playlist" to {
                dao.insert(row(track(MusicSource.YOUTUBE, inSyncedPlaylist = false), syncId = null, userRequested = true))
            },
            "a discovery row nobody asked for" to {
                dao.insert(row(track(MusicSource.YOUTUBE, inSyncedPlaylist = false), syncId = null))
            },
            "a sync row from a service that isn't connected" to {
                dao.insert(row(track(MusicSource.SPOTIFY, inSyncedPlaylist = true), syncId = syncId))
            },
            "a blocked song" to {
                val id = track(MusicSource.YOUTUBE, inSyncedPlaylist = true)
                val t = db.trackDao().getById(id)!!
                db.trackBlocklistDao().insert(
                    TrackBlocklistEntity(
                        canonicalKey = "${t.canonicalArtist}|${t.canonicalTitle}", artist = t.artist, title = t.title,
                        blockedAt = 0L, blockedFrom = "OTHER",
                    ),
                )
                dao.insert(row(id, syncId = syncId))
            },
            "a row already downloading" to {
                dao.insert(row(track(MusicSource.YOUTUBE, inSyncedPlaylist = true), syncId = syncId, status = DownloadStatus.IN_PROGRESS))
            },
            "a row waiting for lossless" to {
                dao.insert(row(track(MusicSource.YOUTUBE, inSyncedPlaylist = true), syncId = syncId, status = DownloadStatus.WAITING_FOR_LOSSLESS))
            },
        )
        val sources = listOf("YOUTUBE", "BOTH")
        for ((name, seed) in cases) {
            db.clearAllTables()
            syncId = db.syncHistoryDao().insert(SyncHistoryEntity())
            seed()
            assertEquals(name, dao.getAllPendingBySources(sources).isNotEmpty(), dao.hasResumableSyncDownload(sources))
        }
    }

    @Test fun `hasResumableSyncDownload sees the expected rows`() = runTest {
        val sources = listOf("YOUTUBE", "BOTH")
        assertEquals(false, dao.hasResumableSyncDownload(sources))
        dao.insert(row(track(MusicSource.YOUTUBE, inSyncedPlaylist = true), syncId = syncId))
        assertEquals(true, dao.hasResumableSyncDownload(sources))
    }

    @Test fun `a failed row with retries left rides along but never starts a run`() = runTest {
        val sources = listOf("YOUTUBE", "BOTH")
        dao.insert(row(track(MusicSource.YOUTUBE, inSyncedPlaylist = true), syncId = syncId, status = DownloadStatus.FAILED, retries = 1))

        assertEquals("a sync's run still takes it", 1, dao.getRetryableBySources(sources).size)
        assertEquals(false, dao.hasResumableSyncDownload(sources))
    }

    @Test fun `refundRetries gives one retry back to pending rows only`() = runTest {
        val back = dao.insert(row(track(MusicSource.YOUTUBE, true), syncId = syncId, retries = 2))
        val none = dao.insert(row(track(MusicSource.YOUTUBE, true), syncId = syncId, retries = 0))
        val failed = dao.insert(row(track(MusicSource.YOUTUBE, true), syncId = syncId, status = DownloadStatus.FAILED, retries = 3))

        assertEquals(2, dao.refundRetries(listOf(back, none, failed)))

        assertEquals(1, dao.getById(back)!!.retryCount)
        assertEquals("never below zero", 0, dao.getById(none)!!.retryCount)
        assertEquals(3, dao.getById(failed)!!.retryCount)
    }

    @Test fun `a deferral finds the claimed row of a song with two rows`() = runTest {
        val song = track(MusicSource.YOUTUBE, inSyncedPlaylist = true)
        // The oldest row, which getByTrackId answers with, is a finished tap; the sync's newer row is the claimed one.
        val older = dao.insert(row(song, syncId = null, status = DownloadStatus.COMPLETED, userRequested = true))
        val claimed = dao.insert(row(song, syncId = syncId, status = DownloadStatus.IN_PROGRESS))
        assertEquals(older, dao.getByTrackId(song)!!.id)

        assertEquals(1, dao.deferInProgressForTrack(song, "wait:lossless"))

        assertEquals(DownloadStatus.WAITING_FOR_LOSSLESS, dao.getStatusById(claimed))
        assertEquals("wait:lossless", dao.getById(claimed)!!.errorMessage)
        assertEquals(DownloadStatus.COMPLETED, dao.getStatusById(older))
    }

    @Test fun `a full phone puts the row back without using a retry, and never revives a cancel`() = runTest {
        val song = track(MusicSource.YOUTUBE, inSyncedPlaylist = true)
        val claimed = dao.insert(row(song, syncId = syncId, status = DownloadStatus.IN_PROGRESS, retries = 2))
        val cancelled = dao.insert(row(track(MusicSource.YOUTUBE, inSyncedPlaylist = true), syncId = syncId, status = DownloadStatus.SKIPPED))

        assertEquals(1, dao.revertToPendingKeepingRetries(claimed, "No space left on device", DownloadFailureType.STORAGE_FULL))
        assertEquals(0, dao.revertToPendingKeepingRetries(cancelled, "No space left on device", DownloadFailureType.STORAGE_FULL))

        val back = dao.getById(claimed)!!
        assertEquals(DownloadStatus.PENDING, back.status)
        assertEquals(2, back.retryCount)
        assertEquals(DownloadFailureType.STORAGE_FULL, back.failureType)
        assertEquals(DownloadStatus.SKIPPED, dao.getStatusById(cancelled))
    }

    @Test fun `the retry-all count is what retry all claims`() = runTest {
        dao.insert(row(track(MusicSource.YOUTUBE, true), syncId = syncId, status = DownloadStatus.FAILED, failure = DownloadFailureType.NETWORK))
        dao.insert(row(track(MusicSource.YOUTUBE, true), syncId = syncId, status = DownloadStatus.FAILED, failure = DownloadFailureType.STORAGE_FULL))
        dao.insert(row(track(MusicSource.YOUTUBE, true), syncId = syncId, status = DownloadStatus.FAILED, failure = DownloadFailureType.NO_MATCH))
        dao.insert(row(track(MusicSource.YOUTUBE, true), syncId = syncId, status = DownloadStatus.PENDING, failure = DownloadFailureType.NETWORK))

        assertEquals(2, dao.observeRetryableFailedCount().first())
        assertEquals(2, dao.atomicallyClaimAllForRetry().size)
    }

    private var nextTrack = 1L

    private suspend fun track(source: MusicSource, inSyncedPlaylist: Boolean): Long {
        val n = nextTrack++
        val id = db.trackDao().insert(
            TrackEntity(
                title = "Song $n", artist = "Artist $n", durationMs = 1000L, source = source,
                canonicalTitle = "song $n", canonicalArtist = "artist $n",
            ),
        )
        val playlist = db.playlistDao().insert(
            PlaylistEntity(
                name = "Playlist $n", source = MusicSource.YOUTUBE, sourceId = "pl_$n", type = PlaylistType.CUSTOM,
                syncEnabled = inSyncedPlaylist,
            ),
        )
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlist, trackId = id, position = 0, addedAt = Instant.EPOCH))
        return id
    }

    private fun row(
        trackId: Long,
        syncId: Long?,
        status: DownloadStatus = DownloadStatus.PENDING,
        retries: Int = 0,
        userRequested: Boolean = false,
        failure: DownloadFailureType = DownloadFailureType.NONE,
    ) = DownloadQueueEntity(
        trackId = trackId, syncId = syncId, status = status, retryCount = retries,
        userRequested = userRequested, failureType = failure, searchQuery = "artist - title",
    )
}
