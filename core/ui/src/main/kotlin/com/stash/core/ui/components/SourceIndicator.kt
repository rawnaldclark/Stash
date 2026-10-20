package com.stash.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stash.core.model.MusicSource
import com.stash.core.ui.theme.StashTheme

/**
 * Displays a colored dot for the track's source, optionally with a text label.
 *
 * @param source     The [MusicSource] to indicate.
 * @param modifier   Optional [Modifier] applied to the root row.
 * @param size       Diameter of the indicator dot. Defaults to 6 dp.
 * @param showLabel  When true, a short text label ("Spotify" / "YouTube") is
 *                   displayed next to the dot for extra clarity. When false,
 *                   the dots carry the same names as their content description.
 */
@Composable
fun SourceIndicator(
    source: MusicSource,
    modifier: Modifier = Modifier,
    size: Dp = 6.dp,
    showLabel: Boolean = false,
) {
    val extendedColors = StashTheme.extendedColors
    Row(
        // Dots alone say nothing to TalkBack: give them the names the labels would have read.
        modifier = if (showLabel) {
            modifier
        } else {
            modifier.semantics { contentDescription = sourceIndicatorLabels(source).joinToString(", ") }
        },
        horizontalArrangement = Arrangement.spacedBy(SourceIndicatorSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (source == MusicSource.SPOTIFY || source == MusicSource.BOTH) {
            Box(Modifier.size(size).clip(CircleShape).background(extendedColors.spotifyGreen))
            if (showLabel) {
                Text(
                    text = "Spotify",
                    style = MaterialTheme.typography.labelSmall,
                    color = extendedColors.textTertiary,
                )
            }
        }
        if (source == MusicSource.YOUTUBE || source == MusicSource.BOTH) {
            Box(Modifier.size(size).clip(CircleShape).background(extendedColors.youtubeRed))
            if (showLabel) {
                Text(
                    text = "YouTube",
                    style = MaterialTheme.typography.labelSmall,
                    color = extendedColors.textTertiary,
                )
            }
        }
        if (source == MusicSource.LOCAL) {
            Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            if (showLabel) {
                Text(
                    text = "Local",
                    style = MaterialTheme.typography.labelSmall,
                    color = extendedColors.textTertiary,
                )
            }
        }
        // A song from the user's own cloud storage (OneDrive), streamed until downloaded.
        if (source == MusicSource.CLOUD) {
            Box(Modifier.size(size).clip(CircleShape).background(extendedColors.cyan))
            if (showLabel) {
                Text(
                    text = "Cloud",
                    style = MaterialTheme.typography.labelSmall,
                    color = extendedColors.textTertiary,
                )
            }
        }
    }
}

/** Gap between the dots and labels inside [SourceIndicator]. */
internal val SourceIndicatorSpacing = 3.dp

/**
 * The text labels [SourceIndicator] draws for [source], in order. Kept beside
 * the composable so a row that measures the labels (see [TrackListItem]) uses
 * the same strings that are drawn.
 */
internal fun sourceIndicatorLabels(source: MusicSource): List<String> = when (source) {
    MusicSource.SPOTIFY -> listOf("Spotify")
    MusicSource.YOUTUBE -> listOf("YouTube")
    MusicSource.BOTH -> listOf("Spotify", "YouTube")
    MusicSource.LOCAL -> listOf("Local")
    MusicSource.CLOUD -> listOf("Cloud")
}
