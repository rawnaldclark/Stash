package com.stash.feature.nowplaying.sheet

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.exp

/*
 * The player sheet's numbers: where a drag lands, and what every piece of the player looks like at
 * a given progress. Pure functions of the one float the sheet is (0 = the mini player, 1 = Now
 * Playing, 2 = the queue in a later phase), so the whole feel is unit-tested here and the composables
 * only read these from graphicsLayer / drawBehind / placement lambdas, never from composition.
 *
 * The thresholds and curves are Namida's (lib/controller/miniplayer_controller.dart:194-251, 441-571):
 * its logical pixels are dp. They sit behind named constants so they can be tuned on a device.
 */

/** The sheet's resting places. [value] is the progress each one sits at. */
enum class PlayerSheetAnchor(val value: Float) {
    /** The glass mini player above the tab bar. */
    MINI(0f),

    /** Now Playing, full screen. */
    FULL(1f),

    /** The queue over Now Playing (phase B; never a target while the queue anchor is off). */
    QUEUE(2f),
}

object PlayerSheetMath {

    /** A release faster than this (dp/s) is a flick: its direction decides, whatever the distance. */
    const val FLING_DP_S = 500f

    /** A drag at least this long (dp) moves to the next anchor even when released slowly. */
    const val ACTUATION_DP = 100f

    /** The actuation distance when there is no bottom navigation under the mini player. */
    const val ACTUATION_NO_NAV_DP = 60f

    /** Downs this close to the screen's bottom edge are the system's (gesture bar), never the sheet's. */
    const val DEAD_ZONE_DP = 12f

    /** Every snap takes this long; only the curve differs by destination. */
    const val SNAP_MS = 300

    /** The rubber band's limits: how far past MINI / the top anchor a drag or an overshoot may go. */
    const val MIN_PROGRESS = -0.05f
    const val MAX_PROGRESS_PHASE_A = 1.03f
    const val MAX_PROGRESS = 2.03f

    /** To MINI: a small bounce as the cover lands (Namida's `_bouncingCurve`). */
    val ToMiniEasing: Easing = CubicBezierEasing(0.175f, 0.885f, 0.32f, 1.125f)

    /** To FULL: fast in, long soft landing (Flutter's `fastEaseInToSlowEaseOut`). */
    val ToFullEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** To QUEUE: a softer overshoot. */
    val ToQueueEasing: Easing = CubicBezierEasing(0.15f, 0.96f, 0.28f, 1.04f)

    fun easingFor(target: PlayerSheetAnchor): Easing = when (target) {
        PlayerSheetAnchor.MINI -> ToMiniEasing
        PlayerSheetAnchor.FULL -> ToFullEasing
        PlayerSheetAnchor.QUEUE -> ToQueueEasing
    }

    /** The anchor a gesture starting at [progress] counts as starting from (Namida's three zones). */
    fun startAnchorOf(progress: Float): PlayerSheetAnchor = when {
        progress > 1f -> PlayerSheetAnchor.QUEUE
        progress > 0.5f -> PlayerSheetAnchor.FULL
        else -> PlayerSheetAnchor.MINI
    }

    /**
     * Where a released drag settles. Positive [distanceDp] and [velocityDpS] point up (towards the
     * queue). A flick (|velocity| > [flingDpS]) goes the way it points, one anchor at most; otherwise
     * a drag of more than [actuationDp] does; anything less returns to [start].
     *
     * Unlike Namida, a flick against the drag wins (drag up 300 dp, flick down: back to MINI), so a
     * change of mind mid-gesture is honoured.
     */
    fun settleTarget(
        start: PlayerSheetAnchor,
        distanceDp: Float,
        velocityDpS: Float,
        actuationDp: Float = ACTUATION_DP,
        flingDpS: Float = FLING_DP_S,
        queueEnabled: Boolean = false,
    ): PlayerSheetAnchor {
        // +1 = up, -1 = down, 0 = neither.
        val direction = when {
            velocityDpS > flingDpS -> 1
            velocityDpS < -flingDpS -> -1
            distanceDp > actuationDp -> 1
            distanceDp < -actuationDp -> -1
            else -> 0
        }
        return when (start) {
            PlayerSheetAnchor.MINI -> if (direction > 0) PlayerSheetAnchor.FULL else PlayerSheetAnchor.MINI
            PlayerSheetAnchor.FULL -> when {
                direction < 0 -> PlayerSheetAnchor.MINI
                direction > 0 && queueEnabled -> PlayerSheetAnchor.QUEUE
                else -> PlayerSheetAnchor.FULL
            }
            PlayerSheetAnchor.QUEUE -> if (direction < 0) PlayerSheetAnchor.FULL else PlayerSheetAnchor.QUEUE
        }
    }

    /**
     * The velocity a release counts with: the tracker's [velocityDpS], unless the finger's last
     * moments ([recentUpDp], its movement over the final ~20 ms) don't go the same way. A finger
     * that stopped before lifting, or turned back by a hair, isn't flicking: then the distance
     * decides. Keeps the "a flick against the drag wins" rule from firing on a tiny reverse
     * twitch at lift.
     */
    fun releaseVelocity(velocityDpS: Float, recentUpDp: Float): Float =
        if (velocityDpS == 0f || recentUpDp == 0f || (velocityDpS > 0f) != (recentUpDp > 0f)) 0f else velocityDpS

    /**
     * Maps a finger's unbounded progress [raw] onto the sheet: 1:1 between [lower] .. [upper]'s
     * anchors (0 and the top anchor), then a rubber band that approaches the limits but never
     * reaches them, so a drag past either end resists more the further it goes.
     */
    fun rubberBand(raw: Float, topAnchor: Float, lower: Float = MIN_PROGRESS, upper: Float): Float {
        val below = -lower // > 0
        val above = upper - topAnchor // > 0
        return when {
            raw < 0f -> -below * (1f - exp(raw / below))
            raw > topAnchor -> topAnchor + above * (1f - exp(-(raw - topAnchor) / above))
            else -> raw
        }
    }

    /** 0 before [start], 1 after [end], linear between. Safe on overshoot (always clamped). */
    fun ramp(p: Float, start: Float, end: Float): Float =
        ((p - start) / (end - start)).coerceIn(0f, 1f)

    /** [a] → [b] by [t]; [t] may be outside 0..1 (extrapolates). */
    fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
        left = a.left + (b.left - a.left) * t,
        top = a.top + (b.top - a.top) * t,
        right = a.right + (b.right - a.right) * t,
        bottom = a.bottom + (b.bottom - a.bottom) * t,
    )

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** The MINI → FULL part of the progress, clamped. */
    fun cp(p: Float): Float = p.coerceIn(0f, 1f)

    /** The card: from the mini player's rect to the whole screen. */
    fun cardRect(mini: Rect, screen: Rect, p: Float): Rect = lerpRect(mini, screen, cp(p))

    /** The card's corners: the mini player's 12 dp, square only over the last 10 %. */
    fun cardCornerDp(p: Float, miniCornerDp: Float = MINI_CORNER_DP): Float =
        miniCornerDp * (1f - ramp(p, 0.9f, 1f))

    /** The card's own ground (the theme background) fades in over the first 10 %. */
    fun cardGroundAlpha(p: Float): Float = ramp(p, 0f, 0.1f)

    /** Now Playing's ambient glow. */
    fun ambientAlpha(p: Float): Float = ramp(p, 0.1f, 0.7f)

    /** Now Playing's controls (top row, title stack, transport) only in the last quarter. */
    fun controlsAlpha(p: Float): Float = ramp(p, 0.75f, 1f)

    /** The controls rise this far (dp, times 1 - alpha) as they fade in. */
    const val CONTROLS_RISE_DP = 24f

    /** The controls' rise in dp at [p]. */
    fun controlsRiseDp(p: Float): Float = (1f - controlsAlpha(p)) * CONTROLS_RISE_DP

    /** The mini player's own content (title, artist, buttons) fades out over the first 15 %. */
    fun miniContentAlpha(p: Float): Float = 1f - ramp(p, 0f, 0.15f)

    /** The mini player's cover: hidden whenever the traveller has it (any progress above MINI). */
    fun miniArtAlpha(p: Float): Float = if (p > 0f) 0f else 1f

    /** Now Playing's own big cover shows only at rest at FULL (the traveller carries it otherwise). */
    fun heroArtAlpha(p: Float): Float = if (p >= 1f) 1f else 0f

    /** The cover traveller flies only between the anchors. */
    fun travellerVisible(p: Float): Boolean = p > 0f && p < 1f

    /** The page behind shrinks 5 % as the player opens. */
    fun pageScale(p: Float): Float = 1f - 0.05f * cp(p)

    /** And darkens under a black scrim. */
    fun scrimAlpha(p: Float): Float = 0.5f * cp(p)

    /** The tab bar slides away by this fraction of its height, gone by 60 %. */
    fun tabBarHide(p: Float): Float = ramp(p, 0f, 0.6f)

    /**
     * And fades out much faster, gone by 12 %: about when its labels would reach the system's
     * 3-button bar below it (measured on a Pixel 5), so they never show through it.
     */
    fun tabBarAlpha(p: Float): Float = 1f - ramp(p, 0f, 0.12f)

    /** The glass blur is off while the sheet is anywhere but MINI (a moving backdrop costs a blur a frame). */
    fun blurEnabled(p: Float): Boolean = abs(p) < BLUR_OFF_ABOVE

    const val BLUR_OFF_ABOVE = 0.01f

    /** The traveller's corner (dp, on screen): the mini cover's 6 dp to the hero's. */
    fun travellerCornerDp(p: Float, heroCornerDp: Float): Float = lerp(MINI_ART_CORNER_DP, heroCornerDp, cp(p))

    /**
     * How far (px) the mini player dips below its place while the sheet is below MINI (the rubber
     * band, or the bounce of the MINI curve), so that landing is felt; capped at [MINI_DIP_MAX_DP].
     */
    fun miniDipPx(p: Float, travelPx: Float, density: Float): Float =
        if (p >= 0f) 0f else (-p * travelPx * MINI_DIP_FACTOR).coerceAtMost(MINI_DIP_MAX_DP * density)

    /** How far (px) Now Playing's content rises while a drag pulls past FULL (phase A's rubber band). */
    fun overFullRisePx(p: Float, travelPx: Float): Float =
        if (p <= 1f) 0f else (p - 1f).coerceAtMost(1f) * travelPx * OVER_FULL_FACTOR

    const val MINI_CORNER_DP = 12f
    const val MINI_ART_CORNER_DP = 6f
    const val HERO_ART_CORNER_DP = 20f
    private const val MINI_DIP_FACTOR = 0.35f
    private const val MINI_DIP_MAX_DP = 14f
    private const val OVER_FULL_FACTOR = 0.5f
}
