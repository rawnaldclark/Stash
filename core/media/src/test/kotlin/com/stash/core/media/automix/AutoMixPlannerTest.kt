package com.stash.core.media.automix

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.AutoMixMode
import kotlin.math.roundToLong
import org.junit.Test

class AutoMixPlannerTest {
    private fun grid(
        bpm: Float,
        confidence: Float = 0.82f,
        stability: Float = 0.95f,
        beatStrength: Float = 0.55f,
    ) = BeatGrid(
        bpm = bpm,
        confidence = confidence,
        firstBeatMs = 0L,
        downbeatOffsetBeats = 0,
        stability = stability,
        beatStrength = beatStrength,
    )

    private fun sections(
        fromMs: Long,
        toMs: Long,
        barMs: Long,
        rms: Float,
        onset: Float = 0.12f,
        brightness: Float = 0.12f,
        melodic: Float = 0f,
        sustained: Float = 0f,
    ): List<AutoMixSection> {
        val result = ArrayList<AutoMixSection>()
        var start = fromMs
        while (start + barMs <= toMs) {
            result += AutoMixSection(
                startMs = start,
                endMs = start + barMs,
                rms = rms,
                onset = onset,
                brightness = brightness,
                melodic = melodic,
                sustained = sustained,
            )
            start += barMs
        }
        return result
    }

    private fun outgoing(
        bpm: Float? = 120f,
        stability: Float = 0.95f,
        rms: Float = 0.28f,
        onset: Float? = null,
        brightness: Float? = null,
        melodic: Float = 0f,
        sustained: Float = 0f,
        key: MusicalKey? = null,
    ): AutoMixTrackAnalysis {
        val barMs = bpm?.let { (240_000.0 / it).roundToLong() } ?: 2_000L
        return AutoMixTrackAnalysis(
            mediaId = "out",
            durationMs = 240_000L,
            beatGrid = bpm?.let { grid(it, stability = stability) },
            effectiveStartMs = 175_000L,
            effectiveEndMs = 239_800L,
            firstStrongDownbeatMs = null,
            sections = sections(
                175_000L,
                240_000L,
                barMs,
                rms,
                onset = onset ?: if (rms > 0.8f) 0.95f else 0.12f,
                brightness = brightness ?: if (rms > 0.8f) 0.95f else 0.12f,
                melodic = melodic,
                sustained = sustained,
            ),
            preferredEntryDownbeatMs = null,
            preferredExitDownbeatMs = 238_000L,
            musicalKey = key,
        )
    }

    private fun incoming(
        bpm: Float? = 122f,
        stability: Float = 0.95f,
        rms: Float = 0.30f,
        onset: Float? = null,
        brightness: Float? = null,
        melodic: Float = 0f,
        sustained: Float = 0f,
        key: MusicalKey? = null,
    ): AutoMixTrackAnalysis {
        val barMs = bpm?.let { (240_000.0 / it).roundToLong() } ?: 2_000L
        return AutoMixTrackAnalysis(
            mediaId = "in",
            durationMs = 240_000L,
            beatGrid = bpm?.let { grid(it, stability = stability) },
            effectiveStartMs = 0L,
            effectiveEndMs = 65_000L,
            firstStrongDownbeatMs = 0L,
            sections = sections(
                0L,
                65_000L,
                barMs,
                rms,
                onset = onset ?: if (rms > 0.8f) 0.95f else 0.12f,
                brightness = brightness ?: if (rms > 0.8f) 0.95f else 0.12f,
                melodic = melodic,
                sustained = sustained,
            ),
            preferredEntryDownbeatMs = 0L,
            preferredExitDownbeatMs = null,
            musicalKey = key,
        )
    }

    @Test fun `stable close tempos with calm sections beat lock`() {
        val plan = AutoMixPlanner.plan(outgoing(), incoming(), AutoMixMode.BEAT_SYNC, 1f)
        assertThat(plan).isNotNull()
        assertThat(plan!!.transitionKind).isEqualTo(AutoMixTransitionKind.BEAT_LOCK)
        assertThat(plan.beatMatched).isTrue()
        assertThat(plan.bars).isAnyOf(4, 8)
        assertThat(plan.incomingMultiplier).isWithin(0.01f).of(120f / 122f)
        assertThat(plan.audibleFadeWallMs).isAtMost(24_000L)
    }

    @Test fun `strong steady outro beat becomes a song-derived loop bridge`() {
        val plan = AutoMixPlanner.plan(
            outgoing(rms = 0.42f, onset = 0.34f, brightness = 0.18f),
            incoming(rms = 0.34f, onset = 0.25f, brightness = 0.20f),
            AutoMixMode.BEAT_SYNC,
            1f,
        )
        assertThat(plan).isNotNull()
        assertThat(plan!!.transitionKind).isEqualTo(AutoMixTransitionKind.BEAT_LOOP_BRIDGE)
        assertThat(plan.beatMatched).isTrue()
        assertThat(plan.loopBars).isEqualTo(2)
        assertThat(plan.loopEndOutgoingMs!! - plan.loopStartOutgoingMs!!).isEqualTo(4_000L)
        assertThat(plan.bridgeStartBpm).isWithin(0.01f).of(120f)
        assertThat(plan.bridgeEndBpm).isWithin(0.01f).of(122f)
        assertThat(plan.bridgeLoopCycles).isEqualTo(2)
        assertThat(plan.incomingMultiplier).isEqualTo(1f)
        assertThat(plan.outgoingTargetMultiplier).isEqualTo(1f)
        assertThat(plan.tempoReturnMs).isEqualTo(0L)
        assertThat(plan.incomingPrimeStartMs).isAtMost(plan.incomingStartMs)
    }

    @Test fun `loop bridge can gradually cross a larger tempo gap without stretching either song`() {
        val plan = AutoMixPlanner.plan(
            outgoing(bpm = 120f, rms = 0.42f, onset = 0.34f, brightness = 0.18f),
            incoming(bpm = 144f, rms = 0.34f, onset = 0.25f, brightness = 0.20f),
            AutoMixMode.BEAT_SYNC,
            1f,
        )
        assertThat(plan).isNotNull()
        assertThat(plan!!.transitionKind).isEqualTo(AutoMixTransitionKind.BEAT_LOOP_BRIDGE)
        assertThat(plan.bars).isEqualTo(8)
        assertThat(plan.bridgeLoopCycles).isEqualTo(4)
        assertThat(plan.bridgeStartBpm).isWithin(0.01f).of(120f)
        assertThat(plan.bridgeEndBpm).isWithin(0.01f).of(144f)
        assertThat(plan.incomingMultiplier).isEqualTo(1f)
        assertThat(plan.bridgeWallMs).isAtLeast(12_000L)
    }

    @Test fun `dense bright outro is not turned into an exposed beat loop`() {
        val plan = AutoMixPlanner.plan(
            outgoing(rms = 0.88f, onset = 0.92f, brightness = 0.92f),
            incoming(rms = 0.34f, onset = 0.25f, brightness = 0.20f),
            AutoMixMode.BEAT_SYNC,
            1f,
        )
        assertThat(plan).isNotNull()
        assertThat(plan!!.transitionKind).isNotEqualTo(AutoMixTransitionKind.BEAT_LOOP_BRIDGE)
    }

    @Test fun `sustained vocal-like outro is never turned into a repeating beat loop`() {
        val plan = AutoMixPlanner.plan(
            outgoing(rms = 0.42f, onset = 0.34f, brightness = 0.18f, melodic = 0.74f, sustained = 0.92f),
            incoming(rms = 0.34f, onset = 0.25f, brightness = 0.20f),
            AutoMixMode.BEAT_SYNC,
            1f,
        )
        assertThat(plan).isNotNull()
        assertThat(plan!!.transitionKind).isNotEqualTo(AutoMixTransitionKind.BEAT_LOOP_BRIDGE)
    }

    @Test fun `slow loop bridge does not repeat the same beat for twenty seconds`() {
        val plan = AutoMixPlanner.plan(
            outgoing(bpm = 99f, rms = 0.42f, onset = 0.34f, brightness = 0.18f),
            incoming(bpm = 120f, rms = 0.34f, onset = 0.25f, brightness = 0.20f),
            AutoMixMode.BEAT_SYNC,
            1f,
        )
        assertThat(plan).isNotNull()
        assertThat(plan!!.transitionKind).isEqualTo(AutoMixTransitionKind.BEAT_LOOP_BRIDGE)
        assertThat(plan.bars).isEqualTo(4)
        assertThat(plan.bridgeWallMs).isAtMost(16_000L)
    }

    @Test fun `clean exit is moved past a later active phrase instead of cutting it off`() {
        val analysis = outgoing(bpm = null, rms = 0.28f).copy(
            sections = sections(175_000L, 220_000L, 1_000L, rms = 0.22f, onset = 0.10f) +
                sections(220_000L, 240_000L, 1_000L, rms = 0.72f, onset = 0.45f, melodic = 0.68f, sustained = 0.88f),
            preferredExitDownbeatMs = 220_000L,
        )
        val plan = AutoMixPlanner.plan(analysis, incoming(bpm = null), AutoMixMode.SMOOTH, 1f)
        assertThat(plan).isNotNull()
        assertThat(plan!!.effectiveEndOutgoingMs).isAtLeast(238_000L)
    }

    @Test fun `vocal that continues to the natural end gets only a brief tail segue`() {
        val plan = AutoMixPlanner.plan(
            outgoing(bpm = 120f, rms = 0.46f, melodic = 0.76f, sustained = 0.94f),
            incoming(bpm = 122f, rms = 0.32f),
            AutoMixMode.SMOOTH,
            1f,
        )
        assertThat(plan).isNotNull()
        assertThat(plan!!.transitionKind).isEqualTo(AutoMixTransitionKind.PHRASE_CUT)
        assertThat(plan.audibleFadeWallMs).isAtMost(2_300L)
    }

    @Test fun `large tempo gap is not forced into a stretched beat blend`() {
        val plan = AutoMixPlanner.plan(outgoing(120f), incoming(150f), AutoMixMode.BEAT_SYNC, 1f)
        assertThat(plan).isNotNull()
        assertThat(plan!!.transitionKind).isNotEqualTo(AutoMixTransitionKind.BEAT_LOCK)
        assertThat(plan.sharedBpm).isNull()
        assertThat(plan.incomingMultiplier).isEqualTo(1f)
        assertThat(plan.audibleFadeWallMs).isAtMost(9_000L)
    }

    @Test fun `unstable grid falls back instead of pretending to beat lock`() {
        val plan = AutoMixPlanner.plan(
            outgoing(stability = 0.20f),
            incoming(stability = 0.20f),
            AutoMixMode.BEAT_SYNC,
            1f,
        )
        assertThat(plan).isNotNull()
        assertThat(plan!!.transitionKind).isNotEqualTo(AutoMixTransitionKind.BEAT_LOCK)
        assertThat(plan.beatMatched).isFalse()
    }

    @Test fun `track without a reliable beat still gets a clean mixing transition`() {
        val plan = AutoMixPlanner.plan(
            outgoing(bpm = null, rms = 0.30f),
            incoming(bpm = null, rms = 0.32f),
            AutoMixMode.BEAT_SYNC,
            1f,
        )
        assertThat(plan).isNotNull()
        assertThat(plan!!.transitionKind).isEqualTo(AutoMixTransitionKind.CLEAN_BLEND)
        assertThat(plan.audibleFadeWallMs).isAtMost(9_000L)
        assertThat(plan.effectiveEndOutgoingMs).isEqualTo(238_000L)
        assertThat(plan.incomingStartMs).isEqualTo(0L)
    }

    @Test fun `two busy sections use a short phrase handoff`() {
        val plan = AutoMixPlanner.plan(
            outgoing(bpm = null, rms = 0.95f),
            incoming(bpm = null, rms = 0.95f),
            AutoMixMode.BEAT_SYNC,
            1f,
        )
        assertThat(plan).isNotNull()
        assertThat(plan!!.transitionKind).isEqualTo(AutoMixTransitionKind.PHRASE_CUT)
        assertThat(plan.audibleFadeWallMs).isAtMost(3_500L)
    }

    @Test fun `smooth mode is short and section aware rather than an 18 second layer`() {
        val plan = AutoMixPlanner.plan(outgoing(), incoming(), AutoMixMode.SMOOTH, 1f)
        assertThat(plan).isNotNull()
        assertThat(plan!!.transitionKind).isNotEqualTo(AutoMixTransitionKind.BEAT_LOCK)
        assertThat(plan.audibleFadeWallMs).isAtMost(9_000L)
    }

    @Test fun `musical key compatibility follows Camelot neighbours`() {
        val cMajor = MusicalKey(rootPitchClass = 0, minor = false, confidence = 0.8f)
        val aMinor = MusicalKey(rootPitchClass = 9, minor = true, confidence = 0.8f)
        val gMajor = MusicalKey(rootPitchClass = 7, minor = false, confidence = 0.8f)
        val fSharpMajor = MusicalKey(rootPitchClass = 6, minor = false, confidence = 0.8f)
        assertThat(cMajor.compatibleWith(aMinor)).isTrue()
        assertThat(cMajor.compatibleWith(gMajor)).isTrue()
        assertThat(cMajor.compatibleWith(fSharpMajor)).isFalse()
    }
}
