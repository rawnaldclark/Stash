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
import java.time.Instant

/**
 * [DownloadQueueDao.cancelWaitingForPlaylist]: turning a playlist's Download switch
 * off (#474) drops its downloads that haven't started, and nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DownloadQueueDaoCancelWaitingTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: DownloadQueueDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.downloadQueueDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `off drops only this playlist's waiting discovery rows`() = runTest {
        val off = playlist("Turned off", "custom_off")
        val kept = playlist("Still kept", "custom_kept", keepOffline = true)
        val followed = playlist("Followed", "share:abc", syncEnabled = true)
        // Synced but not a followed mix: a user's own playlist is created with sync on.
        val mineSynced = playlist("Mine, synced", "custom_mine", syncEnabled = true)
        val hiddenKept = playlist("Kept but hidden", "custom_hidden", keepOffline = true, isActive = false)

        val pending = song("Pending", off)
        val failed = song("Failed", off)
        val waiting = song("Waiting", off)
        val running = song("Running", off)
        val syncQueued = song("Sync queued", off)
        val alsoKept = song("Also kept", off, kept)
        val alsoFollowed = song("Also followed", off, followed)
        val alsoMineSynced = song("Also mine, synced", off, mineSynced)
        val onlyHiddenKept = song("Only hidden kept", off, hiddenKept)
        val elsewhere = song("Other playlist", kept)
        val removedHere = song("Removed here", off, removedFromFirst = true)
        val redownload = song("Re-download missing", off)

        row(pending, DownloadStatus.PENDING)
        row(failed, DownloadStatus.FAILED)
        row(waiting, DownloadStatus.WAITING_FOR_LOSSLESS)
        row(running, DownloadStatus.IN_PROGRESS)
        row(syncQueued, DownloadStatus.PENDING, syncId = db.syncHistoryDao().insert(SyncHistoryEntity()))
        row(alsoKept, DownloadStatus.PENDING)
        row(alsoFollowed, DownloadStatus.PENDING)
        row(alsoMineSynced, DownloadStatus.PENDING)
        row(onlyHiddenKept, DownloadStatus.PENDING)
        row(elsewhere, DownloadStatus.PENDING)
        row(removedHere, DownloadStatus.PENDING)
        row(redownload, DownloadStatus.PENDING, userRequested = true) // Library Health asked for it

        assertThat(dao.cancelWaitingForPlaylist(off)).isEqualTo(5)

        val left = listOf(
            pending, failed, waiting, running, syncQueued, alsoKept, alsoFollowed, alsoMineSynced,
            onlyHiddenKept, elsewhere, removedHere, redownload,
        ).filter { dao.getByTrackId(it) != null }
        assertThat(left).containsExactly(running, syncQueued, alsoKept, alsoFollowed, elsewhere, removedHere, redownload)
    }

    private suspend fun playlist(
        name: String,
        sourceId: String,
        keepOffline: Boolean = false,
        syncEnabled: Boolean = false,
        isActive: Boolean = true,
    ): Long = db.playlistDao().insert(
        PlaylistEntity(
            name = name, source = MusicSource.BOTH, sourceId = sourceId, type = PlaylistType.CUSTOM,
            keepOffline = keepOffline, syncEnabled = syncEnabled, isActive = isActive,
        )
    )

    /** A song in [playlistIds]; [removedFromFirst] soft-removes it from the first one. */
    private suspend fun song(title: String, vararg playlistIds: Long, removedFromFirst: Boolean = false): Long {
        val id = db.trackDao().insert(
            TrackEntity(
                title = title, artist = "A", durationMs = 1000L, source = MusicSource.SPOTIFY,
                canonicalTitle = title.lowercase(), canonicalArtist = "a",
            )
        )
        playlistIds.forEachIndexed { i, pid ->
            db.playlistDao().insertCrossRef(
                PlaylistTrackCrossRef(
                    playlistId = pid, trackId = id, position = 0,
                    removedAt = if (removedFromFirst && i == 0) Instant.now() else null,
                )
            )
        }
        return id
    }

    private suspend fun row(trackId: Long, status: DownloadStatus, syncId: Long? = null, userRequested: Boolean = false) {
        dao.insert(DownloadQueueEntity(trackId = trackId, syncId = syncId, status = status, userRequested = userRequested))
    }
}
