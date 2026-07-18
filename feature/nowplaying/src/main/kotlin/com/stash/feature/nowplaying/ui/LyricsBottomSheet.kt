package com.stash.feature.nowplaying.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * v0.9.36 Task 12 — full-bleed lyrics sheet for Now Playing.
 *
 * Mirrors [QueueBottomSheet] structurally so the two sheets feel like
 * siblings: `skipPartiallyExpanded = true` (no half-state — the sheet
 * either fills the screen or it's dismissed), explicit close button in
 * a thin header row, no drag handle, surface-tinted container.
 *
 * Dispatches to one of the renderers in [LyricsView] based on [state].
 * The synced renderer needs `currentPositionMs` for the highlight; all
 * the other states ignore it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsBottomSheet(
    state: LyricsViewState,
    currentPositionMs: Long,
    liveLyricsEnabled: Boolean,
    onLiveLyricsToggle: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            LyricsHeader(onClose = onDismiss)

            // Live-bar opt-in (default OFF): shows the currently-sung line at
            // the bottom of Now Playing for synced tracks. Lives here — the
            // one place every lyrics user visits — rather than in Settings.
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 2.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Live lyrics bar",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Show the current line at the bottom of Now Playing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                com.stash.core.ui.components.StashSwitch(
                    checked = liveLyricsEnabled,
                    onCheckedChange = onLiveLyricsToggle,
                )
            }

            // Fixed-height body region so the renderers (which all use
            // fillMaxSize) have a bounded parent. 70% of screen leaves
            // room for the header without pushing the sheet past the
            // status bar — close to how the QueueBottomSheet lays out.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LYRICS_BODY_HEIGHT_DP.dp)
                    .padding(top = 8.dp),
            ) {
                when (state) {
                    LyricsViewState.Loading -> CenteredSpinner("Fetching lyrics\u2026")
                    is LyricsViewState.Synced -> LyricsSyncedRenderer(
                        lines = state.lines,
                        currentPositionMs = currentPositionMs,
                        onLineTap = onSeek,
                    )
                    is LyricsViewState.Plain -> LyricsPlainRenderer(state.text)
                    LyricsViewState.Instrumental -> CenteredPlacard("\u266A Instrumental")
                    LyricsViewState.None -> CenteredPlacard(
                        label = "No lyrics found",
                        action = "Retry",
                        onAction = onRetry,
                    )
                    is LyricsViewState.Error -> CenteredPlacard(
                        label = "Couldn't load lyrics",
                        action = if (state.retryable) "Retry" else null,
                        onAction = onRetry,
                    )
                }
            }
        }
    }
}

/**
 * Compact header — title on the left, close button on the right. Matches
 * QueueBottomSheet's header proportions so the two sheets share their
 * silhouette when stacked in the Now Playing UI.
 */
@Composable
private fun LyricsHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Lyrics",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Close")
        }
    }
}

/**
 * Body height in dp. Picked to match QueueBottomSheet's effective
 * working area on a Pixel 6 Pro after the navigation bar + header are
 * subtracted. Tall enough to host ~12 synced lines comfortably.
 */
private const val LYRICS_BODY_HEIGHT_DP = 560
