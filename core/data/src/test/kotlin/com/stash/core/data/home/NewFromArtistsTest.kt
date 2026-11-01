package com.stash.core.data.home

import com.stash.core.data.db.dao.TopArtistRow
import com.stash.data.ytmusic.model.AlbumSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure half of "New from your artists": who your artists are, and which of their releases make the row. */
class NewFromArtistsTest {

    private fun row(artist: String, plays: Int, listenedMs: Long, last: Long = 0) =
        TopArtistRow(artist = artist, plays = plays, songs = 1, lastPlayedAt = last, albumArtUrl = null, albumArtPath = null, listenedMs = listenedMs)

    private fun album(id: String, year: String?, artist: String = "") =
        AlbumSummary(id = id, title = id, artist = artist, thumbnailUrl = null, year = year)

    @Test
    fun `credits group by main act and rank by time listened`() {
        val rows = listOf(
            row("Drake", plays = 10, listenedMs = 1_000),
            row("Drake, Future", plays = 5, listenedMs = 900),
            row("Phoebe Bridgers", plays = 3, listenedMs = 2_500),
            row("Earth, Wind & Fire", plays = 1, listenedMs = 100),
        )
        val top = NewFromArtists.topArtists(rows)
        // Drake's 1,900 ms beat nobody's 2,500, and an allow-listed comma name stays whole
        assertEquals(listOf("Phoebe Bridgers", "Drake", "Earth, Wind & Fire"), top.map { it.name })
        assertEquals(1_900L, top[1].listenedMs)
    }

    @Test
    fun `equal time goes to more plays, then the one heard last, and blank acts are left out`() {
        val rows = listOf(
            row("A", plays = 1, listenedMs = 500, last = 9),
            row("B", plays = 2, listenedMs = 500, last = 1),
            row("C", plays = 2, listenedMs = 500, last = 5),
            row("  ", plays = 50, listenedMs = 50_000),
        )
        assertEquals(listOf("C", "B", "A"), NewFromArtists.topArtists(rows).map { it.name })
        assertEquals(listOf("C"), NewFromArtists.topArtists(rows, limit = 1).map { it.name })
    }

    @Test
    fun `only this year's and last year's, newest first, each artist's newest before anyone's second`() {
        val pages = listOf(
            ArtistReleases(
                "Most played",
                albums = listOf(album("a-2025", "2025"), album("a-2019", "2019")),
                singles = listOf(album("s-2026", "2026"), album("s-2025", "2025"), album("s-2024", "2024")),
            ),
            ArtistReleases(
                "Second",
                albums = listOf(album("b-2026", "2026")),
                singles = listOf(album("t-2026", "2026"), album("t-2025", "2025")),
            ),
        )
        val row = NewFromArtists.pick(pages, thisYear = 2026)
        assertEquals(listOf("s-2026", "b-2026", "t-2026", "a-2025", "s-2025", "t-2025"), row.map { it.id })
        // The page's name fills the artist an artist page's card leaves blank
        assertEquals("Most played", row.first().artist)
        assertEquals("Second", row[1].artist)
    }

    @Test
    fun `a collaboration on two pages shows once, and one artist can't fill the row`() {
        val many = (1..9).map { album("x$it", "2026") }
        val pages = listOf(
            ArtistReleases("Prolific", albums = emptyList(), singles = many + album("collab", "2026")),
            ArtistReleases("Other", albums = listOf(album("collab", "2026"), album("y", "2026")), singles = emptyList()),
        )
        val row = NewFromArtists.pick(pages, thisYear = 2026)
        assertEquals(NewFromArtists.PER_ARTIST + 2, row.size)
        assertEquals(1, row.count { it.id == "collab" })
        assertEquals(NewFromArtists.PER_ARTIST, row.count { it.id.startsWith("x") })
    }

    @Test
    fun `undated releases are left out, and under three cards the row is empty`() {
        val pages = listOf(ArtistReleases("A", albums = listOf(album("n", null), album("old", "2020")), singles = listOf(album("ok", "2026"), album("ok2", "2025"))))
        assertTrue(NewFromArtists.pick(pages, thisYear = 2026).isEmpty())
    }

    @Test
    fun `the row caps at twenty`() {
        val pages = (1..10).map { a -> ArtistReleases("A$a", albums = emptyList(), singles = (1..4).map { album("$a-$it", "2026") }) }
        assertEquals(NewFromArtists.MAX, NewFromArtists.pick(pages, thisYear = 2026).size)
    }

    @Test
    fun `a saved row keeps only what is still new in a new year`() {
        val saved = listOf(album("a", "2025"), album("b", "2025"), album("c", "2026"), album("d", "2026"), album("e", "2026"))
        assertEquals(saved, NewFromArtists.stillNew(saved, 2026))
        assertEquals(listOf("c", "d", "e"), NewFromArtists.stillNew(saved, 2027).map { it.id })
        assertTrue(NewFromArtists.stillNew(saved, 2028).isEmpty())
    }
}
