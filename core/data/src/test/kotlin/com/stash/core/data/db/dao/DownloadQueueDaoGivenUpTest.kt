package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * [DownloadQueueDao.observeGivenUpTrackIds]: the songs a playlist's Download button (#474) leaves
 * out of its count, because they won't download unless the user acts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DownloadQueueDaoGivenUpTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: DownloadQueueDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.downloadQueueDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `failed, cancelled and dismissed songs are given up, waiting ones are not`() = runTest {
        val mix = playlist("Mix", "custom_mix")
        val other = playlist("Other", "custom_other")

        val unmatched = song("Unmatched", mix)
        val exhausted = song("Exhausted", mix)
        val failedOnce = song("Failed once", mix)
        val cancelled = song("Cancelled", mix)
        val dismissed = song("Dismissed", mix, matchDismissed = true)
        val pending = song("Pending", mix)
        val waiting = song("Waiting for lossless", mix)
        val running = song("Running", mix)
        song("Not queued yet", mix) // no row at all
        val downloaded = song("Downloaded", mix, downloaded = true)
        val removedHere = song("Removed here", mix, removedFromFirst = true)
        val onlyElsewhere = song("Only elsewhere", other)

        row(unmatched, DownloadStatus.FAILED, DownloadFailureType.NO_MATCH, retries = 1)
        row(exhausted, DownloadStatus.FAILED, DownloadFailureType.UNKNOWN, retries = 3)
        // Retries left counts too: resetExhaustedRetries hands exhausted rows back at every app
        // start, and no drain starts for a FAILED row, so "will retry" can mean never.
        row(failedOnce, DownloadStatus.FAILED, DownloadFailureType.UNKNOWN, retries = 1)
        row(cancelled, DownloadStatus.SKIPPED)
        row(pending, DownloadStatus.PENDING) // e.g. waiting for Wi-Fi: it will come
        row(waiting, DownloadStatus.WAITING_FOR_LOSSLESS)
        row(running, DownloadStatus.IN_PROGRESS)
        row(downloaded, DownloadStatus.FAILED, DownloadFailureType.NO_MATCH) // an old failed match; on disk now
        row(removedHere, DownloadStatus.FAILED, DownloadFailureType.UNKNOWN)
        row(onlyElsewhere, DownloadStatus.FAILED, DownloadFailureType.UNKNOWN)

        // Not given up: pending, waiting, running, not queued yet, downloaded, or not in this mix.
        assertThat(dao.observeGivenUpTrackIds(mix).first())
            .containsExactly(unmatched, exhausted, failedOnce, cancelled, dismissed)
    }

    private suspend fun playlist(name: String, sourceId: String): Long = db.playlistDao().insert(
        PlaylistEntity(name = name, source = MusicSource.BOTH, sourceId = sourceId, type = PlaylistType.CUSTOM, keepOffline = true),
    )

    /** A song in [playlistId]; [removedFromFirst] soft-removes it from there. */
    private suspend fun song(
        title: String,
        playlistId: Long,
        matchDismissed: Boolean = false,
        downloaded: Boolean = false,
        removedFromFirst: Boolean = false,
    ): Long {
        val id = db.trackDao().insert(
            TrackEntity(
                title = title, artist = "A", durationMs = 1000L, source = MusicSource.SPOTIFY,
                canonicalTitle = title.lowercase(), canonicalArtist = "a",
                matchDismissed = matchDismissed, isDownloaded = downloaded,
                filePath = if (downloaded) "/music/$title.flac" else null,
            )
        )
        db.playlistDao().insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId, trackId = id, position = 0,
                removedAt = if (removedFromFirst) Instant.now() else null,
            )
        )
        return id
    }

    private suspend fun row(
        trackId: Long,
        status: DownloadStatus,
        failureType: DownloadFailureType = DownloadFailureType.NONE,
        retries: Int = 0,
    ) {
        dao.insert(DownloadQueueEntity(trackId = trackId, syncId = null, status = status, failureType = failureType, retryCount = retries))
    }
}
