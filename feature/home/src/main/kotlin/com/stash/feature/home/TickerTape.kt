package com.stash.feature.home

/**
 * Content model for the Home supporter ticker: supporter shout-outs woven
 * with station announcements (mission line, dev/contributor calls). Pure
 * and separately testable; the composable only styles what this builds.
 */
internal sealed interface TickerSegment {
    data class Shoutout(val name: String, val amount: String, val message: String) : TickerSegment
    data class Announcement(val text: String) : TickerSegment
}

/**
 * The station voice. Order matters — it is the rotation order on the tape.
 * Edit freely; [buildTickerSegments] guarantees each appears at least once
 * per cycle regardless of supporter count.
 */
internal val TICKER_ANNOUNCEMENTS = listOf(
    "Stash is a community powered open-source project dedicated to the " +
        "love and growth of music. Thank you to our supporters.",
    "Devs wanted — join the Discord to help build the future of music.",
    "Open source, open to PRs — contributors welcome on GitHub.",
)

/** A shout-out cadence of N supporters between announcements. */
internal const val ANNOUNCEMENT_EVERY = 3

/**
 * Weaves [announcements] between [shoutouts]: one announcement after every
 * [every] supporters (cycling through the list), and any announcements not
 * yet used are appended at the end so every cycle of the tape carries the
 * complete station voice even with only a handful of supporters.
 */
internal fun buildTickerSegments(
    shoutouts: List<TickerSegment.Shoutout>,
    announcements: List<String> = TICKER_ANNOUNCEMENTS,
    every: Int = ANNOUNCEMENT_EVERY,
): List<TickerSegment> {
    val out = mutableListOf<TickerSegment>()
    var next = 0
    shoutouts.forEachIndexed { index, shoutout ->
        out += shoutout
        if ((index + 1) % every == 0 && announcements.isNotEmpty()) {
            out += TickerSegment.Announcement(announcements[next % announcements.size])
            next++
        }
    }
    while (next < announcements.size) {
        out += TickerSegment.Announcement(announcements[next])
        next++
    }
    return out
}

/**
 * Marquee position as a pure function of wall-clock time. Because the
 * offset depends only on elapsed-since-epoch, the tape RESUMES exactly
 * where it would have been after the ticker leaves and re-enters
 * composition — no restart-from-zero on every return to the top of Home.
 * Double math so hours of uptime don't accumulate float drift.
 */
internal fun marqueeOffsetPx(elapsedMs: Long, velocityPxPerSec: Float, tapeWidthPx: Int): Float =
    if (tapeWidthPx <= 0) 0f
    else (((elapsedMs / 1000.0) * velocityPxPerSec) % tapeWidthPx).toFloat()
