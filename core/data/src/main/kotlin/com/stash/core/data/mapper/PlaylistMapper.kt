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
fun PlaylistEntity.toDomain(): Playlist {
    // Daily mixes store 2-tile mosaics as "url1|url2" in art_url. Split for
    // mosaic-aware callers; keep the primary URL in [Playlist.artUrl] so
    // single-image call sites (AsyncImage(model = playlist.artUrl)) work
    // unchanged. Non-pipe URLs pass through as a one-element list.
    val tileUrls = artUrl?.split('|')?.filter { it.isNotBlank() } ?: emptyList()
    return Playlist(
        id = id,
        name = name,
        source = source,
        sourceId = sourceId,
        type = type,
        mixNumber = mixNumber,
        lastSynced = lastSynced?.toEpochMilli(),
        trackCount = trackCount,
        isActive = isActive,
        artUrl = tileUrls.firstOrNull(),
        artTileUrls = tileUrls,
        syncEnabled = syncEnabled,
        dateAdded = dateAdded.toEpochMilli(),
    )
}

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
    // Re-join the tile URLs when persisting; falls back to primary artUrl
    // if the caller never populated artTileUrls.
    artUrl = artTileUrls.takeIf { it.isNotEmpty() }?.joinToString("|") ?: artUrl,
    syncEnabled = syncEnabled,
    dateAdded = Instant.ofEpochMilli(dateAdded),
)
