package com.stash.feature.nowplaying.ui

import kotlin.math.abs
import kotlin.math.sign

/*
 * The decisions behind the album art's gestures (swipe to skip, double-tap a side to seek), kept
 * out of the composable so they can be pinned by plain JVM tests. The mini player's swipe uses the
 * same threshold ([swipeSkipOutcome]), so the two surfaces commit a skip on the same flick.
 */

/** What a horizontal swipe asks for when the finger lifts. */
internal enum class SwipeSkip { NEXT, PREVIOUS, NONE }

/**
 * How far the art must travel, as a fraction of its width, to skip without a fling. A third: far
 * enough that a sideways wobble on the way to the transport buttons never skips, short enough to
 * reach with a thumb on a big art.
 */
internal const val SWIPE_SKIP_DISTANCE_FRACTION = 0.3f

/**
 * A quick flick skips from a shorter travel, but not from nothing: below this fraction of the width
 * a fast flick is a tap that slid, and skipping on it surprised people in testing other players.
 */
internal const val SWIPE_SKIP_FLING_MIN_FRACTION = 0.08f

/** Fling speed (dp/s; the caller converts to px) that commits a skip from a short travel. */
internal const val SWIPE_SKIP_FLING_DP_PER_SEC = 900f

/**
 * How much of the finger's travel the art follows toward a side with nothing there (no next song at
 * the end of a queue without repeat, no previous at its start): a rubber band that says "nothing
 * that way" without a dead, unmoving art.
 */
internal const val BLOCKED_SWIPE_RESISTANCE = 0.3f

/**
 * True when a drag of [offsetPx] (negative = the finger moved left) points at the next song. In a
 * left-to-right layout the next song waits on the right, so pulling the art left brings it in, as
 * a pager would; a right-to-left layout mirrors that, matching its mirrored transport row.
 */
internal fun isTowardNext(offsetPx: Float, rtl: Boolean): Boolean =
    if (rtl) offsetPx > 0f else offsetPx < 0f

/**
 * The skip a swipe asks for when the finger lifts after [offsetPx] of travel at [velocityPxPerSec]
 * (both signed, px, negative = left) on an art [widthPx] wide.
 *
 * It commits when the art travelled [SWIPE_SKIP_DISTANCE_FRACTION] of its width, or was flung at
 * [flingThresholdPxPerSec] or faster in the direction it travelled (after at least
 * [SWIPE_SKIP_FLING_MIN_FRACTION]). A fling back the other way cancels even a long drag: the
 * listener changed their mind. A side with nothing there ([canNext] / [canPrevious] false) never
 * commits, so the art springs back instead of skipping into nothing.
 */
internal fun swipeSkipOutcome(
    offsetPx: Float,
    velocityPxPerSec: Float,
    widthPx: Float,
    flingThresholdPxPerSec: Float,
    canNext: Boolean,
    canPrevious: Boolean,
    rtl: Boolean = false,
): SwipeSkip {
    if (widthPx <= 0f || offsetPx == 0f) return SwipeSkip.NONE
    val travel = abs(offsetPx) / widthPx
    val fast = abs(velocityPxPerSec) >= flingThresholdPxPerSec
    val sameWay = sign(velocityPxPerSec) == sign(offsetPx)
    if (fast && !sameWay) return SwipeSkip.NONE
    val committed = travel >= SWIPE_SKIP_DISTANCE_FRACTION ||
        (fast && sameWay && travel >= SWIPE_SKIP_FLING_MIN_FRACTION)
    if (!committed) return SwipeSkip.NONE
    return if (isTowardNext(offsetPx, rtl)) {
        if (canNext) SwipeSkip.NEXT else SwipeSkip.NONE
    } else {
        if (canPrevious) SwipeSkip.PREVIOUS else SwipeSkip.NONE
    }
}

/**
 * Where the art is drawn for a finger [rawOffsetPx] away from where it started: the full travel
 * toward a side that has a song, [BLOCKED_SWIPE_RESISTANCE] of it toward one that doesn't.
 */
internal fun swipeVisualOffset(rawOffsetPx: Float, canNext: Boolean, canPrevious: Boolean, rtl: Boolean = false): Float {
    if (rawOffsetPx == 0f) return 0f
    val allowed = if (isTowardNext(rawOffsetPx, rtl)) canNext else canPrevious
    return if (allowed) rawOffsetPx else rawOffsetPx * BLOCKED_SWIPE_RESISTANCE
}

/**
 * Whether the transport's Next has somewhere to go, as Media3 decides it for the queue the sheet
 * shows ([currentIndex] of [queueSize], in play order, so shuffle is already accounted for): a
 * later song, or a wrap to the first under repeat-all. Repeat-one doesn't count: Media3 navigates
 * as if repeat were off, so Next at the end of the queue does nothing there either.
 */
internal fun hasNextInQueue(queueSize: Int, currentIndex: Int, repeatAll: Boolean): Boolean =
    queueSize > 0 && (currentIndex < queueSize - 1 || repeatAll)

/** [hasNextInQueue]'s mirror for Previous: an earlier song, or a wrap to the last under repeat-all. */
internal fun hasPreviousInQueue(queueSize: Int, currentIndex: Int, repeatAll: Boolean): Boolean =
    queueSize > 0 && (currentIndex > 0 || repeatAll)

/**
 * The seek a double-tap at [xPx] on an art [widthPx] wide asks for: -1 (back) in the left third,
 * +1 (forward) in the right third, 0 in the middle. Physical left and right, like the progress bar,
 * whose time runs left to right in every layout.
 */
internal fun doubleTapSeekDirection(xPx: Float, widthPx: Float): Int = when {
    widthPx <= 0f -> 0
    xPx < widthPx / 3f -> -1
    xPx > widthPx * 2f / 3f -> 1
    else -> 0
}

/**
 * True when a tap at [xPx] on an art [widthPx] wide flips the cover the moment it lifts: the album
 * has a back ([canFlip]) and no double-tap seek can start there, off the seek thirds or with no
 * seeking at all ([seeks]: a listener, an unknown duration). On a seek third the art must first
 * wait out the double-tap window ([loneSeekTapFlips]).
 */
internal fun flipsOnFirstTap(canFlip: Boolean, seeks: Boolean, xPx: Float, widthPx: Float): Boolean =
    canFlip && (!seeks || doubleTapSeekDirection(xPx, widthPx) == 0)

/**
 * True when a lone tap on a seek third, no second tap having come, flips the cover: unless it lands
 * within [SEEK_BURST_WINDOW_MS] of a double-tap seek ([burst], at [nowMs] on the same clock), where
 * it reads as one more tap of the run, not a flip.
 */
internal fun loneSeekTapFlips(canFlip: Boolean, burst: SeekBurst?, nowMs: Long): Boolean =
    canFlip && (burst == null || nowMs - burst.atMs !in 0..SEEK_BURST_WINDOW_MS)

/**
 * Double-taps landing within this long of the previous one, the same way, add up ("+20 s"), each
 * starting from the last one's target: the player's reported position lags a seek by a tick or
 * two, and seeking from it again would land the second tap on the first one's spot.
 */
internal const val SEEK_BURST_WINDOW_MS = 1_200L

/**
 * A run of double-tap seeks the same way: [direction] (-1 back, +1 forward), how far it has moved
 * in total ([totalMs], for the "+20 s" indicator), where the last tap sent the player
 * ([targetMs]), and when ([atMs], on the caller's clock).
 */
internal data class SeekBurst(val direction: Int, val totalMs: Long, val targetMs: Long, val atMs: Long)

/**
 * The burst after a double-tap seeking [direction] at [nowMs], with the player at [positionMs] of a
 * [durationMs] song: a continuation of [previous] when it went the same way within
 * [SEEK_BURST_WINDOW_MS], else a new burst from [positionMs]. Steps are [SEEK_STEP_MS], kept inside
 * the song ([seekStepMs]).
 */
internal fun nextSeekBurst(
    previous: SeekBurst?,
    direction: Int,
    nowMs: Long,
    positionMs: Long,
    durationMs: Long,
): SeekBurst {
    val continued = previous?.takeIf { it.direction == direction && nowMs - it.atMs in 0..SEEK_BURST_WINDOW_MS }
    val from = continued?.targetMs ?: positionMs
    return SeekBurst(
        direction = direction,
        totalMs = (continued?.totalMs ?: 0L) + SEEK_STEP_MS,
        targetMs = seekStepMs(from, durationMs, direction * SEEK_STEP_MS),
        atMs = nowMs,
    )
}

/** The indicator's text for [burst]: "−10 s" or "+10 s" (a true minus sign), "+20 s" for a run. */
internal fun seekBurstLabel(burst: SeekBurst): String =
    "${if (burst.direction < 0) "−" else "+"}${burst.totalMs / 1_000L} s"
