package com.stash.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stash.core.model.DownloadMode
import com.stash.core.model.QualityTier
import com.stash.core.ui.components.GlassCard
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.feature.settings.components.AudioQualityPicker
import com.stash.feature.settings.components.SettingsPickerRow
import com.stash.feature.settings.components.SettingsSectionLabel
import com.stash.feature.settings.components.SettingsToggleRow

/**
 * Settings › Audio & Quality › Downloads: what every download saves, and only the switches that
 * mode uses.
 *
 *  - Lossless: the FLAC tier, auto-upgrade to FLAC, "Tidal AAC when no FLAC", the lossy fallback.
 *  - AAC 320: the lossy fallback (for a song Tidal has no AAC of).
 *  - Opus: the YouTube quality.
 *
 * Streaming is set in the Lossless card below; this card is downloads only.
 */
@Composable
internal fun DownloadQualityCard(
    mode: DownloadMode,
    onModeChanged: (DownloadMode) -> Unit,
    flacTier: LosslessQualityTier,
    onFlacTierChanged: (LosslessQualityTier) -> Unit,
    autoFlacUpgrade: Boolean,
    onAutoFlacUpgradeChanged: (Boolean) -> Unit,
    tidalAacFallback: Boolean,
    onTidalAacFallbackChanged: (Boolean) -> Unit,
    lossyFallback: Boolean,
    onLossyFallbackChanged: (Boolean) -> Unit,
    youTubeQuality: QualityTier,
    onYouTubeQualityChanged: (QualityTier) -> Unit,
) {
    SettingsSectionLabel(stringResource(R.string.settings_downloads_section))
    GlassCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.settings_download_quality_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Column(modifier = Modifier.selectableGroup()) {
                DownloadMode.entries.forEach { option ->
                    SettingsPickerRow(
                        selected = mode == option,
                        title = stringResource(option.titleRes()),
                        subtitle = stringResource(option.descriptionRes()),
                        onClick = { onModeChanged(option) },
                    )
                }
            }

            if (mode == DownloadMode.LOSSLESS) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_flac_quality_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Column(modifier = Modifier.selectableGroup()) {
                    listOf(LosslessQualityTier.MAX, LosslessQualityTier.HI_RES, LosslessQualityTier.CD).forEach { tier ->
                        SettingsPickerRow(
                            selected = flacTier == tier,
                            title = tier.displayLabel(),
                            subtitle = tier.sizeHint(),
                            onClick = { onFlacTierChanged(tier) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                SettingsToggleRow(
                    title = stringResource(R.string.settings_auto_flac_upgrade_title),
                    subtitle = stringResource(R.string.settings_auto_flac_upgrade_subtitle),
                    checked = autoFlacUpgrade,
                    onCheckedChange = onAutoFlacUpgradeChanged,
                )
                Spacer(modifier = Modifier.height(8.dp))
                SettingsToggleRow(
                    title = stringResource(R.string.settings_tidal_aac_fallback_title),
                    subtitle = stringResource(R.string.settings_tidal_aac_fallback_subtitle),
                    checked = tidalAacFallback,
                    onCheckedChange = onTidalAacFallbackChanged,
                )
            }

            if (mode == DownloadMode.LOSSLESS || mode == DownloadMode.AAC) {
                Spacer(modifier = Modifier.height(8.dp))
                SettingsToggleRow(
                    title = stringResource(R.string.settings_lossy_fallback_title),
                    subtitle = stringResource(
                        if (mode == DownloadMode.AAC) {
                            R.string.settings_lossy_fallback_switch_label_aac
                        } else {
                            R.string.settings_lossy_fallback_switch_label
                        },
                    ),
                    checked = lossyFallback,
                    onCheckedChange = onLossyFallbackChanged,
                )
            }

            // The YouTube quality: everything in Opus mode, the last rung of the lossy fallback
            // otherwise.
            if (mode == DownloadMode.OPUS || lossyFallback) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_youtube_quality_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.settings_download_quality_rates_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                AudioQualityPicker(selected = youTubeQuality, onSelected = onYouTubeQualityChanged)
            }
        }
    }
}

private fun DownloadMode.titleRes(): Int = when (this) {
    DownloadMode.LOSSLESS -> R.string.settings_download_mode_lossless
    DownloadMode.AAC -> R.string.settings_download_mode_aac
    DownloadMode.OPUS -> R.string.settings_download_mode_opus
}

private fun DownloadMode.descriptionRes(): Int = when (this) {
    DownloadMode.LOSSLESS -> R.string.settings_download_mode_lossless_desc
    DownloadMode.AAC -> R.string.settings_download_mode_aac_desc
    DownloadMode.OPUS -> R.string.settings_download_mode_opus_desc
}
