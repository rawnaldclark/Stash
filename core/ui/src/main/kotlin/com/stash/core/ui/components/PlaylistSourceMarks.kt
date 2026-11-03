package com.stash.core.ui.components

import com.stash.core.model.MusicSource
import com.stash.core.model.Track

/**
 * The service dot a playlist's card shows ([SourceIndicator]): Library's Playlists grid, Home's
 * rails and the Your mixes grid. Null for a list Stash makes (owner, 2026-11-02, on Daily Discover's
 * "Spotify • YouTube": "It's not sourced from either of those places. Let's get rid of it.").
 *
 * [MusicSource.BOTH] on a playlist is how Stash files its own lists, never "from Spotify and
 * YouTube": its mixes (Daily Discover, Recommended by Last.fm, the listener's own), playlists made
 * here, Community follows and copies, web and Your record imports, mirrored playlists and Your
 * Downloads. Those show no dot. The stored BOTH stays as it is: downloads, the orphan sweep, the
 * save-to-playlist picker and sync all read it.
 *
 * A list that does come from a service keeps its dot:
 *  - a synced Spotify, YouTube Music or SoundCloud playlist or mix: its own [source];
 *  - an album saved in Stash from a service, kept as BOTH under the service's key: a pasted Spotify
 *    album link (`album:spotify:…`), a pasted YouTube Music album link or that album's page Save
 *    (both `album:youtube:…`). A Qobuz album or playlist has no dot of its own, so it shows none.
 *
 * A link import (`custom_link_…`) is described by its songs on its page ([playlistSourceMarks]); its
 * card has no songs to read, so it shows none, as Library's grid always has.
 */
fun playlistSourceMark(source: MusicSource, sourceId: String): MusicSource? = when {
    sourceId.startsWith(LINK_IMPORT_PREFIX) -> null
    source != MusicSource.BOTH -> source
    sourceId.startsWith(SPOTIFY_ALBUM_PREFIX, ignoreCase = true) -> MusicSource.SPOTIFY
    sourceId.startsWith(YOUTUBE_ALBUM_PREFIX, ignoreCase = true) -> MusicSource.YOUTUBE
    else -> null
}

/**
 * The service dots a playlist's page shows under its name, in order, or none for a list Stash makes
 * ([playlistSourceMark]). A link import (`custom_link_…`) names the services its [tracks] really
 * come from (a SoundCloud song, a song from both, or the song's own source), since the playlist
 * itself is kept as the listener's own.
 */
fun playlistSourceMarks(source: MusicSource, sourceId: String, tracks: List<Track>): List<MusicSource> =
    if (sourceId.startsWith(LINK_IMPORT_PREFIX)) {
        tracks.flatMap { track ->
            when {
                track.soundCloudId != null -> listOf(MusicSource.SOUNDCLOUD)
                track.source == MusicSource.BOTH -> listOf(MusicSource.SPOTIFY, MusicSource.YOUTUBE)
                else -> listOf(track.source)
            }
        }.distinct()
    } else {
        listOfNotNull(playlistSourceMark(source, sourceId))
    }

/** A playlist from a pasted link or a playlist file (core:data's `LinkImporter.PLAYLIST_PREFIX`). */
private const val LINK_IMPORT_PREFIX = "custom_link_"

/** The keys core:data's `SavedAlbums.sourceId` files a Spotify and a YouTube Music album under. */
private const val SPOTIFY_ALBUM_PREFIX = "album:spotify:"
private const val YOUTUBE_ALBUM_PREFIX = "album:youtube:"
