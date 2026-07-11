package com.stash.feature.home

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
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.stash.core.ui.theme.SpaceGrotesk
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.Crossfade
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.mix.MixBuildState
import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.stash.core.ui.components.CreatePlaylistDialog
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.components.SectionHeader
import com.stash.core.ui.components.SourceIndicator
import com.stash.core.ui.theme.LocalIsDarkTheme
import com.stash.core.ui.components.streaming.StreamingModeChip
import com.stash.core.ui.components.streaming.StreamingModeSheet
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.stash.core.ui.theme.StashTheme

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
    onNavigateToSettings: () -> Unit = {},
    onNavigateToMixBuilder: (Long?) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Master streaming-mode flag. Both the top-bar StreamingModeChip and
    // the sheet (StreamingModeSheet) render from this single source of
    // truth; the chip itself early-returns to nothing while the build-
    // time kill-switch (StashConstants.STREAMING_ENGINE_ENABLED) is off.
    val streamingEnabled by viewModel.streamingEnabled.collectAsStateWithLifecycle()

    // Bottom-sheet state for the playback-mode picker triggered by the
    // top-bar chip. The sheet is the chip's tap target — keeps the chip
    // a single thumb-friendly icon-and-label while still routing a flip
    // through the OnlineOfflinePicker so the user explicitly chooses a
    // tile rather than accidentally toggling mid-scroll.
    var showStreamingSheet by remember { mutableStateOf(false) }
    val streamingSheetState = rememberModalBottomSheetState()

    // One-time privacy disclosure dialog for streaming. Shown the first
    // time the user enables streaming; the ViewModel persists the
    // "seen" flag and emits a Unit signal on the SharedFlow. The toggle
    // itself flips instantly — this dialog is informational, not a
    // confirmation gate.
    var showStreamingDisclosure by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.showStreamingDisclosure.collect {
            showStreamingDisclosure = true
        }
    }

    // Playlist selected for the context-menu bottom sheet (shared across daily mixes + grid).
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    // Controls the "New Playlist" naming dialog launched from the Playlists section.
    var showCreateDialog by remember { mutableStateOf(false) }

    val toastContext = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.userMessages.collect { msg ->
            android.widget.Toast.makeText(toastContext, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

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
        // ── App title row: wordmark + social icons ────────────────────
        // v0.9.13: empty space to the right of the wordmark holds quick
        // links to the project (GitHub, X). Supporter pill moves back
        // to its own full-width row below.
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val isDark = LocalIsDarkTheme.current
                Image(
                    painter = painterResource(
                        id = if (isDark) R.drawable.wordmark_stash_dark
                        else R.drawable.wordmark_stash_light,
                    ),
                    contentDescription = stringResource(com.stash.core.ui.R.string.cd_app_logo),
                    modifier = Modifier.height(48.dp),
                )
                Spacer(modifier = Modifier.weight(1f))

                // Streaming-mode quick-access chip. Tap → opens the
                // StreamingModeSheet for the picker. Gated on
                // STREAMING_ENGINE_ENABLED inside the composable so it
                // renders nothing when the flag is off.
                StreamingModeChip(
                    streamingEnabled = streamingEnabled,
                    onClick = { showStreamingSheet = true },
                )

                Spacer(modifier = Modifier.width(8.dp))

                val socialUriHandler = LocalUriHandler.current
                androidx.compose.material3.IconButton(
                    onClick = { socialUriHandler.openUri(STASH_DISCORD_URL) },
                    modifier = Modifier.size(40.dp),
                ) {
                    androidx.compose.material3.Icon(
                        painter = androidx.compose.ui.res.painterResource(
                            id = R.drawable.ic_discord,
                        ),
                        contentDescription = stringResource(com.stash.core.ui.R.string.cd_join_discord),
                        tint = androidx.compose.ui.graphics.Color(0xFF5865F2),
                        modifier = Modifier.size(20.dp),
                    )
                }

                androidx.compose.material3.IconButton(
                    onClick = { socialUriHandler.openUri(STASH_ISSUE_URL) },
                    modifier = Modifier.size(40.dp),
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Filled.Build,
                        contentDescription = stringResource(com.stash.core.ui.R.string.cd_report_issue),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // ── Supporter pill (full row) ─────────────────────────────────
        // v0.9.13: live data from TipJarRepository. Tap → ko-fi.
        item {
            val tipJar = uiState.tipJar
            val pillSupporters = remember(tipJar) {
                tipJar.supporters.map {
                    Supporter(name = it.name, amount = "$${it.amountUsd}", message = it.message)
                }.ifEmpty { HOME_SUPPORTERS }
            }
            SupporterPill(
                supporters = pillSupporters,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 12.dp),
            )
        }

        // ── Powered-by-ARCOD strip: removed 2026-07-01 while ARCOD is parked
        // (host down for us). PartnerStrip + ArcodPartner kept for re-enabling.

        // ── Lossless connect nudge ───────────────────────────────────
        // Shown when the user has lossless toggled OFF and hasn't
        // dismissed. Tap routes to Settings; X dismisses forever.
        // Stacks below the Last.fm banner if both apply.
        uiState.losslessPrompt?.let {
            item {
                Spacer(Modifier.height(6.dp))
                LosslessConnectBanner(
                    onSetUp = {
                        viewModel.requestSettingsLosslessFocus()
                        onNavigateToSettings()
                    },
                    onDismiss = viewModel::dismissLosslessBanner,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // ── Re-tagging library (metadata backfill progress) ──────────
        // v0.9.35: surfaces MetadataBackfillWorker progress on upgrade
        // so users know why disk IO / yt-dlp activity is happening. The
        // banner renders Hidden in the steady state (post-backfill); the
        // 2-second "Done" pulse self-acks via LaunchedEffect inside the
        // composable.
        if (uiState.metadataBackfillBanner !is com.stash.feature.home.banner.MetadataBackfillBannerState.Hidden) {
            item {
                Spacer(Modifier.height(6.dp))
                com.stash.feature.home.banner.MetadataBackfillBanner(
                    state = uiState.metadataBackfillBanner,
                    onFinishedAcknowledged = viewModel::onMetadataBackfillFinishedAcknowledged,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
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
                        label = stringResource(com.stash.core.ui.R.string.label_spotify),
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
                        label = stringResource(com.stash.core.ui.R.string.label_youtube_music),
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
        //
        // The header + row render unconditionally (no `stashMixes.isNotEmpty`
        // gate) so the trailing "Create mix" tile is ALWAYS reachable, even
        // for a user with zero mixes. When mixes exist the layout is
        // identical to before — they render first, Create tile last.
        item {
            SectionHeader(title = stringResource(com.stash.core.ui.R.string.section_stash_mixes))
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Create tile leads the row (a compact "add" affordance).
                item {
                    CreateMixCard(onClick = { onNavigateToMixBuilder(null) })
                }
                items(uiState.stashMixes, key = { it.id }) { playlist ->
                    val buildState = when {
                        uiState.buildingMixIds.contains(playlist.id) -> MixBuildState.BUILDING
                        uiState.emptyMixIds.contains(playlist.id) -> MixBuildState.EMPTY
                        else -> MixBuildState.READY
                    }
                    DailyMixCard(
                        playlist = playlist,
                        buildState = buildState,
                        onClick = {
                            // Opening a stale custom mix transparently
                            // refreshes it (fire-and-forget; no-ops for
                            // builtins + non-stale mixes).
                            viewModel.refreshMixIfStale(playlist.id)
                            onNavigateToPlaylist(playlist.id)
                        },
                        onLongPress = { selectedPlaylist = playlist },
                    )
                }
            }
        }

        // ── Recently Added ───────────────────────────────────────────
        if (uiState.recentlyAdded.isNotEmpty()) {
            item {
                SectionHeader(title = stringResource(com.stash.core.ui.R.string.section_recently_added))
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
            SectionHeader(title = stringResource(com.stash.core.ui.R.string.section_playlists))
        }
        item {
            PlaylistSortChipRow(
                activeSort = uiState.playlistSortOrder,
                onSortSelected = viewModel::setPlaylistSortOrder,
            )
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

    // ── Streaming privacy disclosure (first-use only) ────────────────────
    if (showStreamingDisclosure) {
        com.stash.feature.home.streaming.StreamingDisclosureDialog(
            onDismiss = { showStreamingDisclosure = false },
        )
    }

    // ── Streaming-mode picker sheet (chip → bottom sheet) ──────────────
    // Opens from the top-bar chip. The picker writes through the same
    // applyStreamingMode path as Settings; here we also run the
    // first-time disclosure handshake (HomeViewModel.onStreamingToggle).
    // Sheet auto-dismisses on tile selection so a flip is exactly two
    // taps: chip → tile.
    if (showStreamingSheet) {
        StreamingModeSheet(
            streamingEnabled = streamingEnabled,
            onSelect = { requested ->
                viewModel.onStreamingToggle(requested)
                showStreamingSheet = false
            },
            onDismiss = { showStreamingSheet = false },
            sheetState = streamingSheetState,
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

            // v0.9.16: Manual single-mix refresh — only meaningful for
            // recipe-driven Stash Mixes, not sync-imported daily mixes or
            // user playlists.
            if (playlist.type == PlaylistType.STASH_MIX) {
                HomeBottomSheetActionRow(
                    icon = Icons.Default.Refresh,
                    label = stringResource(com.stash.core.ui.R.string.action_refresh_mix),
                    onClick = {
                        viewModel.refreshMix(playlist.id)
                        selectedPlaylist = null
                    },
                )
            }

            // Edit / Delete — only for user-built (non-builtin) Stash Mixes.
            // Gated on customMixPlaylistIds so builtin recipe playlists (which
            // can't be edited or deleted here) never surface these rows.
            if (uiState.customMixPlaylistIds.contains(playlist.id)) {
                HomeBottomSheetActionRow(
                    icon = Icons.Default.Edit,
                    label = stringResource(com.stash.core.ui.R.string.action_edit_mix),
                    onClick = {
                        // Resolve the recipe id async, then route to the
                        // builder. Dismiss the sheet immediately.
                        viewModel.editRecipeId(playlist.id) { recipeId ->
                            onNavigateToMixBuilder(recipeId)
                        }
                        selectedPlaylist = null
                    },
                )
                HomeBottomSheetActionRow(
                    icon = Icons.Default.Delete,
                    label = stringResource(com.stash.core.ui.R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = {
                        viewModel.deleteCustomMix(playlist)
                        selectedPlaylist = null
                    },
                )
            }
            HomeBottomSheetActionRow(
                icon = Icons.Default.PlayArrow,
                label = stringResource(com.stash.core.ui.R.string.action_play_all),
                onClick = {
                    viewModel.playPlaylist(playlist)
                    selectedPlaylist = null
                },
            )
            HomeBottomSheetActionRow(
                icon = Icons.Default.PlaylistAdd,
                label = stringResource(com.stash.core.ui.R.string.action_add_to_queue),
                onClick = {
                    viewModel.addPlaylistToQueue(playlist)
                    selectedPlaylist = null
                },
            )
            HomeBottomSheetActionRow(
                icon = Icons.Default.Download,
                label = stringResource(com.stash.core.ui.R.string.action_download_all),
                onClick = {
                    viewModel.queueDownloadsForPlaylist(playlist)
                    selectedPlaylist = null
                },
            )
            HomeBottomSheetActionRow(
                icon = Icons.Default.DownloadDone,
                label = stringResource(com.stash.core.ui.R.string.action_remove_downloads),
                onClick = {
                    viewModel.removeDownloadsForPlaylist(playlist)
                    selectedPlaylist = null
                },
            )
            HomeBottomSheetActionRow(
                icon = Icons.Default.RemoveCircleOutline,
                label = stringResource(com.stash.core.ui.R.string.action_remove_playlist),
                onClick = {
                    viewModel.removePlaylist(playlist)
                    selectedPlaylist = null
                },
            )
            HomeBottomSheetActionRow(
                icon = Icons.Default.Delete,
                label = stringResource(com.stash.core.ui.R.string.action_delete_playlist_and_songs),
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
            title = { Text(stringResource(com.stash.core.ui.R.string.dialog_title_delete_playlist, playlist.name)) },
            text = {
                Column {
                    val p = preview
                    Text(
                        text = when {
                            p == null -> stringResource(com.stash.core.ui.R.string.dialog_body_delete_playlist)
                            p.protectedCount == 0 -> stringResource(com.stash.core.ui.R.string.dialog_body_delete_playlist_cascade, p.willDelete, 0)
                            else -> stringResource(com.stash.core.ui.R.string.dialog_body_delete_playlist_cascade, p.willDelete, p.protectedCount)
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
                                text = stringResource(com.stash.core.ui.R.string.label_also_block_songs),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(com.stash.core.ui.R.string.desc_blocked_songs_hint),
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
                        text = if (alsoBlacklist) stringResource(com.stash.core.ui.R.string.action_delete_and_block) else stringResource(com.stash.core.ui.R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { playlistToDelete = null }) {
                    Text(stringResource(com.stash.core.ui.R.string.action_cancel))
                }
            },
        )
    }
}

// ── Daily mix card ───────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DailyMixCard(
    playlist: Playlist,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    buildState: MixBuildState = MixBuildState.READY,
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
                    when (buildState) {
                        MixBuildState.BUILDING -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(11.dp),
                                color = Color.White.copy(alpha = 0.85f),
                                strokeWidth = 1.5.dp,
                            )
                            Text(
                                text = stringResource(com.stash.core.ui.R.string.status_building),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.75f),
                            )
                        }
                        MixBuildState.EMPTY -> Text(
                            text = stringResource(com.stash.core.ui.R.string.label_no_tracks_found),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.75f),
                        )
                        MixBuildState.READY -> Text(
                            text = "${playlist.trackCount} tracks",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.75f),
                        )
                    }
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
            text = stringResource(com.stash.core.ui.R.string.section_your_mixes),
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
                        contentDescription = stringResource(com.stash.core.ui.R.string.cd_play_both_mixes),
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(com.stash.core.ui.R.string.action_play_both),
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
                        contentDescription = stringResource(com.stash.core.ui.R.string.cd_play_all_mixes, label),
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(com.stash.core.ui.R.string.action_play_all),
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
                        text = stringResource(com.stash.core.ui.R.string.section_your_collection),
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
                            text = stringResource(com.stash.core.ui.R.string.label_liked_songs),
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
        MusicSource.SPOTIFY -> stringResource(com.stash.core.ui.R.string.filter_spotify)
        MusicSource.YOUTUBE -> stringResource(com.stash.core.ui.R.string.filter_youtube)
        MusicSource.LOCAL -> stringResource(com.stash.core.ui.R.string.filter_local)
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
                text = stringResource(com.stash.core.ui.R.string.action_create_playlist),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Create mix card ──────────────────────────────────────────────────────

/**
 * Leading tile in the Stash Mixes row. Tapping it opens the Mix Builder
 * to create a brand-new custom mix (recipeId = null). Compact (104×120 — a
 * narrow "add" affordance, not a full 180-wide mix card) with a dashed glass
 * border, mirroring the Playlists grid's [CreatePlaylistCard] affordance.
 */
@Composable
private fun CreateMixCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    val accent = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .width(104.dp)
            .height(120.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(extendedColors.glassBackground)
            .drawBehind {
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        floatArrayOf(8.dp.toPx(), 6.dp.toPx()),
                        0f,
                    ),
                )
                drawRoundRect(
                    color = accent.copy(alpha = 0.5f),
                    style = stroke,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                )
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = stringResource(com.stash.core.ui.R.string.action_create_mix),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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

// ── Lossless connect banner ──────────────────────────────────────────────

/**
 * "Try lossless audio" Home banner. Shows when the user has
 * lossless turned off (explicit save, since v0.9.8 fresh installs
 * default to ON) and hasn't dismissed. Tapping routes to Settings,
 * where the existing Audio Quality card hosts the toggle + captcha
 * setup flow.
 */
@Composable
private fun LosslessConnectBanner(
    onSetUp: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.tertiary
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = accent.copy(alpha = 0.10f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSetUp)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(com.stash.core.ui.R.string.prompt_try_lossless),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(com.stash.core.ui.R.string.desc_lossless_prompt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(com.stash.core.ui.R.string.action_setup_lossless),
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(com.stash.core.ui.R.string.cd_dismiss_lossless_banner),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ── Supporter pill ───────────────────────────────────────────────────────

private data class Supporter(
    val name: String,
    val amount: String,
    val message: String,
)

// v0.9.13: report-an-issue link shown as a wrench icon next to the
// wordmark on Home. Tap → GitHub new-issue form so users can file
// bugs without leaving the project. Edit when the repo URL changes.
private const val STASH_ISSUE_URL = "https://github.com/rawnaldclark/Stash/issues/new"

// v0.9.38+: Discord invite shown as a chat-bubble icon (blurple-tinted)
// to the left of the wrench. Tap → opens the invite in the default
// browser. Edit when the invite rotates.
private const val STASH_DISCORD_URL = "https://discord.gg/vcbjEby5PC"

private val HOME_SUPPORTERS = listOf(
    Supporter(
        name = "Cedric",
        amount = "$10",
        message = "Just downloaded Stash to replace Spotify. This is amazing bro. Thanks for your work.",
    ),
    Supporter(
        name = "Slowcab",
        amount = "$5",
        message = "Amazing work! Keep sticking it to the man!",
    ),
    Supporter(
        name = "RucaNebas",
        amount = "$5",
        message = "Awesome application! I hope continuous improvement and support",
    ),
)

/**
 * v0.9.13: Tip Jar pill — calmer, on-brand redesign.
 *
 * Replaces the prior typewriter+sheet approach (felt corny). Now:
 *  - Small mono lowercase `tip jar` tag with subtle purple glow
 *  - Avatar circle with the supporter's initial (track-row vocabulary)
 *  - Name in Space Grotesk Bold (heroic, like a song title)
 *  - Amount right-aligned in mono (the only number on the surface)
 *  - Message in Inter italic, low-contrast (testimonial / liner-note)
 *  - Footer hint `ko-fi.com/rawnald →` so the tap target is obvious
 *  - Crossfade between supporters every ~6s
 *
 * Tap on the whole card opens ko-fi in the browser. No in-app sheet —
 * goal/progress tracking lives at ko-fi where it actually happens.
 */
@Composable
private fun SupporterPill(
    supporters: List<Supporter>,
    modifier: Modifier = Modifier,
) {
    if (supporters.isEmpty()) return
    val uriHandler = LocalUriHandler.current
    val extendedColors = StashTheme.extendedColors

    var index by remember { mutableStateOf(0) }
    LaunchedEffect(supporters.size) {
        while (true) {
            kotlinx.coroutines.delay(7000)
            if (supporters.isNotEmpty()) {
                index = (index + 1) % supporters.size
            }
        }
    }
    val current = supporters[index.coerceIn(0, supporters.lastIndex)]

    Surface(
        modifier = modifier.clickable { uriHandler.openUri("https://ko-fi.com/rawnald") },
        color = extendedColors.glassBackground,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.FavoriteBorder,
                contentDescription = stringResource(com.stash.core.ui.R.string.cd_supporters_kofi),
                tint = Color(0xFFFFC947),
                modifier = Modifier
                    .size(16.dp)
                    .padding(top = 2.dp),
            )
            Crossfade(
                targetState = current,
                animationSpec = tween(durationMillis = 600),
                label = "supporter-crossfade",
                modifier = Modifier.weight(1f),
            ) { s ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "${s.name} \u00B7 ${s.amount}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "\u201C${s.message}\u201D",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontStyle = FontStyle.Italic,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Cap long donation messages at 2 lines so a paragraph
                        // can't balloon the pill height; ellipsis trims the rest.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ── Playlist sort chips ──────────────────────────────────────────────────

/**
 * Horizontally-scrollable row of filter chips controlling the sort applied to
 * the Home Playlists grid. Deliberately mirrors the Library module's
 * SortChipRow so the two surfaces feel identical.
 */
@Composable
private fun PlaylistSortChipRow(
    activeSort: PlaylistSortOrder,
    onSortSelected: (PlaylistSortOrder) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlaylistSortOrder.entries.forEach { order ->
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

@Composable
private fun PlaylistSortOrder.displayName(): String = when (this) {
    PlaylistSortOrder.RECENT -> stringResource(com.stash.core.ui.R.string.sort_recently_added)
    PlaylistSortOrder.ALPHABETICAL -> stringResource(com.stash.core.ui.R.string.sort_alphabetical)
    PlaylistSortOrder.MOST_PLAYED -> stringResource(com.stash.core.ui.R.string.sort_most_played)
}

