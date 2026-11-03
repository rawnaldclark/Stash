package com.stash.feature.community

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stash.core.model.community.CommunityPost
import com.stash.core.ui.ambient.AccentRoles
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.core.ui.theme.StashTheme

/** The pill's paints: idle, lit (this phone's vote) and inert (your own post, or an arrow waiting for a listen). */
@Immutable
internal data class VotePillColors(
    val fill: Color,
    val border: Color,
    val icon: Color,
    val text: Color,
    val litFill: Color,
    val litBorder: Color,
    val litIcon: Color,
    val litText: Color,
    val inertIcon: Color,
    /** The "Listen first" hint. */
    val hint: Color,
)

internal object VotePillDefaults {
    /**
     * On the page (a list row, a rail card, the post's page): quiet glass with a hairline, and the app's album colour
     * when voted (as the chips are). The hairline is the faint one: on the phone (2026-11-01) the bright one made a
     * column of idle pills read as a stack of outlined buttons, heavier than the posts beside them.
     */
    @Composable
    fun onPage(): VotePillColors {
        val ext = StashTheme.extendedColors
        val chrome = LocalAlbumChrome.current
        return VotePillColors(
            fill = ext.glassBackground,
            border = ext.glassBorder,
            icon = MaterialTheme.colorScheme.onSurfaceVariant,
            text = MaterialTheme.colorScheme.onSurface,
            litFill = chrome.wash,
            litBorder = chrome.accent.copy(alpha = 0.55f),
            litIcon = chrome.accent,
            litText = chrome.ink,
            inertIcon = ext.textTertiary.copy(alpha = 0.38f),
            hint = ext.textTertiary,
        )
    }

    /** On a cover's blurred backdrop (the hero, always dark): white glass, and the cover's own colour when voted. */
    fun onArt(roles: AccentRoles) = VotePillColors(
        fill = Color.White.copy(alpha = 0.12f),
        border = Color.White.copy(alpha = 0.18f),
        icon = Color.White.copy(alpha = 0.82f),
        text = Color.White,
        litFill = roles.accent.copy(alpha = 0.30f),
        litBorder = roles.accent.copy(alpha = 0.75f),
        litIcon = Color.White,
        litText = Color.White,
        inertIcon = Color.White.copy(alpha = 0.3f),
        hint = Color.White.copy(alpha = HERO_POSTER_ALPHA),
    )
}

/**
 * ▲ score ▼ in one horizontal capsule (spec 2026-09-26 §3 Votes, redrawn 2026-11-01). Your vote lights the pill
 * in the album colour; tapping it again takes it back ([CommunityPost.nextVote] in the view model). On your own
 * post the arrows are dimmed and silent to TalkBack, and the score alone stays.
 *
 * Taps on the pill never reach the row or card under it: a near miss on an arrow shouldn't open the post. Your own
 * post's pill takes no taps at all, so a tap on it opens the post as before.
 *
 * Until the post is heard (30 s played from it, contract 2026-11-03) the arrows that would vote are dimmed and a small
 * "Listen first" shows under the pill, or beside it ([hintBeside]). A dimmed arrow still takes the tap and hands it to
 * [onVote], whose view model shows why instead of voting; your current vote stays lit and can be taken back.
 *
 * @param compact the smaller pill of list rows and rail cards (32 dp high instead of 36, narrower arrows), so the
 *   title beside it keeps its room.
 */
@Composable
internal fun VotePill(
    post: CommunityPost,
    onVote: (Int) -> Unit,
    modifier: Modifier = Modifier,
    colors: VotePillColors = VotePillDefaults.onPage(),
    compact: Boolean = false,
    hintBeside: Boolean = false,
) {
    val ui = post.votePill()
    if (!ui.listenFirst) {
        Pill(ui, onVote, modifier, colors, compact)
        return
    }
    val hint: @Composable () -> Unit = {
        Text(
            stringResource(R.string.community_listen_first),
            style = MaterialTheme.typography.labelSmall,
            color = colors.hint,
            maxLines = 1,
            softWrap = false,
            // The dimmed arrows say it to TalkBack.
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
    if (hintBeside) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(ui, onVote, Modifier, colors, compact)
            hint()
        }
    } else {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Pill(ui, onVote, Modifier, colors, compact)
            hint()
        }
    }
}

/** The capsule itself: ▲ score ▼. */
@Composable
private fun Pill(ui: VotePillUi, onVote: (Int) -> Unit, modifier: Modifier, colors: VotePillColors, compact: Boolean) {
    val lit = ui.upLit || ui.downLit
    val fill by animateColorAsState(if (lit) colors.litFill else colors.fill, tween(PILL_FADE_MS), label = "pillFill")
    val border by animateColorAsState(if (lit) colors.litBorder else colors.border, tween(PILL_FADE_MS), label = "pillBorder")
    val height = if (compact) 32.dp else 36.dp
    val arrowWidth = if (compact) 30.dp else 40.dp
    Row(
        modifier = modifier
            .height(height)
            // Painted, not clipped: a clip would also cut the arrows' 48 dp tap areas, which reach past the pill.
            .background(fill, CircleShape)
            .border(1.dp, border, CircleShape)
            .then(if (ui.enabled) Modifier.pointerInput(Unit) { detectTapGestures { } } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val waiting = stringResource(R.string.community_listen_first)
        PillArrow(
            icon = Icons.Rounded.KeyboardArrowUp,
            label = stringResource(R.string.community_vote_up),
            lit = ui.upLit,
            enabled = ui.enabled,
            dimmed = if (ui.upDimmed) waiting else null,
            colors = colors,
            width = arrowWidth,
            height = height,
            // The up arrow's tap area reaches out past the pill's start, never in over the score.
            inner = Alignment.CenterEnd,
        ) { onVote(1) }
        val votes = pluralStringResource(R.plurals.community_votes, ui.score, ui.score)
        // The number rolls up for a vote up and down for a vote down.
        AnimatedContent(
            targetState = ui.score,
            transitionSpec = {
                val up = targetState > initialState
                (slideInVertically(tween(PILL_FADE_MS)) { h -> if (up) h else -h } + fadeIn(tween(PILL_FADE_MS)))
                    .togetherWith(slideOutVertically(tween(PILL_FADE_MS)) { h -> if (up) -h else h } + fadeOut(tween(PILL_FADE_MS)))
            },
            contentAlignment = Alignment.Center,
            label = "pillScore",
            modifier = Modifier.semantics { contentDescription = votes },
        ) { score ->
            Text(
                text = "$score",
                style = (if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge)
                    .copy(fontWeight = FontWeight.SemiBold),
                color = if (lit) colors.litText else colors.text,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.widthIn(min = if (compact) 14.dp else 18.dp).clearAndSetSemantics { },
            )
        }
        PillArrow(
            icon = Icons.Rounded.KeyboardArrowDown,
            label = stringResource(R.string.community_vote_down),
            lit = ui.downLit,
            enabled = ui.enabled,
            dimmed = if (ui.downDimmed) waiting else null,
            colors = colors,
            width = arrowWidth,
            height = height,
            inner = Alignment.CenterStart,
        ) { onVote(-1) }
    }
}

/**
 * One arrow: [width] × [height] of the pill, with a 48 dp tap area. The tap area is bigger than the arrow's part of
 * the pill and reaches outward from it ([inner] is the side facing the score, where it stops), past the pill's ends
 * and above and below it, without taking any room in the layout. The ripple stays on the arrow's own part.
 * Disabled (your own post), it's dimmed, silent to TalkBack and lets taps through. [dimmed] (waiting for a listen),
 * it's dimmed too but still takes taps, and TalkBack hears [dimmed] after its label.
 */
@Composable
private fun PillArrow(
    icon: ImageVector,
    label: String,
    lit: Boolean,
    enabled: Boolean,
    dimmed: String?,
    colors: VotePillColors,
    width: Dp,
    height: Dp,
    inner: Alignment,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        when {
            !enabled || dimmed != null -> colors.inertIcon
            lit -> colors.litIcon
            else -> colors.icon
        },
        tween(PILL_FADE_MS),
        label = "pillArrow",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            // The tap area: 48 dp, laid out over the arrow's slot without growing it.
            .wrapContentSize(inner, unbounded = true)
            .size(width = maxOf(width, MinTouch), height = maxOf(height, MinTouch))
            // Not selectable(enabled = false): a disabled one still swallows the tap, which should open the post.
            .then(
                if (enabled) {
                    Modifier
                        .selectable(selected = lit, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                        .then(if (dimmed != null) Modifier.semantics { stateDescription = dimmed } else Modifier)
                } else {
                    Modifier.clearAndSetSemantics {}
                },
            ),
        contentAlignment = inner,
    ) {
        Box(
            Modifier.size(width = width, height = height).clip(RoundedCornerShape(50)).indication(interaction, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(if (height < 34.dp) 20.dp else 22.dp))
        }
    }
}

/** The smallest tap area (Material's and Android's 48 dp). */
internal val MinTouch = 48.dp

/** The pill's colour change and the score's roll (the tab bar's colour step). */
private const val PILL_FADE_MS = 180
