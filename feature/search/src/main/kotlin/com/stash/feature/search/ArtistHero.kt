package com.stash.feature.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/**
 * Artist Profile hero — editorial split (the hub redesign).
 *
 * Circular avatar LEFT with the name + listener count beside it (a magazine
 * byline), then the app's own action vocabulary in place of the old stock
 * buttons: a solid plum play coin + a hairline radio ring (the Daily-Discover
 * FAB+ring language), with the gold "♥ Support" chip on the right
 * (Bandcamp → website, see [supportUrl]). Below, the artist's doors — one
 * glass coin per social link (brand icons via [socialIconFor], globe
 * fallback). Every element is optional-data-safe: no socials → no coins,
 * no support link → no chip, no listener count → hidden.
 *
 * Paints on the first frame from nav args (see [ArtistProfileViewModel]
 * init) so the < 50 ms hero-paint target is met before the cache emits.
 */
@Composable
fun ArtistHero(
    hero: HeroState,
    @Suppress("UNUSED_PARAMETER") status: ArtistProfileStatus,
    socials: List<SocialLink>,
    onBack: () -> Unit,
    onPlayArtist: () -> Unit,
    onStartRadio: () -> Unit,
    streamingEnabled: Boolean,
    onStreamingClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val uriHandler = LocalUriHandler.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val plum = if (dark) Color(0xFF7E6A90) else Color(0xFF6E5A7E)
    val cream = Color(0xFFF2ECE2)
    val gold = Color(0xFFFFC947)
    val goldInk = if (dark) gold else Color(0xFF7A5A00)
    val support = remember(socials) { supportUrl(socials) }

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
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Top padding clears the overlaid back arrow / streaming chip.
                .padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 16.dp),
        ) {
            // -- Byline: avatar left, name + listeners beside ------------------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AsyncImage(
                    model = ArtUrlUpgrader.upgrade(hero.avatarUrl),
                    contentDescription = hero.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(84.dp).clip(CircleShape),
                )
                Column {
                    Text(
                        text = hero.name,
                        style = MaterialTheme.typography.headlineSmall,
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
            }

            Spacer(Modifier.height(14.dp))

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

        // Top-left back arrow (Scaffold already applies status-bar padding).
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }

        // Top-right Online/Offline chip — flip playback mode from the profile.
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
