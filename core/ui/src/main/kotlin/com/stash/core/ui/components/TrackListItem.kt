package com.stash.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.DownloadDone  
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.core.ui.theme.StashTheme

/**
 * A reusable track row used in both the Library and Home screens.
 *
 * Displays a 48 dp album-art placeholder, title + artist column,
 * formatted duration, source indicator dot, and an overflow menu icon.
 *
 * When the row is crowded (a Hi-Res pill plus two source labels on a narrow
 * phone or at a large display size) the decoration steps aside so the title
 * keeps a fair share: labels collapse to dots, then the pill drops its
 * qualifier, then the pill hides. See [decideTrackRowFit]. TalkBack still hears
 * the full source names and pill text. Wrap a list of these rows in
 * [TrackRowFitScope] so they share one width reading and one text measurer.
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
 * @param selectionActive When true, a leading checkbox is shown; the row's [onClick]
 *                    toggles selection (handled by the caller).
 * @param selected    Whether this row is currently selected (drives the checkbox).
 * @param trailingText Replaces the duration on the right when set (History shows the time a
 *                    song was heard, "9:41 PM"). Null, the default, shows the duration.
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
    isResolving: Boolean = false,
    selectionActive: Boolean = false,
    selected: Boolean = false,
    showAvailabilityTag: Boolean = false,
    trailingText: String? = null,
) {
    val hasMoreButton = !selectionActive && onMoreClick != null
    // Inside a TrackRowFitScope the list shares one width reading and one text measurer; on its own
    // the row reads its width with BoxWithConstraints. Either way the optional decoration (source
    // labels, Hi-Res qualifier) steps aside before it squeezes the title to "…". See [decideTrackRowFit].
    val environment = LocalTrackRowFitEnvironment.current
    if (environment != null) {
        val fit = rememberTrackListItemFit(
            track, environment.rowWidth, environment.measurer, isPlaying, isResolving, selectionActive, hasMoreButton,
            trailingText,
        )
        TrackListItemRow(
            track, onClick, modifier, isPlaying, onMoreClick, onLongPress, isResolving, selectionActive, selected, fit,
            showAvailabilityTag = showAvailabilityTag,
            trailingText = trailingText,
        )
    } else {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            val fit = rememberTrackListItemFit(
                track, maxWidth, rememberTextMeasurer(), isPlaying, isResolving, selectionActive, hasMoreButton,
                trailingText,
            )
            TrackListItemRow(
                track, onClick, Modifier, isPlaying, onMoreClick, onLongPress, isResolving, selectionActive, selected, fit,
                showAvailabilityTag = showAvailabilityTag,
                trailingText = trailingText,
            )
        }
    }
}

/** The row itself, laid out with the decoration [fit] allows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackListItemRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier,
    isPlaying: Boolean,
    onMoreClick: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
    isResolving: Boolean,
    selectionActive: Boolean,
    selected: Boolean,
    fit: TrackRowFit,
    showAvailabilityTag: Boolean = false,
    trailingText: String? = null,
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
                onLongClickLabel = selectLongClickLabel(selectionActive, onLongPress),
                onLongClick = onLongPress,
            )
            .selectionRowSemantics(selectionActive, selected)
            .padding(horizontal = RowHorizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // -- Leading selection checkbox (only while selecting) --
        AnimatedVisibility(visible = selectionActive) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                )
                Spacer(modifier = Modifier.width(SmallGap))
            }
        }

        // -- Album art (48 dp square, rounded corners) --
        val artUrl = track.albumArtPath ?: track.albumArtUrl
        Box(
            modifier = Modifier
                .size(ArtSize)
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

        Spacer(modifier = Modifier.width(ArtGap))

        // -- Title + artist column (takes available space) --
        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    // A hidden pill keeps its words for TalkBack ("FLAC 24/44"), so a crowded row reads
                    // the same as a roomy one.
                    if (fit.badge == BadgeFit.HIDDEN && isLossless(track.fileFormat)) {
                        Modifier.semantics {
                            contentDescription =
                                flacBadgeText(track.fileFormat, track.bitsPerSample, track.sampleRateHz)
                        }
                    } else {
                        Modifier
                    },
                ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BadgeGap),
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isPlaying) primaryColor else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (fit.badge != BadgeFit.HIDDEN) {
                    FlacBadge(
                        fileFormat = track.fileFormat,
                        bitsPerSample = track.bitsPerSample,
                        sampleRateHz = track.sampleRateHz,
                        compact = fit.badge == BadgeFit.COMPACT,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (showAvailabilityTag) AvailabilityTag(track.isDownloaded)
            }
        }

        Spacer(modifier = Modifier.width(SmallGap))

        // -- Resolving spinner / now-playing indicator / duration --
        when {
            isResolving -> CircularProgressIndicator(
                modifier = Modifier.size(ResolvingSize),
                color = primaryColor,
                strokeWidth = 2.dp,
            )
            isPlaying -> Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = "Now playing",
                tint = primaryColor,
                modifier = Modifier.size(PlayingIconSize),
            )
            else -> Text(
                text = trailingText ?: formatDuration(track.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = extendedColors.textTertiary,
            )
        }

        Spacer(modifier = Modifier.width(SmallGap))

        // -- Source indicator dot + label (dots only when space is tight) --
        SourceIndicator(source = track.source, showLabel = fit.showSourceLabels)

        // -- Overflow menu (only when not selecting) --
        if (!selectionActive && onMoreClick != null) {
            IconButton(onClick = onMoreClick, modifier = Modifier.size(MoreButtonSize)) {
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

@Composable
private fun AvailabilityTag(downloaded: Boolean) {
    if (!downloaded) return
    Icon(
        imageVector = Icons.Default.DownloadDone,
        contentDescription = "Downloaded",
        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
        modifier = Modifier.size(14.dp),
    )
}

// ── Previews ───────────────────────────────────────────────────────────────

@Preview(name = "Normal", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun PreviewTrackListItemNormal() {
    StashTheme {
        TrackListItem(
            track = previewTrack(),
            onClick = {},
        )
    }
}

@Preview(name = "Playing", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun PreviewTrackListItemPlaying() {
    StashTheme {
        TrackListItem(
            track = previewTrack(),
            onClick = {},
            isPlaying = true,
        )
    }
}

private fun previewTrack(
    isDownloaded: Boolean = true,
): Track = Track(
    id = 1L,
    title = "Wandering Star",
    artist = "Portishead",
    album = "Dummy",
    durationMs = 287_000L,
    source = MusicSource.SPOTIFY,
    isDownloaded = isDownloaded,
)

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
