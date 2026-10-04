package com.stash.feature.nowplaying.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Natural cubic spline through the knots: same curve family as the `cubic-spline` lib Spicy Lyrics samples. */
internal class CubicSpline(private val xs: DoubleArray, private val ys: DoubleArray) {
    private val m = DoubleArray(xs.size)   // second derivatives; natural boundary = 0 at both ends

    init {
        val n = xs.size
        if (n > 2) {
            val a = DoubleArray(n); val b = DoubleArray(n); val c = DoubleArray(n); val d = DoubleArray(n)
            b[0] = 1.0; b[n - 1] = 1.0
            for (i in 1 until n - 1) {
                val h0 = xs[i] - xs[i - 1]; val h1 = xs[i + 1] - xs[i]
                a[i] = h0; b[i] = 2 * (h0 + h1); c[i] = h1
                d[i] = 6 * ((ys[i + 1] - ys[i]) / h1 - (ys[i] - ys[i - 1]) / h0)
            }
            for (i in 1 until n) {                      // Thomas algorithm
                val w = a[i] / b[i - 1]
                b[i] -= w * c[i - 1]
                d[i] -= w * d[i - 1]
            }
            m[n - 1] = d[n - 1] / b[n - 1]
            for (i in n - 2 downTo 0) m[i] = (d[i] - c[i] * m[i + 1]) / b[i]
        }
    }

    fun at(x: Float): Float {
        val xd = x.toDouble()
        var i = 0
        while (i < xs.size - 2 && xd > xs[i + 1]) i++
        val h = xs[i + 1] - xs[i]
        val l = xs[i + 1] - xd
        val r = xd - xs[i]
        val y = m[i] * l * l * l / (6 * h) + m[i + 1] * r * r * r / (6 * h) +
            (ys[i] / h - m[i] * h / 6) * l + (ys[i + 1] / h - m[i + 1] * h / 6) * r
        return y.toFloat()
    }
}

/** Underdamped spring (port of Fraktality's `spr`, MIT), the same one Spicy Lyrics steps every frame. */
internal class MotionSpring(start: Float, private val frequency: Float, private val damping: Float) {
    private var p = start.toDouble()
    private var v = 0.0
    private var g = start.toDouble()

    val value: Float get() = p.toFloat()

    fun setGoal(goal: Float) { g = goal.toDouble() }

    fun step(dt: Float): Float {
        val d = damping.toDouble()
        val f = frequency * 2.0 * PI                    // Hz -> rad/s
        val t = dt.toDouble()
        val q = exp(-d * f * t)
        val c = sqrt(1.0 - d * d)
        val i = cos(t * f * c)
        val j = sin(t * f * c)
        val z = j / c
        val y = j / (f * c)
        val o = p - g
        p = (o * (i + z * d) + v * y) * q + g
        v = (v * (i - z * d) - o * (z * f)) * q
        return p.toFloat()
    }

    fun canSleep(): Boolean {
        if (v * v > 1e-4) return false
        val o = p - g
        return o * o <= (1.0 / 3840.0) * (1.0 / 3840.0)
    }
}

internal object LyricsMotion {
    const val PHASE_UPCOMING = 0
    const val PHASE_ACTIVE = 1
    const val PHASE_SUNG = 2

    // Spring constants (Spicy: LyricsAnimator.ts)
    const val SCALE_FREQ = 0.88f;  const val SCALE_DAMP = 0.64f
    const val Y_FREQ = 1.45f;      const val Y_DAMP = 0.4f
    const val GLOW_FREQ = 1.18f;   const val GLOW_DAMP = 0.56f

    // Gradient sweep: position runs -20% -> 100% with a 20% soft edge
    const val GRADIENT_START = -0.2f
    const val GRADIENT_SPAN = 1.2f
    const val GRADIENT_EDGE = 0.2f

    const val EMPHASIS_MIN_MS = 1000L        // syllable this long or longer gets split into letters
    const val LETTER_TAIL_MS = 250L          // letters share [start, end - 250ms]
    const val SUNG_LETTER_GLOW = 0.2f

    const val INTERLUDE_MIN_GAP_MS = 3000L
    const val INTERLUDE_PADDING_MS = -550.0  // dots finish 550ms before the next line
    const val INTERLUDE_PRE_HIDDEN_MS = 500L

    val WordScale = spline(0.0 to 0.95, 0.7 to 1.0505, 1.0 to 1.0)
    val WordY = spline(0.0 to 1 / 100.0, 0.9 to -(1 / 60.0), 1.0 to 0.0)
    val Glow = spline(0.0 to 0.0, 0.15 to 1.0, 0.6 to 1.0, 1.0 to 0.0)
    val LetterScale = spline(0.0 to 0.95, 0.7 to 1.175, 1.0 to 1.0)
    val LetterY = spline(0.0 to 1 / 100.0, 0.9 to -(1 / 56.0), 1.0 to 0.0)

    val DotScale = spline(0.0 to 0.75, 0.7 to 1.05, 1.0 to 1.0)
    val DotY = spline(0.0 to 0.0, 0.9 to -0.12, 1.0 to 0.0)
    val DotGlow = spline(0.0 to 0.0, 0.6 to 1.0, 1.0 to 1.0)
    val DotOpacity = spline(0.0 to 0.35, 0.6 to 1.0, 1.0 to 1.0)

    private fun spline(vararg pts: Pair<Double, Double>) =
        CubicSpline(DoubleArray(pts.size) { pts[it].first }, DoubleArray(pts.size) { pts[it].second })

    fun phaseOf(pos: Long, start: Long, end: Long): Int = when {
        pos < start -> PHASE_UPCOMING
        pos >= end -> PHASE_SUNG
        else -> PHASE_ACTIVE
    }

    fun progressOf(pos: Long, start: Long, end: Long): Float =
        ((pos - start).toFloat() / (end - start).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)

    fun gradientFor(phase: Int, progress: Float): Float = when (phase) {
        PHASE_ACTIVE -> GRADIENT_START + GRADIENT_SPAN * progress
        PHASE_SUNG -> 1f
        else -> GRADIENT_START
    }

    fun easeSinOut(t: Float): Float = sin(t * (PI / 2)).toFloat()
}

/** Springs + gradient for one glyph run (a syllable or a single letter). Fields are read in the DRAW phase. */
@Stable
internal class GlyphMotion(
    scaleSpline: CubicSpline,
    ySpline: CubicSpline,
    progress: Float,
    initialGradient: Float,
) {
    private val scaleSpring = MotionSpring(scaleSpline.at(progress), LyricsMotion.SCALE_FREQ, LyricsMotion.SCALE_DAMP)
    private val ySpring = MotionSpring(ySpline.at(progress), LyricsMotion.Y_FREQ, LyricsMotion.Y_DAMP)
    private val glowSpring = MotionSpring(LyricsMotion.Glow.at(progress), LyricsMotion.GLOW_FREQ, LyricsMotion.GLOW_DAMP)

    var scale by mutableFloatStateOf(scaleSpring.value); private set
    var yOffset by mutableFloatStateOf(ySpring.value); private set
    var glow by mutableFloatStateOf(glowSpring.value); private set
    var gradient by mutableFloatStateOf(initialGradient); private set

    fun setGoals(scaleGoal: Float, yGoal: Float, glowGoal: Float, gradientValue: Float) {
        scaleSpring.setGoal(scaleGoal); ySpring.setGoal(yGoal); glowSpring.setGoal(glowGoal)
        gradient = gradientValue
    }

        fun step(dt: Float) {
        scale = scaleSpring.step(dt)
        yOffset = ySpring.step(dt)
        glow = glowSpring.step(dt)
    }

    fun asleep(): Boolean = scaleSpring.canSleep() && ySpring.canSleep() && glowSpring.canSleep()
}

/** Interlude dot: own spring constants (Spicy `DotAnimations`) plus an opacity spring. */
@Stable
internal class DotMotion(progress: Float) {
    private val scaleSpring = MotionSpring(LyricsMotion.DotScale.at(progress), 0.7f, 0.6f)
    private val ySpring = MotionSpring(LyricsMotion.DotY.at(progress), 1.25f, 0.4f)
    private val glowSpring = MotionSpring(LyricsMotion.DotGlow.at(progress), 1f, 0.5f)
    private val opacitySpring = MotionSpring(LyricsMotion.DotOpacity.at(progress), 1f, 0.5f)

    var scale by mutableFloatStateOf(scaleSpring.value); private set
    var yOffset by mutableFloatStateOf(ySpring.value); private set
    var glow by mutableFloatStateOf(glowSpring.value); private set
    var opacity by mutableFloatStateOf(opacitySpring.value); private set

    fun setGoals(progress: Float) {
        scaleSpring.setGoal(LyricsMotion.DotScale.at(progress))
        ySpring.setGoal(LyricsMotion.DotY.at(progress))
        glowSpring.setGoal(LyricsMotion.DotGlow.at(progress))
        opacitySpring.setGoal(LyricsMotion.DotOpacity.at(progress))
    }

    fun step(dt: Float) {
        scale = scaleSpring.step(dt); yOffset = ySpring.step(dt)
        glow = glowSpring.step(dt); opacity = opacitySpring.step(dt)
    }

    fun asleep(): Boolean =
        scaleSpring.canSleep() && ySpring.canSleep() && glowSpring.canSleep() && opacitySpring.canSleep()
}

/**
 * A held syllable split into letters. The active letter peaks (scale/lift/glow) and its neighbours
 * follow with Spicy's falloff (1/(1+d^2.8) for scale+lift, 1/(1+0.9d) for glow), so a wave rolls
 * through the word.
 */
@Stable
internal class EmphasisMotion(letterCount: Int, initialProgress: Float) {
    val word = GlyphMotion(LyricsMotion.WordScale, LyricsMotion.WordY, initialProgress, 0f)
    val letters: List<GlyphMotion> = List(letterCount) {
        GlyphMotion(
            LyricsMotion.LetterScale, LyricsMotion.LetterY, initialProgress,
            if (initialProgress >= 1f) 1f else LyricsMotion.GRADIENT_START,
        )
    }

    /** Returns true when nothing is moving and the syllable is not ACTIVE (safe to stop the frame loop). */
    fun update(posMs: Long, startMs: Long, endMs: Long, dt: Float): Boolean {
        val phase = LyricsMotion.phaseOf(posMs, startMs, endMs)
        val wp = when (phase) {
            LyricsMotion.PHASE_ACTIVE -> LyricsMotion.progressOf(posMs, startMs, endMs)
            LyricsMotion.PHASE_SUNG -> 1f
            else -> 0f
        }
        word.setGoals(LyricsMotion.WordScale.at(wp), LyricsMotion.WordY.at(wp), LyricsMotion.Glow.at(wp), 0f)
        word.step(dt)
        var settled = phase != LyricsMotion.PHASE_ACTIVE && word.asleep()

        val n = letters.size
        val slice = (endMs - startMs - LyricsMotion.LETTER_TAIL_MS).coerceAtLeast(1L).toFloat() / n
        val rel = (posMs - startMs).toFloat()
        var activeIdx = -1
        var activePct = 0f
        if (phase == LyricsMotion.PHASE_ACTIVE && rel >= 0f) {
            val idx = (rel / slice).toInt()
            if (idx in 0 until n) { activeIdx = idx; activePct = (rel - idx * slice) / slice }
        }

        val restScale = LyricsMotion.LetterScale.at(0f)
        val restY = LyricsMotion.LetterY.at(0f)
        val restGlow = LyricsMotion.Glow.at(0f)

        for (k in 0 until n) {
            val letterStart = k * slice
            val letterPhase = when {
                rel < letterStart -> LyricsMotion.PHASE_UPCOMING
                rel >= letterStart + slice -> LyricsMotion.PHASE_SUNG
                else -> LyricsMotion.PHASE_ACTIVE
            }
            var tScale = restScale
            var tY = restY
            var tGlow = restGlow
            var tGrad = LyricsMotion.GRADIENT_START

            when (phase) {
                LyricsMotion.PHASE_SUNG -> {
                    tScale = LyricsMotion.LetterScale.at(1f)
                    tY = LyricsMotion.LetterY.at(1f)
                    tGlow = LyricsMotion.Glow.at(1f)
                    tGrad = 1f
                }
                LyricsMotion.PHASE_ACTIVE -> {
                    if (activeIdx != -1) {
                        val dist = abs(k - activeIdx).toFloat()
                        val falloff = 1f / (1f + dist.pow(2.8f))
                        val glowFalloff = 1f / (1f + dist * 0.9f)
                        tScale += (LyricsMotion.LetterScale.at(activePct) - restScale) * falloff
                        tY += (LyricsMotion.LetterY.at(activePct) - restY) * falloff
                        tGlow += (LyricsMotion.Glow.at(activePct) - restGlow) * glowFalloff
                    }
                    if (letterPhase == LyricsMotion.PHASE_UPCOMING) {
                        tScale = restScale; tY = restY; tGlow = restGlow
                    } else if (letterPhase == LyricsMotion.PHASE_SUNG && activeIdx == -1) {
                        tGlow = LyricsMotion.Glow.at(LyricsMotion.SUNG_LETTER_GLOW)
                    }
                    tGrad = when (letterPhase) {
                        LyricsMotion.PHASE_UPCOMING -> LyricsMotion.GRADIENT_START
                        LyricsMotion.PHASE_SUNG -> 1f
                        else -> if (k == activeIdx) {
                            LyricsMotion.GRADIENT_START + LyricsMotion.GRADIENT_SPAN * LyricsMotion.easeSinOut(activePct)
                        } else LyricsMotion.GRADIENT_START
                    }
                }
            }
            letters[k].setGoals(tScale, tY, tGlow, tGrad)
            letters[k].step(dt)
            if (!letters[k].asleep()) settled = false
        }
        return settled
    }
}

/**
 * Smooth playback clock. The VM polls position every 250ms; between ticks this extrapolates with
 * real time at the playback [speed]. While [isPlaying] is false it holds exactly at the last tick (no drift on pause).
 * Extrapolation is also capped at [MAX_EXTRAPOLATION_MS] so a stalled/buffering player can't run ahead.
 */
@Composable
internal fun rememberSmoothPositionMs(positionMs: Long, isPlaying: Boolean = true, speed: Float = 1f): State<Long> {
    val smooth = remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, isPlaying, speed) {
        smooth.longValue = positionMs
        if (!isPlaying) return@LaunchedEffect
        val base = System.nanoTime()
        while (true) {
            withFrameNanos { }                                   // pacing only; time comes from nanoTime
            val elapsedMs = ((System.nanoTime() - base) / 1_000_000L).coerceIn(0L, MAX_EXTRAPOLATION_MS)
            smooth.longValue = extrapolatedPositionMs(positionMs, elapsedMs, speed)
            if (elapsedMs >= MAX_EXTRAPOLATION_MS) break
        }
    }
    return smooth
}

/**
 * Where the song is [elapsedMs] of real time after a [positionMs] tick. At 1x a 0.5x song would
 * run ahead and snap back every tick, and a 2x one lag and jump: the words flicker.
 */
internal fun extrapolatedPositionMs(positionMs: Long, elapsedMs: Long, speed: Float): Long =
    positionMs + (elapsedMs * speed).toLong()

private const val MAX_EXTRAPOLATION_MS = 400L