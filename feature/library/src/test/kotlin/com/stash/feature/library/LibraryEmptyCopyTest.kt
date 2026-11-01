package com.stash.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * An empty Library tab names the search or filter that emptied it; it used to say "Sync your
 * library to see tracks here" under a forgotten FLAC filter or a search that matched nothing.
 */
class LibraryEmptyCopyTest {

    @Test fun `no search and no filter keeps the tab's own empty line`() {
        assertNull(noMatchMessage("songs", query = "  ", filter = SourceFilter.ALL))
    }

    @Test fun `a filter alone is named`() {
        assertEquals("No FLAC songs in your library", noMatchMessage("songs", "", SourceFilter.FLAC))
    }

    @Test fun `a search alone is quoted`() {
        assertEquals("No artists match “beatles”", noMatchMessage("artists", " beatles "))
    }

    @Test fun `search and filter together`() {
        assertEquals(
            "No Non-FLAC songs match “beatles”",
            noMatchMessage("songs", "beatles", SourceFilter.NON_FLAC),
        )
    }

    @Test fun `only a real filter has a label`() {
        assertNull(sourceFilterLabel(SourceFilter.ALL))
        assertEquals("YouTube", sourceFilterLabel(SourceFilter.YOUTUBE))
    }

    // ── The not-downloaded filter (was "Online" until the mode took that name) ──

    @Test fun `the not-downloaded filter is never called Online, which is the mode's name`() {
        assertEquals("Not downloaded", sourceFilterLabel(SourceFilter.ONLINE))
        for (filter in SourceFilter.entries) {
            assertNotEquals("Online", sourceFilterLabel(filter))
            assertNotEquals("Offline", sourceFilterLabel(filter))
        }
    }

    @Test fun `its chip and empty lines read as plain phrases`() {
        assertEquals("Showing songs that aren't downloaded", activeFilterChipLabel(SourceFilter.ONLINE))
        assertEquals("No songs in your library that aren't downloaded", noMatchMessage("songs", "", SourceFilter.ONLINE))
        assertEquals(
            "No songs that aren't downloaded match “beatles”",
            noMatchMessage("songs", " beatles ", SourceFilter.ONLINE),
        )
    }

    @Test fun `every other filter's chip names it`() {
        assertNull(activeFilterChipLabel(SourceFilter.ALL))
        assertEquals("Showing FLAC only", activeFilterChipLabel(SourceFilter.FLAC))
        assertEquals("Showing Downloaded only", activeFilterChipLabel(SourceFilter.DOWNLOADED))
    }

    // ── A library with nothing downloaded ─────────────────────────────

    @Test fun `with a service connected the empty library leads with Sync`() {
        val prompt = emptyLibraryPrompt(anyServiceConnected = true)
        assertEquals(false, prompt.connectFirst)
        assertEquals("Sync your playlists", prompt.primaryLabel)
    }

    @Test fun `with nothing connected it leads with connecting a service`() {
        val prompt = emptyLibraryPrompt(anyServiceConnected = false)
        assertEquals(true, prompt.connectFirst)
        assertEquals("Connect a music service", prompt.primaryLabel)
    }

    @Test fun `either way it names Search and importing from the phone`() {
        for (connected in listOf(true, false)) {
            val body = emptyLibraryPrompt(connected).body
            assertEquals(true, "Search" in body)
            assertEquals(true, "import audio files from this phone" in body)
        }
    }
}
