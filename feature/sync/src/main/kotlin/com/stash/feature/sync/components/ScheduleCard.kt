package com.stash.feature.sync.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stash.core.data.sync.DayOfWeekSet
import com.stash.core.ui.components.GlassCard

@Composable
fun ScheduleCard(
    autoSyncEnabled: Boolean,
    syncDays: DayOfWeekSet,
    syncHour: Int,
    syncMinute: Int,
    wifiOnly: Boolean,
    onToggleAutoSync: () -> Unit,
    onSyncDaysChanged: (DayOfWeekSet) -> Unit,
    onTimeChanged: (Int, Int) -> Unit,
    onToggleWifiOnly: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var daysExpanded by remember { mutableStateOf(false) }
    var showTimeSheet by remember { mutableStateOf(false) }
    val emptyDays = syncDays.isEmpty
    val purple = MaterialTheme.colorScheme.primary
    val errorColor = Color(0xFFEF4444)

    GlassCard(modifier = modifier) {
        Column(modifier = Modifier.animateContentSize()) {
            // Auto-sync row — never dimmed, so users can always re-enable
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-sync", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = autoSyncEnabled,
                    onCheckedChange = { onToggleAutoSync() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = purple,
                    ),
                )
            }
            Spacer(Modifier.height(12.dp))
            // Everything below dims when auto-sync is off
            Column(modifier = Modifier.alpha(if (autoSyncEnabled) 1f else 0.5f)) {
                if (emptyDays) {
                    Text(
                        text = "Not scheduled — pick at least one day",
                        style = MaterialTheme.typography.bodyMedium,
                        color = errorColor,
                        modifier = Modifier.clickable { daysExpanded = true },
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sync ", style = MaterialTheme.typography.bodyMedium)
                        ScheduleChip(
                            text = syncDays.presetLabel(),
                            active = daysExpanded,
                            onClick = { daysExpanded = !daysExpanded },
                        )
                        Text(" at ", style = MaterialTheme.typography.bodyMedium)
                        ScheduleChip(
                            text = formatTime(syncHour, syncMinute),
                            active = false,
                            onClick = { showTimeSheet = true },
                        )
                        Text(" on ", style = MaterialTheme.typography.bodyMedium)
                        ScheduleChip(
                            text = if (wifiOnly) "Wi-Fi only" else "any network",
                            active = false,
                            onClick = { onToggleWifiOnly() },
                            muted = true,
                        )
                    }
                }

                if (daysExpanded) {
                    Spacer(Modifier.height(10.dp))
                    DayOfWeekPanel(
                        selection = syncDays,
                        onSelectionChanged = onSyncDaysChanged,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Tap any chip to change",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } // end of dimmed Column
        }
    }

    if (showTimeSheet) {
        SyncTimeBottomSheet(
            initialHour = syncHour,
            initialMinute = syncMinute,
            onConfirm = { hour, minute ->
                onTimeChanged(hour, minute)
                showTimeSheet = false
            },
            onDismiss = { showTimeSheet = false },
        )
    }
}

@Composable
private fun ScheduleChip(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    muted: Boolean = false,
) {
    val purple = MaterialTheme.colorScheme.primary
    val (bg, fg, border) = when {
        muted -> Triple(Color.White.copy(alpha = 0.05f), MaterialTheme.colorScheme.onSurfaceVariant, Color.Transparent)
        active -> Triple(purple.copy(alpha = 0.28f), purple, purple.copy(alpha = 0.55f))
        else -> Triple(purple.copy(alpha = 0.18f), purple, purple.copy(alpha = 0.35f))
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = fg,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(bg, RoundedCornerShape(999.dp))
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

private fun formatTime(hour: Int, minute: Int): String {
    val period = if (hour < 12) "AM" else "PM"
    val display = when (hour) {
        0 -> 12
        in 13..23 -> hour - 12
        else -> hour
    }
    return String.format("%d:%02d %s", display, minute, period)
}
