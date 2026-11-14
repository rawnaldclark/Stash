package com.stash.data.download.lossless.qbdlx

import com.google.common.truth.Truth.assertThat
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.DatedRelease
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for [QobuzDiscographyProvider]. Its only collaborator
 * ([QbdlxApiClient]) is a final Kotlin class, so — mirroring
 * [QbdlxQobuzSourceTest] — it is MockK'd
 * (`coEvery` for the suspend calls). The real [DiscographyMerger] and
 * [com.stash.data.download.lossless.qobuz.QobuzCandidateMatcher] run
 * end-to-end so the match gate + merge behave exactly as in production.
 *
 * The gate's job is to FAIL SAFE: on any doubt it must return the YT lists
 * UNCHANGED rather than graft a stranger's discography.
 */
class QobuzDiscographyProviderTest {

    private val apiClient: QbdlxApiClient = mockk()

    private fun provider() = QobuzDiscographyProvider(apiClient)

    private fun ytAlbum(title: String, year: String? = "1991") =
        AlbumSummary("yt_$title", title, "My Bloody Valentine", null, year, AlbumSource.YOUTUBE)

    // Defaults to the matched artist + album-length track count so it survives
    // both gap-fill filters; pass a different `artist` to model a feature/cover,
    // or a low `tracksCount` to model a single/EP.
    private fun qAlbum(
        title: String,
        year: String? = "1991",
        artist: String = "My Bloody Valentine",
        tracksCount: Int = 10,
    ) = QbdlxAlbumItem(
        id = "qb_$title",
        title = title,
        artist = QbdlxPerformer(artist),
        release_date_original = year,
        tracks_count = tracksCount,
    )

    // (c) ────────────────────────────────────────────────────────────────
    @Test
    fun `gap-fill adds the Qobuz-only album and keeps YouTube on collision`() = runTest {
        coEvery { apiClient.searchArtists(any(), any()) } returns
            listOf(QbdlxArtistItem(1, "My Bloody Valentine"))
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns
            listOf(qAlbum("Loveless", "1991"), qAlbum("m b v", "2013"))

        val out = provider().mergeInto(
            "My Bloody Valentine",
            listOf(ytAlbum("Loveless", "1991")), // YT already has Loveless
            emptyList(),
        )

        // "m b v" is the gap Qobuz fills; "Loveless" stays YouTube's (authoritative).
        assertThat(out.albums.map { it.title }).containsExactly("m b v", "Loveless").inOrder()
        assertThat(out.albums.map { it.source })
            .containsExactly(AlbumSource.QOBUZ, AlbumSource.YOUTUBE).inOrder()
    }

    /** #481: a gap-filled album carries its track count to the album screen. */
    @Test
    fun `a gap-filled Qobuz album carries its track count`() = runTest {
        coEvery { apiClient.searchArtists(any(), any()) } returns
            listOf(QbdlxArtistItem(1, "My Bloody Valentine"))
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns
            listOf(qAlbum("Loveless", "1991"), qAlbum("m b v", "2013", tracksCount = 9))

        val out = provider().mergeInto("My Bloody Valentine", listOf(ytAlbum("Loveless", "1991")), emptyList())

        assertThat(out.albums.single { it.source == AlbumSource.QOBUZ }.trackCount).isEqualTo(9)
    }

    /** #755: a CD single with bonus tracks passes the length check; YouTube's Singles shelf keeps it out of Albums. */
    @Test
    fun `a Qobuz CD single YouTube lists as a single stays out of Albums`() = runTest {
        coEvery { apiClient.searchArtists(any(), any()) } returns
            listOf(QbdlxArtistItem(1, "My Bloody Valentine"))
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns
            listOf(qAlbum("Loveless", "1991"), qAlbum("m b v", "2013"), qAlbum("Soon", "1990", tracksCount = 6))

        val out = provider().mergeInto(
            "My Bloody Valentine",
            listOf(ytAlbum("Loveless", "1991")),
            listOf(ytAlbum("Soon", "1990")),
        )

        assertThat(out.albums.map { it.title }).containsExactly("m b v", "Loveless").inOrder()
        assertThat(out.singles.map { it.title }).containsExactly("Soon")
    }

    // (c2) cover-album filter ──────────────────────────────────────────────
    @Test
    fun `cover albums credited to other artists are filtered out`() = runTest {
        // Qobuz's artist-discography endpoint returns tribute/cover albums credited
        // to OTHER artists (real MBV case: "When You Sleep" by Arcade Golf Scene,
        // "sometimes" by Matt Cantu, all newer than the classics). Only the
        // matched artist's own releases must survive.
        coEvery { apiClient.searchArtists(any(), any()) } returns
            listOf(QbdlxArtistItem(1, "My Bloody Valentine"))
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns listOf(
            qAlbum("Loveless", "1991"),
            qAlbum("When You Sleep", "2022", artist = "Arcade Golf Scene"),
            qAlbum("sometimes", "2026", artist = "Matt Cantu"),
        )

        val out = provider().mergeInto(
            "My Bloody Valentine",
            listOf(ytAlbum("Loveless", "1991")),
            emptyList(),
        )

        assertThat(out.albums.map { it.title }).containsExactly("Loveless")
    }

    // (c3) singles/EPs filtered out of the album gap-fill ───────────────────
    @Test
    fun `singles and EPs are excluded from the album gap-fill`() = runTest {
        coEvery { apiClient.searchArtists(any(), any()) } returns
            listOf(QbdlxArtistItem(1, "My Bloody Valentine"))
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns listOf(
            qAlbum("Loveless", "1991", tracksCount = 11),          // real album → kept
            qAlbum("Sunny Sundae Smile", "1987", tracksCount = 1), // single → dropped
        )

        val out = provider().mergeInto("My Bloody Valentine", listOf(ytAlbum("Loveless")), emptyList())

        assertThat(out.albums.map { it.title }).containsExactly("Loveless")
    }

    // (c4) features (co-credited) filtered out ──────────────────────────────
    @Test
    fun `features where the artist is only co-credited are excluded`() = runTest {
        coEvery { apiClient.searchArtists(any(), any()) } returns
            listOf(QbdlxArtistItem(1, "Lil Wayne"))
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns listOf(
            qAlbum("Tha Carter V", "2018", artist = "Lil Wayne", tracksCount = 23),  // corroborates + own
            qAlbum("Tha Carter VI", "2025", artist = "Lil Wayne", tracksCount = 16), // own gap
            qAlbum("Down", "2009", artist = "Jay Sean, Lil Wayne", tracksCount = 12), // feature → dropped
        )

        val out = provider().mergeInto("Lil Wayne", listOf(ytAlbum("Tha Carter V", "2018")), emptyList())

        assertThat(out.albums.map { it.title }).contains("Tha Carter VI") // genuine gap added
        assertThat(out.albums.map { it.title }).doesNotContain("Down")    // feature excluded
    }

    // (c5) singles lane is never supplemented ───────────────────────────────
    @Test
    fun `singles lane stays 100 percent youtube`() = runTest {
        coEvery { apiClient.searchArtists(any(), any()) } returns
            listOf(QbdlxArtistItem(1, "My Bloody Valentine"))
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns
            listOf(qAlbum("Loveless", "1991", tracksCount = 11))
        val ytSingles = listOf(ytAlbum("Sunny Sundae Smile", "1987"))

        val out = provider().mergeInto("My Bloody Valentine", listOf(ytAlbum("Loveless")), ytSingles)

        assertThat(out.singles).isEqualTo(ytSingles) // unchanged; Qobuz never touches singles
    }

    // (d) ────────────────────────────────────────────────────────────────
    @Test
    fun `zero yt albums plus two differently-named candidates aborts homonym`() = runTest {
        // "the doors" vs "doors": distinct normalized names, both cross threshold
        // (subset coverage → 1.0). Ambiguous → must NOT graft either discography.
        coEvery { apiClient.searchArtists(any(), any()) } returns
            listOf(QbdlxArtistItem(1, "The Doors"), QbdlxArtistItem(2, "Doors"))
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns
            listOf(qAlbum("L.A. Woman", "1971")) // present but must be discarded

        val out = provider().mergeInto("The Doors", emptyList(), emptyList())

        assertThat(out.albums).isEmpty() // grafted nothing → aborted
    }

    // (e) ────────────────────────────────────────────────────────────────
    @Test
    fun `various-artists candidates are excluded and treated as no match`() = runTest {
        coEvery { apiClient.searchArtists(any(), any()) } returns
            listOf(QbdlxArtistItem(9, "Various Artists"), QbdlxArtistItem(10, "Verschiedene Interpreten"))
        val albums = listOf(ytAlbum("Loveless"))

        val out = provider().mergeInto("My Bloody Valentine", albums, emptyList())

        assertThat(out.albums).isEqualTo(albums)
        coVerify(exactly = 0) { apiClient.getArtistAlbums(any(), any()) }
    }

    // (f) FLAGSHIP ─────────────────────────────────────────────────────────
    @Test
    fun `zero yt albums plus duplicate same-name rows proceeds MBV case`() = runTest {
        // Qobuz returns the artist's OWN entity twice (same name, different id).
        // These collapse to ONE distinct artist — must NOT be killed as ambiguous.
        coEvery { apiClient.searchArtists(any(), any()) } returns
            listOf(QbdlxArtistItem(1, "My Bloody Valentine"), QbdlxArtistItem(2, "My Bloody Valentine"))
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns
            listOf(qAlbum("Loveless", "1991"), qAlbum("m b v", "2013"))

        val out = provider().mergeInto("My Bloody Valentine", emptyList(), emptyList())

        assertThat(out.albums.map { it.title }).containsExactly("m b v", "Loveless").inOrder()
        assertThat(out.albums.map { it.source }).containsExactly(AlbumSource.QOBUZ, AlbumSource.QOBUZ)
        coVerify { apiClient.getArtistAlbums(1, any()) } // used the first/best real row
    }

    // (g) ────────────────────────────────────────────────────────────────
    @Test
    fun `superstring pseudo-artist is excluded so it can't fake ambiguity`() = runTest {
        // "my bloody valentine tribute" strictly contains the query + extra tokens.
        // It must be dropped from the candidate set, leaving ONE real artist → proceed.
        coEvery { apiClient.searchArtists(any(), any()) } returns
            listOf(
                QbdlxArtistItem(1, "My Bloody Valentine"),
                QbdlxArtistItem(2, "My Bloody Valentine Tribute"),
            )
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns
            listOf(qAlbum("Loveless", "1991"))

        val out = provider().mergeInto("My Bloody Valentine", emptyList(), emptyList())

        // Proceeded (not aborted): the pseudo did not create false ambiguity.
        assertThat(out.albums.map { it.title }).containsExactly("Loveless")
        assertThat(out.albums.single().source).isEqualTo(AlbumSource.QOBUZ)
        coVerify { apiClient.getArtistAlbums(1, any()) }
    }

    // (h) release dates for the artist page's Latest releases row ───────────

    private val nov3 = LocalDate.of(2026, 11, 3)

    /** A provider whose today is 2026-11-03 and whose calls take no time. */
    private fun datingProvider(elapsed: () -> Long = { 0L }) = QobuzDiscographyProvider(apiClient).apply {
        today = { nov3 }
        elapsedMs = elapsed
    }

    private fun qotsaYouTube() = listOf(
        AlbumSummary("yt_perfecth", "Perfecth", "", null, "2026"),
        AlbumSummary("yt_clockwork", "...Like Clockwork", "", null, "2013"),
    )

    private fun qotsa(title: String, date: String, tracksCount: Int = 10) =
        qAlbum(title, date, artist = "Queens Of The Stone Age", tracksCount = tracksCount)

    @Test
    fun `the artist's own releases from this year come dated to the day, singles included`() = runTest {
        coEvery { apiClient.searchArtists(any(), any()) } returns listOf(QbdlxArtistItem(7, "Queens Of The Stone Age"))
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns listOf(
            qotsa("...Like Clockwork", "2013-06-03"),
            qotsa("Perfecth", "2026-10-30", tracksCount = 9),
            qotsa("Run Away From the Mob", "2026-10-28", tracksCount = 1),
            qAlbum("No One Knows", "2026-08-14", artist = "Soundwall", tracksCount = 1), // a cover: not theirs
            qotsa("Alive in the Catacombs", "2025-06-13", tracksCount = 5), // last year
            qotsa("Undated", "2026", tracksCount = 1), // no day
        )

        val out = datingProvider().mergeInto("Queens Of The Stone Age", qotsaYouTube(), emptyList())

        assertThat(out.releaseDates).containsExactly(
            DatedRelease("Perfecth", "2026-10-30", 9),
            DatedRelease("Run Away From the Mob", "2026-10-28", 1),
        )
        // The first answer is enough: no call of their own.
        coVerify(exactly = 0) { apiClient.getNewestArtistAlbums(any(), any()) }
    }

    @Test
    fun `early in the year last year's releases can still be new`() = runTest {
        coEvery { apiClient.searchArtists(any(), any()) } returns listOf(QbdlxArtistItem(1, "My Bloody Valentine"))
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns listOf(
            qAlbum("Loveless", "1991-11-04"),
            qAlbum("December", "2025-12-12"),
            qAlbum("June", "2025-06-01"),
        )
        val p = datingProvider().apply { today = { LocalDate.of(2026, 1, 20) } }

        val out = p.mergeInto("My Bloody Valentine", listOf(ytAlbum("Loveless")), emptyList())

        // 2025 is still among the window's years in January, so both 2025 releases come along;
        // the artist page then keeps only what's within the window.
        assertThat(out.releaseDates.map { it.title }).containsExactly("December", "June")
    }

    @Test
    fun `a full popular list asks for the newest releases when YouTube shows one from this year`() = runTest {
        coEvery { apiClient.searchArtists(any(), any()) } returns listOf(QbdlxArtistItem(7, "Queens Of The Stone Age"))
        // A hundred releases, the most the first call asks for: the artist may have more.
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns
            listOf(qotsa("...Like Clockwork", "2013-06-03")) + List(99) { qotsa("Live $it", "2010-01-01") }
        coEvery { apiClient.getNewestArtistAlbums(7, any()) } returns listOf(
            qotsa("Perfecth", "2026-10-30", tracksCount = 9),
            qAlbum("Fades Away (2026 Remix)", "2026-10-23", artist = "Someone Else", tracksCount = 1),
        )

        val out = datingProvider().mergeInto("Queens Of The Stone Age", qotsaYouTube(), emptyList())

        assertThat(out.releaseDates).containsExactly(DatedRelease("Perfecth", "2026-10-30", 9))
        coVerify(exactly = 1) { apiClient.getNewestArtistAlbums(7, QobuzDiscographyProvider.NEWEST_LIMIT) }
        // The gap-fill still reads the popular list alone: the newest list adds no album.
        assertThat(out.albums.filter { it.source == AlbumSource.QOBUZ }.map { it.title }).doesNotContain("Perfecth")
    }

    @Test
    fun `no newest call when YouTube shows nothing from this year`() = runTest {
        coEvery { apiClient.searchArtists(any(), any()) } returns listOf(QbdlxArtistItem(7, "Radiohead"))
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns
            listOf(qAlbum("In Rainbows", "2007-10-10", artist = "Radiohead")) +
                List(99) { qAlbum("Live $it", "2010-01-01", artist = "Radiohead") }
        val youTube = listOf(
            AlbumSummary("yt_ir", "In Rainbows", "", null, "2007"),
            AlbumSummary("yt_hail", "Hail to the Thief (Live Recordings 2003-2009)", "", null, "2025"),
        )

        val out = datingProvider().mergeInto("Radiohead", youTube, emptyList())

        assertThat(out.releaseDates).isEmpty()
        coVerify(exactly = 0) { apiClient.getNewestArtistAlbums(any(), any()) }
    }

    @Test
    fun `no newest call when the popular list isn't full`() = runTest {
        coEvery { apiClient.searchArtists(any(), any()) } returns listOf(QbdlxArtistItem(7, "Queens Of The Stone Age"))
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns
            List(QbdlxApiClient.ARTIST_ALBUMS_LIMIT - 1) { qotsa("...Like Clockwork", "2013-06-03") }

        datingProvider().mergeInto("Queens Of The Stone Age", qotsaYouTube(), emptyList())

        coVerify(exactly = 0) { apiClient.getNewestArtistAlbums(any(), any()) }
    }

    @Test
    fun `a failed newest call keeps the merge and the first answer's dates`() = runTest {
        coEvery { apiClient.searchArtists(any(), any()) } returns listOf(QbdlxArtistItem(7, "Queens Of The Stone Age"))
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns listOf(
            qotsa("...Like Clockwork", "2013-06-03"),
            qotsa("Rated R", "2000-06-06"),
            qotsa("Easy Street", "2026-07-14", tracksCount = 1),
        ) + List(97) { qotsa("Live $it", "2010-01-01") }
        coEvery { apiClient.getNewestArtistAlbums(any(), any()) } throws QbdlxApiException(503, "busy")

        val out = datingProvider().mergeInto("Queens Of The Stone Age", qotsaYouTube(), emptyList())

        assertThat(out.releaseDates).containsExactly(DatedRelease("Easy Street", "2026-07-14", 1))
        assertThat(out.albums.map { it.title }).contains("Rated R") // the gap-fill stands
    }

    @Test
    fun `slow first calls skip the newest call so the merge stays in ArtistCache's bound`() = runTest {
        coEvery { apiClient.searchArtists(any(), any()) } returns listOf(QbdlxArtistItem(7, "Queens Of The Stone Age"))
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns
            List(QbdlxApiClient.ARTIST_ALBUMS_LIMIT) { qotsa("...Like Clockwork", "2013-06-03") }
        // Each look at the clock is 2.5 s on: the search and the first list took that long.
        var now = 0L
        val p = datingProvider(elapsed = { now.also { now += 2_500L } })

        val out = p.mergeInto("Queens Of The Stone Age", qotsaYouTube(), emptyList())

        coVerify(exactly = 0) { apiClient.getNewestArtistAlbums(any(), any()) }
        assertThat(out.albums.map { it.title }).contains("...Like Clockwork")
    }

    @Test
    fun `no dates when the artist match isn't corroborated`() = runTest {
        // A homonym: none of its albums is one YouTube lists, so nothing of it is taken, its dates neither.
        coEvery { apiClient.searchArtists(any(), any()) } returns listOf(QbdlxArtistItem(1, "My Bloody Valentine"))
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns listOf(qAlbum("Somebody Else's", "2026-10-30"))

        val out = datingProvider().mergeInto("My Bloody Valentine", listOf(ytAlbum("Loveless", "2026")), emptyList())

        assertThat(out.releaseDates).isEmpty()
    }
}
