package com.stash.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.Playlist
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
        onTrackClick = { track -> viewModel.playTrack(track, state.tracks) },
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
    onTrackClick: (com.stash.core.model.Track) -> Unit,
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
                )
                LibraryTab.TRACKS -> TracksTab(
                    tracks = state.tracks,
                    onTrackClick = onTrackClick,
                    anyServiceConnected = anyServiceConnected,
                )
                LibraryTab.ARTISTS -> ArtistsGrid(
                    artists = state.artists,
                    anyServiceConnected = anyServiceConnected,
                )
                LibraryTab.ALBUMS -> AlbumsGrid(
                    albums = state.albums,
                    anyServiceConnected = anyServiceConnected,
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

// ── Playlists tab (2-column grid) ────────────────────────────────────────────

@Composable
private fun PlaylistsGrid(
    playlists: List<Playlist>,
    anyServiceConnected: Boolean,
) {
    if (playlists.isEmpty()) {
        EmptyTabMessage(
            if (anyServiceConnected) "Sync your playlists to see them here"
            else "Connect a service in Settings to see your playlists",
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(playlists, key = { it.id }) { playlist ->
            GlassCard(modifier = Modifier.fillMaxWidth()) {
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
}

// ── Tracks tab (lazy column of TrackListItems) ──────────────────────────────

@Composable
private fun TracksTab(
    tracks: List<com.stash.core.model.Track>,
    onTrackClick: (com.stash.core.model.Track) -> Unit,
    anyServiceConnected: Boolean,
) {
    if (tracks.isEmpty()) {
        EmptyTabMessage(
            if (anyServiceConnected) "Sync your library to see tracks here"
            else "Connect a service in Settings to see your tracks",
        )
        return
    }
    LazyColumn {
        items(tracks, key = { it.id }) { track ->
            TrackListItem(
                track = track,
                onClick = { onTrackClick(track) },
            )
        }
    }
}

// ── Artists tab (2-column grid) ──────────────────────────────────────────────

@Composable
private fun ArtistsGrid(
    artists: List<ArtistInfo>,
    anyServiceConnected: Boolean,
) {
    if (artists.isEmpty()) {
        EmptyTabMessage(
            if (anyServiceConnected) "Sync your library to see artists here"
            else "Connect a service in Settings to see your artists",
        )
        return
    }
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
                    .padding(vertical = 8.dp),
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
}

// ── Albums tab (2-column grid) ───────────────────────────────────────────────

@Composable
private fun AlbumsGrid(
    albums: List<AlbumInfo>,
    anyServiceConnected: Boolean,
) {
    if (albums.isEmpty()) {
        EmptyTabMessage(
            if (anyServiceConnected) "Sync your library to see albums here"
            else "Connect a service in Settings to see your albums",
        )
        return
    }
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
                    .padding(vertical = 4.dp),
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
