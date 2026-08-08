package com.stash.feature.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Restore
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncMode
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.theme.SpaceGrotesk
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.sync.components.AuthExpiredBanner
import com.stash.feature.sync.components.RecentSyncsCard
import com.stash.feature.sync.components.SyncHeroCard
import com.stash.feature.sync.components.SyncActionProgress
import com.stash.feature.sync.components.SyncStatusCard
import com.stash.feature.sync.components.StatusPill
import com.stash.feature.sync.components.formatRelativeTime

/**
 * Main Sync screen.
 *
 * Displays connected source status, schedule configuration, a manual sync
 * trigger with live progress, and recent sync history.
 */
@Composable
fun SyncScreen(
    modifier: Modifier = Modifier,
    onNavigateToFailedMatches: () -> Unit = {},
    onNavigateToBlockedSongs: () -> Unit = {},
    onNavigateToFailedDownloads: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onManageSource: (SyncSource) -> Unit = {},
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val blockedCount by viewModel.blockedCount.collectAsStateWithLifecycle()
    val failedDownloadsCount by viewModel.failedDownloadsCount.collectAsStateWithLifecycle()
    val syncLogLines by viewModel.syncLogLines.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val undoPoint by viewModel.undoPoint.collectAsStateWithLifecycle()
    val undoInProgress by viewModel.undoInProgress.collectAsStateWithLifecycle()
    val undoResult by viewModel.undoResult.collectAsStateWithLifecycle()
    var confirmUndo by remember { mutableStateOf(false) }
    val authState by viewModel.authExpiry.collectAsStateWithLifecycle()
    val streamingMode by viewModel.streamingEnabled.collectAsStateWithLifecycle()
    val playbackOnline by viewModel.playbackOnline.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // -- Auth expiry banner ----------------------------------------------
        // Mounted ABOVE the SyncStatusCard so users see "session expired"
        // before anything else when probes flag expired Spotify/YouTube
        // credentials. The banner renders zero-height when neither source
        // is expired, so it's a no-op for healthy logins.
        item {
            AuthExpiredBanner(
                state = authState,
                onReauth = onNavigateToSettings,
            )
        }

        // -- Sync status card (relocated from Home) ---------------------------
        // Lives at the very top of the Sync tab so library-status info
        // sits with the rest of the Sync surface (was previously the
        // first content card on Home, directly under the supporter pill).
        item {
            Spacer(Modifier.height(8.dp))
            SyncStatusCard(
                syncStatus = uiState.syncStatus,
                spotifyConnected = uiState.spotifyConnected,
                youTubeConnected = uiState.youTubeConnected,
                hasEverSynced = uiState.hasEverSynced,
            )
        }

        // -- Header -----------------------------------------------------------
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Sync",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
            )
        }

        // -- Hero Card: last-sync stats + Sync Now / progress -----------------
        item {
            SyncHeroCard(
                lastSyncRelativeTime = uiState.lastSyncRelativeTime,
                lastSyncTrackCount = uiState.lastSyncTrackCount,
                healthLabel = uiState.lastSyncHealthLabel,
                healthColor = uiState.lastSyncHealthColor,
                isSyncing = uiState.isSyncing,
                playbackOnline = playbackOnline,
                onPlaybackModeChange = viewModel::setPlaybackOnline,
                downloadOnline = streamingMode,
                onDownloadModeChange = viewModel::setStreamingEnabled,
                onSyncNow = viewModel::onSyncNow,
                progressContent = {
                    SyncActionProgress(
                        phase = uiState.syncPhase,
                        progress = uiState.overallProgress,
                        onStopSync = viewModel::onStopSync,
                    )
                },
            )
        }

        // -- Songs that need review card --------------------------------------
        // Covers both "couldn't match during sync" (unmatched) and "user
        // flagged as wrong from Now Playing" (flagged). Without the flagged
        // branch, songs flagged on Now Playing had nowhere to surface —
        // the Failed Matches screen was unreachable without at least one
        // unmatched song alongside them.
        val reviewCount = uiState.unmatchedCount + uiState.flaggedCount
        if (reviewCount > 0) {
            item(key = "review_queue") {
                UnmatchedSongsCard(
                    count = reviewCount,
                    unmatchedCount = uiState.unmatchedCount,
                    flaggedCount = uiState.flaggedCount,
                    onClick = onNavigateToFailedMatches,
                )
            }
        }

        // -- Failed downloads card -------------------------------------------
        // Sibling to the review-queue card. Surfaces tracks that couldn't
        // be downloaded (auth, network, storage, ...) so the user can
        // retry or block from one screen. Hidden when count is 0.
        if (failedDownloadsCount > 0) {
            item(key = "failed_downloads") {
                FailedDownloadsCard(
                    count = failedDownloadsCount,
                    onClick = onNavigateToFailedDownloads,
                )
            }
        }

        // -- Live sync terminal ----------------------------------------------
        // Only once a run has produced lines, so a fresh install shows no empty
        // black box. Sits above Undo: what happened, then how to take it back.
        if (syncLogLines.isNotEmpty()) {
            item(key = "sync_terminal") {
                SyncTerminalCard(
                    lines = syncLogLines,
                    onCopy = {
                        clipboard.setText(AnnotatedString(viewModel.syncLogAsText()))
                    },
                )
            }
        }

        // -- Undo last sync ---------------------------------------------------
        // Only present when a restore point exists. Sits directly under the sync
        // status so the fix is next to the thing that broke.
        undoPoint?.let { point ->
            item(key = "undo_sync") {
                UndoSyncCard(
                    playlistCount = point.playlistCount,
                    capturedAtEpochMs = point.createdAtEpochMs,
                    inProgress = undoInProgress,
                    onUndo = { confirmUndo = true },
                )
            }
        }

        // -- Sources section -------------------------------------------------
        item { SyncSectionLabel("Sources") }

        // -- Spotify source dashboard (above schedule) ------------------------
        // Rendered whenever Spotify is connected, even before the first sync
        // has populated the playlist list — so users can see what's coming.
        // Compact stats + mode chips + "Manage ›"; the per-playlist list lives
        // on the dedicated Manage screen (no endless-scroll on the landing).
        if (uiState.spotifyConnected) {
            item {
                val playlists = uiState.spotifyPlaylists
                val hasPlaylists = playlists.isNotEmpty()
                val purple = MaterialTheme.colorScheme.primary
                com.stash.feature.sync.components.SourcePreferencesCard(
                    name = "Spotify",
                    brandColor = StashTheme.extendedColors.spotifyGreen,
                    connected = uiState.spotifyConnected,
                    stats = {
                        if (hasPlaylists) {
                            SourceStatsRow(
                                mixesCount = playlists.count { it.type == PlaylistType.DAILY_MIX },
                                playlistsEnabled = playlists.count { it.type == PlaylistType.CUSTOM && it.syncEnabled },
                                playlistsTotal = playlists.count { it.type == PlaylistType.CUSTOM },
                                likedCount = playlists.firstOrNull { it.type == PlaylistType.LIKED_SONGS }?.trackCount,
                            )
                        } else {
                            SourceEmptyHint(
                                "Your Spotify playlists and daily mixes will appear here after " +
                                    "the first sync. Tap Sync Now to load them — nothing downloads " +
                                    "until you toggle it on.",
                            )
                        }
                    },
                    modeChips = {
                        if (hasPlaylists) {
                            SyncModeChipRow(
                                mode = uiState.spotifySyncMode,
                                onChange = viewModel::onSpotifySyncModeChanged,
                                onRequestRefresh = viewModel::onRequestSpotifyRefresh,
                                accent = purple,
                            )
                        }
                    },
                    onManage = { onManageSource(SyncSource.SPOTIFY) },
                )
            }
        }

        // -- YouTube source dashboard ----------------------------------------
        // Opt-in by default. The FIRST Sync Now fetches everything but
        // downloads nothing because every playlist starts disabled —
        // that's the staging/discover phase. Users pick what they want on
        // the Manage screen, tap Sync Now again, and downloads begin.
        if (uiState.youTubeConnected) {
            item {
                val playlists = uiState.youTubePlaylists
                val hasPlaylists = playlists.isNotEmpty()
                val accent = MaterialTheme.colorScheme.primary
                // "Other" = everything that isn't Liked or an auto Home Mix.
                val isOther = { t: PlaylistType ->
                    t != PlaylistType.LIKED_SONGS && t != PlaylistType.DAILY_MIX
                }
                com.stash.feature.sync.components.SourcePreferencesCard(
                    name = "YouTube Music",
                    brandColor = StashTheme.extendedColors.youtubeRed,
                    connected = uiState.youTubeConnected,
                    stats = {
                        if (hasPlaylists) {
                            SourceStatsRow(
                                mixesCount = playlists.count { it.type == PlaylistType.DAILY_MIX },
                                playlistsEnabled = playlists.count { isOther(it.type) && it.syncEnabled },
                                playlistsTotal = playlists.count { isOther(it.type) },
                                likedCount = playlists.firstOrNull { it.type == PlaylistType.LIKED_SONGS }?.trackCount,
                            )
                        } else {
                            SourceEmptyHint(
                                "Your Home Mixes, Liked Songs, and playlists from your YouTube " +
                                    "Music library will appear here after the first sync. Nothing " +
                                    "downloads until you pick what you want — no surprise downloads.",
                            )
                        }
                    },
                    modeChips = {
                        if (hasPlaylists) {
                            SyncModeChipRow(
                                mode = uiState.youtubeSyncMode,
                                onChange = viewModel::onYoutubeSyncModeChanged,
                                onRequestRefresh = viewModel::onRequestYoutubeRefresh,
                                accent = accent,
                            )
                        }
                    },
                    onManage = { onManageSource(SyncSource.YOUTUBE) },
                )
            }
        }

        // -- Schedule section -------------------------------------------------
        item { SyncSectionLabel("Schedule") }
        item {
            com.stash.feature.sync.components.ScheduleCard(
                autoSyncEnabled = uiState.syncPreferences.autoSyncEnabled,
                syncDays = com.stash.core.data.sync.DayOfWeekSet(uiState.syncDays),
                syncHour = uiState.syncPreferences.syncHour,
                syncMinute = uiState.syncPreferences.syncMinute,
                wifiOnly = uiState.syncPreferences.wifiOnly,
                onToggleAutoSync = viewModel::onToggleAutoSync,
                onSyncDaysChanged = { viewModel.onSyncDaysChanged(it.bitmask) },
                onTimeChanged = viewModel::onSetSyncTime,
                onToggleWifiOnly = viewModel::onToggleWifiOnly,
            )
        }

        // -- Library section --------------------------------------------------
        // Sync-adjacent maintenance: Blocked Songs gate what sync will
        // re-download, so the entry point lives here above Recent Syncs.
        item { SyncSectionLabel("Library") }
        item {
            LibraryMaintenanceCard(
                blockedCount = blockedCount,
                onNavigateToBlockedSongs = onNavigateToBlockedSongs,
            )
        }

        // -- Recent Syncs section ---------------------------------------------
        if (uiState.recentSyncs.isNotEmpty()) {
            item { SyncSectionLabel("Recent syncs") }
            item {
                val rows = uiState.recentSyncs.map { it.toRecentSyncRow(formatRelativeTime(it.startedAt)) }
                RecentSyncsCard(rows)
            }
        }

        // Bottom spacing so content isn't hidden behind nav bar
        item { Spacer(Modifier.height(80.dp)) }
    }

    // Refresh-confirm dialog. Sibling to the LazyColumn (NOT inside an
    // item {}) so it stays composed regardless of scroll position; an
    // AlertDialog hosts its own window, so it has no effect on layout.
    if (uiState.pendingRefreshSource != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelRefreshMode,
            title = { Text("Switch to Refresh?") },
            text = {
                Text(
                    "Refresh pulls fresh tracks each sync — your auto-generated " +
                        "daily mixes, weekly discovery, and other rotating playlists. " +
                        "Tracks that rotate out are removed from the mix and their " +
                        "downloads deleted to keep your library lean. Cleanup runs once " +
                        "all sources are set to Refresh — while any source still " +
                        "accumulates, nothing is deleted. Tracks you added manually are kept."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRefreshMode) { Text("Switch to Refresh") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRefreshMode) { Text("Cancel") }
            },
        )
    }

    // Undo-confirm dialog. Same top-level placement rule as above. Undo rewrites
    // library state, so it asks first — and says plainly what it will and won't
    // put back, since "undo" invites the assumption that it reverses everything.
    if (confirmUndo) {
        val point = undoPoint
        AlertDialog(
            onDismissRequest = { confirmUndo = false },
            title = { Text("Undo last sync?") },
            text = {
                Text(
                    "Your library goes back to how it was before the last sync: " +
                        "playlists it hid come back, and playlists it emptied are refilled " +
                        "in their original order" +
                        (point?.let { " (${it.playlistCount} playlists)." } ?: ".") +
                        "\n\nTracks you added yourself are kept. Downloaded files that were " +
                        "already deleted can't be brought back."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUndo = false
                        viewModel.onUndoLastSync()
                    },
                ) { Text("Undo sync") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUndo = false }) { Text("Cancel") }
            },
        )
    }

    // Outcome of an undo, acknowledged explicitly: an undo silently changing the
    // library underneath the user is exactly the feeling this feature exists to fix.
    undoResult?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::onUndoResultShown,
            title = { Text("Sync undone") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::onUndoResultShown) { Text("OK") }
            },
        )
    }
}

/** Which source a "Manage ›" tap targets. Consumed by the nav host (Task 5). */
enum class SyncSource { SPOTIFY, YOUTUBE }

// -- Source dashboard: numbers row ───────────────────────────────────────────

/**
 * Compact stats row for a source card, e.g. "42 MIXES   5/30 PLAYLISTS
 * 1.2k LIKED". Numerals in Space Grotesk; cells are omitted when they have
 * nothing to show (no mixes, no liked playlist). Derived client-side — no VM.
 */
@Composable
private fun SourceStatsRow(
    mixesCount: Int,
    playlistsEnabled: Int,
    playlistsTotal: Int,
    likedCount: Int?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (mixesCount > 0) {
            SourceStatCell(number = "$mixesCount", label = "MIXES")
        }
        SourceStatCell(number = "$playlistsEnabled/$playlistsTotal", label = "PLAYLISTS")
        if (likedCount != null) {
            SourceStatCell(number = compactCount(likedCount), label = "LIKED")
        }
    }
}

/** One "42 MIXES" stat cell: bold Space-Grotesk numeral + dim label. */
@Composable
private fun SourceStatCell(
    number: String,
    label: String,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = buildAnnotatedString {
            withStyle(
                SpanStyle(
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Bold,
                    color = onSurface,
                    fontSize = 17.sp,
                ),
            ) { append(number) }
            withStyle(SpanStyle(color = dim, letterSpacing = 0.5.sp)) { append(" $label") }
        },
        style = MaterialTheme.typography.labelMedium,
    )
}

/** First-run explainer shown in the stats slot before a source has synced. */
@Composable
private fun SourceEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Compact count label: 1247 → "1.2k", 1000 → "1k", 980 → "980". Locale.US so a
 *  comma-decimal locale can't leak "1,2k". */
private fun compactCount(n: Int): String =
    if (n >= 1000) "%.1fk".format(java.util.Locale.US, n / 1000.0).replace(".0k", "k") else n.toString()

// -- Section Label ──────────────────────────────────────────────────────────

/**
 * Uppercase section label with small typography and subtle color.
 */
@Composable
private fun SyncSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.7.sp,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

/**
 * Glass-styled card surfacing sync-adjacent maintenance actions. Currently
 * houses the Blocked Songs entry point with a live count badge so the user
 * can see how many tracks are being held back without opening the screen.
 */
@Composable
private fun LibraryMaintenanceCard(
    blockedCount: Int,
    onNavigateToBlockedSongs: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = StashTheme.extendedColors.glassBackground,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, StashTheme.extendedColors.glassBorder,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNavigateToBlockedSongs)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Block,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Blocked Songs",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (blockedCount > 0)
                        "$blockedCount song${if (blockedCount != 1) "s" else ""} will never re-download"
                    else
                        "No blocked songs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// -- Unmatched songs warning card ---------------------------------------------

/**
 * Amber-tinted warning card shown when one or more tracks failed YouTube
 * matching during the last sync.  Tapping navigates to the FailedMatches
 * screen where the user can manually resolve each track.
 *
 * @param count    Number of tracks that are currently unmatched.
 * @param onClick  Navigation callback — routes to the Failed Matches screen.
 */
@Composable
private fun UnmatchedSongsCard(
    count: Int,
    unmatchedCount: Int,
    flaggedCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors

    // Accurate copy for each mix of causes — "couldn't be matched" is
    // specifically the sync-failure path, not the user-flagged path.
    // Without this split, a user who only flagged songs from Now Playing
    // would see a card claiming sync failed, which is false.
    val headline = when {
        unmatchedCount > 0 && flaggedCount > 0 ->
            "$count song${if (count != 1) "s" else ""} need review"
        flaggedCount > 0 ->
            "$flaggedCount song${if (flaggedCount != 1) "s" else ""} flagged as wrong match"
        else ->
            "$unmatchedCount song${if (unmatchedCount != 1) "s" else ""} couldn't be matched"
    }
    val sub = when {
        unmatchedCount > 0 && flaggedCount > 0 ->
            "$unmatchedCount unmatched \u00B7 $flaggedCount flagged \u2014 tap to fix"
        flaggedCount > 0 ->
            "Tap to pick a replacement"
        else ->
            "Tap to review"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = extendedColors.glassBackground,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFFFFA726).copy(alpha = 0.15f), // Amber tint
                            Color.Transparent,
                        )
                    )
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = Color(0xFFFFA726),
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// -- Failed downloads warning card --------------------------------------------

/**
 * Red-tinted warning card surfaced when one or more tracks failed to
 * download (auth, network, storage, codec, ...). Tapping navigates to
 * the FailedDownloads screen where the user can review classified
 * reasons and retry or block per track. Modeled after [UnmatchedSongsCard]
 * to keep the Sync-tab warning surfaces visually consistent.
 *
 * @param count   Number of FAILED rows in download_queue.
 * @param onClick Navigation callback — routes to the Failed Downloads screen.
 */
@Composable
private fun FailedDownloadsCard(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    val accent = Color(0xFFEF4444) // Red — distinct from the amber UnmatchedSongsCard.

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = extendedColors.glassBackground,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            accent.copy(alpha = 0.15f),
                            Color.Transparent,
                        )
                    )
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Failed Downloads",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "$count track${if (count != 1) "s" else ""} need attention",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Live sync terminal: what the run is actually finding, as it happens.
 *
 * The progress counter ("fetched 52… 53…") proves a sync is moving but says
 * nothing about WHAT it found, so a sync that quietly hid half a library looked
 * identical to a good one. Each line names a playlist and its track count, and
 * the decisions that change the library ("Hid 21 YouTube playlists…") are stated
 * outright instead of living only in logcat.
 *
 * Auto-follows the tail while running, and stays readable after the run ends —
 * reading it afterwards is most of the point. "Copy" yields the whole log as
 * text, which is what turns a vague report into a diagnosable one.
 */
@Composable
private fun SyncTerminalCard(
    lines: List<com.stash.core.data.sync.SyncLog.Line>,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    val listState = rememberLazyListState()

    // Follow the tail as new lines land. Keyed on size so a user who scrolls up
    // mid-run isn't yanked back on every single line.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = extendedColors.glassBackground,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "SYNC LOG",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCopy) { Text("Copy") }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(lines.size) { i ->
                    val line = lines[i]
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = when (line.level) {
                            com.stash.core.data.sync.SyncLog.Level.SUCCESS ->
                                StashTheme.extendedColors.success
                            com.stash.core.data.sync.SyncLog.Level.WARN -> Color(0xFFF59E0B)
                            com.stash.core.data.sync.SyncLog.Level.ERROR -> Color(0xFFEF4444)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/**
 * "Undo last sync" — puts the library back to the moment before the last sync
 * ran: playlists it hid return, playlists it emptied are refilled.
 *
 * Only rendered when a restore point exists, so the control never sits there
 * unable to do anything. Deliberately understated (no alarm colour): it's a
 * safety net, not an error — the loud version would imply every sync went wrong.
 */
@Composable
private fun UndoSyncCard(
    playlistCount: Int,
    capturedAtEpochMs: Long,
    inProgress: Boolean,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    val when_ = remember(capturedAtEpochMs) {
        val ageMs = System.currentTimeMillis() - capturedAtEpochMs
        val mins = (ageMs / 60_000L).coerceAtLeast(0)
        when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 60 * 24 -> "${mins / 60} hr ago"
            else -> "${mins / (60 * 24)} day${if (mins / (60 * 24) != 1L) "s" else ""} ago"
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = extendedColors.glassBackground,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Restore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Undo last sync",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Restores $playlistCount playlists to how they were $when_",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onUndo, enabled = !inProgress) {
                Text(if (inProgress) "Undoing…" else "Undo")
            }
        }
    }
}

// ── Refresh / Accumulate chip row (shared Spotify + YouTube) ────────────────

/**
 * Section header + two chips letting the user pick between REFRESH and
 * ACCUMULATE for a single source. Rendered inside each service's expanded
 * preferences view with its own accent color. Bound per-source — the two
 * cards control independent DataStore keys.
 */
@Composable
private fun SyncModeChipRow(
    mode: SyncMode,
    onChange: (SyncMode) -> Unit,
    onRequestRefresh: () -> Unit,
    accent: Color,
) {
    Text(
        text = "Mix sync mode",
        style = MaterialTheme.typography.labelMedium,
        color = accent,
        fontWeight = FontWeight.SemiBold,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = mode == SyncMode.REFRESH,
            onClick = { onRequestRefresh() },
            label = { Text("Refresh") },
        )
        FilterChip(
            selected = mode == SyncMode.ACCUMULATE,
            onClick = { onChange(SyncMode.ACCUMULATE) },
            label = { Text("Accumulate") },
        )
    }
    Text(
        text = if (mode == SyncMode.REFRESH)
            "Mixes update with fresh tracks each sync"
        else
            "New tracks stack on top of old ones",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ── Spotify sync toggle row ─────────────────────────────────────────────────

@Composable
internal fun SpotifySyncToggleRow(
    name: String,
    trackCount: Int,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!enabled) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$trackCount tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        com.stash.core.ui.components.StashSwitch(
            checked = enabled,
            onCheckedChange = onToggle,
        )
    }
}

@Composable
internal fun StudioOnlyToggleRow(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!enabled) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Studio recordings only",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Excludes covers, live recordings, and UGC uploads from your Liked Songs. Other YouTube playlists are unaffected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        com.stash.core.ui.components.StashSwitch(
            checked = enabled,
            onCheckedChange = onChange,
        )
    }
}

