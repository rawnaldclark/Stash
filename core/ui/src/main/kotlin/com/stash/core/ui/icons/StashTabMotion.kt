package com.stash.core.ui.icons

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/*
 * The tab bar's motion vocabulary (design: namida-study 2026-10-18, stash-tab-bar-v2.html): the
 * prototype's CSS curves, a frame clock that a layout hitch can't make jump, and CSS keyframe
 * sampling. Shared by the glyphs (this package) and the bar (app's StashTabBar).
 */

/**
 * The prototype's CSS curves, shared by the glyphs and the bar so both move on the same curves.
 * Several overshoot (a y control point above 1), which CubicBezierEasing evaluates as CSS does.
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

    /**
     * The longest step any tab move takes between two frames (the prototype's `dt ≤ 1/30 s`). The
     * frame after a tab tap can spend ~150 ms laying out the new screen; a move clocked on raw
     * frame time would leap that far ahead when it lands. Clamped, it just resumes.
     */
    const val MAX_FRAME_STEP_NANOS: Long = 1_000_000_000L / 30
}

/**
 * A frame clock whose time never advances more than [maxStepNanos] between two frames, so the
 * animations run under it pause through a long frame instead of skipping ahead. Every caller in
 * the same frame sees the same time.
 */
class ClampedFrameClock(
    private val base: MonotonicFrameClock,
    private val maxStepNanos: Long = StashTabMotion.MAX_FRAME_STEP_NANOS,
) : MonotonicFrameClock {
    private var lastRealNanos = Long.MIN_VALUE
    private var virtualNanos = 0L

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R =
        base.withFrameNanos { real -> onFrame(advance(real)) }

    /** The clamped time for the real frame time [real]. Exposed for the unit test. */
    internal fun advance(real: Long): Long {
        if (lastRealNanos == Long.MIN_VALUE) {
            virtualNanos = real
        } else if (real > lastRealNanos) {
            virtualNanos += min(real - lastRealNanos, maxStepNanos)
        }
        lastRealNanos = real
        return virtualNanos
    }
}

/**
 * Runs [block] with Compose's animations on a [ClampedFrameClock]: a layout hitch pauses them
 * rather than making them jump. Outside a frame clock (a plain test) it just runs [block].
 */
suspend fun <R> withClampedFrameClock(block: suspend CoroutineScope.() -> R): R {
    val base = coroutineContext[MonotonicFrameClock] ?: return coroutineScope(block)
    return withContext(ClampedFrameClock(base), block)
}

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
