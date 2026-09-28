package com.stash.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.model.DownloadNetworkMode
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [MusicRepositoryImpl.queueDownloadsForPlaylist] now runs after every sync for a
 * kept playlist (#474), so it has to be a true no-op the second time: a song that
 * already failed, was cancelled, or had its match dismissed keeps its one row
 * instead of getting a fresh one per call. Real DB, real rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MusicRepositoryQueuePlaylistTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() { db.close() }

    @Test fun `queues only never-tried songs, and a second call adds nothing`() = runTest {
        val pid = db.playlistDao().insert(
            PlaylistEntity(name = "Kept", source = MusicSource.BOTH, sourceId = "custom_1", type = PlaylistType.CUSTOM),
        )
        val fresh = member(pid, 0, track("Fresh"))
        val failed = member(pid, 1, track("Failed"))
        val cancelled = member(pid, 2, track("Cancelled"))
        member(pid, 3, track("Dismissed").copy(matchDismissed = true))
        member(pid, 4, track("Downloaded").copy(isDownloaded = true, filePath = "/x.flac"))
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = failed, status = DownloadStatus.FAILED, retryCount = 3))
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = cancelled, status = DownloadStatus.SKIPPED))

        val repo = repo()
        assertEquals(1, repo.queueDownloadsForPlaylist(pid))
        assertEquals(0, repo.queueDownloadsForPlaylist(pid))

        val pending = db.downloadQueueDao().pendingDiscoveryDownloads().filter { it.status == DownloadStatus.PENDING }
        assertEquals(listOf(fresh), pending.map { it.trackId })
    }

    private suspend fun track(title: String) = TrackEntity(
        title = title, artist = "A", durationMs = 1000L, source = MusicSource.SPOTIFY,
        canonicalTitle = title.lowercase(), canonicalArtist = "a",
    )

    private suspend fun member(playlistId: Long, position: Int, track: TrackEntity): Long {
        val id = db.trackDao().insert(track)
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = id, position = position))
        return id
    }

    private fun repo(): MusicRepositoryImpl {
        val network = mockk<DownloadNetworkPreference>()
        coEvery { network.current() } returns DownloadNetworkMode.entries.first()
        return MusicRepositoryImpl(
            context = context,
            trackDao = db.trackDao(),
            playlistDao = db.playlistDao(),
            syncHistoryDao = mockk(relaxed = true),
            downloadQueueDao = db.downloadQueueDao(),
            discoveryQueueDao = mockk(relaxed = true),
            blocklistGuard = mockk(relaxed = true),
            trackMatcher = mockk(relaxed = true),
            stashMixRecipeDao = mockk(relaxed = true),
            downloadNetworkPreference = network,
            streamingPreference = mockk(relaxed = true),
            localFileOps = mockk(relaxed = true),
            syncPreferencesManager = mockk(relaxed = true),
            singleTrackDownloadEnqueuer = mockk(relaxed = true),
            lastFmRecommendationSource = mockk(relaxed = true),
            sharedMixDao = mockk(relaxed = true),
        )
    }
}
