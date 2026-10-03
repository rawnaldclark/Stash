package com.stash.data.download.files

import android.content.Context
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.LibraryLayout
import com.stash.core.data.prefs.StoragePreference
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The wrong-match swap (#531) asks [FileOrganizer.plannedPath] where a file
 * would go before writing it, and passes a name suffix when another track
 * already uses that spot. Internal storage only: the SAF branch needs a
 * ContentResolver.
 */
class FileOrganizerPlannedPathTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)
    private val storagePreference = mockk<StoragePreference>()
    private val trackDao = mockk<TrackDao>(relaxed = true)
    private lateinit var musicDir: File

    @Before
    fun setUp() {
        val filesDir = tmp.newFolder("files")
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns tmp.newFolder("cache")
        every { storagePreference.externalTreeUri } returns flowOf(null)
        coEvery { trackDao.getFirstPlaylistNameForTrack(any()) } returns "Gothic"
        musicDir = File(filesDir, "music")
    }

    private fun organizer(layout: LibraryLayout): FileOrganizer {
        every { storagePreference.libraryLayout } returns flowOf(layout)
        return FileOrganizer(context, storagePreference, trackDao)
    }

    private fun temp(name: String) = tmp.newFile(name).apply { writeText("audio") }

    @Test
    fun `plannedPath is where commitDownload writes`() = runTest {
        for (layout in LibraryLayout.entries) {
            val organizer = organizer(layout)
            for (suffix in listOf(null, "Synthesis")) {
                val planned = organizer.plannedPaths("Evanescence", "Synthesis", "Lacrymosa", "opus", 7L, listOf(suffix)).single()
                val committed = organizer.commitDownload(
                    temp("swap_${layout}_$suffix.opus"), "Evanescence", "Synthesis", "Lacrymosa", "opus", 7L, suffix,
                )
                assertEquals("$layout, suffix $suffix", planned, committed.filePath)
            }
        }
    }

    @Test
    fun `a name suffix gives a distinct file in every layout`() = runTest {
        val expectedCanonical = mapOf(
            LibraryLayout.SINGLE_FOLDER to File(musicDir, "evanescence-lacrymosa.opus"),
            LibraryLayout.PLAYLIST to File(musicDir, "gothic/evanescence-lacrymosa.opus"),
            LibraryLayout.ARTIST_ALBUM to File(musicDir, "evanescence/singles/lacrymosa.opus"),
        )
        for ((layout, canonical) in expectedCanonical) {
            val organizer = organizer(layout)
            // Blank album: the Artist/Album layout files it under singles/.
            // One call answers every candidate name (on SAF: one folder listing).
            val (plain, suffixed) = organizer.plannedPaths("Evanescence", null, "Lacrymosa", "opus", 7L, listOf(null, "7"))
            assertEquals("$layout canonical", canonical.absolutePath, plain)
            assertNotEquals("$layout suffixed", plain, suffixed)
            assertEquals("$layout suffixed stays in the same folder", File(plain!!).parent, File(suffixed!!).parent)
        }
    }
}
