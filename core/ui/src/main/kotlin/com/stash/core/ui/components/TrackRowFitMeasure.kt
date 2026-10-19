package com.stash.core.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.core.model.Track

// ── TrackListItem's fixed sizes ────────────────────────────────────────────
//
// Shared by the row's layout (TrackListItem) and the space arithmetic below,
// so the two can't drift apart.
internal val RowHorizontalPadding = 20.dp
internal val ArtSize = 48.dp
internal val ArtGap = 12.dp
internal val SmallGap = 8.dp
internal val BadgeGap = 4.dp
internal val MoreButtonSize = 32.dp
internal val ResolvingSize = 20.dp
internal val PlayingIconSize = 18.dp
private val SourceDotSize = 6.dp

/** The pill's horizontal text padding in [FlacBadge] (6 dp each side). */
private val BadgeHorizontalPadding = 12.dp

/**
 * Width of the selection checkbox. Material 3 draws a 20 dp box with 2 dp
 * padding, and adds no 48 dp touch target because `onCheckedChange` is null.
 */
private val CheckboxWidth = 24.dp

/**
 * The fair minimum share of the row the title is guaranteed: about ten
 * characters of body text, enough to recognise a song. In sp so it grows
 * through the same (non-linear, Android 14+) font-scale curve as the title.
 */
private val TitleMinShare = 80.sp

/**
 * Entries in the shared measurer's cache. A screen of rows re-measures the
 * same few strings ("Spotify", "YouTube", "FLAC 24/44", durations), so a
 * modest cache serves them all; titles are unique and simply cycle through.
 */
private const val SharedMeasurerCacheSize = 32

/** What a list of [TrackListItem]s shares: the rows' width and one text measurer. */
@Immutable
internal class TrackRowFitEnvironment(val rowWidth: Dp, val measurer: TextMeasurer)

internal val LocalTrackRowFitEnvironment = staticCompositionLocalOf<TrackRowFitEnvironment?> { null }

/**
 * Wrap a list of full-width [TrackListItem]s (typically its `LazyColumn`) in
 * this. The rows then share one width reading and one [TextMeasurer], instead
 * of each row running its own `BoxWithConstraints` (a subcomposition) and its
 * own measurer with a cold cache.
 *
 * Only for rows as wide as this scope; a [TrackListItem] outside any scope
 * measures its own width.
 */
@Composable
fun TrackRowFitScope(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer(cacheSize = SharedMeasurerCacheSize)
        val environment = remember(maxWidth, measurer) { TrackRowFitEnvironment(maxWidth, measurer) }
        CompositionLocalProvider(LocalTrackRowFitEnvironment provides environment, content = content)
    }
}

/**
 * Measures the row's text at the current density and font scale and asks
 * [decideTrackRowFit] what fits. Remembered per row on every input that
 * changes the answer, so scrolling an already-composed row doesn't re-measure.
 */
@Composable
internal fun rememberTrackListItemFit(
    track: Track,
    rowWidth: Dp,
    measurer: TextMeasurer,
    isPlaying: Boolean,
    isResolving: Boolean,
    selectionActive: Boolean,
    hasMoreButton: Boolean,
    /** Shown instead of the duration when set (see [TrackListItem]'s `trailingText`). */
    trailingText: String? = null,
): TrackRowFit {
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val titleStyle = typography.bodyMedium
    val smallStyle = typography.bodySmall
    val labelStyle = typography.labelSmall

    return remember(
        measurer, density, rowWidth, track.title, track.durationMs, track.source,
        track.fileFormat, track.bitsPerSample, track.sampleRateHz,
        isPlaying, isResolving, selectionActive, hasMoreButton,
        titleStyle, smallStyle, labelStyle, trailingText,
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
                else -> textWidth(trailingText ?: formatDuration(track.durationMs), smallStyle)
            }

            // Source indicator: the coloured dots only. Source names are never drawn in a row, so their
            // text width is not measured and does not take room from the title.
            val labels = sourceIndicatorLabels(track.source)
            val spacing = SourceIndicatorSpacing.roundToPx()
            val dotsWidth = if (labels.isEmpty()) {
                0
            } else {
                labels.size * SourceDotSize.roundToPx() + (labels.size - 1) * spacing
            }

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

            // Everything that isn't the title column (dots only, no source names).
            val fixed = 2 * RowHorizontalPadding.roundToPx() +
                (if (selectionActive) CheckboxWidth.roundToPx() + SmallGap.roundToPx() else 0) +
                ArtSize.roundToPx() + ArtGap.roundToPx() +
                SmallGap.roundToPx() + statusWidth +
                SmallGap.roundToPx() + dotsWidth +
                (if (hasMoreButton) MoreButtonSize.roundToPx() else 0)

            val titleMin = TitleMinShare.roundToPx()
            fun decide(titleWidth: Int) = decideTrackRowFit(
                titleColumnWidth = rowWidth.roundToPx() - fixed,
                sourceLabelsExtra = 0,
                badgeFullWidth = badgeFull,
                badgeCompactWidth = badgeCompact,
                badgeGap = BadgeGap.roundToPx(),
                titleNaturalWidth = titleWidth,
                titleMinWidth = titleMin,
            )
            // Most rows have room for a full fair share, and then the title's own width can't change the
            // answer (a shorter title only needs less), so it is measured only for the crowded rows.
            decide(titleWidth = titleMin).takeIf { it == TrackRowFitDefault }
                ?: decide(textWidth(track.title, titleStyle))
        }
    }
}
