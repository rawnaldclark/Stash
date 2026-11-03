package com.stash.feature.search

import com.stash.core.data.discography.NEW_RELEASE_DAYS
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.DatedRelease
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestReleasesTest {
    private val today = LocalDate.of(2026, 11, 3)

    /** A YouTube card as the artist page parses it: no artist, the year, YouTube's label. */
    private fun yt(id: String, year: String?, type: String? = null, title: String = id) =
        AlbumSummary(id = id, title = title, artist = "", thumbnailUrl = null, year = year, releaseType = type)

    /** A Qobuz release the merge adds to the Albums shelf: a full date, no label, a track count. */
    private fun qobuz(id: String, title: String, date: String?, tracks: Int? = 10) = AlbumSummary(
        id = id, title = title, artist = "Radiohead", thumbnailUrl = null, year = date,
        source = AlbumSource.QOBUZ, trackCount = tracks,
    )

    /** One of the artist's Qobuz releases as the merge dates it. */
    private fun dated(title: String, date: String, tracks: Int? = 1) = DatedRelease(title, date, tracks)

    private fun latest(
        albums: List<AlbumSummary>,
        singles: List<AlbumSummary>,
        dates: List<DatedRelease> = emptyList(),
        on: LocalDate = today,
    ) = latestReleases(albums, singles, dates, on)

    private fun ids(r: List<LatestRelease>) = r.map { it.album.id }

    @Test fun `only the releases from the last 60 days, newest first`() {
        // Queens of the Stone Age on 2026-11-03, as YouTube and Qobuz list them.
        val r = latest(
            albums = listOf(yt("perfecth", "2026", title = "Perfecth"), yt("itnr", "2023", title = "In Times New Roman...")),
            singles = listOf(
                yt("mob", "2026", "Single", title = "Run Away From the Mob"),
                yt("goth", "2026", "Single", title = "Where The Goth Girls At?"),
                yt("other", "2026", "Single", title = "Insignificant Other"),
                yt("easy", "2026", "Single", title = "Easy Street"),
                yt("catacombs", "2025", "EP", title = "Alive in the Catacombs"),
            ),
            dates = listOf(
                dated("Perfecth", "2026-10-30", tracks = 9),
                dated("Easy Street", "2026-07-14"),
                dated("Insignificant Other", "2026-08-25"),
                dated("Where The Goth Girls At?", "2026-09-23"),
                dated("Run Away From the Mob", "2026-10-28"),
            ),
        )
        assertEquals(listOf("perfecth", "mob", "goth"), ids(r))
        assertEquals(
            listOf(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 28), LocalDate.of(2026, 9, 23)),
            r.map { it.released },
        )
        assertEquals(listOf(ReleaseKind.ALBUM, ReleaseKind.SINGLE, ReleaseKind.SINGLE), r.map { it.kind })
        assertEquals(2026, r.first().year)
    }

    @Test fun `60 days ago is still new, 61 isn't`() {
        assertEquals(60L, NEW_RELEASE_DAYS)
        val singles = listOf(yt("edge", "2026", "Single", title = "Edge"), yt("past", "2026", "Single", title = "Past"))
        val r = latest(
            albums = emptyList(),
            singles = singles,
            dates = listOf(dated("Edge", "2026-09-04"), dated("Past", "2026-09-03")),
        )
        assertEquals(listOf("edge"), ids(r))
    }

    @Test fun `out today is new, out tomorrow (a pre-order) isn't yet`() {
        // Bob Dylan's Rolling Thunder box was listed on Qobuz days ahead of its date.
        val albums = listOf(yt("tomorrow", "2026", title = "Rolling Thunder"))
        val singles = listOf(yt("today", "2026", "Single", title = "Today"))
        val dates = listOf(dated("Rolling Thunder", "2026-11-04", tracks = 140), dated("Today", "2026-11-03"))
        assertEquals(listOf("today"), ids(latest(albums, singles, dates)))
        // The next day it is, and leads.
        assertEquals(listOf("tomorrow", "today"), ids(latest(albums, singles, dates, on = today.plusDays(1))))
    }

    @Test fun `the window reaches back into last year in January`() {
        val r = latest(
            albums = listOf(yt("dec", "2025", title = "December Album")),
            singles = emptyList(),
            dates = listOf(dated("December Album", "2025-12-12", tracks = 11)),
            on = LocalDate.of(2026, 1, 20),
        )
        assertEquals(listOf("dec"), ids(r))
    }

    @Test fun `the window is a parameter, the constant its default`() {
        val singles = listOf(yt("s", "2026", "Single", title = "S"))
        val dates = listOf(dated("S", "2026-09-20"))
        assertEquals(listOf("s"), ids(latestReleases(emptyList(), singles, dates, today)))
        assertTrue(latestReleases(emptyList(), singles, dates, today, days = 30).isEmpty())
    }

    @Test fun `an artist with nothing new has no row`() {
        // Radiohead: this year's newest YouTube card is a year-only 2025 release, and nothing is dated recently.
        val r = latest(
            albums = listOf(yt("hail-live", "2025", title = "Hail to the Thief (Live Recordings 2003-2009)"), yt("kid-a-mnesia", "2021")),
            singles = listOf(yt("follow-me", "2021", "Single"), yt("creep", "2021", "Single")),
            dates = listOf(dated("Hail to the Thief (Live Recordings 2003-2009)", "2025-08-13", tracks = 12)),
        )
        assertTrue(r.isEmpty())
        // This year's January album isn't new in November either.
        val january = latest(
            albums = listOf(yt("jan", "2026", title = "January")),
            singles = emptyList(),
            dates = listOf(dated("January", "2026-01-16", tracks = 10)),
        )
        assertTrue(january.isEmpty())
    }

    @Test fun `a release with no exact date isn't new, however recent its year`() {
        // YouTube's "2026" alone, with no Qobuz release to date it (a profile cached before the
        // dates were kept, a cold miss's first paint, or an artist Qobuz doesn't have).
        assertTrue(latest(listOf(yt("a", "2026", "Album")), listOf(yt("s", "2026", "Single"))).isEmpty())
        // A Qobuz release of the title from another year isn't this one.
        val otherYear = latest(
            albums = emptyList(),
            singles = listOf(yt("s", "2026", "Single", title = "Kid")),
            dates = listOf(dated("Kid", "2025-12-30")),
        )
        assertTrue(otherYear.isEmpty())
        // Nor is a release of another title, nor a date that isn't one.
        val noMatch = latest(
            albums = emptyList(),
            singles = listOf(yt("s", "2026", "Single", title = "Kid"), yt("t", "2026", "Single", title = "Trillions")),
            dates = listOf(dated("Kids", "2026-10-28"), dated("Trillions", "2026-10")),
        )
        assertTrue(noMatch.isEmpty())
        // A Qobuz album with only a year can't be placed either.
        assertTrue(latest(listOf(qobuz("q", "Q", "2026")), emptyList()).isEmpty())
    }

    @Test fun `a Qobuz copy of a YouTube album shows once, as YouTube's card, with Qobuz's date`() {
        val copy = qobuz("q-perfecth", "Perfecth (Deluxe Edition)", "2026-10-30", tracks = 9)
        val r = latest(
            albums = listOf(yt("perfecth", "2026", title = "Perfecth"), copy),
            singles = emptyList(),
            dates = listOf(dated("Perfecth (Deluxe Edition)", "2026-10-30", tracks = 9)),
        )
        assertEquals(listOf("perfecth"), ids(r))
        assertEquals(LocalDate.of(2026, 10, 30), r.single().released)
    }

    @Test fun `a new Qobuz album YouTube doesn't list shows with its own date`() {
        val berlin = qobuz("q-berlin", "Live in Berlin", "2026-10-09", tracks = 14)
        val r = latest(albums = listOf(yt("old", "2019"), berlin), singles = emptyList())
        assertEquals(listOf("q-berlin"), ids(r))
        // The album page is opened with the release exactly as the shelf held it.
        assertSame(berlin, r.single().album)
        assertEquals(ReleaseKind.ALBUM, r.single().kind)
    }

    @Test fun `a single named like the album keeps its own day, and so does the album`() {
        val albums = listOf(yt("album", "2026", title = "Perfecth"))
        val singles = listOf(yt("single", "2026", "Single", title = "Perfecth"))
        val dates = listOf(dated("Perfecth", "2026-08-01", tracks = 1), dated("Perfecth", "2026-10-30", tracks = 9))
        val r = latest(albums, singles, dates)
        // The single is 94 days old: only the album is new.
        assertEquals(listOf("album"), ids(r))
        assertEquals(LocalDate.of(2026, 10, 30), r.single().released)
        // A month later than that, the single alone would be.
        val singleNew = latest(albums, singles, listOf(dated("Perfecth", "2026-10-01", tracks = 1), dated("Perfecth", "2026-03-06", tracks = 9)))
        assertEquals(listOf("single"), ids(singleNew))
    }

    @Test fun `of several Qobuz copies of one release the earliest dates it`() {
        // A later edition of the same title and year doesn't make the release new.
        val r = latest(
            albums = listOf(yt("a", "2026", title = "Iceman")),
            singles = emptyList(),
            dates = listOf(dated("ICEMAN", "2026-10-20", tracks = 21), dated("Iceman", "2026-05-15", tracks = 18)),
        )
        assertTrue(r.isEmpty())
        // Copies dated alike just date it.
        val alike = latest(
            albums = emptyList(),
            singles = listOf(yt("s", "2026", "Single", title = "I Knew It, I Knew You")),
            dates = List(3) { dated("I Knew It, I Knew You", "2026-10-16", tracks = 1) } + dated("I Knew It, I Knew You", "2026-10-16", tracks = 3),
        )
        assertEquals(listOf("s"), ids(alike))
    }

    @Test fun `on the same day the album comes before the single`() {
        val r = latest(
            albums = listOf(yt("album", "2026", title = "Surface, Echo & Sound")),
            singles = listOf(yt("focus", "2026", "Single", title = "Seriously")),
            dates = listOf(dated("Seriously", "2026-10-30", tracks = 3), dated("Surface, Echo & Sound", "2026-10-30", tracks = 11)),
        )
        assertEquals(listOf("album", "focus"), ids(r))
    }

    @Test fun `a release on both shelves shows once, as its album-shelf card`() {
        val r = latest(
            albums = listOf(yt("same", "2026", "Album", title = "Same")),
            singles = listOf(yt("same", "2026", "EP", title = "Same"), yt("s", "2026", "Single", title = "S")),
            dates = listOf(dated("Same", "2026-10-02", tracks = 6), dated("S", "2026-10-01")),
        )
        assertEquals(listOf("same", "s"), ids(r))
        assertEquals(ReleaseKind.ALBUM, r.first().kind)
    }

    @Test fun `a Qobuz album only named like a YouTube EP is a newer release, not a copy`() {
        // Holy drill, as the phone had it: YouTube lists the EP "In Christ We Trust" (2024);
        // the merge adds that release again and "In Christ We Trust (The Album)" (2026),
        // which YouTube doesn't have at all.
        val r = latest(
            albums = listOf(
                qobuz("q-album", "In Christ We Trust (The Album)", "2026-09-11"),
                qobuz("q-ep", "In Christ We Trust", "2024-03-22"),
            ),
            singles = listOf(yt("icwt", "2024", "EP", title = "In Christ We Trust")),
            on = LocalDate.of(2026, 9, 20),
        )
        assertEquals(listOf("q-album"), ids(r))
    }

    @Test fun `a Qobuz album named like a new YouTube single is the album, the single its title track`() {
        val r = latest(
            albums = listOf(qobuz("q-holy", "Holy", "2026-10-23", tracks = 12)),
            singles = listOf(yt("holy-single", "2026", "Single", title = "Holy")),
            dates = listOf(dated("Holy", "2026-10-23", tracks = 12), dated("Holy", "2026-09-26", tracks = 1)),
        )
        assertEquals(listOf("q-holy", "holy-single"), ids(r))
        assertEquals(LocalDate.of(2026, 9, 26), r.last().released)
    }

    @Test fun `the kind is YouTube's label, else the Albums shelf's, else unsaid`() {
        val titles = listOf("album", "mixtape", "unlabelled-album", "single", "ep", "unlabelled-single")
        val r = latest(
            albums = listOf(yt("album", "2026", "ALBUM"), yt("mixtape", "2026", "Mixtape"), yt("unlabelled-album", "2026")),
            singles = listOf(yt("single", "2026", "Single"), yt("ep", "2026", "EP"), yt("unlabelled-single", "2026")),
            dates = titles.map { dated(it, "2026-10-10", tracks = null) },
        )
        val kinds = r.associate { it.album.id to it.kind }
        assertEquals(ReleaseKind.ALBUM, kinds["album"])
        assertEquals(ReleaseKind.MIXTAPE, kinds["mixtape"])
        assertEquals(ReleaseKind.ALBUM, kinds["unlabelled-album"])
        assertEquals(ReleaseKind.SINGLE, kinds["single"])
        assertEquals(ReleaseKind.EP, kinds["ep"])
        // A single can't be told from an EP without the label, so the card says neither.
        assertNull(kinds["unlabelled-single"])
    }

    @Test fun `a Qobuz release reads as an album only at album length`() {
        val r = latest(
            albums = listOf(qobuz("q12", "Zero Point", "2026-10-23", tracks = 12), qobuz("q5", "Persona", "2026-10-09", tracks = 5)),
            singles = emptyList(),
        )
        assertEquals(listOf("q12", "q5"), ids(r))
        assertEquals(ReleaseKind.ALBUM, r[0].kind)
        // Up to six tracks it may be a single's CD or an EP, so the card shows the year alone.
        assertNull(r[1].kind)
    }

    @Test fun `caps the row at the newest ten`() {
        val singles = (1..12).map { yt("s$it", "2026", "Single", title = "S$it") }
        val dates = (1..12).map { dated("S$it", LocalDate.of(2026, 10, it).toString()) }
        val r = latest(emptyList(), singles, dates)
        assertEquals(LATEST_RELEASES_MAX, r.size)
        assertEquals((12 downTo 3).map { "s$it" }, ids(r))
    }

    @Test fun `no shelves, no row`() {
        assertTrue(latest(emptyList(), emptyList(), listOf(dated("X", "2026-11-01"))).isEmpty())
    }
}
