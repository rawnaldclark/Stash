package com.stash.feature.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.media.preview.LosslessUrlPrefetcher
import com.stash.core.model.TrackItem
import com.stash.core.media.preview.PreviewState
import com.stash.core.ui.components.DiscoveryErrorCard
import com.stash.core.ui.components.SectionHeader
import com.stash.core.ui.R
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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    val currentPlayingYoutubeId by vm.currentPlayingYoutubeId.collectAsStateWithLifecycle()
    val streamingEnabled by vm.streamingEnabled.collectAsStateWithLifecycle()
    var showStreamingSheet by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val streamingSheetState = androidx.compose.material3.rememberModalBottomSheetState()
    val playlistSheetItem by vm.playlistSheetItem.collectAsStateWithLifecycle()
    val userPlaylists by vm.userPlaylists.collectAsStateWithLifecycle()
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
                    onPlayAlbum = { vm.playAlbum(startIndex = 0) },
                    onAddToQueue = vm::addAlbumToQueue,
                    streamingEnabled = streamingEnabled,
                    onStreamingClick = { showStreamingSheet = true },
                    downloadSupported = vm.downloadSupported,
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
                        title = stringResource(R.string.error_load_album),
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
                                    text = stringResource(R.string.label_no_tracks_available),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = state.tracks,
                            // Include the index: Qobuz tracks all carry a blank
                            // videoId, so keying on videoId alone collides (Compose
                            // requires unique keys). The album tracklist is static
                            // (never reordered), so the index is a stable key.
                            key = { index, t -> "album_track_${index}_${t.videoId}" },
                        ) { index, track ->
                            val currentPreviewState = previewState
                            val isPreviewPlaying = currentPreviewState is PreviewState.Playing &&
                                currentPreviewState.videoId == track.videoId
                            val trackItem = TrackItem(
                                videoId = track.videoId,
                                title = track.title,
                                artist = track.artist,
                                durationSeconds = track.durationSeconds,
                                thumbnailUrl = track.thumbnailUrl,
                                album = state.hero.title,
                                albumArtist = state.hero.artist,
                            )
                            // Warm the lossless URL cache as each YT album track row
                            // enters composition (blank-videoId Qobuz rows dedupe to
                            // a no-op). Idempotent.
                            LaunchedEffect(track.videoId) {
                                vm.losslessPrefetcher.warmUp(trackItem)
                            }
                            // One row for every album track. Tapping plays the album
                            // from this position (album context — not a loose preview).
                            // Download is hidden for Qobuz-native tracks (no videoId).
                            SongRow(
                                item = track.toSearchResultItem(),
                                isDownloading = track.videoId in downloadingIds,
                                isDownloaded = track.videoId in downloadedIds,
                                isPreviewLoading = previewLoadingId == track.videoId,
                                isPreviewPlaying = isPreviewPlaying,
                                isPlaying = isRowPlaying(track.videoId, currentPlayingYoutubeId),
                                downloadSupported = vm.downloadSupported,
                                onPlay = { vm.playAlbum(startIndex = index) },
                                onStopPreview = { vm.delegate.stopPreview() },
                                onDownload = { vm.delegate.downloadTrack(trackItem) },
                                onPlayNext = { vm.onPlayNext(trackItem) },
                                onAddToQueue = { vm.onAddToQueue(trackItem) },
                                onStartRadio = { vm.onStartRadio(trackItem) },
                                onAddToPlaylist = { vm.onRequestAddToPlaylist(trackItem) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        if (state.moreByArtist.isNotEmpty()) {
                            item { SectionHeader(title = stringResource(R.string.section_more_by_artist)) }
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
                title = { Text(stringResource(R.string.dialog_title_download_all)) },
                text = {
                    Text(
                        text = if (count == 0) {
                            stringResource(R.string.dialog_body_all_downloaded)
                        } else {
                            stringResource(R.string.dialog_body_download_count, count)
                        },
                    )
                },
                confirmButton = {
                    if (count == 0) {
                        TextButton(onClick = vm::onDownloadAllDismissed) { Text(stringResource(R.string.action_ok)) }
                    } else {
                        Button(onClick = vm::onDownloadAllConfirmed) { Text(stringResource(R.string.action_download)) }
                    }
                },
                dismissButton = {
                    if (count != 0) {
                        TextButton(onClick = vm::onDownloadAllDismissed) { Text(stringResource(R.string.action_cancel)) }
                    }
                },
            )
        }

        if (showStreamingSheet) {
            com.stash.core.ui.components.streaming.StreamingModeSheet(
                streamingEnabled = streamingEnabled,
                onSelect = { requested ->
                    vm.applyStreamingMode(requested)
                    showStreamingSheet = false
                },
                onDismiss = { showStreamingSheet = false },
                sheetState = streamingSheetState,
            )
        }

        if (playlistSheetItem != null) {
            com.stash.core.ui.components.SaveToPlaylistSheet(
                playlists = userPlaylists.map {
                    com.stash.core.ui.components.PlaylistInfo(it.id, it.name, it.trackCount)
                },
                onSaveToPlaylist = vm::onSaveToPlaylist,
                onCreatePlaylist = vm::onCreatePlaylistAndAdd,
                onDismiss = vm::onDismissPlaylistSheet,
            )
        }
    }
}

