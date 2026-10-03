package com.stash.data.download.lossless.qbdlx

import com.google.common.truth.Truth.assertThat
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

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
}
