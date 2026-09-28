package com.stash.core.data.repository

import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.SharedMixDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The playlist page's Download switch (#474), end to end through [MusicRepositoryImpl]:
 * on queues now, off stops without deleting (and in Download mode also turns sync off),
 * and a song added to a kept playlist is queued. [MusicRepositoryImpl.queueDownloadsForPlaylist]
 * and queueKeptPlaylist are stubbed on a spy: their own rules, and the races between
 * on and off, are covered by MusicRepositoryQueuePlaylistTest.
 */
class MusicRepositoryPlaylistDownloadTest {

    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val playlistDao = mockk<PlaylistDao>(relaxed = true)
    private val sharedMixDao = mockk<SharedMixDao>(relaxed = true)
    private val downloadQueueDao = mockk<DownloadQueueDao>(relaxed = true)
    private val streamingPreference = mockk<StreamingPreference>()

    init {
        coEvery { trackDao.getById(any()) } answers { track(firstArg()) }
        every { trackDao.getByPlaylist(any(), includeStreamable = true) } returns flowOf(emptyList())
        coEvery { playlistDao.getById(KEPT) } returns playlist(KEPT, keepOffline = true)
        coEvery { playlistDao.getById(PLAIN) } returns playlist(PLAIN, keepOffline = false)
        coEvery { sharedMixDao.forPlaylist(any()) } returns null
    }

    @Test fun `on sets the flag and queues the playlist now`() = runTest {
        val repo = repo()
        repo.setPlaylistDownload(PLAIN, on = true)
        coVerify { playlistDao.setKeepOffline(PLAIN, true) }
        coVerify(exactly = 1) { repo.queueKeptPlaylist(PLAIN) }
        coVerify(exactly = 0) { playlistDao.setSyncEnabled(any(), any()) }
        coVerify(exactly = 0) { downloadQueueDao.cancelWaitingForPlaylist(any()) }
    }

    @Test fun `off drops the playlist's songs still waiting, in either mode`() = runTest {
        for (streamOnly in listOf(true, false)) {
            coEvery { streamingPreference.current() } returns streamOnly
            repo().setPlaylistDownload(KEPT, on = false)
        }
        coVerify(exactly = 2) { downloadQueueDao.cancelWaitingForPlaylist(KEPT) }
    }

    @Test fun `off clears the flag and deletes nothing`() = runTest {
        coEvery { streamingPreference.current() } returns false
        val repo = repo()
        repo.setPlaylistDownload(KEPT, on = false)
        coVerify { playlistDao.setKeepOffline(KEPT, false) }
        coVerify(exactly = 0) { repo.removeDownloadsForPlaylist(any()) }
        coVerify(exactly = 0) { repo.removeDownload(any()) }
        coVerify(exactly = 0) { trackDao.clearDownloadState(any()) }
        coVerify(exactly = 0) { repo.queueDownloadsForPlaylist(any(), any()) }
        coVerify(exactly = 0) { repo.queueKeptPlaylist(any()) }
    }

    @Test fun `off in Download mode also turns sync off`() = runTest {
        coEvery { streamingPreference.current() } returns false
        repo().setPlaylistDownload(KEPT, on = false)
        coVerify(exactly = 1) { playlistDao.setSyncEnabled(KEPT, false) }
        coVerify(exactly = 0) { playlistDao.setSyncEnabledAndPin(any(), any(), any()) }
        // And, like the Sync tab's off, drops sync-queued songs no synced playlist wants.
        coVerify(exactly = 1) { downloadQueueDao.cancelDownloadsWithNoEnabledPlaylist() }
    }

    @Test fun `off in Stream-only mode leaves sync alone`() = runTest {
        coEvery { streamingPreference.current() } returns true
        repo().setPlaylistDownload(KEPT, on = false)
        coVerify(exactly = 0) { playlistDao.setSyncEnabled(any(), any()) }
        coVerify(exactly = 0) { downloadQueueDao.cancelDownloadsWithNoEnabledPlaylist() }
    }

    @Test fun `adding a song to a kept playlist queues it`() = runTest {
        val repo = repo()
        repo.addTrackToPlaylist(1L, KEPT)
        coVerify(exactly = 1) { repo.queueKeptPlaylist(KEPT) }
    }

    @Test fun `adding a song to an ordinary playlist queues nothing`() = runTest {
        val repo = repo()
        repo.addTrackToPlaylist(1L, PLAIN)
        repo.addTracksToPlaylist(listOf(1L, 2L), PLAIN)
        coVerify(exactly = 0) { repo.queueKeptPlaylist(any()) }
    }

    @Test fun `a batch added to a kept playlist queues once, after the adds`() = runTest {
        val repo = repo()
        repo.addTracksToPlaylist(listOf(1L, 2L, 3L), KEPT)
        coVerify(exactly = 3) { playlistDao.insertCrossRef(any()) }
        coVerify(exactly = 1) { repo.queueKeptPlaylist(KEPT) }
    }

    @Test fun `a failed queue never fails the add`() = runTest {
        val repo = repo()
        coEvery { repo.queueKeptPlaylist(KEPT) } throws IllegalStateException("db")
        repo.addTrackToPlaylist(1L, KEPT) // must not throw
        coVerify(exactly = 1) { playlistDao.insertCrossRef(any()) }
    }

    private fun track(id: Long) = TrackEntity(
        id = id, title = "T$id", artist = "A$id", durationMs = 1000L, source = MusicSource.SPOTIFY,
        canonicalTitle = "t$id", canonicalArtist = "a$id",
    )

    private fun playlist(id: Long, keepOffline: Boolean) = PlaylistEntity(
        id = id, name = "P$id", source = MusicSource.BOTH, sourceId = "custom_$id",
        type = PlaylistType.CUSTOM, keepOffline = keepOffline,
    )

    private fun repo(): MusicRepositoryImpl = spyk(
        MusicRepositoryImpl(
            context = mockk(relaxed = true),
            trackDao = trackDao,
            playlistDao = playlistDao,
            syncHistoryDao = mockk(relaxed = true),
            downloadQueueDao = downloadQueueDao,
            discoveryQueueDao = mockk(relaxed = true),
            blocklistGuard = mockk(relaxed = true),
            trackMatcher = mockk(relaxed = true),
            stashMixRecipeDao = mockk(relaxed = true),
            downloadNetworkPreference = mockk(relaxed = true),
            streamingPreference = streamingPreference,
            localFileOps = mockk(relaxed = true),
            syncPreferencesManager = mockk(relaxed = true),
            singleTrackDownloadEnqueuer = mockk(relaxed = true),
            lastFmRecommendationSource = mockk(relaxed = true),
            sharedMixDao = sharedMixDao,
        ),
    ).also {
        coEvery { it.queueDownloadsForPlaylist(any(), any()) } returns 0
        coEvery { it.queueKeptPlaylist(any()) } just Runs
    }

    private companion object {
        const val KEPT = 7L
        const val PLAIN = 8L
    }
}
