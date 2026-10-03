package com.stash.data.download.files

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StoragePreference
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import io.mockk.coVerify
import org.junit.Test
import java.io.File

class FileOrganizerExistenceSessionTest {

    private val context: Context = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)

    private fun organizer(treeUri: android.net.Uri? = null): FileOrganizer {
        val prefs: StoragePreference = mockk {
            every { externalTreeUri } returns flowOf(treeUri)
        }
        return FileOrganizer(context, prefs, trackDao)
    }

    @Test
    fun `internal path is checked directly on disk`() = runTest {
        val real = File.createTempFile("existing", ".opus")
        try {
            val session = organizer().existenceSession()
            assertThat(session.exists(1L, "a", "b", "t", real.absolutePath).exists).isTrue()
            assertThat(session.exists(1L, "a", "b", "t", real.absolutePath + ".gone").exists).isFalse()
        } finally {
            real.delete()
        }
    }

    /**
     * A missing/revoked SAF tree grant must FAIL SAFE: reporting content://
     * files as missing here would reset the entire library to undownloaded
     * and trigger a redownload storm on the next sync (#429's failure shape).
     */
    @Test
    fun `content path with no tree grant is reported as existing`() = runTest {
        val session = organizer(treeUri = null).existenceSession()
        val result = session.exists(1L, "Artist", "Album", "Title", "content://com.android.externalstorage/tree/x")
        assertThat(result.exists).isTrue()
        assertThat(result.resolvedFilePath).isNull()
    }

    @Test
    fun `saf index is built at most once per session`() = runTest {
        val spied = spyk(organizer(treeUri = null))
        val session = spied.existenceSession()
        repeat(5) {
            session.exists(1L, "Artist", "Album", "Title $it", "content://tree/doc$it")
        }
        coVerify(exactly = 1) { spied.buildSafIndex() }
    }

    /**
     * The tree-wide filename fallback must not match a BARE `<title>.<ext>`.
     * That name is only an identity inside its own `<artist>/<album>`
     * directory; loose in the tree it belongs to whichever artist got there
     * first, and matching it would heal this track's file_path onto a
     * different song's audio.
     */
    @Test
    fun `tree-wide fallback ignores a bare title owned by another artist`() {
        val otherArtistsFile: androidx.documentfile.provider.DocumentFile = mockk(relaxed = true)
        val index = FileOrganizer.SafIndex(
            byDirKey = emptyMap(),
            // "Intro" by SOMEONE ELSE, sitting somewhere in the tree.
            byFileName = mapOf("intro.opus" to listOf(otherArtistsFile)),
        )

        val hit = organizer().resolveInIndex(
            index = index,
            layout = com.stash.core.data.prefs.LibraryLayout.ARTIST_ALBUM,
            artist = "Our Artist",
            album = "Our Album",
            title = "Intro",
            knownFormat = "opus",
        )

        assertThat(hit).isNull()
    }

    /** The artist-qualified flat name stays matchable tree-wide. */
    @Test
    fun `tree-wide fallback still matches an artist qualified name`() {
        val ourFile: androidx.documentfile.provider.DocumentFile = mockk(relaxed = true)
        val index = FileOrganizer.SafIndex(
            byDirKey = emptyMap(),
            byFileName = mapOf("our-artist-intro.opus" to listOf(ourFile)),
        )

        val hit = organizer().resolveInIndex(
            index = index,
            layout = com.stash.core.data.prefs.LibraryLayout.ARTIST_ALBUM,
            artist = "Our Artist",
            album = "Our Album",
            title = "Intro",
            knownFormat = "opus",
        )

        assertThat(hit).isSameInstanceAs(ourFile)
    }

    /**
     * #531: a swap can give a track a distinct file name when another track
     * already uses the canonical one. The canonical-name lookup would then
     * heal this row onto the OTHER track's file, so a row whose own document
     * is still in the tree is kept as it is.
     */
    @Test
    fun `a SAF row whose own document is still in the tree is not healed onto a same-named file`() = runTest {
        val stored = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/" +
            "primary%3AMusic%2Fevanescence%2Fsynthesis%2Flacrymosa-7.opus"
        val siblingsFile: androidx.documentfile.provider.DocumentFile = mockk(relaxed = true)
        val spied = spyk(organizer(treeUri = mockk(relaxed = true)))
        io.mockk.coEvery { spied.buildSafIndex() } returns FileOrganizer.SafIndex(
            byDirKey = mapOf(
                "evanescence/synthesis" to FileOrganizer.AlbumIndex(
                    mockk(relaxed = true),
                    mapOf("lacrymosa.opus" to siblingsFile),
                ),
            ),
            byFileName = mapOf("lacrymosa.opus" to listOf(siblingsFile)),
            documentUris = setOf(stored),
        )

        val result = spied.existenceSession().exists(7L, "Evanescence", "Synthesis", "Lacrymosa", stored)

        assertThat(result.exists).isTrue()
        assertThat(result.resolvedFilePath).isNull()
    }
}
