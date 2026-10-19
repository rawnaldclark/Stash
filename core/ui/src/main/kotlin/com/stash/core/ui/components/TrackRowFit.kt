package com.stash.core.ui.components

/**
 * How the lossless pill is drawn on a track row.
 *
 * - [FULL]    — the normal pill, e.g. `FLAC 24/44`.
 * - [COMPACT] — the format name only, e.g. `FLAC` (drops the Hi-Res qualifier).
 * - [HIDDEN]  — no pill. Last resort, only when even a compact pill would
 *               squeeze the title below its minimum share.
 */
internal enum class BadgeFit { FULL, COMPACT, HIDDEN }

/**
 * What a track row shows in its optional slots once the space has been shared out.
 *
 * @property showSourceLabels true = "● Spotify ● YouTube"; false = the coloured dots only.
 * @property badge how the lossless pill is drawn.
 */
internal data class TrackRowFit(
    val showSourceLabels: Boolean,
    val badge: BadgeFit,
)

/**
 * Everything is shown as designed: the coloured dots and the full pill. Source names are never
 * shown in a row (owner 2026-10-19), only the dots, so this is the layout every real row uses.
 */
internal val TrackRowFitDefault = TrackRowFit(showSourceLabels = false, badge = BadgeFit.FULL)

/**
 * Decides how much of a track row's optional decoration fits while still
 * leaving the title a fair share of the width.
 *
 * Why this exists: in a `Row`, unweighted children (the lossless pill, the
 * duration, the source labels) are measured first, and the weighted title gets
 * whatever is left. A Hi-Res pill plus two source labels ("● Spotify ● YouTube")
 * could leave the title zero width, so "Nude" by Radiohead rendered as "…".
 *
 * The row gives things up in a fixed order, least informative first, and stops
 * as soon as the title has room:
 *  1. Everything as designed (labels shown only when [sourceLabelsExtra] > 0).
 *  2. Source labels collapse to their coloured dots. Not reached in production:
 *     track rows never show source names, so they pass `sourceLabelsExtra = 0`
 *     and this step is skipped. Kept for callers that do pass label widths.
 *  3. The pill drops its qualifier: `FLAC 24/44` → `FLAC`.
 *  4. The pill is hidden. The title always wins over decoration.
 *
 * "Room" means [titleNaturalWidth] or [titleMinWidth], whichever is smaller:
 * a short title that already fits never costs the row its labels.
 *
 * Pure function of pixel widths so it can be unit-tested without Compose.
 * All widths are in the same unit (px in production). Complexity O(1).
 *
 * @param titleColumnWidth   Width the title column gets when every optional
 *                           element is at its widest (labels shown, full pill).
 *                           With `sourceLabelsExtra = 0` this is the dots-only width.
 * @param sourceLabelsExtra  Extra width the source labels take over dots alone
 *                           (0 when there are no labels to collapse; always 0 in rows).
 * @param badgeFullWidth     Width of the full pill, 0 when the track has none.
 * @param badgeCompactWidth  Width of the compact pill, 0 when the track has none.
 * @param badgeGap           Gap between the title and the pill.
 * @param titleNaturalWidth  Width of the title on one line with no ellipsis.
 * @param titleMinWidth      The fair minimum share the title is guaranteed.
 * @return the first [TrackRowFit] in the give-up order that leaves the title
 *         enough room, or the most compact one if none does.
 */
internal fun decideTrackRowFit(
    titleColumnWidth: Int,
    sourceLabelsExtra: Int,
    badgeFullWidth: Int,
    badgeCompactWidth: Int,
    badgeGap: Int,
    titleNaturalWidth: Int,
    titleMinWidth: Int,
): TrackRowFit {
    val needed = minOf(titleNaturalWidth, titleMinWidth).coerceAtLeast(0)
    val hasBadge = badgeFullWidth > 0
    val hasLabels = sourceLabelsExtra > 0

    // Candidates in give-up order. Skip steps that change nothing for this row.
    val candidates = buildList {
        add(TrackRowFit(showSourceLabels = hasLabels, badge = BadgeFit.FULL))
        if (hasLabels) add(TrackRowFit(showSourceLabels = false, badge = BadgeFit.FULL))
        if (hasBadge) {
            add(TrackRowFit(showSourceLabels = false, badge = BadgeFit.COMPACT))
            add(TrackRowFit(showSourceLabels = false, badge = BadgeFit.HIDDEN))
        }
    }

    for (fit in candidates) {
        val column = titleColumnWidth + if (fit.showSourceLabels) 0 else sourceLabelsExtra
        val badge = when (fit.badge) {
            BadgeFit.FULL -> badgeFullWidth
            BadgeFit.COMPACT -> badgeCompactWidth
            BadgeFit.HIDDEN -> 0
        }
        val gap = if (badge > 0) badgeGap else 0
        if (column - badge - gap >= needed) return fit
    }
    return candidates.last()
}
