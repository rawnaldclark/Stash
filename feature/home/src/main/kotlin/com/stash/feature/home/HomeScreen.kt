package com.stash.feature.home

import android.text.format.DateUtils
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.SyncState
import com.stash.core.model.Track
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.components.SectionHeader
import com.stash.core.ui.components.SourceIndicator
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.home.components.StashVinylLogo

/**
 * Home screen composable displaying a premium dark dashboard with sync
 * status, daily mixes, recently added tracks, liked songs, and playlists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Playlist selected for the context-menu bottom sheet (shared across daily mixes + grid).
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        // ── App title with vinyl logo and checkerboard underline ─────
        item {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StashVinylLogo(size = 56.dp)
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    val purple = StashTheme.extendedColors.purpleLight
                    val white = MaterialTheme.colorScheme.onBackground
                    val stashText = androidx.compose.ui.text.buildAnnotatedString {
                        append(androidx.compose.ui.text.AnnotatedString("S", androidx.compose.ui.text.SpanStyle(color = purple)))
                        append(androidx.compose.ui.text.AnnotatedString("ta", androidx.compose.ui.text.SpanStyle(color = white)))
                        append(androidx.compose.ui.text.AnnotatedString("s", androidx.compose.ui.text.SpanStyle(color = purple)))
                        append(androidx.compose.ui.text.AnnotatedString("h", androidx.compose.ui.text.SpanStyle(color = purple)))
                    }
                    val righteousFont = androidx.compose.ui.text.font.FontFamily(
                        androidx.compose.ui.text.font.Font(com.stash.core.ui.R.font.righteous)
                    )
                    Text(
                        text = stashText,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontFamily = righteousFont,
                            letterSpacing = 1.sp,
                        ),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    // Checkerboard underline bar
                    Row(modifier = Modifier.width(90.dp).height(4.dp)) {
                        val colors = listOf(
                            StashTheme.extendedColors.purpleLight,
                            StashTheme.extendedColors.purpleDark,
                        )
                        repeat(10) { i ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(colors[i % 2]),
                            )
                        }
                    }
                }
            }
        }

        // ── Sync status card ─────────────────────────────────────────
        item {
            SyncStatusCard(
                syncStatus = uiState.syncStatus,
                spotifyConnected = uiState.spotifyConnected,
                youTubeConnected = uiState.youTubeConnected,
                hasEverSynced = uiState.hasEverSynced,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        // ── Daily Mixes ──────────────────────────────────────────────
        if (uiState.dailyMixes.isNotEmpty()) {
            item {
                SectionHeader(title = "Daily Mixes")
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(uiState.dailyMixes, key = { it.id }) { playlist ->
                        DailyMixCard(
                            playlist = playlist,
                            onClick = { viewModel.playPlaylist(playlist) },
                            onLongPress = { selectedPlaylist = playlist },
                        )
                    }
                }
            }
        }

        // ── Recently Added ───────────────────────────────────────────
        if (uiState.recentlyAdded.isNotEmpty()) {
            item {
                SectionHeader(title = "Recently Added")
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(
                        uiState.recentlyAdded,
                        key = { _, track -> track.id },
                    ) { index, track ->
                        CompactTrackCard(
                            track = track,
                            onClick = {
                                viewModel.playTrack(uiState.recentlyAdded, index)
                            },
                        )
                    }
                }
            }
        }

        // ── Liked Songs card ─────────────────────────────────────────
        if (uiState.likedSongsCount > 0) {
            item {
                Spacer(Modifier.height(8.dp))
                LikedSongsCard(
                    count = uiState.likedSongsCount,
                    onClick = { viewModel.playLikedSongs() },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // ── Playlists grid ───────────────────────────────────────────
        if (uiState.playlists.isNotEmpty()) {
            item {
                SectionHeader(title = "Playlists")
            }
            // Render a non-scrollable 2-column grid inside the LazyColumn
            item {
                val rows = uiState.playlists.chunked(2)
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rows.forEach { rowItems ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            rowItems.forEach { playlist ->
                                PlaylistGridCard(
                                    playlist = playlist,
                                    onClick = { viewModel.playPlaylist(playlist) },
                                    onLongPress = { selectedPlaylist = playlist },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // Pad single-item rows with a spacer
                            if (rowItems.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Playlist context-menu bottom sheet ──────────────────────────────
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

            HomeBottomSheetActionRow(
                icon = Icons.Default.PlayArrow,
                label = "Play All",
                onClick = {
                    viewModel.playPlaylist(playlist)
                    selectedPlaylist = null
                },
            )
            HomeBottomSheetActionRow(
                icon = Icons.Default.PlaylistAdd,
                label = "Add to Queue",
                onClick = {
                    viewModel.addPlaylistToQueue(playlist)
                    selectedPlaylist = null
                },
            )
            HomeBottomSheetActionRow(
                icon = Icons.Default.RemoveCircleOutline,
                label = "Remove Playlist",
                onClick = {
                    viewModel.removePlaylist(playlist)
                    selectedPlaylist = null
                },
            )
            HomeBottomSheetActionRow(
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

    // ── Delete playlist confirmation dialog ──────────────────────────────
    playlistToDelete?.let { playlist ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Delete ${playlist.name}?") },
            text = { Text("This will delete all downloaded songs in this playlist from your device.") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        viewModel.deletePlaylistAndSongs(playlist)
                        playlistToDelete = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { playlistToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

// ── Sync status card ─────────────────────────────────────────────────────

@Composable
private fun SyncStatusCard(
    syncStatus: SyncStatusInfo,
    spotifyConnected: Boolean,
    youTubeConnected: Boolean,
    hasEverSynced: Boolean,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    val anyServiceConnected = spotifyConnected || youTubeConnected

    GlassCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // -- Connection + sync status header --
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulseDot(
                    color = when {
                        !anyServiceConnected -> MaterialTheme.colorScheme.onSurfaceVariant
                        !hasEverSynced -> extendedColors.warning
                        syncStatus.state == SyncState.COMPLETED || syncStatus.state == SyncState.IDLE -> extendedColors.success
                        syncStatus.state == SyncState.FAILED -> Color(0xFFEF4444)
                        else -> extendedColors.warning
                    },
                )
                Text(
                    text = when {
                        !anyServiceConnected -> "No services connected"
                        !hasEverSynced -> "Ready to sync"
                        syncStatus.state == SyncState.COMPLETED || syncStatus.state == SyncState.IDLE -> "Synced"
                        syncStatus.state == SyncState.FAILED -> "Sync failed"
                        else -> "Syncing..."
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // -- Connected services row --
            if (anyServiceConnected) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (spotifyConnected) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            SourceIndicator(source = MusicSource.SPOTIFY, size = 6.dp)
                            Text(
                                text = "Spotify",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (youTubeConnected) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            SourceIndicator(source = MusicSource.YOUTUBE, size = 6.dp)
                            Text(
                                text = "YouTube Music",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // -- Prompt or stats depending on sync state --
            if (!anyServiceConnected) {
                Text(
                    text = "Connect Spotify or YouTube Music in Settings to start syncing your library.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!hasEverSynced) {
                Text(
                    text = "Tap Sync Now to download your playlists and tracks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatItem(
                        label = "Tracks",
                        value = syncStatus.totalTracks.toString(),
                    )
                    StatItem(
                        label = "Spotify",
                        value = syncStatus.spotifyTracks.toString(),
                    )
                    StatItem(
                        label = "YouTube",
                        value = syncStatus.youTubeTracks.toString(),
                    )
                    StatItem(
                        label = "Storage",
                        value = formatBytes(syncStatus.storageUsedBytes),
                    )
                }
                if (syncStatus.lastSyncTime != null) {
                    Text(
                        text = "Last sync ${formatRelativeTime(syncStatus.lastSyncTime)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PulseDot(color: Color, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    Box(
        modifier = modifier
            .size(8.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color),
    )
}

// ── Daily mix card ───────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DailyMixCard(
    playlist: Playlist,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    val gradientColors = if (playlist.source == MusicSource.SPOTIFY) {
        listOf(
            extendedColors.spotifyGreen.copy(alpha = 0.4f),
            Color.Transparent,
        )
    } else {
        listOf(
            extendedColors.youtubeRed.copy(alpha = 0.4f),
            Color.Transparent,
        )
    }

    Surface(
        modifier = modifier
            .width(180.dp)
            .height(120.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            ),
        color = extendedColors.glassBackground,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Album art background (if available)
            if (playlist.artUrl != null) {
                AsyncImage(
                    model = playlist.artUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            // Gradient overlay for text readability
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(gradientColors))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                        )
                    ),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                SourceIndicator(source = playlist.source, size = 8.dp)
                Column {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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

// ── Compact track card ───────────────────────────────────────────────────

@Composable
private fun CompactTrackCard(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors

    Surface(
        modifier = modifier
            .width(140.dp)
            .clickable(onClick = onClick),
        color = extendedColors.glassBackground,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Album art
            val artUrl = track.albumArtPath ?: track.albumArtUrl
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(extendedColors.elevatedSurface),
                contentAlignment = Alignment.Center,
            ) {
                if (artUrl != null) {
                    coil3.compose.AsyncImage(
                        model = artUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Text(
                text = track.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SourceIndicator(source = track.source, size = 5.dp)
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── Liked songs card ─────────────────────────────────────────────────────

@Composable
private fun LikedSongsCard(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = extendedColors.glassBackground,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            Color.Transparent,
                        )
                    )
                )
                .padding(20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    extendedColors.purpleDark,
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column {
                    Text(
                        text = "Liked Songs",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "$count tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Playlist grid card ───────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistGridCard(
    playlist: Playlist,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors

    Surface(
        modifier = modifier
            .height(100.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            ),
        color = extendedColors.glassBackground,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Album art background (if available)
            if (playlist.artUrl != null) {
                AsyncImage(
                    model = playlist.artUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                // Dark gradient overlay for text readability
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.2f), Color.Black.copy(alpha = 0.7f)),
                            )
                        ),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = null,
                        tint = if (playlist.artUrl != null) Color.White else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    SourceIndicator(source = playlist.source, size = 6.dp)
                }
                Column {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (playlist.artUrl != null) Color.White else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${playlist.trackCount} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (playlist.artUrl != null) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Bottom sheet action row ──────────────────────────────────────────────

/**
 * A single action row inside a playlist context-menu bottom sheet.
 *
 * @param icon  Leading icon for the action.
 * @param label Human-readable label.
 * @param tint  Icon and label color. Defaults to [MaterialTheme.colorScheme.onSurface].
 * @param onClick Callback when the row is tapped.
 */
@Composable
private fun HomeBottomSheetActionRow(
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

// ── Utilities ────────────────────────────────────────────────────────────

/**
 * Formats a byte count into a human-readable string (e.g. "45.2 MB").
 */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val safeIndex = digitGroups.coerceIn(0, units.lastIndex)
    return "%.1f %s".format(bytes / Math.pow(1024.0, safeIndex.toDouble()), units[safeIndex])
}

/**
 * Formats an epoch-millis timestamp into a relative time string (e.g. "2 hours ago").
 */
private fun formatRelativeTime(epochMillis: Long): String {
    return DateUtils.getRelativeTimeSpanString(
        epochMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}
