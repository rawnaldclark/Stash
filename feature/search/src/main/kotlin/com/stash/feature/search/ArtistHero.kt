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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.common.ArtUrlUpgrader
import com.stash.data.ytmusic.model.SocialLink
import java.util.Locale

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

/**
 * A link coin, as on the original header. The links' line under the Support chip is this tall
 * before they arrive too, so their landing a beat late never moves the name.
 */
private val LinkCoinSize = 32.dp

/**
 * Links the header leaves to About, so the few that fit beside play and radio stay a usable
 * size (owner, 2026-10-29: Björk's 7 shrank to nothing). About still lists every link.
 */
private val HeaderSkippedLinks = setOf("soundcloud", "facebook", "youtube")

/** The links the header shows: all but [HeaderSkippedLinks]. */
internal fun headerLinks(socials: List<SocialLink>): List<SocialLink> =
    socials.filterNot { it.kind.lowercase() in HeaderSkippedLinks }

/**
 * "1.7M monthly listeners" in the app's language, or "" while there is no number (the line keeps
 * its height either way). The plural follows the number shown (1 700 000 for "1.7M"), see
 * [ShortCount]; beyond an Int it is "other" in every language.
 */
@Composable
private fun monthlyListenersLabel(count: Long?): String {
    if (count == null) return ""
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val short = remember(count, locale) { shortMonthlyListeners(count, locale) }
    return pluralStringResource(
        R.plurals.artist_monthly_listeners,
        short.shown.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        short.text,
    )
}

/** The photo's height below the status bar. */
internal val ArtistHeroPhotoHeight = 340.dp

/**
 * Artist Profile hero, Spotify-style: the artist's header photo full width and
 * edge to edge (under the status bar), and everything else set on it. Over the
 * photo's bottom edge, where it fades into the page: the name large and full
 * width, and the monthly listeners under it; then the plum play coin and the radio
 * ring on the left, the gold "♥ Support" chip (Bandcamp → website, see
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
    /** The artist's album covers: the banner when there's no header photo anywhere. */
    fallbackCovers: List<String> = emptyList(),
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
    val links = remember(socials) { headerLinks(socials) }
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
            if (photo == null && fallbackCovers.isNotEmpty()) {
                // No header photo on YouTube or Spotify: the artist's own covers, side by side.
                Row(Modifier.fillMaxSize()) {
                    fallbackCovers.forEach { cover ->
                        AsyncImage(
                            model = ArtUrlUpgrader.upgrade(cover),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
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
            // "1.7M monthly listeners" under the name, full width, as on Spotify: beside Support
            // it no longer fit once it said what it counts. The line is there from the first
            // frame, empty until the number arrives (a beat after the page on a first visit), so
            // its landing never moves the name; an artist with no number keeps the quiet gap.
            Text(
                text = monthlyListenersLabel(hero.monthlyListeners),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            // Play and radio on the left; on the right, Support with the artist's links
            // under it. The links arrive a beat after the page (MusicBrainz);
            // their line is held open from the start, so the row never grows and the name never
            // moves. Not a fixed row height: with a large font, that squashed the coins flat.
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
                Spacer(Modifier.width(16.dp))
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Box(Modifier.height(LinkCoinSize), contentAlignment = Alignment.CenterEnd) {
                    if (links.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            // Many links scroll; reversed so the row starts from its right end.
                            modifier = Modifier.horizontalScroll(rememberScrollState(), reverseScrolling = true),
                        ) {
                            links.forEach { social ->
                                Surface(
                                    onClick = { runCatching { uriHandler.openUri(social.url) } },
                                    shape = CircleShape,
                                    color = background.copy(alpha = 0.45f),
                                    border = BorderStroke(1.dp, ink.copy(alpha = 0.18f)),
                                    modifier = Modifier.size(LinkCoinSize),
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
