package com.stash.core.media.service

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeFadeTest {
    @Test fun `equal power endpoints`() {
        val (out0, in0) = equalPowerVolumes(0f)
        assertEquals(1f, out0, 0.001f); assertEquals(0f, in0, 0.001f)
        val (out1, in1) = equalPowerVolumes(1f)
        assertEquals(0f, out1, 0.001f); assertEquals(1f, in1, 0.001f)
    }

    @Test fun `equal power is constant power`() {
        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val (o, i) = equalPowerVolumes(t)
            assertEquals("power at t=$t", 1f, o * o + i * i, 0.001f)
        }
    }

    @Test fun `AutoMix beat curve hands over decisively`() {
        val (out0, in0) = autoMixBlendVolumes(0f)
        assertEquals(1f, out0, 0.001f); assertEquals(0f, in0, 0.001f)
        val (outLate, inLate) = autoMixBlendVolumes(0.75f)
        assertTrue("outgoing should be mostly gone by 75%, was $outLate", outLate < 0.20f)
        assertTrue("incoming should own the mix by 75%, was $inLate", inLate > 0.95f)
        val (out1, in1) = autoMixBlendVolumes(1f)
        assertEquals(0f, out1, 0.001f); assertEquals(1f, in1, 0.001f)
    }

    @Test fun `AutoMix phrase cut does not leave two busy songs layered`() {
        val (out, incoming) = autoMixPhraseCutVolumes(0.75f)
        assertEquals(0f, out, 0.001f)
        assertEquals(1f, incoming, 0.001f)
    }

    @Test fun `bass swap keeps only one low end before and after the swap`() {
        val (outStart, inStart) = autoMixBassSwapShelves(0f)
        assertEquals(0f, outStart, 0.001f); assertEquals(-20f, inStart, 0.001f)
        val (outMid, inMid) = autoMixBassSwapShelves(0.515f)
        assertEquals(-10f, outMid, 0.75f); assertEquals(-10f, inMid, 0.75f)
        val (outEnd, inEnd) = autoMixBassSwapShelves(1f)
        assertEquals(-20f, outEnd, 0.001f); assertEquals(0f, inEnd, 0.001f)
    }

    @Test fun `bridge tempo morph is log linear in both directions`() {
        assertEquals(120f, bridgeBpmAtProgress(120f, 144f, 0f), 0.001f)
        assertEquals(sqrt(120f * 144f), bridgeBpmAtProgress(120f, 144f, 0.5f), 0.01f)
        assertEquals(144f, bridgeBpmAtProgress(120f, 144f, 1f), 0.001f)
        assertEquals(sqrt(144f * 120f), bridgeBpmAtProgress(144f, 120f, 0.5f), 0.01f)
        assertEquals(120f, bridgeBpmAtProgress(144f, 120f, 1f), 0.001f)
    }

    @Test fun `beat phase error wraps and bend stays bounded`() {
        assertEquals(0.05f, circularBeatPhaseError(0.02f, 0.97f), 0.001f)
        assertEquals(-0.05f, circularBeatPhaseError(0.97f, 0.02f), 0.001f)
        assertEquals(0.20f, circularBeatPhaseError(3.2f, 9.0f), 0.001f)
        assertTrue(beatPhaseBendFactor(0.25f) > 1f)
        assertTrue(beatPhaseBendFactor(-0.25f) < 1f)
        assertEquals(1.06f, beatPhaseBendFactor(10f), 0.001f)
        assertEquals(0.94f, beatPhaseBendFactor(-10f), 0.001f)
    }

    @Test fun `arm only when all conditions hold`() {
        val base = ArmInputs(
            enabled = true, repeatOne = false, hasResolvedNext = true,
            remainingMs = 5000, trackDurationMs = 200_000, crossfadeMs = 6000,
        )
        assertTrue(shouldArm(base))
        assertFalse(shouldArm(base.copy(enabled = false)))
        assertFalse(shouldArm(base.copy(repeatOne = true)))
        assertFalse(shouldArm(base.copy(hasResolvedNext = false)))
        assertFalse("remaining > duration", shouldArm(base.copy(remainingMs = 7000)))
        assertFalse("track too short", shouldArm(base.copy(trackDurationMs = 10_000)))
    }
}
