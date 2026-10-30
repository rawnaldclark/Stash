package com.stash.core.data.link

import android.util.Log
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.weblibrary.ImportSelection
import com.stash.core.data.weblibrary.ImportedPlaylist
import com.stash.core.data.weblibrary.WebLibraryContent
import com.stash.core.data.weblibrary.WebLibraryFile
import com.stash.core.data.weblibrary.WebLibraryImporter
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.model.SyncResult
import com.stash.data.spotify.SpotifyApiClient
import com.stash.data.spotify.SpotifyEmbedClient
import com.stash.data.spotify.SpotifyEmbedEntity
import com.stash.data.spotify.model.SpotifyTrackItem
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.TrackSummary
import com.stash.data.ytmusic.model.YTMusicTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** What a link brings in. */
enum class LinkKind { PLAYLIST, ALBUM, SONG }

/** A pasted link, read: what it is, its songs, and the playlist it becomes ([sourceId]). */
data class LinkPreview(
    val kind: LinkKind,
    val name: String,
    /** Who made the playlist, the album's artist, the song's artist. */
    val byline: String?,
    val coverUrl: String?,
    /** "Spotify" or "YouTube Music". */
    val service: String,
    val songs: List<WireSong>,
    /** The playlist it becomes (an album's is the one saving it from its album page makes, so the page shows it saved). */
    val sourceId: String,
    /** Only the first songs could be read: a long Spotify playlist without a Spotify login (its embed page lists 100). */
    val partial: Boolean = false,
    /** A song link opened from a playlist or album: that list, to offer instead. */
    val fromList: MusicLink? = null,
)

sealed interface LinkLoad {
    data class Ok(val preview: LinkPreview) : LinkLoad
    data class Failed(val message: String) : LinkLoad
}

/** What adding a link did: the playlist it is in, and the songs it gained. */
data class LinkAdded(val playlistId: Long, val songsAdded: Int)

/**
 * Paste links (release plan item: "Paste playlist links Spotify or YouTube. Playlists, albums, or tracks to add to library and
 * download"): reads a [MusicLink] into a [LinkPreview], then brings it into the library once, as it is now (a snapshot; a
 * playlist pasted again gains the songs it lacks). Songs go through the web library's importer ([WebLibraryImporter]: matched
 * by identity to a song already here, else a new stream-only row; songs this phone blocked are left out; nothing is removed).
 *
 * - A playlist becomes a playlist of the listener's own (`custom_link_spotify_<id>`, `custom_link_yt_<id>`).
 * - An album becomes the playlist its album page's Save makes (`album:youtube:<MPREb_…>`), or `album:spotify:<id>`.
 * - A song goes into one playlist of songs from links ([SONGS_SOURCE_ID]).
 *
 * Nothing needs a login: Spotify's embed pages and YouTube Music's own pages are read anonymously. A Spotify playlist
 * longer than its embed page's 100 songs comes whole only through [SpotifyApiClient.getPlaylistTracks] (a Spotify login, or
 * the Web API when it isn't rate-limited); otherwise its first 100, and the preview says so.
 */
@Singleton
class LinkImporter @Inject constructor(
    private val spotifyEmbed: SpotifyEmbedClient,
    private val spotifyApi: SpotifyApiClient,
    private val youtube: YTMusicApiClient,
    private val importer: WebLibraryImporter,
    private val playlists: PlaylistDao,
    private val musicRepository: MusicRepository,
) {

    /** Reads [link]; never throws but a cancellation. */
    suspend fun load(link: MusicLink): LinkLoad = try {
        when (link) {
            is MusicLink.SpotifyShort -> {
                val target = spotifyEmbed.resolveShortLink(link.url)?.let(MusicLinks::parse)
                if (target == null || target is MusicLink.SpotifyShort) LinkLoad.Failed(NOT_MUSIC) else load(target)
            }
            is MusicLink.SpotifyPlaylist -> spotifyPlaylist(link.id)
            is MusicLink.SpotifyAlbum -> spotifyEmbed.get("album", link.id)?.let { e ->
                ok(LinkPreview(LinkKind.ALBUM, e.name, e.subtitle, e.coverUrl, SPOTIFY, e.songs(), "album:spotify:${link.id}"))
            } ?: LinkLoad.Failed(CANT_OPEN)
            is MusicLink.SpotifyTrack -> spotifyEmbed.get("track", link.id)?.let { e ->
                ok(LinkPreview(LinkKind.SONG, e.name, e.tracks.firstOrNull()?.artist, e.coverUrl, SPOTIFY, e.songs(cover = e.coverUrl), SONGS_SOURCE_ID))
            } ?: LinkLoad.Failed(CANT_OPEN)
            is MusicLink.YouTubePlaylist -> youtubePlaylist(link.id)
            is MusicLink.YouTubeAlbum -> youtubeAlbum(link.browseId)
            is MusicLink.YouTubeSong -> youtube.getSong(link.videoId)?.let { s ->
                val song = WireSong(
                    title = s.title, artist = s.artist, album = s.album, durationMs = s.durationMs,
                    refs = mapOf(WebLibraryFile.YOUTUBE_SOURCE to s.videoId), artwork = listOfNotNull(s.thumbnailUrl),
                )
                LinkLoad.Ok(
                    LinkPreview(
                        LinkKind.SONG, s.title, s.artist, s.thumbnailUrl, YOUTUBE, listOf(song), SONGS_SOURCE_ID,
                        fromList = link.listId?.let { MusicLink.YouTubePlaylist(it) },
                    ),
                )
            } ?: LinkLoad.Failed(CANT_OPEN)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "load failed: ${e.javaClass.simpleName}")
        LinkLoad.Failed(CANT_OPEN)
    }

    /** The playlist [preview] became already, or null. */
    suspend fun existing(preview: LinkPreview): Long? = playlists.findBySourceId(preview.sourceId)?.takeIf { it.isActive }?.id

    /**
     * Brings [preview] into the library, its playlist kept on the phone when [download]; [progress] is told the songs matched
     * so far of all. Safe to run again: the playlist gains only what it lacks.
     */
    suspend fun add(preview: LinkPreview, download: Boolean, progress: (done: Int, total: Int) -> Unit): LinkAdded {
        val now = System.currentTimeMillis()
        val content = WebLibraryContent(
            likes = emptyList(),
            playlists = listOf(ImportedPlaylist(preview.sourceId, nameOf(preview), preview.songs, now, now)),
            history = emptyList(),
        )
        val result = importer.import(content, ImportSelection(likes = false, plays = false), ORIGIN, { it.id }, progress)
        val playlist = playlists.findBySourceId(preview.sourceId) ?: error("the playlist wasn't made")
        // Its cover: a song's isn't the playlist of songs from links' (that one shows its songs' covers).
        if (preview.kind != LinkKind.SONG && playlist.artUrl.isNullOrEmpty() && preview.coverUrl != null) {
            playlists.updateArtUrl(playlist.id, preview.coverUrl)
        }
        if (download) musicRepository.setPlaylistDownload(playlist.id, true)
        return LinkAdded(playlist.id, result.songsAdded)
    }

    private fun nameOf(preview: LinkPreview) = if (preview.kind == LinkKind.SONG) SONGS_NAME else preview.name.take(MAX_NAME)

    private suspend fun spotifyPlaylist(id: String): LinkLoad {
        val embed = spotifyEmbed.get("playlist", id)
        // The whole playlist when Spotify gives it (a login, or the Web API); else the embed page's first 100.
        val whole = if (embed == null || embed.tracks.size >= SpotifyEmbedClient.EMBED_CAP) wholeSpotifyPlaylist(id) else null
        if (embed == null && whole == null) return LinkLoad.Failed(CANT_OPEN_PLAYLIST)
        val songs = whole?.takeIf { it.size >= (embed?.tracks?.size ?: 0) } ?: embed!!.songs()
        return ok(
            LinkPreview(
                kind = LinkKind.PLAYLIST,
                name = embed?.name ?: "Spotify playlist",
                byline = embed?.subtitle,
                coverUrl = embed?.coverUrl,
                service = SPOTIFY,
                songs = songs,
                sourceId = "${PLAYLIST_PREFIX}spotify_$id",
                partial = songs.size == embed?.tracks?.size && embed.tracks.size >= SpotifyEmbedClient.EMBED_CAP,
            ),
        )
    }

    private suspend fun wholeSpotifyPlaylist(id: String): List<WireSong>? = try {
        (spotifyApi.getPlaylistTracks(id) as? SyncResult.Success)?.data?.tracks?.mapNotNull { it.wire() }?.takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Rate-limited, or no login for GraphQL: the embed page's songs stand.
        Log.d(TAG, "whole playlist unavailable: ${e.javaClass.simpleName}")
        null
    }

    private suspend fun youtubePlaylist(id: String): LinkLoad {
        // An album's own playlist is that album.
        if (id.startsWith(ALBUM_PLAYLIST_PREFIX)) youtube.albumOfPlaylist(id)?.let { return youtubeAlbum(it) }
        val header = youtube.getPlaylistHeader(id)
        val tracks = when (val r = youtube.getPlaylistTracks(id)) {
            is SyncResult.Success -> r.data
            else -> return LinkLoad.Failed(CANT_OPEN_PLAYLIST)
        }
        return ok(
            LinkPreview(
                kind = LinkKind.PLAYLIST,
                name = header?.title ?: "YouTube Music playlist",
                byline = header?.author,
                coverUrl = header?.thumbnailUrl,
                service = YOUTUBE,
                songs = tracks.tracks.map { it.wire() },
                sourceId = "${PLAYLIST_PREFIX}yt_$id",
                partial = tracks.partial,
            ),
        )
    }

    private suspend fun youtubeAlbum(browseId: String): LinkLoad {
        val album = youtube.getAlbum(browseId)
        if (album.title.isBlank()) return LinkLoad.Failed(CANT_OPEN)
        return ok(
            LinkPreview(
                kind = LinkKind.ALBUM,
                name = album.title,
                byline = album.artist.takeIf { it.isNotBlank() },
                coverUrl = album.thumbnailUrl,
                service = YOUTUBE,
                songs = album.tracks.map { it.wire(album.title, album.thumbnailUrl) },
                // The id its album page saves it under (AlbumDiscoveryViewModel `savedSourceId`): the page then shows it saved.
                sourceId = "album:youtube:$browseId",
            ),
        )
    }

    /** A link with no songs (an empty playlist, a page of podcasts) is nothing to add. */
    private fun ok(preview: LinkPreview): LinkLoad =
        if (preview.songs.isEmpty()) LinkLoad.Failed(NO_SONGS) else LinkLoad.Ok(preview)

    companion object {
        private const val TAG = "LinkImporter"

        /** Plays and likes never come from a link; the import still names where it came from. */
        private const val ORIGIN = "link"

        private const val SPOTIFY = "Spotify"
        private const val YOUTUBE = "YouTube Music"

        /** A playlist from a link: CUSTOM, shown by Library like every `custom_%` playlist. */
        const val PLAYLIST_PREFIX = "custom_link_"

        /** The playlist songs pasted one at a time go into. */
        const val SONGS_SOURCE_ID = "custom_link_songs"
        const val SONGS_NAME = "Songs from links"

        private const val ALBUM_PLAYLIST_PREFIX = "OLAK5uy_"
        private const val MAX_NAME = 100

        const val NOT_MUSIC = "That link isn't a Spotify or YouTube playlist, album or song."
        const val CANT_OPEN = "Couldn't open this link. Check your connection and try again."
        const val CANT_OPEN_PLAYLIST = "Couldn't open this playlist. It may be private."
        const val NO_SONGS = "There are no songs here to add."

        private fun SpotifyEmbedEntity.songs(cover: String? = null): List<WireSong> = tracks.map { t ->
            WireSong(
                title = t.title, artist = t.artist,
                album = if (type == "album") name else null,
                durationMs = t.durationMs, spotifyId = t.id,
                artwork = listOfNotNull(cover ?: coverUrl.takeIf { type == "album" }),
            )
        }

        private fun SpotifyTrackItem.wire(): WireSong? {
            val t = track ?: return null
            if (t.id.isBlank() || t.name.isBlank()) return null
            val artist = t.artists.joinToString(", ") { it.name }.takeIf { it.isNotBlank() } ?: return null
            return WireSong(
                title = t.name, artist = artist, album = t.album?.name, durationMs = t.duration_ms.takeIf { it > 0 },
                isrc = t.isrc, spotifyId = t.id,
            )
        }

        private fun YTMusicTrack.wire() = WireSong(
            title = title, artist = artists, album = album, durationMs = durationMs,
            refs = mapOf(WebLibraryFile.YOUTUBE_SOURCE to videoId), artwork = listOfNotNull(thumbnailUrl),
        )

        private fun TrackSummary.wire(album: String, cover: String?) = WireSong(
            title = title, artist = artist, album = this.album ?: album,
            durationMs = (durationSeconds * 1000).toLong().takeIf { it > 0 },
            refs = mapOf(WebLibraryFile.YOUTUBE_SOURCE to videoId), artwork = listOfNotNull(thumbnailUrl ?: cover),
        )
    }
}
