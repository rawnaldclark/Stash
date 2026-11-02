package com.stash.feature.search

import com.stash.core.data.album.SavedAlbum
import com.stash.core.data.album.SavedAlbums
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import kotlinx.coroutines.flow.flowOf
import org.mockito.kotlin.whenever

/** [this] mock's saved albums, what an album page's "Saved" reads ([MusicRepository.observeSavedAlbums]). */
internal fun MusicRepository.withSavedAlbums(albums: List<SavedAlbum> = emptyList()): MusicRepository = apply {
    whenever(observeSavedAlbums()).thenReturn(flowOf(albums))
}

/** A saved album row as the repository reports it. */
internal fun savedAlbum(
    id: Long,
    sourceId: String,
    title: String = "",
    artist: String = "",
    source: MusicSource = MusicSource.BOTH,
    songs: List<String> = emptyList(),
) = SavedAlbum(
    playlistId = id, title = title, artist = artist, artUrl = null, source = source, sourceId = sourceId,
    dateAdded = id, trackCount = songs.size, downloadedCount = 0,
    songKeys = songs.mapTo(LinkedHashSet()) { SavedAlbums.songKey(it) },
)
