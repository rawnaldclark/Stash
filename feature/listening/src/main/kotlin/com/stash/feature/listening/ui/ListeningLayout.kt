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
