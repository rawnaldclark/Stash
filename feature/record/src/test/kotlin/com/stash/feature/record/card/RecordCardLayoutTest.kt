package com.stash.feature.record.card

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Home's Your record card: when the artwork goes above the words, and where "since · plays" may wrap ([RecordCardLayout]). */
class RecordCardLayoutTest {
    /** The card's width inside its padding on a phone [screen] dp wide: Home's 20 dp each side, the card's own 14 dp. */
    private fun inner(screen: Float) = (screen - 2 * 20 - 2 * 14).dp

    @Test fun `a Pixel 6 keeps the artwork beside the words`() {
        // 411 dp: the words get 179 dp
        assertThat(RecordCardLayout.artOnTop(inner(411f))).isFalse()
    }

    @Test fun `the artwork goes above once the words would get less than 170 dp`() {
        // exactly 170 dp of words: still beside
        assertThat(RecordCardLayout.artOnTop(RecordArtWidth + RecordCardLayout.ArtGap + 170.dp)).isFalse()
        assertThat(RecordCardLayout.artOnTop(RecordArtWidth + RecordCardLayout.ArtGap + 169.5.dp)).isTrue()
        // a Pixel 6 at the largest Display size (~329 dp wide): 97 dp of words
        assertThat(RecordCardLayout.artOnTop(inner(329f))).isTrue()
        // a 360 dp phone: 128 dp of words
        assertThat(RecordCardLayout.artOnTop(inner(360f))).isTrue()
        // a tablet or a phone on its side: beside
        assertThat(RecordCardLayout.artOnTop(inner(800f))).isFalse()
    }

    @Test fun `since and plays wrap only after the dot`() {
        val nbsp = ' '
        assertThat(RecordCardLayout.wrapOnlyAtDot("since 2016 · 330,827 plays"))
            .isEqualTo("since${nbsp}2016${nbsp}· 330,827${nbsp}plays")
        // the only plain space left is the one after the dot
        assertThat(RecordCardLayout.wrapOnlyAtDot("since 2016 · 330,827 plays").count { it == ' ' }).isEqualTo(1)
        // other languages' words, same rule
        assertThat(RecordCardLayout.wrapOnlyAtDot("seit 2016 · 330.827 Wiedergaben"))
            .isEqualTo("seit${nbsp}2016${nbsp}· 330.827${nbsp}Wiedergaben")
    }

    @Test fun `a line without the dot comes back as it is`() {
        assertThat(RecordCardLayout.wrapOnlyAtDot("330,827 plays")).isEqualTo("330,827 plays")
        assertThat(RecordCardLayout.wrapOnlyAtDot("")).isEqualTo("")
    }
}
