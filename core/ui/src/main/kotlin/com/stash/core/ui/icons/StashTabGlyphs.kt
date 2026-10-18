package com.stash.core.ui.icons

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import com.stash.core.ui.ambient.rememberReducedMotion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Stash's tab glyphs (design: namida-study 2026-10-18, stash-tab-bar-v2.html, `GLYPHS` and the
 * `.g-*` CSS). Each is an outline at rest and solid when picked, with details cut out, and each has
 * its own small move on pick:
 *
 *  - Home: the fill rises from the floor like a level meter; the house squashes and springs back.
 *  - Library: the sleeve pops in solid; the record (clipped to the right of the sleeve) slides out
 *    3.4 and turns 220°.
 *  - Search: the lens tilts −14° and back; the solid lens pops; the glint cut-out sweeps −70° → 0°.
 *  - Sync: the arrow loop turns 180° with overshoot; the centre disc pops solid.
 *  - Settings: three faders; the knobs fill and glide to a new mix (−5 / +6 / −3.5), staggered.
 *
 * Everything is drawn on a Canvas in the prototype's 24-unit grid, scaled to the icon's size, so
 * strokes stay crisp at any density. The cut-outs are drawn with BlendMode.Clear inside the
 * glyph's own offscreen layer (two of them are strokes, which no Path op can subtract). Every
 * animated value is an Animatable read only in the draw scope: a move redraws the glyph without
 * recomposing anything.
 *
 * Timings and curves are the prototype's CSS transitions, one for one. A CSS keyframe animation
 * applies its timing function to each keyframe interval, which [keyframeValue] reproduces.
 */

/** The five tab glyphs, in tab order. */
enum class StashTabGlyph { Home, Library, Search, Sync, Settings }

/**
 * One tab glyph, animated between rest and picked.
 *
 * @param glyph Which glyph.
 * @param selected Whether its tab is picked: outline at rest, solid with its move when picked.
 * @param restColor The outline colour at rest.
 * @param inkColor The picked colour. A lambda read in the draw phase, so a crossfading album colour
 *   only redraws the glyph.
 * @param modifier Size it here (the tab bar uses 26 dp); the glyph fills it.
 * @param replayTrigger Bump while [selected] to replay the pick move (a re-tap of the picked tab).
 * @param reducedMotion Snap every change, with no stretch or bounce.
 */
@Composable
fun StashTabGlyph(
    glyph: StashTabGlyph,
    selected: Boolean,
    restColor: Color,
    inkColor: () -> Color,
    modifier: Modifier = Modifier,
    replayTrigger: Int = 0,
    reducedMotion: Boolean = rememberReducedMotion(),
) {
    val motion = remember(glyph) { TabGlyphMotion(glyph, selected, replayTrigger) }
    LaunchedEffect(motion, selected, replayTrigger, reducedMotion) {
        val isReplay = selected && motion.target && replayTrigger != motion.seenReplay
        motion.seenReplay = replayTrigger
        if (isReplay) motion.replay(animate = !reducedMotion) else motion.settle(selected, animate = !reducedMotion)
    }
    Canvas(
        modifier
            // Its own layer, so the Clear cut-outs punch through the glyph only, not the bar under it.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val color = lerp(restColor, inkColor(), motion.colour.value.coerceIn(0f, 1f))
        scale(size.width / GRID, size.height / GRID, pivot = Offset.Zero) {
            drawTabGlyph(glyph, motion, color)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Path data: the prototype's SVG, verbatim, in its 24-unit viewBox.
// ---------------------------------------------------------------------------------------------

/** The glyphs' SVG path data (24-unit grid). Circles and rounded rects are geometry in the draw code. */
internal object TabGlyphPathData {
    const val HOUSE =
        "M4.6 10 11 4.7a1.6 1.6 0 0 1 2 0l6.4 5.3c.4.3.6.8.6 1.3v7.9a1.6 1.6 0 0 1-1.6 1.6H5.6A1.6 1.6 0 0 1 4 19.2v-7.9c0-.5.2-1 .6-1.3z"

    /** The door, cut out of the solid house (the mask's black path). */
    const val HOME_DOOR_CUT = "M9.9 21v-4.4a2.1 2.1 0 0 1 4.2 0V21z"

    /** The door's outline, drawn on top. */
    const val HOME_DOOR = "M9.9 20.6v-4a2.1 2.1 0 0 1 4.2 0v4"

    /** The groove on the record. */
    const val LIBRARY_GROOVE = "M15 8.1a3.9 3.9 0 0 1 3.9 3.9"

    /** The label line on the sleeve (drawn at 1.5, cut out of the solid sleeve at 1.7). */
    const val LIBRARY_LABEL = "M6.2 15.6h4.4"

    /** The glint on the lens, cut out of the solid lens. */
    const val SEARCH_GLINT = "M7.3 9.4a3.5 3.5 0 0 1 2.5-2.6"
    const val SEARCH_HANDLE = "M15.3 15.3 20 20"

    /** The two arcs and two arrowheads of the sync loop. */
    val SYNC_LOOP = listOf(
        "M19.3 9.4A7.8 7.8 0 0 0 5.6 7.2",
        "M19.7 4.8v4.7H15",
        "M4.7 14.6a7.8 7.8 0 0 0 13.7 2.2",
        "M4.3 19.2v-4.7H9",
    )

    /** The three fader tracks. */
    const val SETTINGS_TRACKS = "M6 4v16M12 4v16M18 4v16"

    /** Every path string, for the parse test. */
    val ALL: List<String> = listOf(
        HOUSE, HOME_DOOR_CUT, HOME_DOOR, LIBRARY_GROOVE, LIBRARY_LABEL, SEARCH_GLINT, SEARCH_HANDLE,
        SETTINGS_TRACKS,
    ) + SYNC_LOOP
}

/** The parsed paths, built once on first draw and shared by every glyph (they are only read). */
private object TabGlyphPaths {
    private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()

    val house by lazy { parse(TabGlyphPathData.HOUSE) }
    val doorCut by lazy { parse(TabGlyphPathData.HOME_DOOR_CUT) }
    val door by lazy { parse(TabGlyphPathData.HOME_DOOR) }
    val groove by lazy { parse(TabGlyphPathData.LIBRARY_GROOVE) }
    val label by lazy { parse(TabGlyphPathData.LIBRARY_LABEL) }
    val glint by lazy { parse(TabGlyphPathData.SEARCH_GLINT) }
    val handle by lazy { parse(TabGlyphPathData.SEARCH_HANDLE) }
    val syncLoop by lazy { TabGlyphPathData.SYNC_LOOP.map(::parse) }
    val tracks by lazy { parse(TabGlyphPathData.SETTINGS_TRACKS) }
}

// ---------------------------------------------------------------------------------------------
// Geometry and motion constants (the prototype's numbers).
// ---------------------------------------------------------------------------------------------

/** The SVG viewBox size. */
private const val GRID = 24f

/** `.g { stroke-width: 1.9; stroke-linecap: round; stroke-linejoin: round }`. */
private val Outline = Stroke(width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val Groove = Stroke(width = 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val Handle = Stroke(width = 2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val CutStroke = Stroke(width = 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** `.pop`: picked-state parts start at 55 % and spring to full size. */
private const val POP_FROM = 0.55f

/** Settings `.track { opacity: .55 }`. */
private const val TRACK_ALPHA = 0.55f

/** Settings: each knob's rest rect (x, y) and its picked offset; w 5, h 3.6, r 1.8. */
private val KnobX = floatArrayOf(3.5f, 9.5f, 15.5f)
private val KnobY = floatArrayOf(12.2f, 6.2f, 10.2f)
private val KnobShift = floatArrayOf(-5f, 6f, -3.5f)
private val KnobSize = Size(5f, 3.6f)
private val KnobRadius = CornerRadius(1.8f, 1.8f)

/** Library: the record's travel and turn. */
private const val RECORD_SLIDE = 3.4f
private const val RECORD_TURN = 220f

/** Search: the lens tilt keyframes and the glint's starting angle. */
private const val GLINT_FROM = -70f

/** Sync: the loop's half turn. */
private const val LOOP_TURN = 180f

/**
 * The tab bar prototype's CSS curves, shared by the glyphs and the bar so both move on the same
 * curves. Several overshoot (a y control point above 1), which CubicBezierEasing evaluates as CSS does.
 */
object StashTabMotion {
    /** CSS `ease`, the timing function of a transition that names none. */
    val CssEase: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** CSS `ease-in`. */
    val CssEaseIn: Easing = CubicBezierEasing(0.42f, 0f, 1f, 1f)

    /** CSS `ease-out`. */
    val CssEaseOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** `.pop` and the tab icon's spring back from a press: cubic-bezier(.3, 1.6, .5, 1). */
    val PopEase: Easing = CubicBezierEasing(0.3f, 1.6f, 0.5f, 1f)

    /** The picked icon's lift (`.stack`): cubic-bezier(.3, 1.5, .5, 1). */
    val LiftEase: Easing = CubicBezierEasing(0.3f, 1.5f, 0.5f, 1f)

    /** The picked tab's name rising in (`.tab.on .lbl`): cubic-bezier(.3, 1.4, .5, 1). */
    val LabelEase: Easing = CubicBezierEasing(0.3f, 1.4f, 0.5f, 1f)
}

private val CssEase = StashTabMotion.CssEase
private val PopEase = StashTabMotion.PopEase
private val RiseOnEase: Easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)
private val RiseOffEase: Easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)
private val SquashEase: Easing = CubicBezierEasing(0.3f, 1.2f, 0.5f, 1f)
private val RecordEase: Easing = CubicBezierEasing(0.25f, 1.25f, 0.4f, 1f)
private val TiltEase: Easing = CubicBezierEasing(0.3f, 1.3f, 0.5f, 1f)
private val GlintEase: Easing = CubicBezierEasing(0.25f, 0.8f, 0.3f, 1f)
private val KnobEase: Easing = StashTabMotion.LiftEase // the same curve: cubic-bezier(.3, 1.5, .5, 1)

private const val COLOUR_MS = 240
private const val POP_MS = 420
private const val POP_FADE_MS = 160
private const val RISE_ON_MS = 520
private const val RISE_OFF_MS = 300
private const val SQUASH_MS = 560
private const val RECORD_MS = 700
private const val TILT_MS = 560
private const val GLINT_MS = 620
private const val LOOP_MS = 720
private const val KNOB_MS = 560
private const val KNOB_FILL_MS = 200
private const val KNOB_STAGGER_MS = 45

/** A replay first runs the move back toward rest this long (the prototype's replay tile). */
private const val REPLAY_RETREAT_MS = 220L

/** Home's squash: `@keyframes squash` (scaleX, scaleY) at 0 / 25 / 55 / 100 %. */
internal val SquashStops = floatArrayOf(0f, 0.25f, 0.55f, 1f)
internal val SquashX = floatArrayOf(1f, 1.08f, 0.97f, 1f)
internal val SquashY = floatArrayOf(1f, 0.86f, 1.05f, 1f)

/** Search's tilt: `@keyframes tilt` (rotate, scale) at 0 / 35 / 100 %. */
internal val TiltStops = floatArrayOf(0f, 0.35f, 1f)
internal val TiltAngle = floatArrayOf(0f, -14f, 0f)
internal val TiltScale = floatArrayOf(1f, 1.04f, 1f)

/**
 * A CSS keyframe animation's value at linear time [t] (0..1): find the keyframe interval in
 * [stops], ease the local fraction with [easing] (CSS applies the timing function per interval),
 * and interpolate [values]. Outside 0..1 it holds the end values.
 */
internal fun keyframeValue(t: Float, stops: FloatArray, values: FloatArray, easing: Easing): Float {
    require(stops.size == values.size && stops.size >= 2) { "stops and values must pair up" }
    if (t <= stops.first()) return values.first()
    if (t >= stops.last()) return values.last()
    var i = 0
    while (i < stops.size - 2 && t >= stops[i + 1]) i++
    val local = (t - stops[i]) / (stops[i + 1] - stops[i])
    val eased = easing.transform(local)
    return values[i] + (values[i + 1] - values[i]) * eased
}

// ---------------------------------------------------------------------------------------------
// Motion state.
// ---------------------------------------------------------------------------------------------

/**
 * A glyph's animated values. Each is 0 at rest and 1 picked (the easing may overshoot past 1),
 * except [oneShot], a keyframe clock that runs 0 → 1 once per pick and rests at 1 (identity).
 */
@Stable
internal class TabGlyphMotion(
    private val glyph: StashTabGlyph,
    selected: Boolean,
    initialReplay: Int,
) {
    private val start = if (selected) 1f else 0f

    /** Rest colour → ink (`.tab { transition: color 240ms }`). */
    val colour = Animatable(start)

    /** The solid part's scale (0 → 55 %, 1 → 100 %). */
    val pop = Animatable(start)

    /** The solid part's opacity. */
    val popAlpha = Animatable(start)

    /** The glyph's main move: Home's fill level, Library's record, Search's glint, Sync's loop. */
    val primary = Animatable(start)

    /** Home's squash / Search's tilt keyframe clock. */
    val oneShot = Animatable(1f)

    /** Settings: each knob's travel and fill. */
    val knobs = Array(3) { Animatable(start) }
    val knobFill = Array(3) { Animatable(start) }

    /** The state last asked for. */
    var target: Boolean = selected
        private set

    /** The last replay trigger handled, so a recomposition never replays twice. */
    var seenReplay: Int = initialReplay

    /** Moves to picked or rest; snaps when [animate] is false. */
    suspend fun settle(selected: Boolean, animate: Boolean) {
        val wasSelected = target
        target = selected
        if (!animate) {
            snapTo(selected)
            return
        }
        run(on = selected, includeColour = true, restartOneShot = selected && !wasSelected)
    }

    /**
     * Replays the pick move while picked: runs it back toward rest for a moment, then forward
     * again (the colour stays the ink throughout). Snapped glyphs (reduced motion) have nothing to replay.
     */
    suspend fun replay(animate: Boolean) {
        if (!animate) return
        coroutineScope {
            val retreat = launch { run(on = false, includeColour = false, restartOneShot = false) }
            // Scaled like every Compose animation, so the system's animator duration scale slows it too.
            val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
            delay((REPLAY_RETREAT_MS * scale).toLong())
            retreat.cancel()
        }
        run(on = true, includeColour = false, restartOneShot = true)
    }

    private suspend fun snapTo(selected: Boolean) {
        val v = if (selected) 1f else 0f
        colour.snapTo(v)
        pop.snapTo(v)
        popAlpha.snapTo(v)
        primary.snapTo(v)
        oneShot.snapTo(1f)
        knobs.forEach { it.snapTo(v) }
        knobFill.forEach { it.snapTo(v) }
    }

    private suspend fun run(on: Boolean, includeColour: Boolean, restartOneShot: Boolean) = coroutineScope {
        val v = if (on) 1f else 0f
        if (includeColour) launch { colour.animateTo(v, tween(COLOUR_MS, easing = CssEase)) }
        launch { pop.animateTo(v, tween(POP_MS, easing = PopEase)) }
        launch { popAlpha.animateTo(v, tween(POP_FADE_MS, easing = CssEase)) }
        primarySpec(on)?.let { spec -> launch { primary.animateTo(v, spec) } }
        oneShotMs()?.let { ms ->
            launch {
                if (on && restartOneShot) {
                    oneShot.snapTo(0f)
                    oneShot.animateTo(1f, tween(ms, easing = LinearEasing))
                } else if (!on) {
                    // CSS drops the keyframe animation with the class: the glyph is at identity at once.
                    oneShot.snapTo(1f)
                }
            }
        }
        if (glyph == StashTabGlyph.Settings) {
            for (i in 0 until 3) {
                val delayMs = i * KNOB_STAGGER_MS
                launch { knobs[i].animateTo(v, tween(KNOB_MS, delayMs, KnobEase)) }
                launch { knobFill[i].animateTo(v, tween(KNOB_FILL_MS, delayMs, CssEase)) }
            }
        }
    }

    private fun primarySpec(on: Boolean): AnimationSpec<Float>? = when (glyph) {
        StashTabGlyph.Home -> if (on) tween(RISE_ON_MS, easing = RiseOnEase) else tween(RISE_OFF_MS, easing = RiseOffEase)
        StashTabGlyph.Library -> tween(RECORD_MS, easing = RecordEase)
        StashTabGlyph.Search -> tween(GLINT_MS, easing = GlintEase)
        StashTabGlyph.Sync -> tween(LOOP_MS, easing = RecordEase)
        StashTabGlyph.Settings -> null
    }

    private fun oneShotMs(): Int? = when (glyph) {
        StashTabGlyph.Home -> SQUASH_MS
        StashTabGlyph.Search -> TILT_MS
        else -> null
    }
}

// ---------------------------------------------------------------------------------------------
// Drawing, in the 24-unit grid.
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawTabGlyph(glyph: StashTabGlyph, m: TabGlyphMotion, color: Color) {
    when (glyph) {
        StashTabGlyph.Home -> drawHome(m, color)
        StashTabGlyph.Library -> drawLibrary(m, color)
        StashTabGlyph.Search -> drawSearch(m, color)
        StashTabGlyph.Sync -> drawSync(m, color)
        StashTabGlyph.Settings -> drawSettings(m, color)
    }
}

/** The solid part's colour at the pop's opacity. */
private fun Color.popped(m: TabGlyphMotion): Color = copy(alpha = alpha * m.popAlpha.value.coerceIn(0f, 1f))

/** Scale for a pop progress: 55 % at rest, 100 % picked (overshooting with the curve). */
private fun popScale(p: Float): Float = POP_FROM + (1f - POP_FROM) * p

private fun DrawScope.drawHome(m: TabGlyphMotion, color: Color) {
    val t = m.oneShot.value
    val sx = keyframeValue(t, SquashStops, SquashX, SquashEase)
    val sy = keyframeValue(t, SquashStops, SquashY, SquashEase)
    // `.house { transform-origin: 12px 21px }`: it squashes onto its floor.
    scale(sx, sy, pivot = Offset(12f, 21f)) {
        val level = m.primary.value
        if (level > 0f) {
            // The mask's rect rises from translateY(24) to 0: fill everything below the level.
            clipRect(top = GRID * (1f - level), bottom = GRID * 2f) {
                drawPath(TabGlyphPaths.house, color)
            }
            drawPath(TabGlyphPaths.doorCut, Color.Black, blendMode = BlendMode.Clear)
        }
        drawPath(TabGlyphPaths.house, color, style = Outline)
        drawPath(TabGlyphPaths.door, color, style = Outline)
    }
}

private fun DrawScope.drawLibrary(m: TabGlyphMotion, color: Color) {
    val p = m.primary.value
    val center = Offset(15f, 12f)
    // The record shows only to the right of the sleeve (`clipPath x=14.3`).
    clipRect(left = 14.3f, top = 0f, right = 26.3f, bottom = GRID) {
        withTransform({
            translate(left = RECORD_SLIDE * p)
            rotate(RECORD_TURN * p, pivot = center)
        }) {
            drawCircle(color, radius = 6.6f, center = center, style = Outline)
            drawPath(TabGlyphPaths.groove, color, style = Groove)
            drawCircle(color, radius = 1.3f, center = center)
        }
    }
    // The sleeve pops in solid (from `transform-origin: 8.7px 12px`), its label line cut out.
    if (m.popAlpha.value > 0f) {
        scale(popScale(m.pop.value), pivot = Offset(8.7f, 12f)) {
            drawRoundRect(color.popped(m), topLeft = Offset(3f, 5f), size = Size(11.4f, 14f), cornerRadius = CornerRadius(2.2f))
            drawPath(TabGlyphPaths.label, Color.Black, style = CutStroke, blendMode = BlendMode.Clear)
        }
    }
    drawRoundRect(color, topLeft = Offset(3f, 5f), size = Size(11.4f, 14f), cornerRadius = CornerRadius(2.2f), style = Outline)
    drawPath(TabGlyphPaths.label, color, style = Groove)
}

private fun DrawScope.drawSearch(m: TabGlyphMotion, color: Color) {
    val t = m.oneShot.value
    val angle = keyframeValue(t, TiltStops, TiltAngle, TiltEase)
    val s = keyframeValue(t, TiltStops, TiltScale, TiltEase)
    val center = Offset(10.5f, 10.5f)
    withTransform({
        rotate(angle, pivot = center)
        scale(s, pivot = center)
    }) {
        if (m.popAlpha.value > 0f) {
            scale(popScale(m.pop.value), pivot = center) {
                drawCircle(color.popped(m), radius = 6.4f, center = center)
                // The glint sweeps from −70° to rest across the glass.
                rotate(GLINT_FROM * (1f - m.primary.value), pivot = center) {
                    drawPath(TabGlyphPaths.glint, Color.Black, style = CutStroke, blendMode = BlendMode.Clear)
                }
            }
        }
        drawCircle(color, radius = 6.4f, center = center, style = Outline)
        drawPath(TabGlyphPaths.handle, color, style = Handle)
    }
}

private fun DrawScope.drawSync(m: TabGlyphMotion, color: Color) {
    val center = Offset(12f, 12f)
    rotate(LOOP_TURN * m.primary.value, pivot = center) {
        TabGlyphPaths.syncLoop.forEach { drawPath(it, color, style = Outline) }
    }
    drawCircle(color, radius = 2.6f, center = center, style = Outline)
    if (m.popAlpha.value > 0f) {
        scale(popScale(m.pop.value), pivot = center) {
            drawCircle(color.popped(m), radius = 2.6f, center = center)
        }
    }
}

private fun DrawScope.drawSettings(m: TabGlyphMotion, color: Color) {
    drawPath(TabGlyphPaths.tracks, color.copy(alpha = color.alpha * TRACK_ALPHA), style = Outline)
    for (i in 0 until 3) {
        // At rest the knob's fill is transparent, so its track shows through it, as in the SVG.
        val topLeft = Offset(KnobX[i], KnobY[i] + KnobShift[i] * m.knobs[i].value)
        val fill = m.knobFill[i].value.coerceIn(0f, 1f)
        if (fill > 0f) {
            drawRoundRect(color.copy(alpha = color.alpha * fill), topLeft = topLeft, size = KnobSize, cornerRadius = KnobRadius)
        }
        drawRoundRect(color, topLeft = topLeft, size = KnobSize, cornerRadius = KnobRadius, style = Outline)
    }
}
