package com.stash.data.download.files

import android.content.Context
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.LibraryLayout
import com.stash.core.data.prefs.StoragePreference
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Every library file name ends in `.<format>`, and callers pass the format.
 * Only 1 to 5 lower-case ASCII letters or digits are accepted; any other
 * format is refused before anything is written.
 */
class FileOrganizerFormatTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)
    private val storagePreference = mockk<StoragePreference>()
    private val trackDao = mockk<TrackDao>(relaxed = true)
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = tmp.newFolder("files")
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns tmp.newFolder("cache")
        every { storagePreference.externalTreeUri } returns flowOf(null)
        every { storagePreference.libraryLayout } returns flowOf(LibraryLayout.ARTIST_ALBUM)
    }

    private fun organizer() = FileOrganizer(context, storagePreference, trackDao)

    private val unsafeFormats = listOf(
        "mp3/../../a1",
        "../a1",
        "/a1",
        "mp3\\..\\..\\a1",
        "a.b",
        "",
        "mp3 ",
        "MP3",
        "ｍｐ３",
        "toolong",
    )

    private inline fun assertRefused(format: String, block: () -> Unit) {
        try {
            block()
            fail("format '$format' must be refused")
        } catch (expected: IllegalArgumentException) {
            // refused before anything was written
        }
    }

    @Test
    fun `commitDownload refuses a format that isn't a short lower-case extension`() = runTest {
        val organizer = organizer()
        for (format in unsafeFormats) {
            val temp = tmp.newFile("temp_${unsafeFormats.indexOf(format)}.bin").apply { writeText("audio") }
            assertRefused(format) {
                organizer.commitDownload(temp, "Evanescence", "Synthesis", "Lacrymosa", format, 7L)
            }
            assertTrue("the temp file is left for the caller to clean up", temp.exists())
        }
        val written = filesDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue("nothing may be written for a refused format, found $written", written.isEmpty())
        assertTrue(tmp.root.walkTopDown().none { it.name == "a1" })
    }

    @Test
    fun `plannedPaths refuses the same formats`() = runTest {
        val organizer = organizer()
        for (format in unsafeFormats) {
            assertRefused(format) {
                organizer.plannedPaths("Evanescence", "Synthesis", "Lacrymosa", format, 7L, listOf(null))
            }
        }
    }

    @Test
    fun `the formats downloads and imports use still work`() = runTest {
        val organizer = organizer()
        for (format in listOf("opus", "m4a", "mp3", "flac", "webm", "aac", "ogg", "oga", "wav", "mp4", "mka", "amr", "awb")) {
            val temp = tmp.newFile("ok.$format").apply { writeText("audio") }
            val committed = organizer.commitDownload(temp, "Evanescence", "Synthesis", "Lacrymosa", format, 7L)
            assertEquals(
                File(filesDir, "music/evanescence/synthesis/lacrymosa.$format").absolutePath,
                committed.filePath,
            )
        }
    }
}
