package com.stash.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.stash.core.common.ArtUrlUpgrader
import com.stash.core.ui.ambient.AccentRoles
import com.stash.core.ui.ambient.Ground
import com.stash.core.ui.ambient.rememberCoverRoles
import com.stash.core.ui.theme.StashElevation
import com.stash.core.ui.theme.StashTheme

/**
 * The single "for you" moment on Home: the tailored daily discovery, and the user's own mixes as
 * sibling pages. A compact glass card tinted with its cover's colour (the Recap door's family): the
 * cover, an eyebrow in the cover's ink, the title and a supporting line, and a play disc in the
 * cover's accent. Whole-card tap opens the playlist; the disc plays it.
 *
 * Data-agnostic. When [loading] is true a shimmer block of the same size stands in (skeleton while
 * the Daily Discover playlist first materializes). The caller omits the card when there is no
 * discovery to show; this component always renders something (shimmer or card).
 *
 * @param label    Small eyebrow (e.g. "Updated daily"). Not the title again: the text column is narrow.
 * @param title    Card title (e.g. "Daily Discover"), up to two lines.
 * @param subtitle Supporting line (track count, build state).
 * @param artUrl   Cover art URL: the crisp cover, and the colour the card is tinted with. The brand
 *   purple and a note glyph stand in when null.
 * @param onPlay   Invoked when the play disc is tapped.
 * @param onOpen   Invoked when the card body is tapped (open the playlist).
 * @param onCreateMix Optional: when non-null a small "+" ring sits under the play disc; tapping it
 *   starts a new mix. Omitted callers show no "+".
 * @param onLongPress Optional long-press on the card body (the pager's Your-mix pages use it to open
 *   the mix action sheet).
 * @param loading  When true, render a shimmer skeleton instead of the card body.
 */
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
    if (loading) {
        ShimmerPlaceholder(
            modifier = modifier.fillMaxWidth().height(DiscoverHeroHeight),
            shape = MaterialTheme.shapes.large,
        )
        return
    }
    val art = ArtUrlUpgrader.upgrade(artUrl)
    DiscoverHeroFrame(
        roles = rememberCoverRoles(art, Ground.DARK),
        backdrop = art,
        modifier = modifier,
        onClick = onOpen,
        onLongClick = onLongPress,
    ) { roles ->
        Box(
            modifier = Modifier
                .size(DiscoverHeroCoverSize)
                .clip(DiscoverHeroCoverShape)
                .background(StashTheme.extendedColors.elevatedSurface),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = roles.ink,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        DiscoverHeroText(
            eyebrow = label,
            title = title,
            body = subtitle,
            roles = roles,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(roles.accent)
                    .clickable(role = Role.Button, onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Play $title",
                    tint = roles.onAccent,
                    modifier = Modifier.size(26.dp),
                )
            }
            if (onCreateMix != null) {
                // The ring is small, but its tap area spans the play disc's width and the rest of
                // the cover's height (44 x 36 dp).
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 36.dp)
                        .clickable(
                            interactionSource = null,
                            indication = ripple(bounded = false, radius = 18.dp),
                            role = Role.Button,
                            onClick = onCreateMix,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.85f)), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Create a mix",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The hero's frame, shared with the Daily Discover "Building…" card so the swap between them is
 * quiet: fixed height, the large shape, its cover blurred under a dark scrim, no ring. [backdrop] is
 * the cover model (null for the brand's purple gradient). The content is drawn over the backdrop.
 *
 * @param backdrop The cover model for [BlurredCoverBackdrop]; null for the brand gradient.
 * @param onClick Whole-card tap; null leaves the frame inert (the "Building…" card).
 * @param onLongClick Whole-card long-press, only with [onClick].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiscoverHeroFrame(
    roles: AccentRoles,
    backdrop: Any?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.(AccentRoles) -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val accent by animateColorAsState(roles.accent, tween(ROLE_CROSSFADE_MS), label = "heroAccent")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DiscoverHeroHeight)
            .shadow(StashElevation.Hero, shape, clip = false)
            .clip(shape)
            .then(
                if (onClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick) else Modifier,
            ),
    ) {
        BlurredCoverBackdrop(model = backdrop, modifier = Modifier.matchParentSize())
        Row(
            modifier = Modifier.fillMaxSize().padding(DiscoverHeroPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content(roles.copy(accent = accent))
        }
    }
}

/**
 * The hero's text column: [eyebrow] in the cover's ink, [title] (two lines at most, so long mix
 * names wrap instead of truncating) and [body] (one line, omitted when empty) in the secondary colour.
 */
@Composable
fun DiscoverHeroText(
    eyebrow: String,
    title: String,
    body: String,
    roles: AccentRoles,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = eyebrow,
            style = MaterialTheme.typography.labelMedium,
            color = roles.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = title,
            style = discoverHeroTitleStyle(),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (body.isNotEmpty()) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Space Grotesk between titleMedium and titleLarge: two lines plus eyebrow and body fit the cover's height. */
@Composable
private fun discoverHeroTitleStyle() = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 22.sp)

/** The hero's height: the cover plus its padding (was 150 dp with a 118 dp cover). */
val DiscoverHeroHeight = 104.dp

/** The hero's cover: square, so it never stretches or wide-crops. */
val DiscoverHeroCoverSize = 80.dp

/** The cover's corners: the large card's 16 dp less its padding, so the two curves run parallel. */
val DiscoverHeroCoverShape = RoundedCornerShape(10.dp)

private val DiscoverHeroPadding = 12.dp

/** The cover-colour crossfade (the Recap door's). */
private const val ROLE_CROSSFADE_MS = 600
