package com.stash.core.data.social.stash

import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class StashLikedPlaylistRepositoryTest {

    @Test
    fun `add seeds playlist art from local album art before remote art`() = runTest {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        val musicRepository = mockk<MusicRepository>(relaxed = true)
        coEvery { trackDao.getById(11L) } returns track(
            albumArtPath = "/music/cover.jpg",
            albumArtUrl = "https://cdn.example/cover.jpg",
        )
        coEvery { playlistDao.findBySourceId("stash_liked_songs") } returns null
        val inserted = slot<PlaylistEntity>()
        coEvery { playlistDao.insert(capture(inserted)) } returns 7L
        coEvery { playlistDao.getCrossRef(7L, 11L) } returns null

        StashLikedPlaylistRepository(playlistDao, trackDao, musicRepository).add(11L)

        assertEquals("/music/cover.jpg", inserted.captured.artUrl)
    }

    @Test
    fun `re-tapping an existing like repairs blank art from remote fallback`() = runTest {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        val musicRepository = mockk<MusicRepository>(relaxed = true)
        coEvery { trackDao.getById(11L) } returns track(
            albumArtPath = " ",
            albumArtUrl = "https://cdn.example/cover.jpg",
        )
        coEvery { playlistDao.findBySourceId("stash_liked_songs") } returns likedPlaylist(artUrl = "\t")
        coEvery { playlistDao.getCrossRef(7L, 11L) } returns existingMembership()

        StashLikedPlaylistRepository(playlistDao, trackDao, musicRepository).add(11L)

        coVerify { playlistDao.updateArtUrl(7L, "https://cdn.example/cover.jpg") }
        coVerify(exactly = 0) { musicRepository.addTrackToPlaylist(any(), any()) }
    }

    @Test
    fun `add ignores blank track artwork`() = runTest {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        coEvery { trackDao.getById(11L) } returns track(albumArtPath = " ", albumArtUrl = "\t")
        coEvery { playlistDao.findBySourceId("stash_liked_songs") } returns likedPlaylist(artUrl = null)
        coEvery { playlistDao.getCrossRef(7L, 11L) } returns existingMembership()

        StashLikedPlaylistRepository(playlistDao, trackDao, mockk(relaxed = true)).add(11L)

        coVerify(exactly = 0) { playlistDao.updateArtUrl(any(), any()) }
    }

    @Test
    fun `add preserves established playlist artwork`() = runTest {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        coEvery { trackDao.getById(11L) } returns track(albumArtPath = "/music/new-cover.jpg")
        coEvery { playlistDao.findBySourceId("stash_liked_songs") } returns
            likedPlaylist(artUrl = "/music/established-cover.jpg")
        coEvery { playlistDao.getCrossRef(7L, 11L) } returns existingMembership()

        StashLikedPlaylistRepository(playlistDao, trackDao, mockk(relaxed = true)).add(11L)

        coVerify(exactly = 0) { playlistDao.updateArtUrl(any(), any()) }
    }

    private fun track(albumArtPath: String? = null, albumArtUrl: String? = null) = TrackEntity(
        id = 11L,
        title = "Track",
        artist = "Artist",
        albumArtPath = albumArtPath,
        albumArtUrl = albumArtUrl,
    )

    private fun likedPlaylist(artUrl: String?) = PlaylistEntity(
        id = 7L,
        name = "Liked Songs",
        source = MusicSource.BOTH,
        sourceId = "stash_liked_songs",
        type = PlaylistType.STASH_LIKED,
        artUrl = artUrl,
    )

    private fun existingMembership() = PlaylistTrackCrossRef(
        playlistId = 7L,
        trackId = 11L,
        position = 0,
    )
}
