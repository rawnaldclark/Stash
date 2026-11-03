package com.stash.feature.community

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.stash.core.ui.util.ArtSizeInterceptor
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.material3.ripple
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
 * The chart's #1 (Home and See all ▸ Top): its cover large on the left, under it the cover blurred, then "#1 this
 * week" ([thisWeek]; "#1" when the chart reached past the week), the title, what it is, who posted it and when (YOU
 * on your own), and the vote pill in the cover's colour. The whole card opens the post. Dark in every theme, like
 * the other heroes; its own backdrop (below) keeps the white text readable over a white cover.
 */
@Composable
internal fun CommunityHero(
    post: CommunityPost,
    now: Long,
    thisWeek: Boolean,
    onOpen: () -> Unit,
    onVote: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only a cover host's link is ever loaded, for the backdrop and its colour too.
    val art = post.coverUrls().firstOrNull(ShareConfig::isAllowedCover)
    val roles = rememberCoverRoles(art, Ground.DARK)
    val shape = MaterialTheme.shapes.large
    val res = words()
    Box(
        modifier
            .fillMaxWidth()
            .shadow(StashElevation.Hero, shape, clip = false)
            .clip(shape)
            .clickable(onClick = onOpen),
    ) {
        HeroBackdrop(art, Modifier.matchParentSize())
        Row(Modifier.fillMaxWidth().padding(HeroPadding), verticalAlignment = Alignment.CenterVertically) {
            CoverArt(post.coverUrls(), Modifier.size(HeroCoverSize), corner = 10.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (thisWeek) R.string.community_hero_eyebrow else R.string.community_hero_eyebrow_all),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
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
                    res.postFacts(post),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = HERO_FACTS_ALPHA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (post.mine) YouTag(Color.White)
                    val spoken = res.posterLine(post, now, long = true)
                    Text(
                        res.posterLine(post, now),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = HERO_POSTER_ALPHA),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { contentDescription = spoken },
                    )
                }
                Spacer(Modifier.height(10.dp))
                // "Listen first" beside the pill: the hero has the room, and stays its height.
                VotePill(post, onVote, colors = VotePillDefaults.onArt(roles), hintBeside = true)
            }
        }
    }
}

/**
 * The hero's ground: its cover blurred under an even black layer ([HERO_SCRIM_ALPHA]), not the Discover hero's
 * left-to-right scrim. That one is lightest on the right, where this card's text sits, and over a pale cover the
 * small lines fell under 4.5:1 (sampled on the phone, 2026-11-01). No cover: Stash's purple, as the other heroes.
 */
@Composable
private fun HeroBackdrop(art: String?, modifier: Modifier) {
    if (art == null) {
        BlurredCoverBackdrop(model = null, modifier = modifier)
        return
    }
    Box(modifier) {
        // As BlurredCoverBackdrop: a small decode is all a 24 dp blur needs, from the same URL as the crisp cover.
        val context = LocalContext.current
        val request = remember(context, art) {
            ImageRequest.Builder(context)
                .data(art)
                .size(HERO_BACKDROP_DECODE_PX)
                .apply { extras[ArtSizeInterceptor.SkipSizing] = true }
                .build()
        }
        AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(24.dp))
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = HERO_SCRIM_ALPHA)))
    }
}

private const val HERO_BACKDROP_DECODE_PX = 256

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
internal fun SegmentedTabs(selected: Segment, onSelect: (Segment) -> Unit, modifier: Modifier = Modifier, topThisWeek: Boolean = true) {
    val chrome = LocalAlbumChrome.current
    val ext = StashTheme.extendedColors
    Row(
        modifier
            .fillMaxWidth()
            // Painted, not clipped: a clip would cut the segments' 48 dp tap areas, which reach past the track.
            .background(ext.glassBackground, CircleShape)
            .border(1.dp, ext.glassBorder, CircleShape)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        for (segment in Segment.entries) {
            // "Top this week", or "Top" when the chart reached past the week (see topChart).
            val label = stringResource(if (segment == Segment.TOP && !topThisWeek) R.string.community_segment_top_all else segment.label)
            val on = segment == selected
            val fill by animateColorAsState(if (on) chrome.accent else Color.Transparent, tween(SEGMENT_FADE_MS), label = "segmentFill")
            val ink by animateColorAsState(
                if (on) chrome.onAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                tween(SEGMENT_FADE_MS),
                label = "segmentInk",
            )
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .weight(maxOf(label.length, 6).toFloat())
                    .height(SegmentHeight)
                    // A 48 dp tap area over the 36 dp segment, reaching above and below the track.
                    .wrapContentHeight(unbounded = true)
                    .height(MinTouch)
                    .selectable(selected = on, interactionSource = interaction, indication = null, role = Role.Tab, onClick = { onSelect(segment) }),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(SegmentHeight)
                        .clip(CircleShape)
                        .background(fill)
                        .indication(interaction, ripple())
                        .padding(horizontal = 8.dp),
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
}

/** A segment's height (its track is 3 dp more on each side); its tap area is [MinTouch]. */
private val SegmentHeight = 36.dp

/** See all's top-bar "+ Post": a glass pill, in a 48 dp tap area (the top bar's own height, so it costs no room). */
@Composable
internal fun PostPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ext = StashTheme.extendedColors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .heightIn(min = MinTouch)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clip(CircleShape)
                .background(ext.glassBackground)
                .border(1.dp, ext.glassBorderBright, CircleShape)
                .indication(interaction, ripple())
                .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = LocalAlbumChrome.current.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.community_post), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

/** What an empty or failed list shows: a glass card with an icon, a line, a quieter line and one action. */
@Composable
internal fun StateCard(
    icon: ImageVector,
    title: String,
    body: String,
    action: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** A quieter second action under the first (Home's error card: post anyway). */
    secondary: Pair<String, () -> Unit>? = null,
) {
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
            Spacer(Modifier.height(12.dp))
            // A 40 dp button in a 48 dp tap area, as Material's own buttons.
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .heightIn(min = MinTouch)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onAction),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .heightIn(min = 40.dp)
                        .clip(CircleShape)
                        .background(chrome.accent)
                        .indication(interaction, ripple())
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(action, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = chrome.onAccent)
                }
            }
        }
        if (secondary != null) {
            Box(
                Modifier
                    .heightIn(min = MinTouch)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = secondary.second)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(secondary.first, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = chrome.ink)
            }
        }
    }
}

/** Nothing loaded and the last try failed. */
@Composable
internal fun ErrorCard(onRetry: () -> Unit, modifier: Modifier = Modifier, onPost: (() -> Unit)? = null) = StateCard(
    icon = Icons.Rounded.CloudOff,
    title = stringResource(R.string.community_error_title),
    body = stringResource(R.string.community_error_body),
    action = stringResource(R.string.community_retry),
    onAction = onRetry,
    modifier = modifier,
    secondary = onPost?.let { stringResource(R.string.community_mine_empty_action) to it },
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

/** Rail cards while Home's list loads, then the real Share card: posting never waits on the list. */
@Composable
internal fun RailSkeleton(onPost: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(2) {
            Column {
                ShimmerPlaceholder(Modifier.size(HomeRailCardSize), shape = HomeRailCoverShape)
                Spacer(Modifier.height(8.dp))
                ShimmerPlaceholder(Modifier.width(92.dp).height(12.dp), shape = MaterialTheme.shapes.extraSmall)
                Spacer(Modifier.height(6.dp))
                ShimmerPlaceholder(Modifier.width(56.dp).height(10.dp), shape = MaterialTheme.shapes.extraSmall)
            }
        }
        ShareCard(onPost)
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
        ShimmerPlaceholder(Modifier.width(76.dp).height(32.dp), shape = CircleShape)
    }
}


private const val SEGMENT_FADE_MS = 180
