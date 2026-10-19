package com.stash.feature.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.stash.core.model.QualityTier
import com.stash.feature.settings.R

/**
 * Reusable radio group for the legacy YouTube quality tier
 * (MAX/BEST/HIGH/NORMAL/LOW). Used both at top-level when lossless
 * is off and inside the lossless card's YouTube-fallback expander
 * when lossless is on.
 *
 * Pure extraction of the inline radio-row block previously rendered
 * directly inside SettingsScreen's Audio Quality card. Visual output
 * is identical to that prior inline block.
 */
@Composable
internal fun AudioQualityPicker(
    selected: QualityTier,
    onSelected: (QualityTier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.selectableGroup()) {
        QualityTier.entries.forEach { tier ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == tier,
                        onClick = { onSelected(tier) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected == tier,
                    onClick = null, // handled by Row's selectable
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary,
                        unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        text = tier.displayLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_audio_quality_bitrate_size,
                            tier.bitrateKbps,
                            tier.sizeMbPerMinute,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun QualityTier.displayLabel(): String = when (this) {
    QualityTier.MAX -> stringResource(R.string.settings_quality_tier_max)
    QualityTier.BEST -> stringResource(R.string.settings_quality_tier_best)
    QualityTier.HIGH -> stringResource(R.string.settings_quality_tier_high)
    QualityTier.NORMAL -> stringResource(R.string.settings_quality_tier_normal)
    QualityTier.LOW -> stringResource(R.string.settings_quality_tier_low)
}

