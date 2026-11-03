package com.stash.feature.listening.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How Your listening fits its words (owner 2026-11-01: the stats "look like a settings panel", and
 * "#1 song · 30 days" was cut at every font size). Pure, so the rules are tested without a screen;
 * the composables read the width from their own constraints (never the device model) and the font
 * size from the density, the same way Home's Your record card decides where its artwork goes.
 */
internal object ListeningLayout {

    /** Between Home's two doors, side by side or one under the other. */
    val DoorGap: Dp = 12.dp

    /** Inside a door, all round. */
    val DoorPadding: Dp = 12.dp

    /**
     * The least room inside a door (its padding off) for the two doors to sit side by side at the
     * default font; a larger font needs as much more ([doorsStacked]). 136 dp holds
     * "Top songs · 30 days" on one line and about twelve characters of the song's title a line
     * beside its 40 dp cover.
     */
    val MinDoorInner: Dp = 136.dp

    /** The room inside one of two doors side by side on a row [row] wide. */
    fun doorInner(row: Dp): Dp = (row - DoorGap) / 2 - DoorPadding * 2

    /**
     * Whether Home's doors go one under the other (each the full width) instead of side by side:
     * when side by side each would get less than [MinDoorInner] scaled by the font ([fontScale],
     * the system's font size). [row] is the doors' row inside Home's gutter. A Pixel 6 (371 dp)
     * keeps them side by side at the default font and stacks them from Large; a 360 dp phone, or a
     * Pixel 6 at a larger Display size, stacks them at the default font.
     */
    fun doorsStacked(row: Dp, fontScale: Float): Boolean = doorInner(row) < MinDoorInner * fontScale

    /**
     * The room a day's initial needs under its bar at the default font: "W", the widest, is about
     * 11 dp in the 11 sp label type.
     */
    val WeekDayLetter: Dp = 12.dp

    /**
     * The week card's bars beside its words at the default font: seven days of about 13.7 dp, each
     * with room for its initial ([WeekDayLetter]). Narrow enough that "123 plays · 102 songs ·
     * 4‑day streak" fits on one line beside it on a Pixel 5.
     */
    val WeekChartWidth: Dp = 96.dp

    /** Between the week card's words and its bars. */
    val WeekChartGap: Dp = 12.dp

    /**
     * The bars' width beside the words at [fontScale]: it grows with the font, as their day initials
     * do, so an initial is never cut at a large font on a wide screen (a fixed width cut "W" from
     * about 1.7× on a 460 dp phone).
     */
    fun weekChartWidth(fontScale: Float): Dp = WeekChartWidth * fontScale

    /**
     * The least room for the week card's words beside its bars at the default font: the time at
     * 28 sp ("about 12 h 38 min") on one line. A larger font needs as much more ([weekBarsBelow]).
     */
    val MinWeekWords: Dp = 150.dp

    /**
     * Whether the week card's bars go under its words (the full width) instead of beside them (owner
     * 2026-11-02: the card had grown to 235 dp, "way too big"): when beside them the words would get
     * less than [MinWeekWords] scaled by the font ([fontScale]), so the bars drop rather than squeeze
     * the time. [inner] is the card's width inside its padding. A Pixel 5 (321 dp) keeps them beside
     * up to the 1.15× Large font and drops them at 1.3×; a Pixel 6 (339 dp) keeps them up to about 1.33×.
     */
    fun weekBarsBelow(inner: Dp, fontScale: Float): Boolean =
        inner - weekChartWidth(fontScale) - WeekChartGap < MinWeekWords * fontScale

    /**
     * The week card's plays · songs · streak as lines: which stats share each line, by index, given
     * each stat's width ([widths]), the " · " between two of them ([separator]) and the line's width
     * ([maxWidth], all in px). A stat goes on the line so far when it fits there after a separator,
     * else it starts the next line, so a wrap falls between two stats and leaves no dot behind. A
     * stat wider than a line has a line to itself.
     */
    fun weekStatLines(widths: List<Int>, separator: Int, maxWidth: Int): List<List<Int>> {
        val lines = mutableListOf<MutableList<Int>>()
        var used = 0
        widths.forEachIndexed { i, width ->
            val line = lines.lastOrNull()
            if (line != null && used + separator + width <= maxWidth) {
                line += i
                used += separator + width
            } else {
                lines += mutableListOf(i)
                used = width
            }
        }
        return lines
    }

    /** Inside a stats tile, all round. */
    val TilePadding: Dp = 16.dp

    /**
     * The least room inside a stats tile for its number at 28 sp at the default font: five
     * characters of Space Grotesk Bold ("12.3K", "9,999", "1.23M", the most [CompactCount] writes)
     * with room to spare.
     */
    val MinTileInner: Dp = 88.dp

    /**
     * How many columns the stats screen's four number tiles take on a card [card] wide: two (a 2×2
     * grid) while each tile keeps [MinTileInner] scaled by the font, else one, so a number is never
     * cut at the largest font. A Pixel 6 (371 dp) keeps two columns up to about 1.7×.
     */
    fun statTileColumns(card: Dp, fontScale: Float): Int =
        if (card / 2 - TilePadding * 2 >= MinTileInner * fontScale) 2 else 1
}
