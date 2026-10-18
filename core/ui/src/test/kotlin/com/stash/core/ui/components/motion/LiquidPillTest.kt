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
    fun `the drawn width never drops below the minimum, even when the edges cross`() {
        assertThat(LiquidPill.drawnWidth(100f, 160f, 24f)).isEqualTo(60f)
        assertThat(LiquidPill.drawnWidth(100f, 110f, 24f)).isEqualTo(24f)
        assertThat(LiquidPill.drawnWidth(120f, 100f, 24f)).isEqualTo(24f)
    }

    @Test
    fun `a frame step is clamped to a thirtieth of a second, then slowed by the animator scale`() {
        assertThat(LiquidPill.frameStep(11_000_000L, 1f)).isWithin(1e-6f).of(0.011f)
        // A 150 ms layout hitch advances the springs one clamped step, not 150 ms.
        assertThat(LiquidPill.frameStep(150_000_000L, 1f)).isWithin(1e-6f).of(1f / 30f)
        assertThat(LiquidPill.frameStep(11_000_000L, 4f)).isWithin(1e-6f).of(0.011f / 4f)
        assertThat(LiquidPill.frameStep(-5L, 1f)).isEqualTo(0f)
    }

    @Test
    fun `an edge spring comes to rest on its target`() {
        val edge = EdgeSpring(0f).apply { retarget(100f, LiquidPill.Leading) }
        repeat(120) { edge.advance(1f / 60f) }
        assertThat(edge.position).isWithin(0.05f).of(100f)
        assertThat(edge.isSettled(0.05f, 0.5f)).isTrue()
    }

    @Test
    fun `the leading spring overshoots a little and the trailing one barely`() {
        fun peak(params: SpringParams): Float {
            val edge = EdgeSpring(0f).apply { retarget(100f, params) }
            var max = 0f
            repeat(240) { edge.advance(1f / 120f); max = maxOf(max, edge.position) }
            return max
        }
        assertThat(peak(LiquidPill.Leading)).isGreaterThan(102f)
        assertThat(peak(LiquidPill.Trailing)).isLessThan(peak(LiquidPill.Leading))
    }

    @Test
    fun `a retarget mid-flight keeps the velocity, so the motion bends rather than restarts`() {
        val edge = EdgeSpring(0f).apply { retarget(300f, LiquidPill.Leading) }
        repeat(3) { edge.advance(1f / 60f) }
        val before = edge.velocity
        assertThat(before).isGreaterThan(0f)
        edge.retarget(150f, LiquidPill.Leading)
        assertThat(edge.velocity).isEqualTo(before)
        // It keeps heading right for a moment, then turns toward the new target and settles there.
        val position = edge.position
        edge.advance(1f / 60f)
        assertThat(edge.position).isGreaterThan(position)
        repeat(240) { edge.advance(1f / 60f) }
        assertThat(edge.position).isWithin(0.1f).of(150f)
    }

    @Test
    fun `a snap stops the edge where it lands`() {
        val edge = EdgeSpring(0f).apply { retarget(300f, LiquidPill.Leading) }
        repeat(5) { edge.advance(1f / 60f) }
        edge.snapTo(42f)
        assertThat(edge.position).isEqualTo(42f)
        assertThat(edge.target).isEqualTo(42f)
        assertThat(edge.velocity).isEqualTo(0f)
    }

    @Test
    fun `an overshoot past the capsule's wall squishes against it, the springs untouched`() {
        // Rest 300..360 in a bar whose inner wall is at 370: the right edge overshot to 385.
        val drawn = LiquidPill.drawnEdges(250f, 385f, 300f, 360f, wallLeft = 4f, wallRight = 370f, minWidth = 24f)
        assertThat(drawn).isEqualTo(PillEdges(250f, 370f))
        // Same on the left wall.
        val leftDrawn = LiquidPill.drawnEdges(-10f, 70f, 10f, 70f, wallLeft = 4f, wallRight = 370f, minWidth = 24f)
        assertThat(leftDrawn).isEqualTo(PillEdges(4f, 70f))
    }

    @Test
    fun `a rest beyond the wall is drawn where it rests, and never narrower than the minimum`() {
        assertThat(LiquidPill.drawnEdges(300f, 380f, 300f, 375f, 4f, 370f, 24f)).isEqualTo(PillEdges(300f, 375f))
        assertThat(LiquidPill.drawnEdges(360f, 390f, 300f, 360f, 4f, 370f, 24f)).isEqualTo(PillEdges(360f, 384f))
    }
}
