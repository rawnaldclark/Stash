package com.stash.feature.search

import com.stash.core.data.discography.NEW_RELEASE_DAYS
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.DatedRelease
import java.time.LocalDate

/** What a card in the artist's Latest releases row is, for its "2021 • Single" line. */
enum class ReleaseKind { ALBUM, SINGLE, EP, MIXTAPE, COMPILATION }

/**
 * One card in the artist page's Latest releases row: the release exactly as the page's
 * shelves hold it (a tap opens it the way the Albums and Singles rows do), the day it
 * came out, and its kind, null when the source doesn't say.
 */
data class LatestRelease(
    val album: AlbumSummary,
    val released: LocalDate,
    val kind: ReleaseKind?,
) {
    /** The year on the card ("2026 • Single"). */
    val year: Int get() = released.year
}

/** A long row of new releases is still a row, not a second discography. */
internal const val LATEST_RELEASES_MAX = 10

/**
 * The most tracks a single or an EP has by the stores' rule. Qobuz sends no release
 * type, and the merge files any release of 5+ tracks under Albums, so one of up to six
 * may be a single's bundle or CD single (The Weeknd's "Dancing In The Flames" and
 * Radiohead's "Creep" have five tracks each) as well as an EP or a short album.
 */
private const val SINGLE_OR_EP_MAX_TRACKS = 6

/** Up to three tracks Qobuz's release is a single, as on the album screen ([isSameAlbum]): never an album's date. */
private const val SINGLE_MAX_TRACKS = 3

/**
 * The artist's new releases, albums and singles/EPs together, newest first: the row under
 * Popular, there only when the artist has one. New is out within the last [days] days
 * ([NEW_RELEASE_DAYS]), today included; one dated later (a pre-order) isn't out yet.
 *
 * That takes the day, and YouTube's cards give the year alone ("Single • 2026"): a January
 * album isn't new in November. The day comes from Qobuz. A Qobuz album the merge added to
 * Albums carries its own ("2026-10-30"); a YouTube card takes the date of the artist's
 * Qobuz release ([releaseDates]) of the same title ([albumTitleKey]), the same year and its
 * length ([fitsLength]); of several, the earliest, so a later edition doesn't make a release
 * new. A release no catalog dates is never shown as new: no guessing from the year.
 *
 * Built from what the page already has, so it costs no request. Of a release both
 * catalogs list, YouTube's card stays, as in the merge itself, and one on both shelves
 * shows once, the album shelf's when both are new. On the same day the albums come first,
 * and each shelf keeps YouTube's order.
 */
internal fun latestReleases(
    albums: List<AlbumSummary>,
    singles: List<AlbumSummary>,
    releaseDates: List<DatedRelease>,
    today: LocalDate,
    days: Long = NEW_RELEASE_DAYS,
): List<LatestRelease> {
    val since = today.minusDays(days)
    // YouTube's releases by the album screen's title key, for telling a Qobuz copy from a
    // new release ([isYouTubeCopy]). Lazy: most artists have no Qobuz album.
    val youTubeByKey by lazy {
        (albums + singles)
            .filter { it.source == AlbumSource.YOUTUBE }
            .groupBy { albumTitleKey(it.title).key }
    }
    // Qobuz's dates by the same key, each parsed once.
    val datedByKey by lazy {
        releaseDates
            .mapNotNull { release -> isoDate(release.date)?.let { Dated(release.trackCount, it) to release.title } }
            .groupBy(keySelector = { albumTitleKey(it.second).key }, valueTransform = { it.first })
    }
    val seenIds = HashSet<String>()
    fun newOnes(shelf: List<AlbumSummary>, albumShelf: Boolean) = shelf.mapNotNull { album ->
        // Of a release both catalogs list, YouTube's card stays, as in the merge itself.
        if (album.source != AlbumSource.YOUTUBE && isYouTubeCopy(album, youTubeByKey)) return@mapNotNull null
        val released = releaseDate(album, albumShelf, datedByKey) ?: return@mapNotNull null
        if (released.isAfter(today) || released.isBefore(since)) return@mapNotNull null
        // The same release on both shelves shows once, as its album-shelf card.
        if (!seenIds.add(album.id)) return@mapNotNull null
        LatestRelease(album, released, kindOf(album, albumShelf))
    }
    // A stable sort: on the same day the albums (listed first) stay ahead of the singles,
    // and each shelf keeps YouTube's order.
    return (newOnes(albums, albumShelf = true) + newOnes(singles, albumShelf = false))
        .sortedByDescending { it.released }
        .take(LATEST_RELEASES_MAX)
}

/** A Qobuz release's day and length, as [latestReleases] matches a YouTube card to it. */
private class Dated(val trackCount: Int?, val date: LocalDate)

/**
 * The day [album] came out; null when no catalog says. A Qobuz album carries its own. A
 * YouTube card takes the earliest of the Qobuz releases of its title key, its year (exactly:
 * a year apart may be another release) and its length.
 */
private fun releaseDate(album: AlbumSummary, albumShelf: Boolean, datedByKey: Map<String, List<Dated>>): LocalDate? {
    if (album.source != AlbumSource.YOUTUBE) return isoDate(album.year)
    val year = releaseYear(album.year) ?: return null
    val key = albumTitleKey(album.title).key
    if (key.isEmpty()) return null
    val short = isSingleOrEp(album, albumShelf)
    return datedByKey[key].orEmpty()
        .filter { it.date.year == year && fitsLength(it.trackCount, short) }
        .minOfOrNull { it.date }
}

/** YouTube's Single or EP, or an unlabelled card on the Singles & EPs shelf. */
private fun isSingleOrEp(album: AlbumSummary, albumShelf: Boolean): Boolean =
    when (album.releaseType?.lowercase()) {
        "single", "ep" -> true
        null -> !albumShelf
        else -> false
    }

/**
 * Whether a Qobuz release of [trackCount] tracks can be the YouTube card: a single or an EP
 * is up to six tracks, an album more than three. So an album and the single of its name, out
 * months apart, each keep their own day. An unknown length can be either.
 */
private fun fitsLength(trackCount: Int?, singleOrEp: Boolean): Boolean = when {
    trackCount == null -> true
    singleOrEp -> trackCount <= SINGLE_OR_EP_MAX_TRACKS
    else -> trackCount > SINGLE_MAX_TRACKS
}

/**
 * Whether [qobuz], a release the Qobuz merge added to Albums, is one YouTube lists too:
 * #481's test ([isSameAlbum]) less the artist, which the page settles. It takes the same
 * title key, so an edition ("(Deluxe)") is the same release but "(The Album)" or "(Live)"
 * another; a year within one of YouTube's ([yearsAgree]) unless an edition was set aside;
 * and YouTube's "Single" only for a release of single or EP length, since named like a
 * longer release, a single is its title track. So Holy drill's "In Christ We Trust (The
 * Album)", an album named like its lead single, and an album out years after an EP of
 * its name all stay: each can be the artist's newest release.
 */
private fun isYouTubeCopy(qobuz: AlbumSummary, youTubeByKey: Map<String, List<AlbumSummary>>): Boolean {
    val key = albumTitleKey(qobuz.title)
    if (key.key.isEmpty()) return false
    return youTubeByKey[key.key].orEmpty().any { youTube ->
        (!youTube.releaseType.equals("Single", ignoreCase = true) || isSingleOrEpLength(qobuz.trackCount)) &&
            (key.dropsEdition || albumTitleKey(youTube.title).dropsEdition || yearsAgree(youTube.year, qobuz.year))
    }
}

/** Up to six tracks. An unknown length isn't, as in #481: a single of the name stays its title track. */
private fun isSingleOrEpLength(trackCount: Int?): Boolean =
    trackCount != null && trackCount in 1..SINGLE_OR_EP_MAX_TRACKS

/**
 * YouTube's label when the card had one. Unlabelled is the usual case on the Albums
 * shelf, where YouTube's carousel gives the year alone (only its View all grid says
 * "Album"). A Qobuz release says Album only at album length: up to six tracks it may as
 * well be a single's CD or an EP. YouTube labels every Singles & EPs card, so one without
 * (a profile cached before the label was kept) says neither. Unsaid, the card shows the
 * year alone.
 */
private fun kindOf(album: AlbumSummary, albumShelf: Boolean): ReleaseKind? =
    when (album.releaseType?.lowercase()) {
        "album" -> ReleaseKind.ALBUM
        "single" -> ReleaseKind.SINGLE
        "ep" -> ReleaseKind.EP
        "mixtape" -> ReleaseKind.MIXTAPE
        "compilation" -> ReleaseKind.COMPILATION
        else -> when {
            !albumShelf -> null
            album.source == AlbumSource.YOUTUBE -> ReleaseKind.ALBUM
            (album.trackCount ?: 0) > SINGLE_OR_EP_MAX_TRACKS -> ReleaseKind.ALBUM
            else -> null
        }
    }

/** The year in YouTube's "2021" or Qobuz's "2017-06-23"; null when there is none. */
private fun releaseYear(date: String?): Int? =
    date?.let { YEAR.find(it) }?.value?.toIntOrNull()?.takeIf { it > 0 }

/** The day in Qobuz's "2017-06-23"; null for a year alone ("2017", YouTube's) or anything else. */
private fun isoDate(date: String?): LocalDate? =
    date?.trim()?.takeIf { ISO_DATE.matches(it) }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

private val YEAR = Regex("""\b\d{4}\b""")
private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
