package com.stash.core.data.home

import com.stash.data.ytmusic.model.MoodGrids
import com.stash.data.ytmusic.model.MoodPage
import com.stash.data.ytmusic.model.MoodPlaylist
import com.stash.data.ytmusic.model.MoodShelf
import com.stash.data.ytmusic.model.MoodTile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * [MoodsRepository]: both grids read once a day and kept through a failed read; a tile's page kept a while; a tile's
 * covers saved for a week, read two at a time, and never while Data saver holds a metered network.
 */
class MoodsRepositoryTest {

    @get:Rule val folder = TemporaryFolder()

    private var day = 20_000L
    private var clock = 1_793_000_000_000L
    private val chill = MoodTile("Chill", "p1", 1L)
    private val focus = MoodTile("Focus", "p2", 2L)
    private val rock = MoodTile("Rock", "g1", 3L)
    private var gridsAnswer: MoodGrids? = MoodGrids(listOf(chill, focus), listOf(rock))
    private var gridReads = 0
    private var pageAnswer: MoodPage? = page("A", "B")
    private val pageReads = mutableListOf<String>()
    private var dataSaver = false

    private fun page(vararg covers: String) = MoodPage(
        "Chill",
        listOf(MoodShelf("Coffee", covers.mapIndexed { i, c -> MoodPlaylist("VL$i", "Blend $i", "YouTube Music", art(c)) })),
    )

    /** A YouTube picture as the page sends it, sized for Now Playing. */
    private fun art(name: String) = "https://lh3.googleusercontent.com/$name=w1024-h1024"

    /** The same picture as a tile saves it. */
    private fun cover(name: String) = "https://lh3.googleusercontent.com/$name=w128-h128"

    private fun repo(
        store: HomeShelfStore = HomeShelfStore(folder.root),
        io: CoroutineDispatcher = Dispatchers.IO,
        fetchPage: suspend (String, String) -> MoodPage? = { params, _ -> pageReads += params; pageAnswer },
    ) = MoodsRepository(
        store = store,
        today = { day },
        now = { clock },
        fetchGrids = { gridReads++; gridsAnswer },
        fetchPage = fetchPage,
        dataSaverApplies = { dataSaver },
        io = io,
    )

    @Test
    fun `both grids are read once a day and saved`() = runTest {
        val first = repo().grids()
        assertEquals(listOf("Chill", "Focus"), first.moods.map { it.title })
        assertEquals(listOf("Rock"), first.genres.map { it.title })
        assertEquals(first, repo().grids())
        assertEquals(first, repo().cached())
        assertEquals(1, gridReads)
        day++
        repo().grids()
        assertEquals(2, gridReads)
    }

    @Test
    fun `a file saved today before genres were kept is read again once, so Genres show today`() = runTest {
        val store = HomeShelfStore(folder.root)
        // What the previous build wrote this morning: moods only
        store.write(MoodsRepository.FILE, MoodsFile.serializer(), MoodsFile(day, listOf(chill)))
        assertTrue(repo(store).cached().genres.isEmpty())
        assertEquals(listOf("Rock"), repo(store).grids().genres.map { it.title })
        assertEquals(1, gridReads)
        // Read with genres now: not again today, even when YouTube had no Genres grid to give
        repo(store).grids()
        assertEquals(1, gridReads)
        gridsAnswer = MoodGrids(listOf(chill), emptyList())
        store.write(MoodsRepository.FILE, MoodsFile.serializer(), MoodsFile(day, listOf(chill)))
        repo(store).grids()
        repo(store).grids()
        assertEquals(2, gridReads)
    }

    @Test
    fun `no answer keeps the saved tiles, and the next read waits an hour`() = runTest {
        val first = repo().grids()
        day++
        gridsAnswer = null
        assertEquals(first, repo().grids())
        // Search's resume a minute later: no request
        clock += 60_000
        assertEquals(first, repo().grids())
        assertEquals(2, gridReads)
        // An hour on it asks again, and an answer is the day's tiles
        clock += MoodsRepository.RETRY_MS
        gridsAnswer = MoodGrids(listOf(MoodTile("Sleep", "p3")), listOf(rock))
        assertEquals(listOf("Sleep"), repo().grids().moods.map { it.title })
        assertEquals(3, gridReads)
        assertEquals(listOf("Sleep"), repo().grids().moods.map { it.title })
        assertEquals(3, gridReads)
    }

    @Test
    fun `an answer without a grid keeps yesterday's tiles of that grid, and isn't asked again today`() = runTest {
        val first = repo().grids()
        day++
        // A reworded page: YouTube answered, but no grid was Moods & moments; Genres came as usual
        val pop = MoodTile("Pop", "g2")
        gridsAnswer = MoodGrids(emptyList(), listOf(pop))
        assertEquals(MoodGrids(first.moods, listOf(pop)), repo().grids())
        assertEquals(MoodGrids(first.moods, listOf(pop)), repo().grids())
        assertEquals(MoodGrids(first.moods, listOf(pop)), repo().cached())
        assertEquals(2, gridReads)
        // And the other way round: genres missing keeps the saved genres
        day++
        gridsAnswer = MoodGrids(listOf(MoodTile("Sleep", "p3")), emptyList())
        assertEquals(MoodGrids(listOf(MoodTile("Sleep", "p3")), listOf(pop)), repo().grids())
        assertEquals(3, gridReads)
    }

    @Test
    fun `with nothing saved, an answer without the grids hides both until tomorrow`() = runTest {
        gridsAnswer = MoodGrids.None
        assertTrue(repo().grids().isEmpty())
        assertTrue(repo().grids().isEmpty())
        assertEquals(1, gridReads)
    }

    @Test
    fun `nothing saved and no answer is no grid`() = runTest {
        gridsAnswer = null
        assertTrue(repo().grids().isEmpty())
        assertTrue(repo().cached().isEmpty())
    }

    @Test
    fun `a page is kept for half an hour, then read again`() = runTest {
        val repo = repo()
        assertEquals("Chill", repo.page("p1", "Chill").title)
        repo.page("p1", "Chill")
        assertEquals(1, pageReads.size)
        clock += MoodsRepository.PAGE_TTL_MS
        repo.page("p1", "Chill")
        assertEquals(2, pageReads.size)
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
        assertEquals(2, pageReads.size)
    }

    // ── Covers ─────────────────────────────────────────────────────────

    @Test
    fun `a tile's covers are its page's first two pictures, saved small, and read again after a week`() = runTest {
        repo().grids()
        pageAnswer = page("A", "B", "C")
        assertEquals(listOf(cover("A"), cover("B")), repo().covers("p1", "Chill"))
        assertEquals(1, pageReads.size)
        // Saved: a new process paints them at once and asks nothing all week
        assertEquals(mapOf("p1" to listOf(cover("A"), cover("B"))), repo().savedCovers())
        day += MoodsRepository.COVER_DAYS - 1
        assertEquals(listOf(cover("A"), cover("B")), repo().covers("p1", "Chill"))
        assertEquals(1, pageReads.size)
        // A week on, the page is read again and its new covers replace them
        day++
        pageAnswer = page("D", "E")
        assertEquals(listOf(cover("D"), cover("E")), repo().covers("p1", "Chill"))
        assertEquals(2, pageReads.size)
    }

    @Test
    fun `Data saver on a metered network reads no page for covers - old ones keep showing, none are fetched`() = runTest {
        repo().grids()
        dataSaver = true
        assertNull(repo().covers("p1", "Chill"))
        assertTrue(pageReads.isEmpty())
        assertTrue(repo().savedCovers().isEmpty())
        // Covers saved before still paint, even a stale week's
        dataSaver = false
        repo().covers("p1", "Chill")
        day += MoodsRepository.COVER_DAYS + 3
        dataSaver = true
        assertNull(repo().covers("p1", "Chill"))
        assertEquals(1, pageReads.size)
        assertEquals(listOf(cover("A"), cover("B")), repo().savedCovers()["p1"])
        // Off Data saver (or on Wi-Fi) the stale covers are read again
        dataSaver = false
        repo().covers("p1", "Chill")
        assertEquals(2, pageReads.size)
    }

    @Test
    fun `at most two pages are read for covers at once, and a tile scrolled away while it waited costs nothing`() = runTest {
        repo().grids()
        val gate = CompletableDeferred<Unit>()
        var inFlight = 0
        var most = 0
        // Everything on the test's own clock, so runCurrent() sees exactly who holds a turn
        val repo = repo(io = StandardTestDispatcher(testScheduler), fetchPage = { params, _ ->
            inFlight++
            most = maxOf(most, inFlight)
            gate.await()
            inFlight--
            pageReads += params
            page(params)
        })
        val visible = mutableSetOf("p1", "p2", "g1", "p4")
        val reads = listOf("p1", "p2", "g1", "p4").map { params -> async { repo.covers(params, params) { params in visible } } }
        runCurrent()
        assertEquals(2, inFlight)
        // Scrolled on: two tiles left the screen before their turn
        visible -= setOf("g1", "p4")
        gate.complete(Unit)
        val covers = reads.awaitAll()
        assertEquals(2, most)
        assertEquals(listOf("p1", "p2"), pageReads.sorted())
        assertEquals(listOf(listOf(cover("p1")), listOf(cover("p2")), null, null), covers)
    }

    @Test
    fun `opening a tile refreshes its covers for free, and a read that fails leaves the saved ones`() = runTest {
        repo().grids()
        val repo = repo()
        repo.page("p1", "Chill")
        assertEquals(1, pageReads.size)
        // The cover read finds them fresh: no second request
        assertEquals(listOf(cover("A"), cover("B")), repo.covers("p1", "Chill"))
        assertEquals(1, pageReads.size)
        // A week on with no answer: null, and the saved covers stay
        day += MoodsRepository.COVER_DAYS
        pageAnswer = null
        assertNull(repo().covers("p1", "Chill"))
        assertEquals(listOf(cover("A"), cover("B")), repo().savedCovers()["p1"])
    }

    @Test
    fun `covers of a tile that left the grids are dropped with the next write`() = runTest {
        repo().grids()
        repo().covers("p1", "Chill")
        repo().covers("p2", "Focus")
        assertEquals(setOf("p1", "p2"), repo().savedCovers().keys)
        // Tomorrow Focus is gone (a seasonal tile rotated out)
        day++
        gridsAnswer = MoodGrids(listOf(chill), listOf(rock))
        repo().grids()
        repo().covers("g1", "Rock")
        assertEquals(setOf("p1", "g1"), repo().savedCovers().keys)
    }

    @Test
    fun `a cover is asked for at 128 px from YouTube's picture hosts, any other address as it came`() {
        assertEquals("https://lh3.googleusercontent.com/x=w128-h128-l90-rj", MoodsRepository.coverUrl("https://lh3.googleusercontent.com/x=w1024-h1024-l90-rj"))
        assertEquals("https://yt3.googleusercontent.com/x=w128-h128", MoodsRepository.coverUrl("https://yt3.googleusercontent.com/x=w544-h544"))
        assertEquals("https://yt3.ggpht.com/x=s128", MoodsRepository.coverUrl("https://yt3.ggpht.com/x=s1080"))
        assertEquals("https://i.ytimg.com/vi/a/maxresdefault.jpg", MoodsRepository.coverUrl("https://i.ytimg.com/vi/a/maxresdefault.jpg"))
    }
}
