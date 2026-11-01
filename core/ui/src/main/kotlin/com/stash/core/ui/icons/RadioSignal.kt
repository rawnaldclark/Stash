package com.stash.core.ui.icons

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stash.core.ui.ambient.rememberReducedMotion
import kotlin.math.PI
import kotlin.math.sin

/**
 * Stash's radio glyph, "Signal": a solid dot (the seed a station grows from) with two open arcs to
 * its right, the signal going out. It replaces Material's transistor radio on every Start radio
 * entry point. Drawn on the tab icons' grid (24 units, 1.5 stroke, round caps), so it sits with them.
 *
 *  - [Icon]: the still glyph, for menu rows and anywhere an [ImageVector] is wanted.
 *  - [RadioSignalGlyph]: the live one. Still in the caller's ink when no station is on; in the album
 *    accent while one is; and while it plays, the arcs breathe outward one after the other (2.4 s a
 *    cycle), each brightening with a soft glow as it passes.
 */
object RadioSignal {

    /** The still glyph (black; tint it like any icon). */
    val Icon: ImageVector by lazy {
        ImageVector.Builder(
            name = "Stash.RadioSignal",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = GRID,
            viewportHeight = GRID,
        ).apply {
            addPath(pathData = addPathNodes(DOT_PATH), fill = SolidColor(Color.Black))
            ARC_PATHS.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = STROKE,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
    }

    // The geometry, in grid units. The arcs are centred on the dot and open ±45° to the right; the
    // whole mark is optically centred in the 24-unit box (x 5.25..18.25, y 4.9..19.1).
    internal const val GRID = 24f
    internal const val STROKE = 1.5f
    internal const val DOT_X = 7.5f
    internal const val DOT_Y = 12f
    internal const val DOT_R = 2.25f
    internal val ARC_RADII = floatArrayOf(5.5f, 10f)
    internal const val ARC_HALF_SWEEP = 45f

    private const val DOT_PATH = "M7.5,12m-2.25,0a2.25,2.25 0 1,0 4.5,0a2.25,2.25 0 1,0 -4.5,0"

    // cos 45° x r from the dot: r 5.5 → 3.889, r 10 → 7.071.
    private val ARC_PATHS = listOf(
        "M11.389,8.111A5.5,5.5 0 0,1 11.389,15.889",
        "M14.571,4.929A10,10 0 0,1 14.571,19.071",
    )
}

/**
 * How the Signal glyph shows a radio. Pure; see RadioGlyphStateTest.
 */
enum class RadioGlyphState {
    /** No station: still, in the caller's ink. */
    OFF,

    /** A station is on air but paused, or is still tuning: still, in the album accent. */
    ON,

    /** A station is playing: the album accent, arcs breathing. */
    PULSING,
    ;

    /** Wears the album accent. */
    val tinted: Boolean get() = this != OFF

    companion object {
        /**
         * @param onAir a station is on air (PlayerRepository.radioSeedLabel != null).
         * @param tuning a station is being built (the radar sweep spins around the glyph meanwhile).
         * @param playing the player means to play (playWhenReady: through a buffering stall too).
         * @param reducedMotion the system's animations are off: a playing station holds still.
         */
        fun of(onAir: Boolean, tuning: Boolean, playing: Boolean, reducedMotion: Boolean = false): RadioGlyphState = when {
            !onAir && !tuning -> OFF
            onAir && !tuning && playing && !reducedMotion -> PULSING
            else -> ON
        }
    }
}

/** One full breath of the arcs: slow enough to read as "on air", never as busy. */
private const val PULSE_MS = 2_400

/** How far behind the inner arc the outer one breathes, as a share of the cycle. */
private const val RING_LAG = 0.18f

/** The share of the cycle a ring spends brightening and fading; the rest it rests. */
private const val RING_ACTIVE = 0.55f

/**
 * The live Signal glyph, [size] square. [tint] is the colour to draw in: the caller's ink for
 * [RadioGlyphState.OFF], the album accent otherwise (see [RadioGlyphState.tinted]); callers that
 * already animate their icon tint pass that.
 *
 * Only a [RadioGlyphState.PULSING] glyph asks for frames, and only for the draw phase: the breath is
 * read inside the Canvas, so it never recomposes anything.
 */
@Composable
fun RadioSignalGlyph(
    state: RadioGlyphState,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    contentDescription: String? = null,
) {
    val described = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else {
        modifier
    }
    // Branch BEFORE creating the infinite transition (the RadarSweep lesson): a still glyph must not
    // keep the frame clock ticking for as long as its screen is open.
    if (state == RadioGlyphState.PULSING && !rememberReducedMotion()) {
        PulsingSignal(tint, described.size(size))
    } else {
        Canvas(described.size(size)) { drawSignal(tint, phase = null) }
    }
}

@Composable
private fun PulsingSignal(tint: Color, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "radioSignal")
    val phase: State<Float> = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = PULSE_MS, easing = LinearEasing)),
        label = "radioSignalPhase",
    )
    Canvas(modifier) { drawSignal(tint, phase.value) }
}

/**
 * Draws the glyph scaled to the canvas. [phase] null: still. Otherwise ring i's breath is a half
 * sine over [RING_ACTIVE] of the cycle, [RING_LAG] after the ring inside it: its alpha rises from
 * 45% to full, its radius swells by 6%, and a wide, faint copy of the arc behind it is the glow.
 */
private fun DrawScope.drawSignal(tint: Color, phase: Float?) {
    val unit = size.minDimension / RadioSignal.GRID
    val center = Offset(RadioSignal.DOT_X * unit, RadioSignal.DOT_Y * unit)
    val stroke = RadioSignal.STROKE * unit
    drawCircle(color = tint, radius = RadioSignal.DOT_R * unit, center = center)
    RadioSignal.ARC_RADII.forEachIndexed { i, r ->
        val breath = if (phase == null) null else ringBreath(phase, i)
        val radius = r * unit * (1f + 0.06f * (breath ?: 0f))
        val alpha = if (breath == null) 1f else 0.45f + 0.55f * breath
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2, radius * 2)
        if (breath != null && breath > 0f) {
            drawArc(
                color = tint.copy(alpha = tint.alpha * 0.22f * breath),
                startAngle = -RadioSignal.ARC_HALF_SWEEP,
                sweepAngle = RadioSignal.ARC_HALF_SWEEP * 2,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke * 3f, cap = StrokeCap.Round),
            )
        }
        drawArc(
            color = tint.copy(alpha = tint.alpha * alpha),
            startAngle = -RadioSignal.ARC_HALF_SWEEP,
            sweepAngle = RadioSignal.ARC_HALF_SWEEP * 2,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** Ring [ring]'s breath at [phase] (0..1): 0 at rest, rising to 1 and back over [RING_ACTIVE]. */
internal fun ringBreath(phase: Float, ring: Int): Float {
    val local = ((phase - ring * RING_LAG) % 1f + 1f) % 1f
    return if (local < RING_ACTIVE) sin(PI * local / RING_ACTIVE).toFloat() else 0f
}
