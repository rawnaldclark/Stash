package com.stash.core.data.home

import com.stash.data.ytmusic.model.MoodPage
import com.stash.data.ytmusic.model.MoodPlaylist
import com.stash.data.ytmusic.model.MoodShelf
import com.stash.data.ytmusic.model.MoodTile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** [MoodsRepository]: the tiles read once a day and kept through a failed read; a mood's page kept a while. */
class MoodsRepositoryTest {

    @get:Rule val folder = TemporaryFolder()

    private var day = 20_000L
    private var clock = 1_793_000_000_000L
    private var tilesAnswer: List<MoodTile>? = listOf(MoodTile("Chill", "p1", 1L), MoodTile("Focus", "p2", 2L))
    private var tileReads = 0
    private var pageAnswer: MoodPage? = MoodPage("Chill", listOf(MoodShelf("Coffee", listOf(MoodPlaylist("VL1", "Blend", "YouTube Music", null)))))
    private var pageReads = 0

    private fun repo() = MoodsRepository(
        store = HomeShelfStore(folder.root),
        today = { day },
        now = { clock },
        fetchTiles = { tileReads++; tilesAnswer },
        fetchPage = { _, _ -> pageReads++; pageAnswer },
    )

    @Test
    fun `tiles are read once a day and saved`() = runTest {
        val first = repo().tiles()
        assertEquals(listOf("Chill", "Focus"), first.map { it.title })
        assertEquals(first, repo().tiles())
        assertEquals(first, repo().cached())
        assertEquals(1, tileReads)
        day++
        repo().tiles()
        assertEquals(2, tileReads)
    }

    @Test
    fun `no answer keeps the saved tiles, and the next read waits an hour`() = runTest {
        val first = repo().tiles()
        day++
        tilesAnswer = null
        assertEquals(first, repo().tiles())
        // Home's resume a minute later: no request
        clock += 60_000
        assertEquals(first, repo().tiles())
        assertEquals(2, tileReads)
        // An hour on it asks again, and an answer is the day's tiles
        clock += MoodsRepository.RETRY_MS
        tilesAnswer = listOf(MoodTile("Sleep", "p3"))
        assertEquals(listOf("Sleep"), repo().tiles().map { it.title })
        assertEquals(3, tileReads)
        assertEquals(listOf("Sleep"), repo().tiles().map { it.title })
        assertEquals(3, tileReads)
    }

    @Test
    fun `an answer without the grid keeps yesterday's tiles, and isn't asked again today`() = runTest {
        val first = repo().tiles()
        day++
        // Signed in, or a reworded page: YouTube answered, but no grid was Moods & moments
        tilesAnswer = emptyList()
        assertEquals(first, repo().tiles())
        assertEquals(first, repo().tiles())
        assertEquals(first, repo().cached())
        assertEquals(2, tileReads)
        // Tomorrow it asks again, and an answer with the grid replaces them
        day++
        tilesAnswer = listOf(MoodTile("Sleep", "p3"))
        assertEquals(listOf("Sleep"), repo().tiles().map { it.title })
        assertEquals(3, tileReads)
    }

    @Test
    fun `with nothing saved, an answer without the grid hides the shelf until tomorrow`() = runTest {
        tilesAnswer = emptyList()
        assertTrue(repo().tiles().isEmpty())
        assertTrue(repo().tiles().isEmpty())
        assertEquals(1, tileReads)
    }

    @Test
    fun `nothing saved and no answer is no shelf`() = runTest {
        tilesAnswer = null
        assertTrue(repo().tiles().isEmpty())
        assertTrue(repo().cached().isEmpty())
    }

    @Test
    fun `a mood's page is kept for half an hour, then read again`() = runTest {
        val repo = repo()
        assertEquals("Chill", repo.page("p1", "Chill").title)
        repo.page("p1", "Chill")
        assertEquals(1, pageReads)
        clock += MoodsRepository.PAGE_TTL_MS
        repo.page("p1", "Chill")
        assertEquals(2, pageReads)
    }

    @Test
    fun `a page that didn't answer throws, so the screen can offer a retry`() = runTest {
        pageAnswer = null
        try {
            repo().page("p1", "Chill")
            fail("expected IOException")
        } catch (expected: IOException) {
            // the screen shows Try again
        }
    }

    @Test
    fun `an empty page isn't kept, so the next open asks again`() = runTest {
        pageAnswer = MoodPage("Chill", emptyList())
        val repo = repo()
        assertTrue(repo.page("p1", "Chill").shelves.isEmpty())
        repo.page("p1", "Chill")
        assertEquals(2, pageReads)
    }
}
