package com.stash.core.ui.icons

import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/** The tab glyphs' path data, keyframe sampling and curves (stash-tab-bar-v2.html). */
class StashTabGlyphsTest {

    @Test
    fun `every glyph path parses into nodes that sit inside the 24 grid`() {
        TabGlyphPathData.ALL.forEach { d ->
            val nodes = PathParser().parsePathString(d).toNodes()
            assertThat(nodes).isNotEmpty()
            val b = endpointBounds(nodes)
            assertThat(b.right - b.left + (b.bottom - b.top)).isGreaterThan(0f)
            assertThat(b.left).isAtLeast(0f)
            assertThat(b.top).isAtLeast(0f)
            assertThat(b.right).isAtMost(24f)
            assertThat(b.bottom).isAtMost(24f)
        }
    }

    @Test
    fun `the house spans the grid the prototype draws it in`() {
        val b = endpointBounds(PathParser().parsePathString(TabGlyphPathData.HOUSE).toNodes())
        assertThat(b.left).isWithin(0.01f).of(4f)
        assertThat(b.right).isWithin(0.01f).of(20f)
        assertThat(b.bottom).isWithin(0.01f).of(20.8f)
    }

    @Test
    fun `keyframes hold their ends and hit every stop`() {
        assertThat(keyframeValue(0f, SquashStops, SquashY, LinearEasing)).isEqualTo(1f)
        assertThat(keyframeValue(0.25f, SquashStops, SquashY, LinearEasing)).isWithin(1e-5f).of(0.86f)
        assertThat(keyframeValue(0.55f, SquashStops, SquashY, LinearEasing)).isWithin(1e-5f).of(1.05f)
        assertThat(keyframeValue(1f, SquashStops, SquashY, LinearEasing)).isEqualTo(1f)
        assertThat(keyframeValue(-1f, TiltStops, TiltAngle, LinearEasing)).isEqualTo(0f)
        assertThat(keyframeValue(2f, TiltStops, TiltAngle, LinearEasing)).isEqualTo(0f)
    }

    @Test
    fun `keyframes interpolate within the interval`() {
        // Half-way through 0..35 %: half of the way to -14 degrees.
        assertThat(keyframeValue(0.175f, TiltStops, TiltAngle, LinearEasing)).isWithin(1e-4f).of(-7f)
    }

    @Test
    fun `the timing function is applied per interval, as CSS does`() {
        // With an easing that overshoots, the middle of an interval goes past linear.
        val eased = keyframeValue(0.125f, SquashStops, SquashX, StashTabMotion.PopEase)
        assertThat(eased).isGreaterThan(keyframeValue(0.125f, SquashStops, SquashX, LinearEasing))
    }

    @Test
    fun `the springy curves overshoot and land`() {
        listOf(StashTabMotion.PopEase, StashTabMotion.LiftEase, StashTabMotion.LabelEase).forEach { e ->
            assertThat(e.transform(0f)).isWithin(1e-4f).of(0f)
            assertThat(e.transform(1f)).isWithin(1e-4f).of(1f)
            val peak = (1..99).maxOf { e.transform(it / 100f) }
            assertThat(peak).isGreaterThan(1.02f)
        }
    }

    @Test
    fun `the clamped clock never steps further than its limit and holds within a frame`() {
        val clock = ClampedFrameClock(base = NoClock, maxStepNanos = 33_000_000L)
        assertThat(clock.advance(1_000_000_000L)).isEqualTo(1_000_000_000L)
        assertThat(clock.advance(1_011_000_000L)).isEqualTo(1_011_000_000L)
        // Every caller in the same frame sees the same time.
        assertThat(clock.advance(1_011_000_000L)).isEqualTo(1_011_000_000L)
        // A 150 ms hitch counts as 33 ms; normal frames carry on from there.
        assertThat(clock.advance(1_161_000_000L)).isEqualTo(1_044_000_000L)
        assertThat(clock.advance(1_172_000_000L)).isEqualTo(1_055_000_000L)
    }

    private object NoClock : androidx.compose.runtime.MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R = error("not used")
    }

    private data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** The bounds of a path's end points (enough to catch a typo that sends a path off the grid). */
    private fun endpointBounds(nodes: List<PathNode>): Bounds {
        var x = 0f; var y = 0f; var sx = 0f; var sy = 0f
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        fun visit() { l = min(l, x); t = min(t, y); r = max(r, x); b = max(b, y) }
        nodes.forEach { n ->
            when (n) {
                is PathNode.MoveTo -> { x = n.x; y = n.y; sx = x; sy = y }
                is PathNode.RelativeMoveTo -> { x += n.dx; y += n.dy; sx = x; sy = y }
                is PathNode.LineTo -> { x = n.x; y = n.y }
                is PathNode.RelativeLineTo -> { x += n.dx; y += n.dy }
                is PathNode.HorizontalTo -> x = n.x
                is PathNode.RelativeHorizontalTo -> x += n.dx
                is PathNode.VerticalTo -> y = n.y
                is PathNode.RelativeVerticalTo -> y += n.dy
                is PathNode.CurveTo -> { x = n.x3; y = n.y3 }
                is PathNode.RelativeCurveTo -> { x += n.dx3; y += n.dy3 }
                is PathNode.ArcTo -> { x = n.arcStartX; y = n.arcStartY }
                is PathNode.RelativeArcTo -> { x += n.arcStartDx; y += n.arcStartDy }
                is PathNode.Close -> { x = sx; y = sy }
                else -> error("unexpected node $n")
            }
            visit()
        }
        return Bounds(l, t, r, b)
    }
}
