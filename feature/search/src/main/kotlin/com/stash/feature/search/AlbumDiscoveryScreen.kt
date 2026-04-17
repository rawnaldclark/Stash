package com.stash.feature.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.media.actions.TrackItem
import com.stash.core.media.preview.PreviewState
import com.stash.core.ui.components.DiscoveryErrorCard
import com.stash.core.ui.components.SectionHeader
import com.stash.data.ytmusic.model.AlbumSummary
import kotlinx.coroutines.flow.merge

/**
 * Album Discovery screen.
 *
 * Layout (top → bottom):
 *  1. [AlbumHero] — paints from nav args on first frame (cover + title +
 *     artist + action chips). Shuffle chip only shows when the album has at
 *     least one downloaded track.
 *  2. Body, gated on [AlbumDiscoveryStatus]:
 *     - [AlbumDiscoveryStatus.Loading] — centred [CircularProgressIndicator].
 *     - [AlbumDiscoveryStatus.Error]   — [DiscoveryErrorCard] with a Retry
 *       button (passes a screen-specific title to match
 *       [ArtistProfileScreen]).
 *     - [AlbumDiscoveryStatus.Fresh]   — tracklist of [PreviewDownloadRow]s
 *       (one per [com.stash.data.ytmusic.model.TrackSummary]) followed by a
 *       "More by this artist" rail when non-empty. Empty tracklist renders a
 *       "No tracks available" message.
 *
 * When [AlbumDiscoveryUiState.showDownloadConfirm] is true, a Material3
 * [AlertDialog] overlays the scaffold with the Download-All confirm flow.
 * If the snapshot queue is empty (all tracks already downloaded), the dialog
 * collapses to a single OK button — no destructive "Download 0 tracks" path.
 *
 * `userMessages` from both the VM and its [com.stash.core.media.actions.TrackActionsDelegate]
 * are merged through a single [SnackbarHostState] so preview/download errors
 * surface through the same channel as cache-fetch errors.
 *
 * Per-row preview + download state is sourced directly from `vm.delegate.*`
 * (matching [ArtistProfileScreen]) — the screen does not hold its own
 * copies.
 */
@Composable
fun AlbumDiscoveryScreen(
    onBack: () -> Unit,
    onNavigateToAlbum: (AlbumSummary) -> Unit,
    vm: AlbumDiscoveryViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val downloadingIds by vm.delegate.downloadingIds.collectAsStateWithLifecycle()
    val downloadedIds by vm.delegate.downloadedIds.collectAsStateWithLifecycle()
    val previewLoadingId by vm.delegate.previewLoadingId.collectAsStateWithLifecycle()
    val previewState by vm.delegate.previewState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) {
        merge(
            vm.userMessages,
            vm.delegate.userMessages,
        ).collect { message -> snackbar.showSnackbar(message) }
    }

    val hasDownloaded = remember(state.tracks, downloadedIds) {
        state.tracks.any { it.videoId in downloadedIds }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 120.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            item {
                AlbumHero(
                    hero = state.hero,
                    hasDownloaded = hasDownloaded,
                    onBack = onBack,
                    onShuffle = vm::shuffleDownloaded,
                    onDownloadAll = vm::onDownloadAllClicked,
                )
            }

            when (val status = state.status) {
                AlbumDiscoveryStatus.Loading -> item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                is AlbumDiscoveryStatus.Error -> item {
                    DiscoveryErrorCard(
                        title = "Couldn't load album",
                        message = status.message,
                        onRetry = vm::retry,
                    )
                }
                AlbumDiscoveryStatus.Fresh -> {
                    if (state.tracks.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "No tracks available",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(
                            items = state.tracks,
                            key = { "album_track_" + it.videoId },
                        ) { track ->
                            val currentPreviewState = previewState
                            val isPreviewPlaying = currentPreviewState is PreviewState.Playing &&
                                currentPreviewState.videoId == track.videoId
                            PreviewDownloadRow(
                                item = track.toSearchResultItem(),
                                isDownloading = track.videoId in downloadingIds,
                                isDownloaded = track.videoId in downloadedIds,
                                isPreviewLoading = previewLoadingId == track.videoId,
                                isPreviewPlaying = isPreviewPlaying,
                                onPreview = { vm.delegate.previewTrack(track.videoId) },
                                onStopPreview = { vm.delegate.stopPreview() },
                                onDownload = {
                                    vm.delegate.downloadTrack(
                                        TrackItem(
                                            videoId = track.videoId,
                                            title = track.title,
                                            artist = track.artist,
                                            durationSeconds = track.durationSeconds,
                                            thumbnailUrl = track.thumbnailUrl,
                                        ),
                                    )
                                },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        if (state.moreByArtist.isNotEmpty()) {
                            item { SectionHeader(title = "More by this artist") }
                            item {
                                AlbumsRow(
                                    albums = state.moreByArtist,
                                    onClick = onNavigateToAlbum,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.showDownloadConfirm) {
            val count = state.downloadConfirmQueue.size
            AlertDialog(
                onDismissRequest = vm::onDownloadAllDismissed,
                title = { Text("Download all?") },
                text = {
                    Text(
                        text = if (count == 0) {
                            "All tracks already downloaded."
                        } else {
                            "Download $count track${if (count == 1) "" else "s"} to your library?"
                        },
                    )
                },
                confirmButton = {
                    if (count == 0) {
                        TextButton(onClick = vm::onDownloadAllDismissed) { Text("OK") }
                    } else {
                        Button(onClick = vm::onDownloadAllConfirmed) { Text("Download") }
                    }
                },
                dismissButton = {
                    if (count != 0) {
                        TextButton(onClick = vm::onDownloadAllDismissed) { Text("Cancel") }
                    }
                },
            )
        }
    }
}
