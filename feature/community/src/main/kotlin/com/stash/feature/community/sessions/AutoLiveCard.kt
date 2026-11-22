package com.stash.feature.community.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.stash.core.model.community.AutoLiveMode
import com.stash.core.model.community.AutoLiveSchedule
import com.stash.core.model.community.ScheduleWindow
import com.stash.core.model.community.Station
import com.stash.core.ui.components.MinTouch
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.community.R
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** A day of the week (1 = Monday) as the phone's language shortens it: "Mon". */
internal fun dayName(day: Int, locale: Locale): String =
    DayOfWeek.of(day.coerceIn(1, 7)).getDisplayName(TextStyle.SHORT, locale)

/** "Mon 09:00 – 17:00". */
internal fun hoursLine(window: ScheduleWindow, locale: Locale): String = "${dayName(window.day, locale)} ${window.start} – ${window.end}"

/** What typing a time comes to: "9" and "9:5" aren't times, "09:05" is. Null until it is one. */
internal fun timeOrNull(text: String): String? =
    text.trim().takeIf { Regex("([01][0-9]|2[0-3]):[0-5][0-9]").matches(it) }

@Composable
private fun Chip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = if (selected) scheme.onPrimary else scheme.onSurface,
        maxLines = 1,
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(if (selected) scheme.primary else scheme.onSurface.copy(alpha = 0.08f))
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * Your station's auto-live (Sessions step 6): Off, On a schedule or Always, and for a schedule its hours (up to
 * seven windows), each removable, with Add hours.
 */
@OptIn(ExperimentalLayoutApi::class)
/** Your station's Auto-accept: one switch, in the same card as the rest of the page. */
@Composable
internal fun AutoAcceptCard(on: Boolean, onChange: (Boolean) -> Unit) {
    val ext = StashTheme.extendedColors
    val shape = MaterialTheme.shapes.extraLarge
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ext.glassBackground)
            .border(1.dp, ext.glassBorder, shape)
            .clickable(role = Role.Switch) { onChange(!on) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.station_auto_accept), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.station_auto_accept_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

@Composable
internal fun AutoLiveCard(
    station: Station,
    busy: Boolean,
    onMode: (AutoLiveMode) -> Unit,
    onAddHours: (ScheduleWindow) -> Unit,
    onRemoveHours: (ScheduleWindow) -> Unit,
) {
    val ext = StashTheme.extendedColors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val shape = MaterialTheme.shapes.extraLarge
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val mode = AutoLiveMode.of(station.autoLive)
    val windows = station.schedule?.windows.orEmpty()
    var adding by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().clip(shape).background(ext.glassBackground).border(1.dp, ext.glassBorder, shape).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.autolive_title), style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.semantics { heading() })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.autolive_off), mode == AutoLiveMode.OFF, !busy) { onMode(AutoLiveMode.OFF) }
            Chip(stringResource(R.string.autolive_schedule), mode == AutoLiveMode.SCHEDULE, !busy) { onMode(AutoLiveMode.SCHEDULE) }
            Chip(stringResource(R.string.autolive_always), mode == AutoLiveMode.ALWAYS, !busy) { onMode(AutoLiveMode.ALWAYS) }
        }
        Text(
            stringResource(
                when (mode) {
                    AutoLiveMode.OFF -> R.string.autolive_off_body
                    AutoLiveMode.SCHEDULE -> R.string.autolive_schedule_body
                    AutoLiveMode.ALWAYS -> R.string.autolive_always_body
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        if (mode == AutoLiveMode.SCHEDULE) {
            windows.forEach { window ->
                Row(Modifier.fillMaxWidth().heightIn(min = MinTouch), verticalAlignment = Alignment.CenterVertically) {
                    Text(hoursLine(window, locale), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onRemoveHours(window) }, enabled = !busy) { Text(stringResource(R.string.autolive_remove)) }
                }
            }
            if (windows.size < AutoLiveSchedule.MAX_WINDOWS) {
                TextButton(onClick = { adding = true }, enabled = !busy) { Text(stringResource(R.string.autolive_add)) }
            }
        }
    }

    if (adding) {
        var day by remember { mutableIntStateOf(1) }
        var start by remember { mutableStateOf("09:00") }
        var end by remember { mutableStateOf("17:00") }
        val window = timeOrNull(start)?.let { s -> timeOrNull(end)?.let { e -> ScheduleWindow(day, s, e) } }
        val ok = window != null && AutoLiveSchedule.isValid(window) && window !in windows
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.autolive_add)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..7).forEach { d -> Chip(dayName(d, locale), day == d, true) { day = d } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = start,
                            onValueChange = { if (it.length <= 5) start = it },
                            label = { Text(stringResource(R.string.autolive_from)) },
                            singleLine = true,
                            isError = timeOrNull(start) == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = end,
                            onValueChange = { if (it.length <= 5) end = it },
                            label = { Text(stringResource(R.string.autolive_to)) },
                            singleLine = true,
                            isError = timeOrNull(end) == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(stringResource(R.string.autolive_hours_hint), style = MaterialTheme.typography.bodySmall, color = muted)
                }
            },
            confirmButton = {
                TextButton(onClick = { window?.let(onAddHours); adding = false }, enabled = ok) { Text(stringResource(R.string.station_save)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.station_cancel)) } },
        )
    }
}
