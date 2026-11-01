package com.stash.core.data.home

import com.stash.core.common.matchesArtistCredits
import com.stash.core.common.primaryArtist
import com.stash.core.data.db.dao.TopArtistRow
import com.stash.data.ytmusic.model.AlbumSummary
import java.util.Locale

/** One of your artists as "New from your artists" ranks them: the main act's name and the time you listened to them. */
data class YourArtist(val name: String, val listenedMs: Long)

/** An artist's releases as their YouTube Music page lists them: each shelf newest first. */
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
     * Your top artists, the way Your listening groups them and Your record ranks them: credits grouped by main act
     * ("Drake, Future" counts for Drake, as the Library's Artists tab does), ranked by time listened (the record's
     * `topItems(plays, 'artist', 'time')`), then plays, then the one heard last. A main act its own credits don't
     * name, or a blank one, is left out, as Your listening's Top artists does: its page would be someone else's.
     */
    fun topArtists(rows: List<TopArtistRow>, limit: Int = ARTISTS): List<YourArtist> =
        rows.groupBy { artistKey(it.artist) }
            .mapNotNull { (_, group) ->
                val lead = group.maxWith(compareBy<TopArtistRow> { it.plays }.thenBy { it.lastPlayedAt })
                val name = lead.artist.primaryArtist().trim()
                if (name.isEmpty() || group.none { matchesArtistCredits(it.artist, "", name) }) return@mapNotNull null
                Triple(YourArtist(name, group.sumOf { it.listenedMs }), group.sumOf { it.plays }, group.maxOf { it.lastPlayedAt })
            }
            .sortedWith(
                compareByDescending<Triple<YourArtist, Int, Long>> { it.first.listenedMs }
                    .thenByDescending { it.second }
                    .thenByDescending { it.third },
            )
            .take(limit)
            .map { it.first }

    /**
     * The row: each artist's releases from [thisYear] and the year before, newest first. YouTube dates a release by
     * its year alone and its shelves lead with the newest but aren't strictly by year (a soundtrack can sit last),
     * so this sorts by year; within a year each page's own order stands, albums ahead of singles (an album usually
     * follows its lead singles), and the artists take turns in [pages]' order (your
     * most played first): every artist's newest, then every artist's second newest, and so on. A release on two
     * artists' pages (a collaboration) shows once. Empty when fewer than [MIN] are left.
     */
    fun pick(pages: List<ArtistReleases>, thisYear: Int, max: Int = MAX, perArtist: Int = PER_ARTIST): List<AlbumSummary> {
        data class Candidate(val album: AlbumSummary, val year: Int, val position: Int, val rank: Int)

        val candidates = pages.flatMapIndexed { rank, page ->
            val seen = HashSet<String>()
            (page.albums + page.singles)
                .mapNotNull { album -> releaseYear(album.year)?.let { album to it } }
                .filter { (album, year) -> year in (thisYear - 1)..thisYear && seen.add(album.id) }
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

    /** The folded main act of an artist credit: the key a name's YouTube page is remembered by. */
    fun artistKey(name: String): String = name.primaryArtist().trim().lowercase(Locale.ROOT)

    /** The year in YouTube's "2025", or null when there is none. */
    private fun releaseYear(year: String?): Int? = year?.let { YEAR.find(it) }?.value?.toIntOrNull()

    private val YEAR = Regex("""\b\d{4}\b""")
}
