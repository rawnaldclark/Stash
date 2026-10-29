package com.stash.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.components.bottomChromePadding
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.sync.components.RecentSyncsCard
import com.stash.feature.sync.components.SourcePreferencesCard
import com.stash.feature.sync.components.formatRelativeTime

/**
 * Everything about syncing that isn't needed every day, one row away from the
 * Sync tab ("History, blocked songs, undo"): undo the last sync, the live log,
 * downloads, blocked songs, each account's mix mode, Last.fm recommendations,
 * and recent syncs. All of it sat on the Sync tab itself until the overhaul
 * (2026-10-29), which is most of why that screen read as hard to use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncHistoryScreen(
    onBack: () -> Unit,
    onNavigateToBlockedSongs: () -> Unit,
    onNavigateToDownloads: () -> Unit,
    onManageSource: (SyncSource) -> Unit,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val blockedCount by viewModel.blockedCount.collectAsStateWithLifecycle()
    val syncLogLines by viewModel.syncLogLines.collectAsStateWithLifecycle()
    val undoCard by viewModel.undoCard.collectAsStateWithLifecycle()
    val undoInProgress by viewModel.undoInProgress.collectAsStateWithLifecycle()
    val undoResult by viewModel.undoResult.collectAsStateWithLifecycle()
    val lastFmState by viewModel.lastFmState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var confirmUndo by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.sync_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = bottomChromePadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Only present when the last sync changed something and none is running.
            undoCard?.let { card ->
                item(key = "undo_sync") {
                    UndoSyncCard(
                        playlistCount = card.changedPlaylists,
                        capturedAtEpochMs = card.createdAtEpochMs,
                        inProgress = undoInProgress,
                        onUndo = { confirmUndo = true },
                    )
                }
            }

            // Only once a run has produced lines: what happened, under how to take it back.
            if (syncLogLines.isNotEmpty()) {
                item(key = "sync_terminal") {
                    SyncTerminalCard(
                        lines = syncLogLines,
                        onCopy = { clipboard.setText(AnnotatedString(viewModel.syncLogAsText())) },
                    )
                }
            }

            item(key = "downloads") { DownloadManagementCard(onClick = onNavigateToDownloads) }

            item(key = "library_label") { SyncSectionLabel(stringResource(R.string.sync_section_library)) }
            item(key = "blocked") {
                LibraryMaintenanceCard(
                    blockedCount = blockedCount,
                    onNavigateToBlockedSongs = onNavigateToBlockedSongs,
                )
            }

            // Each account's own playlists and Liked: Refresh mirrors a song removed there,
            // Accumulate keeps it. Mixes have their own Stack switch on the account's page.
            if (uiState.spotifyConnected || uiState.youTubeConnected) {
                item(key = "mixes_label") { SyncSectionLabel(stringResource(R.string.sync_history_mixes_section)) }
                if (uiState.spotifyConnected) {
                    item(key = "spotify_mode") {
                        MixModeCard(name = "Spotify") {
                            SyncModeChipRow(
                                mode = uiState.spotifySyncMode,
                                onChange = viewModel::onSpotifySyncModeChanged,
                                onRequestRefresh = viewModel::onRequestSpotifyRefresh,
                                accent = StashTheme.extendedColors.spotifyGreen,
                            )
                        }
                    }
                }
                if (uiState.youTubeConnected) {
                    item(key = "youtube_mode") {
                        MixModeCard(name = "YouTube Music") {
                            SyncModeChipRow(
                                mode = uiState.youtubeSyncMode,
                                onChange = viewModel::onYoutubeSyncModeChanged,
                                onRequestRefresh = viewModel::onRequestYoutubeRefresh,
                                accent = StashTheme.extendedColors.youtubeRed,
                            )
                        }
                    }
                }
            }

            // Last.fm can't import playlists; its source is the "Recommended by Last.fm" mix (#255).
            if (lastFmState.connected) {
                item(key = "lastfm") {
                    val hasTracks = (lastFmState.trackCount ?: 0) > 0
                    SourcePreferencesCard(
                        name = "Last.fm",
                        brandColor = StashTheme.extendedColors.lastfmRed,
                        connected = true,
                        stats = {
                            if (hasTracks) {
                                SourceStatCell(
                                    number = "${lastFmState.trackCount}",
                                    label = stringResource(R.string.sync_source_stat_recommended),
                                )
                            } else {
                                SourceEmptyHint(stringResource(R.string.sync_source_empty_lastfm))
                            }
                        },
                        modeChips = {
                            LastFmRecommendationsToggleRow(
                                enabled = lastFmState.enabled,
                                onChange = viewModel::onLastFmRecommendationsToggled,
                            )
                        },
                        onManage = { onManageSource(SyncSource.LASTFM) },
                    )
                }
            }

            if (uiState.recentSyncs.isNotEmpty()) {
                item(key = "recent_label") { SyncSectionLabel(stringResource(R.string.sync_section_recent_syncs)) }
                item(key = "recent") {
                    RecentSyncsCard(uiState.recentSyncs.map { it.toRecentSyncRow(formatRelativeTime(it.startedAt)) })
                }
            }

            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }

    // Refresh deletes rotated-out downloads, so switching to it asks first.
    if (uiState.pendingRefreshSource != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelRefreshMode,
            title = { Text(stringResource(R.string.sync_switch_to_refresh_title)) },
            text = { Text(stringResource(R.string.sync_switch_to_refresh_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRefreshMode) { Text(stringResource(R.string.sync_switch_to_refresh_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRefreshMode) { Text(stringResource(R.string.sync_cancel)) }
            },
        )
    }

    // Undo rewrites library state, so it asks first, and says what it will and won't put back.
    if (confirmUndo) {
        val card = undoCard
        val changedPlaylistsSuffix = card?.let {
            " (" + pluralStringResource(R.plurals.sync_playlists_count, it.changedPlaylists, it.changedPlaylists) + ")"
        }.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmUndo = false },
            title = { Text(stringResource(R.string.sync_undo_dialog_title)) },
            text = { Text(stringResource(R.string.sync_undo_dialog_body, changedPlaylistsSuffix)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUndo = false
                        viewModel.onUndoLastSync()
                    },
                ) { Text(stringResource(R.string.sync_undo_sync_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUndo = false }) { Text(stringResource(R.string.sync_cancel)) }
            },
        )
    }

    // An undo silently changing the library underneath the user is the feeling this exists to fix.
    undoResult?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::onUndoResultShown,
            title = { Text(stringResource(R.string.sync_undone_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::onUndoResultShown) { Text(stringResource(R.string.sync_ok)) }
            },
        )
    }
}

@Composable
private fun MixModeCard(name: String, content: @Composable () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}
