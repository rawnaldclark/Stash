package com.stash.core.data.mapper

import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.Track
import java.time.Instant

/**
 * Maps a [TrackEntity] (Room layer) to a [Track] (domain layer).
 *
 * Timestamps are converted from [Instant] to epoch-millis [Long] because
 * the domain model uses primitive longs for serialisation simplicity.
 */
fun TrackEntity.toDomain(): Track = Track(
    id = id,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    filePath = filePath,
    fileFormat = fileFormat,
    qualityKbps = qualityKbps,
    fileSizeBytes = fileSizeBytes,
    source = source,
    spotifyUri = spotifyUri,
    youtubeId = youtubeId,
    albumArtUrl = albumArtUrl,
    albumArtPath = albumArtPath,
    dateAdded = dateAdded.toEpochMilli(),
    lastPlayed = lastPlayed?.toEpochMilli(),
    playCount = playCount,
    isDownloaded = isDownloaded,
    matchConfidence = matchConfidence,
    matchDismissed = matchDismissed,
)

/**
 * Maps a [Track] (domain layer) to a [TrackEntity] (Room layer).
 *
 * Epoch-millis timestamps are converted to [Instant] for the entity.
 */
fun Track.toEntity(): TrackEntity = TrackEntity(
    id = id,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    filePath = filePath,
    fileFormat = fileFormat,
    qualityKbps = qualityKbps,
    fileSizeBytes = fileSizeBytes,
    source = source,
    spotifyUri = spotifyUri,
    youtubeId = youtubeId,
    albumArtUrl = albumArtUrl,
    albumArtPath = albumArtPath,
    dateAdded = Instant.ofEpochMilli(dateAdded),
    lastPlayed = lastPlayed?.let { Instant.ofEpochMilli(it) },
    playCount = playCount,
    isDownloaded = isDownloaded,
    matchConfidence = matchConfidence,
    matchDismissed = matchDismissed,
)
