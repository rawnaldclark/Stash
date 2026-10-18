package com.stash.core.ui.components.motion

import kotlin.math.max

/*
 * The geometry and spring choice behind the tab bar's liquid pill (design: namida-study
 * 2026-10-18, stash-tab-bar-v2.html, `movePill` / `drawPill`). Kept free of Compose so the
 * rules are unit-tested on the JVM; the bar feeds these numbers to two `Animatable`s.
 *
 * The pill is two independent edges, each on its own spring. The edge on the side the pill is
 * travelling towards is stiff and a little bouncy, the trailing edge soft, so the pill
 * stretches on the way and settles with a small overshoot. The prototype's spring is
 * mass-1 `x'' = -k(x - target) - 2ζ√k·v`, the same model as Compose's `spring(ζ, k)`.
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
}
