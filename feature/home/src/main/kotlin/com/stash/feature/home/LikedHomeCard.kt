package com.stash.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.stash.core.common.extensions.pluralize
import com.stash.core.ui.components.HomeRailCardSize
import com.stash.core.ui.components.HomeRailCoverShape
import com.stash.core.ui.components.motion.pressScale
import com.stash.core.ui.theme.StashTheme

/**
 * The merged Liked Songs card on Home's "Your playlists" rail. Mirrors
 * [MixRailCard]'s [HomeRailCardSize] column; the cover is a heart on the Stash purple
 * gradient instead of art. Tap switches to Library ▸ Liked.
 */
@Composable
fun LikedHomeCard(
    trackCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .width(HomeRailCardSize)
            .pressScale(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        val colors = StashTheme.extendedColors
        Box(
            modifier = Modifier
                .size(HomeRailCardSize)
                .clip(HomeRailCoverShape)
                .background(Brush.linearGradient(listOf(colors.purpleLight, colors.purpleDark))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(42.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(text = "Liked Songs", style = MaterialTheme.typography.labelLarge)
        Text(
            text = pluralize(trackCount, "song"),
            style = MaterialTheme.typography.labelSmall,
            color = StashTheme.extendedColors.textTertiary,
        )
    }
}

@Preview
@Composable
private fun LikedHomeCardPreview() {
    StashTheme {
        LikedHomeCard(trackCount = 312, onClick = {})
    }
}
