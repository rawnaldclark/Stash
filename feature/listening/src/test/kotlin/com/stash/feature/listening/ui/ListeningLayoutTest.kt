package com.stash.feature.listening.ui

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** When Home's doors stack, where the week card's bars go and how many columns the stats tiles take ([ListeningLayout]). */
class ListeningLayoutTest {
    /** The doors' row on a phone [screen] dp wide: Home's 20 dp gutter each side. */
    private fun row(screen: Float) = (screen - 2 * 20).dp

    @Test fun `a Pixel 6 keeps the doors side by side at the default font and stacks them from Large`() {
        // 411 dp: each door gets 155.5 dp inside its padding
        assertThat(ListeningLayout.doorInner(row(411f))).isEqualTo(155.5.dp)
        assertThat(ListeningLayout.doorsStacked(row(411f), fontScale = 0.85f)).isFalse()
        assertThat(ListeningLayout.doorsStacked(row(411f), fontScale = 1f)).isFalse()
        assertThat(ListeningLayout.doorsStacked(row(411f), fontScale = 1.1f)).isFalse()
        assertThat(ListeningLayout.doorsStacked(row(411f), fontScale = 1.15f)).isTrue()
        assertThat(ListeningLayout.doorsStacked(row(411f), fontScale = 1.3f)).isTrue()
        assertThat(ListeningLayout.doorsStacked(row(411f), fontScale = 2f)).isTrue()
    }

    @Test fun `a 360 dp phone stacks the doors at the default font`() {
        // 130 dp a door: "Top songs · 30 days" would wrap and the title would get under 80 dp
        assertThat(ListeningLayout.doorsStacked(row(360f), fontScale = 1f)).isTrue()
        assertThat(ListeningLayout.doorsStacked(row(360f), fontScale = 0.85f)).isFalse()
    }

    @Test fun `a large Display size stacks them on a Pixel 6 too`() {
        // Settings › Appearance at 1.25× leaves a Pixel 6 about 329 dp wide
        assertThat(ListeningLayout.doorsStacked(row(329f), fontScale = 1f)).isTrue()
    }

    @Test fun `wider phones and tablets keep them side by side`() {
        assertThat(ListeningLayout.doorsStacked(row(384f), fontScale = 1f)).isFalse()
        assertThat(ListeningLayout.doorsStacked(row(800f), fontScale = 1.3f)).isFalse()
    }

    @Test fun `the switch is at 136 dp inside a door times the font scale`() {
        // (332 - 12) / 2 - 24 = 136
        assertThat(ListeningLayout.doorsStacked(332.dp, fontScale = 1f)).isFalse()
        assertThat(ListeningLayout.doorsStacked(331.dp, fontScale = 1f)).isTrue()
        // (604 - 12) / 2 - 24 = 272 = 136 × 2
        assertThat(ListeningLayout.doorsStacked(604.dp, fontScale = 2f)).isFalse()
        assertThat(ListeningLayout.doorsStacked(602.dp, fontScale = 2f)).isTrue()
    }

    /** Inside the week card on a phone [screen] dp wide: Home's 20 dp gutter and the card's 16 dp padding each side. */
    private fun weekInner(screen: Float) = (screen - 2 * 20 - 2 * 16).dp

    @Test fun `a Pixel 5 keeps the week's bars beside the words up to Large and drops them at 1_3x`() {
        // 393 dp: 321 inside the card, 185 for the words beside 120 dp of bars and a 16 dp gap
        assertThat(weekInner(393f)).isEqualTo(321.dp)
        assertThat(ListeningLayout.weekBarsBelow(weekInner(393f), fontScale = 0.85f)).isFalse()
        assertThat(ListeningLayout.weekBarsBelow(weekInner(393f), fontScale = 1f)).isFalse()
        assertThat(ListeningLayout.weekBarsBelow(weekInner(393f), fontScale = 1.15f)).isFalse()
        assertThat(ListeningLayout.weekBarsBelow(weekInner(393f), fontScale = 1.3f)).isTrue()
        assertThat(ListeningLayout.weekBarsBelow(weekInner(393f), fontScale = 2f)).isTrue()
    }

    @Test fun `a Pixel 6 keeps the week's bars beside the words up to about 1_35x`() {
        assertThat(ListeningLayout.weekBarsBelow(weekInner(411f), fontScale = 1.3f)).isFalse()
        assertThat(ListeningLayout.weekBarsBelow(weekInner(411f), fontScale = 1.4f)).isTrue()
    }

    @Test fun `a 360 dp phone keeps them beside at the default font, and a narrow one drops them`() {
        // 288 inside: 152 dp for the words
        assertThat(ListeningLayout.weekBarsBelow(weekInner(360f), fontScale = 1f)).isFalse()
        assertThat(ListeningLayout.weekBarsBelow(weekInner(360f), fontScale = 1.15f)).isTrue()
        // A large Display size on a small phone
        assertThat(ListeningLayout.weekBarsBelow(weekInner(320f), fontScale = 1f)).isTrue()
    }

    @Test fun `the week's bars drop when the words would get under 150 dp times the font scale`() {
        // 286 - 120 - 16 = 150
        assertThat(ListeningLayout.weekBarsBelow(286.dp, fontScale = 1f)).isFalse()
        assertThat(ListeningLayout.weekBarsBelow(285.dp, fontScale = 1f)).isTrue()
        // 436 - 136 = 300: room for the words up to 2x, and at 1.4x a day's 17 dp still holds its initial
        assertThat(ListeningLayout.weekBarsBelow(436.dp, fontScale = 1.4f)).isFalse()
    }

    @Test fun `on a wide screen at a large font the bars drop rather than cut a day's initial`() {
        // 480 dp at 1.7x: the words have room (272 >= 255), but a day's 17 dp can't hold a 20 dp "W"
        assertThat(ListeningLayout.weekBarsBelow(weekInner(480f), fontScale = 1.7f)).isTrue()
        // A tablet keeps them beside up to about 1.43x, then drops them
        assertThat(ListeningLayout.weekBarsBelow(weekInner(800f), fontScale = 1.4f)).isFalse()
        assertThat(ListeningLayout.weekBarsBelow(weekInner(800f), fontScale = 1.5f)).isTrue()
        // Whenever they stay beside, each day's slot holds its initial
        listOf(360f, 393f, 411f, 480f, 600f, 800f).forEach { screen ->
            listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 1.7f, 2f).forEach { scale ->
                if (!ListeningLayout.weekBarsBelow(weekInner(screen), scale)) {
                    assertThat(ListeningLayout.WeekChartWidth / 7).isAtLeast(ListeningLayout.WeekDayLetter * scale)
                }
            }
        }
    }

    @Test fun `the week's stats share a line while they fit, and a wrap falls between two of them`() {
        // 100 + 10 + 80 + 10 + 70 = 270
        assertThat(ListeningLayout.weekStatLines(listOf(100, 80, 70), separator = 10, maxWidth = 270))
            .containsExactly(listOf(0, 1, 2))
        assertThat(ListeningLayout.weekStatLines(listOf(100, 80, 70), separator = 10, maxWidth = 269))
            .containsExactly(listOf(0, 1), listOf(2)).inOrder()
        assertThat(ListeningLayout.weekStatLines(listOf(100, 80, 70), separator = 10, maxWidth = 150))
            .containsExactly(listOf(0), listOf(1), listOf(2)).inOrder()
    }

    @Test fun `a stat wider than the line has a line to itself`() {
        assertThat(ListeningLayout.weekStatLines(listOf(400, 50, 60), separator = 10, maxWidth = 300))
            .containsExactly(listOf(0), listOf(1, 2)).inOrder()
        assertThat(ListeningLayout.weekStatLines(emptyList(), separator = 10, maxWidth = 300)).isEmpty()
    }

    @Test fun `the stats tiles are a 2x2 grid on a Pixel 6 up to about 1_7x`() {
        val card = row(411f)
        assertThat(ListeningLayout.statTileColumns(card, fontScale = 1f)).isEqualTo(2)
        assertThat(ListeningLayout.statTileColumns(card, fontScale = 1.3f)).isEqualTo(2)
        assertThat(ListeningLayout.statTileColumns(card, fontScale = 1.7f)).isEqualTo(2)
        assertThat(ListeningLayout.statTileColumns(card, fontScale = 1.8f)).isEqualTo(1)
        assertThat(ListeningLayout.statTileColumns(card, fontScale = 2f)).isEqualTo(1)
    }

    @Test fun `on a 360 dp phone the tiles go one a row past about 1_45x`() {
        // 160 dp a tile, 128 dp inside its padding
        val card = row(360f)
        assertThat(ListeningLayout.statTileColumns(card, fontScale = 1f)).isEqualTo(2)
        assertThat(ListeningLayout.statTileColumns(card, fontScale = 1.45f)).isEqualTo(2)
        assertThat(ListeningLayout.statTileColumns(card, fontScale = 1.5f)).isEqualTo(1)
    }
}
