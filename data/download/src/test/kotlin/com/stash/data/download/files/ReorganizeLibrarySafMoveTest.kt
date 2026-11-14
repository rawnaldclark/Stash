package com.stash.data.download.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackExistenceRef
import com.stash.core.data.prefs.LibraryLayout
import com.stash.core.data.prefs.StoragePreference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The SAF branch of the reorganize pass (copy + delete inside the granted
 * tree) over [FakeSafTree]: the tree-relocation does not leave the source
 * behind, the DB row follows the new document, and a document already at
 * its target is never deleted and recreated.
 *
 * Robolectric because this branch parses real document URIs
 * (`Uri.parse` + `DocumentsContract.getDocumentId`) — under plain JVM
 * stubs those return null, same as [SafLibrarySizeWalkTest]'s reasoning.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ReorganizeLibrarySafMoveTest {

    private val context = mockk<Context>(relaxed = true)
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val storagePreference = mockk<StoragePreference>()
    private val fileOrganizer = mockk<FileOrganizer>()
    private val gate = LibraryRewriteGate()

    private lateinit var tree: FakeSafTree
    /** Documents the tree handed out, by their URI — for fromSingleUri to find. */
    private val docsByUri = mutableMapOf<String, DocumentFile>()
    /** Seeded file content by URI — for the copy's openInputStream. */
    private val seedBytes = mutableMapOf<String, ByteArray>()
    private lateinit var coordinator: ReorganizeLibraryCoordinator

    @Before
    fun setUp() {
        every { context.contentResolver } returns resolver
        mockkStatic(DocumentFile::class)
        tree = FakeSafTree(resolver, storagePreference)
        every { DocumentFile.fromSingleUri(any(), any()) } answers {
            docsByUri[secondArg<Uri>().toString()]
        }
        // The pass copies source→target through the resolver; the tree only
        // stubs the output side.
        every { resolver.openInputStream(any()) } answers {
            seedBytes[firstArg<Uri>().toString()]?.let(::ByteArrayInputStream)
        }
        every { storagePreference.externalTreeUri } returns flowOf(tree.treeUri)
        every { fileOrganizer.internalMusicRoot() } returns File("unused")
        coEvery { fileOrganizer.currentLayout() } returns LibraryLayout.SINGLE_FOLDER
        coordinator = ReorganizeLibraryCoordinator(context, trackDao, storagePreference, fileOrganizer, gate)
    }

    @After
    fun tearDown() {
        unmockkStatic(DocumentFile::class)
    }

    /** Seeds [path] into the tree and registers it for the pass's lookups. */
    private fun seed(path: String, bytes: ByteArray) {
        docsByUri[tree.uriOf(path)] = tree.seed(path, bytes)
        seedBytes[tree.uriOf(path)] = bytes
    }

    private fun safRef(id: Long, path: String, artist: String, album: String, title: String) =
        TrackExistenceRef(id, artist, album, title, tree.uriOf(path))

    private suspend fun awaitDone(): ReorganizeLibraryState.Done =
        withTimeout(10_000) {
            coordinator.state.first { it is ReorganizeLibraryState.Done }
        } as ReorganizeLibraryState.Done

    /**
     * A relocation whose target name is FREE (no deletion needed): create →
     * copy → heal → delete source. The document must land under the tree
     * root as the layout prescribes, and the old one must be gone.
     */
    @Test
    fun `moves a document into the flat layout and removes the source`() = runBlocking {
        seed("artist/album-a/song.m4a", "AUDIO".toByteArray())
        coEvery { trackDao.getDownloadedTrackRefs() } returns listOf(
            safRef(1L, "artist/album-a/song.m4a", "Artist", "Album A", "Song"),
        )

        coordinator.start()
        val done = awaitDone()

        val targetUri = tree.uriOf("artist-song.m4a")
        assertThat(done.moved).isEqualTo(1)
        assertThat(tree.written[targetUri]).isEqualTo("AUDIO".toByteArray())
        assertThat(tree.exists("artist/album-a/song.m4a")).isFalse()
        coVerify(exactly = 1) { trackDao.healFilePath(1L, targetUri) }
    }

    /**
     * A stale leftover at the target name (a previous pass cut short) is
     * replaced, and the row is healed to the new document's URI.
     */
    @Test
    fun `replaces a leftover at the target name`() = runBlocking {
        seed("artist/album-a/song.m4a", "NEW AUDIO".toByteArray())
        seed("artist-song.m4a", "half a pass".toByteArray())
        coEvery { trackDao.getDownloadedTrackRefs() } returns listOf(
            safRef(1L, "artist/album-a/song.m4a", "Artist", "Album A", "Song"),
        )

        coordinator.start()
        val done = awaitDone()

        val targetUri = tree.uriOf("artist-song.m4a")
        assertThat(done.moved).isEqualTo(1)
        assertThat(done.failed).isEqualTo(0)
        assertThat(tree.written[targetUri]).isEqualTo("NEW AUDIO".toByteArray())
        assertThat(tree.exists("artist/album-a/song.m4a")).isFalse()
        coVerify(exactly = 1) { trackDao.healFilePath(1L, targetUri) }
    }

    /**
     * A row whose document is already exactly in place is a no-op WITHOUT
     * being counted as a move — a delete-and-recreate of the only copy is
     * the failure this guards against, so bytes and DB row stay untouched.
     */
    @Test
    fun `a document already at its target stays untouched`() = runBlocking {
        seed("artist-song.m4a", "IN PLACE".toByteArray())
        coEvery { trackDao.getDownloadedTrackRefs() } returns listOf(
            safRef(1L, "artist-song.m4a", "Artist", "Album A", "Song"),
        )

        coordinator.start()
        awaitDone()

        // Still the original bytes — never deleted and recreated... but the
        // in-place branch runs during planning (safAlreadyInPlace), so the
        // pass itself reports it as skipped rather than moved.
        assertThat(tree.written[tree.uriOf("artist-song.m4a")]).isEqualTo("IN PLACE".toByteArray())
        coVerify(exactly = 0) { trackDao.healFilePath(any(), any()) }
    }

    /**
     * Two rows that resolve to one target name: the plan's first-claimant
     * guard skips the second rather than deleting the first's audio — over
     * the SAF branch, the branch the plan guard was written for.
     */
    @Test
    fun `a clash loser's row is skipped without deleting the winner`() = runBlocking {
        seed("artist/album-a/song.m4a", "STUDIO".toByteArray())
        seed("artist/unplugged/song.m4a", "LIVE".toByteArray())
        coEvery { trackDao.getDownloadedTrackRefs() } returns listOf(
            safRef(1L, "artist/album-a/song.m4a", "Artist", "Album A", "Song"),
            safRef(2L, "artist/unplugged/song.m4a", "Artist", "Unplugged", "Song"),
        )

        coordinator.start()
        val done = awaitDone()

        assertThat(done.moved).isEqualTo(1)
        assertThat(done.skipped).isEqualTo(1)
        assertThat(tree.written[tree.uriOf("artist-song.m4a")])
            .isEqualTo("STUDIO".toByteArray())
        assertThat(tree.exists("artist/unplugged/song.m4a")).isTrue()
        coVerify(exactly = 0) { trackDao.healFilePath(2L, any()) }
    }
}
