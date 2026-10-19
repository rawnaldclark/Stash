package com.stash.feature.listening

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.stash.core.common.ArtUrlUpgrader
import com.stash.core.model.Track
import com.stash.core.data.listening.ListeningRange
import com.stash.feature.listening.recap.RecapDoorCard
import com.stash.feature.listening.ui.charts.ListeningBars
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.stash.core.ui.components.motion.pressScale
import com.stash.core.ui.theme.StashTheme

/**
 * Home's "Your listening" section (owner decision 2026-10-18: listening lives on Home only). The
 * app passes it into Home as a slot, like Community, so feature/home doesn't depend on this module.
 *
 * Shown only once there's a listen: until then it composes to nothing, so a new install has no
 * empty pills. Then: the header (opens Your listening), "Recently played" (tap plays the song with
 * the rest of the rail queued), the week's summary card (opens Your listening on 7 days) and a row
 * of doors: Top songs and History ([ListeningDoor]), each half the row wide, so a later door
 * (the monthly Recap) never resizes the others.
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
        SectionTitle(title = "Your listening", onClick = { onOpenStats(null) })
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
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
        ListeningDoors(
            doors = listOf(
                ListeningDoor(
                    icon = Icons.Rounded.Leaderboard,
                    title = "Top songs",
                    // Its range said out loud: the Recap card above counts a calendar month.
                    subtitle = s.topSongTitle?.let { "#1 $it · 30 days" } ?: "Last 30 days",
                    onClick = { onOpenTop("songs", ListeningRange.Days30.key) },
                ),
                ListeningDoor(
                    icon = Icons.Rounded.History,
                    title = "History",
                    subtitle = historySubtitle(s),
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
private fun SectionTitle(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open $title", onClick = onClick)
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

/** The 7-day card's line under the time: "142 plays · 5-day streak". */
internal fun weekLine(week: WeekSummary): String = when {
    week.plays == 0 -> "Nothing in the last 7 days"
    week.streak >= 2 -> "${ListeningLabels.plays(week.plays)} · ${ListeningLabels.streak(week.streak)}"
    else -> ListeningLabels.plays(week.plays)
}

/**
 * The last 7 days at a glance: "Last 7 days", the time (Space Grotesk), plays and streak, and a bar per
 * day with today in the full accent. Opens Your listening on 7 days.
 */
@Composable
private fun WeekCard(week: WeekSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = StashTheme.extendedColors
    val time = ListeningLabels.listenTime(week.listenedMs)
    val spoken = "Last 7 days, ${if (week.estimated) "about " else ""}$time listened, ${weekLine(week)}. Opens your listening."
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken },
        shape = MaterialTheme.shapes.large,
        color = colors.glassBackground,
        border = BorderStroke(1.dp, colors.glassBorder),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Last 7 days",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    if (week.estimated && week.listenedMs > 0) {
                        Text(
                            text = "about ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    Text(
                        text = time,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
                Text(
                    text = weekLine(week),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(16.dp))
            ListeningBars(
                values = week.bars,
                summary = "",
                highlight = week.bars.lastIndex,
                height = 48.dp,
                gapFraction = 0.36f,
                minBarHeight = 14.dp,
                modifier = Modifier.width(WeekBarsWidth),
            )
        }
    }
}

/** The History door's second line: paused, today's count, or when the last song was heard. */
internal fun historySubtitle(s: YourListeningState): String = when {
    s.paused -> "Paused"
    s.todayPlays > 0 -> "${ListeningLabels.plays(s.todayPlays)} today"
    else -> "Last played ${ListeningLabels.ago(s.lastPlayedAt, s.nowMs)}"
}

/** One door on the section's card row. */
internal data class ListeningDoor(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
)

/**
 * The doors, each exactly half the row (two per screen, the rest scroll in), so the row never
 * reflows when a screen gains its door.
 */
@Composable
private fun ListeningDoors(doors: List<ListeningDoor>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width: Dp = (maxWidth - SidePadding * 2 - DoorGap) / 2
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = SidePadding),
            horizontalArrangement = Arrangement.spacedBy(DoorGap),
        ) {
            doors.forEach { door -> DoorCard(door, Modifier.width(width)) }
        }
    }
}

/** A door: the design system's glass card (GlassCard's colours, border and shape), tappable. */
@Composable
private fun DoorCard(door: ListeningDoor, modifier: Modifier = Modifier) {
    val colors = StashTheme.extendedColors
    Surface(
        onClick = door.onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = colors.glassBackground,
        border = BorderStroke(1.dp, colors.glassBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = door.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = door.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = door.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A song on the "Recently played" rail: the Home rail card (140 dp cover, title, artist). */
@Composable
private fun RecentSongCard(track: Track, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = StashTheme.extendedColors
    Column(
        modifier = Modifier
            .width(RailCardSize)
            .pressScale(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(RailCardSize)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.elevatedSurface),
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

private val RailCardSize = 140.dp
private val WeekBarsWidth = 92.dp
private val SidePadding = 20.dp
private val DoorGap = 12.dp
