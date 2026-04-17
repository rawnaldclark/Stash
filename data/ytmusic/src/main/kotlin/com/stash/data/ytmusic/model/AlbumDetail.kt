package com.stash.data.ytmusic.model

import kotlinx.serialization.Serializable

/**
 * Full album detail fetched from InnerTube `browse(albumBrowseId)`.
 *
 * Returned in a single round-trip by [com.stash.data.ytmusic.YTMusicApiClient.getAlbum]
 * and parsed by [com.stash.data.ytmusic.AlbumResponseParser]. Renders the
 * Album Discovery screen: hero (title/artist/year/cover), full tracklist, and
 * the "More by this artist" shelf at the bottom.
 *
 * @property id The album browseId (e.g. `MPREb_…`) — matches the value the caller
 *   passed to `getAlbum`.
 * @property title Album title, or "Unknown album" when the header was malformed.
 * @property artist Primary artist display name, or "Unknown artist" when the
 *   subtitle was missing an artist run.
 * @property artistId Channel browseId (`UC…`) of the primary artist, or null if
 *   the subtitle had no linked artist (rare — usually only for various-artists
 *   compilations).
 * @property thumbnailUrl Square cover art at the largest size the fixture ships;
 *   null when the header is missing its thumbnail block.
 * @property year Release year as a 4-digit string, or null when the subtitle
 *   didn't include a year token (e.g. singles, compilations).
 * @property tracks Full tracklist in InnerTube order. Empty when the
 *   `musicShelfRenderer` is missing (region-block or similar — the parser
 *   returns empty rather than throws).
 * @property moreByArtist "More by this artist" shelf items; may be empty for
 *   compilations or for artists with a single release on YouTube Music.
 */
@Serializable
data class AlbumDetail(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val thumbnailUrl: String?,
    val year: String?,
    val tracks: List<TrackSummary>,
    val moreByArtist: List<AlbumSummary>,
)
