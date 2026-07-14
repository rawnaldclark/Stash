package com.stash.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.stash.core.ui.theme.StashTheme

/**
 * One ranked album (Premium Crisp "Top albums" list, spec §5).
 *
 * @param rank     1-based chart position, shown as a Space Grotesk numeral.
 * @param title    Album title.
 * @param artist   Artist name.
 * @param artUrl   Cover art URL (passed through [ArtUrlUpgrader]).
 * @param movement Week-over-week rank change: >0 → "▲ n" (up, cyan); 0 or null → "—" (flat).
 */
data class RankedAlbumUi(
    val rank: Int,
    val title: String,
    val artist: String,
    val artUrl: String?,
    val movement: Int?,
)

/**
 * A vertical list of [RankedAlbumUi] rows. [onClick] fires with the tapped item.
 */
@Composable
fun RankedAlbumList(
    items: List<RankedAlbumUi>,
    onClick: (RankedAlbumUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        items.forEach { item ->
            RankedAlbumRow(item = item, onClick = { onClick(item) })
        }
    }
}

@Composable
private fun RankedAlbumRow(
    item: RankedAlbumUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text(
            text = "${item.rank}",
            style = MaterialTheme.typography.titleMedium,
            color = extendedColors.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(16.dp),
        )
        AsyncImage(
            model = ArtUrlUpgrader.upgrade(item.artUrl),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(4.dp))
        val movement = item.movement ?: 0
        Text(
            text = if (movement > 0) "▲ $movement" else "—",
            style = MaterialTheme.typography.labelMedium,
            color = if (movement > 0) extendedColors.cyan else extendedColors.textTertiary,
        )
    }
}
