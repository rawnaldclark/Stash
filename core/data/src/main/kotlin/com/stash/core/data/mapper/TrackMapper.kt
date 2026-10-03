package com.stash.core.data.mapper

import com.stash.core.data.db.dao.LibraryTrackRow
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.Track
import java.time.Instant

/**
 * Maps the narrow Library feed projection to the domain [Track]. Field-for-
 * field twin of [TrackEntity.toDomain] — [LibraryTrackRow] exists precisely
 * so this mapper's inputs define the SELECT list (#380).
 */
fun LibraryTrackRow.toDomain(): Track = Track(
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
    albumArtist = albumArtist,
    dateAdded = dateAdded.toEpochMilli(),
    lastPlayed = lastPlayed?.toEpochMilli(),
    playCount = playCount,
    isDownloaded = isDownloaded,
    matchConfidence = matchConfidence,
    matchDismissed = matchDismissed,
    isrc = isrc,
    explicit = explicit,
    bitsPerSample = bitsPerSample,
    sampleRateHz = sampleRateHz,
    spotifySavedAt = spotifySavedAt,
    lastFmLovedAt = lastFmLovedAt,
    ytMusicSavedAt = ytMusicSavedAt,
    stashLikedAt = stashLikedAt,
    isStreamable = isStreamable,
    isStreamableCheckedAt = isStreamableCheckedAt,
    metadataEmbeddedAt = metadataEmbeddedAt,
    lyricsFetchedAt = lyricsFetchedAt,
)

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
    albumArtist = albumArtist,
    dateAdded = dateAdded.toEpochMilli(),
    lastPlayed = lastPlayed?.toEpochMilli(),
    playCount = playCount,
    isDownloaded = isDownloaded,
    matchConfidence = matchConfidence,
    matchDismissed = matchDismissed,
    matchPickedAt = matchPickedAt,
    isrc = isrc,
    explicit = explicit,
    bitsPerSample = bitsPerSample,
    sampleRateHz = sampleRateHz,
    spotifySavedAt = spotifySavedAt,
    lastFmLovedAt = lastFmLovedAt,
    ytMusicSavedAt = ytMusicSavedAt,
    stashLikedAt = stashLikedAt,
    isStreamable = isStreamable,
    isStreamableCheckedAt = isStreamableCheckedAt,
    metadataEmbeddedAt = metadataEmbeddedAt,
    lyricsFetchedAt = lyricsFetchedAt,
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
    albumArtist = albumArtist,
    dateAdded = Instant.ofEpochMilli(dateAdded),
    lastPlayed = lastPlayed?.let { Instant.ofEpochMilli(it) },
    playCount = playCount,
    isDownloaded = isDownloaded,
    matchConfidence = matchConfidence,
    matchDismissed = matchDismissed,
    matchPickedAt = matchPickedAt,
    isrc = isrc,
    explicit = explicit,
    bitsPerSample = bitsPerSample,
    sampleRateHz = sampleRateHz,
    spotifySavedAt = spotifySavedAt,
    lastFmLovedAt = lastFmLovedAt,
    ytMusicSavedAt = ytMusicSavedAt,
    stashLikedAt = stashLikedAt,
    isStreamable = isStreamable,
    isStreamableCheckedAt = isStreamableCheckedAt,
    metadataEmbeddedAt = metadataEmbeddedAt,
    lyricsFetchedAt = lyricsFetchedAt,
)
