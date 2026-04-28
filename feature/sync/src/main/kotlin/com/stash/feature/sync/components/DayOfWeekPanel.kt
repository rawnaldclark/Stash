package com.stash.feature.sync.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stash.core.data.sync.DayOfWeekSet
import com.stash.core.ui.theme.StashTheme
import java.time.DayOfWeek

@Composable
fun DayOfWeekPanel(
    selection: DayOfWeekSet,
    onSelectionChanged: (DayOfWeekSet) -> Unit,
    modifier: Modifier = Modifier,
) {
    val purple = MaterialTheme.colorScheme.primary

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = purple.copy(alpha = 0.06f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, purple.copy(alpha = 0.25f)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "REPEAT ON",
                style = MaterialTheme.typography.labelSmall,
                color = StashTheme.extendedColors.purpleLight,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                DayOfWeek.values().forEach { day ->
                    DayCircle(
                        label = day.shortLabel(),
                        on = selection.contains(day),
                        onToggle = { newOn -> onSelectionChanged(selection.with(day, newOn)) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PresetChip("Daily", selection.isDaily) { onSelectionChanged(DayOfWeekSet.EVERY_DAY) }
                PresetChip("Weekdays", selection.isWeekdays) { onSelectionChanged(DayOfWeekSet.WEEKDAYS) }
                PresetChip("Weekends", selection.isWeekends) { onSelectionChanged(DayOfWeekSet.WEEKENDS) }
            }
        }
    }
}

@Composable
private fun DayCircle(label: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    val purple = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(
                color = if (on) purple else Color.White.copy(alpha = 0.04f),
                shape = CircleShape,
            )
            .border(
                width = 1.dp,
                color = if (on) purple else Color.White.copy(alpha = 0.08f),
                shape = CircleShape,
            )
            .clickable { onToggle(!on) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val purple = MaterialTheme.colorScheme.primary
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) purple else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                if (selected) purple.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f),
                RoundedCornerShape(999.dp),
            )
            .border(
                1.dp,
                if (selected) purple.copy(alpha = 0.4f) else Color.Transparent,
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

private fun DayOfWeek.shortLabel(): String = when (this) {
    DayOfWeek.MONDAY -> "M"
    DayOfWeek.TUESDAY -> "T"
    DayOfWeek.WEDNESDAY -> "W"
    DayOfWeek.THURSDAY -> "T"
    DayOfWeek.FRIDAY -> "F"
    DayOfWeek.SATURDAY -> "S"
    DayOfWeek.SUNDAY -> "S"
}
