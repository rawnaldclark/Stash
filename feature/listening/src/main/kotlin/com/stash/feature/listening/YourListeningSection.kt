package com.stash.feature.listening

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.stash.core.common.ArtUrlUpgrader
import com.stash.core.data.listening.ListeningRange
import com.stash.core.model.Track
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.core.ui.components.HomeRailCardSize
import com.stash.core.ui.components.HomeRailCoverShape
import com.stash.core.ui.components.motion.pressScale
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.listening.recap.RecapDoorCard
import com.stash.feature.listening.ui.ArtImage
import com.stash.feature.listening.ui.CompactCount
import com.stash.feature.listening.ui.ListenTimeText
import com.stash.feature.listening.ui.ListeningLayout
import com.stash.feature.listening.ui.NumberOverLabel
import com.stash.feature.listening.ui.SquareArt
import com.stash.feature.listening.ui.charts.ListeningBars
import com.stash.feature.listening.ui.weekBarColor
import com.stash.feature.listening.ui.weekBarDays

/**
 * Home's "Your listening" section (owner decision 2026-10-18: listening lives on Home only). The
 * app passes it into Home as a slot, like Community, so feature/home doesn't depend on this module.
 *
 * Shown only once there's a listen: until then it composes to nothing, so a new install has no
 * empty pills. Then: the header (opens Your listening), "Recently played" (tap plays the song with
 * the rest of the rail queued), the week's card (opens Your listening on 7 days), the Recap card
 * when one is offered, and two doors with their covers: Top songs and History ([ListeningDoor]).
 *
 * The look (owner 2026-11-01, "make the stats look like the rest of Home"): the week card stays
 * glass, so Discover and Your record remain Home's only cover-lit heroes, but its numbers are set
 * like the record's: a 36 sp time, plays · songs · streak each a number over its word, and the
 * week's bars across the whole card with each day's initial, in the album colour while a song
 * plays. Nothing in the section cuts a word: labels sit on their own lines and wrap, numbers are
 * kept short, and the doors go one under the other when a large font or a narrow screen would
 * squeeze them ([ListeningLayout.doorsStacked]).
 */
@Composable
fun YourListeningSection(
    onOpenHistory: () -> Unit,
    onOpenStats: (range: String?) -> Unit,
    onOpenTop: (kind: String, range: String) -> Unit,
    onOpenRecap: (kind: String, year: Int, month: Int) -> Unit = { _, _, _ -> },
    viewModel: YourListeningViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    val s = state ?: return
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))
        SectionTitle(
            title = stringResource(R.string.listening_title),
            onClickLabel = stringResource(R.string.listening_open),
            onClick = { onOpenStats(null) },
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = SidePadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(s.recent, key = { it.id }) { track ->
                RecentSongCard(track = track, onClick = { viewModel.playRecent(track) })
            }
        }
        Spacer(Modifier.height(14.dp))
        WeekCard(
            week = s.week,
            onClick = { onOpenStats(ListeningRange.Days7.key) },
            modifier = Modifier.padding(horizontal = SidePadding),
        )
        s.recap?.let { recap ->
            Spacer(Modifier.height(12.dp))
            RecapDoorCard(
                offer = recap,
                onClick = { onOpenRecap(recap.period.kind, recap.period.year, recap.period.monthArg) },
                modifier = Modifier.padding(horizontal = SidePadding),
            )
        }
        Spacer(Modifier.height(12.dp))
        val top = s.topSong
        val history = historySubtitle(historyLine(s))
        val last = s.recent.firstOrNull()
        ListeningDoors(
            doors = listOf(
                ListeningDoor(
                    label = stringResource(R.string.listening_door_top),
                    range = stringResource(R.string.listening_door_top_range),
                    title = top?.title ?: stringResource(R.string.listening_door_top_none),
                    // A song's name: two lines, then an ellipsis.
                    titleMaxLines = 2,
                    artPath = top?.albumArtPath,
                    artUrl = top?.albumArtUrl,
                    placeholder = Icons.Rounded.Leaderboard,
                    spoken = if (top != null) {
                        stringResource(R.string.listening_door_top_spoken, top.title)
                    } else {
                        stringResource(R.string.listening_door_top_none_spoken)
                    },
                    onClick = { onOpenTop("songs", ListeningRange.Days30.key) },
                ),
                ListeningDoor(
                    label = stringResource(R.string.listening_door_history),
                    range = null,
                    // "Last played 3 days ago": a phrase, never cut; it takes the lines it needs.
                    title = history,
                    titleMaxLines = Int.MAX_VALUE,
                    artPath = last?.albumArtPath,
                    artUrl = last?.albumArtUrl,
                    placeholder = Icons.Rounded.History,
                    spoken = stringResource(R.string.listening_door_history_spoken, history),
                    onClick = onOpenHistory,
                ),
            ),
        )
    }
}

/**
 * The section title, tappable as a whole (opens Your listening), with a chevron saying so. Same
 * type and gutter as [com.stash.core.ui.components.SectionHeader].
 */
@Composable
private fun SectionTitle(title: String, onClickLabel: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = onClickLabel, onClick = onClick)
            .padding(horizontal = SidePadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** One number on the week card: what shows ("142" over "plays") and what TalkBack says ("142 plays"). */
private data class WeekStat(val value: String, val label: String, val spoken: String)

/**
 * The week card's numbers: plays and songs, and the streak once it's two days or more (a 1-day
 * streak is just "you listened today"). Plays and songs are [CompactCount]ed, so even a heavy week
 * stays a short number.
 */
@Composable
private fun weekStats(week: WeekSummary): List<WeekStat> {
    val locale = LocalConfiguration.current.locales[0]
    val plays = pluralStringResource(R.plurals.listening_stat_plays, week.plays)
    val songs = pluralStringResource(R.plurals.listening_stat_songs, week.songs)
    val streak = pluralStringResource(R.plurals.listening_stat_streak, week.streak)
    return buildList {
        add(WeekStat(CompactCount.format(week.plays.toLong(), locale), plays, "${CompactCount.full(week.plays.toLong(), locale)} $plays"))
        add(WeekStat(CompactCount.format(week.songs.toLong(), locale), songs, "${CompactCount.full(week.songs.toLong(), locale)} $songs"))
        if (week.streak >= 2) {
            add(WeekStat(CompactCount.format(week.streak.toLong(), locale), streak, ListeningLabels.streak(week.streak)))
        }
    }
}

/**
 * The last 7 days at a glance, on the design system's glass: "Last 7 days", the time listened set
 * big (36 sp, [ListenTimeText]), then plays · songs · streak each as a number over its word (a
 * FlowRow: at a very large font the last one goes to the next line whole), then the week's bars
 * across the card with each day's initial under its bar ([WeekChart]). Opens Your listening on 7
 * days. TalkBack reads it as one sentence; nothing inside is read on its own.
 */
@Composable
private fun WeekCard(week: WeekSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = StashTheme.extendedColors
    val time = ListeningLabels.listenTime(week.listenedMs)
    val about = week.estimated && week.listenedMs > 0
    val stats = weekStats(week)
    val aboutWord = stringResource(R.string.listening_about)
    val nothing = stringResource(R.string.listening_week_nothing)
    val spoken = stringResource(
        R.string.listening_week_spoken,
        if (about) "$aboutWord $time" else time,
        if (week.plays == 0) nothing else stats.joinToString(", ") { it.spoken },
    )
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken },
        shape = MaterialTheme.shapes.large,
        color = colors.glassBackground,
        border = BorderStroke(1.dp, colors.glassBorder),
    ) {
        Column(Modifier.padding(16.dp).clearAndSetSemantics { }) {
            Text(
                text = stringResource(R.string.listening_week_eyebrow),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ListenTimeText(time = time, about = about, numberSize = WeekTimeSize)
            Spacer(Modifier.height(10.dp))
            if (week.plays == 0) {
                Text(
                    text = nothing,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    stats.forEach { stat ->
                        NumberOverLabel(
                            value = stat.value,
                            label = stat.label,
                            numberStyle = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            WeekChart(week = week)
        }
    }
}

/**
 * The week's bars across the whole card (56 dp), today's in the full colour and the other days a
 * step softer, each day's initial under its bar (today's in the strong ink). The colour is the
 * album's ([LocalAlbumChrome]'s accent, the one the mini player wears) while a song plays, and
 * the neutral ink otherwise ([weekBarColor] by `AlbumChrome.tint`: the app-wide fade the wordmark
 * and the tab bar follow, so the bars turn with them on every visit). The colour is read where
 * the bars are drawn, so a fade or a song change only redraws them. A time axis reads oldest to
 * newest left to right in every language, so the chart stays left-to-right.
 */
@Composable
private fun WeekChart(week: WeekSummary) {
    val locale = LocalConfiguration.current.locales[0]
    val days = remember(week.bars, week.today, locale) { weekBarDays(week.bars, week.today, locale) }
    val chrome = LocalAlbumChrome.current
    val neutral = MaterialTheme.colorScheme.onSurface
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.fillMaxWidth()) {
            ListeningBars(
                values = days.map { it.listenedMs },
                summary = "",
                highlight = days.indexOfFirst { it.isToday }.takeIf { it >= 0 },
                height = WeekBarsHeight,
                barColorAt = { weekBarColor(neutral, chrome.accent, chrome.tint) },
                gapFraction = 0.32f,
                maxBarWidth = 28.dp,
                minBarHeight = 10.dp,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                days.forEach { day ->
                    Text(
                        text = day.initial,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (day.isToday) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = if (day.isToday) MaterialTheme.colorScheme.onSurface else StashTheme.extendedColors.textTertiary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** What the History door's second line says ([historySubtitle] words it). */
internal sealed interface HistoryLine {
    /** History is paused in Settings. */
    data object Paused : HistoryLine

    /** [plays] listens today ("22 plays today"). */
    data class Today(val plays: Int) : HistoryLine

    /** Nothing today: when the last song was heard ([ago]: "3 days ago", [ListeningLabels.ago]). */
    data class LastPlayed(val ago: String) : HistoryLine
}

/** The History door's second line for [s]: paused, today's count, or when the last song was heard. */
internal fun historyLine(s: YourListeningState): HistoryLine = when {
    s.paused -> HistoryLine.Paused
    s.todayPlays > 0 -> HistoryLine.Today(s.todayPlays)
    else -> HistoryLine.LastPlayed(ListeningLabels.ago(s.lastPlayedAt, s.nowMs))
}

/** [line] in words, from the module's strings. */
@Composable
private fun historySubtitle(line: HistoryLine): String = when (line) {
    HistoryLine.Paused -> stringResource(R.string.listening_history_paused)
    is HistoryLine.Today -> pluralStringResource(R.plurals.listening_history_today, line.plays, line.plays)
    is HistoryLine.LastPlayed -> stringResource(R.string.listening_history_last_played, line.ago)
}

/** One door under the week card: a label ("Top songs"), its range ("30 days"), a line, a cover. */
internal data class ListeningDoor(
    /** What the door opens: "Top songs", "History". */
    val label: String,
    /** The range after the label ("Top songs · 30 days"), or null for none. */
    val range: String?,
    /** The line in the door's strong type: the #1 song's title, or "22 plays today". */
    val title: String,
    /** How many lines [title] may take: 2 for a song's name (then an ellipsis), unlimited for a phrase. */
    val titleMaxLines: Int,
    /** The cover: a file on the phone first, else the URL; neither shows [placeholder]. */
    val artPath: String?,
    val artUrl: String?,
    val placeholder: ImageVector,
    /** The door as TalkBack reads it. */
    val spoken: String,
    val onClick: () -> Unit,
)

/**
 * The doors: side by side, each half the row and both as tall as the taller, or, when that would
 * squeeze their words (a large font, a narrow screen or a large Display size:
 * [ListeningLayout.doorsStacked]), one under the other at the full width.
 */
@Composable
private fun ListeningDoors(doors: List<ListeningDoor>) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = SidePadding)) {
        if (ListeningLayout.doorsStacked(maxWidth, LocalDensity.current.fontScale)) {
            Column(verticalArrangement = Arrangement.spacedBy(ListeningLayout.DoorGap)) {
                doors.forEach { door -> DoorCard(door, stacked = true, modifier = Modifier.fillMaxWidth()) }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(ListeningLayout.DoorGap),
            ) {
                doors.forEach { door -> DoorCard(door, stacked = false, modifier = Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

/**
 * A door on the design system's glass (GlassCard's colours, border and shape), tappable, with the
 * cover of what it opens. Side by side, the label line runs across the top of the door and the
 * cover sits beside the title under it, so the label gets the door's whole width; stacked (a full-
 * width row), the cover sits at the start and the label and title beside it.
 */
@Composable
private fun DoorCard(door: ListeningDoor, stacked: Boolean, modifier: Modifier = Modifier) {
    val colors = StashTheme.extendedColors
    Surface(
        onClick = door.onClick,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = door.spoken },
        shape = MaterialTheme.shapes.large,
        color = colors.glassBackground,
        border = BorderStroke(1.dp, colors.glassBorder),
    ) {
        if (stacked) {
            Row(
                modifier = Modifier.padding(ListeningLayout.DoorPadding).clearAndSetSemantics { },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DoorCover(door, StackedCoverSize)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    DoorLabel(door)
                    DoorTitle(door)
                }
            }
        } else {
            Column(Modifier.padding(ListeningLayout.DoorPadding).clearAndSetSemantics { }) {
                DoorLabel(door)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DoorCover(door, TileCoverSize)
                    Spacer(Modifier.width(10.dp))
                    DoorTitle(door, Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * "Top songs · 30 days": the label in the secondary ink, the range a step quieter. Each half keeps
 * its words together, so when it has to wrap it breaks only after the dot, never inside
 * "Top songs" or "30 days", and never cuts.
 */
@Composable
private fun DoorLabel(door: ListeningDoor) {
    val tertiary = StashTheme.extendedColors.textTertiary
    val text = buildAnnotatedString {
        append(door.label.keepTogether())
        door.range?.let { range ->
            withStyle(SpanStyle(color = tertiary)) {
                append("\u00A0· ")
                append(range.keepTogether())
            }
        }
    }
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The door's strong line: the song's title (two lines, then an ellipsis) or a phrase (all of it). */
@Composable
private fun DoorTitle(door: ListeningDoor, modifier: Modifier = Modifier) {
    Text(
        text = door.title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = door.titleMaxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The door's cover, rounded like the stats screens' song art; the door's glyph until there is one. */
@Composable
private fun DoorCover(door: ListeningDoor, size: Dp) {
    ArtImage(path = door.artPath, url = door.artUrl, size = size, shape = SquareArt, placeholder = door.placeholder)
}

/** [this] with its spaces made non-breaking, so a line never breaks inside it. */
private fun String.keepTogether(): String = replace(' ', '\u00A0')

/** A song on the "Recently played" rail: the Home rail card (its cover, title, artist). */
@Composable
private fun RecentSongCard(track: Track, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = StashTheme.extendedColors
    Column(
        modifier = Modifier
            .width(HomeRailCardSize)
            .pressScale(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(HomeRailCardSize)
                .clip(HomeRailCoverShape)
                .background(colors.elevatedSurface)
                // The Home rail cards' hairline ring: a pale cover keeps its edge on the light theme.
                .border(1.dp, colors.glassBorder, HomeRailCoverShape),
            contentAlignment = Alignment.Center,
        ) {
            val art = track.albumArtPath ?: ArtUrlUpgrader.upgrade(track.albumArtUrl)
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = track.artist,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val SidePadding = 20.dp

/** The week card's time: the record card's hours are 32 sp; this is the section's one big number. */
private val WeekTimeSize = 36.sp

/** The week card's bars, across the card. */
private val WeekBarsHeight = 56.dp

/** A door's cover side by side (beside the title) and stacked (beside label and title). */
private val TileCoverSize = 40.dp
private val StackedCoverSize = 48.dp
