package com.stash.feature.search

import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestReleasesTest {
    /** A YouTube card as the artist page parses it: no artist, the year, YouTube's label. */
    private fun yt(id: String, year: String?, type: String? = null, title: String = id) =
        AlbumSummary(id = id, title = title, artist = "", thumbnailUrl = null, year = year, releaseType = type)

    /** A Qobuz album the merge adds to the Albums shelf: a full date, no label, a track count. */
    private fun qobuz(id: String, title: String, date: String?) = AlbumSummary(
        id = id, title = title, artist = "Radiohead", thumbnailUrl = null, year = date,
        source = AlbumSource.QOBUZ, trackCount = 10,
    )

    private fun ids(r: List<LatestRelease>) = r.map { it.album.id }

    @Test fun `albums and singles merge into one row, newest first`() {
        val r = latestReleases(
            albums = listOf(yt("a2020", "2020", "Album"), yt("a2015", "2015", "Album")),
            singles = listOf(yt("s2023", "2023", "Single"), yt("s2018", "2018", "Single")),
        )
        assertEquals(listOf("s2023", "a2020", "s2018", "a2015"), ids(r))
        assertEquals(listOf(2023, 2020, 2018, 2015), r.map { it.year })
    }

    @Test fun `within a year the albums come first and each shelf keeps YouTube's order`() {
        // KID A MNESIA (album) came out after its singles Follow Me Around and If You Say the Word.
        val r = latestReleases(
            albums = listOf(yt("hail-live", "2025"), yt("kid-a-mnesia", "2021"), yt("oknotok", "2017")),
            singles = listOf(yt("follow-me", "2021"), yt("say-the-word", "2021"), yt("ill-wind", "2019")),
        )
        assertEquals(
            listOf("hail-live", "kid-a-mnesia", "follow-me", "say-the-word", "ill-wind", "oknotok"),
            ids(r),
        )
    }

    @Test fun `a Qobuz album dated to the day sorts by its year and reads as an album`() {
        val oknotok = qobuz("123", "OK Computer OKNOTOK 1997 2017", "2017-06-23")
        val r = latestReleases(
            albums = listOf(yt("kid-a-mnesia", "2021", "Album"), oknotok),
            singles = listOf(yt("ill-wind", "2019", "Single")),
        )
        assertEquals(listOf("kid-a-mnesia", "ill-wind", "123"), ids(r))
        val card = r.last()
        assertEquals(2017, card.year)
        assertEquals(ReleaseKind.ALBUM, card.kind)
        // The album page is opened with the release exactly as the shelf held it.
        assertSame(oknotok, card.album)
        assertEquals("2017-06-23", card.album.year)
    }

    @Test fun `a release without a year is left out`() {
        val r = latestReleases(
            albums = listOf(yt("undated", null), yt("blank", ""), yt("a2010", "2010")),
            singles = listOf(yt("s2012", "2012"), yt("words", "Single")),
        )
        assertEquals(listOf("s2012", "a2010"), ids(r))
    }

    @Test fun `one dated release on each shelf is enough`() {
        val r = latestReleases(albums = listOf(yt("a", "2001")), singles = listOf(yt("s", "1999")))
        assertEquals(listOf("a", "s"), ids(r))
    }

    @Test fun `the row is empty unless both shelves have a dated release`() {
        val albums = listOf(yt("a1", "2020"), yt("a2", "2018"))
        val singles = listOf(yt("s1", "2021"), yt("s2", "2019"))
        // One shelf only: the row would just repeat it, right below.
        assertTrue(latestReleases(albums, emptyList()).isEmpty())
        assertTrue(latestReleases(emptyList(), singles).isEmpty())
        // The other shelf has releases, but none with a year.
        assertTrue(latestReleases(albums, listOf(yt("s?", null))).isEmpty())
        assertTrue(latestReleases(listOf(yt("a?", null)), singles).isEmpty())
        assertTrue(latestReleases(emptyList(), emptyList()).isEmpty())
    }

    @Test fun `caps the row at the newest ten`() {
        val albums = (2000..2007).map { yt("a$it", "$it") }.reversed()
        val singles = (2010..2017).map { yt("s$it", "$it") }.reversed()
        val r = latestReleases(albums, singles)
        assertEquals(LATEST_RELEASES_MAX, r.size)
        assertEquals((2017 downTo 2010).map { "s$it" } + listOf("a2007", "a2006"), ids(r))
    }

    @Test fun `a release on both shelves shows once, as its album-shelf card`() {
        val r = latestReleases(
            albums = listOf(yt("same", "2020", "Album")),
            singles = listOf(yt("same", "2020", "EP"), yt("s", "2019", "Single")),
        )
        assertEquals(listOf("same", "s"), ids(r))
        assertEquals(ReleaseKind.ALBUM, r.first().kind)
    }

    @Test fun `a Qobuz copy of a YouTube EP is dropped, YouTube's card stays`() {
        val r = latestReleases(
            albums = listOf(yt("a", "2020", "Album"), qobuz("q", "My Iron Lung", "1994-09-26")),
            singles = listOf(yt("mil", "1994", "EP", title = "My Iron Lung")),
        )
        assertEquals(listOf("a", "mil"), ids(r))
        assertEquals(ReleaseKind.EP, r.last().kind)
    }

    @Test fun `a Qobuz album only named like a YouTube EP is a newer release, not a copy`() {
        // Holy drill, as the phone had it: YouTube lists the EP "In Christ We Trust" (2024);
        // the merge adds that release again and "In Christ We Trust (The Album)" (2026),
        // the newest of everything, which YouTube doesn't have at all.
        val r = latestReleases(
            albums = listOf(
                qobuz("q-album", "In Christ We Trust (The Album)", "2026-09-11"),
                qobuz("q-ep", "In Christ We Trust", "2024-03-22"),
            ),
            singles = listOf(
                yt("trust-god-acoustic", "2025", "Single", title = "Trust God (Acoustic version)"),
                yt("icwt", "2024", "EP", title = "In Christ We Trust"),
            ),
        )
        assertEquals(listOf("q-album", "trust-god-acoustic", "icwt"), ids(r))
    }

    @Test fun `an edition of a YouTube EP is still a copy`() {
        val r = latestReleases(
            albums = listOf(yt("a", "2020", "Album"), qobuz("q", "My Iron Lung (Deluxe Edition)", "1994-09-26")),
            singles = listOf(yt("mil", "1994", "EP", title = "My Iron Lung")),
        )
        assertEquals(listOf("a", "mil"), ids(r))
    }

    @Test fun `a YouTube single named like the album is a release of its own`() {
        val r = latestReleases(
            albums = listOf(yt("lover-album", "2019", "Album", title = "Lover")),
            singles = listOf(yt("lover-single", "2019", "Single", title = "Lover")),
        )
        assertEquals(listOf("lover-album", "lover-single"), ids(r))
    }

    @Test fun `the kind is YouTube's label, else the Albums shelf's, else unsaid`() {
        val r = latestReleases(
            albums = listOf(
                yt("album", "2024", "ALBUM"),
                yt("mixtape", "2023", "Mixtape"),
                yt("compilation", "2022", "Compilation"),
                yt("unlabelled-album", "2021"),
            ),
            singles = listOf(
                yt("single", "2020", "Single"),
                yt("ep", "2019", "EP"),
                yt("unlabelled-single", "2018"),
            ),
        )
        val kinds = r.associate { it.album.id to it.kind }
        assertEquals(ReleaseKind.ALBUM, kinds["album"])
        assertEquals(ReleaseKind.MIXTAPE, kinds["mixtape"])
        assertEquals(ReleaseKind.COMPILATION, kinds["compilation"])
        assertEquals(ReleaseKind.ALBUM, kinds["unlabelled-album"])
        assertEquals(ReleaseKind.SINGLE, kinds["single"])
        assertEquals(ReleaseKind.EP, kinds["ep"])
        // A single can't be told from an EP without the label, so the card says neither.
        assertNull(kinds["unlabelled-single"])
    }
}
