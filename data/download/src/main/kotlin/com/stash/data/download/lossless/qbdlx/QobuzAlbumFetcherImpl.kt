package com.stash.data.download.lossless.qbdlx

import com.stash.core.data.discography.QobuzAlbumFetcher
import com.stash.data.ytmusic.model.AlbumDetail
import com.stash.data.ytmusic.model.TrackSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps a Qobuz album (via [QbdlxApiClient.getAlbum] + a live token) into the app's
 * existing [AlbumDetail] so the album screen can render + play it.
 *
 * Phase 1 leaves [AlbumDetail]/[TrackSummary] unchanged: Qobuz tracks carry
 * `videoId = ""` (the "no YouTube id" sentinel — resolution happens by
 * title/artist metadata via qbdlx, not by videoId). [AlbumDetail.moreByArtist]
 * is always empty (the merged-discography shelf is composed elsewhere).
 */
@Singleton
class QobuzAlbumFetcherImpl @Inject constructor(
    private val apiClient: QbdlxApiClient,
    private val credentialStore: QbdlxCredentialStore,
) : QobuzAlbumFetcher {
    override suspend fun getAlbum(qobuzAlbumId: String): AlbumDetail {
        val token = credentialStore.activeToken() ?: error("qbdlx: no live token")
        val r = apiClient.getAlbum(qobuzAlbumId, token)
        val artistName = r.artist?.name.orEmpty()
        val cover = r.image?.large ?: r.image?.small ?: r.image?.thumbnail
        return AlbumDetail(
            id = r.id,
            title = r.title,
            artist = artistName,
            artistId = null,
            thumbnailUrl = cover,
            year = r.release_date_original,
            tracks = r.tracks.items.map { t ->
                TrackSummary(
                    videoId = "",
                    title = t.title,
                    artist = t.performer?.name?.ifBlank { artistName } ?: artistName,
                    album = r.title,
                    durationSeconds = t.duration.toDouble(),
                    thumbnailUrl = cover,
                )
            },
            moreByArtist = emptyList(),
        )
    }

    override suspend fun getPlaylist(playlistId: String): AlbumDetail {
        val token = credentialStore.activeToken() ?: error("qbdlx: no live token")
        val p = apiClient.getPlaylist(playlistId, token)
        val cover = p.images300.firstOrNull()
        return AlbumDetail(
            id = playlistId,
            title = p.name,
            artist = p.owner?.name.orEmpty(),     // curator
            artistId = null,
            thumbnailUrl = cover,
            year = null,
            tracks = p.tracks.items.map { t ->
                TrackSummary(
                    videoId = "",
                    title = t.title,
                    artist = t.performer?.name.orEmpty(),
                    album = t.album?.title,
                    durationSeconds = t.duration.toDouble(),
                    thumbnailUrl = t.album?.image?.large ?: cover,
                )
            },
            moreByArtist = emptyList(),
        )
    }
}
