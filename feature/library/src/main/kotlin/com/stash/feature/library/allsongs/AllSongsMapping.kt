package com.stash.feature.library.allsongs

import com.stash.core.data.db.entity.ScannedSongEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.core.model.scannedTrackId
import com.stash.feature.library.SortOrder

/**
 * Search matches title, artist or album. "Recently added" is by the file's modified time (what
 * "new on this phone" means for files that were already there), else the day it was first found.
 */
internal fun sortAndFilter(rows: List<ScannedSongEntity>, order: SortOrder, query: String): List<ScannedSongEntity> {
    val q = query.trim()
    val matched = if (q.isEmpty()) {
        rows
    } else {
        rows.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.artist.contains(q, ignoreCase = true) ||
                it.album.contains(q, ignoreCase = true)
        }
    }
    return when (order) {
        SortOrder.ALPHABETICAL -> matched.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        SortOrder.RECENT -> matched.sortedByDescending { if (it.lastModified > 0) it.lastModified else it.dateAdded }
        SortOrder.DURATION -> matched.sortedByDescending { it.durationMs }
        else -> matched // MOST_PLAYED isn't offered here: scanned songs have no play counts
    }
}

/** A scanned row as the player's Track: an id in the scanned range ([scannedTrackId]), local and on disk. */
internal fun ScannedSongEntity.toTrack(covers: ScannedCoverStore): Track = Track(
    id = scannedTrackId(id),
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    filePath = uri,
    fileFormat = fileFormat,
    fileSizeBytes = fileSizeBytes,
    albumArtPath = albumArtPath?.takeIf { it.isNotEmpty() }?.let { covers.file(it).absolutePath },
    dateAdded = dateAdded,
    source = MusicSource.LOCAL,
    isDownloaded = true,
)