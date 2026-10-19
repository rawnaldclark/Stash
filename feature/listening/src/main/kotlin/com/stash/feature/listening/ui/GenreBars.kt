package com.stash.feature.listening.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stash.core.data.genre.GenreShare
import com.stash.core.ui.theme.StashTheme
import kotlin.math.floor
import kotlin.math.roundToInt

/** The credit every screen showing genres carries (Last.fm's terms; DESIGN.md phase 2). */
internal const val GENRES_CREDIT = "Genres from Last.fm"

/** Below this coverage the credit line says how much of the listening has a genre. */
internal const val FULL_COVERAGE = 0.95

/** [coverage] 0..1 as a whole percent, rounded down: never claims more than is known. */
internal fun coveragePercent(coverage: Double): Int = floor(coverage.coerceIn(0.0, 1.0) * 100).toInt()

/**
 * The line under genre bars: "Share of your listening time · Genres from Last.fm", or, when a
 * part of the listening has no known genre, "Genre known for 80% of your listening · Genres from
 * Last.fm" (the shares are of all the listening, so they add up to that 80% at most).
 */
internal fun genreCredit(coverage: Double): String =
    if (coverage >= FULL_COVERAGE) {
        "Share of your listening time · $GENRES_CREDIT"
    } else {
        "Genre known for ${coveragePercent(coverage)}% of your listening · $GENRES_CREDIT"
    }

/**
 * What the Genres list says instead of ranking when under half the listening has a genre:
 * how far along it is, "not enough" when genres exist but none for this period, and "on their way"
 * only before any genre has arrived at all.
 */
internal fun genresThinMessage(coverage: Double, genresCached: Boolean): String {
    val pct = coveragePercent(coverage)
    return when {
        pct > 0 -> "We know the genre of $pct% of your listening in this period so far. Genres show up here once that's half or more."
        genresCached -> "Not enough to rank genres for this period yet."
        else -> "Genres for your artists are still on their way from Last.fm. They show up here once they've arrived."
    }
}

/**
 * A genre's share as whole percent: "34%", "<1%" for a sliver (never "0%" for a genre that is
 * there). [share] is 0..1.
 */
internal fun genrePercent(share: Double): String {
    val pct = (share * 100).roundToInt()
    return if (pct < 1) "<1%" else "$pct%"
}

/**
 * One genre: its name and percent on a line, and a thin bar under them whose length is the share
 * relative to the strongest genre (so #1 fills the row and the rest read against it).
 *
 * @param fraction the bar's length 0..1 (the caller divides by the top share).
 */
@Composable
internal fun GenreBar(
    genre: GenreShare,
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    barHeight: Dp = 6.dp,
) {
    val percent = genrePercent(genre.share)
    Column(modifier.clearAndSetSemantics { contentDescription = "${genre.name}, $percent of your listening" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = genre.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(percent, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        BarTrack(fraction, color, barHeight)
    }
}

/** A rounded track with the filled part [fraction] long. */
@Composable
internal fun BarTrack(fraction: Float, color: Color, height: Dp = 6.dp, track: Color = StashTheme.extendedColors.glassBorderBright) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .clip(RoundedCornerShape(height / 2))
                .background(color),
        )
    }
}

/** The bars' lengths: each share relative to the strongest one. */
internal fun genreFractions(genres: List<GenreShare>): List<Float> {
    val top = genres.maxOfOrNull { it.share } ?: return emptyList()
    if (top <= 0.0) return genres.map { 0f }
    return genres.map { (it.share / top).toFloat() }
}
