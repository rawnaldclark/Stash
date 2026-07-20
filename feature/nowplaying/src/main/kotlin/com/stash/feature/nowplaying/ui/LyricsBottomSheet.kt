package com.stash.feature.nowplaying.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    // "Save with song file" footer (writes the .lrc beside the downloaded
    // audio so external players pick the lyrics up). Shown only for
    // downloaded tracks while lyrics are actually on screen.
    canSaveToFile: Boolean = false,
    savingToFile: Boolean = false,
    onSaveToFile: () -> Unit = {},
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
            LyricsHeader(
                liveEnabled = liveLyricsEnabled,
                onLiveToggle = onLiveLyricsToggle,
                onClose = onDismiss,
            )

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

            // Quiet footer action: only when the track is downloaded AND
            // lyrics are actually showing — you save what you can see.
            val lyricsOnScreen =
                state is LyricsViewState.Synced || state is LyricsViewState.Plain
            if (canSaveToFile && lyricsOnScreen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(
                        enabled = !savingToFile,
                        onClick = onSaveToFile,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (savingToFile) "Saving…" else "Save with song file",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact header — title on the left; the "Live" toggle (the live
 * synced-line bar opt-in) sits seamlessly beside the close button.
 * Matches QueueBottomSheet's header proportions so the two sheets share
 * their silhouette when stacked in the Now Playing UI.
 */
@Composable
private fun LyricsHeader(
    liveEnabled: Boolean,
    onLiveToggle: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Live",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            com.stash.core.ui.components.StashSwitch(
                checked = liveEnabled,
                onCheckedChange = onLiveToggle,
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }
    }
}

/**
 * Body height in dp. Picked to match QueueBottomSheet's effective
 * working area on a Pixel 6 Pro after the navigation bar + header are
 * subtracted. Tall enough to host ~12 synced lines comfortably.
 */
private const val LYRICS_BODY_HEIGHT_DP = 560
