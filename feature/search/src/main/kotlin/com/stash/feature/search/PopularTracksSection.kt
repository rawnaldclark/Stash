package com.stash.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stash.data.ytmusic.model.TrackSummary

/**
 * "Popular" shelf on the Artist Profile screen.
 *
 * Renders up to 5 of the artist's popular tracks via [PreviewDownloadRow] —
 * the exact same composable the main Search tab uses for song results. No
 * fork: a shared Compose UI test (when the androidTest source set lands)
 * asserts identity via the `"PreviewDownloadRow"` test tag.
 *
 * For Task 8 the row click / download / preview handlers are no-ops; Task
 * 10 wires them to the real SearchViewModel once the Search tab rewrite
 * lands.
 */
@Composable
fun PopularTracksSection(
    tracks: List<TrackSummary>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tracks.forEach { track ->
            PreviewDownloadRow(
                item = SearchResultItem(
                    videoId = track.videoId,
                    title = track.title,
                    artist = track.artist,
                    durationSeconds = track.durationSeconds,
                    thumbnailUrl = track.thumbnailUrl,
                ),
                isDownloading = false,
                isDownloaded = false,
                isPreviewLoading = false,
                isPreviewPlaying = false,
                onPreview = { /* Task 10 wires preview */ },
                onStopPreview = { /* Task 10 wires preview */ },
                onDownload = { /* Task 10 wires download */ },
            )
        }
    }
}
