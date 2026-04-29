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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.SyncDisplayStatus
import com.stash.core.model.SyncMode
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.sync.components.RecentSyncRow
import com.stash.feature.sync.components.RecentSyncsCard
import com.stash.feature.sync.components.SyncRowStatus
import com.stash.feature.sync.components.SyncHeroCard
import com.stash.feature.sync.components.SyncActionProgress
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
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val blockedCount by viewModel.blockedCount.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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

        // -- Sources section -------------------------------------------------
        item { SyncSectionLabel("Sources") }

        // -- Spotify Sync Preferences (above schedule) ------------------------
        // Rendered whenever Spotify is connected, even before the first sync
        // has populated the playlist list — so users can see what's coming
        // and opt in to the playlists they want BEFORE tapping Sync. Starts
        // expanded when empty (first-run explainer is visible) or when every
        // playlist is off (user might be reviewing a fresh install's state).
        if (uiState.spotifyConnected) {
            item {
                val everythingOff = uiState.spotifyPlaylists.isNotEmpty() &&
                    uiState.spotifyPlaylists.none { it.syncEnabled }
                val startExpanded = uiState.spotifyPlaylists.isEmpty() || everythingOff
                val spotifySummaryLine = remember(uiState.spotifyPlaylists) {
                    val enabled = uiState.spotifyPlaylists.count { it.syncEnabled }
                    val total = uiState.spotifyPlaylists.size
                    val tracks = uiState.spotifyPlaylists.filter { it.syncEnabled }.sumOf { it.trackCount }
                    "$enabled of $total playlists · ${"%,d".format(tracks)} tracks"
                }
                com.stash.feature.sync.components.SourcePreferencesCard(
                    name = "Spotify",
                    brandColor = StashTheme.extendedColors.spotifyGreen,
                    connected = uiState.spotifyConnected,
                    statusPills = { SpotifySummaryPills(uiState) },
                    summaryLine = spotifySummaryLine,
                    initiallyExpanded = startExpanded,
                    expandedContent = {
                        SpotifyExpandedContent(
                            uiState = uiState,
                            onSyncModeChanged = viewModel::onSpotifySyncModeChanged,
                            onPlaylistToggled = viewModel::onTogglePlaylistSync,
                        )
                    },
                )
            }
        }

        // -- YouTube Sync Preferences ----------------------------------------
        // Opt-in by default. The FIRST Sync Now fetches everything but
        // downloads nothing because every playlist starts disabled —
        // that's the staging/discover phase. Users pick what they want,
        // tap Sync Now again, and downloads begin.
        if (uiState.youTubeConnected) {
            item {
                val hasPlaylists = uiState.youTubePlaylists.isNotEmpty()
                val everythingOff = hasPlaylists &&
                    uiState.youTubePlaylists.none { it.syncEnabled }
                val startExpanded = !hasPlaylists || everythingOff
                val youtubeSummaryLine = remember(uiState.youTubePlaylists) {
                    val enabled = uiState.youTubePlaylists.count { it.syncEnabled }
                    val total = uiState.youTubePlaylists.size
                    val tracks = uiState.youTubePlaylists.filter { it.syncEnabled }.sumOf { it.trackCount }
                    "$enabled of $total playlists · ${"%,d".format(tracks)} tracks"
                }
                com.stash.feature.sync.components.SourcePreferencesCard(
                    name = "YouTube Music",
                    brandColor = StashTheme.extendedColors.youtubeRed,
                    connected = uiState.youTubeConnected,
                    statusPills = { YouTubeSummaryPills(uiState) },
                    summaryLine = youtubeSummaryLine,
                    initiallyExpanded = startExpanded,
                    expandedContent = {
                        YouTubeExpandedContent(
                            uiState = uiState,
                            onSyncModeChanged = viewModel::onYoutubeSyncModeChanged,
                            onStudioOnlyChanged = viewModel::onYoutubeLikedStudioOnlyChanged,
                            onPlaylistToggled = viewModel::onTogglePlaylistSync,
                        )
                    },
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
        // Blocked Songs + Fix wrong-version downloads were previously in
        // Settings → Library. They live here now because both are
        // sync-adjacent operations: blocked songs gate what sync will re-
        // download, and "fix wrong-version" actively re-canonicalizes
        // YT library imports (which is literally a sync operation). Sits
        // above Recent Syncs so maintenance actions stay glanceable
        // without scrolling past the (potentially long) history log.
        item { SyncSectionLabel("Library") }
        item {
            LibraryMaintenanceCard(
                blockedCount = blockedCount,
                onNavigateToBlockedSongs = onNavigateToBlockedSongs,
                onRunYtLibraryBackfill = viewModel::onRunYtLibraryBackfill,
            )
        }

        // -- Recent Syncs section ---------------------------------------------
        if (uiState.recentSyncs.isNotEmpty()) {
            item { SyncSectionLabel("Recent syncs") }
            item {
                val rows = uiState.recentSyncs.map { sync ->
                    RecentSyncRow(
                        id = sync.id,
                        timestamp = formatRelativeTime(sync.startedAt),
                        summary = buildString {
                            append("Found ${sync.newTracksFound}")
                            append(" / ${sync.tracksDownloaded} downloaded")
                            if (sync.tracksFailed > 0) append(" / ${sync.tracksFailed} failed")
                        },
                        status = when (sync.displayStatus) {
                            SyncDisplayStatus.Success -> SyncRowStatus.HEALTHY
                            is SyncDisplayStatus.PartialSuccess -> SyncRowStatus.PARTIAL
                            is SyncDisplayStatus.Interrupted -> SyncRowStatus.PARTIAL
                            is SyncDisplayStatus.Failed -> SyncRowStatus.FAILED
                            SyncDisplayStatus.Running -> SyncRowStatus.PARTIAL
                            SyncDisplayStatus.Idle -> SyncRowStatus.PARTIAL
                        },
                        errorMessage = sync.errorMessage,
                        diagnostics = sync.diagnostics,
                    )
                }
                RecentSyncsCard(rows)
            }
        }

        // Bottom spacing so content isn't hidden behind nav bar
        item { Spacer(Modifier.height(80.dp)) }
    }
}

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
 * Glass-styled card containing the two library-maintenance actions
 * migrated out of Settings in Phase 8:
 *  - Navigate to the Blocked Songs manager.
 *  - Launch the YT library backfill worker (re-canonicalize OMV imports).
 *
 * Shows a blocked-count badge on the first row so the count is visible
 * without entering the screen.
 */
@Composable
private fun LibraryMaintenanceCard(
    blockedCount: Int,
    onNavigateToBlockedSongs: () -> Unit,
    onRunYtLibraryBackfill: () -> Unit,
) {
    val cardContext = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = StashTheme.extendedColors.glassBackground,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, StashTheme.extendedColors.glassBorder,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onRunYtLibraryBackfill()
                        android.widget.Toast.makeText(
                            cardContext,
                            "Scanning YouTube library\u2026 watch the Sync Progress notification.",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Fix wrong-version downloads",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Swap music-video imports for their studio audio. Re-downloads the affected tracks.",
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
            onClick = { onChange(SyncMode.REFRESH) },
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
private fun SpotifySyncToggleRow(
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
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            ),
        )
    }
}

@Composable
private fun StudioOnlyToggleRow(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    accent: Color,
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
        Switch(
            checked = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = accent,
                checkedTrackColor = accent.copy(alpha = 0.5f),
            ),
        )
    }
}

// ── SourcePreferencesCard helpers ────────────────────────────────────────────

@Composable
private fun SpotifySummaryPills(uiState: SyncUiState) {
    val ec = StashTheme.extendedColors
    val likedSyncing = uiState.spotifyPlaylists.any {
        it.type == com.stash.core.model.PlaylistType.LIKED_SONGS && it.syncEnabled
    }
    val mixesEnabled = uiState.spotifyPlaylists.count {
        it.type == com.stash.core.model.PlaylistType.DAILY_MIX && it.syncEnabled
    }
    val mixesTotal = uiState.spotifyPlaylists.count {
        it.type == com.stash.core.model.PlaylistType.DAILY_MIX
    }
    val customEnabled = uiState.spotifyPlaylists.count {
        it.type == com.stash.core.model.PlaylistType.CUSTOM && it.syncEnabled
    }
    val customTotal = uiState.spotifyPlaylists.count {
        it.type == com.stash.core.model.PlaylistType.CUSTOM
    }
    if (likedSyncing) {
        StatusPill("Liked \u2713", brandColor = ec.spotifyGreen)
    }
    if (mixesTotal > 0) {
        StatusPill("Mixes $mixesEnabled/$mixesTotal")
    }
    if (customTotal > 0) {
        StatusPill("Custom $customEnabled/$customTotal")
    }
}

@Composable
private fun SpotifyExpandedContent(
    uiState: SyncUiState,
    onSyncModeChanged: (SyncMode) -> Unit,
    onPlaylistToggled: (Long, Boolean) -> Unit,
) {
    val purple = MaterialTheme.colorScheme.primary
    if (uiState.spotifyPlaylists.isEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Your Spotify playlists and daily mixes will appear here after the first sync. Tap Sync Now to load them — nothing downloads until you toggle it on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
    } else {
        Spacer(modifier = Modifier.height(12.dp))
        SyncModeChipRow(
            mode = uiState.spotifySyncMode,
            onChange = onSyncModeChanged,
            accent = purple,
        )
        Spacer(modifier = Modifier.height(8.dp))

        val liked = uiState.spotifyPlaylists.filter {
            it.type == com.stash.core.model.PlaylistType.LIKED_SONGS
        }
        val custom = uiState.spotifyPlaylists.filter {
            it.type == com.stash.core.model.PlaylistType.CUSTOM
        }
        // Split DAILY_MIX type into actual Daily Mixes vs other
        // Spotify-generated mixes (Release Radar, Discover Weekly, etc.)
        val allMixes = uiState.spotifyPlaylists.filter {
            it.type == com.stash.core.model.PlaylistType.DAILY_MIX
        }
        val dailyMixes = allMixes.filter {
            it.name.matches(Regex("""Daily Mix \d+"""))
        }
        val otherMixes = allMixes.filter {
            !it.name.matches(Regex("""Daily Mix \d+"""))
        }

        // Liked Songs
        liked.forEach { playlist ->
            SpotifySyncToggleRow(
                name = playlist.name,
                trackCount = playlist.trackCount,
                enabled = playlist.syncEnabled,
                onToggle = { onPlaylistToggled(playlist.id, it) },
            )
        }

        // Spotify-generated mixes (each gets its own toggle)
        if (otherMixes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Spotify Mixes",
                style = MaterialTheme.typography.labelMedium,
                color = purple,
                fontWeight = FontWeight.SemiBold,
            )
            otherMixes.forEach { playlist ->
                SpotifySyncToggleRow(
                    name = playlist.name,
                    trackCount = playlist.trackCount,
                    enabled = playlist.syncEnabled,
                    onToggle = { onPlaylistToggled(playlist.id, it) },
                )
            }
        }

        // Daily Mixes 1-6 — single toggle for all
        if (dailyMixes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            val totalMixTracks = dailyMixes.sumOf { it.trackCount }
            val allMixesEnabled = dailyMixes.all { it.syncEnabled }
            SpotifySyncToggleRow(
                name = "Daily Mixes (${dailyMixes.size})",
                trackCount = totalMixTracks,
                enabled = allMixesEnabled,
                onToggle = { enabled ->
                    dailyMixes.forEach { mix -> onPlaylistToggled(mix.id, enabled) }
                },
            )
        }

        // User Playlists
        if (custom.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Your Playlists",
                style = MaterialTheme.typography.labelMedium,
                color = purple,
                fontWeight = FontWeight.SemiBold,
            )
            custom.forEach { playlist ->
                SpotifySyncToggleRow(
                    name = playlist.name,
                    trackCount = playlist.trackCount,
                    enabled = playlist.syncEnabled,
                    onToggle = { onPlaylistToggled(playlist.id, it) },
                )
            }
        }
    }
}

@Composable
private fun YouTubeSummaryPills(uiState: SyncUiState) {
    val ec = StashTheme.extendedColors
    val likedSyncing = uiState.youTubePlaylists.any {
        it.type == com.stash.core.model.PlaylistType.LIKED_SONGS && it.syncEnabled
    }
    val mixesEnabled = uiState.youTubePlaylists.count {
        it.type == com.stash.core.model.PlaylistType.DAILY_MIX && it.syncEnabled
    }
    val mixesTotal = uiState.youTubePlaylists.count {
        it.type == com.stash.core.model.PlaylistType.DAILY_MIX
    }
    val otherEnabled = uiState.youTubePlaylists.count {
        it.type !in setOf(
            com.stash.core.model.PlaylistType.LIKED_SONGS,
            com.stash.core.model.PlaylistType.DAILY_MIX,
        ) && it.syncEnabled
    }
    val otherTotal = uiState.youTubePlaylists.count {
        it.type !in setOf(
            com.stash.core.model.PlaylistType.LIKED_SONGS,
            com.stash.core.model.PlaylistType.DAILY_MIX,
        )
    }
    if (likedSyncing) {
        StatusPill(
            text = if (uiState.youtubeLikedStudioOnly) "Liked \u00b7 Studio only" else "Liked \u2713",
            brandColor = ec.youtubeRed,
        )
    }
    if (mixesTotal > 0) {
        StatusPill("$mixesEnabled/$mixesTotal mixes")
    }
    if (otherTotal > 0) {
        StatusPill("$otherEnabled/$otherTotal playlists")
    }
}

@Composable
private fun YouTubeExpandedContent(
    uiState: SyncUiState,
    onSyncModeChanged: (SyncMode) -> Unit,
    onStudioOnlyChanged: (Boolean) -> Unit,
    onPlaylistToggled: (Long, Boolean) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val hasPlaylists = uiState.youTubePlaylists.isNotEmpty()

    if (!hasPlaylists) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Your Home Mixes, Liked Songs, and playlists from your YouTube Music library will appear here after the first sync. Nothing downloads until you pick what you want — no surprise downloads.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
    } else {
        Spacer(modifier = Modifier.height(12.dp))
        SyncModeChipRow(
            mode = uiState.youtubeSyncMode,
            onChange = onSyncModeChanged,
            accent = accent,
        )
        Spacer(modifier = Modifier.height(8.dp))
        StudioOnlyToggleRow(
            enabled = uiState.youtubeLikedStudioOnly,
            onChange = onStudioOnlyChanged,
            accent = accent,
        )
        Spacer(modifier = Modifier.height(8.dp))

        val ytLiked = uiState.youTubePlaylists.filter {
            it.type == com.stash.core.model.PlaylistType.LIKED_SONGS
        }
        val ytMixes = uiState.youTubePlaylists.filter {
            it.type == com.stash.core.model.PlaylistType.DAILY_MIX
        }
        val ytOther = uiState.youTubePlaylists.filter {
            it.type != com.stash.core.model.PlaylistType.LIKED_SONGS &&
                it.type != com.stash.core.model.PlaylistType.DAILY_MIX
        }

        ytLiked.forEach { playlist ->
            SpotifySyncToggleRow(
                name = playlist.name,
                trackCount = playlist.trackCount,
                enabled = playlist.syncEnabled,
                onToggle = { onPlaylistToggled(playlist.id, it) },
            )
        }

        if (ytMixes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Home Mixes",
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
            ytMixes.forEach { playlist ->
                SpotifySyncToggleRow(
                    name = playlist.name,
                    trackCount = playlist.trackCount,
                    enabled = playlist.syncEnabled,
                    onToggle = { onPlaylistToggled(playlist.id, it) },
                )
            }
        }

        if (ytOther.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Other Playlists",
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
            ytOther.forEach { playlist ->
                SpotifySyncToggleRow(
                    name = playlist.name,
                    trackCount = playlist.trackCount,
                    enabled = playlist.syncEnabled,
                    onToggle = { onPlaylistToggled(playlist.id, it) },
                )
            }
        }
    }
}
