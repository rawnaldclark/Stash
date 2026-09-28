package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/**
 * [DownloadQueueDao.deleteLegacyStashMixDownloads]: the one-time clean-up (#474)
 * of the download rows Stash Mixes filed before v0.9.37, which nothing holds back
 * any more now that turning Stash Mixes off no longer cancels the download worker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DownloadQueueDaoLegacyMixCleanupTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: DownloadQueueDao
    private val cutoff = Instant.parse("2026-05-26T00:00:00Z")
    private val old = cutoff.minus(Duration.ofDays(30))

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.downloadQueueDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `drops only old Stash Mixes rows that never started`() = runTest {
        val kept = playlist("Kept", "custom_kept", keepOffline = true)
        val followed = playlist("Followed", "share:abc", syncEnabled = true)
        val syncId = db.syncHistoryDao().insert(SyncHistoryEntity())

        val pending = row(song("Pending"), DownloadStatus.PENDING)
        val failed = row(song("Failed"), DownloadStatus.FAILED)
        val waiting = row(song("Waiting"), DownloadStatus.WAITING_FOR_LOSSLESS)
        val running = row(song("Running"), DownloadStatus.IN_PROGRESS)
        val done = row(song("Done"), DownloadStatus.COMPLETED)
        val redownload = row(song("Re-download missing"), DownloadStatus.PENDING, userRequested = true)
        val syncQueued = row(song("Sync queued"), DownloadStatus.PENDING, syncId = syncId)
        val inKept = row(song("In a kept playlist", kept), DownloadStatus.PENDING)
        val inFollowed = row(song("In a followed mix", followed), DownloadStatus.PENDING)
        val recent = row(song("Recent"), DownloadStatus.PENDING, createdAt = Instant.now())

        assertThat(dao.deleteLegacyStashMixDownloads(cutoff.toEpochMilli())).isEqualTo(3)

        val left = listOf(pending, failed, waiting, running, done, redownload, syncQueued, inKept, inFollowed, recent)
            .filter { dao.getByTrackId(it) != null }
        assertThat(left).containsExactly(running, done, redownload, syncQueued, inKept, inFollowed, recent)
    }

    @Test fun `a current install deletes nothing`() = runTest {
        row(song("Tapped today"), DownloadStatus.PENDING, createdAt = Instant.now())
        row(song("Failed today"), DownloadStatus.FAILED, createdAt = Instant.now())
        assertThat(dao.deleteLegacyStashMixDownloads(cutoff.toEpochMilli())).isEqualTo(0)
    }

    private suspend fun playlist(
        name: String,
        sourceId: String,
        keepOffline: Boolean = false,
        syncEnabled: Boolean = false,
    ): Long = db.playlistDao().insert(
        PlaylistEntity(
            name = name, source = MusicSource.BOTH, sourceId = sourceId, type = PlaylistType.CUSTOM,
            keepOffline = keepOffline, syncEnabled = syncEnabled,
        )
    )

    private suspend fun song(title: String, vararg playlistIds: Long): Long {
        val id = db.trackDao().insert(
            TrackEntity(
                title = title, artist = "A", durationMs = 1000L, source = MusicSource.SPOTIFY,
                canonicalTitle = title.lowercase(), canonicalArtist = "a",
            )
        )
        playlistIds.forEach { pid ->
            db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = pid, trackId = id, position = 0))
        }
        return id
    }

    /** A queue row for [trackId], created before the cutoff unless [createdAt] says otherwise. Returns the track id. */
    private suspend fun row(
        trackId: Long,
        status: DownloadStatus,
        syncId: Long? = null,
        userRequested: Boolean = false,
        createdAt: Instant = old,
    ): Long {
        dao.insert(
            DownloadQueueEntity(
                trackId = trackId, syncId = syncId, status = status,
                userRequested = userRequested, createdAt = createdAt,
            )
        )
        return trackId
    }
}
