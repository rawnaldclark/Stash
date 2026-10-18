package com.stash.feature.listening.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stash.core.data.db.dao.DayCount
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.listening.ListeningLabels
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Jump to a day: a month pager from the first listen to this month, each day with listens filled
 * in the brand purple, deeper the more was heard (four steps, by quartile of the user's own days,
 * so a light listener's busy day still reads as busy). Days without listens can't be picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListeningCalendarSheet(
    dayCounts: Map<Long, DayCount>,
    firstDay: Long,
    today: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = Locale.getDefault()
    val months = remember(firstDay, today) { monthsBetween(firstDay, today) }
    val thresholds = remember(dayCounts) { calendarThresholds(dayCounts.values.map { it.plays }) }
    val pager = rememberPagerState(initialPage = months.lastIndex) { months.size }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = StashTheme.extendedColors

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.elevatedSurface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            val month = months[pager.currentPage]
            val monthPlays = remember(month, dayCounts) {
                (1..month.lengthOfMonth()).sumOf { dayCounts[month.atDay(it).toEpochDay()]?.plays ?: 0 }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                    enabled = pager.currentPage > 0,
                ) { Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous month") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (monthPlays == 0) "No listens" else ListeningLabels.plays(monthPlays),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    enabled = pager.currentPage < months.lastIndex,
                ) { Icon(Icons.Rounded.ChevronRight, contentDescription = "Next month") }
            }
            Spacer(Modifier.height(12.dp))
            val firstDow = WeekFields.of(locale).firstDayOfWeek
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { i ->
                    Text(
                        text = firstDow.plus(i.toLong()).getDisplayName(TextStyle.NARROW, locale),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { page ->
                MonthGrid(
                    month = months[page],
                    firstDow = firstDow,
                    dayCounts = dayCounts,
                    thresholds = thresholds,
                    today = today,
                    onPick = onPick,
                )
            }
            Spacer(Modifier.height(12.dp))
            Legend()
        }
    }
}

/** Six weeks of [month] (always six rows, so the sheet doesn't change height between months). */
@Composable
private fun MonthGrid(
    month: YearMonth,
    firstDow: java.time.DayOfWeek,
    dayCounts: Map<Long, DayCount>,
    thresholds: IntArray,
    today: Long,
    onPick: (Long) -> Unit,
) {
    val lead = (month.atDay(1).dayOfWeek.value - firstDow.value + 7) % 7
    Column(Modifier.fillMaxWidth()) {
        for (week in 0 until 6) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val dayOfMonth = week * 7 + col - lead + 1
                    Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (dayOfMonth in 1..month.lengthOfMonth()) {
                            val date = month.atDay(dayOfMonth)
                            val epochDay = date.toEpochDay()
                            DayCell(
                                date = date,
                                plays = dayCounts[epochDay]?.plays ?: 0,
                                level = calendarLevel(dayCounts[epochDay]?.plays ?: 0, thresholds),
                                isToday = epochDay == today,
                                onClick = { onPick(epochDay) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, plays: Int, level: Int, isToday: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val fill = if (level == 0) Color.Transparent else primary.copy(alpha = LEVEL_ALPHA[level])
    val ink = when {
        level >= 3 -> MaterialTheme.colorScheme.onPrimary
        level > 0 -> MaterialTheme.colorScheme.onSurface
        else -> StashTheme.extendedColors.textTertiary
    }
    val label = "${date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))}, " +
        if (plays == 0) "no listens" else ListeningLabels.plays(plays)
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(fill)
            .then(if (isToday) Modifier.border(BorderStroke(1.5.dp, primary), CircleShape) else Modifier)
            .clickable(enabled = plays > 0, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (level > 0) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = ink,
        )
    }
}

/** "Less ▪▪▪▪ More": what the fill means. */
@Composable
private fun Legend() {
    val primary = MaterialTheme.colorScheme.primary
    val colors = StashTheme.extendedColors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Tap a day to jump to it", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
        Spacer(Modifier.weight(1f))
        Text("Less", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
        Spacer(Modifier.width(6.dp))
        for (level in 1..4) {
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .size(12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(primary.copy(alpha = LEVEL_ALPHA[level])),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text("More", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
    }
}

/** Fill strength per level (0 = no listens). */
private val LEVEL_ALPHA = floatArrayOf(0f, 0.22f, 0.42f, 0.70f, 1f)

/** Every month from [firstDay]'s to [today]'s, oldest first (at least one). */
internal fun monthsBetween(firstDay: Long, today: Long): List<YearMonth> {
    val last = YearMonth.from(LocalDate.ofEpochDay(today))
    var m = YearMonth.from(LocalDate.ofEpochDay(minOf(firstDay, today)))
    val out = ArrayList<YearMonth>()
    while (m <= last) {
        out += m
        m = m.plusMonths(1)
    }
    return out
}

/** The quartile cut points of the days' listen counts (each day with a listen counted once). */
internal fun calendarThresholds(plays: List<Int>): IntArray {
    val sorted = plays.filter { it > 0 }.sorted()
    if (sorted.isEmpty()) return intArrayOf(0, 0, 0)
    fun q(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
    return intArrayOf(q(0.25), q(0.5), q(0.75))
}

/** 0 for a day without listens, else 1..4: one more for each quartile cut point it's above. */
internal fun calendarLevel(plays: Int, thresholds: IntArray): Int =
    if (plays <= 0) 0 else 1 + thresholds.count { plays > it }
