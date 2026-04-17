package com.stash.feature.search

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.ui.theme.StashTheme

/**
 * Canonical "preview + download" search/Popular row.
 *
 * Extracted from the previous `SearchResultRow` in `SearchScreen.kt` so that
 * both [ResultsList] (Search tab) and [PopularTracksSection] (Artist Profile)
 * render the SAME composable — no fork, no divergence. The outer Row is
 * tagged `"PreviewDownloadRow"` so a Compose UI test can lock the non-fork.
 *
 * The download button cycles through three visual states:
 *  - Default: download arrow icon (tappable)
 *  - Downloading: circular progress indicator
 *  - Downloaded: green checkmark icon
 */
@Composable
fun PreviewDownloadRow(
    item: SearchResultItem,
    isDownloading: Boolean,
    isDownloaded: Boolean,
    isPreviewLoading: Boolean,
    isPreviewPlaying: Boolean,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("PreviewDownloadRow")
            .clip(RoundedCornerShape(12.dp))
            .background(extendedColors.glassBackground)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Album art or fallback music note
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(extendedColors.elevatedSurface),
            contentAlignment = Alignment.Center,
        ) {
            if (item.thumbnailUrl != null) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title and artist column -- takes up remaining space
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Duration label
        Text(
            text = formatDuration(item.durationSeconds),
            style = MaterialTheme.typography.bodySmall,
            color = extendedColors.textTertiary,
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Preview button
        IconButton(
            onClick = if (isPreviewPlaying) onStopPreview else onPreview,
            modifier = Modifier.size(40.dp),
        ) {
            when {
                isPreviewLoading -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                isPreviewPlaying -> Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop preview",
                    tint = MaterialTheme.colorScheme.primary,
                )
                else -> Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Preview",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Download action button
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isDownloaded -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Downloaded",
                        modifier = Modifier.size(24.dp),
                        tint = extendedColors.success,
                    )
                }
                isDownloading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                else -> {
                    IconButton(onClick = onDownload) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Formats a duration in seconds to "m:ss" or "h:mm:ss" display string.
 *
 * Internal to the search package so both [PreviewDownloadRow] and any
 * callers that surface a row can share a single formatter.
 */
internal fun formatDuration(seconds: Double): String {
    val totalSeconds = seconds.toLong()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}
