package com.stash.data.download.files

import android.content.ContentResolver
import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.LibraryLayout
import com.stash.core.data.prefs.StoragePreference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The move cannot run alongside the reorganize pass in EITHER start order,
 * never deletes a document another track already moved onto (two internal
 * files can resolve to one name), and stops when cancelled. The SAF side is
 * an in-memory tree ([FakeSafTree]).
 */
class MoveLibraryCoordinatorTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val storagePreference = mockk<StoragePreference>(relaxed = true)
    private val gate = LibraryRewriteGate()
    private lateinit var musicDir: File

    /** What each track's row records now, as markAsDownloaded leaves it. */
    private val recorded = ConcurrentHashMap<Long, String>()

    private val coordinator by lazy { MoveLibraryCoordinator(context, trackDao, storagePreference, gate) }

    @Before
    fun setUp() {
        val filesDir = tmp.newFolder("files")
        musicDir = File(filesDir, "music")
        every { context.filesDir } returns filesDir
        every { context.contentResolver } returns resolver
        every { storagePreference.libraryLayout } returns flowOf(LibraryLayout.SINGLE_FOLDER)
        coEvery { trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any()) } answers {
            recorded[firstArg()] = secondArg()
            1
        }
        coEvery { trackDao.countOtherTracksWithFilePath(any(), any()) } answers {
            val path = firstArg<String>()
            val trackId = secondArg<Long>()
            recorded.count { (id, p) -> id != trackId && p == path }
        }
        mockkStatic(DocumentFile::class)
    }

    @After
    fun tearDown() {
        unmockkStatic(DocumentFile::class)
    }

    @Test fun `refuses to start while another pass holds the gate`() = runBlocking {
        assertThat(gate.tryAcquire("reorganize")).isTrue()

        // A mock Uri: the gate is checked before the destination is touched.
        coordinator.start(mockk())
        delay(300)

        assertThat(coordinator.state.value).isInstanceOf(MoveLibraryState.Error::class.java)
        coVerify(exactly = 0) { trackDao.healFilePath(any(), any()) }
    }

    /**
     * Single folder names both files `<artist>-intro`. The second move used to
     * delete the first one's document, whose internal copy was already gone.
     */
    @Test fun `a name clash never deletes a track that was already moved`() = runBlocking {
        val tree = FakeSafTree(resolver, storagePreference)
        val studio = internalTrack(1, "Album A", "studio take")
        val live = internalTrack(2, "Live at X", "live take")
        coEvery { trackDao.getDownloadedTracksWithPathPrefix(any()) } returns listOf(studio, live)

        coordinator.start(tree.treeUri)
        val done = withTimeout(10_000) { coordinator.state.first { it is MoveLibraryState.Done } }

        assertThat(done).isEqualTo(MoveLibraryState.Done(moved = 2, failed = 0))
        assertThat(tree.deleted).isEmpty()
        assertThat(recorded[1L]).isEqualTo(tree.uriOf("artist-intro.opus"))
        assertThat(recorded[2L]).isEqualTo(tree.uriOf("artist-intro-live-at-x.opus"))
        assertThat(tree.written[recorded[1L]]).isEqualTo("studio take".toByteArray())
        assertThat(tree.written[recorded[2L]]).isEqualTo("live take".toByteArray())
    }

    /** A leftover nobody records (a move cut short) is still replaced. */
    @Test fun `a leftover document no track records is replaced`() = runBlocking {
        every { storagePreference.libraryLayout } returns flowOf(LibraryLayout.ARTIST_ALBUM)
        val tree = FakeSafTree(resolver, storagePreference)
        tree.seed("artist/album-a/intro.opus", "half".toByteArray())
        coEvery { trackDao.getDownloadedTracksWithPathPrefix(any()) } returns
            listOf(internalTrack(1, "Album A", "studio take"))

        coordinator.start(tree.treeUri)
        withTimeout(10_000) { coordinator.state.first { it is MoveLibraryState.Done } }

        assertThat(recorded[1L]).isEqualTo(tree.uriOf("artist/album-a/intro.opus"))
        assertThat(tree.written[recorded[1L]]).isEqualTo("studio take".toByteArray())
    }

    /** Per-playlist moves a song into the playlist the one batched read names for it. */
    @Test fun `per playlist moves each song under the playlist read for it`() = runBlocking {
        every { storagePreference.libraryLayout } returns flowOf(LibraryLayout.PLAYLIST)
        val tree = FakeSafTree(resolver, storagePreference)
        val filed = internalTrack(1, "Album A", "filed")
        val unfiled = internalTrack(2, "Album B", "unfiled")
        coEvery { trackDao.getDownloadedTracksWithPathPrefix(any()) } returns listOf(filed, unfiled)
        coEvery { trackDao.getFirstPlaylistNamesForTracks(listOf(1L, 2L)) } returns mapOf(1L to "Gothic")

        coordinator.start(tree.treeUri)
        val done = withTimeout(10_000) { coordinator.state.first { it is MoveLibraryState.Done } }

        assertThat(done).isEqualTo(MoveLibraryState.Done(moved = 2, failed = 0))
        assertThat(recorded[1L]).isEqualTo(tree.uriOf("gothic/artist-intro.opus"))
        assertThat(recorded[2L]).isEqualTo(tree.uriOf("artist/album-b/intro.opus"))
        coVerify(exactly = 0) { trackDao.getFirstPlaylistNameForTrack(any()) }
    }

    /**
     * The playlist names could not be read: the move stops before the first
     * file, instead of filing the whole library under artist and album.
     */
    @Test fun `no file moves when the playlist names can't be read`() = runBlocking {
        every { storagePreference.libraryLayout } returns flowOf(LibraryLayout.PLAYLIST)
        val tree = FakeSafTree(resolver, storagePreference)
        val song = internalTrack(1, "Album A", "audio")
        coEvery { trackDao.getDownloadedTracksWithPathPrefix(any()) } returns listOf(song)
        coEvery { trackDao.getFirstPlaylistNamesForTracks(any()) } throws IllegalStateException("db closed")

        coordinator.start(tree.treeUri)
        withTimeout(10_000) { coordinator.state.first { it is MoveLibraryState.Error } }

        assertThat(tree.written).isEmpty()
        assertThat(recorded).isEmpty()
        assertThat(File(song.filePath!!).readText()).isEqualTo("audio")
    }

    /**
     * A legacy library can have two rows on one internal file. Moving one of
     * them used to delete the file unconditionally, leaving the other row on
     * nothing. It stays until the last row that records it has moved.
     */
    @Test fun `an internal file another track still records is kept`() = runBlocking {
        val tree = FakeSafTree(resolver, storagePreference)
        val download = internalTrack(1, "Album A", "the song")
        val sharedFile = File(download.filePath!!)
        // The import took the download's file; its move did not run (failed, or
        // not reached yet).
        recorded[1L] = sharedFile.absolutePath
        recorded[2L] = sharedFile.absolutePath
        coEvery { trackDao.getDownloadedTracksWithPathPrefix(any()) } returns listOf(download)

        coordinator.start(tree.treeUri)
        withTimeout(10_000) { coordinator.state.first { it is MoveLibraryState.Done } }

        assertThat(recorded[1L]).isEqualTo(tree.uriOf("artist-intro.opus"))
        assertThat(sharedFile.exists()).isTrue()
        assertThat(recorded[2L]).isEqualTo(sharedFile.absolutePath)
    }

    /** Both rows move, each to its own document; the last one deletes the file. */
    @Test fun `a shared internal file goes with the last row that records it`() = runBlocking {
        val tree = FakeSafTree(resolver, storagePreference)
        val download = internalTrack(1, "Album A", "the song")
        val import = download.copy(id = 2, album = "Imported")
        val sharedFile = File(download.filePath!!)
        recorded[1L] = sharedFile.absolutePath
        recorded[2L] = sharedFile.absolutePath
        coEvery { trackDao.getDownloadedTracksWithPathPrefix(any()) } returns listOf(download, import)

        coordinator.start(tree.treeUri)
        val done = withTimeout(10_000) { coordinator.state.first { it is MoveLibraryState.Done } }

        assertThat(done).isEqualTo(MoveLibraryState.Done(moved = 2, failed = 0))
        assertThat(recorded[1L]).isNotEqualTo(recorded[2L])
        assertThat(tree.written[recorded[1L]]).isEqualTo("the song".toByteArray())
        assertThat(tree.written[recorded[2L]]).isEqualTo("the song".toByteArray())
        assertThat(sharedFile.exists()).isFalse()
    }

    /**
     * Cancel used to be swallowed as one failed track: the loop copied the
     * rest of the library and then overwrote Idle with Done.
     */
    @Test fun `cancel stops the move`() = runBlocking {
        val tree = FakeSafTree(resolver, storagePreference)
        val tracks = (1L..3L).map { internalTrack(it, "Album $it", "take $it", title = "Song $it") }
        coEvery { trackDao.getDownloadedTracksWithPathPrefix(any()) } returns tracks
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        coEvery { trackDao.markAsDownloaded(1L, any(), any(), any(), any(), any()) } coAnswers {
            writing.countDown()
            release.await(5, TimeUnit.SECONDS)
            delay(500) // the cancel lands here
            1
        }

        coordinator.start(tree.treeUri)
        assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue()
        coordinator.cancel()
        release.countDown()

        assertThat(withTimeoutOrNull(1_500) { coordinator.state.first { it is MoveLibraryState.Done } }).isNull()
        assertThat(coordinator.state.value).isEqualTo(MoveLibraryState.Idle)
        coVerify(exactly = 0) { trackDao.markAsDownloaded(2L, any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { trackDao.markAsDownloaded(3L, any(), any(), any(), any(), any()) }
        assertThat(tree.written.keys).doesNotContain(tree.uriOf("artist-song-2.opus"))
        assertThat(tree.written.keys).doesNotContain(tree.uriOf("artist-song-3.opus"))
    }

    private fun internalTrack(id: Long, album: String, audio: String, title: String = "Intro"): TrackEntity {
        val albumDir = album.lowercase().replace(' ', '-')
        val file = File(musicDir, "artist/$albumDir/${title.lowercase().replace(' ', '-')}.opus")
        file.parentFile!!.mkdirs()
        file.writeText(audio)
        return TrackEntity(
            id = id,
            title = title,
            artist = "Artist",
            album = album,
            durationMs = 200_000L,
            canonicalTitle = title.lowercase(),
            canonicalArtist = "artist",
            filePath = file.absolutePath,
            isDownloaded = true,
        )
    }
}
