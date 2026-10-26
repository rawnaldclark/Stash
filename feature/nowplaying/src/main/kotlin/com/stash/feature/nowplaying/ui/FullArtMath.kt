package com.stash.feature.nowplaying.ui

import androidx.compose.ui.geometry.Rect
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** The shortest and longest a page stays behind the controls in full art while a song plays. */
internal const val FULL_ART_MIN_HOLD_MS = 10_000L
internal const val FULL_ART_MAX_HOLD_MS = 40_000L

/** The hold when the song's length isn't known (a live stream): the pages go round at this pace. */
internal const val FULL_ART_UNKNOWN_HOLD_MS = 20_000L

/** The art's height in full art at most, of the screen's: the controls sit over its lower part. */
internal const val FULL_ART_MAX_HEIGHT = 0.62f

/**
 * How the song paces the pages in full art: which page each stretch of the song shows ([pages],
 * indexes into the album's pages, in order) and how long each stretch is ([holdMs]). The stretches
 * share the song evenly, so the last page ends with the song. [endless] when the song's length
 * isn't known: the pages then go round for as long as it plays.
 */
internal data class FullArtSchedule(val pages: List<Int>, val holdMs: Long, val endless: Boolean = false) {
    /** The stretch playing at [positionMs]: past the last one only when [endless]. */
    fun segmentAt(positionMs: Long): Int {
        val segment = (positionMs.coerceAtLeast(0L) / holdMs).toInt()
        return if (endless) segment else segment.coerceAtMost(pages.size - 1)
    }

    /** The page stretch [segment] shows. */
    fun pageAt(segment: Int): Int = pages[segment.mod(pages.size)]
}

/**
 * The pages for a song of [durationMs] over an album of [pageCount] pages: each page held
 * between [FULL_ART_MIN_HOLD_MS] and [FULL_ART_MAX_HOLD_MS]. A long song goes round the pages as
 * many times as that takes; a short one shows the first pages it has time for (the front and the
 * back first). An unknown length goes round at [FULL_ART_UNKNOWN_HOLD_MS].
 */
internal fun fullArtSchedule(durationMs: Long, pageCount: Int): FullArtSchedule {
    val count = pageCount.coerceAtLeast(1)
    if (durationMs <= 0L) return FullArtSchedule((0 until count).toList(), FULL_ART_UNKNOWN_HOLD_MS, endless = true)
    val pages = if (durationMs / count < FULL_ART_MIN_HOLD_MS) {
        (0 until (durationMs / FULL_ART_MIN_HOLD_MS).toInt().coerceIn(1, count)).toList()
    } else {
        val rounds = ceil(durationMs.toDouble() / (count * FULL_ART_MAX_HOLD_MS)).toInt().coerceAtLeast(1)
        List(rounds * count) { it % count }
    }
    return FullArtSchedule(pages, (durationMs / pages.size).coerceAtLeast(1L))
}

/** Slow at both ends. */
internal fun easeInOutSine(p: Float): Float = (0.5 - 0.5 * cos(PI * p.coerceIn(0f, 1f))).toFloat()

/** A page of [pageW] x [pageH] filling a [boxW] x [boxH] box, centred and cropped. */
internal fun coverCrop(pageW: Float, pageH: Float, boxW: Float, boxH: Float): Rect {
    val scale = max(boxW / pageW, boxH / pageH)
    val w = pageW * scale
    val h = pageH * scale
    val left = (boxW - w) / 2f
    val top = (boxH - h) / 2f
    return Rect(left, top, left + w, top + h)
}

/**
 * The art's box in full art on a [viewW] x [viewH] screen at [zoom] (a square cover that many
 * screen widths across): as tall as that square, up to [FULL_ART_MAX_HEIGHT] of the screen. The
 * second value is the zoom left over past that height, which scales the page up inside the box.
 */
internal fun fullArtBox(viewW: Float, viewH: Float, zoom: Float): Pair<Rect, Float> {
    val height = min(zoom * viewW, FULL_ART_MAX_HEIGHT * viewH).coerceAtLeast(1f)
    return Rect(0f, 0f, viewW, height) to max(1f, zoom * viewW / height)
}

/**
 * A page of [pageW] x [pageH] filling a [boxW] x [boxH] box, scaled up by [zoom] past filling it
 * and centred, then panned across what the crop hides, [elapsedS] seconds into its time on
 * screen: along the side it overflows most, from one edge toward the other ([direction] 1 left to
 * right, -1 back), at [speedPxPerS] eased in and out, then resting. A crop too wide to cross
 * within [holdS] at that speed is crossed through its middle, so the pan never hurries.
 */
internal fun panCrop(
    pageW: Float,
    pageH: Float,
    boxW: Float,
    boxH: Float,
    elapsedS: Float,
    direction: Int,
    holdS: Float,
    speedPxPerS: Float,
    zoom: Float = 1f,
): Rect {
    val filled = coverCrop(pageW, pageH, boxW, boxH)
    val w = filled.width * zoom
    val h = filled.height * zoom
    val crop = Rect((boxW - w) / 2f, (boxH - h) / 2f, (boxW + w) / 2f, (boxH + h) / 2f)
    val horizontal = crop.width - boxW >= crop.height - boxH
    val overflow = if (horizontal) crop.width - boxW else crop.height - boxH
    if (overflow <= 0.5f || speedPxPerS <= 0f) return crop
    val travel = min(overflow, speedPxPerS * holdS)
    val e = easeInOutSine(elapsedS / max(0.001f, travel / speedPxPerS))
    val along = (overflow - travel) / 2f + travel * (if (direction > 0) e else 1f - e)
    return if (horizontal) {
        Rect(-along, crop.top, -along + crop.width, crop.bottom)
    } else {
        Rect(crop.left, -along, crop.right, -along + crop.height)
    }
}

/** [a] to [b] by [t]. */
internal fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
    a.left + (b.left - a.left) * t,
    a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t,
    a.bottom + (b.bottom - a.bottom) * t,
)
