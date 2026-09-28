package com.stash.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.sync.workers.DiscoveryDownloadWorker
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

    @Test fun `a sync queues every kept playlist, then starts one drain that follows the download setting`() = runTest {
        member(playlist("Kept 1", keepOffline = true), 0, track("A"))
        member(playlist("Kept 2", keepOffline = true), 0, track("B"))
        member(playlist("Not kept"), 0, track("C"))

        assertEquals(2, repo().queueKeptPlaylists())

        val waiting = db.downloadQueueDao().pendingDiscoveryDownloads().map { db.trackDao().getById(it.trackId)!!.title }
        assertEquals(setOf("A", "B"), waiting.toSet())
        // One drain for both playlists (a drain per playlist restarts it), on Wi-Fi as the setting says.
        assertEquals(listOf(NetworkType.UNMETERED), drains().map { it.constraints.requiredNetworkType })
    }

    @Test fun `background queueing waits for the download setting`() = runTest {
        val pid = playlist("Mix")
        member(pid, 0, track("A"))
        repo().queueDownloadsForPlaylist(pid, background = true)
        assertEquals(listOf(NetworkType.UNMETERED), drains().map { it.constraints.requiredNetworkType })
    }

    @Test fun `a tap after a background drain takes over on any network`() = runTest {
        val pid = playlist("Mix")
        member(pid, 0, track("A"))
        repo().queueDownloadsForPlaylist(pid, background = true)
        member(pid, 1, track("B"))
        repo().queueDownloadsForPlaylist(pid)

        val live = drains().filter { !it.state.isFinished }
        assertEquals(listOf(NetworkType.CONNECTED), live.map { it.constraints.requiredNetworkType })
    }

    @Test fun `a background drain after a tap never downgrades it`() = runTest {
        val pid = playlist("Mix")
        member(pid, 0, track("A"))
        repo().queueDownloadsForPlaylist(pid)
        member(pid, 1, track("B"))
        repo().queueDownloadsForPlaylist(pid, background = true)

        // The tap's drain still goes on any network; the background one waits behind it.
        val live = drains().filter { !it.state.isFinished }
            .map { it.state to it.constraints.requiredNetworkType }
        assertEquals(
            setOf(WorkInfo.State.ENQUEUED to NetworkType.CONNECTED, WorkInfo.State.BLOCKED to NetworkType.UNMETERED),
            live.toSet(),
        )
    }

    /**
     * A song an earlier Download-mode sync queued is stuck in Stream-only mode: that
     * mode never runs the sync's download step, and the row counts as handled. Queueing
     * the playlist hands it to the drain; another playlist's stuck song is left alone.
     */
    @Test fun `stream-only mode hands this playlist's stuck sync rows to the drain`() = runTest {
        val (stuck, failedOnce, otherPlaylists) = seedStuckSyncRows()

        assertEquals(2, repo(streamOnly = true).queueDownloadsForPlaylist(playlist("Kept")))

        val drainable = db.downloadQueueDao().pendingDiscoveryDownloads().map { it.trackId }
        assertEquals(setOf(stuck, failedOnce), drainable.toSet())
        assertEquals(syncId, db.downloadQueueDao().getByTrackId(otherPlaylists)!!.syncId)
        assertEquals(1, drains().size)
    }

    @Test fun `download mode leaves sync rows to the sync`() = runTest {
        seedStuckSyncRows()
        assertEquals(0, repo(streamOnly = false).queueDownloadsForPlaylist(playlist("Kept")))
        assertEquals(emptyList<Long>(), db.downloadQueueDao().pendingDiscoveryDownloads().map { it.trackId })
    }

    private var syncId = 0L

    /** Sync rows for songs of the playlist named "Kept" (created first here) and of another playlist. */
    private suspend fun seedStuckSyncRows(): Triple<Long, Long, Long> {
        syncId = db.syncHistoryDao().insert(SyncHistoryEntity())
        val kept = playlist("Kept")
        val stuck = member(kept, 0, track("Stuck"))
        val failedOnce = member(kept, 1, track("Failed once"))
        val otherPlaylists = member(playlist("Other"), 0, track("Other"))
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = stuck, syncId = syncId))
        db.downloadQueueDao().insert(
            DownloadQueueEntity(trackId = failedOnce, syncId = syncId, status = DownloadStatus.FAILED, retryCount = 1),
        )
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = otherPlaylists, syncId = syncId))
        return Triple(stuck, failedOnce, otherPlaylists)
    }

    private fun drains(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(DiscoveryDownloadWorker.UNIQUE_WORK_NAME).get()

    private suspend fun playlist(name: String, keepOffline: Boolean = false): Long =
        db.playlistDao().findBySourceId("custom_$name")?.id ?: db.playlistDao().insert(
            PlaylistEntity(
                name = name, source = MusicSource.BOTH, sourceId = "custom_$name", type = PlaylistType.CUSTOM,
                keepOffline = keepOffline,
            ),
        )

    private suspend fun track(title: String) = TrackEntity(
        title = title, artist = "A", durationMs = 1000L, source = MusicSource.SPOTIFY,
        canonicalTitle = title.lowercase(), canonicalArtist = "a",
    )

    private suspend fun member(playlistId: Long, position: Int, track: TrackEntity): Long {
        val id = db.trackDao().insert(track)
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = id, position = position))
        return id
    }

    private fun repo(streamOnly: Boolean = false): MusicRepositoryImpl {
        val network = mockk<DownloadNetworkPreference>()
        coEvery { network.current() } returns DownloadNetworkMode.WIFI_ANY
        val streaming = mockk<StreamingPreference>()
        coEvery { streaming.current() } returns streamOnly
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
            streamingPreference = streaming,
            localFileOps = mockk(relaxed = true),
            syncPreferencesManager = mockk(relaxed = true),
            singleTrackDownloadEnqueuer = mockk(relaxed = true),
            lastFmRecommendationSource = mockk(relaxed = true),
            sharedMixDao = mockk(relaxed = true),
        )
    }
}
