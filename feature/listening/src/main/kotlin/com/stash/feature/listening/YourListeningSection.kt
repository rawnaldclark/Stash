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
import androidx.compose.material.icons.rounded.History
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
import com.stash.core.ui.components.SectionHeader
import com.stash.core.ui.components.motion.pressScale
import com.stash.core.ui.theme.StashTheme

/**
 * Home's "Your listening" section (owner decision 2026-10-18: listening lives on Home only). The
 * app passes it into Home as a slot, like Community, so feature/home doesn't depend on this module.
 *
 * Shown only once there's a listen: until then it composes to nothing, so a new install has no
 * empty pills. Then: "Recently played" (tap plays the song with the rest of the rail queued) and
 * a row of doors into the listening screens. Today the only door is History; Top songs, Stats and
 * Your Year join it as their screens land ([ListeningDoor]), each half the row wide, so adding one
 * never resizes the others.
 */
@Composable
fun YourListeningSection(
    onOpenHistory: () -> Unit,
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
        SectionHeader(title = "Your listening")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(s.recent, key = { it.id }) { track ->
                RecentSongCard(track = track, onClick = { viewModel.playRecent(track) })
            }
        }
        Spacer(Modifier.height(14.dp))
        ListeningDoors(
            doors = listOf(
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
private val SidePadding = 20.dp
private val DoorGap = 12.dp
