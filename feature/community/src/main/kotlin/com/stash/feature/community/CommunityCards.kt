package com.stash.feature.community

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.core.model.community.CommunityPost
import com.stash.core.model.share.ShareConfig
import com.stash.core.ui.ambient.Ground
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.core.ui.ambient.rememberCoverRoles
import com.stash.core.ui.components.BlurredCoverBackdrop
import com.stash.core.ui.components.HomeRailCardSize
import com.stash.core.ui.components.HomeRailCoverShape
import com.stash.core.ui.components.ShimmerPlaceholder
import com.stash.core.ui.components.motion.pressScale
import com.stash.core.ui.theme.SpaceGrotesk
import com.stash.core.ui.theme.StashElevation
import com.stash.core.ui.theme.StashTheme

/*
 * Community's cards, in the app's own family: the hero is Daily Discover's and Your record's (the large shape,
 * the hero lift, its cover blurred under a scrim, the cover's own colour), the rail cards are Home's rail cards
 * (HomeRailCardSize, HomeRailCoverShape, press scale), and the states are glass cards.
 */

/** The hero's cover: the same size as a rail card's, so Home's section steps at one size. */
private val HeroCoverSize = 120.dp
private val HeroPadding = 14.dp

/** The skeleton's height: the hero with one-line text (the cover and its padding, rounded up to the text column's). */
private val HeroSkeletonHeight = 164.dp

/**
 * This week's #1 (Home and See all ▸ Top): its cover large on the left, under it the cover blurred, then "#1 this
 * week" in the cover's ink, the title, what it is, who posted it and when, and the vote pill in the cover's colour.
 * The whole card opens the post. Dark in every theme, like the other heroes.
 */
@Composable
internal fun CommunityHero(post: CommunityPost, now: Long, onOpen: () -> Unit, onVote: (Int) -> Unit, modifier: Modifier = Modifier) {
    // Only a cover host's link is ever loaded, for the backdrop and its colour too.
    val art = post.coverUrls().firstOrNull(ShareConfig::isAllowedCover)
    val roles = rememberCoverRoles(art, Ground.DARK)
    val shape = MaterialTheme.shapes.large
    Box(
        modifier
            .fillMaxWidth()
            .shadow(StashElevation.Hero, shape, clip = false)
            .clip(shape)
            .clickable(onClick = onOpen),
    ) {
        BlurredCoverBackdrop(model = art, modifier = Modifier.matchParentSize())
        Row(Modifier.fillMaxWidth().padding(HeroPadding), verticalAlignment = Alignment.CenterVertically) {
            CoverArt(post.coverUrls(), Modifier.size(HeroCoverSize), corner = 10.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.community_hero_eyebrow),
                    style = MaterialTheme.typography.labelMedium,
                    color = roles.ink,
                    maxLines = 1,
                )
                Text(
                    post.title,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 22.sp),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    postFacts(post),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(post.name, ageText(ageOf(post.createdAt, now))).joinToString(SEP),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                VotePill(post, onVote, colors = VotePillDefaults.onArt(roles))
            }
        }
    }
}

/** The rank over a rail card's cover: big Space Grotesk, white with a soft shadow so it reads on a pale cover. */
private val RankNumeral = TextStyle(
    fontFamily = SpaceGrotesk,
    fontWeight = FontWeight.Bold,
    fontSize = 40.sp,
    lineHeight = 40.sp,
    letterSpacing = (-1).sp,
    shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), blurRadius = 10f),
)

/** #2–#5 on Home: a rail card with the rank over the cover's corner, the title, who posted it and a small vote pill. */
@Composable
internal fun CommunityRailCard(rank: Int, post: CommunityPost, onOpen: () -> Unit, onVote: (Int) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(HomeRailCardSize)
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen),
    ) {
        Box {
            CoverArt(
                post.coverUrls(),
                Modifier.size(HomeRailCardSize).border(1.dp, StashTheme.extendedColors.glassBorder, HomeRailCoverShape),
                corner = 10.dp,
            )
            // A dark foot under the rank, so a white cover keeps it.
            Box(
                Modifier
                    .matchParentSize()
                    .clip(HomeRailCoverShape)
                    .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.5f))),
            )
            Text(
                "$rank",
                style = RankNumeral,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                post.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (post.mine) YouTag()
        }
        Text(
            post.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        VotePill(post, onVote, compact = true)
    }
}

/** The rail's last card: post something (the picker). Home's section's only `+`. */
@Composable
internal fun ShareCard(onPost: () -> Unit) {
    val chrome = LocalAlbumChrome.current
    val ext = StashTheme.extendedColors
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(HomeRailCardSize)
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onPost),
    ) {
        Box(
            Modifier
                .size(HomeRailCardSize)
                .clip(HomeRailCoverShape)
                .background(ext.glassBackground)
                .border(1.dp, ext.glassBorderBright, HomeRailCoverShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(chrome.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = chrome.onAccent, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.community_share_card_title), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        Text(
            stringResource(R.string.community_share_card_body),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

/**
 * Top this week / New / Your posts: one glass track, the chosen one filled in the album colour (as the app's
 * chips are). Each takes a share of the width by its label's length, so a long one doesn't wrap at a large font.
 */
@Composable
internal fun SegmentedTabs(selected: Segment, onSelect: (Segment) -> Unit, modifier: Modifier = Modifier) {
    val chrome = LocalAlbumChrome.current
    val ext = StashTheme.extendedColors
    Row(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(ext.glassBackground)
            .border(1.dp, ext.glassBorder, CircleShape)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        for (segment in Segment.entries) {
            val label = stringResource(segment.label)
            val on = segment == selected
            val fill by animateColorAsState(if (on) chrome.accent else Color.Transparent, tween(SEGMENT_FADE_MS), label = "segmentFill")
            val ink by animateColorAsState(
                if (on) chrome.onAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                tween(SEGMENT_FADE_MS),
                label = "segmentInk",
            )
            Box(
                Modifier
                    .weight(maxOf(label.length, 6).toFloat())
                    .heightIn(min = 36.dp)
                    .clip(CircleShape)
                    .background(fill)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(segment) })
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** See all's top-bar "+ Post": a glass pill. */
@Composable
internal fun PostPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ext = StashTheme.extendedColors
    Row(
        modifier
            .clip(CircleShape)
            .background(ext.glassBackground)
            .border(1.dp, ext.glassBorderBright, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, tint = LocalAlbumChrome.current.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.community_post), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** What an empty or failed list shows: a glass card with an icon, a line, a quieter line and one action. */
@Composable
internal fun StateCard(icon: ImageVector, title: String, body: String, action: String?, onAction: (() -> Unit)?, modifier: Modifier = Modifier) {
    val chrome = LocalAlbumChrome.current
    val ext = StashTheme.extendedColors
    val shape = MaterialTheme.shapes.large
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ext.glassBackground)
            .border(1.dp, ext.glassBorder, shape)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(chrome.wash), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = chrome.ink, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .heightIn(min = 40.dp)
                    .clip(CircleShape)
                    .background(chrome.accent)
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(action, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = chrome.onAccent)
            }
        }
    }
}

/** Nothing loaded and the last try failed. */
@Composable
internal fun ErrorCard(onRetry: () -> Unit, modifier: Modifier = Modifier) = StateCard(
    icon = Icons.Rounded.CloudOff,
    title = stringResource(R.string.community_error_title),
    body = stringResource(R.string.community_error_body),
    action = stringResource(R.string.community_retry),
    onAction = onRetry,
    modifier = modifier,
)

/** Loaded and empty: on Your posts, "You have no posts up"; elsewhere "Nothing posted this week yet · Be the first". */
@Composable
internal fun EmptyCard(segment: Segment?, onPost: () -> Unit, modifier: Modifier = Modifier) {
    if (segment == Segment.MINE) {
        StateCard(
            icon = Icons.Rounded.LibraryMusic,
            title = stringResource(R.string.community_mine_empty_title),
            body = stringResource(R.string.community_mine_empty_body),
            action = stringResource(R.string.community_mine_empty_action),
            onAction = onPost,
            modifier = modifier,
        )
    } else {
        StateCard(
            icon = Icons.Rounded.Forum,
            title = stringResource(if (segment == Segment.NEW) R.string.community_new_empty_title else R.string.community_empty_title),
            body = stringResource(R.string.community_empty_body),
            action = stringResource(R.string.community_empty_action),
            onAction = onPost,
            modifier = modifier,
        )
    }
}

/** The hero while the first list loads. */
@Composable
internal fun HeroSkeleton(modifier: Modifier = Modifier) {
    ShimmerPlaceholder(modifier.fillMaxWidth().height(HeroSkeletonHeight), shape = MaterialTheme.shapes.large)
}

/** Three rail cards while Home's list loads. */
@Composable
internal fun RailSkeleton(modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            Column {
                ShimmerPlaceholder(Modifier.size(HomeRailCardSize), shape = HomeRailCoverShape)
                Spacer(Modifier.height(8.dp))
                ShimmerPlaceholder(Modifier.width(92.dp).height(12.dp), shape = MaterialTheme.shapes.extraSmall)
                Spacer(Modifier.height(6.dp))
                ShimmerPlaceholder(Modifier.width(56.dp).height(10.dp), shape = MaterialTheme.shapes.extraSmall)
            }
        }
    }
}

/** A list row while See all loads: the cover, three lines and the pill, where the row's own will be. */
@Composable
internal fun RowSkeleton() {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShimmerPlaceholder(Modifier.size(RowCoverSize), shape = MaterialTheme.shapes.small)
        Column(Modifier.weight(1f)) {
            ShimmerPlaceholder(Modifier.width(150.dp).height(14.dp), shape = MaterialTheme.shapes.extraSmall)
            Spacer(Modifier.height(8.dp))
            ShimmerPlaceholder(Modifier.width(110.dp).height(10.dp), shape = MaterialTheme.shapes.extraSmall)
            Spacer(Modifier.height(6.dp))
            ShimmerPlaceholder(Modifier.width(72.dp).height(10.dp), shape = MaterialTheme.shapes.extraSmall)
        }
        ShimmerPlaceholder(Modifier.width(96.dp).height(36.dp), shape = CircleShape)
    }
}


private const val SEGMENT_FADE_MS = 180
