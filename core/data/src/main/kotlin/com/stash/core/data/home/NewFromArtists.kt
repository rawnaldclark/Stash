package com.stash.core.data.home

import com.stash.core.common.foldCase
import com.stash.core.data.db.dao.TopArtistRow
import com.stash.core.data.listening.MainArtists
import com.stash.data.ytmusic.model.AlbumSummary
import kotlinx.serialization.Serializable

/** One of your artists as "New from your artists" ranks them: the main act's name and the time you listened to them. */
data class YourArtist(val name: String, val listenedMs: Long)

/** An artist's releases as their YouTube Music page lists them: each shelf newest first. */
@Serializable
data class ArtistReleases(val artist: String, val albums: List<AlbumSummary>, val singles: List<AlbumSummary>)

/**
 * Home's "New from your artists": this year's and last year's albums and singles from the artists you listen to
 * most, newest first. A Release Radar for people without Spotify, from calls Stash already makes (an artist's
 * page, whose Albums and Singles & EPs carousels each lead with the newest). The idea is Mochy Music's
 * `NewFromArtists` row (github.com/realidkroo/Mochy-Music, GPL-3.0); this is written against Stash's own history
 * and client, not ported.
 */
object NewFromArtists {
    /** Artists whose pages are read: Mochy reads 8; ten finds a full row more often, at ten requests a day. */
    const val ARTISTS = 10

    /** At most this many releases from one artist, so one prolific act can't fill the row. */
    const val PER_ARTIST = 4

    /** The row's length. */
    const val MAX = 20

    /** Fewer than this and the row shows nothing (Mochy drops it under 3): two cards read as a broken row. */
    const val MIN = 3

    /**
     * Your top artists, grouped by main act exactly as Your listening's Top artists groups them
     * ([MainArtists.byMainArtist]: "Drake, Future" counts for Drake, a main act its credits don't name is left out),
     * ranked as Your record ranks them: by time listened (`topItems(plays, 'artist', 'time')`), then plays, then the
     * one heard last.
     */
    fun topArtists(rows: List<TopArtistRow>, limit: Int = ARTISTS): List<YourArtist> =
        MainArtists.byMainArtist(rows)
            .sortedWith(
                compareByDescending<TopArtistRow> { it.listenedMs }
                    .thenByDescending { it.plays }
                    .thenByDescending { it.lastPlayedAt },
            )
            .take(limit)
            .map { YourArtist(it.artist, it.listenedMs) }

    /**
     * The row: each artist's releases from [thisYear] and the year before, newest first. YouTube dates a release by
     * its year alone and its shelves lead with the newest but aren't strictly by year (a soundtrack can sit last),
     * so this sorts by year; within a year each page's own order stands, albums ahead of singles (an album usually
     * follows its lead singles), and the artists take turns in [pages]' order (your
     * most played first): every artist's newest, then every artist's second newest, and so on. A release on two
     * artists' pages (a collaboration) shows once, and so does an album and a single of the same title and year
     * (the album, as [com.stash.data.ytmusic.YTMusicApiClient.resolveAlbum] prefers it: a title track released ahead
     * of its album). Empty when fewer than [MIN] are left.
     */
    fun pick(pages: List<ArtistReleases>, thisYear: Int, max: Int = MAX, perArtist: Int = PER_ARTIST): List<AlbumSummary> {
        data class Candidate(val album: AlbumSummary, val year: Int, val position: Int, val rank: Int)

        val candidates = pages.flatMapIndexed { rank, page ->
            val seen = HashSet<String>()
            // Albums are listed first, so an album and its title single of the same year keep the album
            val titles = HashSet<String>()
            (page.albums + page.singles)
                .mapNotNull { album -> releaseYear(album.year)?.let { album to it } }
                .filter { (album, year) ->
                    year in (thisYear - 1)..thisYear && seen.add(album.id) && titles.add(foldCase(album.title.trim()) + "|" + year)
                }
                // Stable: within a year the page's order stands, albums (listed first) ahead of singles.
                .sortedByDescending { it.second }
                .take(perArtist)
                .mapIndexed { position, (album, year) ->
                    Candidate(album.copy(artist = album.artist.ifBlank { page.artist }), year, position, rank)
                }
        }
        val seen = HashSet<String>()
        val row = candidates
            .sortedWith(compareByDescending<Candidate> { it.year }.thenBy { it.position }.thenBy { it.rank })
            .filter { seen.add(it.album.id) }
            .take(max)
            .map { it.album }
        return if (row.size < MIN) emptyList() else row
    }

    /** [releases] still within [thisYear] and the year before: a row saved last December keeps only what still is. */
    fun stillNew(releases: List<AlbumSummary>, thisYear: Int): List<AlbumSummary> {
        val kept = releases.filter { album -> releaseYear(album.year)?.let { it >= thisYear - 1 } == true }
        return if (kept.size < MIN) emptyList() else kept
    }

    /** The folded main act of an artist credit ([MainArtists.key]): the key a name's YouTube page is remembered by. */
    fun artistKey(name: String): String = MainArtists.key(name)

    /** [page]'s releases from [thisYear] and the year before: what's saved of an artist, for a day their page fails. */
    fun recent(page: ArtistReleases, thisYear: Int): ArtistReleases {
        fun List<AlbumSummary>.recent() = filter { album -> releaseYear(album.year)?.let { it >= thisYear - 1 } == true }
        return page.copy(albums = page.albums.recent(), singles = page.singles.recent())
    }

    /** The year in YouTube's "2025", or null when there is none. */
    private fun releaseYear(year: String?): Int? = year?.let { YEAR.find(it) }?.value?.toIntOrNull()

    private val YEAR = Regex("""\b\d{4}\b""")
}
