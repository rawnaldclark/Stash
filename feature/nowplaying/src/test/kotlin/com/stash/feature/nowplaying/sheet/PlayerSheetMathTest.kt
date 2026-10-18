package com.stash.feature.nowplaying.sheet

import androidx.compose.ui.geometry.Rect
import com.stash.feature.nowplaying.sheet.PlayerSheetAnchor.FULL
import com.stash.feature.nowplaying.sheet.PlayerSheetAnchor.MINI
import com.stash.feature.nowplaying.sheet.PlayerSheetAnchor.QUEUE
import com.stash.feature.nowplaying.sheet.PlayerSheetMath.settleTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSheetMathTest {

    /** One release: a velocity (dp/s, + = up) and the distance dragged (dp, + = up). */
    private data class Release(val name: String, val velocity: Float, val distance: Float)

    private val upFlick = Release("up flick", 600f, 10f)
    private val downFlick = Release("down flick", -600f, -10f)
    private val upDistance = Release("up distance", 0f, 150f)
    private val downDistance = Release("down distance", 0f, -150f)
    private val neither = Release("neither", 100f, 50f)
    private val flickAgainstUpDrag = Release("down flick after an up drag", -600f, 300f)
    private val flickAgainstDownDrag = Release("up flick after a down drag", 600f, -300f)

    private fun check(start: PlayerSheetAnchor, queue: Boolean, expected: Map<Release, PlayerSheetAnchor>) {
        for ((r, want) in expected) {
            assertEquals(
                "$start, queue=$queue, ${r.name}",
                want,
                settleTarget(start, r.distance, r.velocity, queueEnabled = queue),
            )
        }
    }

    @Test
    fun `from MINI only an upward flick or drag opens, with or without the queue`() {
        for (queue in listOf(false, true)) {
            check(
                MINI, queue,
                mapOf(
                    upFlick to FULL, downFlick to MINI, upDistance to FULL, downDistance to MINI,
                    neither to MINI, flickAgainstUpDrag to MINI, flickAgainstDownDrag to FULL,
                ),
            )
        }
    }

    @Test
    fun `from FULL without the queue an upward release stays (the rubber band)`() {
        check(
            FULL, false,
            mapOf(
                upFlick to FULL, downFlick to MINI, upDistance to FULL, downDistance to MINI,
                neither to FULL, flickAgainstUpDrag to MINI, flickAgainstDownDrag to FULL,
            ),
        )
    }

    @Test
    fun `from FULL with the queue an upward release opens the queue`() {
        check(
            FULL, true,
            mapOf(
                upFlick to QUEUE, downFlick to MINI, upDistance to QUEUE, downDistance to MINI,
                neither to FULL, flickAgainstUpDrag to MINI, flickAgainstDownDrag to QUEUE,
            ),
        )
    }

    @Test
    fun `from QUEUE a downward release goes back to FULL, never straight to MINI`() {
        for (queue in listOf(false, true)) {
            check(
                QUEUE, queue,
                mapOf(
                    upFlick to QUEUE, downFlick to FULL, upDistance to QUEUE, downDistance to FULL,
                    neither to QUEUE, flickAgainstUpDrag to FULL, flickAgainstDownDrag to QUEUE,
                ),
            )
        }
    }

    @Test
    fun `the thresholds are strict (500 dp per s is not a flick, 100 dp is not enough)`() {
        assertEquals(MINI, settleTarget(MINI, 0f, 500f))
        assertEquals(FULL, settleTarget(MINI, 0f, 501f))
        assertEquals(MINI, settleTarget(MINI, 100f, 0f))
        assertEquals(FULL, settleTarget(MINI, 101f, 0f))
        // Without the bottom nav the actuation distance is 60 dp.
        assertEquals(FULL, settleTarget(MINI, 61f, 0f, actuationDp = PlayerSheetMath.ACTUATION_NO_NAV_DP))
    }

    @Test
    fun `the start anchor is the zone the gesture began in`() {
        assertEquals(MINI, PlayerSheetMath.startAnchorOf(-0.03f))
        assertEquals(MINI, PlayerSheetMath.startAnchorOf(0.5f))
        assertEquals(FULL, PlayerSheetMath.startAnchorOf(0.51f))
        assertEquals(FULL, PlayerSheetMath.startAnchorOf(1f))
        assertEquals(QUEUE, PlayerSheetMath.startAnchorOf(1.01f))
    }

    @Test
    fun `ramps clamp and never decrease`() {
        assertEquals(0f, PlayerSheetMath.ramp(-1f, 0.2f, 0.4f), 0f)
        assertEquals(0.5f, PlayerSheetMath.ramp(0.3f, 0.2f, 0.4f), 1e-6f)
        assertEquals(1f, PlayerSheetMath.ramp(3f, 0.2f, 0.4f), 0f)
        var last = -1f
        for (i in -10..30) {
            val v = PlayerSheetMath.ramp(i / 10f, 0.2f, 0.4f)
            assertTrue(v >= last)
            last = v
        }
    }

    @Test
    fun `lerpRect hits both ends`() {
        val a = Rect(10f, 1000f, 100f, 1090f)
        val b = Rect(0f, 0f, 1080f, 2340f)
        assertEquals(a, PlayerSheetMath.lerpRect(a, b, 0f))
        assertEquals(b, PlayerSheetMath.lerpRect(a, b, 1f))
        assertEquals(Rect(5f, 500f, 590f, 1715f), PlayerSheetMath.lerpRect(a, b, 0.5f))
    }

    @Test
    fun `the controls show only in the last quarter, rising 24 dp`() {
        for (p in listOf(-0.03f, 0f, 0.3f, 0.6f, 0.75f)) assertEquals(0f, PlayerSheetMath.controlsAlpha(p), 0f)
        assertEquals(1f, PlayerSheetMath.controlsAlpha(1f), 0f)
        assertEquals(PlayerSheetMath.CONTROLS_RISE_DP, PlayerSheetMath.controlsRiseDp(0.5f), 0f)
        assertEquals(0f, PlayerSheetMath.controlsRiseDp(1f), 0f)
    }

    @Test
    fun `the covers hand over exactly at the anchors`() {
        assertEquals(1f, PlayerSheetMath.miniArtAlpha(0f), 0f)
        assertEquals(1f, PlayerSheetMath.miniArtAlpha(-0.04f), 0f) // the landing bounce: the mini art is back
        assertEquals(0f, PlayerSheetMath.miniArtAlpha(0.0001f), 0f)
        assertEquals(0f, PlayerSheetMath.heroArtAlpha(0.9999f), 0f)
        assertEquals(1f, PlayerSheetMath.heroArtAlpha(1f), 0f)
        assertFalse(PlayerSheetMath.travellerVisible(0f))
        assertTrue(PlayerSheetMath.travellerVisible(0.5f))
        assertFalse(PlayerSheetMath.travellerVisible(1f))
        // Exactly one cover is drawn at every progress.
        for (i in -5..103) {
            val p = i / 100f
            val shown = listOf(
                PlayerSheetMath.miniArtAlpha(p) > 0f,
                PlayerSheetMath.travellerVisible(p),
                PlayerSheetMath.heroArtAlpha(p) > 0f,
            ).count { it }
            assertEquals("p=$p", 1, shown)
        }
    }

    @Test
    fun `overshoot past either end keeps every alpha in range`() {
        for (p in listOf(-0.05f, -0.03f, 1.03f, 2.03f)) {
            val alphas = listOf(
                PlayerSheetMath.cardGroundAlpha(p), PlayerSheetMath.ambientAlpha(p), PlayerSheetMath.controlsAlpha(p),
                PlayerSheetMath.miniContentAlpha(p), PlayerSheetMath.scrimAlpha(p), PlayerSheetMath.tabBarHide(p),
            )
            for (a in alphas) assertTrue("p=$p a=$a", a in 0f..1f)
            assertTrue(PlayerSheetMath.pageScale(p) in 0.95f..1f)
            assertTrue(PlayerSheetMath.cardCornerDp(p) in 0f..PlayerSheetMath.MINI_CORNER_DP)
        }
    }

    @Test
    fun `the scrim, page scale and tab bar follow the opening`() {
        assertEquals(0f, PlayerSheetMath.scrimAlpha(0f), 0f)
        assertEquals(0.5f, PlayerSheetMath.scrimAlpha(1f), 1e-6f)
        assertEquals(1f, PlayerSheetMath.pageScale(0f), 0f)
        assertEquals(0.95f, PlayerSheetMath.pageScale(1f), 1e-6f)
        assertEquals(1f, PlayerSheetMath.tabBarHide(0.6f), 0f)
        assertEquals(12f, PlayerSheetMath.cardCornerDp(0.9f), 1e-6f)
        assertEquals(0f, PlayerSheetMath.cardCornerDp(1f), 0f)
    }

    @Test
    fun `the tab bar is gone (fully faded) well before it has slid out`() {
        assertEquals(1f, PlayerSheetMath.tabBarAlpha(0f), 0f)
        assertEquals(0f, PlayerSheetMath.tabBarAlpha(0.12f), 0f)
        assertTrue(PlayerSheetMath.tabBarHide(0.12f) < 0.25f)
    }

    @Test
    fun `a release flicks only when the finger's last moments go the same way`() {
        assertEquals(800f, PlayerSheetMath.releaseVelocity(800f, 12f), 0f)
        assertEquals(-800f, PlayerSheetMath.releaseVelocity(-800f, -3f), 0f)
        // Stopped before lifting, or a tiny turn back at lift: no flick, the distance decides.
        assertEquals(0f, PlayerSheetMath.releaseVelocity(800f, 0f), 0f)
        assertEquals(0f, PlayerSheetMath.releaseVelocity(-700f, 1f), 0f)
        // So a long upward drag with a reverse twitch at lift still opens.
        val v = PlayerSheetMath.releaseVelocity(-600f, 0.5f)
        assertEquals(PlayerSheetAnchor.FULL, settleTarget(MINI, 300f, v))
    }

    @Test
    fun `the blur pauses as soon as the sheet leaves MINI`() {
        assertTrue(PlayerSheetMath.blurEnabled(0f))
        assertFalse(PlayerSheetMath.blurEnabled(0.02f))
        assertFalse(PlayerSheetMath.blurEnabled(-0.02f))
        assertFalse(PlayerSheetMath.blurEnabled(1f))
    }

    @Test
    fun `the rubber band is 1 to 1 between the anchors and never passes its limits`() {
        val upper = PlayerSheetMath.MAX_PROGRESS_PHASE_A
        for (raw in listOf(0f, 0.3f, 1f)) assertEquals(raw, PlayerSheetMath.rubberBand(raw, 1f, upper = upper), 0f)
        var last = -1f
        for (i in -300..300) {
            val raw = i / 100f
            val v = PlayerSheetMath.rubberBand(raw, 1f, upper = upper)
            assertTrue("raw=$raw", v >= PlayerSheetMath.MIN_PROGRESS && v <= upper)
            assertTrue(v >= last)
            last = v
        }
    }

    @Test
    fun `the MINI curve bounces, the FULL curve lands without overshoot`() {
        val toMini = (0..100).map { PlayerSheetMath.ToMiniEasing.transform(it / 100f) }
        assertTrue("the landing bounce", toMini.max() > 1f)
        val toFull = (0..100).map { PlayerSheetMath.ToFullEasing.transform(it / 100f) }
        assertTrue(toFull.max() <= 1f + 1e-4f)
        // A MINI snap from 1 overshoots below 0 by less than the rubber band allows.
        val lowest = toMini.minOf { 1f - it }
        assertTrue("lowest=$lowest", lowest > PlayerSheetMath.MIN_PROGRESS)
    }

    @Test
    fun `the mini player dips only below MINI, and only a little`() {
        assertEquals(0f, PlayerSheetMath.miniDipPx(0f, 2000f, 2.75f), 0f)
        assertEquals(0f, PlayerSheetMath.miniDipPx(0.5f, 2000f, 2.75f), 0f)
        val dip = PlayerSheetMath.miniDipPx(-0.05f, 2000f, 2.75f)
        assertTrue(dip > 0f && dip <= 14f * 2.75f)
    }
}
