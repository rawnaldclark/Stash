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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToLong

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
    isPlaying: Boolean = true,
    playbackSpeed: Float = 1f,
    /** Null = no lyrics row to store an offset on (streaming / not fetched yet): Offset is hidden. */
    currentOffsetMs: Long? = null,
    onOffsetChange: (Long) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Issue #382 — hold the screen on while lyrics are on screen. This
    // composable only exists while the sheet is open (the call site guards
    // it with `if (showLyrics)`), so the flag is scoped to exactly that.
    // Restore the previous value rather than forcing false, so we never
    // clear a keep-screen-on set by something else.
    val view = LocalView.current
    DisposableEffect(view) {
        val wasKeepingScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = wasKeepingScreenOn }
    }

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
                showOffsetButton = state is LyricsViewState.Synced && currentOffsetMs != null,
                currentOffsetMs = currentOffsetMs ?: 0L,
                onOffsetChange = onOffsetChange,
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
                        syllables = state.syllables,
                        isPlaying = isPlaying,
                        playbackSpeed = playbackSpeed,
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
            val lyricsOnScreen = state is LyricsViewState.Synced || state is LyricsViewState.Plain
            if (canSaveToFile && lyricsOnScreen) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(enabled = !savingToFile, onClick = onSaveToFile) {
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
 * Compact header — title on the left (with the Offset button before it when
 * synced lyrics can take one); the "Live" toggle (the live synced-line bar
 * opt-in) sits seamlessly beside the close button. Matches
 * QueueBottomSheet's header proportions so the two sheets share their
 * silhouette when stacked in the Now Playing UI.
 */
@Composable
private fun LyricsHeader(
    liveEnabled: Boolean,
    onLiveToggle: (Boolean) -> Unit,
    onClose: () -> Unit,
    showOffsetButton: Boolean,
    currentOffsetMs: Long,
    onOffsetChange: (Long) -> Unit,
) {
    var showOffsetDialog by remember { mutableStateOf(false) }
    if (showOffsetDialog) {
        OffsetAdjustDialog(
            currentOffsetMs = currentOffsetMs,
            onOffsetChange = onOffsetChange,
            onDismiss = { showOffsetDialog = false },
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showOffsetButton) {
                TextButton(onClick = { showOffsetDialog = true }) {
                    Text(
                        text = if (currentOffsetMs == 0L) "Offset" else {
                            "Offset %+.1fs".format(Locale.ROOT, currentOffsetMs / 1000f)
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Text(text = "Lyrics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Live",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            com.stash.core.ui.components.StashSwitch(checked = liveEnabled, onCheckedChange = onLiveToggle)
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
        }
    }
}

/**
 * Sync-adjustment popup: ±5s slider in 0.1s steps, plus a text field for anything further out
 * (hard-capped ±60s).
 *
 * The slider only PERSISTS on release ([Slider.onValueChangeFinished]) — writing to Room on every
 * tick meant the just-written value flowed back down through [currentOffsetMs] mid-drag and
 * visibly snapped the slider backward. Dragging still moves the displayed number instantly via
 * local state; only the DB write is deferred to release.
 */
@Composable
private fun OffsetAdjustDialog(
    currentOffsetMs: Long,
    onOffsetChange: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var sliderSeconds by remember(currentOffsetMs) {
        mutableStateOf((currentOffsetMs / 1000f).coerceIn(-OFFSET_SLIDER_RANGE_S, OFFSET_SLIDER_RANGE_S))
    }
    var customText by remember(currentOffsetMs) {
        mutableStateOf("%.1f".format(Locale.ROOT, currentOffsetMs / 1000f))
    }

    fun apply(seconds: Float) {
        onOffsetChange((seconds.coerceIn(-OFFSET_HARD_CAP_S, OFFSET_HARD_CAP_S) * 1000f).roundToLong())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adjust sync") },
        text = {
            Column {
                Text(
                    text = "Positive delays lyrics, negative shows them earlier.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "%+.1f s".format(Locale.ROOT, sliderSeconds),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Slider(
                    value = sliderSeconds,
                    onValueChange = { v ->
                        sliderSeconds = v
                        customText = "%.1f".format(Locale.ROOT, v)
                    },
                    onValueChangeFinished = { apply(sliderSeconds) },
                    valueRange = -OFFSET_SLIDER_RANGE_S..OFFSET_SLIDER_RANGE_S,
                    steps = OFFSET_SLIDER_STEPS,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Further out:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Seconds") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        // Accept a comma decimal separator (Germany, France, ...) as well as a
                        // period — toFloatOrNull() only understands the latter regardless of the
                        // device's own locale, so a comma-locale device typing "1,5" and tapping
                        // Set silently did nothing.
                        val v = customText.replace(',', '.').toFloatOrNull() ?: return@TextButton
                        sliderSeconds = v.coerceIn(-OFFSET_SLIDER_RANGE_S, OFFSET_SLIDER_RANGE_S)
                        apply(v)
                    }) { Text("Set") }
                }
                TextButton(onClick = {
                    sliderSeconds = 0f
                    customText = "0.0"
                    apply(0f)
                }) { Text("Reset to 0") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * Body height in dp. Picked to match QueueBottomSheet's effective
 * working area on a Pixel 6 Pro after the navigation bar + header are
 * subtracted. Tall enough to host ~12 synced lines comfortably.
 */
private const val LYRICS_BODY_HEIGHT_DP = 560
private const val OFFSET_SLIDER_RANGE_S = 5f
private const val OFFSET_SLIDER_STEPS = 99
private const val OFFSET_HARD_CAP_S = 60f