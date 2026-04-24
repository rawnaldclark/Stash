package com.stash.core.data.repository

import android.content.Context
import com.stash.core.common.ArtUrlUpgrader
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicRepositoryDownloadsMixTest {

    @Test
    fun `ensureDownloadsMixSeeded inserts a new playlist when none exists`() = runTest {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        coEvery { playlistDao.findBySourceId("stash_downloads_mix") } returns null
        val inserted = slot<PlaylistEntity>()
        coEvery { playlistDao.insert(capture(inserted)) } returns 42L

        val repo = buildRepo(playlistDao = playlistDao)
        val id = repo.ensureDownloadsMixSeeded()

        assertEquals(42L, id)
        assertEquals(PlaylistType.DOWNLOADS_MIX, inserted.captured.type)
        assertEquals(MusicSource.BOTH, inserted.captured.source)
        assertEquals("stash_downloads_mix", inserted.captured.sourceId)
        assertEquals("Your Downloads", inserted.captured.name)
        assertEquals(false, inserted.captured.syncEnabled)
    }

    @Test
    fun `ensureDownloadsMixSeeded is idempotent when playlist already exists`() = runTest {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val existing = PlaylistEntity(
            id = 7L,
            name = "Your Downloads",
            source = MusicSource.BOTH,
            sourceId = "stash_downloads_mix",
            type = PlaylistType.DOWNLOADS_MIX,
        )
        coEvery { playlistDao.findBySourceId("stash_downloads_mix") } returns existing

        val repo = buildRepo(playlistDao = playlistDao)
        val id = repo.ensureDownloadsMixSeeded()

        assertEquals(7L, id)
        coVerify(exactly = 0) { playlistDao.insert(any()) }
    }

    @Test
    fun `linkTrackToDownloadsMix seeds then adds track when no existing link`() = runTest {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        coEvery { playlistDao.findBySourceId("stash_downloads_mix") } returns null
        coEvery { playlistDao.insert(any()) } returns 42L
        coEvery { playlistDao.getCrossRef(42L, 99L) } returns null
        coEvery { playlistDao.getNextPosition(42L) } returns 0

        // addTrackToPlaylist calls trackDao.getByPlaylist(...).first() to update the
        // track count — return an empty list so first() doesn't block.
        val trackDao = mockk<TrackDao>(relaxed = true)
        coEvery { trackDao.getByPlaylist(42L) } returns flowOf(emptyList())

        val repo = buildRepo(playlistDao = playlistDao, trackDao = trackDao)
        repo.linkTrackToDownloadsMix(trackId = 99L)

        coVerify { playlistDao.insertCrossRef(match { it.playlistId == 42L && it.trackId == 99L }) }
    }

    @Test
    fun `linkTrackToDownloadsMix is a no-op when link already exists`() = runTest {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val existing = PlaylistEntity(
            id = 7L,
            name = "Your Downloads",
            source = MusicSource.BOTH,
            sourceId = "stash_downloads_mix",
            type = PlaylistType.DOWNLOADS_MIX,
        )
        coEvery { playlistDao.findBySourceId("stash_downloads_mix") } returns existing
        coEvery { playlistDao.getCrossRef(7L, 99L) } returns
            PlaylistTrackCrossRef(playlistId = 7L, trackId = 99L, position = 0)

        val repo = buildRepo(playlistDao = playlistDao)
        repo.linkTrackToDownloadsMix(trackId = 99L)

        coVerify(exactly = 0) { playlistDao.insertCrossRef(any()) }
    }

    private fun buildRepo(
        playlistDao: PlaylistDao = mockk(relaxed = true),
        context: Context = mockk(relaxed = true),
        trackDao: TrackDao = mockk(relaxed = true),
        syncHistoryDao: SyncHistoryDao = mockk(relaxed = true),
        downloadQueueDao: DownloadQueueDao = mockk(relaxed = true),
        discoveryQueueDao: DiscoveryQueueDao = mockk(relaxed = true),
    ): MusicRepositoryImpl = MusicRepositoryImpl(
        context = context,
        trackDao = trackDao,
        playlistDao = playlistDao,
        syncHistoryDao = syncHistoryDao,
        downloadQueueDao = downloadQueueDao,
        discoveryQueueDao = discoveryQueueDao,
    )
}
