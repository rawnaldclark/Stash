package com.stash.core.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import androidx.compose.ui.res.stringResource
import com.stash.core.ui.R
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
 * @param selectionActive   When true, a leading checkbox is shown; the row's [onClick]
 *                          toggles selection (handled by the caller).
 * @param selected          Whether this row is currently selected (drives the checkbox).
 * @param onMoreClick       Optional callback for a trailing overflow (three-dot) button,
 *                          shown only when selection is inactive.
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
    isResolving: Boolean = false,
    selectionActive: Boolean = false,
    selected: Boolean = false,
    onMoreClick: (() -> Unit)? = null,
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

        // -- Leading selection checkbox (only while selecting) --
        AnimatedVisibility(visible = selectionActive) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
        }

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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isPlaying) primaryColor else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                FlacBadge(
                    fileFormat = track.fileFormat,
                    bitsPerSample = track.bitsPerSample,
                    sampleRateHz = track.sampleRateHz,
                )
            }
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

        // -- Duration / resolving spinner --
        when {
            isResolving -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
            else -> Text(
                text = formatDuration(track.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = extendedColors.textTertiary,
            )
        }

        // -- Overflow menu (only when not selecting) --
        if (!selectionActive && onMoreClick != null) {
            IconButton(onClick = onMoreClick, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.cd_more_options),
                    tint = extendedColors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ── Previews ───────────────────────────────────────────────────────────────

@Preview(name = "Normal", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun PreviewDetailTrackRowNormal() {
    StashTheme {
        DetailTrackRow(
            track = previewDetailTrack(),
            trackNumber = 1,
            isPlaying = false,
            onClick = {},
            onLongPress = {},
        )
    }
}

@Preview(name = "Playing", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun PreviewDetailTrackRowPlaying() {
    StashTheme {
        DetailTrackRow(
            track = previewDetailTrack(),
            trackNumber = 3,
            isPlaying = true,
            onClick = {},
            onLongPress = {},
        )
    }
}

private fun previewDetailTrack(
    isDownloaded: Boolean = true,
): Track = Track(
    id = 1L,
    title = "Glory Box",
    artist = "Portishead",
    album = "Dummy",
    durationMs = 308_000L,
    source = MusicSource.SPOTIFY,
    isDownloaded = isDownloaded,
)
