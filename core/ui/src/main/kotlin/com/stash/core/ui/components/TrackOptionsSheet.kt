package com.stash.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.model.Track
import com.stash.core.ui.theme.StashTheme

/**
 * Bottom sheet content showing queue actions for a selected track.
 *
 * Every action row except Play Next and Save to Playlist is optional: pass null
 * to hide it, so each surface shows only what makes sense there (e.g. the play
 * queue omits Add to Queue and Delete — a track is already queued, and swipe
 * removes it — while library rows keep both).
 *
 * @param track               The track the user long-pressed.
 * @param onPlayNext          Inserts the track after the currently-playing track.
 * @param onSaveToPlaylist    Opens the save-to-playlist flow.
 * @param onAddToQueue        Appends the track to the end of the queue. Null hides.
 * @param onStartRadio        Starts a song radio seeded from this track. Null hides.
 * @param onShare             Opens the share-links sheet. Null hides.
 * @param onDownload          Queue this streaming-only track for download. Null hides.
 * @param onRemoveDownload    Remove the on-disk file but keep the row (track
 *                            stays streamable). Null hides.
 * @param onDelete            Deletes the track from the device. Null hides.
 */
@Composable
fun TrackOptionsSheet(
    track: Track,
    onPlayNext: (Track) -> Unit,
    onSaveToPlaylist: (Track) -> Unit,
    onAddToQueue: ((Track) -> Unit)? = null,
    onStartRadio: ((Track) -> Unit)? = null,
    onShare: ((Track) -> Unit)? = null,
    onViewAlbum: ((Track) -> Unit)? = null,
    onDownload: ((Track) -> Unit)? = null,
    onRemoveDownload: ((Track) -> Unit)? = null,
    onDelete: ((Track) -> Unit)? = null,
) {
    val extendedColors = StashTheme.extendedColors
    val role = LocalListenTogetherRole.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
    ) {
        // -- Track info header --
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val artUrl = track.albumArtPath ?: track.albumArtUrl
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(extendedColors.glassBackground),
                contentAlignment = Alignment.Center,
            ) {
                if (artUrl != null) {
                    AsyncImage(
                        model = artUrl,
                        contentDescription = null,
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

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
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
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp),
            thickness = 0.5.dp,
            color = extendedColors.glassBorder,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // -- Play Next option --
        SheetOptionRow(
            icon = Icons.Default.PlaylistPlay,
            label = playNextLabel(role, "Play Next"),
            onClick = { onPlayNext(track) },
        )

        // -- Add to Queue option --
        if (onAddToQueue != null && role == ListenTogetherRole.NONE) {
            SheetOptionRow(
                icon = Icons.Default.PlaylistAdd,
                label = "Add to Queue",
                onClick = { onAddToQueue(track) },
            )
        }

        // -- Start Radio option --
        if (onStartRadio != null && role == ListenTogetherRole.NONE) {
            SheetOptionRow(
                icon = Icons.Default.Radio,
                label = "Start radio",
                onClick = { onStartRadio(track) },
            )
        }

        // -- Save to Playlist option --
        SheetOptionRow(
            icon = Icons.Default.FavoriteBorder,
            label = "Save to Playlist",
            onClick = { onSaveToPlaylist(track) },
        )

        // -- Download / Remove Download option --
        // The two states are mutually exclusive: a track is either on disk
        // (offer Remove) or it's not (offer Download). Both callbacks are
        // optional so screens that don't wire them (e.g. Library detail
        // under Path A) can simply leave them null and the rows vanish.
        if (track.isDownloaded && onRemoveDownload != null) {
            SheetOptionRow(
                icon = Icons.Default.DownloadDone,
                label = "Remove download",
                onClick = { onRemoveDownload(track) },
            )
        } else if (!track.isDownloaded && onDownload != null) {
            SheetOptionRow(
                icon = Icons.Default.Download,
                label = "Download",
                onClick = { onDownload(track) },
            )
        }

        // -- Share option --
        if (onShare != null) {
            SheetOptionRow(
                icon = Icons.Default.Share,
                label = "Share",
                onClick = { onShare(track) },
            )
        }

        // -- View Album option --
        if (onViewAlbum != null) {
            SheetOptionRow(
                icon = Icons.Default.Album,
                label = "View Album",
                onClick = { onViewAlbum(track) },
            )
        }

        // -- Delete option --
        if (onDelete != null) {
            Spacer(modifier = Modifier.height(4.dp))
            SheetOptionRow(
                icon = Icons.Default.Delete,
                label = "Delete",
                tint = MaterialTheme.colorScheme.error,
                onClick = { onDelete(track) },
            )
        }
    }
}

/**
 * A single tappable row in the bottom sheet options list.
 *
 * @param icon    Leading icon for the option.
 * @param label   Text label for the option.
 * @param onClick Invoked when the row is tapped.
 * @param tint    Color applied to the icon and label text.
 */
@Composable
fun SheetOptionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
    }
}
