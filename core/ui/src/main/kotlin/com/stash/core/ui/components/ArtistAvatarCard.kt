// core/ui/src/main/kotlin/com/stash/core/ui/components/ArtistAvatarCard.kt
package com.stash.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.common.ArtUrlUpgrader

/**
 * Circular 96dp artist avatar tile with a centered name label.
 *
 * Used in search results and on Artist Profile screens. Tapping the card
 * invokes [onClick] — typically a navigation to the artist profile.
 *
 * @param name The artist's display name. Shown beneath the avatar, single-line ellipsized.
 * @param avatarUrl Optional avatar URL; passed through [ArtUrlUpgrader] which asks the
 *   CDN for 1024x1024. Coil downsamples in-memory to the 96dp render target — the
 *   high-res source guarantees crispness on 3x displays where 96dp = 288px.
 * @param modifier Optional layout modifier applied to the root column.
 * @param onClick Invoked on tap.
 */
@Composable
fun ArtistAvatarCard(
    name: String,
    avatarUrl: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.width(96.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = ArtUrlUpgrader.upgrade(avatarUrl),
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(96.dp).clip(CircleShape),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
