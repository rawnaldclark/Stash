package com.stash.feature.settings.components

import android.widget.Toast
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
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.ListeningHistoryCounts
import com.stash.feature.settings.ListeningHistorySettingsViewModel
import com.stash.feature.settings.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a clear asks to confirm: everything, or the days [fromDay]..[toDay]. */
private sealed interface ClearRequest {
    data object Everything : ClearRequest
    data class Range(val fromDay: Long, val toDay: Long) : ClearRequest
}

/**
 * Settings › Library & Storage › Listening history: pause the history (no listens, skips or
 * scrobbles while paused), clear a date range, or clear everything. Each clear is confirmed with
 * how many plays go.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningHistorySheet(
    viewModel: ListeningHistorySettingsViewModel,
    onDismiss: () -> Unit,
) {
    val paused by viewModel.paused.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pickingRange by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ClearRequest?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = StashTheme.extendedColors.elevatedSurface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.settings_listening_history_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            val playsStr = pluralStringResource(R.plurals.settings_listening_history_plays_count, counts.totalPlays, counts.totalPlays)
            val sinceStr = counts.firstDay?.let { stringResource(R.string.settings_listening_history_since_date, longDate(it)) } ?: ""
            Text(
                text = if (counts.totalPlays == 0) {
                    stringResource(R.string.settings_listening_history_sheet_subtitle_empty)
                } else {
                    stringResource(R.string.settings_listening_history_sheet_subtitle_nonempty, playsStr, sinceStr)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            SettingsGroupCard(
                rows = listOf<@Composable () -> Unit>({
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_listening_history_pause_title),
                        subtitle = stringResource(R.string.settings_listening_history_pause_subtitle),
                        checked = paused,
                        onCheckedChange = viewModel::setPaused,
                    )
                }),
            )
            SettingsSectionLabel(stringResource(R.string.settings_listening_history_clear_section))
            SettingsGroupCard(
                rows = listOf<@Composable () -> Unit>(
                    {
                        ActionRow(
                            icon = Icons.Rounded.DateRange,
                            title = stringResource(R.string.settings_listening_history_clear_range_title),
                            subtitle = stringResource(R.string.settings_listening_history_clear_range_subtitle),
                            enabled = counts.totalPlays > 0,
                            onClick = { pickingRange = true },
                        )
                    },
                    {
                        val clearSubtitle = if (counts.totalPlays > 0) {
                            pluralStringResource(R.plurals.settings_listening_history_plays_count, counts.totalPlays, counts.totalPlays)
                        } else {
                            stringResource(R.string.settings_listening_history_nothing_to_clear)
                        }
                        ActionRow(
                            icon = Icons.Rounded.DeleteForever,
                            title = stringResource(R.string.settings_listening_history_clear_everything_title),
                            subtitle = clearSubtitle,
                            enabled = counts.totalPlays > 0,
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { confirm = ClearRequest.Everything },
                        )
                    },
                ),
            )
            Spacer(Modifier.height(10.dp))
            val keepsScrobbles = stringResource(R.string.settings_listening_history_clear_keeps_scrobbles)
            Text(
                text = stringResource(R.string.settings_listening_history_clear_footer, keepsScrobbles),
                style = MaterialTheme.typography.bodySmall,
                color = StashTheme.extendedColors.textTertiary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (pickingRange) {
        ClearRangePicker(
            counts = counts.copy(today = viewModel.today()),
            onPicked = { from, to ->
                pickingRange = false
                confirm = ClearRequest.Range(from, to)
            },
            onDismiss = { pickingRange = false },
        )
    }

    confirm?.let { request ->
        val keepsScrobbles = stringResource(R.string.settings_listening_history_clear_keeps_scrobbles)
        val (title, n) = when (request) {
            ClearRequest.Everything -> stringResource(R.string.settings_listening_history_confirm_everything_title) to counts.totalPlays
            is ClearRequest.Range -> {
                val label = if (request.fromDay == request.toDay) {
                    longDate(request.fromDay)
                } else {
                    "${longDate(request.fromDay)} – ${longDate(request.toDay)}"
                }
                stringResource(R.string.settings_listening_history_confirm_range_title, label) to counts.playsIn(request.fromDay, request.toDay)
            }
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = {
                val body = if (n > 0) {
                    val pStr = pluralStringResource(R.plurals.settings_listening_history_plays_count, n, n).replaceFirstChar { it.uppercase() }
                    pluralStringResource(R.plurals.settings_listening_history_confirm_plays_leave, n, pStr, keepsScrobbles)
                } else {
                    stringResource(R.string.settings_listening_history_confirm_no_listens, keepsScrobbles)
                }
                Text(body)
            },
            confirmButton = {
                TextButton(
                    enabled = n > 0,
                    onClick = {
                        when (request) {
                            ClearRequest.Everything -> viewModel.clearAll()
                            is ClearRequest.Range -> viewModel.clearRange(request.fromDay, request.toDay)
                        }
                        confirm = null
                    },
                ) { Text(stringResource(R.string.settings_listening_history_confirm_clear_button), color = if (n > 0) MaterialTheme.colorScheme.error else Color.Unspecified) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

/**
 * The days to clear, between the first listen and today. Material's date pickers speak UTC
 * midnights, which are exactly epoch-days (`millis / 86 400 000`), the unit history days use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClearRangePicker(
    counts: ListeningHistoryCounts,
    onPicked: (Long, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = counts.firstDay ?: counts.today
    val today = counts.today
    val state = rememberDateRangePickerState(
        yearRange = LocalDate.ofEpochDay(first).year..LocalDate.ofEpochDay(today).year,
        selectableDates = remember(first, today) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = (utcTimeMillis / MS_PER_DAY) in first..today
                override fun isSelectableYear(year: Int): Boolean =
                    year in LocalDate.ofEpochDay(first).year..LocalDate.ofEpochDay(today).year
            }
        },
    )
    val start = state.selectedStartDateMillis?.div(MS_PER_DAY)
    // One tap picks a single day; a second tap ends the range.
    val end = state.selectedEndDateMillis?.div(MS_PER_DAY) ?: start
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = start != null, onClick = { if (start != null && end != null) onPicked(start, end) }) {
                Text(stringResource(R.string.settings_listening_history_picker_next))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    ) {
        DateRangePicker(
            state = state,
            title = {
                Text(
                    stringResource(R.string.settings_listening_history_picker_title),
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                )
            },
            showModeToggle = false,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A settings row that does something (no chevron): icon, title, subtitle. */
@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint.copy(alpha = alpha), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = tint.copy(alpha = alpha))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = StashTheme.extendedColors.textTertiary.copy(alpha = alpha),
            )
        }
    }
}

private const val MS_PER_DAY = 86_400_000L


private fun longDate(day: Long): String =
    LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
