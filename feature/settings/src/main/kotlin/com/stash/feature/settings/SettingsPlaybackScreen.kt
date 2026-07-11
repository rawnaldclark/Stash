package com.stash.feature.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.common.constants.StashConstants
import com.stash.core.ui.R
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsRowPadH
import com.stash.feature.settings.components.SettingsRowPadV
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsSectionLabel
import com.stash.feature.settings.components.SettingsSegmented
import com.stash.feature.settings.components.SettingsToggleRow

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

    SettingsScaffold(title = stringResource(R.string.title_playback), onBack = onBack, modifier = modifier) {
        if (StashConstants.STREAMING_ENGINE_ENABLED) {
            SettingsSectionLabel(stringResource(R.string.section_mode))
            SettingsSegmented(
                options = listOf(stringResource(R.string.mode_online), stringResource(R.string.mode_offline)),
                selectedIndex = if (streamingEnabled) 0 else 1,
                onSelect = { viewModel.onStreamingToggle(it == 0) },
            )

            SettingsSectionLabel(stringResource(R.string.section_streaming))
            SettingsGroupCard(
                rows = listOf(
                    {
                        SettingsToggleRow(
                            title = stringResource(R.string.label_stream_cellular),
                            subtitle = stringResource(R.string.desc_stream_cellular),
                            checked = streamOnCellular,
                            onCheckedChange = viewModel::onStreamOnCellularToggle,
                        )
                    },
                    {
                        SettingsToggleRow(
                            title = stringResource(R.string.label_stream_youtube),
                            subtitle = stringResource(R.string.desc_stream_youtube),
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
                            title = stringResource(R.string.label_stream_amz),
                            subtitle = stringResource(R.string.desc_stream_amz),
                            checked = forceAmzOnly,
                            onCheckedChange = viewModel::setForceAmzOnly,
                        )
                    },
                    {
                        SettingsToggleRow(
                            title = stringResource(R.string.label_force_qobuz),
                            subtitle = stringResource(R.string.desc_force_qobuz),
                            checked = forceQbdlxOnly,
                            onCheckedChange = viewModel::setForceQbdlxOnly,
                        )
                    },
                ),
            )
        } else {
            Text(
                text = stringResource(R.string.label_streaming_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Crossfade applies to both streamed and downloaded tracks, so it sits
        // outside the streaming-engine gate.
        SettingsSectionLabel(stringResource(R.string.label_crossfade))
        SettingsGroupCard(
            rows = buildList {
                add {
                    SettingsToggleRow(
                        title = stringResource(R.string.label_crossfade),
                        subtitle = stringResource(R.string.desc_crossfade),
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
    }
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
                text = stringResource(R.string.label_duration),
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
            onValueChange = { onSecondsChange(it.toInt()) },
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
