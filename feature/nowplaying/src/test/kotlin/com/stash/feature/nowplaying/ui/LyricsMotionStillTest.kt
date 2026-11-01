package com.stash.feature.nowplaying.ui

import com.stash.feature.nowplaying.ui.LyricsMotion.GRADIENT_START
import com.stash.feature.nowplaying.ui.LyricsMotion.PHASE_ACTIVE
import com.stash.feature.nowplaying.ui.LyricsMotion.PHASE_SUNG
import com.stash.feature.nowplaying.ui.LyricsMotion.PHASE_UPCOMING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * With motion still (Remove animations, or Battery Saver) a lyric word takes its still look once
 * per phase instead of springing every frame: dim before it is sung, lit from its start, and at
 * rest, so no frame loop is needed to finish it.
 */
class LyricsMotionStillTest {

    @Test
    fun `a still word rests dim before it is sung and lit from its start`() {
        assertEquals(0f, LyricsMotion.stillProgress(PHASE_UPCOMING))
        assertEquals(1f, LyricsMotion.stillProgress(PHASE_ACTIVE))
        assertEquals(1f, LyricsMotion.stillProgress(PHASE_SUNG))
        assertEquals(GRADIENT_START, LyricsMotion.stillGradient(PHASE_UPCOMING))
        assertEquals(1f, LyricsMotion.stillGradient(PHASE_ACTIVE))
        assertEquals(1f, LyricsMotion.stillGradient(PHASE_SUNG))
    }

    @Test
    fun `a snapped spring is at its goal and asleep`() {
        val spring = MotionSpring(0.95f, LyricsMotion.SCALE_FREQ, LyricsMotion.SCALE_DAMP)
        spring.setGoal(1.05f)
        spring.step(0.016f) // moving now
        spring.snapTo(1f)
        assertEquals(1f, spring.value)
        assertTrue(spring.canSleep())
    }

    @Test
    fun `a settled glyph shows the values at once and needs no frames`() {
        val glyph = GlyphMotion(LyricsMotion.WordScale, LyricsMotion.WordY, 0f, GRADIENT_START)
        glyph.settle(scaleGoal = 1f, yGoal = 0f, glowGoal = 0f, gradientValue = 1f)
        assertEquals(1f, glyph.scale)
        assertEquals(0f, glyph.yOffset)
        assertEquals(0f, glyph.glow)
        assertEquals(1f, glyph.gradient)
        assertTrue(glyph.asleep())
    }

    @Test
    fun `a held syllable's letters all light together when it starts, at rest`() {
        val motion = EmphasisMotion(letterCount = 4, initialProgress = 0f)
        motion.settleStill(PHASE_ACTIVE)
        motion.letters.forEach {
            assertEquals(1f, it.gradient)
            assertEquals(LyricsMotion.LetterScale.at(1f), it.scale)
            assertTrue(it.asleep())
        }
        assertEquals(LyricsMotion.WordScale.at(1f), motion.word.scale)
        assertTrue(motion.word.asleep())

        motion.settleStill(PHASE_UPCOMING)
        motion.letters.forEach { assertEquals(GRADIENT_START, it.gradient) }
    }

    @Test
    fun `a settled interlude dot is at its progress look and asleep`() {
        val dot = DotMotion(progress = 0f)
        dot.settle(1f)
        assertEquals(LyricsMotion.DotOpacity.at(1f), dot.opacity)
        assertEquals(LyricsMotion.DotScale.at(1f), dot.scale)
        assertTrue(dot.asleep())
    }
}
