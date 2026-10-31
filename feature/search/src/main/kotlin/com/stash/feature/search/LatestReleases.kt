package com.stash.feature.search

import com.stash.data.download.lossless.qobuz.QobuzCandidateMatcher
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary

/** What a card in the artist's Latest releases row is, for its "2021 • Single" line. */
enum class ReleaseKind { ALBUM, SINGLE, EP, MIXTAPE, COMPILATION }

/**
 * One card in the artist page's Latest releases row: the release exactly as the page's
 * shelves hold it (a tap opens it the way the Albums and Singles rows do), the year it
 * came out, and its kind, null when the source doesn't say.
 */
data class LatestRelease(
    val album: AlbumSummary,
    val year: Int,
    val kind: ReleaseKind?,
)

/** The newest few, not a second discography: the full shelves sit just below. */
internal const val LATEST_RELEASES_MAX = 10

/**
 * The artist's newest releases, albums and singles/EPs together, newest first: the row
 * under Popular. Built from the two shelves the page already has, so it costs no request.
 *
 * The date is the year. YouTube's cards carry nothing finer ("Single • 2021"); the Qobuz
 * albums merged into the Albums shelf carry a full date ("2017-06-23"), and its year is
 * taken so the two compare alike. Within a year YouTube's own order stands (each shelf
 * lists newest first), that year's albums ahead of its singles: an album usually follows
 * its lead singles. A release with no year can't be placed, so it is left out.
 *
 * Empty, and the row hidden, unless both shelves have a dated release: with one shelf the
 * row would only repeat it just below, and with none there is nothing to put in order.
 */
internal fun latestReleases(albums: List<AlbumSummary>, singles: List<AlbumSummary>): List<LatestRelease> {
    // Qobuz files any release of 5+ tracks as an album, so the merge can add one YouTube
    // lists under Singles & EPs; side by side here it would read as a duplicate card.
    // YouTube's copy stays, as in the merge itself. Lazy: most artists have no Qobuz album.
    val youTubeTitles by lazy {
        (albums + singles)
            .filter { it.source == AlbumSource.YOUTUBE }
            .map { titleKey(it.title) }
            .filterTo(HashSet()) { it.isNotEmpty() }
    }
    val seenIds = HashSet<String>()
    fun dated(shelf: List<AlbumSummary>, albumShelf: Boolean) = shelf.mapNotNull { album ->
        val year = releaseYear(album.year) ?: return@mapNotNull null
        if (album.source != AlbumSource.YOUTUBE && titleKey(album.title) in youTubeTitles) return@mapNotNull null
        // The same release on both shelves shows once, as its album-shelf card.
        if (!seenIds.add(album.id)) return@mapNotNull null
        LatestRelease(album, year, kindOf(album, albumShelf))
    }
    val fromAlbums = dated(albums, albumShelf = true)
    val fromSingles = dated(singles, albumShelf = false)
    if (fromAlbums.isEmpty() || fromSingles.isEmpty()) return emptyList()
    // A stable sort: within a year the albums (listed first) stay ahead of the singles,
    // and each shelf keeps YouTube's order.
    return (fromAlbums + fromSingles)
        .sortedByDescending { it.year }
        .take(LATEST_RELEASES_MAX)
}

/** YouTube's label when the card had one; else the Albums shelf's own heading says it. */
private fun kindOf(album: AlbumSummary, albumShelf: Boolean): ReleaseKind? =
    when (album.releaseType?.lowercase()) {
        "album" -> ReleaseKind.ALBUM
        "single" -> ReleaseKind.SINGLE
        "ep" -> ReleaseKind.EP
        "mixtape" -> ReleaseKind.MIXTAPE
        "compilation" -> ReleaseKind.COMPILATION
        // Unlabelled is the usual case on the Albums shelf: YouTube's Albums carousel gives
        // the year alone (only its View all grid says "Album"), and the Qobuz albums merged
        // in are album-length by rule. YouTube labels every Singles & EPs card, so one
        // without (a profile cached before the label was kept) says neither.
        else -> if (albumShelf) ReleaseKind.ALBUM else null
    }

/** The year in YouTube's "2021" or Qobuz's "2017-06-23"; null when there is none. */
private fun releaseYear(date: String?): Int? =
    date?.let { YEAR.find(it) }?.value?.toInt()?.takeIf { it > 0 }

/** The key the Qobuz merge compares titles by ("" when nothing of the title is left). */
private fun titleKey(title: String): String = QobuzCandidateMatcher.normalize(title)

private val YEAR = Regex("""\b\d{4}\b""")
