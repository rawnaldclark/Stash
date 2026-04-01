package com.stash.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.Playlist
import com.stash.core.model.Track
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.components.SourceIndicator
import com.stash.core.ui.components.TrackListItem
import com.stash.core.ui.theme.StashTheme

/**
 * Library screen entry point. Injects the [LibraryViewModel] via Hilt
 * and delegates rendering to the stateless [LibraryContent] composable.
 */
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LibraryContent(
        state = state,
        onTabSelected = viewModel::selectTab,
        onSearchQueryChanged = viewModel::setSearchQuery,
        onSortOrderChanged = viewModel::setSortOrder,
        onSourceFilterChanged = viewModel::setSourceFilter,
        onTrackClick = { track -> viewModel.playTrack(track, state.tracks) },
        onPlayNext = viewModel::playNext,
        onAddToQueue = viewModel::addToQueue,
        onDeleteTrack = viewModel::deleteTrack,
        onPlayPlaylist = viewModel::playPlaylist,
        onAddPlaylistToQueue = viewModel::addPlaylistToQueue,
        onRemovePlaylist = viewModel::removePlaylist,
        onDeletePlaylist = viewModel::deletePlaylist,
        onPlayArtist = viewModel::playArtist,
        onAddArtistToQueue = viewModel::addArtistToQueue,
        onDeleteArtist = viewModel::deleteArtist,
        onPlayAlbum = viewModel::playAlbum,
        onAddAlbumToQueue = viewModel::addAlbumToQueue,
        modifier = modifier,
    )
}

// ── Stateless content composable ─────────────────────────────────────────────

@Composable
private fun LibraryContent(
    state: LibraryUiState,
    onTabSelected: (LibraryTab) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSortOrderChanged: (SortOrder) -> Unit,
    onSourceFilterChanged: (SourceFilter) -> Unit,
    onTrackClick: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onDeleteTrack: (Track) -> Unit,
    onPlayPlaylist: (Playlist) -> Unit,
    onAddPlaylistToQueue: (Playlist) -> Unit,
    onRemovePlaylist: (Playlist) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
    onPlayArtist: (String) -> Unit,
    onAddArtistToQueue: (String) -> Unit,
    onDeleteArtist: (String) -> Unit,
    onPlayAlbum: (String, String) -> Unit,
    onAddAlbumToQueue: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 16.dp),
    ) {
        // -- Heading --
        Text(
            text = "Library",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // -- Glassmorphic search bar --
        GlassSearchBar(
            query = state.searchQuery,
            onQueryChange = onSearchQueryChanged,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // -- Tab chips (horizontal scroll) --
        TabChipRow(
            activeTab = state.activeTab,
            onTabSelected = onTabSelected,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // -- Sort chips --
        SortChipRow(
            activeSort = state.sortOrder,
            onSortSelected = onSortOrderChanged,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // -- Source filter chips --
        SourceFilterChipRow(
            activeFilter = state.sourceFilter,
            onFilterSelected = onSourceFilterChanged,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // -- Content area --
        val anyServiceConnected = state.spotifyConnected || state.youTubeConnected
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            when (state.activeTab) {
                LibraryTab.PLAYLISTS -> PlaylistsGrid(
                    playlists = state.playlists,
                    anyServiceConnected = anyServiceConnected,
                    onPlayPlaylist = onPlayPlaylist,
                    onAddPlaylistToQueue = onAddPlaylistToQueue,
                    onRemovePlaylist = onRemovePlaylist,
                    onDeletePlaylist = onDeletePlaylist,
                )
                LibraryTab.TRACKS -> TracksTab(
                    tracks = state.tracks,
                    onTrackClick = onTrackClick,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onDeleteTrack = onDeleteTrack,
                    anyServiceConnected = anyServiceConnected,
                )
                LibraryTab.ARTISTS -> ArtistsGrid(
                    artists = state.artists,
                    anyServiceConnected = anyServiceConnected,
                    onPlayArtist = onPlayArtist,
                    onAddArtistToQueue = onAddArtistToQueue,
                    onDeleteArtist = onDeleteArtist,
                )
                LibraryTab.ALBUMS -> AlbumsGrid(
                    albums = state.albums,
                    anyServiceConnected = anyServiceConnected,
                    onPlayAlbum = onPlayAlbum,
                    onAddAlbumToQueue = onAddAlbumToQueue,
                )
            }
        }
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

// ── Tab chips ────────────────────────────────────────────────────────────────

@Composable
private fun TabChipRow(
    activeTab: LibraryTab,
    onTabSelected: (LibraryTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LibraryTab.entries.forEach { tab ->
            val isSelected = tab == activeTab
            FilterChip(
                selected = isSelected,
                onClick = { onTabSelected(tab) },
                label = {
                    Text(
                        text = tab.displayName(),
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = Color.White,
                    containerColor = StashTheme.extendedColors.glassBackground,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = StashTheme.extendedColors.glassBorder,
                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                    enabled = true,
                    selected = isSelected,
                ),
            )
        }
    }
}

/** Human-readable label for each tab. */
private fun LibraryTab.displayName(): String = when (this) {
    LibraryTab.PLAYLISTS -> "Playlists"
    LibraryTab.TRACKS -> "Tracks"
    LibraryTab.ARTISTS -> "Artists"
    LibraryTab.ALBUMS -> "Albums"
}

// ── Sort chips ───────────────────────────────────────────────────────────────

@Composable
private fun SortChipRow(
    activeSort: SortOrder,
    onSortSelected: (SortOrder) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SortOrder.entries.forEach { order ->
            val isSelected = order == activeSort
            FilterChip(
                selected = isSelected,
                onClick = { onSortSelected(order) },
                label = {
                    Text(
                        text = order.displayName(),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = StashTheme.extendedColors.elevatedSurface,
                    selectedLabelColor = MaterialTheme.colorScheme.onBackground,
                    containerColor = Color.Transparent,
                    labelColor = StashTheme.extendedColors.textTertiary,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = Color.Transparent,
                    selectedBorderColor = StashTheme.extendedColors.glassBorderBright,
                    enabled = true,
                    selected = isSelected,
                ),
            )
        }
    }
}

private fun SortOrder.displayName(): String = when (this) {
    SortOrder.RECENT -> "Recently Added"
    SortOrder.ALPHABETICAL -> "A-Z"
    SortOrder.MOST_PLAYED -> "Most Played"
}

// ── Source filter chips ─────────────────────────────────────────────────────

@Composable
private fun SourceFilterChipRow(
    activeFilter: SourceFilter,
    onFilterSelected: (SourceFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SourceFilter.entries.forEach { filter ->
            val isSelected = filter == activeFilter
            FilterChip(
                selected = isSelected,
                onClick = { onFilterSelected(filter) },
                label = {
                    Text(
                        text = filter.displayName(),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = StashTheme.extendedColors.elevatedSurface,
                    selectedLabelColor = MaterialTheme.colorScheme.onBackground,
                    containerColor = Color.Transparent,
                    labelColor = StashTheme.extendedColors.textTertiary,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = Color.Transparent,
                    selectedBorderColor = StashTheme.extendedColors.glassBorderBright,
                    enabled = true,
                    selected = isSelected,
                ),
            )
        }
    }
}

private fun SourceFilter.displayName(): String = when (this) {
    SourceFilter.ALL -> "All"
    SourceFilter.YOUTUBE -> "YouTube"
    SourceFilter.SPOTIFY -> "Spotify"
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
    onDeletePlaylist: (Playlist) -> Unit,
) {
    if (playlists.isEmpty()) {
        EmptyTabMessage(
            if (anyServiceConnected) "Sync your playlists to see them here"
            else "Connect a service in Settings to see your playlists",
        )
        return
    }

    // Playlist selected for the context-menu bottom sheet.
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    // Playlist pending delete confirmation.
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(playlists, key = { it.id }) { playlist ->
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onPlayPlaylist(playlist) },
                        onLongClick = { selectedPlaylist = playlist },
                    ),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SourceIndicator(source = playlist.source)
                        Text(
                            text = "${playlist.trackCount} tracks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
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
                    text = "${playlist.trackCount} tracks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
                label = "Add to Queue",
                onClick = {
                    onAddPlaylistToQueue(playlist)
                    selectedPlaylist = null
                },
            )
            BottomSheetActionRow(
                icon = Icons.Default.RemoveCircleOutline,
                label = "Remove Playlist",
                onClick = {
                    onRemovePlaylist(playlist)
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

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // ── Delete confirmation dialog ──────────────────────────────────────
    playlistToDelete?.let { playlist ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Delete playlist?") },
            text = {
                Text(
                    "\"${playlist.name}\" and all its tracks will be removed from " +
                        "your library and deleted from disk. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePlaylist(playlist)
                        playlistToDelete = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
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
    onTrackClick: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onDeleteTrack: (Track) -> Unit,
    anyServiceConnected: Boolean,
) {
    if (tracks.isEmpty()) {
        EmptyTabMessage(
            if (anyServiceConnected) "Sync your library to see tracks here"
            else "Connect a service in Settings to see your tracks",
        )
        return
    }

    // Track selected for the context-menu bottom sheet.
    var selectedTrack by remember { mutableStateOf<Track?>(null) }
    // Track pending delete confirmation.
    var trackToDelete by remember { mutableStateOf<Track?>(null) }

    LazyColumn {
        items(tracks, key = { it.id }) { track ->
            TrackListItem(
                track = track,
                onClick = { onTrackClick(track) },
                onLongPress = { selectedTrack = track },
            )
        }
    }

    // ── Context-menu bottom sheet ───────────────────────────────────────
    selectedTrack?.let { track ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { selectedTrack = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            // Header: track title + artist
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp),
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Action rows
            BottomSheetActionRow(
                icon = Icons.Default.PlaylistPlay,
                label = "Play Next",
                onClick = {
                    onPlayNext(track)
                    selectedTrack = null
                },
            )
            BottomSheetActionRow(
                icon = Icons.Default.PlaylistAdd,
                label = "Add to Queue",
                onClick = {
                    onAddToQueue(track)
                    selectedTrack = null
                },
            )
            BottomSheetActionRow(
                icon = Icons.Default.Delete,
                label = "Delete",
                tint = MaterialTheme.colorScheme.error,
                onClick = {
                    trackToDelete = track
                    selectedTrack = null
                },
            )

            // Bottom padding for gesture navigation inset
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // ── Delete confirmation dialog ──────────────────────────────────────
    trackToDelete?.let { track ->
        AlertDialog(
            onDismissRequest = { trackToDelete = null },
            title = { Text("Delete track?") },
            text = {
                Text(
                    "\"${track.title}\" by ${track.artist} will be removed from " +
                        "your library and deleted from disk. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteTrack(track)
                        trackToDelete = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
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
    anyServiceConnected: Boolean,
    onPlayArtist: (String) -> Unit,
    onAddArtistToQueue: (String) -> Unit,
    onDeleteArtist: (String) -> Unit,
) {
    if (artists.isEmpty()) {
        EmptyTabMessage(
            if (anyServiceConnected) "Sync your library to see artists here"
            else "Connect a service in Settings to see your artists",
        )
        return
    }

    // Artist selected for the context-menu bottom sheet.
    var artistToDelete by remember { mutableStateOf<ArtistInfo?>(null) }
    var selectedArtist by remember { mutableStateOf<ArtistInfo?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(artists, key = { it.name }) { artist ->
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
                // Circular placeholder
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(StashTheme.extendedColors.elevatedSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = StashTheme.extendedColors.textTertiary,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "${artist.trackCount} tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
                    text = "${artist.trackCount} tracks",
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
                label = "Add to Queue",
                onClick = {
                    onAddArtistToQueue(artist.name)
                    selectedArtist = null
                },
            )
            BottomSheetActionRow(
                icon = Icons.Default.Delete,
                label = "Delete All by Artist",
                tint = MaterialTheme.colorScheme.error,
                onClick = {
                    artistToDelete = artist
                    selectedArtist = null
                },
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Delete artist confirmation
    artistToDelete?.let { artist ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { artistToDelete = null },
            title = { Text("Delete all by ${artist.name}?") },
            text = { Text("This will delete all ${artist.trackCount} downloaded songs by this artist from your device.") },
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
    anyServiceConnected: Boolean,
    onPlayAlbum: (String, String) -> Unit,
    onAddAlbumToQueue: (String, String) -> Unit,
) {
    if (albums.isEmpty()) {
        EmptyTabMessage(
            if (anyServiceConnected) "Sync your library to see albums here"
            else "Connect a service in Settings to see your albums",
        )
        return
    }

    // Album selected for the context-menu bottom sheet.
    var selectedAlbum by remember { mutableStateOf<AlbumInfo?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(albums, key = { "${it.name}|${it.artist}" }) { album ->
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .combinedClickable(
                        onClick = { onPlayAlbum(album.name, album.artist) },
                        onLongClick = { selectedAlbum = album },
                    ),
            ) {
                // Album art placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(StashTheme.extendedColors.elevatedSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        tint = StashTheme.extendedColors.textTertiary,
                        modifier = Modifier.size(40.dp),
                    )
                }
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = album.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
                label = "Add to Queue",
                onClick = {
                    onAddAlbumToQueue(album.name, album.artist)
                    selectedAlbum = null
                },
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ── Empty state placeholder ──────────────────────────────────────────────────

@Composable
private fun EmptyTabMessage(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = StashTheme.extendedColors.textTertiary,
        )
    }
}
