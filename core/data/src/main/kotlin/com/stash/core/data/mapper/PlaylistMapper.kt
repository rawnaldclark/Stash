package com.stash.core.data.mapper

import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.model.Playlist
import java.time.Instant

/**
 * Maps a [PlaylistEntity] (Room layer) to a [Playlist] (domain layer).
 *
 * The [Playlist.tracks] list is left empty; callers should populate it
 * separately when a playlist-with-tracks projection is needed.
 */
fun PlaylistEntity.toDomain(): Playlist = Playlist(
    id = id,
    name = name,
    source = source,
    sourceId = sourceId,
    type = type,
    mixNumber = mixNumber,
    lastSynced = lastSynced?.toEpochMilli(),
    trackCount = trackCount,
    isActive = isActive,
    artUrl = artUrl,
)

/**
 * Maps a [Playlist] (domain layer) to a [PlaylistEntity] (Room layer).
 *
 * The [Playlist.tracks] list is not persisted in the entity; track
 * associations are managed through the [PlaylistTrackCrossRef] table.
 */
fun Playlist.toEntity(): PlaylistEntity = PlaylistEntity(
    id = id,
    name = name,
    source = source,
    sourceId = sourceId,
    type = type,
    mixNumber = mixNumber,
    lastSynced = lastSynced?.let { Instant.ofEpochMilli(it) },
    trackCount = trackCount,
    isActive = isActive,
    artUrl = artUrl,
)
