package com.stash.feature.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.common.ArtUrlUpgrader
import com.stash.core.data.mix.MixBuildState
import com.stash.core.model.MusicSource
import com.stash.core.ui.components.SourceIndicator
import com.stash.core.ui.components.motion.pressScale
import com.stash.core.ui.theme.StashTheme

/**
 * Premium-Crisp card for one algorithmic mix on a Home rail.
 *
 * Mirrors [com.stash.core.ui.components.AlbumSquareCard]: a 140dp column with a
 * square cover, source dot, title, and — for the user's own Stash mixes — a
 * build-state line while their tracks populate.
 *
 * @param title      Mix name shown under the cover (up to two lines).
 * @param artUrl     Optional cover art; upgraded via [ArtUrlUpgrader]. When null,
 *                   a source-tinted gradient stands in.
 * @param source     Drives the [SourceIndicator] dot. Stash mixes pass a source
 *                   the indicator doesn't draw (no dot), which is intentional.
 * @param buildState [MixBuildState.BUILDING]/[MixBuildState.EMPTY] show a status
 *                   line; [MixBuildState.READY] shows none. Streaming mixes are
 *                   always READY.
 * @param onClick    Invoked on tap — opens the mix.
 * @param onLongPress Optional long-press — drives the Stash-mix action sheet;
 *                    streaming-mix cards pass null.
 * @param modifier   Applied to the root column.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MixRailCard(
    title: String,
    artUrl: String?,
    source: MusicSource,
    buildState: MixBuildState = MixBuildState.READY,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .width(140.dp)
            .pressScale(interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress,
            ),
    ) {
        Box {
            val coverModifier = Modifier.size(140.dp).clip(RoundedCornerShape(8.dp))
            if (artUrl != null) {
                AsyncImage(
                    model = ArtUrlUpgrader.upgrade(artUrl),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = coverModifier,
                )
            } else {
                Box(coverModifier.background(sourceGradient(source)))
            }
            SourceIndicator(
                source = source,
                size = 8.dp,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val statusLine = when (buildState) {
            MixBuildState.BUILDING -> "Building…"
            MixBuildState.EMPTY -> "No tracks yet"
            MixBuildState.READY -> null
        }
        if (statusLine != null) {
            Text(
                text = statusLine,
                style = MaterialTheme.typography.labelSmall,
                color = StashTheme.extendedColors.textTertiary,
            )
        }
    }
}

/** Source-tinted top-left→bottom-right gradient for the artless cover fallback. */
@Composable
private fun sourceGradient(source: MusicSource): Brush {
    val colors = StashTheme.extendedColors
    val tint = when (source) {
        MusicSource.SPOTIFY -> colors.spotifyGreen
        MusicSource.YOUTUBE -> colors.youtubeRed
        else -> colors.purpleLight // Stash / algorithmic mixes
    }
    return Brush.linearGradient(listOf(tint, colors.purpleDark))
}

@Preview
@Composable
private fun MixRailCardPreview() {
    StashTheme {
        Column {
            MixRailCard(
                title = "Discover Weekly",
                artUrl = null,
                source = MusicSource.SPOTIFY,
                buildState = MixBuildState.READY,
                onClick = {},
            )
            Spacer(Modifier.height(12.dp))
            MixRailCard(
                title = "My Supermix",
                artUrl = null,
                source = MusicSource.YOUTUBE,
                buildState = MixBuildState.READY,
                onClick = {},
            )
            Spacer(Modifier.height(12.dp))
            MixRailCard(
                title = "Late Night Focus",
                artUrl = null,
                source = MusicSource.LOCAL,
                buildState = MixBuildState.BUILDING,
                onClick = {},
            )
        }
    }
}
