// core/ui/src/main/kotlin/com/stash/core/ui/components/AlbumSquareCard.kt
package com.stash.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.common.ArtUrlUpgrader

/**
 * 140dp square album card with rounded thumbnail, title, and "year • artist" subtitle.
 *
 * Used in search results and on Artist Profile discography rails.
 *
 * @param title Album title shown on the first text row.
 * @param artist Artist name for the subtitle.
 * @param thumbnailUrl Optional cover art URL; passed through [ArtUrlUpgrader] which
 *   asks the CDN for 1024x1024. Coil downsamples to the 140dp render target — the
 *   high-res request just guarantees crispness on high-DPI screens.
 * @param year Optional release year. When present the subtitle is `"$year • $artist"`,
 *   otherwise just the artist name.
 * @param modifier Optional layout modifier applied to the root column.
 * @param onClick Invoked on tap — typically opens the album detail screen.
 */
@Composable
fun AlbumSquareCard(
    title: String,
    artist: String,
    thumbnailUrl: String?,
    year: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(modifier = modifier.width(140.dp).clickable(onClick = onClick)) {
        AsyncImage(
            model = ArtUrlUpgrader.upgrade(thumbnailUrl),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(140.dp).clip(RoundedCornerShape(8.dp)),
        )
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
