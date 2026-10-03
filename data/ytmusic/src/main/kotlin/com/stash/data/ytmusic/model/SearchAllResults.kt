package com.stash.data.ytmusic.model

import kotlinx.serialization.Serializable

/**
 * One section shown in the sectioned Search results.
 *
 * The Search tab renders up to four sections — Top, Songs, Artists, Albums —
 * in that fixed order. Each section is a discriminated case of this sealed
 * interface so the UI can pattern-match on the kind and render the
 * appropriate composable (tall card, vertical list, horizontal row, etc.).
 */
sealed interface SearchResultSection {
    /** Single tall "Top result" card (either artist or track). */
    data class Top(val item: TopResultItem) : SearchResultSection

    /** Up to 4 inline song rows. */
    data class Songs(val tracks: List<TrackSummary>) : SearchResultSection

    /** Horizontal row of artist avatar cards. */
    data class Artists(val artists: List<ArtistSummary>) : SearchResultSection

    /** Horizontal row of album square cards. */
    data class Albums(val albums: List<AlbumSummary>) : SearchResultSection
}

/**
 * Discriminator for the tall "Top result" card at the top of a search page.
 *
 * InnerTube decides whether the top result is an artist (when the query
 * matches an artist name) or a track (when the query matches a specific
 * song). Both cases are surfaced here so the UI can render either variant.
 */
sealed interface TopResultItem {
    data class ArtistTop(val artist: ArtistSummary) : TopResultItem
    data class TrackTop(val track: TrackSummary) : TopResultItem
}

/**
 * Full result of a Search tab query — an ordered list of sections.
 *
 * An empty [sections] list signals "no results"; the UI should render an
 * appropriate empty-state rather than a blank list.
 *
 * [topAlbum] is YouTube's top-result card when that card is an album. Search's
 * top slot shows artists and tracks only, so it rides alongside the sections
 * for [com.stash.data.ytmusic.YTMusicApiClient.resolveAlbum]: for a new release
 * the card is often the only place a search names the album (#481).
 */
data class SearchAllResults(
    val sections: List<SearchResultSection>,
    val topAlbum: AlbumSummary? = null,
)

/** Minimal artist identity for cards, top-result, and related-artists rows. */
@Serializable
data class ArtistSummary(
    val id: String,
    val name: String,
    val avatarUrl: String?,
)

/** Which catalog an album entry came from. Drives AlbumCache routing + play path. */
@Serializable
enum class AlbumSource { YOUTUBE, QOBUZ, QOBUZ_PLAYLIST }

/**
 * Minimal album identity for horizontal album rows and discography grids.
 *
 * [releaseType] is YouTube's label from a search result ("Album", "Single",
 * "EP"...), null where the source doesn't say. #481: a single that shares an
 * album's title is a different release. [trackCount] is how many tracks the
 * release has where the source lists it (Qobuz's rows do, and send no release
 * type, so it is how a Qobuz single is told from an album); null otherwise.
 */
@Serializable
data class AlbumSummary(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val year: String?,
    val source: AlbumSource = AlbumSource.YOUTUBE,
    val releaseType: String? = null,
    val trackCount: Int? = null,
)

/** Minimal playlist identity for the Home discovery playlist row. */
@Serializable
data class PlaylistSummary(
    val id: String,
    val title: String,
    val curator: String,
    val thumbnailUrl: String?,
    val trackCount: Int,
)

/**
 * Minimal track identity for search song rows and artist "Popular" lists.
 *
 * [durationSeconds] is a [Double] to accept fractional values from some
 * InnerTube responses; UI code should round for display.
 */
@Serializable
data class TrackSummary(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSeconds: Double,
    val thumbnailUrl: String?,
)

/**
 * Full Artist Profile — populated from a single InnerTube `browse` call.
 *
 * Shelves that don't exist on a given artist page (e.g. a brand-new artist
 * with no albums) surface as empty lists rather than nulls; the UI hides
 * empty rows.
 */
@Serializable
data class ArtistProfile(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val subscribersText: String?,
    val popular: List<TrackSummary>,
    val albums: List<AlbumSummary>,
    val singles: List<AlbumSummary>,
    val related: List<ArtistSummary>,
    val about: ArtistAbout? = null,
)
