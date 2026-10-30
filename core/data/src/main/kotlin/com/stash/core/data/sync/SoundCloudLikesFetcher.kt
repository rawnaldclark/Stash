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
import javax.inject.Inject

/** Fetch completely before publishing; a failed page cannot authorize removal of any likes. */
class SoundCloudLikesFetcher @Inject constructor(
    private val client: SoundCloudClient,
    private val accounts: SoundCloudAccountManager,
    private val database: StashDatabase,
) {
    suspend fun fetch(syncId: Long, session: SoundCloudSession): Boolean {
        val tracks = client.likes(session.userId, session.oauthToken)
        return accounts.withCurrentSession(session.connectionId) {
            database.withTransaction {
                val snapshots = database.remoteSnapshotDao()
                val id = snapshots.insertPlaylistSnapshot(RemotePlaylistSnapshotEntity(
                    syncId = syncId, source = MusicSource.SOUNDCLOUD,
                    sourcePlaylistId = "soundcloud_liked_${session.userId}",
                    soundCloudConnectionId = session.connectionId,
                    playlistName = "SoundCloud Liked", playlistType = PlaylistType.LIKED_SONGS,
                    trackCount = tracks.size, artUrl = tracks.firstNotNullOfOrNull { it.artworkUrl },
                ))
                snapshots.insertTrackSnapshots(tracks.mapIndexed { position, track ->
                    RemoteTrackSnapshotEntity(
                        syncId = syncId, snapshotPlaylistId = id, title = track.title, artist = track.artist,
                        durationMs = track.durationMs, soundCloudId = track.id,
                        soundCloudPermalink = track.permalink, soundCloudPreview = track.preview,
                        albumArtUrl = track.artworkUrl, isrc = track.isrc, position = position,
                    )
                })
                true
            }
        } ?: false
    }
}
