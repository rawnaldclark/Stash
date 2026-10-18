package com.stash.core.ui.components.motion

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The liquid pill's geometry and its leading/trailing spring choice (stash-tab-bar-v2.html `movePill`). */
class LiquidPillTest {

    private val width = 400f
    private val inset = 4f

    @Test
    fun `tab centres split the bar inside its padding into equal tabs`() {
        // (400 - 8) / 5 = 78.4 per tab.
        assertThat(LiquidPill.tabCenter(0, 5, width, inset)).isWithin(1e-4f).of(4f + 39.2f)
        assertThat(LiquidPill.tabCenter(2, 5, width, inset)).isWithin(1e-4f).of(200f)
        assertThat(LiquidPill.tabCenter(4, 5, width, inset)).isWithin(1e-4f).of(400f - 4f - 39.2f)
    }

    @Test
    fun `right to left mirrors the centres`() {
        assertThat(LiquidPill.tabCenter(0, 5, width, inset, rtl = true))
            .isWithin(1e-4f).of(LiquidPill.tabCenter(4, 5, width, inset))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an index outside the tabs is refused`() {
        LiquidPill.tabCenter(5, 5, width, inset)
    }

    @Test
    fun `the edges sit half the pill either side of the centre`() {
        assertThat(LiquidPill.edges(200f, 60f)).isEqualTo(PillEdges(170f, 230f))
    }

    @Test
    fun `moving right the right edge leads and the left trails`() {
        val springs = LiquidPill.springsFor(targetRight = 300f, currentRight = 100f)
        assertThat(springs.right).isEqualTo(LiquidPill.Leading)
        assertThat(springs.left).isEqualTo(LiquidPill.Trailing)
    }

    @Test
    fun `moving left the left edge leads and the right trails`() {
        val springs = LiquidPill.springsFor(targetRight = 100f, currentRight = 300f)
        assertThat(springs.left).isEqualTo(LiquidPill.Leading)
        assertThat(springs.right).isEqualTo(LiquidPill.Trailing)
    }

    @Test
    fun `the springs are the approved ones`() {
        assertThat(LiquidPill.Leading).isEqualTo(SpringParams(stiffness = 620f, dampingRatio = 0.72f))
        assertThat(LiquidPill.Trailing).isEqualTo(SpringParams(stiffness = 210f, dampingRatio = 0.82f))
    }

    @Test
    fun `the drawn width never drops below the minimum, even when the edges cross`() {
        assertThat(LiquidPill.drawnWidth(100f, 160f, 24f)).isEqualTo(60f)
        assertThat(LiquidPill.drawnWidth(100f, 110f, 24f)).isEqualTo(24f)
        assertThat(LiquidPill.drawnWidth(120f, 100f, 24f)).isEqualTo(24f)
    }
}
