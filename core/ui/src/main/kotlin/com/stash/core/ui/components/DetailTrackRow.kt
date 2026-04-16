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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.model.Track
import com.stash.core.ui.theme.StashTheme
import com.stash.core.ui.util.formatDuration

/**
 * A single track row used in detail screens (playlist detail, liked songs, etc.).
 *
 * Shows track number, album art thumbnail, title/artist, and formatted duration.
 * Tapping plays the track; long-pressing triggers the [onLongPress] callback.
 *
 * @param track             The track data to display.
 * @param trackNumber       The 1-based position in the list.
 * @param isPlaying         True if this track is currently playing (highlights the row).
 * @param onClick           Invoked on tap to start playback from this track.
 * @param onLongPress       Invoked on long-press to open the options sheet.
 * @param showArtist        When true (default), the artist name is shown as the subtitle.
 *                          Set to false to suppress the subtitle entirely (e.g. AlbumDetailScreen).
 * @param subtitleOverride  When non-null, replaces the artist name with this string.
 *                          A blank override is treated as absent — no subtitle is rendered.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailTrackRow(
    track: Track,
    trackNumber: Int,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    showArtist: Boolean = true,
    subtitleOverride: String? = null,
) {
    val extendedColors = StashTheme.extendedColors
    val primaryColor = MaterialTheme.colorScheme.primary

    // Subtle background highlight for the currently-playing track.
    val rowBackground = if (isPlaying) {
        primaryColor.copy(alpha = 0.06f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // -- Track number --
        Text(
            text = "$trackNumber",
            style = MaterialTheme.typography.bodySmall,
            color = if (isPlaying) primaryColor else extendedColors.textTertiary,
            modifier = Modifier.width(28.dp),
        )

        // -- Album art thumbnail (48dp, 8dp rounded corners) --
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

        // -- Title + artist stacked vertically --
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isPlaying) primaryColor else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Determine subtitle text — default is artist, can be overridden or hidden
            val subtitle = subtitleOverride ?: track.artist
            if ((showArtist || subtitleOverride != null) && subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // -- Duration --
        Text(
            text = formatDuration(track.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = extendedColors.textTertiary,
        )
    }
}
