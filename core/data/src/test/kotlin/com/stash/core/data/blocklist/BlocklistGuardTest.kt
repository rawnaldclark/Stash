package com.stash.core.data.blocklist

import androidx.room.withTransaction
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackBlocklistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackBlocklistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.MusicSource
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BlocklistGuardTest {

    private val matcher = TrackMatcher()
    private val database: StashDatabase = mockk(relaxed = true)
    private val blocklistDao: TrackBlocklistDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val playlistDao: PlaylistDao = mockk(relaxed = true)
    private val downloadQueueDao: DownloadQueueDao = mockk(relaxed = true)
    private val fileDeleter: FileDeleter = mockk(relaxed = true)

    private val guard = BlocklistGuard(
        database = database,
        blocklistDao = blocklistDao,
        trackDao = trackDao,
        playlistDao = playlistDao,
        downloadQueueDao = downloadQueueDao,
        fileDeleter = fileDeleter,
        matcher = matcher,
    )

    @Before
    fun stubWithTransaction() {
        // `withTransaction` is a top-level suspend extension on RoomDatabase.
        // mockkStatic the file and invoke the supplied lambda. Because it's
        // an extension function, the database receiver is firstArg and the
        // block is secondArg in the captured invocation.
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
    }

    @After
    fun teardown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `isBlocked returns true when blocklist has matching identity`() = runTest {
        coEvery { blocklistDao.isBlocked("arctic monkeys|505", null, null) } returns true

        val result = guard.isBlocked(artist = "Arctic Monkeys", title = "505",
            spotifyUri = null, youtubeId = null)

        assertTrue(result)
    }

    @Test
    fun `isBlocked returns false when blocklist has no matching identity`() = runTest {
        coEvery { blocklistDao.isBlocked(any(), any(), any()) } returns false

        val result = guard.isBlocked(artist = "Arctic Monkeys", title = "Brianstorm",
            spotifyUri = null, youtubeId = null)

        assertFalse(result)
    }

    @Test
    fun `block deletes files + cross refs + queue rows + track row + inserts blocklist entry`() = runTest {
        val track = TrackEntity(
            id = 42L,
            title = "505",
            artist = "Arctic Monkeys",
            album = "Favourite Worst Nightmare",
            source = MusicSource.SPOTIFY,
            spotifyUri = "spotify:track:abc",
            filePath = "/sdcard/505.flac",
            albumArtPath = "/sdcard/505.jpg",
            isDownloaded = true,
            canonicalArtist = "arctic monkeys",
            canonicalTitle = "505",
        )
        val captured = slot<TrackBlocklistEntity>()
        coEvery { blocklistDao.insert(capture(captured)) } just Runs

        guard.block(track, BlockSource.NOW_PLAYING)

        assertEquals("arctic monkeys|505", captured.captured.canonicalKey)
        assertEquals("Arctic Monkeys", captured.captured.artist)
        assertEquals("505", captured.captured.title)
        assertEquals("spotify:track:abc", captured.captured.spotifyUri)
        assertEquals("NOW_PLAYING", captured.captured.blockedFrom)
        coVerify(exactly = 1) { fileDeleter.delete("/sdcard/505.flac") }
        coVerify(exactly = 1) { fileDeleter.delete("/sdcard/505.jpg") }
        coVerify(exactly = 1) { playlistDao.deleteAllCrossRefsForTrack(42L) }
        coVerify(exactly = 1) { downloadQueueDao.deleteByTrackId(42L) }
        coVerify(exactly = 1) { trackDao.deleteById(42L) }
    }

    @Test
    fun `block recounts every playlist that held the track`() = runTest {
        val track = TrackEntity(id = 42L, title = "505", artist = "Arctic Monkeys", source = MusicSource.SPOTIFY)
        coEvery { playlistDao.playlistIdsForTrack(42L) } returns listOf(7L, 9L)
        every { trackDao.getByPlaylist(7L, true) } returns flowOf(List(3) { TrackEntity(title = "t$it", artist = "a") })
        every { trackDao.getByPlaylist(9L, true) } returns flowOf(emptyList())

        guard.block(track, BlockSource.CONTEXT_MENU)

        coVerify(exactly = 1) { playlistDao.updateTrackCount(7L, 3) }
        coVerify(exactly = 1) { playlistDao.updateTrackCount(9L, 0) }
    }

    /**
     * Two rows can share a file (an import that took a downloaded song's file
     * before imports picked a name of their own). Blocking one row must not
     * delete the audio the other still plays from.
     */
    @Test
    fun `block keeps a file another song still plays from`() = runTest {
        val track = TrackEntity(
            id = 42L, title = "505", artist = "Arctic Monkeys", source = MusicSource.SPOTIFY,
            filePath = "/music/505.flac", isDownloaded = true,
        )
        coEvery { trackDao.countOtherTracksWithFilePath("/music/505.flac", 42L) } returns 1

        guard.block(track, BlockSource.CONTEXT_MENU)

        coVerify(exactly = 0) { fileDeleter.delete("/music/505.flac") }
        coVerify(exactly = 1) { trackDao.deleteById(42L) }
    }

    @Test
    fun `block deletes a file only it records`() = runTest {
        val track = TrackEntity(
            id = 42L, title = "505", artist = "Arctic Monkeys", source = MusicSource.SPOTIFY,
            filePath = "/music/505.flac", isDownloaded = true,
        )
        coEvery { trackDao.countOtherTracksWithFilePath("/music/505.flac", 42L) } returns 0

        guard.block(track, BlockSource.CONTEXT_MENU)

        coVerify(exactly = 1) { fileDeleter.delete("/music/505.flac") }
    }

    @Test
    fun `unblock deletes the blocklist row by canonical key`() = runTest {
        guard.unblock("arctic monkeys|505")
        coVerify(exactly = 1) { blocklistDao.deleteByKey("arctic monkeys|505") }
    }
}
