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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stash.core.model.community.CommunityPost
import com.stash.core.ui.ambient.AccentRoles
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.core.ui.theme.StashTheme

/** The pill's paints: idle, lit (this phone's vote) and inert (your own post). */
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
) {
    val ui = post.votePill()
    val lit = ui.upLit || ui.downLit
    val fill by animateColorAsState(if (lit) colors.litFill else colors.fill, tween(PILL_FADE_MS), label = "pillFill")
    val border by animateColorAsState(if (lit) colors.litBorder else colors.border, tween(PILL_FADE_MS), label = "pillBorder")
    val height = if (compact) 32.dp else 36.dp
    val arrowWidth = if (compact) 30.dp else 40.dp
    Row(
        modifier = modifier
            .height(height)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, border, CircleShape)
            .then(if (ui.enabled) Modifier.pointerInput(Unit) { detectTapGestures { } } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillArrow(
            icon = Icons.Rounded.KeyboardArrowUp,
            label = stringResource(R.string.community_vote_up),
            lit = ui.upLit,
            enabled = ui.enabled,
            colors = colors,
            width = arrowWidth,
            height = height,
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
            colors = colors,
            width = arrowWidth,
            height = height,
        ) { onVote(-1) }
    }
}

/** One arrow's tap area, the pill's full height. Disabled (your own post), it's dimmed, silent to TalkBack and lets taps through. */
@Composable
private fun PillArrow(
    icon: ImageVector,
    label: String,
    lit: Boolean,
    enabled: Boolean,
    colors: VotePillColors,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        when {
            !enabled -> colors.inertIcon
            lit -> colors.litIcon
            else -> colors.icon
        },
        tween(PILL_FADE_MS),
        label = "pillArrow",
    )
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(50))
            // Not selectable(enabled = false): a disabled one still swallows the tap, which should open the post.
            .then(if (enabled) Modifier.selectable(selected = lit, role = Role.Button, onClick = onClick) else Modifier.clearAndSetSemantics {}),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(if (height < 34.dp) 20.dp else 22.dp))
    }
}

/** The pill's colour change and the score's roll (the tab bar's colour step). */
private const val PILL_FADE_MS = 180
