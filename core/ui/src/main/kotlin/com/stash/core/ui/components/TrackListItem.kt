package com.stash.core.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.model.Track
import com.stash.core.ui.theme.StashTheme

/**
 * A reusable track row used in both the Library and Home screens.
 *
 * Displays a 48 dp album-art placeholder, title + artist column,
 * formatted duration, source indicator dot, and an overflow menu icon.
 *
 * @param track       The [Track] to display.
 * @param onClick     Callback invoked when the row is tapped.
 * @param modifier    Optional [Modifier] applied to the root row.
 * @param isPlaying   Whether this track is the currently-playing track.
 *                    When true, the title is tinted with the primary color,
 *                    a "now playing" equalizer icon replaces the duration,
 *                    and the row background gets a subtle primary highlight.
 * @param onMoreClick Optional callback for the overflow (three-dot) button.
 * @param onLongPress Optional callback invoked when the row is long-pressed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackListItem(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    onMoreClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val extendedColors = StashTheme.extendedColors
    val primaryColor = MaterialTheme.colorScheme.primary

    // Subtle background highlight when this track is currently playing.
    val rowBackground = if (isPlaying) {
        primaryColor.copy(alpha = 0.06f)
    } else {
        androidx.compose.ui.graphics.Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowBackground)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // -- Album art (48 dp square, rounded corners) --
        val artUrl = track.albumArtPath ?: track.albumArtUrl
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(extendedColors.elevatedSurface),
            contentAlignment = Alignment.Center,
        ) {
            if (artUrl != null) {
                AsyncImage(
                    model = artUrl,
                    contentDescription = "${track.title} album art",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = extendedColors.textTertiary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // -- Title + artist column (takes available space) --
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isPlaying) primaryColor else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // -- Duration or now-playing indicator --
        if (isPlaying) {
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = "Now playing",
                tint = primaryColor,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text(
                text = formatDuration(track.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = extendedColors.textTertiary,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // -- Source indicator dot + label --
        SourceIndicator(source = track.source, showLabel = true)

        // -- Overflow menu --
        if (onMoreClick != null) {
            IconButton(onClick = onMoreClick, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = extendedColors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Formats a duration in milliseconds to a human-readable "m:ss" or "h:mm:ss" string.
 */
fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
