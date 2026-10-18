package com.stash.app.navigation

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.stash.core.ui.components.motion.EdgeSpring
import com.stash.core.ui.components.motion.LiquidPill
import com.stash.core.ui.components.motion.PillEdges
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/*
 * The tab bar's liquid pill: two edges on hand-stepped springs (LiquidPill / EdgeSpring in
 * core/ui), run the prototype's way in one frame loop with the step clamped to 1/30 s, and the
 * painter that draws them. A new pick retargets the springs and keeps their velocity, so a second
 * tap mid-flight bends the pill toward the new tab.
 */

/** The pill (`.indicator`): 60 × 34, centred on the lifted icon. */
internal val PillWidth = 60.dp
private val PillHeight = 34.dp
private val PillMinWidth = 24.dp
private val PillTop = StackTop + IconSize / 2 - PillHeight / 2

/** How far an overshoot squashed against the capsule's end may flatten the pill. */
private val MaxWallSquish = 3.dp
private const val WALL_SQUISH_PER_PX = 0.2f

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

/** The prototype's settle test (`|x − target| < .05 px`, `|v| < .5 px/s`), in dp. */
private const val SETTLE_POSITION_DP = 0.05f
private const val SETTLE_VELOCITY_DP = 0.5f

/** The pill's two edges, in px along the bar. */
@Stable
internal class PillState {
    private val left = EdgeSpring()
    private val right = EdgeSpring()

    /** The edges as last stepped; snapshot state, so only the bar's draw reads them. */
    var leftPx by mutableFloatStateOf(0f)
        private set
    var rightPx by mutableFloatStateOf(0f)
        private set

    /** Where the pill comes to rest on the picked tab. */
    var rest = PillEdges(0f, 0f)
        private set

    /** Whether the edges have been placed on a tab yet. Before, the bar draws the rest position. */
    var placed by mutableStateOf(false)
        private set

    /** Whether the springs are moving (the frame loop runs only then). */
    private var moving by mutableStateOf(false)
    private var lastFrameNanos = 0L
    private var lastBarWidth = 0f

    /**
     * Heads for [edges] in a bar [barWidth] wide. The first placement, a new bar width (rotation)
     * and [animate] false (reduced motion) jump straight there; otherwise the springs retarget,
     * keeping their velocity: the leading edge in the direction of travel stiff, the trailing soft.
     */
    fun moveTo(edges: PillEdges, barWidth: Float, animate: Boolean) {
        rest = edges
        if (!animate || !placed || barWidth != lastBarWidth) {
            lastBarWidth = barWidth
            left.snapTo(edges.left)
            right.snapTo(edges.right)
            publish()
            placed = true
            moving = false
            return
        }
        if (edges.left == left.target && edges.right == right.target) return
        val springs = LiquidPill.springsFor(edges.right, right.position)
        left.retarget(edges.left, springs.left)
        right.retarget(edges.right, springs.right)
        if (!moving) {
            // The frame clock runs on System.nanoTime: the first step covers the time since the tap.
            lastFrameNanos = System.nanoTime()
            moving = true
        }
    }

    /** Steps the springs once a frame while they move; runs for the bar's lifetime. */
    suspend fun runSprings(density: Float) {
        val positionTolerance = SETTLE_POSITION_DP * density
        val velocityTolerance = SETTLE_VELOCITY_DP * density
        while (true) {
            snapshotFlow { moving }.first { it }
            while (moving) {
                val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
                withFrameNanos { now ->
                    if (scale <= 0f) {
                        settle()
                    } else {
                        val dt = LiquidPill.frameStep(now - lastFrameNanos, scale)
                        lastFrameNanos = now
                        left.advance(dt)
                        right.advance(dt)
                        if (left.isSettled(positionTolerance, velocityTolerance) &&
                            right.isSettled(positionTolerance, velocityTolerance)
                        ) {
                            settle()
                        } else {
                            publish()
                        }
                    }
                }
            }
        }
    }

    private fun settle() {
        left.snapTo(left.target)
        right.snapTo(right.target)
        publish()
        moving = false
    }

    private fun publish() {
        leftPx = left.position
        rightPx = right.position
    }
}

/**
 * Draws the pill: its glow under it, the accent gradient, and the 1 px top highlight. Paths and
 * paint are reused across frames; the gradient is rebuilt only when its colour changes.
 */
internal class PillPainter {
    private val pillPath = Path()
    private val shiftedPath = Path()
    private val glowPaint = Paint()
    private var glowRadiusPx = -1f
    private var brushKey: Color? = null
    private var brushTop = 0f
    private var brush: Brush? = null

    /**
     * Draws the pill for spring edges [edges] resting at [rest] in this bar, in [accent]. An
     * overshoot past the capsule's inner wall squishes against it, a touch flatter, rather than
     * being cut off by the capsule's rounded end.
     */
    fun draw(scope: DrawScope, edges: PillEdges, rest: PillEdges, accent: Color, dark: Boolean) = with(scope) {
        val wall = BarInnerPadding.toPx()
        val drawn = LiquidPill.drawnEdges(
            left = edges.left,
            right = edges.right,
            restLeft = rest.left,
            restRight = rest.right,
            wallLeft = wall,
            wallRight = size.width - wall,
            minWidth = PillMinWidth.toPx(),
        )
        val trimmed = (edges.right - drawn.right).coerceAtLeast(0f) + (drawn.left - edges.left).coerceAtLeast(0f)
        val squish = min(trimmed * WALL_SQUISH_PER_PX, MaxWallSquish.toPx())
        val left = drawn.left
        val width = drawn.right - drawn.left
        val top = PillTop.toPx() + squish / 2f
        val height = PillHeight.toPx() - squish
        val radius = height / 2f
        val rect = RoundRect(left, top, left + width, top + height, CornerRadius(radius))
        pillPath.reset()
        pillPath.addRoundRect(rect)

        // The glow (box-shadow): only outside the pill, as CSS draws it. BlurMaskFilter blurs on the
        // GPU from API 28; earlier devices skip the glow rather than draw it hard-edged.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipPath(pillPath, ClipOp.Difference) { drawGlow(accent, left, width, top, height, radius) }
        }

        val topAlpha = if (dark) PILL_TOP_ALPHA_DARK else PILL_TOP_ALPHA_LIGHT
        val bottomAlpha = if (dark) PILL_BOTTOM_ALPHA_DARK else PILL_BOTTOM_ALPHA_LIGHT
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
        shiftedPath.reset()
        shiftedPath.addRoundRect(rect.translate(Offset(0f, 1.dp.toPx())))
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
