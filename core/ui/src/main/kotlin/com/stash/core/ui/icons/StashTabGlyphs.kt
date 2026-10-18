package com.stash.core.ui.icons

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
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
import com.stash.core.ui.ambient.rememberReducedMotion

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
 * Shapes: StashTabGlyphPaths.kt. Motion and timings: StashTabGlyphMotion.kt (the prototype's CSS
 * transitions, one for one) and StashTabMotion.kt (curves, keyframes, the clamped frame clock).
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
        if (glyph in CutOutGlyphs) {
            // Its own layer, so the Clear cut-outs punch through the glyph only, not the bar under it.
            modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        } else {
            modifier
        },
    ) {
        val color = lerp(restColor, inkColor(), motion.colour.value.coerceIn(0f, 1f))
        scale(size.width / GLYPH_GRID, size.height / GLYPH_GRID, pivot = Offset.Zero) {
            drawTabGlyph(glyph, motion, color)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Drawing geometry (the prototype's numbers, in the 24-unit grid).
// ---------------------------------------------------------------------------------------------

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

/** Search: the glint's starting angle. */
private const val GLINT_FROM = -70f

/** Sync: the loop's half turn. */
private const val LOOP_TURN = 180f

/** The keyframe curves: `squash` cubic-bezier(.3, 1.2, .5, 1), `tilt` cubic-bezier(.3, 1.3, .5, 1). */
private val SquashEase: Easing = CubicBezierEasing(0.3f, 1.2f, 0.5f, 1f)
private val TiltEase: Easing = CubicBezierEasing(0.3f, 1.3f, 0.5f, 1f)

/** The glyphs with BlendMode.Clear cut-outs, which need their own offscreen layer. */
private val CutOutGlyphs = setOf(StashTabGlyph.Home, StashTabGlyph.Library, StashTabGlyph.Search)

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
            clipRect(top = GLYPH_GRID * (1f - level), bottom = GLYPH_GRID * 2f) {
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
    clipRect(left = 14.3f, top = 0f, right = 26.3f, bottom = GLYPH_GRID) {
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
