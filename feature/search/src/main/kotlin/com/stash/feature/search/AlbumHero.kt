package com.stash.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.ui.theme.StashTheme
import com.stash.core.ui.util.formatTotalDuration

/**
 * Album Discovery hero card.
 *
 * Renders a square cover image (with gradient-placeholder fallback), a
 * bottom-edge scrim for text readability, a glass-background back button,
 * and a metadata block (title / artist+year / track-count+duration) followed
 * by an action-chip row.
 *
 * The Shuffle chip only renders when [hasDownloaded] is `true` (i.e. at
 * least one track in the album has a local file on disk) — shuffling an
 * album that has no downloaded tracks yet is a no-op, so we hide the
 * affordance until it would do something useful. Download-All is always
 * rendered; the dialog + confirm flow lives in [AlbumDiscoveryScreen] /
 * [AlbumDiscoveryViewModel].
 *
 * Visual/structure cribbed from `feature/library`'s private
 * `AlbumDetailHeader` so the two album pages feel like siblings. Parallax /
 * scroll-collapse is explicitly deferred (see spec §12).
 *
 * @param hero          Hero state (title/artist/thumbnail/year/track-count/
 *                      totalDuration). See [AlbumHeroState].
 * @param hasDownloaded Whether >= 1 of the album's tracks has already been
 *                      downloaded. Controls visibility of the Shuffle chip.
 * @param onBack        Invoked when the back arrow is tapped.
 * @param onShuffle     Invoked when the Shuffle chip is tapped. Only
 *                      reachable when [hasDownloaded] is `true`.
 * @param onDownloadAll Invoked when the Download-All chip is tapped. The
 *                      screen owns dialog + confirm wiring.
 */
@Composable
fun AlbumHero(
    hero: AlbumHeroState,
    hasDownloaded: Boolean,
    onBack: () -> Unit,
    onShuffle: () -> Unit,
    onDownloadAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors

    Column(modifier = modifier.fillMaxWidth()) {
        // -- Square artwork with back button + bottom scrim --
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            if (hero.thumbnailUrl != null) {
                AsyncImage(
                    model = hero.thumbnailUrl,
                    contentDescription = "${hero.title} artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Gradient placeholder with album icon
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f),
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                    )
                }
            }

            // Bottom gradient scrim for text readability
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background,
                            ),
                        ),
                    ),
            )

            // Back button (glass-background circle, top-left)
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(8.dp)
                    .align(Alignment.TopStart)
                    .size(48.dp)
                    .background(
                        color = extendedColors.glassBackground,
                        shape = CircleShape,
                    ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // -- Metadata + action chips --
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = hero.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = buildString {
                    append(hero.artist)
                    if (hero.year != null) {
                        append(" \u2022 ")
                        append(hero.year)
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (hero.trackCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(hero.trackCount)
                        append(" track")
                        if (hero.trackCount != 1) append("s")
                        if (hero.totalDurationMs > 0) {
                            append(" \u2022 ")
                            append(formatTotalDuration(hero.totalDurationMs))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hasDownloaded) {
                    OutlinedButton(
                        onClick = onShuffle,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Shuffle",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                Button(
                    onClick = onDownloadAll,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Download all",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
