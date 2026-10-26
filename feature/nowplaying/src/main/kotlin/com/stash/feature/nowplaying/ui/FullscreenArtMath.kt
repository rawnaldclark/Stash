package com.stash.feature.nowplaying.ui

import androidx.compose.ui.geometry.Rect
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** The shortest and longest a page is held in fullscreen art while a song plays. */
internal const val FULLSCREEN_MIN_HOLD_MS = 10_000L
internal const val FULLSCREEN_MAX_HOLD_MS = 40_000L

/** The hold when the song's length isn't known (a live stream): the pages go round at this pace. */
internal const val FULLSCREEN_UNKNOWN_HOLD_MS = 20_000L

/** A page this much wider than tall is a spread (a gatefold's inside): panned, not pushed into. */
private const val SPREAD_ASPECT = 1.4f

/** A spread's height on the screen, of the screen's height. */
private const val SPREAD_HEIGHT = 0.6f

/** How far the camera moves into a page, at most: this many times its whole-page size. */
private const val MAX_PUSH = 2.4f

/** Where a page's middle sits while it's smaller than the screen, of the screen's height. */
private const val PAGE_CENTER_Y = 0.46f

/**
 * How the song paces the pages in fullscreen art: which page each stretch of the song shows
 * ([pages], indexes into the album's pages, in order) and how long each stretch is ([holdMs]).
 * The stretches share the song evenly, so the last page ends with the song. [endless] when the
 * song's length isn't known: the pages then go round for as long as it plays.
 */
internal data class FullscreenSchedule(val pages: List<Int>, val holdMs: Long, val endless: Boolean = false) {
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
 * between [FULLSCREEN_MIN_HOLD_MS] and [FULLSCREEN_MAX_HOLD_MS]. A long song goes round the pages
 * as many times as that takes; a short one shows the first pages it has time for (the front and
 * the back first). An unknown length goes round at [FULLSCREEN_UNKNOWN_HOLD_MS].
 */
internal fun fullscreenSchedule(durationMs: Long, pageCount: Int): FullscreenSchedule {
    val count = pageCount.coerceAtLeast(1)
    if (durationMs <= 0L) return FullscreenSchedule((0 until count).toList(), FULLSCREEN_UNKNOWN_HOLD_MS, endless = true)
    val pages = if (durationMs / count < FULLSCREEN_MIN_HOLD_MS) {
        (0 until (durationMs / FULLSCREEN_MIN_HOLD_MS).toInt().coerceIn(1, count)).toList()
    } else {
        val rounds = ceil(durationMs.toDouble() / (count * FULLSCREEN_MAX_HOLD_MS)).toInt().coerceAtLeast(1)
        List(rounds * count) { it % count }
    }
    return FullscreenSchedule(pages, (durationMs / pages.size).coerceAtLeast(1L))
}

/** Slow at both ends: the camera eases out of rest and into it. */
internal fun easeInOutSine(p: Float): Float = (0.5 - 0.5 * cos(PI * p.coerceIn(0f, 1f))).toFloat()

/**
 * Where a page of [pageW] x [pageH] is drawn on a [viewW] x [viewH] screen at [progress] (0 to 1)
 * through its hold: the slow camera of fullscreen art.
 *
 * A page starts whole, centred, and moves slowly in until it fills the screen's height (at most
 * [MAX_PUSH] times its whole size), drifting toward one side ([direction] 1 the left, -1 the
 * right), so the page is seen whole and then up close. It never uncovers the screen's sides once
 * it is wider than the screen. A spread (wider than [SPREAD_ASPECT]) is drawn [SPREAD_HEIGHT] of
 * the screen tall and panned across its whole width instead, left to right for [direction] 1.
 */
internal fun fullscreenCamera(pageW: Float, pageH: Float, viewW: Float, viewH: Float, progress: Float, direction: Int): Rect {
    val e = easeInOutSine(progress)
    if (pageW / pageH > SPREAD_ASPECT) {
        val h = max(viewH * SPREAD_HEIGHT, viewW * pageH / pageW) * (1f + 0.05f * e)
        val w = h * pageW / pageH
        val from = if (direction > 0) 0f else viewW - w
        val to = if (direction > 0) viewW - w else 0f
        val left = from + (to - from) * e
        val top = viewH * PAGE_CENTER_Y - h / 2f
        return Rect(left, top, left + w, top + h)
    }
    val fit = min(viewW / pageW, viewH / pageH)
    val fill = max(viewW / pageW, viewH / pageH)
    val scale = fit + (min(fill, fit * MAX_PUSH) - fit) * e
    val w = pageW * scale
    val h = pageH * scale
    val focusX = 0.5f + ((if (direction > 0) 0.22f else 0.78f) - 0.5f) * e
    val left = if (w >= viewW) (viewW / 2f - focusX * w).coerceIn(viewW - w, 0f) else (viewW - w) / 2f
    val centerY = viewH * (PAGE_CENTER_Y + (0.5f - PAGE_CENTER_Y) * e)
    val top = if (h >= viewH) (centerY - h / 2f).coerceIn(viewH - h, 0f) else centerY - h / 2f
    return Rect(left, top, left + w, top + h)
}

/** A page of [pageW] x [pageH] filling a [boxW] x [boxH] box, centred and cropped: the standard art. */
internal fun coverCrop(pageW: Float, pageH: Float, boxW: Float, boxH: Float): Rect {
    val scale = max(boxW / pageW, boxH / pageH)
    val w = pageW * scale
    val h = pageH * scale
    val left = (boxW - w) / 2f
    val top = (boxH - h) / 2f
    return Rect(left, top, left + w, top + h)
}

/** [a] to [b] by [t]. */
internal fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
    a.left + (b.left - a.left) * t,
    a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t,
    a.bottom + (b.bottom - a.bottom) * t,
)
