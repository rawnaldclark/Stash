package com.stash.core.data.discography
import com.stash.data.ytmusic.model.AlbumDetail

/** Loads a Qobuz album's or playlist's tracklist. Impl in data:download. Throws
 *  on failure (AlbumCache surfaces it exactly like a YT album load failure). */
interface QobuzAlbumFetcher {
    /** Throws [QobuzAlbumUnavailableException] when Qobuz has no such album for this caller. */
    suspend fun getAlbum(qobuzAlbumId: String): AlbumDetail

    /** Loads a Qobuz playlist as an [AlbumDetail] (title = name, artist = curator). */
    suspend fun getPlaylist(playlistId: String): AlbumDetail
}

/**
 * #481: Qobuz has no such album for this caller — album/get answered 404 "No
 * result matching given argument". Qobuz picks its store from the caller's IP,
 * and in a country it doesn't sell in (Ecuador, India...) every album 404s, even
 * the ones Home's featured rows list. A retry can't fix that, so it gets its own
 * type: the album screen looks for the album elsewhere instead.
 */
class QobuzAlbumUnavailableException(val albumId: String, cause: Throwable? = null) :
    RuntimeException("Qobuz has no album $albumId for this region", cause)
