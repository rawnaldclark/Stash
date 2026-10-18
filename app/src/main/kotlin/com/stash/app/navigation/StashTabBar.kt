package com.stash.app.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.core.ui.ambient.rememberReducedMotion
import com.stash.core.ui.components.ChromeGlassDefaults
import com.stash.core.ui.components.chromeGlass
import com.stash.core.ui.components.motion.LiquidPill
import com.stash.core.ui.components.motion.PillEdges
import com.stash.core.ui.icons.StashTabGlyph
import com.stash.core.ui.icons.StashTabMotion
import com.stash.core.ui.icons.withClampedFrameClock
import com.stash.core.ui.theme.LocalIsDarkTheme
import com.stash.core.ui.theme.SpaceGrotesk
import com.stash.core.ui.theme.StashTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.min

/*
 * The tab bar (design: namida-study 2026-10-18, stash-tab-bar-v2.html, Style "Liquid pill" + Bar
 * "Floating"): a glass capsule floating above the system bar, five glyphs that are outlines at rest
 * and solid with their own move when picked, and one liquid pill that springs from tab to tab on
 * two edge springs (LiquidPillIndicator.kt). Only the picked tab shows its name: its icon lifts and
 * the name rises in under it. TalkBack still reads every tab's name.
 *
 * Nothing here recomposes per frame. The pill's edges, the lift, the name and the press scale are
 * read only in graphicsLayer / drawBehind; the glyphs draw their own moves; the album colour
 * (LocalAlbumChrome) is read in the draw phase too, so its crossfade only redraws. Every move runs
 * on a clamped frame clock, so the frame that lays out a newly picked screen pauses it, never
 * flings it ahead.
 */

/** The prototype's geometry (CSS px = dp). */
private val BarHeight = 64.dp
private val BarShape = RoundedCornerShape(26.dp)

/** `.bar { padding: 0 4px }`: the tabs' inset inside the capsule (and the pill's walls). */
internal val BarInnerPadding = 4.dp

/** The icon + gap + name stack (`.stack`), centred in the bar. */
private val StackHeight = 44.dp
internal val StackTop = (BarHeight - StackHeight) / 2
internal val IconSize = 26.dp
private val LabelGap = 3.dp

/** A tab at rest sits its icon this far down the stack (`translateY(9px)`), centring it in the bar. */
private val RestDrop = 9.dp

/** `0 14px 30px -14px rgba(0, 0, 0, .55)`: the capsule's soft drop shadow. */
private val BarShadow = Shadow(
    radius = 30.dp,
    spread = (-14).dp,
    color = Color.Black.copy(alpha = 0.55f),
    offset = DpOffset(0.dp, 14.dp),
)

/** The tab bar's glass is denser than the mini player's (70 % → 84 % on dark), as before. */
private const val TAB_GLASS_TINT_SCALE = 1.2f

/**
 * The rest glyph colour on dark and pure black (the prototype's `--rest`): 4.4:1 on the dark glass.
 * The theme's tertiary text (#606078) only reaches 3.2:1 there, too faint for an icon that has no
 * label. On light the theme's tertiary text is the prototype's colour exactly (#8b7ab0, 3.7:1).
 */
private val TabRestDark = Color(0xFF77778F)

/** Where the capsule floats when the system draws no navigation bar under it. */
private val MinFloatGap = 12.dp

/** The tab bar's name style: the display face, `600 11.5px`, letter-spacing .015em. */
private val LabelStyle = TextStyle(
    fontFamily = SpaceGrotesk,
    fontWeight = FontWeight.SemiBold,
    fontSize = 11.5.sp,
    lineHeight = 15.sp,
    letterSpacing = 0.015.em,
)

/**
 * The name grows with the system font size up to this, so it still fits under the icon in the
 * 64 dp capsule ("Settings" whole at the largest sizes). TalkBack reads the full name regardless.
 */
private const val LABEL_MAX_FONT_SCALE = 1.3f

/** The keyboard focus ring: the pill's footprint, outlined. */
private val FocusRingInset = 2.dp
private val FocusRingHeight = 34.dp
private val FocusRingStroke = 2.dp

/** The pick and press timings (CSS transitions). */
private const val LIFT_MS = 460
private const val LABEL_IN_MS = 460
private const val LABEL_IN_DELAY_MS = 70
private const val LABEL_FADE_IN_MS = 200
private const val LABEL_OUT_MS = 200
private const val LABEL_FADE_OUT_MS = 150
private const val PRESS_MS = 110
private const val RELEASE_MS = 420
private const val PRESS_SCALE = 0.84f

/** `translateY(110%)`: a hidden name waits just below its clip. */
private const val LABEL_HIDDEN_OFFSET = 1.1f

/**
 * The app's bottom tab bar: a floating glass capsule with the liquid pill.
 *
 * Takes the navigation bar's inset under it (the capsule floats above the gesture handle or the
 * three buttons), so the bar's whole height is what screens pad by (LocalBottomChromeInset).
 *
 * @param currentRoute The route of the tab whose stack is on screen ([currentTabRoute]), so a
 *   tab's own detail screens keep it picked. A route that isn't a tab picks none.
 * @param onNavigate Called with a tab on every tap, including a tap on the picked tab (which pops
 *   back to the tab's root; the glyph replays its move).
 */
@Composable
fun StashTabBar(
    currentRoute: String?,
    onNavigate: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = TopLevelDestination.entries
    val selectedIndex = tabs.indexOfFirst { it.route::class.qualifiedName == currentRoute }
    // The tabs appear with the first known pick, already in place: a recreated bar (rotation, a
    // theme or UI scale change, a cold start) reads an empty back stack for a frame, and tabs
    // composed then would play their pick move into the real tab.
    val tabsShown = remember { Latch() }
    if (selectedIndex >= 0) tabsShown.on = true

    val dark = LocalIsDarkTheme.current
    val restColor = if (dark) TabRestDark else StashTheme.extendedColors.textTertiary
    val chrome = LocalAlbumChrome.current
    // The picked glyph, its name and the pill wear the album colour (Stash purple when idle).
    val ink = remember(chrome) { { chrome.selectedTab } }
    val reducedMotion = rememberReducedMotion()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current

    val pill = remember { PillState() }
    var barWidth by remember { mutableIntStateOf(0) }
    val painter = remember { PillPainter() }

    LaunchedEffect(pill, density) { pill.runSprings(density.density) }
    LaunchedEffect(selectedIndex, barWidth, rtl, reducedMotion, density) {
        if (barWidth == 0 || selectedIndex < 0) return@LaunchedEffect
        pill.moveTo(
            edges = restEdges(selectedIndex, tabs.size, barWidth.toFloat(), rtl, density),
            barWidth = barWidth.toFloat(),
            animate = !reducedMotion,
        )
    }

    Box(
        modifier
            .fillMaxWidth()
            // Above the gesture handle or three-button bar, and clear of a side bar in landscape.
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets(bottom = MinFloatGap)))
            .padding(horizontal = ChromeGlassDefaults.FloatingSideMargin),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .dropShadow(BarShape, BarShadow)
                .chromeGlass(BarShape, ring = true, tintAlphaScale = TAB_GLASS_TINT_SCALE)
                .onSizeChanged { barWidth = it.width }
                .drawBehind {
                    if (selectedIndex < 0) return@drawBehind
                    // Until the springs are placed (the bar's first frame), draw the pill at rest.
                    val rest = if (pill.placed) pill.rest else restEdges(selectedIndex, tabs.size, size.width, rtl, this)
                    val edges = if (pill.placed) {
                        PillEdges(pill.leftPx, pill.rightPx)
                    } else {
                        rest
                    }
                    painter.draw(this, edges, rest, chrome.selectedTab, dark)
                }
                .padding(horizontal = BarInnerPadding)
                .selectableGroup(),
        ) {
            if (tabsShown.on) {
                tabs.forEachIndexed { index, dest ->
                    TabItem(
                        dest = dest,
                        selected = index == selectedIndex,
                        restColor = restColor,
                        ink = ink,
                        reducedMotion = reducedMotion,
                        onClick = { onNavigate(dest) },
                    )
                }
            }
        }
    }
}

/** The pill's rest edges on tab [index] of [count] in a bar [barWidth] px wide. */
private fun restEdges(index: Int, count: Int, barWidth: Float, rtl: Boolean, density: Density) =
    with(density) {
        LiquidPill.edges(
            center = LiquidPill.tabCenter(index, count, barWidth, BarInnerPadding.toPx(), rtl),
            pillWidth = PillWidth.toPx(),
        )
    }

/** One tab: the glyph, lifted when picked, with its name rising in under it. */
@Composable
private fun RowScope.TabItem(
    dest: TopLevelDestination,
    selected: Boolean,
    restColor: Color,
    ink: () -> Color,
    reducedMotion: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val start = if (selected) 1f else 0f
    val lift = remember { Animatable(start) }
    val labelIn = remember { Animatable(start) }
    val labelAlpha = remember { Animatable(start) }
    val press = remember { Animatable(1f) }
    var replay by remember { mutableIntStateOf(0) }
    val focused by interaction.collectIsFocusedAsState()

    LaunchedEffect(selected, reducedMotion) {
        val v = if (selected) 1f else 0f
        if (reducedMotion) {
            lift.snapTo(v); labelIn.snapTo(v); labelAlpha.snapTo(v)
            return@LaunchedEffect
        }
        withClampedFrameClock {
            launch { lift.animateTo(v, tween(LIFT_MS, easing = StashTabMotion.LiftEase)) }
            if (selected) {
                launch { labelIn.animateTo(1f, tween(LABEL_IN_MS, LABEL_IN_DELAY_MS, StashTabMotion.LabelEase)) }
                launch { labelAlpha.animateTo(1f, tween(LABEL_FADE_IN_MS, LABEL_IN_DELAY_MS, StashTabMotion.CssEase)) }
            } else {
                launch { labelIn.animateTo(0f, tween(LABEL_OUT_MS, easing = StashTabMotion.CssEaseIn)) }
                launch { labelAlpha.animateTo(0f, tween(LABEL_FADE_OUT_MS, easing = StashTabMotion.CssEase)) }
            }
        }
    }

    // Press: the icon dips to 84 % and springs back on release. With animations off it stays put,
    // and a press held as they turn off is let go.
    LaunchedEffect(interaction, reducedMotion) {
        if (reducedMotion) {
            press.snapTo(1f)
            return@LaunchedEffect
        }
        coroutineScope {
            interaction.interactions.collect { event ->
                when (event) {
                    is PressInteraction.Press ->
                        launch { press.animateTo(PRESS_SCALE, tween(PRESS_MS, easing = StashTabMotion.CssEaseOut)) }
                    is PressInteraction.Release, is PressInteraction.Cancel ->
                        launch { press.animateTo(1f, tween(RELEASE_MS, easing = StashTabMotion.PopEase)) }
                }
            }
        }
    }

    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = {
                    if (selected) replay++
                    onClick()
                },
            )
            // Every tab's name for TalkBack, though only the picked one shows it.
            .semantics { contentDescription = dest.label }
            // Keyboard focus: the pill's footprint outlined around the icon, where it sits now.
            .drawBehind {
                if (!focused) return@drawBehind
                val ringWidth = min(PillWidth.toPx(), size.width - FocusRingInset.toPx() * 2)
                val ringHeight = FocusRingHeight.toPx()
                val centerY = (StackTop + IconSize / 2).toPx() + RestDrop.toPx() * (1f - lift.value)
                drawRoundRect(
                    color = ink(),
                    topLeft = Offset((size.width - ringWidth) / 2f, centerY - ringHeight / 2f),
                    size = Size(ringWidth, ringHeight),
                    cornerRadius = CornerRadius(ringHeight / 2f),
                    style = Stroke(FocusRingStroke.toPx()),
                )
            },
    ) {
        StashTabGlyph(
            glyph = dest.glyph,
            selected = selected,
            restColor = restColor,
            inkColor = ink,
            replayTrigger = replay,
            reducedMotion = reducedMotion,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = StackTop)
                .size(IconSize)
                .graphicsLayer {
                    translationY = RestDrop.toPx() * (1f - lift.value)
                    scaleX = press.value
                    scaleY = press.value
                },
        )
        // The name, clipped to its own line so it rises in from below.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = StackTop + IconSize + LabelGap)
                .graphicsLayer {
                    translationY = RestDrop.toPx() * (1f - lift.value)
                    clip = true
                },
        ) {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(LABEL_MAX_FONT_SCALE)),
            ) {
                BasicText(
                    text = dest.label,
                    style = LabelStyle,
                    color = ink,
                    maxLines = 1,
                    modifier = Modifier
                        .clearAndSetSemantics { }
                        .graphicsLayer {
                            translationY = size.height * LABEL_HIDDEN_OFFSET * (1f - labelIn.value)
                            alpha = labelAlpha.value.coerceIn(0f, 1f)
                        },
                )
            }
        }
    }
}

/** Set once, never observed: the bar recomposes with every pick anyway. */
private class Latch {
    var on = false
}
