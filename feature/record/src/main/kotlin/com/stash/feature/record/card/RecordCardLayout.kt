package com.stash.feature.record.card

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How Home's Your record card fits its words (owner 2026-11-01: "Most played: John Fruscia…" at a large font). Pure, so the
 * rules are tested without a screen: [YourRecordSection] lays the card out by them.
 */
internal object RecordCardLayout {
    /**
     * The least room the words get beside the artwork at the default font, before it goes above them; a larger font needs
     * as much more ([artOnTop]). 125 dp holds the hours' number at 32 sp (five digits and a separator) and the name in up to
     * two lines; a 360 dp phone (128 dp of words) keeps the artwork beside them at the default font.
     */
    val MinWordsWidth: Dp = 125.dp

    /** Between the artwork and the words, beside them or above. */
    val ArtGap: Dp = 14.dp

    /**
     * Whether the card puts its artwork above its words: when beside it the words would get less than [MinWordsWidth] scaled
     * by the font ([fontScale], the system's font size): a 360 dp phone stacks from the Large font on, a Pixel 6 (179 dp of
     * words) only past about 1.4×. [inner] is the card's width inside its padding, from the layout's own constraints, never
     * from the device model.
     */
    fun artOnTop(inner: Dp, fontScale: Float): Boolean = inner - RecordArtWidth - ArtGap < MinWordsWidth * fontScale

    /** The separator between "since 2016" and "330,827 plays" in every language's `record_since`. */
    private const val DOT = " · "

    /**
     * [line] ("since 2016 · 330,827 plays") as its two halves, the dot kept with the first ("since 2016 ·", "330,827 plays"):
     * the card sets them side by side when they fit and the second under the first when they don't, each wrapping as text
     * does on its own. Null second half when there's no dot (just the plays, or a language that words it otherwise).
     */
    fun splitAtDot(line: String): Pair<String, String?> {
        val at = line.indexOf(DOT)
        if (at < 0) return line to null
        return line.substring(0, at) + " ·" to line.substring(at + DOT.length)
    }
}
