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
internal val ArtistHeroPhotoHeight = 300.dp

/**
 * Artist Profile hero, Spotify-style: the artist's header photo full width and
 * edge to edge (under the status bar), fading into the page, with the name set
 * large over its bottom edge and the listener count under it. Below the photo,
 * the app's own action vocabulary: a solid plum play coin + a hairline radio
 * ring, the gold "♥ Support" chip on the right (Bandcamp → website, see
 * [supportUrl]), then one glass coin per social link.
 *
 * YouTube's header photos are wide (about 16:9), so [ArtUrlUpgrader.banner]
 * asks for the photo's own shape; the crop sits a little above centre, where
 * faces are. No photo → the old plum gradient, same layout.
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
    val uriHandler = LocalUriHandler.current
    val dark = background.luminance() < 0.5f
    val plum = if (dark) Color(0xFF7E6A90) else Color(0xFF6E5A7E)
    val cream = Color(0xFFF2ECE2)
    val gold = Color(0xFFFFC947)
    val goldInk = if (dark) gold else Color(0xFF7A5A00)
    val support = remember(socials) { supportUrl(socials) }
    val photo = remember(hero.avatarUrl) { ArtUrlUpgrader.banner(hero.avatarUrl) }
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ArtistHeroPhotoHeight + statusBarTop)
                .clipToBounds(),
        ) {
            // -- The photo (or the gradient without one), parallax + fade ------
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
                // Top scrim: the back button and status bar stay legible on a bright photo.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(statusBarTop + 72.dp)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))),
                )
            }

            // Bottom fade into the page, under the name. Outside the parallax layer so it
            // stays put: the photo slides down under it and its cut edge never shows.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.6f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, background.copy(alpha = 0.75f), background))),
            )

            // -- Name + listeners over the photo's bottom edge -----------------
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            ) {
                Text(
                    text = hero.name,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hero.subscribersText != null) {
                    Text(
                        text = hero.subscribersText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // -- Streaming chip (Back is pinned by the screen, over everything) --
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

        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)) {
            // -- Actions: play coin + radio ring · gold Support right ----------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    onClick = onPlayArtist,
                    shape = CircleShape,
                    color = plum,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play ${hero.name}",
                            tint = cream,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                Surface(
                    onClick = onStartRadio,
                    shape = CircleShape,
                    color = Color.Transparent,
                    border = BorderStroke(1.5.dp, plum),
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Default.Radio,
                            contentDescription = "Start ${hero.name} radio",
                            tint = plum,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (support != null) {
                    Surface(
                        onClick = { runCatching { uriHandler.openUri(support) } },
                        shape = RoundedCornerShape(99.dp),
                        color = gold.copy(alpha = 0.28f),
                        border = BorderStroke(1.dp, gold.copy(alpha = 0.55f)),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = null,
                                tint = goldInk,
                                modifier = Modifier.size(13.dp),
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

            // -- The artist's doors: one glass coin per social link ------------
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
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, plum.copy(alpha = 0.35f)),
                            modifier = Modifier.size(32.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                val brand = socialIconFor(social.kind)
                                if (brand != null) {
                                    Icon(
                                        painter = painterResource(brand),
                                        contentDescription = social.kind,
                                        tint = plum,
                                        modifier = Modifier.size(16.dp),
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Public,
                                        contentDescription = social.kind,
                                        tint = plum,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
