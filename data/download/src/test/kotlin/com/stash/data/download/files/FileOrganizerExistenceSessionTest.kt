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
}
