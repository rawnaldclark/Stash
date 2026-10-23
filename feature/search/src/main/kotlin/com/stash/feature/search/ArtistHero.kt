package com.stash.feature.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.common.ArtUrlUpgrader
import com.stash.data.ytmusic.model.SocialLink

/**
 * Where the gold "♥ Support" chip sends people: the artist's Bandcamp when
 * MusicBrainz knows one (money actually reaches artists there), else their
 * official website; null hides the chip.
 */
internal fun supportUrl(socials: List<SocialLink>): String? =
    socials.firstOrNull {
        it.kind.equals("bandcamp", ignoreCase = true) ||
            it.url.contains("bandcamp.com", ignoreCase = true)
    }?.url
        ?: socials.firstOrNull { it.kind.equals("website", ignoreCase = true) }?.url

/** The photo's height below the status bar. */
internal val ArtistHeroPhotoHeight = 340.dp

/**
 * Artist Profile hero, Spotify-style: the artist's header photo full width and
 * edge to edge (under the status bar), and everything else set on it. Over the
 * photo's bottom edge, where it fades into the page: the name large and full
 * width; under it the plum play coin and the radio ring on the left, the
 * listener count and the gold "♥ Support" chip (Bandcamp → website, see
 * [supportUrl]) on the right; then one row of the artist's links, a glass coin each.
 *
 * YouTube's header photos are wide (about 16:9), so [ArtUrlUpgrader.banner]
 * asks for the photo's own shape; the crop sits a little above centre, where
 * faces are. No photo → the old plum gradient, same layout. The controls take
 * their colours from the page background the photo fades into, so they read the
 * same on any photo and in both themes.
 *
 * [scrollOffsetPx] is how far the hero has scrolled up: the photo moves at
 * half speed under the page (parallax) and fades as it goes. Read in the draw
 * phase only, so scrolling doesn't recompose.
 *
 * Back is not part of the hero: [ArtistProfileScreen] pins it over the page.
 *
 * Paints on the first frame from nav args (see [ArtistProfileViewModel] init);
 * the header photo crossfades in when the profile arrives.
 */
@Composable
fun ArtistHero(
    hero: HeroState,
    @Suppress("UNUSED_PARAMETER") status: ArtistProfileStatus,
    socials: List<SocialLink>,
    onPlayArtist: () -> Unit,
    onStartRadio: () -> Unit,
    streamingEnabled: Boolean,
    onStreamingClick: () -> Unit,
    modifier: Modifier = Modifier,
    scrollOffsetPx: () -> Int = { 0 },
) {
    val primary = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.background
    val ink = MaterialTheme.colorScheme.onBackground
    val uriHandler = LocalUriHandler.current
    val dark = background.luminance() < 0.5f
    val plum = if (dark) Color(0xFF7E6A90) else Color(0xFF6E5A7E)
    val cream = Color(0xFFF2ECE2)
    val gold = Color(0xFFFFC947)
    val goldInk = if (dark) gold else Color(0xFF7A5A00)
    val support = remember(socials) { supportUrl(socials) }
    val photo = remember(hero.avatarUrl) { ArtUrlUpgrader.banner(hero.avatarUrl) }
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ArtistHeroPhotoHeight + statusBarTop)
            .clipToBounds(),
    ) {
        // -- The photo (or the gradient without one), parallax + fade ----------
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val offset = scrollOffsetPx().toFloat()
                    translationY = offset * 0.5f
                    alpha = (1f - offset / size.height).coerceIn(0f, 1f)
                }
                .background(
                    Brush.verticalGradient(listOf(primary.copy(alpha = 0.45f), primary.copy(alpha = 0.08f))),
                ),
        ) {
            if (photo != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(photo)
                        .crossfade(true)
                        .build(),
                    contentDescription = hero.name,
                    contentScale = ContentScale.Crop,
                    alignment = BiasAlignment(0f, -0.35f),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Top scrim: Back and the status bar stay legible on a bright photo.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(statusBarTop + 72.dp)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))),
            )
        }

        // Bottom fade into the page, under the name and controls. Outside the parallax
        // layer so it stays put: the photo slides down under it and its cut edge never shows.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.62f)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to background.copy(alpha = 0.7f),
                        0.8f to background.copy(alpha = 0.95f),
                        1f to background,
                    ),
                ),
        )

        // -- Name, listeners, play + radio, links + Support: all on the photo --
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, bottom = 12.dp),
        ) {
            // The name gets the full width: the controls sit on the line below it.
            Text(
                text = hero.name,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Play and radio on the left; listeners and Support on the right.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = onPlayArtist,
                    shape = CircleShape,
                    color = plum,
                    shadowElevation = 6.dp,
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play ${hero.name}",
                            tint = cream,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Surface(
                    onClick = onStartRadio,
                    shape = CircleShape,
                    color = background.copy(alpha = 0.35f),
                    border = BorderStroke(1.5.dp, ink.copy(alpha = 0.45f)),
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Default.Radio,
                            contentDescription = "Start ${hero.name} radio",
                            tint = ink,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (hero.subscribersText != null) {
                    Text(
                        text = hero.subscribersText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (hero.subscribersText != null && support != null) Spacer(Modifier.width(10.dp))
                if (support != null) {
                    Surface(
                        onClick = { runCatching { uriHandler.openUri(support) } },
                        shape = RoundedCornerShape(99.dp),
                        color = gold.copy(alpha = 0.28f),
                        border = BorderStroke(1.dp, gold.copy(alpha = 0.55f)),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = null,
                                tint = goldInk,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                text = "Support",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = goldInk,
                            )
                        }
                    }
                }
            }

            // -- The artist's doors: a glass coin each, the full width ------------
            if (socials.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    socials.forEach { social ->
                        Surface(
                            onClick = { runCatching { uriHandler.openUri(social.url) } },
                            shape = CircleShape,
                            color = background.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, ink.copy(alpha = 0.18f)),
                            modifier = Modifier.size(32.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                val brand = socialIconFor(social.kind)
                                if (brand != null) {
                                    Icon(
                                        painter = painterResource(brand),
                                        contentDescription = social.kind,
                                        tint = ink.copy(alpha = 0.85f),
                                        modifier = Modifier.size(16.dp),
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Public,
                                        contentDescription = social.kind,
                                        tint = ink.copy(alpha = 0.85f),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // -- Streaming chip (Back is pinned by the screen, over everything) --------
        if (com.stash.core.common.constants.StashConstants.STREAMING_ENGINE_ENABLED) {
            com.stash.core.ui.components.streaming.StreamingModeChip(
                streamingEnabled = streamingEnabled,
                onClick = onStreamingClick,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 8.dp, top = statusBarTop + 8.dp),
            )
        }
    }
}
