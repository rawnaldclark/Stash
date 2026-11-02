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
 * Square album card with rounded thumbnail, title, and "year • artist" subtitle: 140 dp unless
 * [size] says otherwise.
 *
 * Used in search results and on Artist Profile discography rails at the default size; Home's album
 * rows pass [HomeRailCardSize] so they step with the mix rails beside them.
 *
 * @param title Album title shown on the first text row.
 * @param artist Artist name for the subtitle.
 * @param thumbnailUrl Optional cover art URL; passed through [ArtUrlUpgrader] which
 *   asks the CDN for 1024x1024. Coil downsamples to the [size] render target — the
 *   high-res request just guarantees crispness on high-DPI screens.
 * @param year Optional release year. When present the subtitle is `"$year • $artist"`,
 *   otherwise just the artist name.
 * @param modifier Optional layout modifier applied to the root column.
 * @param isLossless When true, overlays a small FLAC badge on the cover art
 *   (Premium Crisp quality signal). Defaults to false so existing callers are unaffected.
 * @param size The cover's side and the card's width. 140 dp by default (Search, the artist page,
 *   Library, the mood pages); Home's rows pass [HomeRailCardSize] (120 dp). Only the size changes:
 *   corners, badge and text stay.
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
    size: Dp = 140.dp,
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
            text = if (year != null) "$year • $artist" else artist,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
