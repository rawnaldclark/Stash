package com.stash.feature.home.shelves

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stash.core.ui.components.SectionHeader
import com.stash.core.ui.components.motion.pressScale
import com.stash.core.ui.theme.StashTheme
import com.stash.data.ytmusic.model.MoodTile

/** A tile's height; two rows of them make the shelf. */
private val TileHeight = 52.dp

/** The gap between tiles, both ways. */
private val TileGap = 10.dp

/**
 * Home's "Moods & moments": YouTube Music's mood tiles in two scrolling rows, each with YouTube's own colour as a
 * stripe down its edge, the way YouTube Music draws them. A tap opens the mood's page of playlists ([onOpen]).
 */
@Composable
internal fun MoodTilesShelf(
    title: String,
    tiles: List<MoodTile>,
    onOpen: (MoodTile) -> Unit,
) {
    Column {
        Spacer(Modifier.height(16.dp))
        SectionHeader(title = title)
        LazyHorizontalGrid(
            rows = GridCells.Fixed(2),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(TileGap),
            verticalArrangement = Arrangement.spacedBy(TileGap),
            modifier = Modifier.height(TileHeight * 2 + TileGap),
        ) {
            items(tiles, key = { it.params }) { tile -> MoodTileCard(tile, onClick = { onOpen(tile) }) }
        }
    }
}

@Composable
private fun MoodTileCard(tile: MoodTile, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .width(152.dp)
            .height(TileHeight)
            .pressScale(interaction)
            .clip(shape)
            .background(StashTheme.extendedColors.elevatedSurface)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(6.dp)
                .fillMaxHeight()
                .background(tile.color?.let { Color(stripeArgb(it)) } ?: MaterialTheme.colorScheme.primary),
        )
        Text(
            text = tile.title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/** YouTube's stripe colour as Compose takes it: ARGB, made opaque when it came without alpha (it'd be invisible). */
internal fun stripeArgb(color: Long): Long = if (color ushr 24 == 0L) color or 0xFF000000 else color
