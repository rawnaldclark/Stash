package com.stash.feature.home

import android.text.format.DateUtils
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.SyncDisplayStatus
import com.stash.core.model.SyncState
import com.stash.core.model.Track
import com.stash.core.ui.components.CreatePlaylistDialog
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.components.SectionHeader
import com.stash.core.ui.components.SourceIndicator
import com.stash.core.ui.theme.LocalIsDarkTheme
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
    onNavigateToPlaylist: (Long) -> Unit = {},
    onNavigateToLikedSongs: (String?) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Playlist selected for the context-menu bottom sheet (shared across daily mixes + grid).
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    // Controls the "New Playlist" naming dialog launched from the Playlists section.
    var showCreateDialog by remember { mutableStateOf(false) }

    // Pre-computed 2-column chunking for the Playlists grid. Hoisted out
    // of the LazyColumn's item{} so the chunked() + buildList{} only runs
    // when the playlists list actually changes — not on every recomposition
    // triggered by unrelated state (sync status, liked songs count, etc.).
    val playlistGridRows = remember(uiState.playlists) {
        val tiles: List<PlaylistTile> = buildList {
            add(PlaylistTile.Create)
            uiState.playlists.forEach { add(PlaylistTile.Item(it)) }
        }
        tiles.chunked(2)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        // ── App title with vinyl logo and Bungee Shade wordmark ──────
        item {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StashVinylLogo(size = 56.dp)
                Spacer(modifier = Modifier.width(14.dp))
                // Bungee Shade "Stash" as a VectorDrawable. The light and
                // dark variants are both in drawable/ and we pick via the
                // LocalIsDarkTheme CompositionLocal, because the app theme
                // is Compose-state driven (not OS ui-mode) so the
                // drawable-night/ resource qualifier wouldn't flip with it.
                val isDark = LocalIsDarkTheme.current
                Image(
                    painter = painterResource(
                        id = if (isDark) R.drawable.wordmark_stash_dark
                        else R.drawable.wordmark_stash_light,
                    ),
                    contentDescription = "Stash",
                    modifier = Modifier.height(48.dp),
                )
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

        // ── Mixes (split by source, each with a Play All button) ─────
        if (uiState.spotifyMixes.isNotEmpty() || uiState.youtubeMixes.isNotEmpty()) {
            item {
                MixesSectionHeader(
                    showPlayBoth = uiState.hasBothMixSources,
                    onPlayBoth = { viewModel.playAllMixes(source = null) },
                )
            }

            // Spotify mixes row — sub-header with Play All always present
            if (uiState.spotifyMixes.isNotEmpty()) {
                item {
                    SourceSubHeader(
                        label = "Spotify",
                        source = MusicSource.SPOTIFY,
                        onPlayAll = { viewModel.playAllMixes(MusicSource.SPOTIFY) },
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.spotifyMixes, key = { it.id }) { playlist ->
                            DailyMixCard(
                                playlist = playlist,
                                onClick = { onNavigateToPlaylist(playlist.id) },
                                onLongPress = { selectedPlaylist = playlist },
                            )
                        }
                    }
                }
            }

            // YouTube mixes row — sub-header with Play All always present
            if (uiState.youtubeMixes.isNotEmpty()) {
                item {
                    SourceSubHeader(
                        label = "YouTube Music",
                        source = MusicSource.YOUTUBE,
                        onPlayAll = { viewModel.playAllMixes(MusicSource.YOUTUBE) },
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.youtubeMixes, key = { it.id }) { playlist ->
                            DailyMixCard(
                                playlist = playlist,
                                onClick = { onNavigateToPlaylist(playlist.id) },
                                onLongPress = { selectedPlaylist = playlist },
                            )
                        }
                    }
                }
            }
        }

        // ── Stash Mixes (recipe-driven, generated locally) ───────────
        // v0.4.1: sits BELOW the sync-sourced Daily Mixes while the
        // feature is in beta. Once it graduates, this block can move
        // back up so user-generated mixes feel primary.
        if (uiState.stashMixes.isNotEmpty()) {
            item {
                SectionHeader(title = "Stash Mixes  (Beta)")
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(uiState.stashMixes, key = { it.id }) { playlist ->
                        DailyMixCard(
                            playlist = playlist,
                            onClick = { onNavigateToPlaylist(playlist.id) },
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

        // ── Liked Songs card (with source split + smart collapse) ────
        if (uiState.hasAnyLikedSongs) {
            item {
                Spacer(Modifier.height(8.dp))
                LikedSongsCard(
                    totalCount = uiState.totalLikedCount,
                    spotifyCount = uiState.spotifyLikedCount,
                    youtubeCount = uiState.youtubeLikedCount,
                    showSourceChips = uiState.hasBothLikedSources,
                    singleSource = uiState.singleLikedSource,
                    onPlayAll = { viewModel.playLikedSongs(source = null) },
                    onPlaySpotify = { viewModel.playLikedSongs(source = MusicSource.SPOTIFY) },
                    onPlayYouTube = { viewModel.playLikedSongs(source = MusicSource.YOUTUBE) },
                    onClick = { onNavigateToLikedSongs(null) },
                    onClickSpotify = {
                        val spotifyPlaylistId = uiState.spotifyLikedPlaylists.firstOrNull()?.id
                        if (spotifyPlaylistId != null) onNavigateToPlaylist(spotifyPlaylistId)
                    },
                    onClickYouTube = {
                        val youtubePlaylistId = uiState.youtubeLikedPlaylists.firstOrNull()?.id
                        if (youtubePlaylistId != null) onNavigateToPlaylist(youtubePlaylistId)
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // ── Playlists grid ───────────────────────────────────────────
        // Always rendered so the Create Playlist card is available even when
        // the user has no custom playlists yet.
        item {
            SectionHeader(title = "Playlists")
        }
        // 2-column grid virtualized via the outer LazyColumn: each chunked
        // row is its own LazyColumn item, so only the rows near the viewport
        // get composed/measured. The pre-Phase-8 version wrapped the whole
        // grid in a single item{} + non-lazy Column, which forced every
        // PlaylistGridCard (33+ in a typical library) to compose + layout +
        // load its AsyncImage every time the parent item was near-visible.
        // That was the dominant source of vertical-scroll jank; horizontal
        // carousels stayed smooth because they were already real LazyRows.
        itemsIndexed(
            items = playlistGridRows,
            key = { index, row ->
                // Row-stable key so LazyColumn can reuse layouts when the
                // user's playlist list mutates (rename, delete, reorder).
                row.joinToString("-") { tile ->
                    when (tile) {
                        PlaylistTile.Create -> "create"
                        is PlaylistTile.Item -> tile.playlist.id.toString()
                    }
                }
            },
        ) { index, rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        // Spacing between rows — previously handled by the
                        // outer Column's spacedBy(12). Done inline here so
                        // each item carries its own bottom padding.
                        top = if (index == 0) 0.dp else 12.dp,
                    ),
            ) {
                rowItems.forEach { tile ->
                    when (tile) {
                        is PlaylistTile.Create -> CreatePlaylistCard(
                            onClick = { showCreateDialog = true },
                            modifier = Modifier.weight(1f),
                        )
                        is PlaylistTile.Item -> PlaylistGridCard(
                            playlist = tile.playlist,
                            onClick = { onNavigateToPlaylist(tile.playlist.id) },
                            onLongPress = { selectedPlaylist = tile.playlist },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                // Pad single-item rows with a spacer
                if (rowItems.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    // ── Create playlist naming dialog ────────────────────────────────────
    if (showCreateDialog) {
        CreatePlaylistDialog(
            onConfirm = { name ->
                viewModel.createPlaylist(name)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
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
        var alsoBlacklist by remember(playlist.id) { mutableStateOf(false) }
        var preview by remember(playlist.id) {
            mutableStateOf<HomeViewModel.DeletePreview?>(null)
        }
        // Load the cascade preview (deleted vs. kept-due-to-protection)
        // as soon as the dialog opens so the copy is accurate.
        LaunchedEffect(playlist.id) {
            preview = viewModel.previewPlaylistDelete(playlist)
        }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Delete ${playlist.name}?") },
            text = {
                Column {
                    val p = preview
                    Text(
                        text = when {
                            p == null -> "This will delete downloaded songs in this playlist from your device."
                            p.protectedCount == 0 -> "This will delete ${p.willDelete} downloaded song${if (p.willDelete != 1) "s" else ""} from your device."
                            else -> "${p.willDelete} song${if (p.willDelete != 1) "s" else ""} will be deleted. ${p.protectedCount} ${if (p.protectedCount != 1) "are also in" else "is also in"} Liked Songs or a custom playlist and will stay."
                        },
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
                androidx.compose.material3.TextButton(
                    onClick = {
                        viewModel.deletePlaylistAndSongs(playlist, alsoBlacklist)
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
            // Uses SyncDisplayStatus so "Completed with some failures" and
            // "Interrupted mid-run" don't both read as a generic failure.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulseDot(color = syncStatusDotColor(syncStatus, anyServiceConnected, hasEverSynced))
                Text(
                    text = syncStatusLabel(syncStatus, anyServiceConnected, hasEverSynced),
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

/**
 * Label shown next to the pulse dot in [SyncStatusCard]. Interprets
 * [SyncStatusInfo.displayStatus] so partial / interrupted runs aren't
 * misreported as generic failures.
 */
@Composable
private fun syncStatusLabel(
    syncStatus: SyncStatusInfo,
    anyServiceConnected: Boolean,
    hasEverSynced: Boolean,
): String = when {
    !anyServiceConnected -> "No services connected"
    !hasEverSynced -> "Ready to sync"
    else -> when (val s = syncStatus.displayStatus) {
        SyncDisplayStatus.Idle -> "Ready to sync"
        SyncDisplayStatus.Running -> "Syncing..."
        SyncDisplayStatus.Success -> "Synced"
        is SyncDisplayStatus.PartialSuccess ->
            "Partially synced — ${s.downloaded} saved, ${s.failed} failed"
        is SyncDisplayStatus.Interrupted ->
            if (s.downloaded > 0) "Interrupted — ${s.downloaded} saved"
            else "Interrupted"
        is SyncDisplayStatus.Failed -> "Sync failed"
    }
}

/**
 * Color for the pulse dot in [SyncStatusCard]. Green = success-ish,
 * amber = in-progress / warning, red = genuine failure, gray = idle.
 */
@Composable
private fun syncStatusDotColor(
    syncStatus: SyncStatusInfo,
    anyServiceConnected: Boolean,
    hasEverSynced: Boolean,
): Color {
    val extendedColors = StashTheme.extendedColors
    return when {
        !anyServiceConnected -> MaterialTheme.colorScheme.onSurfaceVariant
        !hasEverSynced -> extendedColors.warning
        else -> when (syncStatus.displayStatus) {
            SyncDisplayStatus.Idle -> extendedColors.warning
            SyncDisplayStatus.Running -> extendedColors.warning
            SyncDisplayStatus.Success -> extendedColors.success
            is SyncDisplayStatus.PartialSuccess -> extendedColors.warning
            is SyncDisplayStatus.Interrupted -> extendedColors.warning
            is SyncDisplayStatus.Failed -> Color(0xFFEF4444)
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
            // Album art background. For daily mixes with 2 tile URLs
            // (first 2 unique album covers from the current tracklist) we
            // render them side-by-side — the cover updates visibly every
            // sync that rotates tracks. Single-URL playlists render as a
            // single background as before.
            DailyMixCoverBackground(
                tileUrls = playlist.artTileUrls,
                fallback = playlist.artUrl,
                modifier = Modifier.fillMaxSize(),
            )
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
                    // Text always renders white because the card always has a
                    // dark bottom gradient overlay (Black alpha 0.6) by design.
                    // Using theme-aware onSurface would make the text disappear
                    // on light theme where onSurface is near-black.
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${playlist.trackCount} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
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

// ── Mixes section header with optional Play Both button ─────────────────

/**
 * Custom header for the "Your Mixes" section. Shows the title on the left
 * and an optional "Play Both" pill button on the right that plays every
 * mix from both Spotify and YouTube Music combined.
 *
 * The Play Both pill only renders when [showPlayBoth] is true, so users
 * connected to only one service see a plain header instead.
 *
 * @param showPlayBoth Whether to render the Play Both pill. True when the
 *   user has mixes from both sources.
 * @param onPlayBoth Callback invoked when the Play Both pill is tapped.
 */
@Composable
private fun MixesSectionHeader(
    showPlayBoth: Boolean,
    onPlayBoth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Your Mixes",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (showPlayBoth) {
            val accent = MaterialTheme.colorScheme.primary
            Surface(
                modifier = Modifier
                    .height(32.dp)
                    .clickable(onClick = onPlayBoth),
                color = accent.copy(alpha = 0.14f),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play mixes from both services",
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Play Both",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                }
            }
        }
    }
}

// ── Source sub-header (used for grouping mix rows by service) ────────────

/**
 * A row header used to separate mix rows by source. Contains a colored
 * source indicator, a label (e.g. "Spotify" or "YouTube Music"), and an
 * optional trailing "Play All" button that plays every track from every
 * mix under that source, effectively merging them into one queue.
 *
 * @param label Display label for the source.
 * @param source The source this header represents (for color/indicator).
 * @param onPlayAll Optional callback. When non-null, renders a trailing
 *   play-all button that invokes this lambda on tap.
 */
@Composable
private fun SourceSubHeader(
    label: String,
    source: MusicSource,
    modifier: Modifier = Modifier,
    onPlayAll: (() -> Unit)? = null,
) {
    val extendedColors = StashTheme.extendedColors
    val accent = when (source) {
        MusicSource.SPOTIFY -> extendedColors.spotifyGreen
        MusicSource.YOUTUBE -> extendedColors.youtubeRed
        MusicSource.LOCAL, MusicSource.BOTH -> MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SourceIndicator(source = source, size = 8.dp)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (onPlayAll != null) {
            Surface(
                modifier = Modifier
                    .height(32.dp)
                    .clickable(onClick = onPlayAll),
                color = accent.copy(alpha = 0.12f),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play all $label mixes",
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Play All",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                }
            }
        }
    }
}

// ── Liked songs card (with source chips + smart collapse) ───────────────

/**
 * Featured card showing liked-songs across Spotify and YouTube Music.
 *
 * Layout:
 * - Tappable main row plays the combined pool (both sources).
 * - When [showSourceChips] is true (both sources have liked songs), a pair
 *   of tappable chips below the main row plays only one source at a time.
 * - When only one source contributes, chips are hidden and a small source
 *   indicator appears next to the title to identify which service the
 *   count represents.
 *
 * @param totalCount Combined liked-song count across both sources.
 * @param spotifyCount Spotify liked-song count (0 if none).
 * @param youtubeCount YouTube liked-song count (0 if none).
 * @param showSourceChips Whether to render per-source chips.
 * @param singleSource The sole contributing source when [showSourceChips] is
 *   false, used to label the card; null when both or neither source contributes.
 * @param onPlayAll Invoked when the main card body is tapped.
 * @param onPlaySpotify Invoked when the Spotify chip is tapped.
 * @param onPlayYouTube Invoked when the YouTube chip is tapped.
 */
@Composable
private fun LikedSongsCard(
    totalCount: Int,
    spotifyCount: Int,
    youtubeCount: Int,
    showSourceChips: Boolean,
    singleSource: MusicSource?,
    onPlayAll: () -> Unit,
    onPlaySpotify: () -> Unit,
    onPlayYouTube: () -> Unit,
    onClick: () -> Unit,
    onClickSpotify: () -> Unit,
    onClickYouTube: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    val infiniteTransition = rememberInfiniteTransition(label = "livingHeart")

    // Shifting gradient — cycles through purple hues
    val gradientColor1 by infiniteTransition.animateColor(
        initialValue = extendedColors.purpleLight,
        targetValue = extendedColors.purpleDark,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "gradientColor1",
    )
    val gradientColor2 by infiniteTransition.animateColor(
        initialValue = extendedColors.purpleDark,
        targetValue = extendedColors.purpleLight,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "gradientColor2",
    )

    // Breathing glow — shadow radius pulses
    val glowRadius by infiniteTransition.animateFloat(
        initialValue = 8f,
        targetValue = 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowRadius",
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowAlpha",
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = extendedColors.glassBackground,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            Color.Transparent,
                        )
                    )
                ),
        ) {
            // Main row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Text content on the left
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "YOUR COLLECTION",
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 1.5.sp,
                        ),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Liked Songs",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (singleSource != null) {
                            SourceIndicator(source = singleSource, size = 6.dp)
                        }
                    }
                    Text(
                        text = when (singleSource) {
                            MusicSource.SPOTIFY -> "$totalCount tracks on Spotify"
                            MusicSource.YOUTUBE -> "$totalCount tracks on YouTube Music"
                            else -> "$totalCount tracks \u00B7 2 sources"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Living heart icon on the right
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .drawBehind {
                            drawCircle(
                                color = gradientColor1.copy(alpha = glowAlpha),
                                radius = glowRadius.dp.toPx(),
                            )
                        }
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(gradientColor1, gradientColor2)
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
            }

            // Source chips — compact pills, dot + count only
            if (showSourceChips) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SourceLikedChip(
                        source = MusicSource.SPOTIFY,
                        count = spotifyCount,
                        onClick = onClickSpotify,
                    )
                    SourceLikedChip(
                        source = MusicSource.YOUTUBE,
                        count = youtubeCount,
                        onClick = onClickYouTube,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceLikedChip(
    source: MusicSource,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    val accent = when (source) {
        MusicSource.SPOTIFY -> extendedColors.spotifyGreen
        MusicSource.YOUTUBE -> extendedColors.youtubeRed
        MusicSource.LOCAL, MusicSource.BOTH -> MaterialTheme.colorScheme.primary
    }
    val sourceName = when (source) {
        MusicSource.SPOTIFY -> "Spotify"
        MusicSource.YOUTUBE -> "YouTube"
        MusicSource.LOCAL -> "Local"
        MusicSource.BOTH -> ""
    }

    Surface(
        modifier = modifier
            .clickable(onClick = onClick),
        color = accent.copy(alpha = 0.12f),
        shape = RoundedCornerShape(20.dp),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accent)
                    .semantics {
                        contentDescription = "$count $sourceName liked songs"
                    },
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
        }
    }
}

// ── Daily mix cover background ───────────────────────────────────────────

/**
 * Renders the cover art for a daily-mix card. When 2 tile URLs are supplied,
 * draws them side-by-side as a 50/50 horizontal mosaic so users see visible
 * proof that the mix refreshed. With fewer URLs, falls back to a single
 * [AsyncImage] using [fallback]. Draws nothing when neither is available.
 */
@Composable
private fun DailyMixCoverBackground(
    tileUrls: List<String>,
    fallback: String?,
    modifier: Modifier = Modifier,
) {
    when {
        tileUrls.size >= 2 -> Row(modifier = modifier) {
            AsyncImage(
                model = tileUrls[0],
                contentDescription = null,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentScale = ContentScale.Crop,
            )
            AsyncImage(
                model = tileUrls[1],
                contentDescription = null,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentScale = ContentScale.Crop,
            )
        }
        fallback != null -> AsyncImage(
            model = fallback,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
        else -> Unit
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

/**
 * Sealed tile type used to interleave the Create-Playlist entry point with
 * the user's custom playlists inside the Home Playlists 2-column grid.
 */
private sealed interface PlaylistTile {
    object Create : PlaylistTile
    data class Item(val playlist: Playlist) : PlaylistTile
}

// ── Create playlist card ────────────────────────────────────────────────

/**
 * First tile in the Playlists grid. Tapping it opens the naming dialog to
 * create a new empty custom playlist. Styled to match [PlaylistGridCard]
 * so the grid reads consistently.
 */
@Composable
private fun CreatePlaylistCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors

    Surface(
        modifier = modifier
            .height(100.dp)
            .clickable(onClick = onClick),
        color = extendedColors.glassBackground,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = "Create Playlist",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
