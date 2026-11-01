package com.stash.core.data.home

import com.stash.core.data.db.dao.TopArtistRow
import com.stash.core.data.listening.ListeningRange
import com.stash.data.ytmusic.InnerTubeClient
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.ArtistLookup
import com.stash.data.ytmusic.model.ArtistProfile
import com.stash.data.ytmusic.model.ArtistSummary
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException
import java.time.LocalDate

/**
 * [NewFromArtistsRepository]: end to end on a real artist page and the real search that finds it (data:ytmusic's
 * `artist_new_releases.json` and `search_artists_sabrina.json`: Sabrina Carpenter, fetched signed out with hl=en
 * gl=US on 2026-11-01, trimmed) read through the real [YTMusicApiClient]; and with made-up artists for what a build
 * does when some pages answer and some don't. The row is saved in a temporary folder.
 */
class NewFromArtistsRepositoryTest {

    @get:Rule val folder = TemporaryFolder()

    private val nov1 = LocalDate.of(2026, 11, 1).toEpochDay()
    private var today = nov1
    private var now = 1_793_000_000_000L
    private var searches = 0
    private var browses = 0
    private var answer = true

    private val api: YTMusicApiClient by lazy {
        val inner = mock<InnerTubeClient>()
        val page = YtFixtures.json("artist_new_releases.json")
        val found = YtFixtures.json("search_artists_sabrina.json")
        runBlocking {
            whenever(inner.searchWithStatus(eq("Sabrina Carpenter"), anyOrNull())).thenAnswer {
                searches++
                InnerTubeClient.RequestOutcome(if (answer) found else null, if (answer) 200 else -1)
            }
            whenever(inner.browse(eq("UCz51ZodJbYUNfkdPHOjJKKw"), anyOrNull())).thenAnswer {
                browses++
                if (answer) page else null
            }
        }
        YTMusicApiClient(inner)
    }

    private fun history(vararg artists: String): (ListeningRange) -> List<TopArtistRow> = { range ->
        if (range == ListeningRange.Months3) {
            artists.mapIndexed { i, a -> TopArtistRow(a, plays = 10 - i, songs = 1, lastPlayedAt = 0, albumArtUrl = null, albumArtPath = null, listenedMs = 1_000L - i) }
        } else {
            emptyList()
        }
    }

    private fun file() = HomeShelfStore(folder.root).read(NewFromArtistsRepository.FILE, NewFromArtistsFile.serializer())

    /** The real client for Sabrina Carpenter. */
    private fun repo(rows: (ListeningRange) -> List<TopArtistRow> = history("Sabrina Carpenter")) = NewFromArtistsRepository(
        store = HomeShelfStore(folder.root),
        today = { today },
        nowMs = { now },
        topArtistRows = { rows(it) },
        lookUpArtist = { api.lookUpArtist(it) },
        artistPage = { api.getArtistPage(it) },
        playedTitles = { emptySet() },
    )

    // ── Made-up artists: each one's look-up and page, set per test ──────────

    private val lookups = mutableMapOf<String, ArtistLookup>()
    private val pages = mutableMapOf<String, () -> ArtistProfile>()
    private val looked = mutableListOf<String>()
    private val read = mutableListOf<String>()
    private var played = emptySet<String>()

    private fun found(name: String, exact: Boolean = true) = ArtistLookup.Found(ArtistSummary("UC-$name", name, null), exact)

    private fun release(id: String, year: String = "2026") = AlbumSummary(id, id, "", null, year)

    private fun page(name: String, vararg releases: AlbumSummary, popular: List<String> = emptyList()) = ArtistProfile(
        id = "UC-$name", name = name, avatarUrl = null, subscribersText = null,
        popular = popular.map { TrackSummary("v-$it", it, name, null, 180.0, null) },
        albums = releases.toList(), singles = emptyList(), related = emptyList(),
    )

    private val blank = ArtistProfile("", "", null, null, emptyList(), emptyList(), emptyList(), emptyList())

    private fun fake(vararg artists: String) = NewFromArtistsRepository(
        store = HomeShelfStore(folder.root),
        today = { today },
        nowMs = { now },
        topArtistRows = { history(*artists)(it) },
        lookUpArtist = { name -> looked += name; lookups[name] ?: ArtistLookup.NoAnswer },
        artistPage = { id -> read += id; pages[id]?.invoke() ?: blank },
        playedTitles = { played },
    )

    /** Artist [name] found, with a page of [releases] (2026 unless said). */
    private fun artist(name: String, vararg releases: String) {
        lookups[name] = found(name)
        pages["UC-$name"] = { page(name, *releases.map { release(it) }.toTypedArray()) }
    }

    // ── The real page ───────────────────────────────────────────────────────

    @Test
    fun `a real page makes a row of this year's and last year's releases, newest first`() = runTest {
        val row = repo().shelf()
        assertEquals("Bring Your Love", row.first().title)
        assertEquals("2026", row.first().year)
        // 2025: the page's order, the album ahead of the singles
        assertEquals(listOf("Man’s Best Friend", "Manchild"), row.drop(1).take(2).map { it.title })
        assertTrue(row.all { it.year == "2026" || it.year == "2025" })
        assertTrue(row.all { it.artist == "Sabrina Carpenter" && it.id.startsWith("MPREb_") })
        assertTrue(row.size in NewFromArtists.MIN..NewFromArtists.PER_ARTIST)
    }

    @Test
    fun `built once a day - a second look reads the saved row, the next day builds again`() = runTest {
        val first = repo().shelf()
        assertEquals(1, browses)
        // A new repository (process death) reads the file
        assertEquals(first, repo().shelf())
        assertEquals(first, repo().cached())
        assertEquals(1, browses)
        today = nov1 + 1
        assertEquals(first, repo().shelf())
        assertEquals(2, browses)
        // The artist's page id was saved: one search
        assertEquals(1, searches)
        // ...for thirty days, then looked up again
        today = nov1 + NewFromArtistsRepository.LOOKUP_DAYS
        repo().shelf()
        assertEquals(2, searches)
    }

    @Test
    fun `no page answering keeps the saved row, and the next try waits an hour`() = runTest {
        val first = repo().shelf()
        today = nov1 + 1
        answer = false
        assertEquals(first, repo().shelf())
        assertEquals(2, browses)
        // Home's resume a minute later: no request, the saved row
        now += 60_000
        assertEquals(first, repo().shelf())
        assertEquals(2, browses)
        // An hour on, it tries again, and an answer builds the day's row
        now += NewFromArtistsRepository.RETRY_MS
        answer = true
        assertEquals(first, repo().shelf())
        assertEquals(3, browses)
        assertEquals(today, file()!!.day)
        assertEquals(first, repo().shelf())
        assertEquals(3, browses)
    }

    @Test
    fun `no listening history means no row, no file and no request`() = runTest {
        repo().shelf()
        assertTrue(file() != null)
        // The history was cleared: the shelf, and what it knew of your artists, go
        today = nov1 + 1
        assertTrue(repo(rows = { emptyList() }).shelf().isEmpty())
        assertNull(file())
        assertEquals(1, searches)
        assertEquals(1, browses)
    }

    @Test
    fun `a broken file is a cache miss, never a crash`() = runTest {
        folder.root.resolve("${NewFromArtistsRepository.FILE}.json").writeText("{not json")
        assertTrue(repo().cached().isEmpty())
        assertTrue(repo().shelf().isNotEmpty())
    }

    // ── Some answer, some don't ─────────────────────────────────────────────

    @Test
    fun `an artist whose page fails keeps their saved releases in the row`() = runTest {
        artist("A", "a1", "a2")
        artist("B", "b1", "b2")
        assertEquals(setOf("a1", "a2", "b1", "b2"), fake("A", "B").shelf().map { it.id }.toSet())
        today = nov1 + 1
        pages["UC-B"] = { blank }
        // B's page didn't answer today: B's releases stay, A's are today's
        assertEquals(setOf("a1", "a2", "b1", "b2"), fake("A", "B").shelf().map { it.id }.toSet())
        // One of two answered: half is enough, the day is stamped
        assertEquals(today, file()!!.day)
    }

    @Test
    fun `fewer than half answering doesn't stamp the day, and the next try waits an hour`() = runTest {
        artist("A", "a1", "a2", "a3")
        listOf("B", "C", "D").forEach { lookups[it] = ArtistLookup.NoAnswer }
        assertEquals(listOf("a1", "a2", "a3"), fake("A", "B", "C", "D").shelf().map { it.id })
        assertEquals(-1L, file()!!.day)
        looked.clear()
        now += 30 * 60_000
        fake("A", "B", "C", "D").shelf()
        assertEquals(emptyList<String>(), looked)
        now += 31 * 60_000
        fake("A", "B", "C", "D").shelf()
        assertEquals(listOf("B", "C", "D"), looked.sorted())
    }

    @Test
    fun `a build that falls short never empties a row`() = runTest {
        artist("A", "a1", "a2", "a3")
        val first = fake("A").shelf()
        assertEquals(3, first.size)
        // Your listening moved on to B and C, whose searches don't answer today: nothing to show from them yet
        today = nov1 + 1
        lookups["B"] = ArtistLookup.NoAnswer
        lookups["C"] = ArtistLookup.NoAnswer
        assertEquals(first, fake("B", "C").shelf())
        assertEquals(first, fake("B", "C").cached())
    }

    @Test
    fun `a search with no answer is never taken for "not on YouTube Music", and doesn't stamp the day`() = runTest {
        lookups["A"] = ArtistLookup.NoAnswer
        assertTrue(fake("A").shelf().isEmpty())
        val saved = file()!!
        assertEquals(-1L, saved.day)
        assertFalse(saved.artists["a"]?.notFound == true)
        assertTrue(saved.failedAt > 0)
    }

    @Test
    fun `artists YouTube Music doesn't have are remembered for thirty days, then searched again`() = runTest {
        lookups["A"] = ArtistLookup.NotFound
        lookups["B"] = ArtistLookup.NotFound
        assertTrue(fake("A", "B").shelf().isEmpty())
        // Every artist answered: the day is stamped, no row
        assertEquals(today, file()!!.day)
        today = nov1 + 1
        looked.clear()
        assertTrue(fake("A", "B").shelf().isEmpty())
        assertEquals(emptyList<String>(), looked)
        today = nov1 + NewFromArtistsRepository.LOOKUP_DAYS
        fake("A", "B").shelf()
        assertEquals(listOf("A", "B"), looked.sorted())
    }

    @Test
    fun `a loose name match counts only when the page's top songs include one you've played`() = runTest {
        // "Bon" is found as "Bon Iver", whose top songs you've never played: someone else, remembered as not there
        lookups["Bon"] = found("Bon Iver", exact = false)
        pages["UC-Bon Iver"] = { page("Bon Iver", release("For Emma"), release("22, A Million"), release("SABLE"), popular = listOf("Holocene")) }
        played = setOf("bon's own song")
        assertTrue(fake("Bon").shelf().isEmpty())
        assertTrue(file()!!.artists.getValue("bon").notFound)
        // With one of them among your plays, it's yours
        folder.root.resolve("${NewFromArtistsRepository.FILE}.json").delete()
        played = setOf("holocene")
        assertEquals(3, fake("Bon").shelf().size)
        assertEquals("UC-Bon Iver", file()!!.artists.getValue("bon").id)
    }

    @Test
    fun `a page that fails while others load is looked up again next time`() = runTest {
        artist("A", "a1", "a2")
        artist("B", "b1", "b2")
        fake("A", "B").shelf()
        today = nov1 + 1
        pages["UC-B"] = { throw IOException("gone") }
        fake("A", "B").shelf()
        // B's id is dropped (its releases kept): tomorrow B is searched again
        assertNull(file()!!.artists.getValue("b").id)
        assertTrue(file()!!.artists.getValue("b").releases != null)
        today = nov1 + 2
        looked.clear()
        artist("B", "b3")
        fake("A", "B").shelf()
        assertEquals(listOf("B"), looked)
    }

    @Test
    fun `the no-answer profile is a failed page, not an artist without releases`() = runTest {
        lookups["A"] = found("A")
        assertTrue(fake("A").shelf().isEmpty())
        // Not stamped as today's: a file with today's day would stop the next look from trying
        assertTrue(file()!!.day != today)
        assertEquals(listOf("UC-A"), read)
    }
}
