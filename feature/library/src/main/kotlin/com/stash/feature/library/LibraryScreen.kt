package com.stash.feature.library

import android.net.Uri
import kotlin.math.absoluteValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.components.NowPlayingJumpChip
import com.stash.core.ui.components.SourceIndicator
import com.stash.core.ui.components.TrackListItem
import com.stash.core.ui.components.VerticalScrollbar
import com.stash.core.ui.selection.RemoveDownloadsConfirmDialog
import com.stash.core.ui.components.fastScrollSectionLetter
import com.stash.core.ui.selection.SelectionAction
import com.stash.core.ui.selection.removeDownloadsNeedsConfirm
import com.stash.core.ui.selection.SelectionScaffoldOverlay
import com.stash.core.ui.selection.SelectionState
import com.stash.core.ui.theme.StashTheme
import com.stash.core.common.extensions.pluralize
import com.stash.core.ui.components.bottomChromePadding
import com.stash.feature.library.allsongs.AllSongsViewModel
import com.stash.feature.library.allsongs.OnThisDeviceSelectionBar
import com.stash.feature.library.allsongs.OnThisDeviceTab

/**
 * Library screen entry point. Injects the [LibraryViewModel] via Hilt
 * and delegates rendering to the stateless [LibraryContent] composable.
 *
 * Multi-select applies to the Tracks and Liked tabs. The [selection]
 * state is hoisted here so the contextual chrome ([SelectionScaffoldOverlay])
 * and the batch Save/Delete surfaces can sit in this screen's root Box. The
 * selection is cleared whenever the active tab changes so it can never strand
 * across tabs (or hide the nav bar while a non-selectable tab is showing).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    onNavigateToPlaylist: (Long) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToAlbum: (albumId: String, name: String, artUrl: String?, artistName: String) -> Unit = { _, _, _, _ -> },
    onNavigateToRemoteAlbum: (albumId: String, name: String, artUrl: String?, artistName: String) -> Unit = { _, _, _, _ -> },
    onSelectionModeChanged: (Boolean) -> Unit = {},
    // An empty library's ways in (see LibraryEmptyState): the Sync tab, the Search tab, and
    // Settings › Accounts to connect a service.
    onNavigateToSync: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToAccounts: () -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Bound to the search field directly. NOT state.searchQuery — that value
    // round-trips through the off-Main filter pipeline and arrives too late to
    // render the keystroke, which made typing drop characters (see
    // LibraryViewModel.searchQuery).
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val importState by viewModel.localImportState.collectAsStateWithLifecycle()
    val userPlaylists by viewModel.userPlaylists.collectAsStateWithLifecycle(initialValue = emptyList())
    val likedTracks by viewModel.likedTracks.collectAsStateWithLifecycle()
    val likedFilter by viewModel.likedFilter.collectAsStateWithLifecycle()
    val likedSources by viewModel.likedSources.collectAsStateWithLifecycle()

    // "On this device" (the folder scan): its own ViewModel, steered by this screen's search and sort.
    val deviceViewModel: AllSongsViewModel = hiltViewModel()
    LaunchedEffect(searchQuery) { deviceViewModel.setQuery(searchQuery) }
    LaunchedEffect(state.sortOrder) { deviceViewModel.setSortOrder(state.sortOrder) }

    // Home's Liked card queued a focus before the tab switch: land on Liked.
    // The tab is ALSO handed to the content pager — on a cold restore the
    // pager re-emits its SAVED page through the settled-page push-back and
    // would clobber this selectTab (device-reproduced); the pager must be
    // scrolled explicitly, not just the ViewModel state.
    var deepLinkFocusTab by remember { mutableStateOf<LibraryTab?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (viewModel.consumeDeepLinkFocus() == com.stash.core.data.navigation.LibraryFocus.LIKED) {
            deepLinkFocusTab = LibraryTab.LIKED
            viewModel.selectTab(LibraryTab.LIKED)
        }
    }

    // Multi-select state — Tracks and Liked tabs. `isActive` signals out so the
    // host can hide the mini-player (Task 7), and the selection is force-cleared
    // on every tab change so it can't leak onto Playlists/Artists/Albums.
    val selection = com.stash.core.ui.selection.rememberSelectionState()
    androidx.compose.runtime.LaunchedEffect(selection.isActive) { onSelectionModeChanged(selection.isActive) }
    androidx.compose.runtime.LaunchedEffect(state.activeTab) { selection.clear() }
    androidx.activity.compose.BackHandler(enabled = selection.isActive) { selection.clear() }

    // Batch-flow flags for the Save / Delete / FLAC-upgrade surfaces.
    var showBatchSave by remember { mutableStateOf(false) }
    var showBatchDelete by remember { mutableStateOf(false) }
    var showFlacConfirm by remember { mutableStateOf(false) }

    // Snackbar for the batch roll-up summaries.
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.userMessages.collect { snackbarHostState.showSnackbar(it) }
    }

    // A row's "Remove download" is staged in the ViewModel and deleted only when this Undo
    // snackbar goes away without Undo (LibraryViewModel.removeDownloadWithUndo). The snackbar's
    // own lifetime is the clock, so Undo is never offered for a file already gone: TalkBack
    // lengthens a snackbar that has an action, and the delete waits for it.
    val pendingRemoval by viewModel.pendingDownloadRemoval.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRemoval?.id) {
        val track = pendingRemoval ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "\"${track.title}\" removed from downloads",
            actionLabel = "Undo",
            duration = androidx.compose.material3.SnackbarDuration.Short,
        )
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
            viewModel.undoRemoveDownload(track.id)
        } else {
            viewModel.commitPendingDownloadRemoval(track.id)
        }
    }
    // Leaving Library (another tab, a detail screen, Back) takes the Undo with it: delete now.
    androidx.compose.runtime.DisposableEffect(viewModel) {
        onDispose { viewModel.commitPendingDownloadRemoval() }
    }

    LaunchedEffect(Unit) {
        viewModel.albumNavEvents.collect { a ->
            onNavigateToRemoteAlbum(a.albumId, a.name, a.artUrl, a.artistName)
        }
    }
    

    val followedPlaylistIds by viewModel.followedPlaylistIds.collectAsStateWithLifecycle()
    // Derived lists are remembered: this screen recomposes on every keystroke of the search.
    val playlistInfos = remember(userPlaylists) {
        userPlaylists.map { com.stash.core.ui.components.PlaylistInfo(it.id, it.name, it.trackCount) }
    }
    Box(modifier = modifier.fillMaxSize()) {
        LibraryContent(
            state = state,
            searchQuery = searchQuery,
            importState = importState,
            onShuffleLibrary = {
                if (state.streamingMode) viewModel.shuffleVisible(state.tracks) else viewModel.shuffleLibrary()
            },
            onShuffleLiked = viewModel::shuffleLiked,
            onShuffleDevice = deviceViewModel::shuffle,
            onTabSelected = viewModel::selectTab,
            onSearchQueryChanged = viewModel::setSearchQuery,
            onSortOrderChanged = viewModel::setSortOrder,
            onSourceFilterChanged = viewModel::setSourceFilter,
            onLibraryOnlyChanged = viewModel::setLibraryOnly,
            onTrackClick = { track -> viewModel.playTrack(track, state.tracks) },
            onPlayNext = viewModel::playNext,
            onAddToQueue = viewModel::addToQueue,
            onDeleteTrack = viewModel::deleteTrack,
            onPlayPlaylist = { playlist -> onNavigateToPlaylist(playlist.id) },
            onAddPlaylistToQueue = viewModel::addPlaylistToQueue,
            onRemovePlaylist = viewModel::removePlaylist,
            onDeletePlaylist = viewModel::deletePlaylist,
            isMirrored = viewModel::isMirrored,
            canDeleteEverywhere = viewModel::canDeleteEverywhere,
            followedPlaylistIds = followedPlaylistIds,
            onUnfollowPlaylist = viewModel::unfollowPlaylist,
            onSetPlaylistImage = viewModel::setPlaylistImage,
            onRemovePlaylistImage = viewModel::removePlaylistImage,
            onTogglePlaylistPinned = viewModel::togglePlaylistPinned,
            onTogglePlaylistOnHome = viewModel::togglePlaylistOnHome,
            onPlayArtist = onNavigateToArtist,
            onViewAlbum = viewModel::onViewAlbumTapped,
            onAddArtistToQueue = viewModel::addArtistToQueue,
            onDeleteArtist = viewModel::deleteArtist,
            onPlayAlbum = viewModel::playAlbum,
            onAddAlbumToQueue = viewModel::addAlbumToQueue,
            onOpenAlbum = { album ->
                onNavigateToAlbum(album.name, album.name, album.artUrl, album.artist)
            },
            onStartImport = viewModel::startLocalImport,
            onCancelImport = viewModel::cancelLocalImport,
            onDismissImport = viewModel::dismissLocalImport,
            selection = selection,
            // Single-track sheet actions reuse the batch VM methods with
            // one-element lists — no separate single-track VM surface.
            userPlaylists = playlistInfos,
            onSaveToPlaylist = { trackId, playlistId -> viewModel.saveSelectedToPlaylist(listOf(trackId), playlistId) },
            onCreatePlaylistWithTrack = { name, trackId -> viewModel.createPlaylistAndAddTracks(name, listOf(trackId)) },
            onDownloadTrack = { viewModel.downloadSelected(listOf(it)) },
            // Staged with Undo (see the snackbar above); the batch path confirms instead.
            onRemoveDownloadTrack = viewModel::removeDownloadWithUndo,
            likedTracks = likedTracks,
            likedFilter = likedFilter,
            likedSources = likedSources,
            onSelectLikedSource = viewModel::setLikedFilter,
            onPlayLikedTrack = viewModel::playLiked,
            deepLinkFocusTab = deepLinkFocusTab,
            onOpenSync = onNavigateToSync,
            onOpenSearch = onNavigateToSearch,
            onOpenAccounts = onNavigateToAccounts,
            deviceContent = {
                OnThisDeviceTab(
                    viewModel = deviceViewModel,
                    selection = selection,
                    alphabetical = state.sortOrder == SortOrder.ALPHABETICAL,
                    onClearSearch = { viewModel.setSearchQuery("") },
                )
            },
        )

        // ── Selection chrome — meaningful on the Tracks and Liked tabs. Selection
        // can only be entered from a TrackListItem (those tabs' rows), and we clear
        // on tab change, so guarding the overlay on activeTab is belt-and-braces.
        val activeSelectableTracks =
            if (state.activeTab == LibraryTab.LIKED) likedTracks else state.tracks
        val selectedTracks = activeSelectableTracks.filter { it.id in selection.selectedIds }
        val selectedIds = selection.selectedIds.toList()
        val allDownloaded = selectedTracks.isNotEmpty() && selectedTracks.all { it.isDownloaded }

        // In a Listen Together session "Add to queue" hides; Play next says what it does there (as in TrackOptionsSheet).
        val listenRole = com.stash.core.ui.components.LocalListenTogetherRole.current
        // Batch "Remove download" deletes files that can take hours to fetch again (a whole
        // Select all, one mis-tap from the confirmed Delete): confirm it first (shared dialog).
        var confirmRemoveDownloads by remember { mutableStateOf<List<Track>?>(null) }
        var confirmDownloadIds by remember { mutableStateOf<List<Long>?>(null) }
        confirmDownloadIds?.let { ids ->
            AlertDialog(
                onDismissRequest = { confirmDownloadIds = null },
                title = { Text("Download ${ids.size} songs?") },
                text = { Text("This queues ${ids.size} downloads and can use a lot of data and storage.") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.downloadSelected(ids); selection.clear(); confirmDownloadIds = null
                    }) { Text("Download") }
                },
                dismissButton = { TextButton(onClick = { confirmDownloadIds = null }) { Text("Cancel") } },
            )
        }
        confirmRemoveDownloads?.let { tracks ->
            RemoveDownloadsConfirmDialog(
                count = tracks.size,
                totalBytes = tracks.sumOf { it.fileSizeBytes },
                onConfirm = {
                    viewModel.removeDownloadsForSelected(tracks.map { it.id })
                    selection.clear()
                    confirmRemoveDownloads = null
                },
                onDismiss = { confirmRemoveDownloads = null },
            )
        }
        val selectionActions = listOfNotNull(
            SelectionAction("add_queue", "Add to queue", Icons.Default.PlaylistAdd) {
                viewModel.addSelectedToQueue(selectedTracks); selection.clear()
            }.takeIf { listenRole == com.stash.core.ui.components.ListenTogetherRole.NONE },
            SelectionAction("add_playlist", "Add to playlist", Icons.Default.PlaylistAddCheck) {
                showBatchSave = true
            },
            if (allDownloaded) {
                SelectionAction("remove_download", "Remove download", Icons.Default.DownloadDone) {
                    if (removeDownloadsNeedsConfirm(selectedTracks.size)) {
                        confirmRemoveDownloads = selectedTracks
                    } else {
                        viewModel.removeDownloadsForSelected(selectedIds); selection.clear()
                    }
                }
            } else {
                SelectionAction("download", "Download", Icons.Default.Download) {
                    // Only the rows that still need downloading, and a confirm past 100.
                    val ids = selectedTracks.filter { !it.isDownloaded }.map { it.id }
                    if (ids.size > 100) confirmDownloadIds = ids
                    else { viewModel.downloadSelected(ids); selection.clear() }
                }
            },
            SelectionAction("delete", "Delete", Icons.Default.Delete) {
                showBatchDelete = true
            },
            SelectionAction("play_next", com.stash.core.ui.components.playNextLabel(listenRole, "Play next"), Icons.Default.PlaylistPlay) {
                viewModel.playSelectedNext(selectedTracks); selection.clear()
            },
            // Lands in the selection bar's More overflow (6 > 4) — by design.
            SelectionAction("upgrade_flac", "Upgrade to FLAC", Icons.Default.HighQuality) {
                showFlacConfirm = true
            },
        )

        if (state.activeTab == LibraryTab.TRACKS || state.activeTab == LibraryTab.LIKED) {
            SelectionScaffoldOverlay(
                selection = selection,
                allIds = remember(activeSelectableTracks) { activeSelectableTracks.map { it.id } },
                actions = selectionActions,
            )
        }

        if (state.activeTab == LibraryTab.DEVICE) {
            OnThisDeviceSelectionBar(selection = selection, viewModel = deviceViewModel)
        }

        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = bottomChromePadding(16.dp)),
        ) { data -> androidx.compose.material3.Snackbar(snackbarData = data) }
    }

    // ── Batch Save to Playlist sheet (its own ModalBottomSheet) ────────────
    if (showBatchSave) {
        val batchIds = selection.selectedIds.toList()
        com.stash.core.ui.components.SaveToPlaylistSheet(
            playlists = userPlaylists.map {
                com.stash.core.ui.components.PlaylistInfo(it.id, it.name, it.trackCount)
            },
            onSaveToPlaylist = { playlistId ->
                viewModel.saveSelectedToPlaylist(batchIds, playlistId)
                showBatchSave = false
                selection.clear()
            },
            onCreatePlaylist = { name ->
                viewModel.createPlaylistAndAddTracks(name, batchIds)
                showBatchSave = false
                selection.clear()
            },
            onDismiss = { showBatchSave = false },
        )
    }

    // ── Batch delete confirmation dialog ──────────────────────────────────
    // Mirrors the single-track Library delete dialog (same "also block" toggle
    // and "Delete & Block" wording), pluralised across the current selection.
    if (showBatchDelete) {
        // Source from the active tab's list — state.tracks is downloaded-only,
        // so a Liked-tab selection holding undownloaded likes would under-count
        // and silently skip those rows if sourced from it.
        val batchTracks = (if (state.activeTab == LibraryTab.LIKED) likedTracks else state.tracks)
            .filter { it.id in selection.selectedIds }
        val n = batchTracks.size
        var alsoBlacklist by remember(showBatchDelete) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showBatchDelete = false },
            title = { Text("Delete $n song${if (n != 1) "s" else ""}?") },
            text = {
                Column {
                    Text(
                        "${n} song${if (n != 1) "s" else ""} will be removed from your " +
                            "${n} song${if (n != 1) "s" else ""} will be removed from your library" +
                            if (batchTracks.any { it.isDownloaded }) " and deleted from disk." else ".",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { alsoBlacklist = !alsoBlacklist },
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = alsoBlacklist,
                            onCheckedChange = { alsoBlacklist = it },
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Also block these songs from future syncs",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "Blocked songs never re-download. Unblock them in Settings later.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelected(batchTracks, alsoBlacklist)
                        showBatchDelete = false
                        selection.clear()
                    },
                ) {
                    Text(
                        text = if (alsoBlacklist) "Delete & Block" else "Delete",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDelete = false }) { Text("Cancel") }
            },
        )
    }

    // ── Upgrade-to-FLAC confirmation dialog (spec §3) ─────────────────────
    // Pre-flight honesty: eligible count, duration-based size estimate, and
    // a free-space heads-up. Warning only — never hard-blocks the confirm.
    if (showFlacConfirm) {
        val batchTracks = (if (state.activeTab == LibraryTab.LIKED) likedTracks else state.tracks)
            .filter { it.id in selection.selectedIds }
        val eligible = eligibleForFlacUpgrade(batchTracks)
        val skipped = batchTracks.size - eligible.size
        val bytes = estimateFlacBytes(eligible)
        val context = androidx.compose.ui.platform.LocalContext.current
        val freeBytes = remember {
            runCatching {
                android.os.StatFs(context.filesDir.path).availableBytes
            }.getOrDefault(Long.MAX_VALUE)
        }
        AlertDialog(
            onDismissRequest = { showFlacConfirm = false },
            title = { Text("Upgrade to FLAC?") },
            text = {
                Column {
                    Text(
                        "Upgrade ${eligible.size} ${if (eligible.size == 1) "track" else "tracks"} to FLAC? " +
                            "Roughly ~${formatGb(bytes)} of downloads. Originals are replaced." +
                            if (skipped > 0) {
                                "\n\n$skipped already-FLAC or not-downloaded " +
                                    "${if (skipped == 1) "track" else "tracks"} will be skipped."
                            } else {
                                ""
                            },
                    )
                    if (bytes > freeBytes * 8 / 10) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Heads up: that's close to (or beyond) your free space " +
                                "(${formatGb(freeBytes)} available).",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = eligible.isNotEmpty(),
                    onClick = {
                        viewModel.upgradeSelectedToFlac(eligible.map { it.id })
                        showFlacConfirm = false
                        selection.clear()
                    },
                ) { Text("Upgrade") }
            },
            dismissButton = {
                TextButton(onClick = { showFlacConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

// ── Stateless content composable ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryContent(
    state: LibraryUiState,
    /** Field-facing query — updates this frame; see LibraryViewModel.searchQuery. */
    searchQuery: String,
    importState: com.stash.data.download.files.LocalImportState,
    onShuffleLibrary: () -> Unit,
    onShuffleLiked: () -> Unit,
    onShuffleDevice: () -> Unit,
    onTabSelected: (LibraryTab) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSortOrderChanged: (SortOrder) -> Unit,
    onSourceFilterChanged: (SourceFilter) -> Unit,
    /** Artists and Albums' "In your library" choice (Stream only). */
    onLibraryOnlyChanged: (Boolean) -> Unit = {},
    onTrackClick: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onDeleteTrack: (Track, Boolean) -> Unit,
    onPlayPlaylist: (Playlist) -> Unit,
    onAddPlaylistToQueue: (Playlist) -> Unit,
    onRemovePlaylist: (Playlist) -> Unit,
    onDeletePlaylist: (Playlist, Boolean, Boolean) -> Unit,
    isMirrored: (Long) -> Boolean = { false },
    canDeleteEverywhere: (Long) -> Boolean = { false },
    followedPlaylistIds: Set<Long> = emptySet(),
    onUnfollowPlaylist: (Playlist) -> Unit = {},
    onSetPlaylistImage: (Long, Uri) -> Unit,
    onRemovePlaylistImage: (Long) -> Unit,
    onTogglePlaylistPinned: (Playlist) -> Unit,
    onTogglePlaylistOnHome: (Playlist) -> Unit,
    onViewAlbum: (Track) -> Unit,
    onPlayArtist: (String) -> Unit,
    onAddArtistToQueue: (String) -> Unit,
    onDeleteArtist: (String) -> Unit,
    onPlayAlbum: (String, String) -> Unit,
    onAddAlbumToQueue: (String, String) -> Unit,
    onOpenAlbum: (AlbumInfo) -> Unit,
    onStartImport: (List<Uri>) -> Unit,
    onCancelImport: () -> Unit,
    onDismissImport: () -> Unit,
    selection: SelectionState,
    userPlaylists: List<com.stash.core.ui.components.PlaylistInfo>,
    onSaveToPlaylist: (trackId: Long, playlistId: Long) -> Unit,
    onCreatePlaylistWithTrack: (name: String, trackId: Long) -> Unit,
    onDownloadTrack: (Long) -> Unit,
    onRemoveDownloadTrack: (Track) -> Unit,
    likedTracks: List<Track>,
    likedFilter: LikedFilter,
    likedSources: Set<LikedFilter>,
    onSelectLikedSource: (LikedFilter) -> Unit,
    onPlayLikedTrack: (Track) -> Unit,
    deepLinkFocusTab: LibraryTab? = null,
    onOpenSync: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenAccounts: () -> Unit = {},
    /** The "On this device" page (the folder scan); see OnThisDeviceTab. */
    deviceContent: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 16.dp),
    ) {
        // -- Heading + Import action --
        // SAF audio picker launched from the "Add tracks" icon button in
        // the heading row. Returns a List<Uri> that we hand directly to
        // the coordinator; no persistable permission needed because we
        // copy the bytes immediately at import time.
        val importPicker = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris: List<Uri>? ->
            if (!uris.isNullOrEmpty()) onStartImport(uris)
        }
        // Opens already showing a query the ViewModel kept (back on this tab, or after a
        // rotation): a hidden field over a still-filtered list was a dead end.
        var searchOpen by remember { mutableStateOf(searchQuery.isNotEmpty()) }
        var sortFilterOpen by remember { mutableStateOf(false) }

        // Recent-downloads rail — the scrolling leading content of the Songs
        // landing (scrolls away while the compact header + category chips stay
        // pinned). The old purple shuffle hero is gone — it duplicated the
        // header's shuffle action.
        // Songs landing only — noise above the other grids. Handed ONLY to
        // TracksTab: with the pager, neighbor pages compose mid-swipe, so
        // gating on activeTab here would flash the rail onto other pages.
        val libraryHeader: @Composable () -> Unit = {
            Column {
                if (state.recentlyAdded.isNotEmpty()) {
                    RecentlyDownloadedRail(
                        tracks = state.recentlyAdded.take(12),
                        onTrackClick = onTrackClick,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // "On this device" opens from the header's disk icon, not a chip: it's a different place
        // (files in folders you picked), not another view of the library. The disk icon (or Back)
        // returns to the library page you came from.
        val deviceTabActive = state.activeTab == LibraryTab.DEVICE
        var lastLibraryTab by rememberSaveable { mutableStateOf(LibraryTab.TRACKS) }
        LaunchedEffect(state.activeTab) {
            if (state.activeTab != LibraryTab.DEVICE) lastLibraryTab = state.activeTab
        }
        androidx.activity.compose.BackHandler(enabled = deviceTabActive && !selection.isActive) {
            onTabSelected(lastLibraryTab)
        }

        // Compact header: On this device, shuffle, Import (+), search, and sort/filter icons, on the
        // right. No "Library" title: the tab bar already says where you are.
        val chrome = LocalAlbumChrome.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            // Shuffle stays in the always-visible header so it's reachable even
            // once the hero has scrolled away. It shuffles what you're looking
            // at: the liked list on the Liked tab (issue #402 — it used to
            // shuffle the whole library there), the whole library elsewhere.
            val likedTabActive = state.activeTab == LibraryTab.LIKED
            IconButton(
                onClick = { onTabSelected(if (deviceTabActive) lastLibraryTab else LibraryTab.DEVICE) },
                modifier = Modifier.semantics { this.selected = deviceTabActive },
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = if (deviceTabActive) {
                                chrome.accent.copy(alpha = 0.18f)
                            } else {
                                Color.Transparent
                            },
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Save,
                        contentDescription = "On this device",
                        tint = if (deviceTabActive) {
                            chrome.accent
                        } else {
                            LocalContentColor.current
                        },
                    )
                }
            }
            IconButton(
                onClick = when {
                    likedTabActive -> onShuffleLiked
                    deviceTabActive -> onShuffleDevice
                    else -> onShuffleLibrary
                },
            ) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = when {
                        likedTabActive -> "Shuffle liked songs"
                        deviceTabActive -> "Shuffle songs on this device"
                        else -> "Shuffle library"
                    },
                    tint = chrome.accent,
                )
            }
            IconButton(onClick = { importPicker.launch(arrayOf("audio/*")) }) {
                Icon(Icons.Filled.Add, contentDescription = "Import tracks")
            }
            // Closing the search clears it: it used to only hide the field, leaving every tab
            // silently filtered by a query the user could no longer see.
            IconButton(onClick = {
                if (searchOpen) onSearchQueryChanged("")
                searchOpen = !searchOpen
            }) {
                Icon(Icons.Filled.Search, contentDescription = "Search library")
            }
            IconButton(onClick = { sortFilterOpen = true }) {
                Icon(Icons.Filled.Tune, contentDescription = "Sort and filter")
            }
        }

        // -- Import progress strip (only when Running / Done / Error) --
        LocalImportStrip(
            state = importState,
            onCancel = onCancelImport,
            onDismiss = onDismissImport,
        )

        // -- Inline search (toggled by the header 🔍) — no permanent bar --
        if (searchOpen) {
            GlassSearchBar(
                query = searchQuery,
                onQueryChange = onSearchQueryChanged,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        // An empty tab's "Show everything" (see noMatchMessage). The source filter only narrows
        // Songs, so only Songs clears it.
        val clearSearch: () -> Unit = {
            onSearchQueryChanged("")
            searchOpen = false
        }
        val clearSearchAndFilter: () -> Unit = {
            clearSearch()
            onSourceFilterChanged(SourceFilter.ALL)
        }
        // Artists and Albums: "Show everything" also drops "In your library".
        val clearSearchAndLibraryOnly: () -> Unit = {
            clearSearch()
            if (state.libraryOnly) onLibraryOnlyChanged(false)
        }
        val artistsOrAlbums = state.activeTab == LibraryTab.ARTISTS || state.activeTab == LibraryTab.ALBUMS

        // -- Active source filter, on the tab it narrows. It's persisted and re-applied every
        // launch, and since the chip rows moved into the sheet nothing on screen showed it: a
        // FLAC filter from days ago left Songs at 40 of 5,000 with no hint why. Tap to clear.
        val activeFilterLabel = sourceFilterLabel(state.sourceFilter)
        if (activeFilterLabel != null && state.activeTab == LibraryTab.TRACKS) {
            ActiveFilterChip(
                label = "Showing $activeFilterLabel only",
                onClear = { onSourceFilterChanged(SourceFilter.ALL) },
            )
        }
        // -- "In your library" on Artists and Albums, persisted the same way: shown while it narrows.
        if (state.libraryOnly && artistsOrAlbums) {
            ActiveFilterChip(label = "Showing your library only", onClear = { onLibraryOnlyChanged(false) })
        }

        // -- Category chips (pinned): Songs / Playlists / Artists / Albums. None is lit while On
        // this device (the header's disk icon) is open; a chip tap goes back to the library.
        val chipTabs = listOf(
            "Songs" to LibraryTab.TRACKS,
            "Liked" to LibraryTab.LIKED,
            "Playlists" to LibraryTab.PLAYLISTS,
            "Artists" to LibraryTab.ARTISTS,
            "Albums" to LibraryTab.ALBUMS,
        )
        com.stash.core.ui.components.CrispChipRow(
            chips = chipTabs.map { it.first },
            selected = chipTabs.firstOrNull { it.second == state.activeTab }?.first.orEmpty(),
            onSelect = { label -> onTabSelected(chipTabs.first { it.first == label }.second) },
            modifier = Modifier.padding(vertical = 4.dp),
        )

        Spacer(modifier = Modifier.height(4.dp))

        // -- Sort & filter sheet (toggled by the header ⇅) --
        if (sortFilterOpen) {
            LibrarySortFilterSheet(
                sortOrder = state.sortOrder,
                sourceFilter = state.sourceFilter,
                showDuration = state.activeTab == LibraryTab.TRACKS || state.activeTab == LibraryTab.DEVICE,
                // The source filter narrows Songs only; offered elsewhere it looked broken.
                showSourceFilter = state.activeTab == LibraryTab.TRACKS,
                showAvailabilityFilter = state.streamingMode,
                // Download lists only downloaded songs, all in the library already.
                showLibraryOnly = state.streamingMode && artistsOrAlbums,
                libraryOnly = state.libraryOnly,
                onLibraryOnlySelected = onLibraryOnlyChanged,
                // Scanned songs have no play counts.
                showMostPlayed = state.activeTab != LibraryTab.DEVICE,
                onSortSelected = onSortOrderChanged,
                onFilterSelected = onSourceFilterChanged,
                onDismiss = { sortFilterOpen = false },
            )
        }

        // -- Content area --
        val anyServiceConnected = state.spotifyConnected || state.youTubeConnected
        if (deviceTabActive) {
            // Outside the pager: swiping is for the library's own pages.
            Box(modifier = Modifier.fillMaxSize().weight(1f)) { deviceContent() }
        } else if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            // Swiping left/right moves between the category pages too — the
            // pager and the chip row drive the same activeTab state: a chip
            // tap animates the pager here; a settled swipe pushes the tab
            // back to the ViewModel (both sync no-ops when already aligned).
            val pagerState = rememberPagerState(
                initialPage = chipTabs.indexOfFirst { it.second == state.activeTab }.coerceAtLeast(0),
            ) { chipTabs.size }
            LaunchedEffect(state.activeTab) {
                val idx = chipTabs.indexOfFirst { it.second == state.activeTab }
                if (idx >= 0 && idx != pagerState.currentPage) pagerState.animateScrollToPage(idx)
            }
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }.collect { page ->
                    onTabSelected(chipTabs[page].second)
                }
            }
            // Deep link (Home's Liked card) must beat the restored pager
            // page: on a cold restore the push-back above re-emits the SAVED
            // page and overwrites the deep-link's selectTab. Scrolling the
            // pager directly is deterministic — the settled emission then
            // pushes the same tab back, self-consistently.
            LaunchedEffect(deepLinkFocusTab) {
                val idx = chipTabs.indexOfFirst { it.second == deepLinkFocusTab }
                if (idx >= 0) pagerState.scrollToPage(idx)
            }
            // A–Z order: every page's scrollbar shows a letter bubble while it's dragged.
            val alphabetical = state.sortOrder == SortOrder.ALPHABETICAL
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (chipTabs[page].second) {
                    LibraryTab.PLAYLISTS -> PlaylistsGrid(
                        playlists = state.playlists,
                        anyServiceConnected = anyServiceConnected,
                        onPlayPlaylist = onPlayPlaylist,
                        onAddPlaylistToQueue = onAddPlaylistToQueue,
                        onRemovePlaylist = onRemovePlaylist,
                        onDeletePlaylist = onDeletePlaylist,
                        isMirrored = isMirrored,
                        canDeleteEverywhere = canDeleteEverywhere,
                        followedPlaylistIds = followedPlaylistIds,
                        onUnfollowPlaylist = onUnfollowPlaylist,
                        onSetPlaylistImage = onSetPlaylistImage,
                        onRemovePlaylistImage = onRemovePlaylistImage,
                        onTogglePlaylistPinned = onTogglePlaylistPinned,
                        onTogglePlaylistOnHome = onTogglePlaylistOnHome,
                        header = {},
                        noMatch = noMatchMessage("playlists", state.searchQuery),
                        onClearFilters = clearSearch,
                        alphabetical = alphabetical,
                    )
                    LibraryTab.TRACKS -> TracksTab(
                        tracks = state.tracks,
                        currentlyPlayingTrackId = state.currentlyPlayingTrackId,
                        onTrackClick = onTrackClick,
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onDeleteTrack = onDeleteTrack,
                        onViewAlbum = onViewAlbum,
                        anyServiceConnected = anyServiceConnected,
                        selection = selection,
                        userPlaylists = userPlaylists,
                        onSaveToPlaylist = onSaveToPlaylist,
                        onCreatePlaylistWithTrack = onCreatePlaylistWithTrack,
                        onDownloadTrack = onDownloadTrack,
                        onRemoveDownloadTrack = onRemoveDownloadTrack,
                        header = libraryHeader,
                        noMatch = noMatchMessage("songs", state.searchQuery, state.sourceFilter),
                        onClearFilters = clearSearchAndFilter,
                        alphabetical = alphabetical,
                        onImport = { importPicker.launch(arrayOf("audio/*")) },
                        onOpenSync = onOpenSync,
                        onOpenSearch = onOpenSearch,
                        onOpenAccounts = onOpenAccounts,
                    )
                    LibraryTab.LIKED -> LikedTab(
                        tracks = likedTracks,
                        filter = likedFilter,
                        sources = likedSources,
                        currentlyPlayingTrackId = state.currentlyPlayingTrackId,
                        onSelectSource = onSelectLikedSource,
                        onTrackClick = onPlayLikedTrack,
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onDeleteTrack = onDeleteTrack,
                        selection = selection,
                        userPlaylists = userPlaylists,
                        onSaveToPlaylist = onSaveToPlaylist,
                        onCreatePlaylistWithTrack = onCreatePlaylistWithTrack,
                        onDownloadTrack = onDownloadTrack,
                        onRemoveDownloadTrack = onRemoveDownloadTrack,
                        noMatch = noMatchMessage("liked songs", state.searchQuery),
                        onClearFilters = clearSearch,
                        alphabetical = alphabetical,
                    )
                    LibraryTab.ARTISTS -> ArtistsGrid(
                        artists = state.artists,
                        singleTrackArtists = state.singleTrackArtists,
                        anyServiceConnected = anyServiceConnected,
                        onPlayArtist = onPlayArtist,
                        onAddArtistToQueue = onAddArtistToQueue,
                        onDeleteArtist = onDeleteArtist,
                        header = {},
                        noMatch = noMatchMessage("artists", state.searchQuery),
                        onClearFilters = clearSearchAndLibraryOnly,
                        alphabetical = alphabetical,
                    )
                    LibraryTab.ALBUMS -> AlbumsGrid(
                        albums = state.albums,
                        singleTrackAlbums = state.singleTrackAlbums,
                        anyServiceConnected = anyServiceConnected,
                        onPlayAlbum = onPlayAlbum,
                        onAddAlbumToQueue = onAddAlbumToQueue,
                        onOpenAlbum = onOpenAlbum,
                        header = {},
                        noMatch = noMatchMessage("albums", state.searchQuery),
                        onClearFilters = clearSearchAndLibraryOnly,
                        alphabetical = alphabetical,
                    )
                    LibraryTab.DEVICE -> Unit // shown above, outside the pager
                }
            }
        }
    }
}

// ── Recently downloaded rail ─────────────────────────────────────────────────

/** A persisted filter that narrows the open tab, said on screen: tap it (or its close icon) to clear. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveFilterChip(label: String, onClear: () -> Unit) {
    androidx.compose.material3.InputChip(
        selected = true,
        onClick = onClear,
        label = { Text(label) },
        trailingIcon = {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Clear filter",
                modifier = Modifier.size(18.dp),
            )
        },
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

@Composable
private fun RecentlyDownloadedRail(
    tracks: List<Track>,
    onTrackClick: (Track) -> Unit,
) {
    Column {
        Text(
            text = "RECENTLY DOWNLOADED",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(tracks, key = { it.id }) { track ->
                com.stash.core.ui.components.AlbumSquareCard(
                    title = track.title,
                    artist = track.artist,
                    thumbnailUrl = track.albumArtUrl,
                    year = null,
                    isLossless = track.fileFormat.equals("flac", ignoreCase = true),
                    onClick = { onTrackClick(track) },
                )
            }
        }
    }
}

// ── Liked subcategory (browse + sift likes by origin) ───────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LikedTab(
    tracks: List<Track>,
    filter: LikedFilter,
    sources: Set<LikedFilter>,
    currentlyPlayingTrackId: Long?,
    onSelectSource: (LikedFilter) -> Unit,
    onTrackClick: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onDeleteTrack: (Track, Boolean) -> Unit,
    selection: SelectionState,
    userPlaylists: List<com.stash.core.ui.components.PlaylistInfo>,
    onSaveToPlaylist: (trackId: Long, playlistId: Long) -> Unit,
    onCreatePlaylistWithTrack: (name: String, trackId: Long) -> Unit,
    onDownloadTrack: (Long) -> Unit,
    onRemoveDownloadTrack: (Track) -> Unit,
    /** Empty-state line when the search emptied the list (see [noMatchMessage]). */
    noMatch: String? = null,
    onClearFilters: () -> Unit = {},
    /** Sorted A–Z: the scrollbar shows a letter bubble while dragged. */
    alphabetical: Boolean = false,
) {
    // Sift chips: "All" plus only the origins that actually have likes.
    val sift = buildList {
        add("All" to LikedFilter.ALL)
        if (LikedFilter.STASH in sources) add("Stash" to LikedFilter.STASH)
        if (LikedFilter.SPOTIFY in sources) add("Spotify" to LikedFilter.SPOTIFY)
        if (LikedFilter.YOUTUBE in sources) add("YouTube" to LikedFilter.YOUTUBE)
    }

    // Track selected for the context-menu bottom sheet (opened via the ⋮ now;
    // long-press enters multi-select instead).
    var selectedTrack by remember { mutableStateOf<Track?>(null) }
    // Track whose share sheet is open (fork issue ParaliyzedEvo/Stash#40).
    var trackToShare by remember { mutableStateOf<Track?>(null) }
    trackToShare?.let { t ->
        com.stash.core.ui.components.ShareTrackSheet(
            track = t,
            onDismiss = { trackToShare = null },
        )
    }
    // Track whose Save-to-Playlist sheet is open (single-track path).
    var trackToSave by remember { mutableStateOf<Track?>(null) }
    // Track pending delete confirmation.
    var trackToDelete by remember { mutableStateOf<Track?>(null) }

    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        // The rows share one width reading and text measurer (TrackListItem's fit).
        com.stash.core.ui.components.TrackRowFitScope {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = bottomChromePadding(if (selection.isActive) 140.dp else 0.dp)),
            ) {
                // Only offer the sift when there's more than one origin to pick between.
                if (sift.size > 2) {
                    item {
                        com.stash.core.ui.components.CrispChipRow(
                            chips = sift.map { it.first },
                            selected = sift.firstOrNull { it.second == filter }?.first ?: "All",
                            onSelect = { label -> onSelectSource(sift.first { it.first == label }.second) },
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                if (tracks.isEmpty()) {
                    item {
                        EmptyTabMessage(
                            noMatch = noMatch,
                            fallback = "No liked songs yet — tap the heart on a track to like it",
                            onClear = onClearFilters,
                        )
                    }
                }
                items(tracks, key = { it.id }) { track ->
                    TrackListItem(
                        track = track,
                        onClick = {
                            if (selection.isActive) selection.toggle(track.id)
                            else onTrackClick(track)
                        },
                        isPlaying = track.id == currentlyPlayingTrackId,
                        onLongPress = { if (!selection.isActive) selection.enter(track.id) },
                        selectionActive = selection.isActive,
                        selected = selection.isSelected(track.id),
                        onMoreClick = { selectedTrack = track },
                    )
                }
            }
        }
        VerticalScrollbar(
            state = listState,
            // The sift row (when shown) is the one item above the likes.
            sectionLabel = if (alphabetical) {
                alphabeticalSectionLabel(tracks, leadingItems = if (sift.size > 2) 1 else 0) { it.title }
            } else {
                null
            },
        )
        // "Now playing ↑/↓" while the playing like is scrolled out of sight.
        NowPlayingJumpChip(
            listState = listState,
            trackIndex = remember(tracks, currentlyPlayingTrackId) {
                tracks.indexOfFirst { it.id == currentlyPlayingTrackId }
            },
            trackCount = tracks.size,
            enabled = !selection.isActive,
        )
    }

    // ── Context-menu bottom sheet (shared component) ────────────────────
    selectedTrack?.let { track ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { selectedTrack = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            com.stash.core.ui.components.TrackOptionsSheet(
                track = track,
                onPlayNext = { onPlayNext(it); selectedTrack = null },
                onAddToQueue = { onAddToQueue(it); selectedTrack = null },
                onSaveToPlaylist = { trackToSave = it; selectedTrack = null },
                onDismiss = { selectedTrack = null },
                onShare = { trackToShare = it; selectedTrack = null },
                onDownload = { onDownloadTrack(it.id); selectedTrack = null },
                onRemoveDownload = { onRemoveDownloadTrack(it); selectedTrack = null },
                onDelete = { trackToDelete = it; selectedTrack = null },
            )
        }
    }

    // ── Save to Playlist sheet (single-track) ───────────────────────────
    trackToSave?.let { track ->
        com.stash.core.ui.components.SaveToPlaylistSheet(
            playlists = userPlaylists,
            onSaveToPlaylist = { playlistId ->
                onSaveToPlaylist(track.id, playlistId)
                trackToSave = null
            },
            onCreatePlaylist = { name ->
                onCreatePlaylistWithTrack(name, track.id)
                trackToSave = null
            },
            onDismiss = { trackToSave = null },
        )
    }

    // ── Delete confirmation dialog ──────────────────────────────────────
    trackToDelete?.let { track ->
        var alsoBlacklist by remember(track.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { trackToDelete = null },
            title = { Text("Delete \"${track.title}\"?") },
            text = {
                Column {
                    Text(
                        "\"${track.title}\" by ${track.artist} will be removed from " +
                            if (track.isDownloaded) "your library and deleted from disk."
                            else "your library.",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { alsoBlacklist = !alsoBlacklist },
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = alsoBlacklist,
                            onCheckedChange = { alsoBlacklist = it },
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Also block this song from future syncs",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "Blocked songs never re-download. Unblock them in Settings later.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteTrack(track, alsoBlacklist)
                        trackToDelete = null
                    },
                ) {
                    Text(
                        text = if (alsoBlacklist) "Delete & Block" else "Delete",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { trackToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

// ── Search bar ───────────────────────────────────────────────────────────────

@Composable
private fun GlassSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        color = extendedColors.glassBackground,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    "Search library...",
                    color = extendedColors.textTertiary,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = extendedColors.textTertiary,
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear search",
                            tint = extendedColors.textTertiary,
                        )
                    }
                }
            },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── Local import progress strip ─────────────────────────────────────────────

/**
 * Compact banner that appears below the Library header while
 * [LocalImportState] is non-Idle. Shows progress during a batch import,
 * a summary after Done, or an error + dismiss after Error. Hides
 * completely when [state] is [LocalImportState.Idle].
 */
@Composable
private fun LocalImportStrip(
    state: com.stash.data.download.files.LocalImportState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        com.stash.data.download.files.LocalImportState.Idle -> Unit
        is com.stash.data.download.files.LocalImportState.Running -> {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Importing ${state.current} of ${state.total}\u2026",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    androidx.compose.material3.TextButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                }
                androidx.compose.material3.LinearProgressIndicator(
                    progress = {
                        if (state.total == 0) 0f
                        else state.current.toFloat() / state.total.toFloat()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        is com.stash.data.download.files.LocalImportState.Done -> {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = buildString {
                        append("Imported ${state.imported} track")
                        if (state.imported != 1) append("s")
                        if (state.failed > 0) append(" \u2022 ${state.failed} failed")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
        is com.stash.data.download.files.LocalImportState.Error -> {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Import failed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }
}

// ── Playlists tab (2-column grid) ────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistsGrid(
    playlists: List<Playlist>,
    anyServiceConnected: Boolean,
    onPlayPlaylist: (Playlist) -> Unit,
    onAddPlaylistToQueue: (Playlist) -> Unit,
    onRemovePlaylist: (Playlist) -> Unit,
    onDeletePlaylist: (Playlist, Boolean, Boolean) -> Unit,
    isMirrored: (Long) -> Boolean = { false },
    canDeleteEverywhere: (Long) -> Boolean = { false },
    followedPlaylistIds: Set<Long> = emptySet(),
    onUnfollowPlaylist: (Playlist) -> Unit = {},
    onSetPlaylistImage: (Long, Uri) -> Unit,
    onRemovePlaylistImage: (Long) -> Unit,
    onTogglePlaylistPinned: (Playlist) -> Unit,
    onTogglePlaylistOnHome: (Playlist) -> Unit,
    header: @Composable () -> Unit = {},
    /** Empty-state line when the search emptied the grid (see [noMatchMessage]). */
    noMatch: String? = null,
    onClearFilters: () -> Unit = {},
    /** Sorted A–Z: the scrollbar shows a letter bubble while dragged. */
    alphabetical: Boolean = false,
) {
    // Playlist selected for the context-menu bottom sheet.
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    // Playlist whose share sheet is open.
    // Held as an id so the sheet survives rotation; the Playlist is looked up from the list shown.
    var sharePlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Playlist pending delete confirmation.
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    // Playlist pending remove confirmation (the list goes; its songs stay).
    var playlistToRemove by remember { mutableStateOf<Playlist?>(null) }
    // Playlist awaiting image picker result.
    var playlistForImagePick by remember { mutableStateOf<Playlist?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null && playlistForImagePick != null) {
            onSetPlaylistImage(playlistForImagePick!!.id, uri)
        }
        playlistForImagePick = null
    }

    val gridState = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = bottomChromePadding(8.dp)),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Full-width leading header (Shuffle hero + recent rail, Songs-tab only).
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                header()
            }
            if (playlists.isEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    EmptyTabMessage(
                        noMatch = noMatch,
                        fallback = if (anyServiceConnected) "Sync your playlists to see them here"
                        else "Connect a service in Settings to see your playlists",
                        onClear = onClearFilters,
                    )
                }
            }
            items(playlists, key = { it.id }) { playlist ->
                if (playlist.artUrl != null) {
                    // Playlist with artwork: image background + dark overlay
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .combinedClickable(
                                onClick = { onPlayPlaylist(playlist) },
                                onLongClick = { selectedPlaylist = playlist },
                            ),
                    ) {
                        AsyncImage(
                            model = playlist.artUrl,
                            contentDescription = "${playlist.name} artwork",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        // Dark scrim overlay for text legibility
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.7f),
                                        ),
                                        startY = 40f,
                                    ),
                                ),
                        )
                        // Text pinned to bottom
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SourceIndicator(source = playlist.source)
                                Text(
                                    text = pluralize(playlist.trackCount, "track"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.8f),
                                )
                            }
                        }
                        // Inside the artwork Box, so TopEnd is the card's corner. It used to sit
                        // after the Box, straight in the grid item, where align() resolved to an
                        // outer BoxScope, did nothing, and the pin landed at the cell's top-start.
                        if (playlist.pinned) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = "Pinned",
                                tint = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .size(16.dp),
                            )
                        }
                    }
                } else {
                    // No artwork: keep the original GlassCard look
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onPlayPlaylist(playlist) },
                                onLongClick = { selectedPlaylist = playlist },
                            ),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // A pinned playlist without art showed no pin at all.
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (playlist.pinned) {
                                    Icon(
                                        imageVector = Icons.Default.PushPin,
                                        contentDescription = "Pinned",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .padding(start = 4.dp)
                                            .size(16.dp),
                                    )
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SourceIndicator(source = playlist.source)
                                Text(
                                    text = pluralize(playlist.trackCount, "track"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        VerticalScrollbar(
            state = gridState,
            // Pinned playlists lead (A–Z among themselves), so the letters run A–Z twice:
            // each bubble is still the letter of the playlist under it.
            sectionLabel = if (alphabetical) {
                alphabeticalSectionLabel(playlists, leadingItems = 1) { it.name }
            } else {
                null
            },
        )
    }

    // ── Context-menu bottom sheet ───────────────────────────────────────
    selectedPlaylist?.let { playlist ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { selectedPlaylist = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            // Header: playlist name + track count
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp),
            ) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluralize(playlist.trackCount, "track"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            BottomSheetActionRow(
                icon = if (playlist.pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                label = if (playlist.pinned) "Unpin Playlist" else "Pin Playlist",
                onClick = {
                    onTogglePlaylistPinned(playlist)
                    selectedPlaylist = null
                },
            )

            BottomSheetActionRow(
                icon = if (playlist.pinnedToHomeAt != null) Icons.Filled.Home else Icons.Outlined.Home,
                label = if (playlist.pinnedToHomeAt != null) "Remove from Home" else "Show on Home",
                onClick = {
                    onTogglePlaylistOnHome(playlist)
                    selectedPlaylist = null
                },
            )

            BottomSheetActionRow(
                icon = Icons.Default.PlayArrow,
                label = "Play All",
                onClick = {
                    onPlayPlaylist(playlist)
                    selectedPlaylist = null
                },
            )
            BottomSheetActionRow(
                icon = Icons.Default.PlaylistAdd,
                label = com.stash.core.ui.components.playNextLabel(com.stash.core.ui.components.LocalListenTogetherRole.current, "Add to Queue"), // session: queue or suggest
                onClick = {
                    onAddPlaylistToQueue(playlist)
                    selectedPlaylist = null
                },
            )
            BottomSheetActionRow(
                icon = Icons.Default.Share,
                label = "Share mix",
                onClick = {
                    sharePlaylistId = playlist.id
                    selectedPlaylist = null
                },
            )
            // An active follow is read-only (spec §6): no image edits, and Unfollow replaces Remove/Delete.
            val followed = playlist.id in followedPlaylistIds
            // Image options — only for custom playlists
            if (playlist.type == PlaylistType.CUSTOM && !followed) {
                BottomSheetActionRow(
                    icon = Icons.Default.Image,
                    label = if (playlist.artUrl != null) "Change Image" else "Add Image",
                    onClick = {
                        playlistForImagePick = playlist
                        selectedPlaylist = null
                        imagePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                )

                // Remove Image — only shown when a custom image is set
                if (playlist.artUrl != null) {
                    BottomSheetActionRow(
                        icon = Icons.Default.ImageNotSupported,
                        label = "Remove Image",
                        onClick = {
                            onRemovePlaylistImage(playlist.id)
                            selectedPlaylist = null
                        },
                    )
                }
            }

            if (followed) {
                // Unfollow removes the playlist (and can take its downloads): second tap confirms.
                var confirmUnfollow by remember(playlist.id) { mutableStateOf(false) }
                BottomSheetActionRow(
                    icon = Icons.Default.RemoveCircleOutline,
                    label = if (confirmUnfollow) "Tap again to unfollow" else "Unfollow",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = {
                        if (confirmUnfollow) {
                            onUnfollowPlaylist(playlist)
                            selectedPlaylist = null
                        } else {
                            confirmUnfollow = true
                        }
                    },
                )
            } else {
                BottomSheetActionRow(
                    icon = Icons.Default.RemoveCircleOutline,
                    label = "Remove Playlist",
                    // Confirmed like its neighbours (Delete's dialog, Unfollow's second tap): a
                    // removed custom playlist can't be brought back, and its shared link dies too.
                    onClick = {
                        playlistToRemove = playlist
                        selectedPlaylist = null
                    },
                )
                BottomSheetActionRow(
                    icon = Icons.Default.Delete,
                    label = "Delete Playlist & Songs",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = {
                        playlistToDelete = playlist
                        selectedPlaylist = null
                    },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    sharePlaylistId?.let { id -> playlists.firstOrNull { it.id == id } }?.let {
        com.stash.feature.library.share.ShareMixSheet(it.id, it.name, it.trackCount, onDismiss = { sharePlaylistId = null })
    }

    // ── Remove confirmation dialog ──────────────────────────────────────
    playlistToRemove?.let { playlist ->
        AlertDialog(
            onDismissRequest = { playlistToRemove = null },
            title = { Text("Remove \"${playlist.name}\"?") },
            text = {
                Text(
                    "The playlist is removed from your library; its songs stay. " +
                        "If you shared it, its link stops working for everyone who follows it. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemovePlaylist(playlist)
                        playlistToRemove = null
                    },
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToRemove = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    // ── Delete confirmation dialog ──────────────────────────────────────
    playlistToDelete?.let { playlist ->
        var alsoBlacklist by remember(playlist.id) { mutableStateOf(false) }
        // A playlist that mirrors with Stash on the web (spec §2.4): delete it everywhere, or only here (it stops mirroring).
        val mirrored = remember(playlist.id) { isMirrored(playlist.id) }
        // "Everywhere" only when this phone sends playlists and has joined them, or for a read-only one (as the web; R-S4).
        val offerEverywhere = remember(playlist.id) { canDeleteEverywhere(playlist.id) }
        var everywhere by remember(playlist.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Delete \"${playlist.name}\"?") },
            text = {
                Column {
                    Text(
                        "\"${playlist.name}\" is removed, and its songs are deleted from your device, " +
                            "except songs that are also in Liked Songs or another playlist. " +
                            "This cannot be undone.",
                    )
                    if (mirrored && !offerEverywhere) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "It stops mirroring here; your other devices keep their copy.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (mirrored && offerEverywhere) {
                        Spacer(modifier = Modifier.height(12.dp))
                        listOf(true to "Everywhere — on all your linked devices", false to "Only here — and stop mirroring it").forEach { (all, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { everywhere = all },
                            ) {
                                androidx.compose.material3.RadioButton(selected = everywhere == all, onClick = { everywhere = all })
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { alsoBlacklist = !alsoBlacklist },
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = alsoBlacklist,
                            onCheckedChange = { alsoBlacklist = it },
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Also block these songs from future syncs",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "Blocked songs never re-download. Unblock them in Settings later.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePlaylist(playlist, alsoBlacklist, everywhere)
                        playlistToDelete = null
                    },
                ) {
                    Text(
                        text = if (alsoBlacklist) "Delete & Block" else "Delete",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

// ── Tracks tab (lazy column of TrackListItems) ──────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TracksTab(
    tracks: List<Track>,
    currentlyPlayingTrackId: Long?,
    onTrackClick: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onDeleteTrack: (Track, Boolean) -> Unit,
    onViewAlbum: (Track) -> Unit,
    anyServiceConnected: Boolean,
    selection: SelectionState,
    userPlaylists: List<com.stash.core.ui.components.PlaylistInfo>,
    onSaveToPlaylist: (trackId: Long, playlistId: Long) -> Unit,
    onCreatePlaylistWithTrack: (name: String, trackId: Long) -> Unit,
    onDownloadTrack: (Long) -> Unit,
    onRemoveDownloadTrack: (Track) -> Unit,
    header: @Composable () -> Unit = {},
    /** Empty-state line when the search or source filter emptied the list (see [noMatchMessage]). */
    noMatch: String? = null,
    onClearFilters: () -> Unit = {},
    /** Sorted A–Z: the scrollbar shows a letter bubble while dragged. */
    alphabetical: Boolean = false,
    // The empty library's ways in (see LibraryEmptyState).
    onImport: () -> Unit = {},
    onOpenSync: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenAccounts: () -> Unit = {},
) {
    if (tracks.isEmpty()) {
        Column {
            header()
            if (noMatch != null) {
                // The listener's own search or filter emptied it: one tap undoes that.
                EmptyTabMessage(noMatch, onClearFilters)
            } else {
                // Nothing narrows the list, so nothing is downloaded at all: say how music gets here.
                LibraryEmptyState(
                    anyServiceConnected = anyServiceConnected,
                    onOpenSync = onOpenSync,
                    onOpenAccounts = onOpenAccounts,
                    onOpenSearch = onOpenSearch,
                    onImport = onImport,
                )
            }
        }
        return
    }

    // Tag rows Offline/Online only when the list actually mixes both.
    val mixed = remember(tracks) {
        tracks.any { it.isDownloaded } && tracks.any { !it.isDownloaded }
    }

    // Track selected for the context-menu bottom sheet (opened via the ⋮ now;
    // long-press enters multi-select instead).
    var selectedTrack by remember { mutableStateOf<Track?>(null) }
    // Track whose share sheet is open (fork issue ParaliyzedEvo/Stash#40).
    var trackToShare by remember { mutableStateOf<Track?>(null) }
    trackToShare?.let { t ->
        com.stash.core.ui.components.ShareTrackSheet(
            track = t,
            onDismiss = { trackToShare = null },
        )
    }
    // Track whose Save-to-Playlist sheet is open (single-track path).
    var trackToSave by remember { mutableStateOf<Track?>(null) }
    // Track pending delete confirmation.
    var trackToDelete by remember { mutableStateOf<Track?>(null) }

    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        // The rows share one width reading and text measurer (TrackListItem's fit).
        com.stash.core.ui.components.TrackRowFitScope {
            LazyColumn(
                state = listState,
                // While selecting, the mini-player hides (Task 7) but the bottom
                // selection bar takes its place — pad enough that the last row clears
                // it in either state.
                contentPadding = PaddingValues(bottom = bottomChromePadding(if (selection.isActive) 140.dp else 0.dp)),
            ) {
                item { header() }
                items(tracks, key = { it.id }) { track ->
                    TrackListItem(
                        track = track,
                        onClick = {
                            if (selection.isActive) selection.toggle(track.id)
                            else onTrackClick(track)
                        },
                        isPlaying = track.id == currentlyPlayingTrackId,
                        onLongPress = { if (!selection.isActive) selection.enter(track.id) },
                        selectionActive = selection.isActive,
                        selected = selection.isSelected(track.id),
                        showAvailabilityTag = mixed,
                        onMoreClick = { selectedTrack = track },
                    )
                }
            }
        }
        VerticalScrollbar(
            state = listState,
            // Item 0 is the recently-downloaded header.
            sectionLabel = if (alphabetical) {
                alphabeticalSectionLabel(tracks, leadingItems = 1) { it.title }
            } else {
                null
            },
        )
        // "Now playing ↑/↓" while the playing song is scrolled out of sight: one tap back
        // to it from anywhere in a long library.
        NowPlayingJumpChip(
            listState = listState,
            trackIndex = remember(tracks, currentlyPlayingTrackId) {
                tracks.indexOfFirst { it.id == currentlyPlayingTrackId }
            },
            trackCount = tracks.size,
            enabled = !selection.isActive,
        )
    }

    // ── Context-menu bottom sheet (shared component) ────────────────────
    selectedTrack?.let { track ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { selectedTrack = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            com.stash.core.ui.components.TrackOptionsSheet(
                track = track,
                onPlayNext = { onPlayNext(it); selectedTrack = null },
                onAddToQueue = { onAddToQueue(it); selectedTrack = null },
                onSaveToPlaylist = { trackToSave = it; selectedTrack = null },
                onDismiss = { selectedTrack = null },
                onShare = { trackToShare = it; selectedTrack = null },
                onViewAlbum = { onViewAlbum(it); selectedTrack = null },
                onDownload = { onDownloadTrack(it.id); selectedTrack = null },
                onRemoveDownload = { onRemoveDownloadTrack(it); selectedTrack = null },
                onDelete = { trackToDelete = it; selectedTrack = null },
            )

            // Bottom padding for gesture navigation inset
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // ── Save to Playlist sheet (single-track) ───────────────────────────
    trackToSave?.let { track ->
        com.stash.core.ui.components.SaveToPlaylistSheet(
            playlists = userPlaylists,
            onSaveToPlaylist = { playlistId ->
                onSaveToPlaylist(track.id, playlistId)
                trackToSave = null
            },
            onCreatePlaylist = { name ->
                onCreatePlaylistWithTrack(name, track.id)
                trackToSave = null
            },
            onDismiss = { trackToSave = null },
        )
    }

    // ── Delete confirmation dialog ──────────────────────────────────────
    trackToDelete?.let { track ->
        var alsoBlacklist by remember(track.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { trackToDelete = null },
            title = { Text("Delete \"${track.title}\"?") },
            text = {
                Column {
                    Text(
                        "\"${track.title}\" by ${track.artist} will be removed from " +
                            if (track.isDownloaded) "your library and deleted from disk."
                            else "your library.",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { alsoBlacklist = !alsoBlacklist },
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = alsoBlacklist,
                            onCheckedChange = { alsoBlacklist = it },
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Also block this song from future syncs",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "Blocked songs never re-download. Unblock them in Settings later.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteTrack(track, alsoBlacklist)
                        trackToDelete = null
                    },
                ) {
                    Text(
                        text = if (alsoBlacklist) "Delete & Block" else "Delete",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { trackToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * A single action row inside the track context-menu bottom sheet.
 *
 * @param icon  Leading icon for the action.
 * @param label Human-readable label.
 * @param tint  Icon and label color. Defaults to [MaterialTheme.colorScheme.onSurface].
 * @param onClick Callback when the row is tapped.
 */
@Composable
private fun BottomSheetActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
    }
}

// ── Artists tab (2-column grid) ──────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ArtistsGrid(
    artists: List<ArtistInfo>,
    singleTrackArtists: List<ArtistInfo>,
    anyServiceConnected: Boolean,
    onPlayArtist: (String) -> Unit,
    onAddArtistToQueue: (String) -> Unit,
    onDeleteArtist: (String) -> Unit,
    header: @Composable () -> Unit = {},
    /** Empty-state line when the search emptied the grid (see [noMatchMessage]). */
    noMatch: String? = null,
    onClearFilters: () -> Unit = {},
    /** Sorted A–Z: the scrollbar shows a letter bubble while dragged. */
    alphabetical: Boolean = false,
) {
    if (artists.isEmpty() && singleTrackArtists.isEmpty()) {
        Column {
            header()
            EmptyTabMessage(
                noMatch = noMatch,
                fallback = if (anyServiceConnected) "Sync your library to see artists here"
                else "Connect a service in Settings to see your artists",
                onClear = onClearFilters,
            )
        }
        return
    }

    var artistToDelete by remember { mutableStateOf<ArtistInfo?>(null) }
    var selectedArtist by remember { mutableStateOf<ArtistInfo?>(null) }
    var showSingleTrack by remember { mutableStateOf(false) }

    val displayList = if (showSingleTrack) artists + singleTrackArtists else artists

    val gridState = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = bottomChromePadding(8.dp)),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items(displayList, key = { it.name }) { artist ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .combinedClickable(
                            onClick = { onPlayArtist(artist.name) },
                            onLongClick = { selectedArtist = artist },
                        ),
                ) {
                    ArtistAvatar(
                        name = artist.name,
                        photoUrl = artist.photoUrl,
                        artUrl = artist.artUrl,
                        size = 72.dp,
                    )
                    Text(
                        text = artist.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = pluralize(artist.trackCount, "track"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Collapsible section for single-track artists
            if (singleTrackArtists.isNotEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable { showSingleTrack = !showSingleTrack },
                        color = StashTheme.extendedColors.glassBackground,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, StashTheme.extendedColors.glassBorder,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = if (showSingleTrack) "Hide single-track artists"
                                else "${singleTrackArtists.size} artists with 1 track",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (showSingleTrack) Icons.Default.ExpandLess
                                else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        VerticalScrollbar(
            state = gridState,
            // Item 0 is the header; the "artists with 1 track" toggle after the grid shows no letter.
            sectionLabel = if (alphabetical) {
                alphabeticalSectionLabel(displayList, leadingItems = 1) { it.name }
            } else {
                null
            },
        )
    }

    // ── Context-menu bottom sheet ───────────────────────────────────────
    selectedArtist?.let { artist ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { selectedArtist = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            // Header: artist name + track count
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp),
            ) {
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluralize(artist.trackCount, "track"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            BottomSheetActionRow(
                icon = Icons.Default.PlayArrow,
                label = "Play All by Artist",
                onClick = {
                    onPlayArtist(artist.name)
                    selectedArtist = null
                },
            )
            BottomSheetActionRow(
                icon = Icons.Default.PlaylistAdd,
                label = com.stash.core.ui.components.playNextLabel(com.stash.core.ui.components.LocalListenTogetherRole.current, "Add to Queue"), // session: queue or suggest
                onClick = {
                    onAddArtistToQueue(artist.name)
                    selectedArtist = null
                },
            )
            // Deletes downloaded songs only: an artist with none (Stream only lists them) has
            // nothing on the phone to delete.
            if (artist.downloadedCount > 0) {
                BottomSheetActionRow(
                    icon = Icons.Default.Delete,
                    label = "Delete All by Artist",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = {
                        artistToDelete = artist
                        selectedArtist = null
                    },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Delete artist confirmation
    artistToDelete?.let { artist ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { artistToDelete = null },
            title = { Text("Delete all by ${artist.name}?") },
            // downloadedCount, not trackCount: the card counts every song its page lists, but
            // this deletes only the downloaded ones it leads (LibraryViewModel.deleteArtist).
            text = {
                Text(
                    if (artist.downloadedCount == 1) {
                        "This will delete the 1 downloaded song by this artist from your device."
                    } else {
                        "This will delete all ${artist.downloadedCount} downloaded songs by this artist from your device."
                    },
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        onDeleteArtist(artist.name)
                        artistToDelete = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { artistToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

// ── Albums tab (2-column grid) ───────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AlbumsGrid(
    albums: List<AlbumInfo>,
    singleTrackAlbums: List<AlbumInfo>,
    anyServiceConnected: Boolean,
    onPlayAlbum: (String, String) -> Unit,
    onAddAlbumToQueue: (String, String) -> Unit,
    onOpenAlbum: (AlbumInfo) -> Unit,
    header: @Composable () -> Unit = {},
    /** Empty-state line when the search emptied the grid (see [noMatchMessage]). */
    noMatch: String? = null,
    onClearFilters: () -> Unit = {},
    /** Sorted A–Z: the scrollbar shows a letter bubble while dragged. */
    alphabetical: Boolean = false,
) {
    if (albums.isEmpty() && singleTrackAlbums.isEmpty()) {
        Column {
            header()
            EmptyTabMessage(
                noMatch = noMatch,
                fallback = if (anyServiceConnected) "Sync your library to see albums here"
                else "Connect a service in Settings to see your albums",
                onClear = onClearFilters,
            )
        }
        return
    }

    var selectedAlbum by remember { mutableStateOf<AlbumInfo?>(null) }
    var showSingleTrack by remember { mutableStateOf(false) }

    val displayList = if (showSingleTrack) albums + singleTrackAlbums else albums

    val gridState = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = bottomChromePadding(8.dp)),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items(displayList, key = { "${it.name}|${it.artist}" }) { album ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .combinedClickable(
                            onClick = { onOpenAlbum(album) },
                            onLongClick = { selectedAlbum = album },
                        ),
                ) {
                    // Album art: try local path, then remote URL, then fallback icon.
                    // AlbumArtwork also falls back to the icon while a remote
                    // image is loading or after it errors — a failed URL used to
                    // render as an empty grey box.
                    val artModel = album.artPath ?: album.artUrl
                    AlbumArtwork(
                        model = artModel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    ) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable { onPlayAlbum(album.name, album.artist) },
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            shape = CircleShape,
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play ${album.name}",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                    }
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = album.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = "· ${album.trackCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Collapsible section for single-track albums
            if (singleTrackAlbums.isNotEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable { showSingleTrack = !showSingleTrack },
                        color = StashTheme.extendedColors.glassBackground,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, StashTheme.extendedColors.glassBorder,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = if (showSingleTrack) "Hide single-track albums"
                                else "${singleTrackAlbums.size} albums with 1 track",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (showSingleTrack) Icons.Default.ExpandLess
                                else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        VerticalScrollbar(
            state = gridState,
            // Item 0 is the header; the "albums with 1 track" toggle after the grid shows no letter.
            sectionLabel = if (alphabetical) {
                alphabeticalSectionLabel(displayList, leadingItems = 1) { it.name }
            } else {
                null
            },
        )
    }

    // ── Context-menu bottom sheet ───────────────────────────────────────
    selectedAlbum?.let { album ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { selectedAlbum = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            // Header: album name + artist
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp),
            ) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = album.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            BottomSheetActionRow(
                icon = Icons.Default.PlayArrow,
                label = "Play Album",
                onClick = {
                    onPlayAlbum(album.name, album.artist)
                    selectedAlbum = null
                },
            )
            BottomSheetActionRow(
                icon = Icons.Default.PlaylistAdd,
                label = com.stash.core.ui.components.playNextLabel(com.stash.core.ui.components.LocalListenTogetherRole.current, "Add to Queue"), // session: queue or suggest
                onClick = {
                    onAddAlbumToQueue(album.name, album.artist)
                    selectedAlbum = null
                },
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Album artwork with a graceful fallback. Shows the image once it actually
 * loads; while a remote image is loading, and after it errors, a placeholder
 * album icon renders on the elevated surface instead of an empty grey box.
 */
@Composable
private fun AlbumArtwork(
    model: Any?,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier.background(StashTheme.extendedColors.elevatedSurface),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) {
            val painter = rememberAsyncImagePainter(model = model)
            val state by painter.state.collectAsState()
            if (state is AsyncImagePainter.State.Success) {
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Album,
                    contentDescription = null,
                    tint = StashTheme.extendedColors.textTertiary,
                    modifier = Modifier.size(40.dp),
                )
            }
        } else {
            Icon(
                imageVector = Icons.Default.Album,
                contentDescription = null,
                tint = StashTheme.extendedColors.textTertiary,
                modifier = Modifier.size(40.dp),
            )
        }
        content()
    }
}

// ── Artist gradient helper ───────────────────────────────────────────────────

/**
 * Pre-defined gradient pairs used for artist avatar backgrounds.
 * A deterministic pair is picked based on the hash of the artist name,
 * giving each artist a unique-ish but consistent color.
 */
private val artistGradientPalette = listOf(
    listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)),  // Indigo -> Violet
    listOf(Color(0xFFEC4899), Color(0xFFF43F5E)),  // Pink -> Rose
    listOf(Color(0xFF14B8A6), Color(0xFF06B6D4)),  // Teal -> Cyan
    listOf(Color(0xFFF97316), Color(0xFFEAB308)),  // Orange -> Yellow
    listOf(Color(0xFF3B82F6), Color(0xFF6366F1)),  // Blue -> Indigo
    listOf(Color(0xFF10B981), Color(0xFF14B8A6)),  // Emerald -> Teal
    listOf(Color(0xFFE11D48), Color(0xFFDB2777)),  // Rose -> Pink
    listOf(Color(0xFF8B5CF6), Color(0xFFA855F7)),  // Violet -> Purple
)

/**
 * Returns a gradient color list for a given artist name.
 * The selection is deterministic so the same artist always gets the same gradient.
 */
private fun artistGradient(name: String): List<Color> {
    val index = name.hashCode().absoluteValue % artistGradientPalette.size
    return artistGradientPalette[index]
}

/**
 * Artist circular avatar for the Artists grid: the cached official photo
 * ([ArtistInfo.photoUrl]) when available, else the album-art proxy
 * ([ArtistInfo.artUrl]), else a gradient-initial circle. Coil draws the photo;
 * while it loads (or if it errors) the gradient initial is shown so a failed
 * fetch never leaves an empty ring.
 */
@Composable
private fun ArtistAvatar(
    name: String,
    photoUrl: String?,
    artUrl: String?,
    size: Dp,
) {
    val model = photoUrl ?: artUrl
    if (model != null) {
        val painter = rememberAsyncImagePainter(model = model)
        val state by painter.state.collectAsState()
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (state is AsyncImagePainter.State.Success) {
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                ArtistInitialFallback(name, size)
            }
        }
    } else {
        ArtistInitialFallback(name, size)
    }
}

/** Gradient circle with the artist's first letter — the no-photo fallback. */
@Composable
private fun ArtistInitialFallback(name: String, size: Dp) {
    val gradientColors = remember(name) { artistGradient(name) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(gradientColors)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

// ── A–Z scrollbar bubble ─────────────────────────────────────────────────────

/**
 * The scrollbar bubble's letter for each lazy item of a Library list that shows [items] after
 * [leadingItems] header items (the recently-downloaded rail, the Liked sift row). The letter is
 * read from the item's [name], the very string the list was sorted by under [alphabeticalOrder],
 * so the bubble's letters always run in the list's own order. A header borrows the first item's
 * letter; an item past the end (the "with 1 track" toggle under a grid) has none.
 */
private fun <T> alphabeticalSectionLabel(
    items: List<T>,
    leadingItems: Int,
    name: (T) -> String,
): (Int) -> String? = { index ->
    items.getOrNull((index - leadingItems).coerceAtLeast(0))
        ?.let { fastScrollSectionLetter(name(it)) }
}

// ── Empty state placeholder ──────────────────────────────────────────────────

@Composable
private fun EmptyTabMessage(message: String, onClear: (() -> Unit)? = null) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = StashTheme.extendedColors.textTertiary,
            )
            // Emptied by the user's own search/filter (see noMatchMessage): one tap undoes it.
            if (onClear != null) {
                TextButton(onClick = onClear) { Text("Show everything") }
            }
        }
    }
}

/**
 * The Songs tab with nothing downloaded at all (a first run, or every download removed). The
 * library is downloads-only, so a bare "Sync your library" line left a new listener guessing; this
 * names the three real ways music arrives and makes each one a button:
 *
 * - Sync: with a service connected, the Sync tab (Sync now, and what each playlist keeps); with
 *   none, Settings › Accounts to connect Spotify or YouTube Music first. Leading with Sync when
 *   nothing is connected would land on a Sync now that has nothing to fetch.
 * - Search: find any song, stream it, download it.
 * - Import: audio files already on this phone (the same picker as the header's + button).
 *
 * Scrolls when it doesn't fit (a small phone at a large font size) and pads its end clear of the
 * glass mini player and tab bar.
 */
@Composable
private fun LibraryEmptyState(
    anyServiceConnected: Boolean,
    onOpenSync: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenSearch: () -> Unit,
    onImport: () -> Unit,
) {
    val prompt = emptyLibraryPrompt(anyServiceConnected)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 32.dp, end = 32.dp, top = 48.dp, bottom = bottomChromePadding(24.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.LibraryMusic,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Your library is empty",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = prompt.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = if (prompt.connectFirst) onOpenAccounts else onOpenSync) {
            Icon(
                imageVector = if (prompt.connectFirst) Icons.Default.Link else Icons.Default.Sync,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(prompt.primaryLabel)
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenSearch) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Find songs in Search")
        }
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onImport) {
            Text("Import from this phone")
        }
    }
}

/** [EmptyTabMessage] for a tab: [noMatch] (with a Clear action) when set, else [fallback]. */
@Composable
private fun EmptyTabMessage(noMatch: String?, fallback: String, onClear: () -> Unit) {
    if (noMatch != null) EmptyTabMessage(noMatch, onClear) else EmptyTabMessage(fallback)
}
