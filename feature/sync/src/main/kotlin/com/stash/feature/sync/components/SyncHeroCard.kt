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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/**
 * Gradient-tinted hero card carrying last-sync metadata + the Sync Now button.
 *
 * Two independent mode toggles: [playbackOnline] governs whether Now Playing
 * can stream tracks that aren't downloaded yet; [downloadOnline] governs
 * whether Sync Now writes real files to disk or only refreshes which tracks
 * are stream-eligible. They're orthogonal — see [PlaybackModePreference] /
 * [StreamingPreference] KDocs — so e.g. a user can keep playing a mixed
 * playlist (Playback = Online) uninterrupted while a sync runs with
 * Download = Offline in the background.
 *
 * @param playbackOnline         Current Playback Mode. True = streams
 *                                non-downloaded tracks on tap.
 * @param onPlaybackModeChange   Invoked with true for Online, false for
 *                                Offline when the user taps the Playback toggle.
 * @param downloadOnline         Current Download Mode (was `streamingMode`).
 *                                True = sync only refreshes the streamable
 *                                index; false = sync writes real files.
 * @param onDownloadModeChange   Invoked with true for Online, false for
 *                                Offline when the user taps the Downloads toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncHeroCard(
    lastSyncRelativeTime: String,
    lastSyncTrackCount: Int?,
    healthLabel: String,
    healthColor: Color,
    isSyncing: Boolean,
    playbackOnline: Boolean,
    onPlaybackModeChange: (Boolean) -> Unit,
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
                        text = "LAST SYNC",
                        style = MaterialTheme.typography.labelSmall,
                        color = StashTheme.extendedColors.purpleLight,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    val body = when {
                        lastSyncTrackCount == null -> "Never synced"
                        lastSyncTrackCount == 0 -> "$lastSyncRelativeTime · no new songs"
                        lastSyncTrackCount == 1 -> "$lastSyncRelativeTime · 1 new song"
                        else -> "$lastSyncRelativeTime · $lastSyncTrackCount new songs"
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

            // Playback mode: can Now Playing stream a non-downloaded track?
            Text(
                text = "PLAYBACK",
                style = MaterialTheme.typography.labelSmall,
                color = StashTheme.extendedColors.purpleLight,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = playbackOnline,
                    onClick = { if (!playbackOnline) onPlaybackModeChange(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    enabled = !isSyncing,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.CloudQueue,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = { Text("Online") },
                )
                SegmentedButton(
                    selected = !playbackOnline,
                    onClick = { if (playbackOnline) onPlaybackModeChange(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    enabled = !isSyncing,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.OfflinePin,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = { Text("Offline") },
                )
            }

            Spacer(Modifier.height(12.dp))

            // Download mode: does Sync Now write real files, or just refresh
            // the streamable index?
            Text(
                text = "DOWNLOADS",
                style = MaterialTheme.typography.labelSmall,
                color = StashTheme.extendedColors.purpleLight,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = downloadOnline,
                    onClick = { if (!downloadOnline) onDownloadModeChange(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    enabled = !isSyncing,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.CloudQueue,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = { Text("Online") },
                )
                SegmentedButton(
                    selected = !downloadOnline,
                    onClick = { if (downloadOnline) onDownloadModeChange(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    enabled = !isSyncing,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.OfflinePin,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = { Text("Offline") },
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
                        text = if (downloadOnline) "Update Streaming Index" else "Download Tracks to Device",
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
