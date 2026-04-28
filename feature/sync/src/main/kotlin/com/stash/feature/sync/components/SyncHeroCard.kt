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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/**
 * Gradient-tinted hero card carrying last-sync metadata + the Sync Now button.
 *
 * When a sync is running, the button is replaced by [progressContent] (the
 * multi-phase progress widget extracted as [SyncActionProgress]).
 *
 * @param lastSyncRelativeTime  e.g. "2 hours ago", "Yesterday", or "" when never synced.
 * @param lastSyncTrackCount    Total tracks downloaded in the most recent sync, or null
 *                              if never synced.
 * @param healthLabel           "✓ healthy" / "! partial" / "× failed" — small status text.
 * @param healthColor           Tint for the health label (success / warning / error).
 * @param isSyncing             True while a sync is in progress.
 * @param onSyncNow             Invoked when the Sync Now button is tapped.
 * @param progressContent       Slot shown when [isSyncing] is true, in place of the button.
 */
@Composable
fun SyncHeroCard(
    lastSyncRelativeTime: String,
    lastSyncTrackCount: Int?,
    healthLabel: String,
    healthColor: Color,
    isSyncing: Boolean,
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
                        else -> "$lastSyncRelativeTime · $lastSyncTrackCount tracks"
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
                        text = "Sync Now",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
