package com.stash.feature.record.card

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Home's Your record card: when the artwork goes above the words, and how "since · plays" splits ([RecordCardLayout]). */
class RecordCardLayoutTest {
    /** The card's width inside its padding on a phone [screen] dp wide: Home's 20 dp each side, the card's own 14 dp. */
    private fun inner(screen: Float) = (screen - 2 * 20 - 2 * 14).dp

    @Test fun `a Pixel 6 keeps the artwork beside the words until the font passes about 140 percent`() {
        // 411 dp: the words get 179 dp
        assertThat(RecordCardLayout.artOnTop(inner(411f), fontScale = 1f)).isFalse()
        assertThat(RecordCardLayout.artOnTop(inner(411f), fontScale = 1.3f)).isFalse()
        assertThat(RecordCardLayout.artOnTop(inner(411f), fontScale = 1.45f)).isTrue()
        assertThat(RecordCardLayout.artOnTop(inner(411f), fontScale = 2f)).isTrue()
    }

    @Test fun `a 360 dp phone keeps it beside at the default font and stacks from Large`() {
        // 128 dp of words
        assertThat(RecordCardLayout.artOnTop(inner(360f), fontScale = 1f)).isFalse()
        assertThat(RecordCardLayout.artOnTop(inner(360f), fontScale = 0.85f)).isFalse()
        assertThat(RecordCardLayout.artOnTop(inner(360f), fontScale = 1.15f)).isTrue()
    }

    @Test fun `phones between 360 and 411 dp keep it beside at the default font`() {
        for (w in listOf(384f, 392f, 400f)) {
            assertThat(RecordCardLayout.artOnTop(inner(w), fontScale = 1f)).isFalse()
        }
    }

    @Test fun `the switch is at 125 dp of words times the font scale`() {
        val beside = RecordArtWidth + RecordCardLayout.ArtGap
        assertThat(RecordCardLayout.artOnTop(beside + 125.dp, fontScale = 1f)).isFalse()
        assertThat(RecordCardLayout.artOnTop(beside + 124.5.dp, fontScale = 1f)).isTrue()
        assertThat(RecordCardLayout.artOnTop(beside + 250.dp, fontScale = 2f)).isFalse()
        assertThat(RecordCardLayout.artOnTop(beside + 249.dp, fontScale = 2f)).isTrue()
        // a large Display size narrows the card itself: a Pixel 6 at ~329 dp has 97 dp of words
        assertThat(RecordCardLayout.artOnTop(inner(329f), fontScale = 1f)).isTrue()
        // a tablet or a phone on its side: beside
        assertThat(RecordCardLayout.artOnTop(inner(800f), fontScale = 1.3f)).isFalse()
    }

    @Test fun `since and plays split at the dot, the dot kept with the first half`() {
        assertThat(RecordCardLayout.splitAtDot("since 2016 · 330,827 plays")).isEqualTo("since 2016 ·" to "330,827 plays")
        // each half keeps its own spaces, so it can still wrap as text does (Spanish's long word at a large font)
        assertThat(RecordCardLayout.splitAtDot("desde 2016 · 330.827 reproducciones")).isEqualTo("desde 2016 ·" to "330.827 reproducciones")
        assertThat(RecordCardLayout.splitAtDot("2016년부터 · 12 plays")).isEqualTo("2016년부터 ·" to "12 plays")
    }

    @Test fun `a line without the dot is one half`() {
        assertThat(RecordCardLayout.splitAtDot("330,827 plays")).isEqualTo("330,827 plays" to null)
        assertThat(RecordCardLayout.splitAtDot("")).isEqualTo("" to null)
    }
}
