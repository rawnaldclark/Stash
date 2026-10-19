package com.stash.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.prefs.HiResOutputMode
import com.stash.core.media.output.SignalQuality
import com.stash.core.ui.components.GlassCard
import com.stash.feature.settings.components.SettingsPickerRow
import com.stash.feature.settings.components.SettingsSectionLabel

/**
 * Audio & Quality › Hi-Res Output.
 *
 * Three honest choices, each saying what it costs:
 *  - **Standard** — Android's shared mixer (usually 48 kHz): what every app gets.
 *  - **Match DAC to each song** — a USB DAC is reopened at the song's sample
 *    rate, so nothing is resampled; EQ, crossfade and notifications still work.
 *  - **Bit-perfect** — the samples reach the DAC unaltered; EQ, normalization
 *    and crossfade step aside, and volume becomes the DAC's job (confirmed
 *    with a warning, since some DACs then play at full level).
 *
 * The live line on top is the same verdict the Now Playing signal-path sheet
 * shows, so the user can see the setting take effect.
 */
@Composable
fun HiResOutputSection(viewModel: HiResOutputViewModel = hiltViewModel()) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val verdict by viewModel.signalPath.collectAsStateWithLifecycle()
    val supported = viewModel.isSupported
    var confirmBitPerfect by remember { mutableStateOf(false) }

    SettingsSectionLabel(stringResource(R.string.settings_hires_output_section))
    GlassCard {
        Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
            val now = verdict
            val device = now?.deviceName
            val verdictText = when {
                now == null -> stringResource(R.string.settings_hires_verdict_idle)
                device != null -> stringResource(
                    R.string.settings_hires_verdict_now_with_device,
                    now.headline,
                    device,
                )
                else -> stringResource(R.string.settings_hires_verdict_now, now.headline)
            }
            Text(
                text = verdictText,
                style = MaterialTheme.typography.bodySmall,
                color = if (now?.quality == SignalQuality.RESAMPLED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(modifier = Modifier.height(8.dp))

            SettingsPickerRow(
                selected = mode == HiResOutputMode.STANDARD,
                title = stringResource(R.string.settings_hires_mode_standard),
                subtitle = stringResource(R.string.settings_hires_mode_standard_desc),
                onClick = { viewModel.setMode(HiResOutputMode.STANDARD) },
            )
            val hiResRowModifier = Modifier.alpha(if (supported) 1f else 0.38f)
            SettingsPickerRow(
                selected = mode == HiResOutputMode.MATCH_RATE,
                title = stringResource(R.string.settings_hires_mode_match_rate),
                subtitle = if (supported) {
                    stringResource(R.string.settings_hires_mode_match_rate_desc)
                } else {
                    stringResource(R.string.settings_hires_needs_android_14)
                },
                onClick = { if (supported) viewModel.setMode(HiResOutputMode.MATCH_RATE) },
                modifier = hiResRowModifier,
            )
            SettingsPickerRow(
                selected = mode == HiResOutputMode.BIT_PERFECT,
                title = stringResource(R.string.settings_hires_mode_bit_perfect),
                subtitle = if (supported) {
                    stringResource(R.string.settings_hires_mode_bit_perfect_desc)
                } else {
                    stringResource(R.string.settings_hires_needs_android_14)
                },
                onClick = {
                    if (supported && mode != HiResOutputMode.BIT_PERFECT) confirmBitPerfect = true
                },
                modifier = hiResRowModifier,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_hires_footer_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmBitPerfect) {
        AlertDialog(
            onDismissRequest = { confirmBitPerfect = false },
            title = { Text(stringResource(R.string.settings_hires_bit_perfect_dialog_title)) },
            text = {
                Text(stringResource(R.string.settings_hires_bit_perfect_dialog_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBitPerfect = false
                    viewModel.setMode(HiResOutputMode.BIT_PERFECT)
                }) { Text(stringResource(R.string.settings_hires_bit_perfect_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBitPerfect = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}
