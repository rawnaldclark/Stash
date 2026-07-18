package com.stash.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
 * @param onCreateMix Optional — when non-null a small "＋ ring" renders beneath the
 *   play button; tapping it starts a new mix. Omitted callers show no ＋.
 * @param onLongPress Optional — long-press on the card body (the hero pager's
 *   Your-mix pages use it to open the mix action sheet).
 * @param loading  When true, render a shimmer skeleton instead of the hero body.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiscoverHeroCard(
    label: String,
    title: String,
    subtitle: String,
    artUrl: String?,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onCreateMix: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
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
            .then(
                if (!loading) {
                    Modifier.combinedClickable(onClick = onOpen, onLongClick = onLongPress)
                } else {
                    Modifier
                }
            ),
    ) {
        if (loading) {
            ShimmerPlaceholder(modifier = Modifier.fillMaxSize(), shape = shape)
            return@Box
        }

        // Ambient backdrop: the cover, blurred + darkened (blur is a no-op below
        // API 31 — the scrim still carries it). Brand gradient when there's no art.
        if (artUrl != null) {
            AsyncImage(
                model = ArtUrlUpgrader.upgrade(artUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(24.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(StashPurpleDark, StashBackground))),
            )
        }
        // Scrim — darker on the left where the text sits, so it stays legible.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.6f), Color.Black.copy(alpha = 0.25f)),
                    ),
                ),
        )

        Row(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Crisp square cover — shown 1:1 so it never stretches or wide-crops.
            if (artUrl != null) {
                AsyncImage(
                    model = ArtUrlUpgrader.upgrade(artUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(118.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Surface(
                    onClick = onPlay,
                    shape = CircleShape,
                    color = Color.White,
                    modifier = Modifier.size(52.dp).shadow(6.dp, CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play $title",
                        tint = Color.Black,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                if (onCreateMix != null) {
                    Surface(
                        onClick = onCreateMix,
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.04f),
                        border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.30f)),
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Create a mix",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}
