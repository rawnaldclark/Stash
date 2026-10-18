package com.stash.app.navigation

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.core.ui.ambient.rememberReducedMotion
import com.stash.core.ui.components.chromeGlass
import com.stash.core.ui.theme.StashTheme

/*
 * The tab bar, after Namida's: Material 3's navigation bar with the label shown on the selected
 * tab only (Flutter's NavigationDestinationLabelBehavior.onlyShowSelected), 64dp tall, Iconsax
 * "Broken" icons. Selecting a tab plays Flutter's destination animation over Namida's 1 s: the
 * pill jumps to 40 % width and grows to full width from its centre, the icon rises to make room
 * and the label fades in under it; the tab being left plays the same in reverse. All of it on
 * the emphasized curve, so most of the move lands in the first ~300 ms and the rest settles.
 *
 * The bar itself is unchanged from before: Stash's frosted chrome glass running under the system
 * bar, with the web's 1px hairline on top; the selected tab wears the playing song's colour.
 */

/** Namida's NavigationBar(height: 64). */
private val NavBarHeight = 64.dp

/** Namida's NavigationBar(animationDuration: Duration(seconds: 1)). */
private const val NAV_ITEM_ANIM_MS = 1000

/** Flutter's Curves.easeInOutCubicEmphasized, as Material 3's emphasized easing. */
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Material 3's indicator: 64 x 32, fully rounded. */
private val IndicatorWidth = 64.dp
private val IndicatorHeight = 32.dp
private val IconSize = 24.dp

/** Icon top at rest (centred in the bar) and when selected (icon + gap + label centred). */
private val IconTopRest = (NavBarHeight - IconSize) / 2
private val IconTopSelected = 10.dp
private val LabelGap = 4.dp

/** The tab bar's glass is denser than the mini player's (70 % → 84 % on dark). */
private const val NAV_GLASS_TINT_SCALE = 1.2f

@Composable
fun StashNavigationBar(
    currentRoute: String?,
    onNavigate: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The web's 1px top line on the tab bar (rgba(255,255,255,.06) / the light glass border).
    val hairline = StashTheme.extendedColors.glassBorder
    Box(
        modifier
            .fillMaxWidth()
            .chromeGlass(ring = false, tintAlphaScale = NAV_GLASS_TINT_SCALE)
            .drawBehind {
                drawLine(
                    color = hairline,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(NavBarHeight)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TopLevelDestination.entries.forEach { dest ->
                StashNavItem(
                    dest = dest,
                    selected = currentRoute == dest.route::class.qualifiedName,
                    onClick = { onNavigate(dest) },
                )
            }
        }
    }
}

@Composable
private fun RowScope.StashNavItem(
    dest: TopLevelDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val extendedColors = StashTheme.extendedColors
    val chrome = LocalAlbumChrome.current
    val reducedMotion = rememberReducedMotion()
    // 0 = unselected, 1 = selected; eased in the direction of travel (Flutter's curve forward,
    // its flipped curve back).
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(if (reducedMotion) 0 else NAV_ITEM_ANIM_MS, easing = EmphasizedEasing),
        label = "navItem",
    )
    // Selected: the song's text ink while a song plays, the theme's neutral selected colour
    // otherwise (LocalAlbumChrome crossfades that over 800 ms). Unselected tabs stay neutral.
    val selectedInk = chrome.selectedTab
    val ink = lerp(extendedColors.textTertiary, selectedInk, progress)
    val interactions = remember { MutableInteractionSource() }

    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactions,
                indication = null,
            )
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.TopCenter,
    ) {
        val iconTop = { lerp(IconTopRest.value, IconTopSelected.value, progress).dp }
        // The pill: jumps to 40 % width as soon as the tab is selected, grows to full width from its
        // centre; on the way out it shrinks back to 40 % and then is gone.
        Box(
            Modifier
                .offset { IntOffset(0, (iconTop() - (IndicatorHeight - IconSize) / 2).roundToPx()) }
                .size(IndicatorWidth, IndicatorHeight)
                .graphicsLayer {
                    scaleX = if (progress <= 0f) 0f else lerp(0.4f, 1f, progress)
                }
                .background(selectedInk.copy(alpha = 0.16f), RoundedCornerShape(50)),
        )
        // The press ripple, on the pill's full shape (Material 3 draws it there).
        Box(
            Modifier
                .offset { IntOffset(0, (iconTop() - (IndicatorHeight - IconSize) / 2).roundToPx()) }
                .size(IndicatorWidth, IndicatorHeight)
                .clip(RoundedCornerShape(50))
                .indication(interactions, ripple()),
        )
        Icon(
            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
            contentDescription = null,
            tint = ink,
            modifier = Modifier
                .offset { IntOffset(0, iconTop().roundToPx()) }
                .size(IconSize),
        )
        // The label: only the selected tab's, fading in under the icon as it rises. Always laid out
        // (alpha 0 when unselected), so TalkBack reads every tab's name.
        Text(
            text = dest.label,
            style = MaterialTheme.typography.labelMedium,
            color = ink,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .offset { IntOffset(0, (iconTop() + IconSize + LabelGap).roundToPx()) }
                .graphicsLayer { alpha = progress },
        )
    }
}
