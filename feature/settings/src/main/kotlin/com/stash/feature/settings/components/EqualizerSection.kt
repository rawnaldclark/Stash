package com.stash.feature.settings.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.stash.core.ui.components.GlassCard

/**
 * Available equalizer presets.
 *
 * The "Custom" preset is automatically selected whenever the user manually
 * adjusts individual band gains, bass boost, or virtualizer values.
 */
private val EQ_PRESETS = listOf(
    "Flat", "Bass Boost", "Treble Boost", "Rock", "Pop", "Jazz", "Classical", "Vocal", "Custom",
)

/**
 * Full equalizer UI section for the Settings screen.
 *
 * Renders an enable toggle, preset chips, per-band gain sliders, bass boost,
 * and virtualizer controls inside a [GlassCard]. All slider values are
 * normalized to 0..1 range; the ViewModel is responsible for converting
 * to/from the actual millibel or strength values used by the audio engine.
 *
 * When [enabled] is false, all controls are visually dimmed and non-interactive.
 *
 * @param enabled           Whether the equalizer effect chain is active.
 * @param currentPreset     The name of the currently selected preset (must match one of [EQ_PRESETS]).
 * @param bandFrequencies   Display labels for each band, e.g. ["60Hz", "250Hz", "1kHz", "4kHz", "16kHz"].
 * @param bandGains         Normalized gain for each band (0.0 = min, 0.5 = flat, 1.0 = max).
 * @param bassBoost         Normalized bass boost strength (0.0..1.0).
 * @param virtualizer       Normalized virtualizer / surround strength (0.0..1.0).
 * @param onEnabledChanged  Callback when the user toggles the equalizer on/off.
 * @param onPresetSelected  Callback when the user selects a preset chip.
 * @param onBandGainChanged Callback when a band slider moves. Params: (bandIndex, normalizedGain).
 * @param onBassBoostChanged Callback when the bass boost slider moves.
 * @param onVirtualizerChanged Callback when the virtualizer slider moves.
 */
@Composable
fun EqualizerSection(
    enabled: Boolean,
    currentPreset: String,
    bandFrequencies: List<String>,
    bandGains: List<Float>,
    bassBoost: Float,
    virtualizer: Float,
    onEnabledChanged: (Boolean) -> Unit,
    onPresetSelected: (String) -> Unit,
    onBandGainChanged: (Int, Float) -> Unit,
    onBassBoostChanged: (Float) -> Unit,
    onVirtualizerChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {

            // -- Enable/disable toggle ----------------------------------------
            EnableToggle(
                enabled = enabled,
                onEnabledChanged = onEnabledChanged,
            )

            // -- All controls below dim when disabled -------------------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (enabled) 1f else 0.4f),
            ) {

                Spacer(modifier = Modifier.height(16.dp))

                // -- Preset chips ---------------------------------------------
                PresetChipRow(
                    currentPreset = currentPreset,
                    enabled = enabled,
                    onPresetSelected = onPresetSelected,
                )

                Spacer(modifier = Modifier.height(20.dp))

                // -- Per-band gain sliders ------------------------------------
                Text(
                    text = "Band Gain",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(8.dp))

                bandFrequencies.forEachIndexed { index, freqLabel ->
                    val gain = bandGains.getOrElse(index) { 0.5f }
                    BandGainRow(
                        frequencyLabel = freqLabel,
                        gain = gain,
                        enabled = enabled,
                        onGainChanged = { newGain -> onBandGainChanged(index, newGain) },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // -- Bass Boost -----------------------------------------------
                LabeledSlider(
                    label = "Bass Boost",
                    value = bassBoost,
                    enabled = enabled,
                    onValueChanged = onBassBoostChanged,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // -- Virtualizer / Surround -----------------------------------
                LabeledSlider(
                    label = "Surround",
                    value = virtualizer,
                    enabled = enabled,
                    onValueChanged = onVirtualizerChanged,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Internal composables
// ---------------------------------------------------------------------------

/**
 * Toggle row with "Equalizer" label and a [Switch].
 */
@Composable
private fun EnableToggle(
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Equalizer",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChanged,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}

/**
 * Horizontally scrollable row of [FilterChip]s for preset selection.
 */
@Composable
private fun PresetChipRow(
    currentPreset: String,
    enabled: Boolean,
    onPresetSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EQ_PRESETS.forEach { preset ->
            val selected = currentPreset.equals(preset, ignoreCase = true)

            val containerColor by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                label = "chipContainer",
            )

            FilterChip(
                selected = selected,
                onClick = { onPresetSelected(preset) },
                enabled = enabled,
                label = {
                    Text(
                        text = preset,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = containerColor,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

/**
 * A single horizontal band-gain slider row.
 *
 * Layout: `60 Hz   -----*-----  +3 dB`
 *
 * The dB display is an approximation: 0.0 maps to -15 dB, 0.5 maps to 0 dB,
 * and 1.0 maps to +15 dB. This is purely cosmetic -- the ViewModel converts
 * normalized values to actual millibels.
 */
@Composable
private fun BandGainRow(
    frequencyLabel: String,
    gain: Float,
    enabled: Boolean,
    onGainChanged: (Float) -> Unit,
) {
    // Convert normalized 0..1 to a display dB value (-15..+15)
    val displayDb = ((gain - 0.5f) * 30f).toInt()
    val dbText = if (displayDb >= 0) "+${displayDb} dB" else "$displayDb dB"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Frequency label -- fixed width for alignment
        Text(
            text = frequencyLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )

        Slider(
            value = gain,
            onValueChange = onGainChanged,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                disabledActiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
            ),
        )

        // dB value -- fixed width for alignment
        Text(
            text = dbText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
    }
}

/**
 * A horizontal slider with a text label on the left and a percentage on the right.
 *
 * Used for Bass Boost and Virtualizer controls.
 *
 * @param label          The display name shown on the left.
 * @param value          Current normalized value (0.0..1.0).
 * @param enabled        Whether the slider is interactive.
 * @param onValueChanged Callback when the slider is dragged.
 */
@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    enabled: Boolean,
    onValueChanged: (Float) -> Unit,
) {
    val percentText = "${(value * 100).toInt()}%"

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )

        Slider(
            value = value,
            onValueChange = onValueChanged,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                disabledActiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
            ),
        )

        Text(
            text = percentText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
    }
}
