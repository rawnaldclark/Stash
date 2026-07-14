package com.stash.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.common.ArtUrlUpgrader
import com.stash.core.ui.theme.StashBackground
import com.stash.core.ui.theme.StashElevation
import com.stash.core.ui.theme.StashPurpleDark

/**
 * The single bold "for you" moment on Home (Premium Crisp, spec §4/§5) —
 * the tailored daily discovery. Whole-card tap opens the playlist; the round
 * button plays it.
 *
 * Data-agnostic. When [loading] is true a shimmer block is shown in place of
 * the content (spec §5 hero state: skeleton while the Daily Discover playlist
 * first materializes). The caller omits this card entirely when there is no
 * discovery to show — this component always renders something (shimmer or hero).
 *
 * @param label    Small uppercase eyebrow (e.g. "Daily discovery").
 * @param title    Hero title (e.g. "Discover").
 * @param subtitle Supporting line (track count + cadence).
 * @param artUrl   Cover art URL — rendered as the card background (with a scrim
 *   for text legibility). Falls back to the brand gradient when null.
 * @param onPlay   Invoked when the round play button is tapped.
 * @param onOpen   Invoked when the card body is tapped (open the playlist).
 * @param loading  When true, render a shimmer skeleton instead of the hero body.
 */
@Composable
fun DiscoverHeroCard(
    label: String,
    title: String,
    subtitle: String,
    artUrl: String?,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp)
            .shadow(StashElevation.Hero, shape, clip = false)
            .clip(shape)
            .then(if (!loading) Modifier.clickable(onClick = onOpen) else Modifier),
    ) {
        if (loading) {
            ShimmerPlaceholder(modifier = Modifier.fillMaxSize(), shape = shape)
            return@Box
        }

        if (artUrl != null) {
            // Cover art fills the card; a scrim keeps the bottom-left text legible.
            AsyncImage(
                model = ArtUrlUpgrader.upgrade(artUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.7f)),
                        ),
                    ),
            )
        } else {
            // No art yet — brand gradient fallback.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(StashPurpleDark, StashBackground))),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                        ),
                    ),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 15.dp, end = 15.dp, bottom = 13.dp),
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Surface(
            onClick = onPlay,
            shape = CircleShape,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp)
                .size(38.dp),
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play $title",
                tint = Color.Black,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}
