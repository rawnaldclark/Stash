package com.stash.app.navigation

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.translate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.stash.core.ui.ambient.AlbumChrome
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.core.ui.ambient.rememberReducedMotion
import com.stash.core.ui.components.chromeGlass
import com.stash.core.ui.components.motion.LiquidPill
import com.stash.core.ui.components.motion.SpringParams
import com.stash.core.ui.icons.StashTabGlyph
import com.stash.core.ui.icons.StashTabMotion
import com.stash.core.ui.theme.LocalIsDarkTheme
import com.stash.core.ui.theme.SpaceGrotesk
import com.stash.core.ui.theme.StashTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/*
 * The tab bar (design: namida-study 2026-10-18, stash-tab-bar-v2.html, Style "Liquid pill" + Bar
 * "Floating"): a glass capsule floating above the system bar, five glyphs that are outlines at rest
 * and solid with their own move when picked, and one liquid pill that springs from tab to tab on
 * two edge springs. Only the picked tab shows its name: its icon lifts and the name rises in under
 * it. TalkBack still reads every tab's name.
 *
 * Nothing here recomposes per frame. The pill's edges, the lift, the name and the press scale are
 * Animatables read only in graphicsLayer / drawBehind; the glyphs draw their own moves; the album
 * colour (LocalAlbumChrome) is read in the draw phase too, so its crossfade only redraws.
 */

/** The prototype's geometry (CSS px = dp). */
private val BarHeight = 64.dp
private val BarRadius = 26.dp
private val BarShape = RoundedCornerShape(BarRadius)

/** The capsule's margin from the screen's sides; the mini player above takes the same. */
val TabBarSideMargin = 12.dp

/** `.bar { padding: 0 4px }`: the tabs' inset inside the capsule. */
private val BarInnerPadding = 4.dp

/** The icon + gap + name stack (`.stack`), centred in the bar. */
private val StackHeight = 44.dp
private val StackTop = (BarHeight - StackHeight) / 2
private val IconSize = 26.dp
private val LabelGap = 3.dp

/** A tab at rest sits its icon this far down the stack (`translateY(9px)`), centring it in the bar. */
private val RestDrop = 9.dp

/** The pill (`.indicator`): centred on the lifted icon. */
private val PillWidth = 60.dp
private val PillHeight = 34.dp
private val PillMinWidth = 24.dp
private val PillTop = StackTop + IconSize / 2 - PillHeight / 2

/** The pill's glow: `0 8px 22px -8px accent / 70 %`. */
private val GlowOffsetY = 8.dp
private val GlowSpread = 8.dp
private val GlowBlur = 22.dp
private const val GLOW_ALPHA = 0.7f

/** The pill's vertical gradient of the accent, top → bottom (dark, then light). */
private const val PILL_TOP_ALPHA_DARK = 0.30f
private const val PILL_BOTTOM_ALPHA_DARK = 0.16f
private const val PILL_TOP_ALPHA_LIGHT = 0.22f
private const val PILL_BOTTOM_ALPHA_LIGHT = 0.12f

/** The pill's 1 px inner top highlight (`--hi`). */
private val PillHighlightDark = Color.White.copy(alpha = 0.10f)
private val PillHighlightLight = Color.White.copy(alpha = 0.60f)

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

/** A spring settles within half a pixel. */
private const val PILL_THRESHOLD_PX = 0.5f

/**
 * The app's bottom tab bar: a floating glass capsule with the liquid pill.
 *
 * Takes the navigation bar's inset under it (the capsule floats above the gesture handle or the
 * three buttons), so the bar's whole height is what screens pad by (LocalBottomChromeInset).
 *
 * @param currentRoute The current destination's route. On a screen inside a tab (a detail screen)
 *   the tab you came from stays picked.
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
    // The picked tab: the current route's, or, inside a tab, the last tab route seen.
    val routeIndex = tabs.indexOfFirst { it.route::class.qualifiedName == currentRoute }
    var lastTabIndex by rememberSaveable { mutableIntStateOf(-1) }
    val selectedIndex = if (routeIndex >= 0) routeIndex else lastTabIndex
    SideEffect { if (routeIndex >= 0) lastTabIndex = routeIndex }

    val dark = LocalIsDarkTheme.current
    val restColor = if (dark) TabRestDark else StashTheme.extendedColors.textTertiary
    val chrome = LocalAlbumChrome.current
    // The picked glyph, its name and the pill wear the album colour (Stash purple when idle).
    val ink = remember(chrome) { { chrome.selectedTab } }
    val reducedMotion = rememberReducedMotion()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current

    val pill = remember { PillState() }
    var barSize by remember { mutableIntStateOf(0) }
    val painter = remember { PillPainter() }

    LaunchedEffect(selectedIndex, barSize, rtl, reducedMotion, density) {
        if (barSize == 0 || selectedIndex < 0) return@LaunchedEffect
        pill.moveTo(
            index = selectedIndex,
            count = tabs.size,
            barWidth = barSize.toFloat(),
            inset = with(density) { BarInnerPadding.toPx() },
            pillWidth = with(density) { PillWidth.toPx() },
            rtl = rtl,
            animate = !reducedMotion,
        )
    }

    Box(
        modifier
            .fillMaxWidth()
            // Above the gesture handle or three-button bar, and clear of a side bar in landscape.
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets(bottom = MinFloatGap)))
            .padding(horizontal = TabBarSideMargin),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .dropShadow(BarShape, BarShadow)
                .chromeGlass(BarShape, ring = true, tintAlphaScale = TAB_GLASS_TINT_SCALE)
                .onSizeChanged { size: IntSize -> barSize = size.width }
                .drawBehind {
                    if (selectedIndex >= 0 && pill.placed) {
                        painter.draw(this, pill, chrome, dark)
                    }
                }
                .padding(horizontal = BarInnerPadding)
                .selectableGroup(),
        ) {
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

    LaunchedEffect(selected, reducedMotion) {
        val v = if (selected) 1f else 0f
        if (reducedMotion) {
            lift.snapTo(v); labelIn.snapTo(v); labelAlpha.snapTo(v)
            return@LaunchedEffect
        }
        coroutineScope {
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

    // Press: the icon dips to 84 % and springs back on release.
    LaunchedEffect(interaction, reducedMotion) {
        interaction.interactions.collect { event ->
            if (reducedMotion) return@collect
            when (event) {
                is PressInteraction.Press ->
                    launch { press.animateTo(PRESS_SCALE, tween(PRESS_MS, easing = StashTabMotion.CssEaseOut)) }
                is PressInteraction.Release, is PressInteraction.Cancel ->
                    launch { press.animateTo(1f, tween(RELEASE_MS, easing = StashTabMotion.PopEase)) }
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
            .semantics { contentDescription = dest.label },
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

/** The pill's two edges, in px along the bar, each on its own spring. */
private class PillState {
    val left = Animatable(0f)
    val right = Animatable(0f)

    /** Whether the edges have been placed on a tab yet (nothing is drawn before). */
    var placed = false
        private set

    private var lastBarWidth = 0f

    suspend fun moveTo(
        index: Int,
        count: Int,
        barWidth: Float,
        inset: Float,
        pillWidth: Float,
        rtl: Boolean,
        animate: Boolean,
    ) {
        val center = LiquidPill.tabCenter(index, count, barWidth, inset, rtl)
        val target = LiquidPill.edges(center, pillWidth)
        // The first placement, a new bar width (rotation) and reduced motion all jump straight there.
        if (!animate || !placed || barWidth != lastBarWidth) {
            lastBarWidth = barWidth
            left.snapTo(target.left)
            right.snapTo(target.right)
            placed = true
            return
        }
        val springs = LiquidPill.springsFor(target.right, right.value)
        coroutineScope {
            launch { left.animateTo(target.left, springs.left.toSpec()) }
            launch { right.animateTo(target.right, springs.right.toSpec()) }
        }
    }

    private fun SpringParams.toSpec() =
        spring(dampingRatio = dampingRatio, stiffness = stiffness, visibilityThreshold = PILL_THRESHOLD_PX)
}

/**
 * Draws the pill: its glow under it, the accent gradient, and the 1 px top highlight. Paths and
 * paint are reused across frames; the gradient is rebuilt only when its colour changes.
 */
private class PillPainter {
    private val pillPath = Path()
    private val shiftedPath = Path()
    private val glowPaint = Paint()
    private var glowRadiusPx = -1f
    private var brushKey: Color? = null
    private var brushTop = 0f
    private var brush: Brush? = null

    fun draw(scope: DrawScope, pill: PillState, chrome: AlbumChrome, dark: Boolean) = with(scope) {
        val accent = chrome.selectedTab
        val left = pill.left.value
        val width = LiquidPill.drawnWidth(left, pill.right.value, PillMinWidth.toPx())
        val top = PillTop.toPx()
        val height = PillHeight.toPx()
        val radius = height / 2f
        val rect = RoundRect(left, top, left + width, top + height, CornerRadius(radius))
        pillPath.reset()
        pillPath.addRoundRect(rect)

        // The glow (box-shadow): only outside the pill, as CSS draws it. BlurMaskFilter blurs on the
        // GPU from API 28; earlier devices skip the glow rather than draw it hard-edged.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipPath(pillPath, ClipOp.Difference) { drawGlow(accent, left, width, top, height, radius) }
        }

        val (topAlpha, bottomAlpha) =
            if (dark) PILL_TOP_ALPHA_DARK to PILL_BOTTOM_ALPHA_DARK else PILL_TOP_ALPHA_LIGHT to PILL_BOTTOM_ALPHA_LIGHT
        if (brushKey != accent || brushTop != top) {
            brush = Brush.verticalGradient(
                listOf(accent.copy(alpha = topAlpha), accent.copy(alpha = bottomAlpha)),
                startY = top,
                endY = top + height,
            )
            brushKey = accent
            brushTop = top
        }
        drawPath(pillPath, brush!!)

        // `inset 0 1px 0 --hi`: the pill minus itself moved down 1 px leaves a crescent on top.
        val px = 1.dp.toPx()
        shiftedPath.reset()
        shiftedPath.addRoundRect(rect.translate(Offset(0f, px)))
        clipPath(pillPath) {
            clipPath(shiftedPath, ClipOp.Difference) {
                drawRect(
                    if (dark) PillHighlightDark else PillHighlightLight,
                    topLeft = Offset(left, top),
                    size = Size(width, radius),
                )
            }
        }
    }

    private fun DrawScope.drawGlow(accent: Color, left: Float, width: Float, top: Float, height: Float, radius: Float) {
        val blurPx = GlowBlur.toPx()
        if (glowRadiusPx != blurPx) {
            // CSS blur 22px = a Gaussian of sigma 11; Skia's sigma = 0.57735 * radius + 0.5.
            val sigma = blurPx / 2f
            glowPaint.asFrameworkPaint().maskFilter =
                BlurMaskFilter(((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.1f), BlurMaskFilter.Blur.NORMAL)
            glowRadiusPx = blurPx
        }
        val spread = GlowSpread.toPx()
        val dy = GlowOffsetY.toPx()
        glowPaint.asFrameworkPaint().color = accent.copy(alpha = accent.alpha * GLOW_ALPHA).toArgb()
        val r = (radius - spread).coerceAtLeast(0f)
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRoundRect(
                left + spread, top + spread + dy, left + width - spread, top + height - spread + dy,
                r, r, glowPaint.asFrameworkPaint(),
            )
        }
    }
}
