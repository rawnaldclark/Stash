// core/ui/src/main/kotlin/com/stash/core/ui/components/AlbumSquareCard.kt
package com.stash.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.common.ArtUrlUpgrader
import com.stash.core.ui.components.motion.pressScale

/**
 * [AlbumSquareCard]'s cover and width everywhere but Home (which uses [HomeRailCardSize]), and the
 * size of the placeholders that stand in for it while the artist page loads ([AlbumsRowSkeleton]).
 */
val AlbumCardSize = 140.dp

/**
 * Square album card with rounded thumbnail, title, and "year • artist" subtitle: [AlbumCardSize]
 * (140 dp) unless [size] says otherwise.
 *
 * At [AlbumCardSize]: Search's Albums row; the artist page's Latest releases, Albums and Singles &
 * EPs rails, and an album page's More by this artist (all through `AlbumsRow`/`LatestReleasesRow`);
 * Library's Recently downloaded; the mood pages; and the Qobuz playlist browse grid.
 * At [HomeRailCardSize] (120 dp): Home's New Releases, Qobuz Playlists, Top Albums, New from your
 * artists and Because you like rows, so they step with the mix rails beside them.
 *
 * @param title Album title shown on the first text row.
 * @param artist Artist name for the subtitle.
 * @param thumbnailUrl Optional cover art URL; passed through [ArtUrlUpgrader] which
 *   asks the CDN for 1024x1024. Coil downsamples to the [size] render target — the
 *   high-res request just guarantees crispness on high-DPI screens.
 * @param year Optional release year. The subtitle is `"$year • $artist"` ([albumCardSubtitle]),
 *   or whichever of the two isn't blank: the artist page passes no artist, its cards are all its own.
 * @param modifier Optional layout modifier applied to the root column.
 * @param isLossless When true, overlays a small FLAC badge on the cover art
 *   (Premium Crisp quality signal). Defaults to false so existing callers are unaffected.
 * @param size The cover's side and the card's width: [AlbumCardSize] by default, [HomeRailCardSize]
 *   on Home's rows (the callers are listed above). Only the size changes: corners, badge and text stay.
 * @param onClick Invoked on tap — typically opens the album detail screen.
 */
@Composable
fun AlbumSquareCard(
    title: String,
    artist: String,
    thumbnailUrl: String?,
    year: String?,
    modifier: Modifier = Modifier,
    isLossless: Boolean = false,
    size: Dp = AlbumCardSize,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .width(size)
            .pressScale(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        Box {
            AsyncImage(
                model = ArtUrlUpgrader.upgrade(thumbnailUrl),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)),
            )
            if (isLossless) {
                // Bottom-right, translucent: the industry-standard corner for a
                // quality mark, and see-through enough to not block the art.
                FlacBadge(
                    fileFormat = "flac",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .alpha(0.78f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = albumCardSubtitle(year, artist),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The card's second line: "2010 • Artist", or just the part that isn't blank ("2010", "Artist"), never a lone "•". */
internal fun albumCardSubtitle(year: String?, artist: String): String =
    listOfNotNull(year?.takeIf { it.isNotBlank() }, artist.takeIf { it.isNotBlank() }).joinToString(" • ")
