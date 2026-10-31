package com.stash.feature.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.sync.SyncPhase
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncMode
import com.stash.core.ui.components.bottomChromePadding
import com.stash.core.ui.components.streaming.StreamingModeChip
import com.stash.core.ui.components.streaming.StreamingModeSheet
import com.stash.core.ui.theme.SpaceGrotesk
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.sync.components.AccountCard
import com.stash.feature.sync.components.ArrivalSheet
import com.stash.feature.sync.components.AttentionRow
import com.stash.feature.sync.components.AuthExpiredBanner
import com.stash.feature.sync.components.BrandDotLabel
import com.stash.feature.sync.components.ConnectButton
import com.stash.feature.sync.components.CoverFan
import com.stash.feature.sync.components.CoverTile
import com.stash.feature.sync.components.LandedCoverTile
import com.stash.feature.sync.components.PendingCoverTile
import com.stash.feature.sync.components.SavedCheck
import com.stash.feature.sync.components.SpotifyMark
import com.stash.feature.sync.components.SyncAmbientBackdrop
import com.stash.feature.sync.components.SyncLinkRow
import com.stash.feature.sync.components.YouTubeMusicMark
import com.stash.feature.sync.components.phaseLabel

/**
 * The Sync tab. Four faces, one at a time:
 *  - nothing connected: connect an account;
 *  - connected, never synced: one Sync button (a new install's first Sync asks
 *    how music should arrive, [ArrivalSheet]);
 *  - syncing: covers fill in as playlists land, each playable at once;
 *  - synced: the library at a glance in Stream only, or what's on the phone in
 *    Download mode.
 * Under it the accounts, anything that needs a look, Auto-sync, and one row to
 * History (the log, undo, blocked songs, mix modes, recent syncs). The Sync tab
 * overhaul, 2026-10-29: the screen used to show all of that at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    modifier: Modifier = Modifier,
    onNavigateToFailedMatches: () -> Unit = {},
    onNavigateToFailedDownloads: () -> Unit = {},
    // Settings › Accounts: connecting, and an expired sign-in's Reconnect.
    onNavigateToAccounts: () -> Unit = {},
    onManageSource: (SyncSource) -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenPlaylist: (Long) -> Unit = {},
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val failedDownloadsCount by viewModel.failedDownloadsCount.collectAsStateWithLifecycle()
    val authState by viewModel.authExpiry.collectAsStateWithLifecycle()
    val streamingMode by viewModel.streamingEnabled.collectAsStateWithLifecycle()
    var showModeSheet by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }

    // "Save to this phone" on the first-sync sheet: straight to the playlists to pick from.
    LaunchedEffect(uiState.openManageFor) {
        uiState.openManageFor?.let {
            onManageSource(it)
            viewModel.onManageOpened()
        }
    }

    // What's in the library: the account pages also list what was taken out.
    val rows = remember(uiState.spotifyPlaylists, uiState.youTubePlaylists) {
        uiState.playlistRows().filterNot { it.userHidden }
    }
    val anyConnected = uiState.spotifyConnected || uiState.youTubeConnected
    val firstSource = when {
        uiState.spotifyConnected -> SyncSource.SPOTIFY
        uiState.youTubeConnected -> SyncSource.YOUTUBE
        else -> null
    }
    val savedRows = rows.filter { it.saved(streamingMode) }
    // The user's own playlists lead the art; mixes fill in when there are few.
    val arts = remember(rows, streamingMode) {
        val pool = if (!streamingMode && savedRows.isNotEmpty()) savedRows else rows
        (pool.filter { it.type == PlaylistType.CUSTOM } + pool.filter { it.type != PlaylistType.CUSTOM })
            .mapNotNull { it.artUrl }
            .distinct()
    }

    Box(modifier.fillMaxSize()) {
        if (anyConnected) SyncAmbientBackdrop(arts = arts)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomChromePadding() + 24.dp),
        ) {
            item(key = "auth") {
                AuthExpiredBanner(state = authState, onReauth = onNavigateToAccounts)
            }

            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.sync_title),
                        fontFamily = SpaceGrotesk,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 30.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    if (anyConnected && uiState.isSyncing) {
                        TextButton(onClick = viewModel::onStopSync) {
                            Text(
                                if (uiState.syncPhase is SyncPhase.WaitingForNetwork) {
                                    stringResource(R.string.sync_cancel)
                                } else {
                                    stringResource(R.string.sync_stop)
                                },
                            )
                        }
                    } else if (anyConnected) {
                        StreamingModeChip(streamingEnabled = streamingMode, onClick = { showModeSheet = true })
                    }
                }
            }

            when {
                !anyConnected -> item(key = "first_run") {
                    FirstRunContent(onConnect = onNavigateToAccounts)
                }
                uiState.isSyncing -> item(key = "syncing") {
                    SyncingContent(
                        phase = uiState.syncPhase,
                        progress = uiState.overallProgress,
                        landed = rows,
                        spotify = uiState.spotifyConnected,
                        youTube = uiState.youTubeConnected,
                        onOpenPlaylist = onOpenPlaylist,
                    )
                }
                !uiState.hasEverSynced -> item(key = "ready") {
                    ReadyContent(onSync = viewModel::onSyncNow)
                }
                streamingMode -> item(key = "synced") {
                    // The three newest mixes, so the hero changes when a sync brings some.
                    // The fan draws its last cover on top: the newest goes last.
                    val fan = rows.filter { it.isMix() && it.artUrl != null }.newestFirst()
                        .distinctBy { it.artUrl }.take(3).reversed()
                    // "Play something new": the newest mix that has songs.
                    val newest = rows.filter { it.isMix() && it.trackCount > 0 }.newestFirst().firstOrNull()
                    SyncedHero(
                        arts = fan.mapNotNull { it.artUrl }.ifEmpty { arts },
                        // Each cover opens its mix; the fallback art (no mixes yet) is pictures only.
                        onCoverTap = if (fan.isEmpty()) null else { i -> onOpenPlaylist(fan[i].id) },
                        summary = librarySummary(uiState, rows),
                        syncedAgo = uiState.lastSyncRelativeTime,
                        newest = newest,
                        onPlay = { row -> viewModel.onPlayPlaylist(row.id, row.name) },
                        onSync = viewModel::onSyncNow,
                    )
                }
                else -> item(key = "on_phone") {
                    OnThisPhoneHero(
                        saved = savedRows,
                        songsOnPhone = uiState.syncStatus.spotifyTracks + uiState.syncStatus.youTubeTracks,
                        syncedAgo = uiState.lastSyncRelativeTime,
                        onChoose = { firstSource?.let(onManageSource) },
                        onSync = viewModel::onSyncNow,
                        onOpenPlaylist = onOpenPlaylist,
                    )
                }
            }

            if (anyConnected && !uiState.isSyncing) {
                // "Undo last sync" paused Auto-sync; the Sync button above is how it resumes.
                if (uiState.syncPreferences.autoSyncEnabled && uiState.syncPreferences.autoSyncPausedAfterUndo) {
                    item(key = "auto_sync_paused") {
                        Spacer(Modifier.height(12.dp))
                        AutoSyncPausedNote()
                    }
                }

                item(key = "accounts") {
                    Spacer(Modifier.height(26.dp))
                    AccountCards(
                        uiState = uiState,
                        rows = rows,
                        onManageSource = onManageSource,
                        onAddAccount = onNavigateToAccounts,
                    )
                }

                // Covers "couldn't match during sync" and "flagged as wrong from Now Playing".
                val reviewCount = uiState.unmatchedCount + uiState.flaggedCount
                if (reviewCount > 0) {
                    item(key = "review") {
                        Spacer(Modifier.height(14.dp))
                        AttentionRow(
                            title = reviewHeadline(reviewCount, uiState.unmatchedCount, uiState.flaggedCount),
                            subtitle = reviewSubtitle(uiState.unmatchedCount, uiState.flaggedCount),
                            dotColor = StashTheme.extendedColors.warning,
                            onClick = onNavigateToFailedMatches,
                        )
                    }
                }
                if (failedDownloadsCount > 0) {
                    item(key = "failed_downloads") {
                        AttentionRow(
                            title = stringResource(R.string.sync_failed_downloads_title),
                            subtitle = pluralStringResource(
                                R.plurals.sync_failed_downloads_count,
                                failedDownloadsCount,
                                failedDownloadsCount,
                            ),
                            dotColor = MaterialTheme.colorScheme.error,
                            onClick = onNavigateToFailedDownloads,
                        )
                    }
                }

                item(key = "links") {
                    Spacer(Modifier.height(10.dp))
                    SyncLinkRow(
                        title = stringResource(R.string.sync_auto_sync),
                        trailing = autoSyncSummary(uiState.syncPreferences),
                        onClick = { showSchedule = true },
                    )
                    SyncLinkRow(
                        title = stringResource(R.string.sync_history_row),
                        trailing = null,
                        onClick = onOpenHistory,
                    )
                }
            }
        }
    }

    if (uiState.showArrivalSheet) {
        ArrivalSheet(
            spotifyConnected = uiState.spotifyConnected,
            youTubeConnected = uiState.youTubeConnected,
            onChoose = viewModel::onArrivalChosen,
            onDismiss = viewModel::onArrivalDismissed,
        )
    }

    if (showModeSheet) {
        StreamingModeSheet(
            streamingEnabled = streamingMode,
            onSelect = {
                viewModel.setStreamingEnabled(it)
                showModeSheet = false
            },
            onDismiss = { showModeSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        )
    }

    if (showSchedule) {
        ModalBottomSheet(
            onDismissRequest = { showSchedule = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = StashTheme.extendedColors.elevatedSurface,
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
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
        }
    }
}

/** One playlist of either account, as the Sync tab and its playlist pages show it. */
internal data class SyncPlaylistRow(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val type: PlaylistType,
    val syncEnabled: Boolean,
    val keepOffline: Boolean,
    val artUrl: String?,
    val source: SyncSource,
    /** Taken out of the library: its account's page still lists it, nothing else does. */
    val userHidden: Boolean = false,
    /** When it first arrived (epoch ms). */
    val addedAt: Long = 0L,
    /** A mix that can stack, and whether it does. */
    val stackable: Boolean = false,
    val stackSongs: Boolean = false,
) {
    /**
     * Kept on this phone: its Save switch, or, in Download mode, switched on for
     * sync, which downloads it. The same rule as the playlist page's Download
     * button, so the two never disagree.
     */
    fun saved(streamOnly: Boolean): Boolean = keepOffline || (!streamOnly && syncEnabled)
}

internal fun SyncUiState.playlistRows(): List<SyncPlaylistRow> =
    spotifyPlaylists.map {
        SyncPlaylistRow(it.id, it.name, it.trackCount, it.type, it.syncEnabled, it.keepOffline, it.artUrl, SyncSource.SPOTIFY, it.userHidden, it.dateAdded, it.stackable, it.stackSongs)
    } + youTubePlaylists.map {
        SyncPlaylistRow(it.id, it.name, it.trackCount, it.type, it.syncEnabled, it.keepOffline, it.artUrl, SyncSource.YOUTUBE, it.userHidden, it.dateAdded, it.stackable, it.stackSongs)
    }

/**
 * Newest arrivals first. Every cover on the Sync tab reads in this order, so a sync that brings
 * new mixes shows them; name order kept the first sync's covers up forever (2026-10-29: 25 new
 * YouTube mixes arrived and nothing on the tab changed). Stable, so ties keep their order.
 */
internal fun List<SyncPlaylistRow>.newestFirst(): List<SyncPlaylistRow> = sortedByDescending { it.addedAt }

/** "Mixes" is a service's own algorithmic playlists; everything else but Liked is the user's. */
private fun SyncPlaylistRow.isMix() = type == PlaylistType.DAILY_MIX
private fun SyncPlaylistRow.isOwnPlaylist() = type != PlaylistType.DAILY_MIX && type != PlaylistType.LIKED_SONGS

@Composable
private fun librarySummary(uiState: SyncUiState, rows: List<SyncPlaylistRow>): String {
    val songs = uiState.syncStatus.totalTracks
    val playlists = rows.count { it.isOwnPlaylist() }
    val mixes = rows.count { it.isMix() }
    val counts = stringResource(
        R.string.sync_home_summary,
        pluralStringResource(R.plurals.sync_songs_count, songs, songs),
        pluralStringResource(R.plurals.sync_playlists_count, playlists, playlists),
        pluralStringResource(R.plurals.sync_mixes_count, mixes, mixes),
    )
    return if (uiState.lastSyncRelativeTime.isBlank()) {
        counts
    } else {
        counts + " · " + stringResource(R.string.sync_home_synced_ago, uiState.lastSyncRelativeTime.lowercase())
    }
}

@Composable
private fun autoSyncSummary(prefs: com.stash.core.data.sync.SyncPreferences): String {
    if (!prefs.autoSyncEnabled) return stringResource(R.string.sync_auto_sync_off)
    val time = java.time.LocalTime.of(prefs.syncHour, prefs.syncMinute)
        .format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))
    return if (prefs.wifiOnly) {
        stringResource(R.string.sync_auto_sync_at_wifi, time)
    } else {
        stringResource(R.string.sync_auto_sync_at, time)
    }
}

/** Accurate copy for each mix of causes: "couldn't be matched" is the sync-failure path only. */
@Composable
private fun reviewHeadline(count: Int, unmatchedCount: Int, flaggedCount: Int): String = when {
    unmatchedCount > 0 && flaggedCount > 0 ->
        pluralStringResource(R.plurals.sync_review_need_review, count, count)
    flaggedCount > 0 ->
        pluralStringResource(R.plurals.sync_review_flagged_wrong, flaggedCount, flaggedCount)
    else ->
        pluralStringResource(R.plurals.sync_review_unmatched, unmatchedCount, unmatchedCount)
}

@Composable
private fun reviewSubtitle(unmatchedCount: Int, flaggedCount: Int): String = when {
    unmatchedCount > 0 && flaggedCount > 0 ->
        stringResource(R.string.sync_review_sub_both, unmatchedCount, flaggedCount)
    flaggedCount > 0 -> stringResource(R.string.sync_review_sub_flagged)
    else -> stringResource(R.string.sync_review_sub_unmatched)
}

// -- The four faces ──────────────────────────────────────────────────────────

@Composable
private fun HeroTitle(text: String, size: Int = 28) {
    Text(
        text = text,
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = size.sp,
        lineHeight = (size + 6).sp,
        letterSpacing = (-0.5).sp,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun HeroBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The primary pill: light on the dark ground, the one thing to tap. */
@Composable
private fun PrimaryPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(25.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.onBackground,
            contentColor = MaterialTheme.colorScheme.background,
        ),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SyncCircleButton(onClick: () -> Unit) {
    // Icon and word: the bare circular arrow read as "refresh this screen", or as nothing.
    Row(
        modifier = Modifier
            .height(50.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(StashTheme.extendedColors.glassBackgroundHover)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Sync,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(R.string.sync_button),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun FirstRunContent(onConnect: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 56.dp)) {
        HeroTitle(stringResource(R.string.sync_firstrun_title), size = 38)
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.sync_firstrun_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(36.dp))
        ConnectButton(
            label = stringResource(R.string.sync_connect_spotify),
            mark = { SpotifyMark(34.dp) },
            onClick = onConnect,
        )
        Spacer(Modifier.height(12.dp))
        ConnectButton(
            label = stringResource(R.string.sync_connect_youtube),
            mark = { YouTubeMusicMark(34.dp) },
            onClick = onConnect,
        )
        Spacer(Modifier.height(28.dp))
        TextButton(onClick = onConnect) {
            Text(stringResource(R.string.sync_connect_lastfm))
        }
    }
}

@Composable
private fun ReadyContent(onSync: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp)) {
        HeroTitle(stringResource(R.string.sync_firstrun_title), size = 34)
        Spacer(Modifier.height(10.dp))
        HeroBody(stringResource(R.string.sync_ready_body))
        Spacer(Modifier.height(22.dp))
        PrimaryPill(
            text = stringResource(R.string.sync_arrival_sync_now),
            onClick = onSync,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SyncedHero(
    arts: List<String>,
    onCoverTap: ((Int) -> Unit)?,
    summary: String,
    syncedAgo: String,
    newest: SyncPlaylistRow?,
    onPlay: (SyncPlaylistRow) -> Unit,
    onSync: () -> Unit,
) {
    // Sync is what this tab is for, so it's the big button (it used to be "Open Library",
    // which the Library tab already is). The second turns a finished sync into listening.
    Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
        CoverFan(arts = arts, onTap = onCoverTap)
        Spacer(Modifier.height(18.dp))
        HeroTitle(stringResource(R.string.sync_home_title))
        Spacer(Modifier.height(6.dp))
        HeroBody(
            if (syncedAgo.isBlank()) summary
            else summary + " · " + stringResource(R.string.sync_home_synced_ago, syncedAgo.lowercase()),
        )
        Spacer(Modifier.height(18.dp))
        PrimaryPill(
            text = stringResource(R.string.sync_arrival_sync_now),
            onClick = onSync,
            icon = Icons.Filled.Sync,
            modifier = Modifier.fillMaxWidth(),
        )
        if (newest != null) {
            Spacer(Modifier.height(10.dp))
            PlaySomethingNew(name = newest.name, onClick = { onPlay(newest) })
        }
    }
}

/** A glass pill: play, "Play something new", and which mix it is. */
@Composable
private fun PlaySomethingNew(name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(StashTheme.extendedColors.glassBackgroundHover)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = stringResource(R.string.sync_play_new),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun OnThisPhoneHero(
    saved: List<SyncPlaylistRow>,
    songsOnPhone: Int,
    syncedAgo: String,
    onChoose: () -> Unit,
    onSync: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 30.dp)) {
        HeroTitle(stringResource(R.string.sync_download_title))
        Spacer(Modifier.height(6.dp))
        val songs = pluralStringResource(R.plurals.sync_songs_count, songsOnPhone, songsOnPhone)
        HeroBody(
            if (syncedAgo.isBlank()) songs
            else songs + " · " + stringResource(R.string.sync_home_synced_ago, syncedAgo.lowercase()),
        )
        Spacer(Modifier.height(16.dp))
        if (saved.isEmpty()) {
            HeroBody(stringResource(R.string.sync_download_none))
        } else {
            saved.take(5).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenPlaylist(row.id) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CoverTile(url = row.artUrl, modifier = Modifier.size(52.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = row.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = pluralStringResource(R.plurals.sync_songs_count, row.trackCount, row.trackCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SavedCheck(contentDescription = stringResource(R.string.sync_saved_cd))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryPill(
                text = stringResource(R.string.sync_download_choose),
                onClick = onChoose,
                modifier = Modifier.weight(1f),
            )
            SyncCircleButton(onClick = onSync)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.sync_download_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A sync under way: the count, the phase, and the playlists so far as covers.
 * Each landed cover opens its playlist, so music plays before the sync ends.
 */
@Composable
private fun SyncingContent(
    phase: SyncPhase,
    progress: Float,
    landed: List<SyncPlaylistRow>,
    spotify: Boolean,
    youTube: Boolean,
    onOpenPlaylist: (Long) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 30.dp)) {
        HeroTitle(stringResource(R.string.sync_syncing_title))
        Spacer(Modifier.height(6.dp))
        HeroBody(stringResource(R.string.sync_syncing_body))
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = phaseLabel(phase),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (spotify) BrandDotLabel(StashTheme.extendedColors.spotifyGreen, "Spotify")
                if (youTube) BrandDotLabel(StashTheme.extendedColors.youtubeRed, "YouTube")
            }
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = MaterialTheme.colorScheme.onBackground,
            trackColor = StashTheme.extendedColors.glassBackgroundHover,
        )
        Spacer(Modifier.height(26.dp))
        // What just arrived first, covers before art-less ones; three pending tiles keep the grid going.
        val tiles = landed.newestFirst().sortedBy { it.artUrl == null }.take(12)
        val cells = tiles.map<SyncPlaylistRow, SyncPlaylistRow?> { it } + List(3) { null }
        cells.chunked(3).take(5).forEach { line ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                line.forEach { row ->
                    if (row == null) {
                        PendingCoverTile(Modifier.weight(1f))
                    } else {
                        LandedCoverTile(
                            name = row.name,
                            artUrl = row.artUrl,
                            onClick = { onOpenPlaylist(row.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One card per connected account: its covers and counts. Opens its playlists. */
@Composable
private fun AccountCards(
    uiState: SyncUiState,
    rows: List<SyncPlaylistRow>,
    onManageSource: (SyncSource) -> Unit,
    onAddAccount: () -> Unit,
) {
    val sources = buildList {
        if (uiState.spotifyConnected) add(Triple(SyncSource.SPOTIFY, "Spotify", StashTheme.extendedColors.spotifyGreen))
        if (uiState.youTubeConnected) add(Triple(SyncSource.YOUTUBE, "YouTube Music", StashTheme.extendedColors.youtubeRed))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        sources.forEach { (source, name, color) ->
            val mine = rows.filter { it.source == source }
            val playlists = mine.count { it.isOwnPlaylist() }
            val mixes = mine.count { it.isMix() }
            val arts = mine.newestFirst()
                .mapNotNull { it.artUrl }
                .distinct()
                .take(4)
            AccountCard(
                name = name,
                brandColor = color,
                arts = arts,
                subtitle = stringResource(
                    R.string.sync_account_summary,
                    pluralStringResource(R.plurals.sync_playlists_count, playlists, playlists),
                    pluralStringResource(R.plurals.sync_mixes_count, mixes, mixes),
                ),
                onClick = { onManageSource(source) },
                modifier = Modifier.weight(1f),
            )
        }
        // One account connected: the other half offers the second.
        if (sources.size == 1) {
            val missingSpotify = !uiState.spotifyConnected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, StashTheme.extendedColors.glassBorder, RoundedCornerShape(18.dp))
                    .clickable(onClick = onAddAccount)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    if (missingSpotify) SpotifyMark(40.dp) else YouTubeMusicMark(40.dp)
                }
                Text(
                    text = stringResource(if (missingSpotify) R.string.sync_connect_spotify else R.string.sync_connect_youtube),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Permanent entry point for the queue and recent download history. */
@Composable
internal fun DownloadManagementCard(onClick: () -> Unit) {
    val extendedColors = StashTheme.extendedColors
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = extendedColors.glassBackground,
        border = BorderStroke(0.5.dp, extendedColors.glassBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sync_downloads_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.sync_downloads_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.sync_downloads_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Which source a "Manage ›" tap targets. Consumed by the nav host (Task 5). */
enum class SyncSource { SPOTIFY, YOUTUBE,

    /**
     * Last.fm as a recommendation SOURCE (issue #255). Unlike SPOTIFY/
     * YOUTUBE there are no remote playlists to import — Last.fm has no
     * public playlist API — so its manage surface toggles the app-managed
     * "Recommended by Last.fm" mix instead.
     */
    LASTFM,
}

/** One "42 MIXES" stat cell: bold Space-Grotesk numeral + dim label. */
@Composable
internal fun SourceStatCell(
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
internal fun SourceEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// -- Section Label ──────────────────────────────────────────────────────────

/**
 * Uppercase section label with small typography and subtle color.
 */
@Composable
internal fun SyncSectionLabel(text: String) {
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
internal fun LibraryMaintenanceCard(
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
                    text = stringResource(R.string.sync_blocked_songs_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (blockedCount > 0)
                        pluralStringResource(R.plurals.sync_blocked_songs_count, blockedCount, blockedCount)
                    else
                        stringResource(R.string.sync_blocked_songs_none),
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
internal fun SyncTerminalCard(
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
                    text = stringResource(R.string.sync_log_title),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCopy) { Text(stringResource(R.string.sync_log_copy)) }
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
 * The plain note under the Sync button while "Undo last sync" has Auto-sync
 * paused: without it the next morning's missing sync would look like a fault.
 */
@Composable
internal fun AutoSyncPausedNote(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.PauseCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.sync_auto_sync_paused_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * "Undo last sync" — puts the library back to the moment before the last sync
 * ran: playlists it hid return, ones it added are hidden, ones it emptied are
 * refilled. [playlistCount] is how many playlists that sync changed.
 *
 * Only rendered when a restore point exists, so the control never sits there
 * unable to do anything. Deliberately understated (no alarm colour): it's a
 * safety net, not an error — the loud version would imply every sync went wrong.
 */
@Composable
internal fun UndoSyncCard(
    playlistCount: Int,
    capturedAtEpochMs: Long,
    inProgress: Boolean,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    val mins = remember(capturedAtEpochMs) {
        val ageMs = (System.currentTimeMillis() - capturedAtEpochMs).coerceAtLeast(0)
        ageMs / 60_000L
    }
    val when_ = when {
        mins < 1 -> stringResource(R.string.sync_time_just_now)
        mins < 60 -> stringResource(R.string.sync_time_mins_ago, mins.toInt())
        mins < 60 * 24 -> stringResource(R.string.sync_time_hours_ago, (mins / 60).toInt())
        else -> {
            val days = (mins / (60 * 24)).toInt()
            pluralStringResource(R.plurals.sync_time_days_ago, days, days)
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
                    text = stringResource(R.string.sync_undo_last_sync_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        R.string.sync_undo_last_sync_subtitle,
                        pluralStringResource(R.plurals.sync_playlists_count, playlistCount, playlistCount),
                        when_,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onUndo, enabled = !inProgress) {
                Text(if (inProgress) stringResource(R.string.sync_undoing) else stringResource(R.string.sync_undo))
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
internal fun SyncModeChipRow(
    mode: SyncMode,
    onChange: (SyncMode) -> Unit,
    onRequestRefresh: () -> Unit,
    accent: Color,
) {
    Text(
        text = stringResource(R.string.sync_mix_mode_title),
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
            label = { Text(stringResource(R.string.sync_mode_refresh)) },
        )
        FilterChip(
            selected = mode == SyncMode.ACCUMULATE,
            onClick = { onChange(SyncMode.ACCUMULATE) },
            label = { Text(stringResource(R.string.sync_mode_accumulate)) },
        )
    }
    Text(
        text = if (mode == SyncMode.REFRESH)
            stringResource(R.string.sync_mode_refresh_desc)
        else
            stringResource(R.string.sync_mode_accumulate_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ── Spotify sync toggle row ─────────────────────────────────────────────────

/**
 * Recommendations toggle for the Last.fm source card (issue #255). Lives in
 * the [com.stash.feature.sync.components.SourcePreferencesCard] modeChips
 * slot — where Spotify/YouTube put their Refresh/Accumulate chips — because
 * rotation is the only mode a recommendation playlist has; the only choice
 * worth offering is whether it exists.
 */
@Composable
internal fun LastFmRecommendationsToggleRow(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!enabled) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.sync_recommended_tracks_title),
                style = MaterialTheme.typography.labelMedium,
                color = StashTheme.extendedColors.lastfmRed,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (enabled)
                    stringResource(R.string.sync_lastfm_recommended_enabled_desc)
                else
                    stringResource(R.string.sync_lastfm_recommended_disabled_desc),
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
                text = stringResource(R.string.sync_studio_only_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.sync_studio_only_desc),
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

