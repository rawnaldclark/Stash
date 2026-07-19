package com.stash.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.common.constants.StashConstants
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsRowPadH
import com.stash.feature.settings.components.SettingsRowPadV
import com.stash.core.media.SleepTimerController
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsSectionLabel
import com.stash.feature.settings.components.SettingsSegmented
import com.stash.feature.settings.components.SettingsToggleRow
import kotlin.math.roundToInt

/**
 * The Playback spoke of the hub-and-spoke Settings redesign.
 *
 * This re-homes the original `StashConstants.STREAMING_ENGINE_ENABLED` block from
 * the monolithic `SettingsScreen.kt`: the Online/Offline mode picker plus the
 * streaming toggles (cellular, YouTube fallback, antra-only). This is a
 * behavior-preserving relocation + restyle — every control calls the SAME
 * [SettingsViewModel] method the old screen used; no logic is changed.
 */
@Composable
fun SettingsPlaybackScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val streamingEnabled by viewModel.streamingEnabled.collectAsStateWithLifecycle()
    val streamOnCellular by viewModel.streamOnCellular.collectAsStateWithLifecycle()
    val forceYouTubeFallback by viewModel.forceYouTubeFallback.collectAsStateWithLifecycle()
    val forceAmzOnly by viewModel.forceAmzOnly.collectAsStateWithLifecycle()
    val forceQbdlxOnly by viewModel.forceQbdlxOnly.collectAsStateWithLifecycle()
    val crossfadeEnabled by viewModel.crossfadeEnabled.collectAsStateWithLifecycle()
    val crossfadeDurationMs by viewModel.crossfadeDurationMs.collectAsStateWithLifecycle()

    SettingsScaffold(title = "Playback", onBack = onBack, modifier = modifier) {
        if (StashConstants.STREAMING_ENGINE_ENABLED) {
            SettingsSectionLabel("Mode")
            SettingsSegmented(
                options = listOf("Online", "Offline"),
                selectedIndex = if (streamingEnabled) 0 else 1,
                onSelect = { viewModel.onStreamingToggle(it == 0) },
            )

            SettingsSectionLabel("Streaming")
            SettingsGroupCard(
                rows = listOf(
                    {
                        SettingsToggleRow(
                            title = "Stream on cellular",
                            subtitle = "Allow streaming over mobile data (5G / LTE). Off by default to avoid surprise data use.",
                            checked = streamOnCellular,
                            onCheckedChange = viewModel::onStreamOnCellularToggle,
                        )
                    },
                    {
                        SettingsToggleRow(
                            title = "Stream via YouTube",
                            subtitle = "Skip the lossless sources (Qobuz) and stream everything via YouTube. Turn this on if lossless playback is down or only playing short clips.",
                            checked = forceYouTubeFallback,
                            onCheckedChange = viewModel::setForceYouTubeFallback,
                        )
                    },
                    // Force-ARCOD toggle row: removed 2026-07-01 while ARCOD is
                    // parked (host down). The pref + registry branch stay; restore
                    // this row (checked = forceArcodOnly,
                    // onCheckedChange = viewModel::setForceArcodOnly) to re-enable.
                    {
                        SettingsToggleRow(
                            title = "Stream via amz (test)",
                            subtitle = "Route streaming AND downloads through amz (Amazon Music) only — no Qobuz, no YouTube. For testing the amz source. Turn off after testing.",
                            checked = forceAmzOnly,
                            onCheckedChange = viewModel::setForceAmzOnly,
                        )
                    },
                    {
                        SettingsToggleRow(
                            title = "Force Direct Qobuz only (test)",
                            subtitle = "Route streaming AND downloads through Direct Qobuz only — no other sources, no YouTube. For testing the Direct Qobuz source. Turn off after testing.",
                            checked = forceQbdlxOnly,
                            onCheckedChange = viewModel::setForceQbdlxOnly,
                        )
                    },
                ),
            )
        } else {
            Text(
                text = "Streaming is unavailable in this build.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Crossfade applies to both streamed and downloaded tracks, so it sits
        // outside the streaming-engine gate.
        SettingsSectionLabel("Crossfade")
        SettingsGroupCard(
            rows = buildList {
                add {
                    SettingsToggleRow(
                        title = "Crossfade",
                        subtitle = "Fade the ending track into the next on auto-advance. Manual skips still cut instantly.",
                        checked = crossfadeEnabled,
                        onCheckedChange = viewModel::onCrossfadeToggle,
                    )
                }
                if (crossfadeEnabled) {
                    add {
                        CrossfadeDurationRow(
                            seconds = (crossfadeDurationMs / 1000L).toInt().coerceIn(1, 12),
                            onSecondsChange = { viewModel.onCrossfadeDurationChange(it * 1000L) },
                        )
                    }
                }
            },
        )

        // Sleep timer (fork issue ParaliyzedEvo/Stash#26): pauses playback
        // after the chosen delay or when the current track finishes. Lives
        // here per user direction — playback behavior belongs in Settings,
        // not the Now Playing chrome.
        val sleepTimer by viewModel.sleepTimerState.collectAsStateWithLifecycle()
        SettingsSectionLabel("Sleep timer")
        SettingsGroupCard(
            rows = buildList {
                val status = when (val st = sleepTimer) {
                    is SleepTimerController.State.Countdown -> {
                        val minutesLeft =
                            ((st.endsAtMs - System.currentTimeMillis()) / 60_000L)
                                .coerceAtLeast(0) + 1
                        "Music pauses in about $minutesLeft min"
                    }
                    SleepTimerController.State.EndOfTrack -> "Music pauses when the current track ends"
                    SleepTimerController.State.Off -> "Off"
                }
                add {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (sleepTimer == SleepTimerController.State.Off) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.padding(
                            horizontal = SettingsRowPadH,
                            vertical = SettingsRowPadV,
                        ),
                    )
                }
                listOf(15, 30, 45, 60).forEach { minutes ->
                    add {
                        SleepTimerChoiceRow(
                            label = "$minutes minutes",
                            onClick = { viewModel.onSleepTimerMinutes(minutes) },
                        )
                    }
                }
                add {
                    SleepTimerChoiceRow(
                        label = "End of track",
                        onClick = viewModel::onSleepTimerEndOfTrack,
                    )
                }
                if (sleepTimer != SleepTimerController.State.Off) {
                    add {
                        SleepTimerChoiceRow(
                            label = "Cancel timer",
                            tint = MaterialTheme.colorScheme.error,
                            onClick = viewModel::onSleepTimerCancel,
                        )
                    }
                }
            },
        )
    }
}

/** One tappable sleep-timer choice row. */
@Composable
private fun SleepTimerChoiceRow(
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = tint,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = SettingsRowPadH, vertical = SettingsRowPadV),
    )
}

/**
 * Duration slider row for the Crossfade section: 1–12 s in whole-second steps
 * (Material [Slider] `steps` counts internal stops, so 10 → 12 positions).
 * Stateless; the caller owns persistence.
 */
@Composable
private fun CrossfadeDurationRow(
    seconds: Int,
    onSecondsChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsRowPadH, vertical = SettingsRowPadV),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Duration",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "$seconds sec",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = seconds.toFloat(),
            onValueChange = { onSecondsChange(it.roundToInt()) },
            valueRange = 1f..12f,
            steps = 10,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}
