package com.stash.feature.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.Track
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.core.ui.components.SaveToPlaylistSheet
import com.stash.core.ui.components.ShareTrackSheet
import com.stash.feature.nowplaying.listen.ListenTogetherViewModel
import com.stash.feature.nowplaying.ui.QueueBottomSheet

/**
 * The queue sheet opened from the mini player, over whatever screen is showing: the same
 * [QueueBottomSheet] Now Playing's queue button opens, wired to the same actions (tap to play,
 * drag to reorder, swipe to remove (with Undo), and each row's menu: Play next, Start radio, Save to
 * playlist, Share, Download), so it behaves identically without the detour through Now Playing.
 *
 * Hosted by the app scaffold with the activity-scoped [NowPlayingViewModel] (the mini player's).
 * A Listen Together room has no personal queue (Now Playing hides its queue button there), so
 * the sheet closes itself if a room starts while it's open; it also closes (and never shows)
 * when there's no song, e.g. restored after the process died or the queue was cleared elsewhere.
 * The actions' confirmations ("Playing next", "Downloading…", a radio that couldn't start) come
 * from the ViewModel's userMessages, toasted app-wide by [PlayerMessagesToaster].
 *
 * @param onDismiss Closes the sheet (the host stops composing it).
 */
@Composable
fun PlayerQueueSheet(
    onDismiss: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
    listen: ListenTogetherViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val together by listen.state.collectAsStateWithLifecycle()
    val inRoom = together is ListenTogetherState.InRoom
    val hasTrack = uiState.hasTrack
    val showQueue = !inRoom && hasTrack
    LaunchedEffect(showQueue) { if (!showQueue) onDismiss() }

    // The queue row (if any) whose Save-to-Playlist picker is open.
    var saveTrack by remember { mutableStateOf<Track?>(null) }
    val shareTrack by viewModel.shareTrack.collectAsStateWithLifecycle()
    // Shown only for a share asked for in this sheet: the VM's lookup is async, and a result
    // landing after the sheet closed must not pop up the next time it opens.
    var shareRequested by remember { mutableStateOf(false) }
    // A share opened here belongs to this sheet: don't leave it pending in the activity-scoped VM.
    DisposableEffect(viewModel) { onDispose { viewModel.onShareDismissed() } }

    if (showQueue) {
        QueueBottomSheet(
            queue = uiState.queue,
            currentIndex = uiState.currentIndex,
            // The song's text ink: Now Playing's accent, contrast-safe on the sheet in both themes.
            accentColor = LocalAlbumChrome.current.ink,
            source = uiState.source,
            isShuffleEnabled = uiState.shuffleEnabled,
            onDismiss = onDismiss,
            onTrackClick = { index ->
                viewModel.onSkipToQueueIndex(index)
                onDismiss()
            },
            onRemoveTrack = viewModel::onRemoveFromQueue,
            onMoveTrack = viewModel::onMoveInQueue,
            onPlayNext = viewModel::queuePlayNext,
            onStartRadio = viewModel::startRadioForTrack,
            onSaveToPlaylist = { saveTrack = it },
            // Only when a sheet is coming: a request that never answers must not stay armed.
            onShare = { track -> shareRequested = viewModel.onShareTrack(track) },
            onToggleDownload = viewModel::toggleDownload,
            onRestoreTrack = viewModel::onRestoreToQueue,
        )
    }

    saveTrack?.let { t ->
        SaveToPlaylistSheet(
            playlists = uiState.userPlaylists,
            onSaveToPlaylist = { playlistId ->
                viewModel.saveTrackToPlaylist(t, playlistId)
                saveTrack = null
            },
            onCreatePlaylist = { name ->
                viewModel.createPlaylistAndAddTrack(name, t)
                saveTrack = null
            },
            onDismiss = { saveTrack = null },
        )
    }

    // Share from a queue row: VM-driven with the full DB track (see NowPlayingScreen).
    if (shareRequested) {
        shareTrack?.let { full ->
            ShareTrackSheet(
                track = full,
                onDismiss = {
                    shareRequested = false
                    viewModel.onShareDismissed()
                },
            )
        }
    }
}
