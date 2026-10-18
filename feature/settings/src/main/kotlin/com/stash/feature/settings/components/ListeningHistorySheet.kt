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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.ListeningHistoryCounts
import com.stash.feature.settings.ListeningHistorySettingsViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Plan §4, word for word: the line every clear says. */
internal const val CLEAR_KEEPS_SCROBBLES =
    "This doesn't remove anything already sent to Last.fm, ListenBrainz or YouTube Music."

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
            Text("Listening history", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (counts.totalPlays == 0) {
                    "What this phone remembers about the songs you play."
                } else {
                    "This phone remembers ${plays(counts.totalPlays)}" +
                        (counts.firstDay?.let { " since ${longDate(it)}" } ?: "") + "."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            SettingsGroupCard(
                rows = listOf<@Composable () -> Unit>({
                    SettingsToggleRow(
                        title = "Pause history",
                        subtitle = "While paused, nothing you play is saved, and nothing is sent to Last.fm or ListenBrainz.",
                        checked = paused,
                        onCheckedChange = viewModel::setPaused,
                    )
                }),
            )
            SettingsSectionLabel("Clear")
            SettingsGroupCard(
                rows = listOf<@Composable () -> Unit>(
                    {
                        ActionRow(
                            icon = Icons.Rounded.DateRange,
                            title = "Clear a date range",
                            subtitle = "Pick the first and last day",
                            enabled = counts.totalPlays > 0,
                            onClick = { pickingRange = true },
                        )
                    },
                    {
                        ActionRow(
                            icon = Icons.Rounded.DeleteForever,
                            title = "Clear everything",
                            subtitle = if (counts.totalPlays > 0) plays(counts.totalPlays) else "Nothing to clear",
                            enabled = counts.totalPlays > 0,
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { confirm = ClearRequest.Everything },
                        )
                    },
                ),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Clearing removes listens from this phone only. $CLEAR_KEEPS_SCROBBLES",
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
        val (title, n) = when (request) {
            ClearRequest.Everything -> "Clear your whole listening history?" to counts.totalPlays
            is ClearRequest.Range -> {
                val label = if (request.fromDay == request.toDay) {
                    longDate(request.fromDay)
                } else {
                    "${longDate(request.fromDay)} – ${longDate(request.toDay)}"
                }
                "Clear $label?" to counts.playsIn(request.fromDay, request.toDay)
            }
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = {
                Text(
                    (if (n > 0) "${plays(n).replaceFirstChar { it.uppercase() }} leave this phone's history, " +
                        "and play counts are worked out again without them. " else "There are no listens on those days. ") +
                        CLEAR_KEEPS_SCROBBLES,
                )
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
                ) { Text("Clear", color = if (n > 0) MaterialTheme.colorScheme.error else Color.Unspecified) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
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
                Text("Next")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(
            state = state,
            title = {
                Text(
                    "Days to clear",
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

private fun plays(n: Int): String = if (n == 1) "1 play" else "%,d plays".format(n)

private fun longDate(day: Long): String =
    LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
