package com.stash.feature.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.prefs.HiResOutputMode
import com.stash.core.media.output.SignalQuality
import com.stash.feature.settings.components.SettingsChoice
import com.stash.feature.settings.components.SettingsChoiceSheet
import com.stash.feature.settings.components.SettingsValueRow

/**
 * Audio & Quality > Output > Hi-Res output: one row showing the current mode,
 * opening a sheet with the three choices, each saying what it costs:
 *  - **Standard**: Android's shared mixer (usually 48 kHz), what every app gets.
 *  - **Match USB DAC**: a USB DAC is reopened at the song's sample rate, so
 *    nothing is resampled; EQ, crossfade and notifications still work.
 *  - **Bit-perfect**: the samples reach the DAC unaltered; EQ, normalization
 *    and crossfade step aside, and volume becomes the DAC's job (confirmed
 *    with a warning, since some DACs then play at full level).
 *
 * While something plays, the row's subtitle (and the sheet's top line) is the
 * same verdict the Now Playing signal-path sheet shows, so the listener can
 * see the setting take effect.
 */
@Composable
fun HiResOutputRow(viewModel: HiResOutputViewModel = hiltViewModel()) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val verdict by viewModel.signalPath.collectAsStateWithLifecycle()
    val supported = viewModel.isSupported
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var confirmBitPerfect by remember { mutableStateOf(false) }

    val now = verdict
    val device = now?.deviceName
    val verdictText = when {
        now == null -> null
        device != null -> stringResource(R.string.settings_hires_verdict_now_with_device, now.headline, device)
        else -> stringResource(R.string.settings_hires_verdict_now, now.headline)
    }
    val verdictColor = if (now?.quality == SignalQuality.RESAMPLED) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    SettingsValueRow(
        title = stringResource(R.string.settings_hires_output_title),
        value = stringResource(
            when (mode) {
                HiResOutputMode.STANDARD -> R.string.settings_hires_mode_standard
                HiResOutputMode.MATCH_RATE -> R.string.settings_hires_value_match_rate
                HiResOutputMode.BIT_PERFECT -> R.string.settings_hires_value_bit_perfect
            },
        ),
        subtitle = verdictText,
        onClick = { sheetOpen = true },
    )

    if (sheetOpen) {
        val unsupported = stringResource(R.string.settings_hires_needs_android_14)
        SettingsChoiceSheet(
            title = stringResource(R.string.settings_hires_output_title),
            header = {
                Text(
                    text = verdictText ?: stringResource(R.string.settings_hires_verdict_idle),
                    style = MaterialTheme.typography.bodySmall,
                    color = verdictColor,
                )
            },
            choices = listOf(
                SettingsChoice(
                    HiResOutputMode.STANDARD,
                    stringResource(R.string.settings_hires_mode_standard),
                    stringResource(R.string.settings_hires_mode_standard_desc),
                ),
                SettingsChoice(
                    HiResOutputMode.MATCH_RATE,
                    stringResource(R.string.settings_hires_mode_match_rate),
                    if (supported) stringResource(R.string.settings_hires_mode_match_rate_desc) else unsupported,
                    enabled = supported,
                ),
                SettingsChoice(
                    HiResOutputMode.BIT_PERFECT,
                    stringResource(R.string.settings_hires_mode_bit_perfect),
                    if (supported) stringResource(R.string.settings_hires_mode_bit_perfect_desc) else unsupported,
                    enabled = supported,
                ),
            ),
            selected = mode,
            onSelect = { picked ->
                when {
                    picked == mode -> Unit
                    picked == HiResOutputMode.BIT_PERFECT -> confirmBitPerfect = true
                    else -> viewModel.setMode(picked)
                }
            },
            onDismiss = { sheetOpen = false },
            footer = stringResource(R.string.settings_hires_footer_note),
        )
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
