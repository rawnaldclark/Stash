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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * qualifier, then the pill hides. See [decideTrackRowFit].
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
) {
    val extendedColors = StashTheme.extendedColors
    val primaryColor = MaterialTheme.colorScheme.primary

    // Subtle background highlight when this track is currently playing.
    val rowBackground = if (isPlaying) {
        primaryColor.copy(alpha = 0.06f)
    } else {
        androidx.compose.ui.graphics.Color.Transparent
    }

    // BoxWithConstraints gives the row's real width so the optional
    // decoration (source labels, Hi-Res qualifier) can step aside before it
    // squeezes the title to "…". See [decideTrackRowFit].
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val fit = rememberTrackListItemFit(
            track = track,
            rowWidth = maxWidth,
            isPlaying = isPlaying,
            isResolving = isResolving,
            selectionActive = selectionActive,
            hasMoreButton = !selectionActive && onMoreClick != null,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowBackground)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongPress,
                )
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
                modifier = Modifier.weight(1f),
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
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                    text = formatDuration(track.durationMs),
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
}

// ── Space sharing ──────────────────────────────────────────────────────────
//
// The row's fixed sizes, shared by the layout above and the arithmetic below so
// the two can't drift apart.
private val RowHorizontalPadding = 20.dp
private val ArtSize = 48.dp
private val ArtGap = 12.dp
private val SmallGap = 8.dp
private val BadgeGap = 4.dp
private val MoreButtonSize = 32.dp
private val ResolvingSize = 20.dp
private val PlayingIconSize = 18.dp
private val SourceDotSize = 6.dp

/** The pill's horizontal text padding in [FlacBadge] (6 dp each side). */
private val BadgeHorizontalPadding = 12.dp

/**
 * Width of the selection checkbox. Material 3 draws a 20 dp box with 2 dp
 * padding, and adds no 48 dp touch target because `onCheckedChange` is null.
 */
private val CheckboxWidth = 24.dp

/**
 * The fair minimum share of the row the title is guaranteed, at 1.0 font
 * scale. About ten characters of body text: enough to recognise a song.
 * Scaled by the user's font scale so large text keeps the same characters.
 */
private val TitleMinShare = 80.dp

/**
 * Measures the row's text at the current density and font scale and asks
 * [decideTrackRowFit] what fits. Remembered per row on the inputs that change
 * the answer, so scrolling doesn't re-measure.
 */
@Composable
private fun rememberTrackListItemFit(
    track: Track,
    rowWidth: Dp,
    isPlaying: Boolean,
    isResolving: Boolean,
    selectionActive: Boolean,
    hasMoreButton: Boolean,
): TrackRowFit {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val titleStyle = typography.bodyMedium
    val smallStyle = typography.bodySmall
    val labelStyle = typography.labelSmall

    return remember(
        measurer, density, rowWidth, track.title, track.durationMs, track.source,
        track.fileFormat, track.bitsPerSample, track.sampleRateHz,
        isPlaying, isResolving, selectionActive, hasMoreButton,
        titleStyle, smallStyle, labelStyle,
    ) {
        // Unbounded width (e.g. a horizontally scrolling parent): everything fits.
        if (rowWidth == Dp.Infinity) return@remember TrackRowFitDefault
        with(density) {
            fun textWidth(text: String, style: TextStyle): Int =
                measurer.measure(text = text, style = style, maxLines = 1, softWrap = false).size.width

            // Trailing status slot: spinner, equaliser icon, or the duration.
            val statusWidth = when {
                isResolving -> ResolvingSize.roundToPx()
                isPlaying -> PlayingIconSize.roundToPx()
                else -> textWidth(formatDuration(track.durationMs), smallStyle)
            }

            // Source indicator: dots always, labels optionally.
            val labels = sourceIndicatorLabels(track.source)
            val spacing = SourceIndicatorSpacing.roundToPx()
            val dotsWidth = if (labels.isEmpty()) {
                0
            } else {
                labels.size * SourceDotSize.roundToPx() + (labels.size - 1) * spacing
            }
            val labelsExtra = labels.sumOf { spacing + textWidth(it, labelStyle) }

            // Lossless pill, full and compact (0 when the track has none).
            val lossless = isLossless(track.fileFormat)
            val badgePadding = BadgeHorizontalPadding.roundToPx()
            val badgeFull = if (!lossless) 0 else badgePadding + textWidth(
                flacBadgeText(track.fileFormat, track.bitsPerSample, track.sampleRateHz),
                labelStyle,
            )
            val badgeCompact = if (!lossless) 0 else badgePadding + textWidth(
                flacBadgeText(track.fileFormat, null, null),
                labelStyle,
            )

            // Everything that isn't the title column, with labels shown.
            val fixed = 2 * RowHorizontalPadding.roundToPx() +
                (if (selectionActive) CheckboxWidth.roundToPx() + SmallGap.roundToPx() else 0) +
                ArtSize.roundToPx() + ArtGap.roundToPx() +
                SmallGap.roundToPx() + statusWidth +
                SmallGap.roundToPx() + dotsWidth + labelsExtra +
                (if (hasMoreButton) MoreButtonSize.roundToPx() else 0)

            decideTrackRowFit(
                titleColumnWidth = rowWidth.roundToPx() - fixed,
                sourceLabelsExtra = labelsExtra,
                badgeFullWidth = badgeFull,
                badgeCompactWidth = badgeCompact,
                badgeGap = BadgeGap.roundToPx(),
                titleNaturalWidth = textWidth(track.title, titleStyle),
                titleMinWidth = (TitleMinShare * fontScale).roundToPx(),
            )
        }
    }
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
