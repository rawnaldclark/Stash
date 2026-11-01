package com.stash.feature.record.card

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How Home's Your record card fits its words (owner 2026-11-01: "Most played: John Fruscia…" at a large font). Pure, so the
 * rules are tested without a screen: [YourRecordSection] lays the card out by them.
 */
internal object RecordCardLayout {
    /**
     * The least room the words get beside the artwork before it goes above them. Measured on a Pixel 6 (411 dp wide): the
     * words had 179 dp, enough for the new layout (the name on its own line, up to two) at a large font; much less and the
     * hours' number itself has no room at 32 sp.
     */
    val MinWordsWidth: Dp = 170.dp

    /** Between the artwork and the words, beside them or above. */
    val ArtGap: Dp = 14.dp

    /**
     * Whether the card puts its artwork above its words: when beside it the words would get less than [MinWordsWidth] (a
     * narrow phone, or Stash's Display size). [inner] is the card's width inside its padding, from the layout's own
     * constraints, never from the device model.
     */
    fun artOnTop(inner: Dp): Boolean = inner - RecordArtWidth - ArtGap < MinWordsWidth

    /** The plain space and the no-break space that replaces it. */
    private const val SPACE = ' '
    private const val NO_BREAK = ' '

    /** The separator between "since 2016" and "330,827 plays" in every language's `record_since`. */
    private const val DOT = " · "

    /**
     * [line] ("since 2016 · 330,827 plays") set so it can wrap only after its dot: every other space (inside each half, and
     * before the dot) made a no-break space, so a narrow card shows "since 2016 ·" over "330,827 plays", never "330,827" over
     * "plays". A line without the dot (just the plays, or a language that words it otherwise) comes back as it is.
     */
    fun wrapOnlyAtDot(line: String): String {
        val parts = line.split(DOT)
        if (parts.size < 2) return line
        return parts.joinToString("$NO_BREAK· ") { it.replace(SPACE, NO_BREAK) }
    }
}
