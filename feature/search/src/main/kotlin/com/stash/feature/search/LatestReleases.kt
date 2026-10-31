package com.stash.feature.search

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
 * The most tracks a single or an EP has by the stores' rule. Qobuz sends no release
 * type, and the merge files any release of 5+ tracks under Albums, so one of up to six
 * may be a single's bundle or CD single (The Weeknd's "Dancing In The Flames" and
 * Radiohead's "Creep" have five tracks each) as well as an EP or a short album.
 */
private const val SINGLE_OR_EP_MAX_TRACKS = 6

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
 * Empty, and the row hidden, when it would only repeat the shelf right below it: with no
 * dated album, Singles & EPs follows Popular directly, and ten albums without a single
 * are the Albums row's own first cards. Ten singles do show: the Albums row stands
 * between them and Popular, which is how a new single went unseen.
 */
internal fun latestReleases(albums: List<AlbumSummary>, singles: List<AlbumSummary>): List<LatestRelease> {
    // YouTube's releases by the album screen's title key, for telling a Qobuz copy from a
    // new release ([isYouTubeCopy]). Lazy: most artists have no Qobuz album.
    val youTubeByKey by lazy {
        (albums + singles)
            .filter { it.source == AlbumSource.YOUTUBE }
            .groupBy { albumTitleKey(it.title).key }
    }
    val seenIds = HashSet<String>()
    fun dated(shelf: List<AlbumSummary>, albumShelf: Boolean) = shelf.mapNotNull { album ->
        val year = releaseYear(album.year) ?: return@mapNotNull null
        // Of a release both catalogs list, YouTube's card stays, as in the merge itself.
        if (album.source != AlbumSource.YOUTUBE && isYouTubeCopy(album, youTubeByKey)) return@mapNotNull null
        // The same release on both shelves shows once, as its album-shelf card.
        if (!seenIds.add(album.id)) return@mapNotNull null
        LatestRelease(album, year, kindOf(album, albumShelf))
    }
    val fromAlbums = dated(albums, albumShelf = true)
    if (fromAlbums.isEmpty()) return emptyList()
    val fromSingles = dated(singles, albumShelf = false)
    // A stable sort: within a year the albums (listed first) stay ahead of the singles,
    // and each shelf keeps YouTube's order.
    val newest = (fromAlbums + fromSingles)
        .sortedByDescending { it.year }
        .take(LATEST_RELEASES_MAX)
    val singleIds = fromSingles.mapTo(HashSet()) { it.album.id }
    return if (newest.any { it.album.id in singleIds }) newest else emptyList()
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

private val YEAR = Regex("""\b\d{4}\b""")
