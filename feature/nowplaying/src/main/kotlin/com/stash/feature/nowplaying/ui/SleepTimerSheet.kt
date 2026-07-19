package com.stash.feature.nowplaying.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stash.core.media.SleepTimerController

/**
 * Sleep-timer picker (fork issue ParaliyzedEvo/Stash#26): duration presets,
 * end-of-track, and a cancel row while armed. Playback pauses when the
 * timer fires — nothing is stopped or cleared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    state: SleepTimerController.State,
    onMinutes: (Int) -> Unit,
    onEndOfTrack: () -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 8.dp),
        ) {
            Text(
                text = "Sleep timer",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            // Armed status, computed once at sheet-open — precise enough for
            // a picker; no per-second ticking.
            val statusLine = remember(state) {
                when (state) {
                    is SleepTimerController.State.Countdown -> {
                        val minutesLeft =
                            ((state.endsAtMs - System.currentTimeMillis()) / 60_000L)
                                .coerceAtLeast(0) + 1
                        "Music pauses in about $minutesLeft min"
                    }
                    SleepTimerController.State.EndOfTrack -> "Music pauses when this track ends"
                    SleepTimerController.State.Off -> null
                }
            }
            if (statusLine != null) {
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        listOf(15, 30, 45, 60).forEach { minutes ->
            TimerRow(
                icon = Icons.Default.Bedtime,
                label = "$minutes minutes",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onMinutes(minutes) },
            )
        }
        TimerRow(
            icon = Icons.Default.SkipNext,
            label = "End of track",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onEndOfTrack,
        )
        if (state != SleepTimerController.State.Off) {
            TimerRow(
                icon = Icons.Default.Close,
                label = "Cancel timer",
                tint = MaterialTheme.colorScheme.error,
                onClick = onCancelTimer,
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun TimerRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
