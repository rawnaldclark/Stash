package com.stash.feature.sync.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.sync.R

/**
 * Gradient-tinted hero card carrying last-sync metadata + the Sync Now button.
 *
 * One mode control: the Download switch. [downloadOnline] governs whether
 * Sync Now writes real files to disk or only refreshes which tracks are
 * stream-eligible; playback streams either way (since 2026-09-17 the player
 * no longer reads [StreamingPreference]). The second "Playback" control that
 * #413 added here wrote a preference the player never read; removed the
 * same day.
 *
 * @param downloadOnline         Current Download Mode (was `streamingMode`).
 *                                True = sync only refreshes the streamable
 *                                index (switch OFF); false = sync writes real
 *                                files (switch ON).
 * @param onDownloadModeChange   Invoked with `!checked` when the user taps
 *                                the switch: true = stream only, false = download.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncHeroCard(
    lastSyncRelativeTime: String,
    lastSyncTrackCount: Int?,
    healthLabel: String,
    healthColor: Color,
    isSyncing: Boolean,
    downloadOnline: Boolean,
    onDownloadModeChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    progressContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val purple = MaterialTheme.colorScheme.primary
    val cyan = StashTheme.extendedColors.cyan
    val gradient = Brush.linearGradient(
        colors = listOf(
            purple.copy(alpha = 0.18f),
            cyan.copy(alpha = 0.08f),
        ),
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, purple.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .background(gradient, RoundedCornerShape(18.dp))
                .padding(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.sync_hero_last_sync),
                        style = MaterialTheme.typography.labelSmall,
                        color = StashTheme.extendedColors.purpleLight,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    val body = when {
                        lastSyncTrackCount == null -> stringResource(R.string.sync_hero_never_synced)
                        lastSyncTrackCount == 0 -> stringResource(R.string.sync_hero_no_new_songs, lastSyncRelativeTime)
                        else -> pluralStringResource(R.plurals.sync_hero_new_songs, lastSyncTrackCount, lastSyncRelativeTime, lastSyncTrackCount)
                    }
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (lastSyncTrackCount != null) {
                    Text(
                        text = healthLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = healthColor,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            // The app's one mode switch. ON = Download mode: Sync Now writes
            // switched-on playlists and mixes to this phone. OFF = sync only
            // keeps the streamable index. Playback streams either way.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.sync_hero_download),
                        style = MaterialTheme.typography.labelSmall,
                        color = StashTheme.extendedColors.purpleLight,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            if (downloadOnline) R.string.sync_hero_download_off
                            else R.string.sync_hero_download_on
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                com.stash.core.ui.components.StashSwitch(
                    checked = !downloadOnline,
                    onCheckedChange = { on -> onDownloadModeChange(!on) },
                    enabled = !isSyncing,
                )
            }

            Spacer(Modifier.height(12.dp))

            if (isSyncing) {
                progressContent()
            } else {
                Button(
                    onClick = onSyncNow,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = purple),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            if (downloadOnline) R.string.sync_hero_btn_stream
                            else R.string.sync_hero_btn_download
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
