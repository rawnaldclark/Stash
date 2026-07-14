package com.stash.core.data.discography
import com.stash.data.ytmusic.model.AlbumDetail

/** Loads a Qobuz album's or playlist's tracklist. Impl in data:download. Throws
 *  on failure (AlbumCache surfaces it exactly like a YT album load failure). */
interface QobuzAlbumFetcher {
    suspend fun getAlbum(qobuzAlbumId: String): AlbumDetail

    /** Loads a Qobuz playlist as an [AlbumDetail] (title = name, artist = curator). */
    suspend fun getPlaylist(playlistId: String): AlbumDetail
}
