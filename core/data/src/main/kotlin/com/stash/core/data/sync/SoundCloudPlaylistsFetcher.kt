package com.stash.core.data.sync

import androidx.room.withTransaction
import com.stash.core.auth.soundcloud.SoundCloudAccountManager
import com.stash.core.auth.soundcloud.SoundCloudSession
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.RemoteTrackSnapshotEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.data.soundcloud.SoundCloudClient
import com.stash.data.soundcloud.SoundCloudPlaylist
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

data class SoundCloudPlaylistFetchResult(val published: Int = 0, val failed: Int = 0, val skipped: Boolean = false)

/** Fetch a complete listing, then hydrate/commit one complete playlist at a time. */
class SoundCloudPlaylistsFetcher @Inject constructor(
    private val client: SoundCloudClient,
    private val accounts: SoundCloudAccountManager,
    private val database: StashDatabase,
) {
    suspend fun fetchPlaylists(syncId: Long, session: SoundCloudSession): SoundCloudPlaylistFetchResult =
        publish(syncId, session, client.ownPlaylists(session.userId, session.oauthToken), mixes = false)

    suspend fun fetchMixes(syncId: Long, session: SoundCloudSession): SoundCloudPlaylistFetchResult {
        val token = session.oauthToken ?: return SoundCloudPlaylistFetchResult(skipped = true)
        return publish(syncId, session, client.personalMixes(session.userId, token), mixes = true)
    }

    private suspend fun publish(syncId: Long, session: SoundCloudSession, playlists: List<SoundCloudPlaylist>, mixes: Boolean): SoundCloudPlaylistFetchResult {
        var published = 0
        var failed = 0
        for (playlist in playlists) {
            try {
                val tracks = client.playlistTracks(client.playlist(playlist.id, session.oauthToken), session.oauthToken)
                val applied = accounts.withCurrentSession(session.connectionId) {
                    database.withTransaction {
                        val dao = database.remoteSnapshotDao()
                        val id = dao.insertPlaylistSnapshot(RemotePlaylistSnapshotEntity(
                            syncId = syncId, source = MusicSource.SOUNDCLOUD,
                            sourcePlaylistId = if (mixes) SoundCloudPlaylistIds.mix(session.userId, playlist.id)
                                else SoundCloudPlaylistIds.playlist(session.userId, playlist.id),
                            soundCloudConnectionId = session.connectionId, playlistName = playlist.title,
                            playlistType = if (mixes) PlaylistType.DAILY_MIX else PlaylistType.CUSTOM,
                            trackCount = tracks.size, artUrl = playlist.artworkUrl ?: tracks.firstNotNullOfOrNull { it.artworkUrl },
                        ))
                        dao.insertTrackSnapshots(tracks.mapIndexed { position, track -> RemoteTrackSnapshotEntity(
                            syncId = syncId, snapshotPlaylistId = id, title = track.title, artist = track.artist,
                            durationMs = track.durationMs, soundCloudId = track.id, soundCloudPermalink = track.permalink,
                            soundCloudPreview = track.preview, albumArtUrl = track.artworkUrl, isrc = track.isrc, position = position,
                        ) })
                        true
                    }
                } ?: false
                if (!applied) return SoundCloudPlaylistFetchResult(published, failed, skipped = true)
                published++
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                // No snapshot for this list: keep its existing membership, but still import healthy lists.
                failed++
            }
        }
        return SoundCloudPlaylistFetchResult(published, failed)
    }
}
