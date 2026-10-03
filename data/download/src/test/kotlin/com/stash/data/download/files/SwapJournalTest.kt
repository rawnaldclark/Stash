package com.stash.data.download.files

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The swap writes down where it moved the old file before saving over its
 * spot, so a swap cut short by the app being killed can be repaired at the
 * next start (#531 review).
 */
class SwapJournalTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context = mockk<Context>()

    @Before
    fun setUp() {
        every { context.noBackupFilesDir } returns tmp.newFolder("no-backup")
    }

    @Test
    fun `an entry survives until it is cleared, even across restarts`() {
        val entry = SwapJournal.Entry(
            trackId = 7L,
            originalPath = "/music/evanescence/synthesis/lacrymosa.opus",
            backupPath = "/music/evanescence/synthesis/lacrymosa.opus.swapbak",
        )
        SwapJournal(context).record(entry)

        // A new instance, as after the process is killed and the app restarts.
        assertEquals(listOf(entry), SwapJournal(context).pending())

        SwapJournal(context).clear(7L)
        assertTrue(SwapJournal(context).pending().isEmpty())
    }

    @Test
    fun `entries for different tracks are kept apart`() {
        val journal = SwapJournal(context)
        journal.record(SwapJournal.Entry(7L, "/a.opus", "/a.opus.swapbak"))
        journal.record(SwapJournal.Entry(8L, "content://tree/document/b.opus", "content://tree/document/b.opus.swapbak"))

        journal.clear(7L)

        assertEquals(
            listOf(SwapJournal.Entry(8L, "content://tree/document/b.opus", "content://tree/document/b.opus.swapbak")),
            journal.pending(),
        )
    }
}
