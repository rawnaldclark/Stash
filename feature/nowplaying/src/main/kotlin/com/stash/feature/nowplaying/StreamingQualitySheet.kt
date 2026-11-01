package com.stash.feature.nowplaying

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stash.core.ui.components.StashSwitch

/**
 * ⋮ › Streaming quality (#716): Settings › Audio & Quality's streaming switches, in the order
 * and words Settings uses (Prefer Tidal AAC only with Lossless on, as there). Laid out like the
 * Playback speed sheet: a centred title, a line under it, then the choices.
 *
 * Not offered: the per-network FLAC tiers (Settings, picked once per network) and the lossless
 * sources (a setup page). The footnote says which switch saves the daily allowance, the reason
 * people change this mid-listen: Tidal AAC is smaller, but it counts like FLAC.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StreamingQualitySheet(
    state: StreamingQualityState,
    onLosslessChange: (Boolean) -> Unit,
    onSaveDataChange: (Boolean) -> Unit,
    onPreferTidalAacChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = com.stash.core.ui.theme.StashTheme.extendedColors.elevatedSurface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 36.dp)) {
            Text(
                text = stringResource(R.string.np_quality_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                // As the Track Options header: the sheet's default content colour is the subtitles' grey.
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp).align(Alignment.CenterHorizontally),
            )
            Text(
                text = stringResource(R.string.np_quality_when),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp).align(Alignment.CenterHorizontally),
            )
            QualitySwitchRow(
                title = stringResource(R.string.np_quality_lossless_title),
                // Settings' three-way subtitle: "FLAC" only when a lossless source can serve it.
                subtitle = stringResource(
                    when {
                        !state.lossless -> R.string.np_quality_lossless_subtitle_off
                        state.noLosslessSource -> R.string.np_quality_lossless_subtitle_no_source
                        else -> R.string.np_quality_lossless_subtitle_active
                    },
                ),
                checked = state.lossless,
                onCheckedChange = onLosslessChange,
            )
            QualitySwitchRow(
                title = stringResource(R.string.np_quality_save_data_title),
                // Lossless stays lossless: Save Data is its lowest tier, or the lowest lossy quality.
                subtitle = stringResource(
                    if (state.lossless) R.string.np_quality_save_data_lossless_subtitle else R.string.np_quality_save_data_subtitle,
                ),
                checked = state.saveData,
                onCheckedChange = onSaveDataChange,
            )
            // Only with Lossless on, as in Settings: Tidal is a lossless-relay rung.
            if (state.lossless) {
                QualitySwitchRow(
                    title = stringResource(R.string.np_quality_prefer_tidal_aac_title),
                    subtitle = stringResource(R.string.np_quality_prefer_tidal_aac_subtitle),
                    checked = state.preferTidalAac,
                    onCheckedChange = onPreferTidalAacChange,
                )
            }
            Text(
                text = stringResource(R.string.np_quality_allowance_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp).padding(top = 12.dp),
            )
        }
    }
}

/** One switch: the whole row toggles it, and TalkBack reads row and switch as one control. */
@Composable
private fun QualitySwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        // No handler of its own: the row's toggleable takes the tap.
        StashSwitch(checked = checked, onCheckedChange = null)
    }
}
