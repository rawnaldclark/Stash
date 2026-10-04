package com.stash.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.SharedMixDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Before imports picked a file name of their own, an import named like a
 * downloaded song wrote over that song's file and recorded the same path,
 * so two rows shared one file. Deleting either row, or removing either
 * download, deleted the file and left the other row on nothing. A file now
 * goes only with the last row that records it.
 *
 * [paths] stands in for the tracks table's file_path column, so the count of
 * other rows on a path follows the rows these calls clear and delete.
 */
class MusicRepositorySharedFileTest {

    @get:Rule val tmp = TemporaryFolder()

    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val playlistDao = mockk<PlaylistDao>(relaxed = true)
    private val sharedMixDao = mockk<SharedMixDao>(relaxed = true)
    private val paths = mutableMapOf<Long, String?>()
    private lateinit var file: File

    @Before
    fun setUp() {
        file = tmp.newFile("killing-me-softly-with-his-song.flac").apply { writeText("audio") }
        coEvery { trackDao.countOtherTracksWithFilePath(any(), any()) } answers {
            val path = firstArg<String>()
            val trackId = secondArg<Long>()
            paths.count { (id, p) -> id != trackId && p == path }
        }
        coEvery { trackDao.getById(any()) } answers {
            val id = firstArg<Long>()
            if (id in paths) entity(id, paths[id]) else null
        }
        coEvery { trackDao.clearDownloadState(any()) } answers { paths[firstArg()] = null }
        coEvery { trackDao.delete(any()) } answers { paths.remove(firstArg<TrackEntity>().id) }
        every { trackDao.getByPlaylist(any(), any()) } returns flowOf(emptyList())
        coEvery { sharedMixDao.forPlaylist(any()) } returns null
    }

    // ── deleteTrack ─────────────────────────────────────────────────────

    @Test
    fun `deleting a song keeps a file another song still plays from`() = runTest {
        paths[1L] = file.absolutePath
        paths[2L] = file.absolutePath

        repo().deleteTrack(domain(1L))

        assertThat(file.exists()).isTrue()
        assertThat(paths).containsExactly(2L, file.absolutePath)
    }

    @Test
    fun `deleting the only song on a file deletes the file`() = runTest {
        paths[1L] = file.absolutePath

        repo().deleteTrack(domain(1L))

        assertThat(file.exists()).isFalse()
        assertThat(paths).isEmpty()
    }

    // ── removeDownload ──────────────────────────────────────────────────

    @Test
    fun `removing a download keeps a file another song still plays from`() = runTest {
        paths[1L] = file.absolutePath
        paths[2L] = file.absolutePath

        repo().removeDownload(1L)

        assertThat(file.exists()).isTrue()
        assertThat(paths[1L]).isNull()
        assertThat(paths[2L]).isEqualTo(file.absolutePath)
    }

    @Test
    fun `removing the only download on a file deletes the file`() = runTest {
        paths[1L] = file.absolutePath

        repo().removeDownload(1L)

        assertThat(file.exists()).isFalse()
        assertThat(paths[1L]).isNull()
    }

    // ── removeDownloadsForPlaylist ──────────────────────────────────────

    @Test
    fun `removing a playlist's downloads keeps a file a song outside it plays from`() = runTest {
        paths[1L] = file.absolutePath
        paths[2L] = file.absolutePath
        every { trackDao.getByPlaylist(PLAYLIST, true) } returns flowOf(listOf(entity(1L, file.absolutePath)))

        repo().removeDownloadsForPlaylist(PLAYLIST)

        assertThat(file.exists()).isTrue()
        assertThat(paths[2L]).isEqualTo(file.absolutePath)
    }

    /** Both owners are in the playlist: the file goes with the second. */
    @Test
    fun `removing a playlist's downloads deletes a shared file with its last owner`() = runTest {
        paths[1L] = file.absolutePath
        paths[2L] = file.absolutePath
        every { trackDao.getByPlaylist(PLAYLIST, true) } returns
            flowOf(listOf(entity(1L, file.absolutePath), entity(2L, file.absolutePath)))

        repo().removeDownloadsForPlaylist(PLAYLIST)

        assertThat(file.exists()).isFalse()
        assertThat(paths.values.filterNotNull()).isEmpty()
    }

    // ── removeTrackFromPlaylistAndMaybeDelete ───────────────────────────

    @Test
    fun `a cascade delete keeps a file another song still plays from`() = runTest {
        paths[1L] = file.absolutePath
        paths[2L] = file.absolutePath

        val summary = repo().removeTrackFromPlaylistAndMaybeDelete(1L, PLAYLIST, alsoBlacklist = false)

        assertThat(summary.deleted).isEqualTo(1)
        assertThat(file.exists()).isTrue()
        assertThat(paths).containsExactly(2L, file.absolutePath)
    }

    @Test
    fun `a cascade delete of the only song on a file deletes the file`() = runTest {
        paths[1L] = file.absolutePath

        repo().removeTrackFromPlaylistAndMaybeDelete(1L, PLAYLIST, alsoBlacklist = false)

        assertThat(file.exists()).isFalse()
    }

    // ── cleanOrphanedMixTracks ──────────────────────────────────────────

    @Test
    fun `the orphan sweep keeps a file another song still plays from`() = runTest {
        paths[1L] = file.absolutePath
        paths[2L] = file.absolutePath
        coEvery { trackDao.deleteOrphanedDownloadedTracks() } answers {
            paths.remove(1L)
            listOf(entity(1L, file.absolutePath))
        }

        repo().cleanOrphanedMixTracks()

        assertThat(file.exists()).isTrue()
        assertThat(paths).containsExactly(2L, file.absolutePath)
    }

    @Test
    fun `the orphan sweep deletes a file no song is left on`() = runTest {
        paths[1L] = file.absolutePath
        coEvery { trackDao.deleteOrphanedDownloadedTracks() } answers {
            paths.remove(1L)
            listOf(entity(1L, file.absolutePath))
        }

        repo().cleanOrphanedMixTracks()

        assertThat(file.exists()).isFalse()
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun entity(id: Long, filePath: String?) = TrackEntity(
        id = id, title = "Killing Me Softly With His Song", artist = "Roberta Flack", durationMs = 1000L,
        source = if (id == 1L) MusicSource.LOCAL else MusicSource.SPOTIFY,
        canonicalTitle = "killing me softly with his song", canonicalArtist = "roberta flack",
        filePath = filePath, isDownloaded = filePath != null,
    )

    private fun domain(id: Long) = Track(
        id = id, title = "Killing Me Softly With His Song", artist = "Roberta Flack", filePath = paths[id],
        isDownloaded = true,
    )

    private fun repo() = MusicRepositoryImpl(
        context = mockk(relaxed = true),
        trackDao = trackDao,
        playlistDao = playlistDao,
        syncHistoryDao = mockk(relaxed = true),
        downloadQueueDao = mockk(relaxed = true),
        discoveryQueueDao = mockk(relaxed = true),
        blocklistGuard = mockk(relaxed = true),
        trackMatcher = mockk(relaxed = true),
        stashMixRecipeDao = mockk(relaxed = true),
        downloadNetworkPreference = mockk(relaxed = true),
        streamingPreference = mockk(relaxed = true),
        localFileOps = mockk(relaxed = true),
        syncPreferencesManager = mockk(relaxed = true),
        singleTrackDownloadEnqueuer = mockk(relaxed = true),
        lastFmRecommendationSource = mockk(relaxed = true),
        sharedMixDao = sharedMixDao,
    )

    private companion object {
        const val PLAYLIST = 9L
    }
}
