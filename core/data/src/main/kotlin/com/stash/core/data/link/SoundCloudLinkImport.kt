package com.stash.core.data.link

import androidx.room.withTransaction
import com.stash.core.auth.TokenManager
import com.stash.core.common.TrackCanonical
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.model.MusicSource
import com.stash.data.soundcloud.SoundCloudClient
import com.stash.data.soundcloud.SoundCloudLinks
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class SoundCloudLinkSong(val id: String, val permalink: String, val title: String, val artist: String,
    val durationMs: Long, val artUrl: String?, val isrc: String?, val preview: Boolean)

/** Phone-local import identity; it never changes the web player's wire protocol. */
class SoundCloudLinkImport @Inject constructor(
    private val client: SoundCloudClient,
    private val tokens: TokenManager,
    private val database: StashDatabase,
    private val repository: MusicRepository,
) {
    suspend fun load(url: String): LinkLoad = withContext(Dispatchers.IO) {
        val link = SoundCloudLinks.parse(url) ?: return@withContext LinkLoad.Failed("Invalid SoundCloud link")
        val token = tokens.getSoundCloudToken()
        val entity = client.resolve(link, token)
        val remoteId = entity.text("id")?.takeIf { it.matches(Regex("[0-9]+")) }
            ?: return@withContext LinkLoad.Failed("SoundCloud page is unavailable")
        val kind = entity.text("kind")
        val tracks = when (kind) {
            "track" -> listOf(client.parseTrack(entity))
            "playlist" -> client.playlistTracks(entity, token)
            "user" -> client.likes(client.user(entity).id, token)
            else -> return@withContext LinkLoad.Failed("This SoundCloud page has no songs")
        }
        if (tracks.isEmpty()) return@withContext LinkLoad.Failed("This SoundCloud page has no songs")
        val song = kind == "track"
        LinkLoad.Ok(LinkPreview(
            kind = if (song) LinkKind.SONG else LinkKind.PLAYLIST,
            name = if (kind == "user") "${client.user(entity).name}'s likes" else entity.text("title") ?: "SoundCloud playlist",
            byline = (entity["user"] as? JsonObject)?.text("username"),
            coverUrl = tracks.firstNotNullOfOrNull { it.artworkUrl }, service = "SoundCloud",
            songs = tracks.map { WireSong(it.title, it.artist, durationMs = it.durationMs,
                artwork = listOfNotNull(it.artworkUrl), phoneOnly = true) },
            sourceId = if (song) LinkImporter.SONGS_SOURCE_ID else "custom_link_soundcloud_${kind}_${remoteId}",
            soundCloudSongs = tracks.map { SoundCloudLinkSong(it.id, it.permalink, it.title, it.artist,
                it.durationMs, it.artworkUrl, it.isrc, it.preview) },
        ))
    }

    suspend fun add(preview: LinkPreview, download: Boolean, progress: (Int, Int) -> Unit): LinkAdded = withContext(Dispatchers.IO) {
        val songs = requireNotNull(preview.soundCloudSongs)
        val result = database.withTransaction {
            val playlists = database.playlistDao()
            val tracks = database.trackDao()
            val playlistId = playlists.findBySourceId(preview.sourceId)?.id ?: playlists.insert(PlaylistEntity(
                name = if (preview.kind == LinkKind.SONG) "Songs from links" else preview.name.take(200),
                source = MusicSource.BOTH, sourceId = preview.sourceId, artUrl = preview.coverUrl,
            ))
            val have = playlists.getCrossRefsForPlaylist(playlistId).filter { it.removedAt == null }.map { it.trackId }.toSet()
            val wanted = songs.mapIndexedNotNull { index, s ->
                val ct = TrackCanonical.title(s.title); val ca = TrackCanonical.artist(s.artist)
                val existing = tracks.findBySoundCloudId(s.id) ?: s.isrc?.takeIf { it.matches(Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")) }
                    ?.let { tracks.findByIsrc(it) }?.takeIf { it.soundCloudId == null && it.source != MusicSource.CLOUD && !it.matchFlagged && !it.matchDismissed }
                progress(index + 1, songs.size)
                if (database.trackBlocklistDao().isBlocked(com.stash.core.data.blocklist.BlocklistKey.fromStoredCanonicals(ca, ct), existing?.spotifyUri, existing?.youtubeId)) null else {
                    if (existing != null) {
                        tracks.update(existing.copy(soundCloudId = s.id, soundCloudPermalink = s.permalink, soundCloudPreview = s.preview))
                        existing.id
                    } else tracks.insert(TrackEntity(title = s.title, artist = s.artist, durationMs = s.durationMs,
                        source = MusicSource.SOUNDCLOUD, soundCloudId = s.id, soundCloudPermalink = s.permalink,
                        soundCloudPreview = s.preview, isrc = s.isrc, albumArtUrl = s.artUrl,
                        canonicalTitle = ct, canonicalArtist = ca))
                }
            }.distinct()
            val missing = wanted.filterNot(have::contains)
            val start = playlists.getNextPosition(playlistId)
            playlists.insertAllCrossRefs(missing.mapIndexed { index, id ->
                com.stash.core.data.db.entity.PlaylistTrackCrossRef(playlistId = playlistId, trackId = id,
                    position = start + index, locallyAdded = true)
            })
            playlists.updateTrackCount(playlistId, have.size + missing.size)
            LinkAdded(playlistId, missing.size)
        }
        if (download && songs.any { !it.preview }) repository.setPlaylistDownload(result.playlistId, true)
        result
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}
