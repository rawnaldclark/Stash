package com.stash.core.ui.components.motion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * The geometry and spring choice behind the tab bar's liquid pill (design: namida-study
 * 2026-10-18, stash-tab-bar-v2.html, `movePill` / `drawPill`). Kept free of Compose so the
 * rules are unit-tested on the JVM; the bar feeds these numbers to two `Animatable`s.
 *
 * The pill is two independent edges, each on its own spring. The edge on the side the pill is
 * travelling towards is stiff and a little bouncy, the trailing edge soft, so the pill
 * stretches on the way and settles with a small overshoot. The prototype's spring is
 * mass-1 `x'' = -k(x - target) - 2ζ√k·v`, stepped by hand with semi-implicit Euler ([EdgeSpring]),
 * so a new target mid-flight keeps the edge's velocity and the pill bends toward it.
 */

/** One spring's parameters, in Compose's `spring(dampingRatio, stiffness)` terms. */
data class SpringParams(val stiffness: Float, val dampingRatio: Float)

/** The springs for the pill's two edges for one move. */
data class EdgeSprings(val left: SpringParams, val right: SpringParams)

/** Where the pill's left and right edges should come to rest, in px along the bar. */
data class PillEdges(val left: Float, val right: Float)

/** The liquid pill's rules. All distances are in the caller's unit (px in the bar). */
object LiquidPill {
    /** The leading edge: quick and slightly underdamped, so it overshoots a touch. */
    val Leading = SpringParams(stiffness = 620f, dampingRatio = 0.72f)

    /** The trailing edge: soft, catching up behind the leading one. */
    val Trailing = SpringParams(stiffness = 210f, dampingRatio = 0.82f)

    /**
     * The horizontal centre of tab [index] of [count] equal-width tabs laid out across [width],
     * inside [inset] of padding on each side (the capsule's 4 dp). Mirrored for right-to-left.
     */
    fun tabCenter(index: Int, count: Int, width: Float, inset: Float, rtl: Boolean = false): Float {
        require(count > 0) { "count must be positive" }
        require(index in 0 until count) { "index $index out of 0 until $count" }
        val tabWidth = (width - 2f * inset) / count
        val ltr = inset + (index + 0.5f) * tabWidth
        return if (rtl) width - ltr else ltr
    }

    /** The resting edges of a pill [pillWidth] wide centred on [center]. */
    fun edges(center: Float, pillWidth: Float): PillEdges =
        PillEdges(left = center - pillWidth / 2f, right = center + pillWidth / 2f)

    /**
     * The springs for a move whose right edge goes to [targetRight] from where it is now,
     * [currentRight]. Moving right, the right edge leads; otherwise the left edge does (the
     * prototype's `right = r > R.x`, so a move to the same spot counts as leftward, which is
     * harmless: neither edge travels).
     */
    fun springsFor(targetRight: Float, currentRight: Float): EdgeSprings =
        if (targetRight > currentRight) {
            EdgeSprings(left = Trailing, right = Leading)
        } else {
            EdgeSprings(left = Leading, right = Trailing)
        }

    /** The width drawn for edges at [left] and [right]: never narrower than [minWidth]. */
    fun drawnWidth(left: Float, right: Float, minWidth: Float): Float = max(right - left, minWidth)

    /** The prototype's sub-steps per frame (`for (i < 4) step(dt / 4)`). */
    const val SUBSTEPS = 4

    /** The longest step the springs take between two frames: a long frame pauses them, never flings them. */
    const val MAX_STEP_SECONDS = 1f / 30f

    /**
     * The seconds the springs advance for [elapsedNanos] of frame time: clamped to
     * [MAX_STEP_SECONDS], then slowed by the system's animator [durationScale] (the prototype's
     * `min(dt, 1/30) / slow`). A non-positive scale is "animations off" and is the caller's to snap.
     */
    fun frameStep(elapsedNanos: Long, durationScale: Float): Float {
        val seconds = (elapsedNanos.coerceAtLeast(0L) / 1e9f).coerceAtMost(MAX_STEP_SECONDS)
        return if (durationScale > 0f) seconds / durationScale else seconds
    }

    /**
     * The pill as drawn for spring edges [left]..[right] whose rest is [restLeft]..[restRight], in
     * a bar whose inner walls are [wallLeft] and [wallRight]: an edge that overshoots past a wall
     * squishes against it (unless the rest itself is past the wall) instead of being cut off by the
     * capsule. Never narrower than [minWidth]. Only the drawing is clamped; the springs run free.
     */
    fun drawnEdges(
        left: Float,
        right: Float,
        restLeft: Float,
        restRight: Float,
        wallLeft: Float,
        wallRight: Float,
        minWidth: Float,
    ): PillEdges {
        val r = min(right, max(wallRight, restRight))
        val l = max(left, min(wallLeft, restLeft))
        return PillEdges(l, l + drawnWidth(l, r, minWidth))
    }
}

/**
 * One edge of the pill on its own mass-1 spring, stepped by hand as the prototype does:
 * semi-implicit Euler, [LiquidPill.SUBSTEPS] sub-steps a frame. [retarget] changes only the
 * target and the spring, never the velocity, so a tap mid-flight bends the motion instead of
 * restarting it.
 */
class EdgeSpring(position: Float = 0f) {
    /** Where the edge is now. */
    var position: Float = position
        private set

    /** Its velocity, in units per second. */
    var velocity: Float = 0f
        private set

    /** Where it is heading. */
    var target: Float = position
        private set

    /** The spring it is on. */
    var params: SpringParams = LiquidPill.Trailing
        private set

    /** Jumps to [value] and stops there. */
    fun snapTo(value: Float) {
        position = value
        target = value
        velocity = 0f
    }

    /** Heads for [newTarget] on [spring], keeping the current velocity. */
    fun retarget(newTarget: Float, spring: SpringParams) {
        target = newTarget
        params = spring
    }

    /** Advances [seconds] of motion in [substeps] semi-implicit Euler steps. */
    fun advance(seconds: Float, substeps: Int = LiquidPill.SUBSTEPS) {
        if (seconds <= 0f) return
        val h = seconds / substeps
        val k = params.stiffness
        val c = 2f * params.dampingRatio * sqrt(k)
        repeat(substeps) {
            val a = -k * (position - target) - c * velocity
            velocity += a * h
            position += velocity * h
        }
    }

    /** Whether it has come to rest: within [positionTolerance] of the target and slower than [velocityTolerance]. */
    fun isSettled(positionTolerance: Float, velocityTolerance: Float): Boolean =
        abs(position - target) < positionTolerance && abs(velocity) < velocityTolerance
}
