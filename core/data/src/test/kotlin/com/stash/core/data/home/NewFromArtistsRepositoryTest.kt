package com.stash.core.data.home

import com.stash.core.data.db.dao.TopArtistRow
import com.stash.core.data.listening.ListeningRange
import com.stash.data.ytmusic.InnerTubeClient
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.ArtistProfile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate

/**
 * [NewFromArtistsRepository] end to end on a real artist page (`home/artist_new_releases.json`: Sabrina Carpenter's
 * YouTube Music page, fetched signed out with hl=en gl=US on 2026-11-01, trimmed) read through the real
 * [YTMusicApiClient] parser, with a saved row in a temporary folder.
 */
class NewFromArtistsRepositoryTest {

    @get:Rule val folder = TemporaryFolder()

    private val nov1 = LocalDate.of(2026, 11, 1).toEpochDay()
    private var today = nov1
    private var resolves = 0
    private var browses = 0
    private var answer = true

    private val page by lazy {
        val text = this::class.java.classLoader!!.getResourceAsStream("home/artist_new_releases.json")!!.bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject
    }

    private val api: YTMusicApiClient by lazy {
        val inner = mock<InnerTubeClient>()
        runBlocking {
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

    private fun repo(
        store: HomeShelfStore = HomeShelfStore(folder.root),
        rows: (ListeningRange) -> List<TopArtistRow> = history("Sabrina Carpenter"),
    ) = NewFromArtistsRepository(
        store = store,
        today = { today },
        topArtistRows = { rows(it) },
        resolveArtistId = { name -> resolves++; if (name == "Sabrina Carpenter") "UCz51ZodJbYUNfkdPHOjJKKw" else null },
        artistPage = { id -> api.getArtistPage(id) },
    )

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
    fun `built once a day: a second look reads the saved row, the next day builds again`() = runTest {
        val first = repo().shelf()
        assertEquals(1, browses)
        // A new repository (process death) reads the file
        assertEquals(first, repo().shelf())
        assertEquals(first, repo().cached())
        assertEquals(1, browses)
        today = nov1 + 1
        assertEquals(first, repo().shelf())
        assertEquals(2, browses)
        // The artist's page id was saved: one search ever
        assertEquals(1, resolves)
    }

    @Test
    fun `no page answering keeps the saved row and tries again on the next look`() = runTest {
        val first = repo().shelf()
        today = nov1 + 1
        answer = false
        assertEquals(first, repo().shelf())
        assertEquals(first, repo().shelf())
        assertEquals(3, browses) // tried again: the day wasn't stamped
        answer = true
        assertEquals(first, repo().shelf())
        assertEquals(4, browses)
        assertEquals(first, repo().shelf())
        assertEquals(4, browses)
    }

    @Test
    fun `no listening history means no row and no request`() = runTest {
        assertTrue(repo(rows = { emptyList() }).shelf().isEmpty())
        assertEquals(0, resolves)
        assertEquals(0, browses)
    }

    @Test
    fun `an artist YouTube can't find is skipped`() = runTest {
        val row = repo(rows = history("Nobody Anyone Knows", "Sabrina Carpenter")).shelf()
        assertTrue(row.isNotEmpty())
        assertEquals(2, resolves)
    }

    @Test
    fun `a broken file is a cache miss, never a crash`() = runTest {
        folder.root.resolve("${NewFromArtistsRepository.FILE}.json").writeText("{not json")
        assertTrue(repo().cached().isEmpty())
        assertTrue(repo().shelf().isNotEmpty())
    }

    @Test
    fun `the no-answer profile is a failed page, not an artist without releases`() = runTest {
        val repo = NewFromArtistsRepository(
            store = HomeShelfStore(folder.root),
            today = { today },
            topArtistRows = { history("Sabrina Carpenter")(it) },
            resolveArtistId = { "UC1" },
            artistPage = { ArtistProfile("UC1", "", null, null, emptyList(), emptyList(), emptyList(), emptyList()) },
        )
        assertTrue(repo.shelf().isEmpty())
        // Not stamped as today's: a file with today's day would stop the next look from trying
        val saved = HomeShelfStore(folder.root).read(NewFromArtistsRepository.FILE, NewFromArtistsFile.serializer())
        assertTrue(saved == null || saved.day != today)
    }
}
