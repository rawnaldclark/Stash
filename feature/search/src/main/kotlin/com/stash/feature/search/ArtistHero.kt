package com.stash.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.common.ArtUrlUpgrader
import com.stash.core.ui.R

/**
 * Artist Profile hero card.
 *
 * Renders a large circular avatar, the artist name, and an optional
 * subscriber count over a purple vertical wash. The composable paints on
 * the first frame from nav args (see [ArtistProfileViewModel] init) so the
 * < 50 ms hero-paint target can be met even before the cache emits.
 *
 * Kept intentionally lean for Task 8 — the glass-chip polish (play /
 * shuffle / follow buttons, parallax on scroll) arrives in Task 11.
 *
 * @param hero Name + avatar + subscribers triple. Name is required; the
 *   rest are optional and hide gracefully when null.
 * @param status Load status; currently unused visually but accepted so
 *   Task 11 can add a stale badge without the call-site changing.
 * @param onBack Invoked when the top-left back arrow is tapped (spec §5.2).
 * @param onPlayArtist Invoked when the "Play" button is tapped. Hybrid-starts
 *   playback of the artist's catalog — see [ArtistProfileViewModel.playArtist].
 * @param onStartRadio Invoked when the "Radio" button is tapped. Starts a
 *   balanced artist radio — see [ArtistProfileViewModel.startRadio].
 */
@Composable
fun ArtistHero(
    hero: HeroState,
    @Suppress("UNUSED_PARAMETER") status: ArtistProfileStatus,
    onBack: () -> Unit,
    onPlayArtist: () -> Unit,
    onStartRadio: () -> Unit,
    streamingEnabled: Boolean,
    onStreamingClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        primary.copy(alpha = 0.35f),
                        primary.copy(alpha = 0.05f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AsyncImage(
                model = ArtUrlUpgrader.upgrade(hero.avatarUrl),
                contentDescription = hero.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(120.dp).clip(CircleShape),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = hero.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (hero.subscribersText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = hero.subscribersText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Play + Radio CTAs. Play matches the AlbumHero "Play" style (filled
            // primary); Radio is a tonal sibling that starts a balanced station.
            Row(horizontalArrangement = Arrangement.Center) {
                Button(
                    onClick = onPlayArtist,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_play),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Spacer(Modifier.width(10.dp))
                FilledTonalButton(
                    onClick = onStartRadio,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Radio,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_radio),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        // Top-left back arrow. The Scaffold parent already applies
        // `innerPadding` (incl. status bar) to the LazyColumn that hosts
        // this hero, so here we just add the 8.dp Compose toolbar gutter.
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_back),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }

        // Top-right Online/Offline chip — flip playback mode from the profile
        // (mirrors the back arrow's placement).
        if (com.stash.core.common.constants.StashConstants.STREAMING_ENGINE_ENABLED) {
            com.stash.core.ui.components.streaming.StreamingModeChip(
                streamingEnabled = streamingEnabled,
                onClick = onStreamingClick,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            )
        }
    }
}
