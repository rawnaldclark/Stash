package com.stash.core.media.automix

import com.stash.core.data.prefs.AutoMixMode
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

data class AutoMixTrackAnalysis(
    val mediaId: String,
    val durationMs: Long,
    val beatGrid: BeatGrid?,
    val effectiveStartMs: Long,
    val effectiveEndMs: Long,
    val firstStrongDownbeatMs: Long?,
    val sections: List<AutoMixSection> = emptyList(),
    val preferredEntryDownbeatMs: Long? = null,
    val preferredExitDownbeatMs: Long? = null,
    val musicalKey: MusicalKey? = null,
)

enum class AutoMixTransitionKind {
    /** Tempo- and downbeat-aligned blend, only for stable/confident grids and low-clash sections. */
    BEAT_LOCK,

    /**
     * A DJ-style tempo bridge: the outgoing song's last clean beat bars are
     * looped on a separate deck, that loop's tempo is gradually morphed to
     * the incoming song's BPM, and the incoming song drops in on the loop's
     * downbeat. This is the transition that should feel like one continuous
     * beat changing speed rather than two songs simply fading through each
     * other.
     */
    BEAT_LOOP_BRIDGE,

    /** Short blend into a calmer intro/outro without pretending the beats can be locked. */
    CLEAN_BLEND,

    /** Very short handoff used when both sides are busy; overlapping them would sound muddy. */
    PHRASE_CUT,
}

data class AutoMixPlan(
    val mode: AutoMixMode,
    val outgoingMediaId: String,
    val incomingMediaId: String,
    val fadeStartOutgoingMs: Long,
    val audibleStartOutgoingMs: Long,
    val effectiveEndOutgoingMs: Long,
    val incomingStartMs: Long,
    val totalFadeWallMs: Long,
    val syncLeadInMs: Long,
    val audibleFadeWallMs: Long,
    val outgoingTargetMultiplier: Float,
    val incomingMultiplier: Float,
    val tempoReturnMs: Long,
    val outgoingBpm: Float?,
    val incomingBpm: Float?,
    val sharedBpm: Float?,
    val bars: Int,
    val leadInBars: Int,
    val confidence: Float,
    val userSpeedUsed: Float,
    val transitionKind: AutoMixTransitionKind = AutoMixTransitionKind.CLEAN_BLEND,
    val keyCompatible: Boolean? = null,
    /** Source range of the outgoing bars repeated by a [AutoMixTransitionKind.BEAT_LOOP_BRIDGE]. */
    val loopStartOutgoingMs: Long? = null,
    val loopEndOutgoingMs: Long? = null,
    val loopBars: Int = 0,
    /** Tempo morph performed by the loop deck, from outgoing BPM to the incoming song's adjusted BPM. */
    val bridgeStartBpm: Float? = null,
    val bridgeEndBpm: Float? = null,
    val bridgeWallMs: Long = 0L,
    val bridgeLoopCycles: Int = 0,
    /** Incoming BPM after any half/double-time adjustment; the grid the bridge phase locks to. */
    val incomingAdjustedBpm: Float? = null,
    /** Where to prime the incoming player so it reaches [incomingStartMs] on a loop downbeat. */
    val incomingPrimeStartMs: Long? = null,
) {
    val beatMatched: Boolean
        get() = (transitionKind == AutoMixTransitionKind.BEAT_LOCK && sharedBpm != null) ||
            (transitionKind == AutoMixTransitionKind.BEAT_LOOP_BRIDGE && bridgeEndBpm != null)
}

/**
 * Plans a transition with a "do no harm" bias, but with a real DJ bridge as
 * the first choice for Beat Sync.
 *
 * Earlier versions either forced two full songs through a long overlap or,
 * after that sounded muddy on a phone, avoided many beat locks altogether.
 * The bridge here is different: a clean two-bar beat loop from the outgoing
 * song is repeated while its tempo gradually becomes the incoming song's
 * tempo, then the incoming song enters on that same downbeat. A static beat
 * lock remains for pairs with no loopable outro beat, and a clean blend /
 * phrase handoff remains for material whose beat should not be trusted.
 */
object AutoMixPlanner {
    private const val MIN_BEAT_CONFIDENCE = 0.34f
    private const val MIN_BEAT_STABILITY = 0.55f
    private const val MIN_BEAT_STRENGTH = 0.08f
    private const val MIN_LOOP_CONFIDENCE = 0.38f
    private const val MIN_LOOP_STABILITY = 0.62f
    private const val MIN_LOOP_BEAT_STRENGTH = 0.12f
    private const val DIRECT_MAX_RATIO = 1.08
    private const val SPLIT_MAX_RATIO = 1.16
    private const val LOOP_BRIDGE_MAX_RATIO = 1.30
    private const val LOOP_BARS = 2
    private const val FINAL_OVERLAP_BARS = 2
    private const val MAX_LOOP_MELODIC = 0.38f
    private const val MAX_LOOP_TAIL_MELODIC = 0.40f
    private const val VOCAL_TAIL_MELODIC = 0.42f
    private const val MIN_BEAT_TRACK_MS = 45_000L
    private const val MIN_CLEAN_OUTGOING_MS = 30_000L
    private const val MIN_CLEAN_INCOMING_MS = 25_000L
    private const val MAX_ENTRY_MS = 35_000L

    fun plan(
        outgoing: AutoMixTrackAnalysis,
        incoming: AutoMixTrackAnalysis,
        mode: AutoMixMode,
        userSpeed: Float,
    ): AutoMixPlan? {
        val speed = userSpeed.takeIf { it.isFinite() && it > 0.05f } ?: 1f
        return when (mode) {
            AutoMixMode.BEAT_SYNC -> beatLoopBridgePlan(outgoing, incoming, speed)
                ?: beatSyncPlan(outgoing, incoming, speed)
                ?: cleanPlan(outgoing, incoming, AutoMixMode.BEAT_SYNC, speed)
            AutoMixMode.SMOOTH -> cleanPlan(outgoing, incoming, AutoMixMode.SMOOTH, speed)
        }
    }

    private data class AdjustedBpm(val factor: Double, val bpm: Double)

    private fun adjustedIncomingBpm(outgoingBpm: Double, incomingGrid: BeatGrid): AdjustedBpm? =
        listOf(0.5, 1.0, 2.0)
            .map { factor -> AdjustedBpm(factor, incomingGrid.bpm.toDouble() * factor) }
            .filter { it.bpm in 35.0..220.0 }
            .filter { candidate ->
                candidate.factor == 1.0 || outgoingBpm / candidate.bpm in (1.0 / 1.09)..1.09
            }
            .minByOrNull { candidate ->
                abs(ln(outgoingBpm / candidate.bpm)) + if (candidate.factor == 1.0) 0.0 else 0.01
            }

    private data class SectionAverage(
        val rms: Float,
        val onset: Float,
        val brightness: Float,
        val melodic: Float = 0f,
        val sustained: Float = 0f,
    ) {
        val busy: Float get() = (0.55f * rms + 0.25f * onset + 0.20f * brightness).coerceIn(0f, 1f)
        val vocalRisk: Boolean
            get() = melodic >= VOCAL_TAIL_MELODIC || (sustained >= 0.78f && rms >= 0.38f && melodic >= 0.24f)
    }

    private data class BeatCandidate(
        val score: Double,
        val bars: Int,
        val effectiveEnd: Long,
        val audibleStart: Long,
        val fadeStart: Long,
        val incomingStart: Long,
        val outgoingBusy: Float,
        val incomingBusy: Float,
        val clash: Float,
    )

    private data class LoopBridgeCandidate(
        val score: Double,
        val loopStartMs: Long,
        val loopEndMs: Long,
        val incomingStartMs: Long,
        val loopMelodic: Float,
    )

    /**
     * Preferred Beat Sync transition. The outgoing song keeps playing through
     * its chosen two bars once; a separate loop deck repeats exactly those
     * bars while their playback tempo moves log-linearly to the incoming
     * BPM. The incoming track is started one beat early so its chosen
     * downbeat coincides with a loop boundary, then the final two bars are a
     * short bass-swapped blend. Returning null is important: a bad loop is
     * worse than the static beat lock or the clean fallback.
     */
    private fun beatLoopBridgePlan(
        outgoing: AutoMixTrackAnalysis,
        incoming: AutoMixTrackAnalysis,
        userSpeed: Float,
    ): AutoMixPlan? {
        if (outgoing.durationMs < MIN_BEAT_TRACK_MS || incoming.durationMs < MIN_BEAT_TRACK_MS) return null
        val outGrid = outgoing.beatGrid ?: return null
        val inGrid = incoming.beatGrid ?: return null
        if (!outGrid.isReliableForBeatLoop() || !inGrid.isReliableForBeatLoop()) return null
        if (outgoing.sections.isEmpty() || incoming.sections.isEmpty()) return null

        val outgoingBpm = outGrid.bpm.toDouble()
        val adjusted = adjustedIncomingBpm(outgoingBpm, inGrid) ?: return null
        val adjustedIncomingBpm = adjusted.bpm
        val tempoRatio = max(adjustedIncomingBpm / outgoingBpm, outgoingBpm / adjustedIncomingBpm)
        if (tempoRatio > LOOP_BRIDGE_MAX_RATIO) return null

        val keyCompatible = keyCompatibility(outgoing.musicalKey, incoming.musicalKey)
        val latestEnd = min(outgoing.effectiveEndMs, outgoing.durationMs - 250L)
        if (latestEnd < MIN_BEAT_TRACK_MS) return null
        val outgoingBarMs = 240_000.0 / outgoingBpm
        val loopSourceMs = (LOOP_BARS * outgoingBarMs).roundToLong()
        val finalIncomingSourceMs = (FINAL_OVERLAP_BARS * 240_000.0 / adjustedIncomingBpm).roundToLong()
        val entries = entryCandidates(incoming, inGrid, adjusted.factor)

        var best: LoopBridgeCandidate? = null
        for (loopEnd in exitCandidates(outgoing, outGrid, latestEnd)) {
            val loopStart = loopEnd - loopSourceMs
            if (loopStart < 8_000L || loopEnd > outgoing.durationMs - 250L) continue
            if (!sectionsCover(outgoing.sections, loopStart, loopSourceMs)) continue
            val loop = averageSections(outgoing.sections, loopStart, loopSourceMs)
            val firstBar = averageSections(
                outgoing.sections,
                loopStart,
                outgoingBarMs.roundToLong(),
            )
            val secondBar = averageSections(
                outgoing.sections,
                loopStart + outgoingBarMs.roundToLong(),
                outgoingBarMs.roundToLong(),
            )

            // A loop has to contain a beat a listener can follow after the
            // rest of the song is taken away. Near-silence, a vocal-like wall
            // of transients, or two unlike bars all expose the repeat. A
            // sung phrase is not a drum groove: repeating it for several
            // cycles sounds like the song is stuck, so melodic/sustained
            // material is a hard veto rather than just another score term.
            if (loop.rms !in 0.10f..0.68f || loop.onset < 0.16f) continue
            if (loop.busy > 0.62f || loop.brightness > 0.68f) continue
            if (loop.melodic > MAX_LOOP_MELODIC || loop.vocalRisk) continue
            if (hasActiveTailAfter(outgoing.sections, loopEnd, latestEnd)) continue
            val barDifference = abs(firstBar.rms - secondBar.rms) +
                abs(firstBar.onset - secondBar.onset) +
                abs(firstBar.brightness - secondBar.brightness) +
                abs(firstBar.melodic - secondBar.melodic)
            if (barDifference > 0.55f) continue

            val beforeLoop = averageSections(
                outgoing.sections,
                (loopStart - loopSourceMs).coerceAtLeast(0L),
                loopSourceMs,
            )
            val afterLoopDurationMs = min(loopSourceMs * 2L, (latestEnd - loopEnd).coerceAtLeast(0L))
            if (afterLoopDurationMs > 600L) {
                val afterLoop = averageSections(outgoing.sections, loopEnd, afterLoopDurationMs)
                if (afterLoop.melodic > MAX_LOOP_TAIL_MELODIC || afterLoop.vocalRisk ||
                    afterLoop.rms > loop.rms + 0.16f || afterLoop.busy > 0.56f
                ) {
                    continue
                }
            }
            val previousDifference = abs(beforeLoop.rms - loop.rms) +
                abs(beforeLoop.onset - loop.onset) +
                abs(beforeLoop.brightness - loop.brightness) +
                abs(beforeLoop.melodic - loop.melodic)
            val energyDropIntoLoop = (beforeLoop.rms - loop.rms).coerceIn(0f, 0.5f)
            val melodicDropIntoLoop = (beforeLoop.melodic - loop.melodic).coerceIn(0f, 0.7f)
            val repeatedGroove = previousDifference <= 0.42f
            val phraseHasEnded = energyDropIntoLoop >= 0.06f || melodicDropIntoLoop >= 0.12f ||
                latestEnd - loopEnd <= outgoingBarMs.roundToLong()
            if (!repeatedGroove && !phraseHasEnded) continue
            val loopScore = 2.0 +
                1.15 * loop.onset -
                0.95 * loop.busy -
                0.50 * loop.brightness -
                1.30 * loop.melodic -
                0.40 * loop.sustained -
                abs(loop.rms - 0.38f) -
                1.25 * barDifference +
                0.45 * energyDropIntoLoop +
                0.70 * melodicDropIntoLoop +
                (if (repeatedGroove) 0.25 else 0.0) -
                ((latestEnd - loopEnd).coerceAtLeast(0L) / 30_000.0) * 0.25

            for (entry in entries) {
                if (entry + finalIncomingSourceMs + 12_000L > incoming.durationMs) continue
                if (!sectionsCover(incoming.sections, entry, finalIncomingSourceMs)) continue
                val incomingHead = averageSections(
                    incoming.sections,
                    entry,
                    finalIncomingSourceMs,
                )
                if (incomingHead.rms < 0.06f || incomingHead.busy > 0.74f) continue
                if (incomingHead.melodic > 0.62f || loop.melodic * incomingHead.melodic > 0.20f) continue
                val clash = (
                    loop.busy * incomingHead.busy +
                        0.85f * loop.melodic * incomingHead.melodic +
                        1.5f * max(0f, loop.rms - 0.60f) * max(0f, incomingHead.rms - 0.60f)
                    ).coerceIn(0f, 1f)
                if (loop.busy * incomingHead.busy > 0.46f) continue
                if (keyCompatible == false && (loop.brightness > 0.35f || incomingHead.busy > 0.50f)) {
                    continue
                }
                val score = loopScore -
                    1.40 * clash -
                    0.55 * abs(incomingHead.rms - 0.42f) -
                    0.25 * incomingHead.onset -
                    0.45 * incomingHead.melodic +
                    when (keyCompatible) {
                        true -> 0.12
                        false -> -0.05
                        null -> 0.0
                    } -
                    (entry / 35_000.0) * 0.08
                val candidate = LoopBridgeCandidate(score, loopStart, loopEnd, entry, loop.melodic)
                if (best == null || candidate.score > best.score) best = candidate
            }
        }
        val chosen = best ?: return null
        if (chosen.score < 1.15) return null

        var bridgeBars = if (tempoRatio <= 1.10) 4 else 8
        var bridgeWallMs = tempoMorphWallMs(
            beats = bridgeBars * 4,
            startBpm = outgoingBpm,
            endBpm = adjustedIncomingBpm,
            speed = userSpeed,
        )
        // A 2-bar segment repeated for 20+ seconds feels stuck, however good
        // the tempo ride is. Keep the bridge musical rather than maximal:
        // an 8-bar ride is reserved for faster/cleaner grooves that can do it
        // inside about fifteen seconds.
        if (bridgeBars == 8 && (bridgeWallMs > 15_000L || chosen.loopMelodic > 0.28f)) {
            bridgeBars = 4
            bridgeWallMs = tempoMorphWallMs(
                beats = bridgeBars * 4,
                startBpm = outgoingBpm,
                endBpm = adjustedIncomingBpm,
                speed = userSpeed,
            )
        }
        if (bridgeWallMs !in 4_000L..16_000L) return null
        val bridgeCycles = bridgeBars / LOOP_BARS
        val preEntryWallMs = wallAtBeatFraction(
            totalWallMs = bridgeWallMs,
            beatFraction = (bridgeBars - FINAL_OVERLAP_BARS).toDouble() / bridgeBars,
            startBpm = outgoingBpm,
            endBpm = adjustedIncomingBpm,
        )
        val finalOverlapWallMs = bridgeWallMs - preEntryWallMs
        if (finalOverlapWallMs < 1_800L) return null
        val prerollWallMs = (loopSourceMs / userSpeed.toDouble()).roundToLong()
        val incomingBeatMs = (60_000.0 / adjustedIncomingBpm).roundToLong()
        val confidence = (min(outGrid.confidence, inGrid.confidence) *
            ((outGrid.stability + inGrid.stability) / 2f)).coerceIn(0.55f, 1f)

        return AutoMixPlan(
            mode = AutoMixMode.BEAT_SYNC,
            outgoingMediaId = outgoing.mediaId,
            incomingMediaId = incoming.mediaId,
            fadeStartOutgoingMs = chosen.loopStartMs,
            audibleStartOutgoingMs = chosen.loopEndMs,
            effectiveEndOutgoingMs = chosen.loopEndMs,
            incomingStartMs = chosen.incomingStartMs,
            totalFadeWallMs = prerollWallMs + bridgeWallMs,
            syncLeadInMs = prerollWallMs + preEntryWallMs,
            audibleFadeWallMs = finalOverlapWallMs,
            outgoingTargetMultiplier = 1f,
            incomingMultiplier = 1f,
            tempoReturnMs = 0L,
            outgoingBpm = outGrid.bpm,
            incomingBpm = inGrid.bpm,
            sharedBpm = adjustedIncomingBpm.toFloat(),
            bars = bridgeBars,
            leadInBars = LOOP_BARS,
            confidence = confidence,
            userSpeedUsed = userSpeed,
            transitionKind = AutoMixTransitionKind.BEAT_LOOP_BRIDGE,
            keyCompatible = keyCompatible,
            loopStartOutgoingMs = chosen.loopStartMs,
            loopEndOutgoingMs = chosen.loopEndMs,
            loopBars = LOOP_BARS,
            bridgeStartBpm = outgoingBpm.toFloat(),
            bridgeEndBpm = adjustedIncomingBpm.toFloat(),
            bridgeWallMs = bridgeWallMs,
            bridgeLoopCycles = bridgeCycles,
            incomingAdjustedBpm = adjustedIncomingBpm.toFloat(),
            incomingPrimeStartMs = (chosen.incomingStartMs - incomingBeatMs).coerceAtLeast(0L),
        )
    }

    private fun beatSyncPlan(
        outgoing: AutoMixTrackAnalysis,
        incoming: AutoMixTrackAnalysis,
        userSpeed: Float,
    ): AutoMixPlan? {
        if (outgoing.durationMs < MIN_BEAT_TRACK_MS || incoming.durationMs < MIN_BEAT_TRACK_MS) return null
        val outGrid = outgoing.beatGrid ?: return null
        val inGrid = incoming.beatGrid ?: return null
        if (!outGrid.isReliableForBeatLock() || !inGrid.isReliableForBeatLock()) return null

        val outgoingBpm = outGrid.bpm.toDouble()
        val adjusted = adjustedIncomingBpm(outgoingBpm, inGrid) ?: return null
        val adjustedIncomingBpm = adjusted.bpm
        val ratio = outgoingBpm / adjustedIncomingBpm
        val (outgoingMultiplier, incomingMultiplier, sharedBpm) = when {
            ratio in (1.0 / DIRECT_MAX_RATIO)..DIRECT_MAX_RATIO ->
                Triple(1.0, ratio, outgoingBpm)
            ratio in (1.0 / SPLIT_MAX_RATIO)..SPLIT_MAX_RATIO -> {
                val shared = sqrt(outgoingBpm * adjustedIncomingBpm)
                Triple(shared / outgoingBpm, shared / adjustedIncomingBpm, shared)
            }
            else -> return null
        }

        val keyCompatible = keyCompatibility(outgoing.musicalKey, incoming.musicalKey)
        val latestEnd = min(outgoing.effectiveEndMs, outgoing.durationMs - 250L)
        if (latestEnd < MIN_BEAT_TRACK_MS) return null
        val leadInBars = if (abs(outgoingMultiplier - 1.0) > 0.002) 2 else 1
        val exitCandidates = exitCandidates(outgoing, outGrid, latestEnd)
        val entryCandidates = entryCandidates(incoming, inGrid, adjusted.factor)
        if (exitCandidates.isEmpty() || entryCandidates.isEmpty()) return null

        val barOptions = buildList {
            add(4)
            // Eight bars is a long time for two songs to share the speakers.
            // Only offer it when there is no clear key clash; the section
            // score below still has to prove both sides are calm enough.
            if (keyCompatible != false) add(8)
        }

        var best: BeatCandidate? = null
        val outBarMs = outGrid.beatIntervalMs * 4.0
        val leadBarSourceMs = leadInBars * outBarMs
        for (bars in barOptions) {
            val audibleWallMs = bars * 240_000.0 / (sharedBpm * userSpeed)
            if (audibleWallMs !in 4_000.0..24_000.0) continue
            if (bars == 8 && audibleWallMs > 22_000.0) continue
            val outgoingAudibleSourceMs = bars * outBarMs
            val incomingSourceNeededMs = (bars * 240_000.0 / adjustedIncomingBpm).roundToLong()
            for (effectiveEnd in exitCandidates) {
                val audibleStart = effectiveEnd - outgoingAudibleSourceMs.roundToLong()
                val fadeStart = audibleStart - leadBarSourceMs.roundToLong()
                // Do not let an early quiet bar plus a long blend erase most
                // of the song's final minute. The new song should become
                // audible no more than ~35 s before the outgoing's real end.
                if (latestEnd - audibleStart > 35_000L) continue
                if (fadeStart < 8_000L || audibleStart <= fadeStart || effectiveEnd <= audibleStart) continue
                // Never declare this bar the end while a later active/melodic
                // phrase still remains in the song; that is how a singer gets
                // cut off mid-line at the role swap.
                if (hasActiveTailAfter(outgoing.sections, effectiveEnd, latestEnd)) continue
                val outAverage = averageSections(outgoing.sections, audibleStart, outgoingAudibleSourceMs.roundToLong())
                val outBefore = averageSections(
                    outgoing.sections,
                    audibleStart - outgoingAudibleSourceMs.roundToLong(),
                    outgoingAudibleSourceMs.roundToLong(),
                )
                for (incomingStart in entryCandidates) {
                    if (incomingStart + incomingSourceNeededMs + 12_000L > incoming.durationMs) continue
                    val inAverage = averageSections(incoming.sections, incomingStart, incomingSourceNeededMs)
                    val inAfter = averageSections(
                        incoming.sections,
                        incomingStart + incomingSourceNeededMs,
                        incomingSourceNeededMs,
                    )
                    if (outAverage.melodic >= 0.50f && inAverage.melodic >= 0.45f) continue
                    val clash = (
                        outAverage.busy * inAverage.busy +
                            0.85f * outAverage.melodic * inAverage.melodic +
                            1.5f * max(0f, outAverage.rms - 0.60f) * max(0f, inAverage.rms - 0.60f)
                        ).coerceIn(0f, 1f)
                    var score = 3.0
                    score -= 2.2 * clash
                    score -= 0.9 * abs(outAverage.rms - 0.36f)
                    score -= 0.8 * abs(inAverage.rms - 0.42f)
                    score -= 0.5 * (outAverage.onset + inAverage.onset)
                    score -= 0.65 * (outAverage.melodic + inAverage.melodic)
                    score += 0.7 * max(0f, outBefore.rms - outAverage.rms)
                    score += 0.6 * max(0f, inAfter.rms - inAverage.rms)
                    score -= ((latestEnd - effectiveEnd).coerceAtLeast(0L) / 30_000.0) * 0.30
                    score -= (incomingStart / MAX_ENTRY_MS.toDouble()) * 0.10
                    score += if (bars == 8) (0.55 - max(outAverage.busy, inAverage.busy)) * 0.9 else 0.08
                    score += when (keyCompatible) {
                        true -> 0.15
                        false -> -0.10
                        null -> 0.0
                    }
                    val candidate = BeatCandidate(
                        score = score,
                        bars = bars,
                        effectiveEnd = effectiveEnd,
                        audibleStart = audibleStart,
                        fadeStart = fadeStart,
                        incomingStart = incomingStart,
                        outgoingBusy = outAverage.busy,
                        incomingBusy = inAverage.busy,
                        clash = clash,
                    )
                    if (best == null || candidate.score > best!!.score) best = candidate
                }
            }
        }

        val chosen = best ?: return null
        // If both chosen sections are dense, the clean thing to do is not to
        // layer them for four/eight bars. Fall back to a short handoff.
        if (chosen.clash > 0.52f || (chosen.outgoingBusy > 0.72f && chosen.incomingBusy > 0.68f)) return null
        if (keyCompatible == false && chosen.clash > 0.38f) return null

        val audibleWallMs = (chosen.bars * 240_000.0 / (sharedBpm * userSpeed)).roundToLong()
        val leadSourceMs = leadInBars * 4.0 * outGrid.beatIntervalMs
        val leadWallMs = (leadSourceMs / (userSpeed * ((1.0 + outgoingMultiplier) / 2.0))).roundToLong()
        // Return the incoming song to its own tempo fairly promptly. Leaving
        // a noticeable stretch running for 10+ seconds is one of the things
        // that made test2 feel "wrong" on sad/slow material.
        val tempoReturnMs = if (abs(incomingMultiplier - 1.0) < 0.001) 0L else {
            (2.0 * 240_000.0 / (sharedBpm * userSpeed)).roundToLong().coerceIn(3_000L, 7_000L)
        }
        val confidence = (min(outGrid.confidence, inGrid.confidence) *
            ((outGrid.stability + inGrid.stability) / 2f).coerceIn(0.5f, 1f)).coerceIn(0f, 1f)
        return AutoMixPlan(
            mode = AutoMixMode.BEAT_SYNC,
            outgoingMediaId = outgoing.mediaId,
            incomingMediaId = incoming.mediaId,
            fadeStartOutgoingMs = chosen.fadeStart,
            audibleStartOutgoingMs = chosen.audibleStart,
            effectiveEndOutgoingMs = chosen.effectiveEnd,
            incomingStartMs = chosen.incomingStart,
            totalFadeWallMs = leadWallMs + audibleWallMs,
            syncLeadInMs = leadWallMs,
            audibleFadeWallMs = audibleWallMs,
            outgoingTargetMultiplier = outgoingMultiplier.toFloat(),
            incomingMultiplier = incomingMultiplier.toFloat(),
            tempoReturnMs = tempoReturnMs,
            outgoingBpm = outGrid.bpm,
            incomingBpm = inGrid.bpm,
            sharedBpm = sharedBpm.toFloat(),
            bars = chosen.bars,
            leadInBars = leadInBars,
            confidence = confidence,
            userSpeedUsed = userSpeed,
            transitionKind = AutoMixTransitionKind.BEAT_LOCK,
            keyCompatible = keyCompatible,
            incomingAdjustedBpm = adjustedIncomingBpm.toFloat(),
        )
    }

    private fun BeatGrid.isReliableForBeatLock(): Boolean =
        bpm.isFinite() && bpm in 45f..190f &&
            confidence >= MIN_BEAT_CONFIDENCE &&
            stability >= MIN_BEAT_STABILITY &&
            beatStrength >= MIN_BEAT_STRENGTH

    private fun BeatGrid.isReliableForBeatLoop(): Boolean =
        bpm.isFinite() && bpm in 45f..190f &&
            confidence >= MIN_LOOP_CONFIDENCE &&
            stability >= MIN_LOOP_STABILITY &&
            beatStrength >= MIN_LOOP_BEAT_STRENGTH

    /**
     * Wall time needed to accumulate [beats] while BPM follows
     * `B(t) = start × (end/start)^(t/T)`. The engine uses exactly this curve,
     * so the number of loop cycles the listener hears is the number planned.
     */
    private fun tempoMorphWallMs(
        beats: Int,
        startBpm: Double,
        endBpm: Double,
        speed: Float,
    ): Long {
        if (beats <= 0 || startBpm <= 0.0 || endBpm <= 0.0 || speed <= 0f) return 0L
        val ratio = endBpm / startBpm
        val sourceMinutes = if (abs(ln(ratio)) < 1e-9 || abs(endBpm - startBpm) < 1e-9) {
            beats / startBpm
        } else {
            beats * ln(ratio) / (endBpm - startBpm)
        }
        return (sourceMinutes * 60_000.0 / speed.toDouble()).roundToLong()
    }

    /** Inverse of the accumulated-beat integral for [tempoMorphWallMs]. */
    private fun wallAtBeatFraction(
        totalWallMs: Long,
        beatFraction: Double,
        startBpm: Double,
        endBpm: Double,
    ): Long {
        val fraction = beatFraction.coerceIn(0.0, 1.0)
        if (totalWallMs <= 0L || startBpm <= 0.0 || endBpm <= 0.0) return 0L
        val ratio = endBpm / startBpm
        if (abs(ln(ratio)) < 1e-9) return (totalWallMs * fraction).roundToLong()
        val progress = ln(1.0 + fraction * (ratio - 1.0)) / ln(ratio)
        return (totalWallMs * progress.coerceIn(0.0, 1.0)).roundToLong()
    }

    private fun keyCompatibility(first: MusicalKey?, second: MusicalKey?): Boolean? {
        if (first == null || second == null) return null
        if (first.confidence < 0.16f || second.confidence < 0.16f) return null
        return first.compatibleWith(second)
    }

    private fun exitCandidates(
        outgoing: AutoMixTrackAnalysis,
        grid: BeatGrid,
        latestEnd: Long,
    ): List<Long> {
        val barMs = (grid.beatIntervalMs * 4.0).roundToLong().coerceAtLeast(1L)
        val candidates = LinkedHashSet<Long>()
        val preferred = outgoing.preferredExitDownbeatMs
            ?.let { grid.downbeatAtOrBefore(min(it, latestEnd)) }
        val earliestExit = (latestEnd - 25_000L).coerceAtLeast(1L)
        if (preferred != null) {
            for (offsetBars in -8..8) {
                val candidate = preferred + offsetBars * barMs
                if (candidate in earliestExit..latestEnd) candidates += candidate
            }
        }
        val naturalEnd = grid.downbeatAtOrBefore(latestEnd)
        for (backBars in 0..10) {
            val candidate = naturalEnd - backBars * barMs
            if (candidate >= earliestExit) candidates += candidate
        }
        if (candidates.isEmpty()) candidates += naturalEnd
        return candidates.toList()
    }

    private fun entryCandidates(
        incoming: AutoMixTrackAnalysis,
        grid: BeatGrid,
        adjustedFactor: Double,
    ): List<Long> {
        val nativeBarMs = (grid.beatIntervalMs * 4.0).roundToLong().coerceAtLeast(1L)
        val stepBars = if (adjustedFactor < 1.0) {
            (1.0 / adjustedFactor).roundToLong().toInt().coerceAtLeast(1)
        } else 1
        val stepMs = nativeBarMs * stepBars
        val centers = LinkedHashSet<Long>()
        incoming.preferredEntryDownbeatMs?.let { centers += grid.downbeatAtOrAfter(it) }
        incoming.firstStrongDownbeatMs?.let { centers += grid.downbeatAtOrAfter(it) }
        centers += grid.downbeatAtOrAfter(incoming.effectiveStartMs)

        val candidates = LinkedHashSet<Long>()
        for (center in centers) {
            for (offset in -6..6) {
                val candidate = center + offset * stepMs
                if (candidate in 0L..MAX_ENTRY_MS) candidates += candidate
            }
            // Starting a fraction before the first audible attack sounds empty;
            // the next downbeat is often the cleaner musical entrance.
            if (center < 750L) {
                val next = grid.downbeatAtOrAfter(750L)
                if (next in 0L..MAX_ENTRY_MS) candidates += next
            }
        }
        return candidates.toList()
    }

    /**
     * A loop must be based on measured bars, not [averageSections]' neutral
     * fallback for ranges outside the analysed window.
     */
    private fun sectionsCover(
        sections: List<AutoMixSection>,
        fromMs: Long,
        durationMs: Long,
    ): Boolean {
        if (durationMs <= 0L) return false
        val toMs = fromMs + durationMs
        var coveredMs = 0L
        for (section in sections) {
            coveredMs += (min(section.endMs, toMs) - max(section.startMs, fromMs)).coerceAtLeast(0L)
        }
        return coveredMs >= (durationMs * 3L / 4L)
    }

    /**
     * Whether meaningful musical material still happens after a proposed
     * exit. An exit is phrase-safe only when the remaining tail is a decay /
     * quiet outro, not when another sung or active phrase is still to come.
     */
    private fun hasActiveTailAfter(
        sections: List<AutoMixSection>,
        exitMs: Long,
        latestEndMs: Long,
    ): Boolean {
        if (latestEndMs - exitMs <= 1_200L) return false
        for (section in sections) {
            val overlap = min(section.endMs, latestEndMs) - max(section.startMs, exitMs)
            if (overlap < 300L) continue
            if (section.rms >= 0.48f || section.busy >= 0.60f || section.melodic >= 0.46f ||
                (section.sustained >= 0.78f && section.rms >= 0.36f && section.melodic >= 0.24f)
            ) {
                return true
            }
        }
        return false
    }

    private fun averageSections(
        sections: List<AutoMixSection>,
        fromMs: Long,
        durationMs: Long,
    ): SectionAverage {
        if (sections.isEmpty() || durationMs <= 0L) {
            return SectionAverage(0.50f, 0.40f, 0.40f, melodic = 0.45f, sustained = 0.50f)
        }
        val toMs = fromMs + durationMs
        var totalWeight = 0.0
        var rms = 0.0
        var onset = 0.0
        var brightness = 0.0
        var melodic = 0.0
        var sustained = 0.0
        for (section in sections) {
            val overlap = min(section.endMs, toMs) - max(section.startMs, fromMs)
            if (overlap <= 0L) continue
            val weight = overlap.toDouble()
            totalWeight += weight
            rms += section.rms * weight
            onset += section.onset * weight
            brightness += section.brightness * weight
            melodic += section.melodic * weight
            sustained += section.sustained * weight
        }
        if (totalWeight <= 0.0) {
            return SectionAverage(0.50f, 0.40f, 0.40f, melodic = 0.45f, sustained = 0.50f)
        }
        return SectionAverage(
            rms = (rms / totalWeight).toFloat().coerceIn(0f, 1f),
            onset = (onset / totalWeight).toFloat().coerceIn(0f, 1f),
            brightness = (brightness / totalWeight).toFloat().coerceIn(0f, 1f),
            melodic = (melodic / totalWeight).toFloat().coerceIn(0f, 1f),
            sustained = (sustained / totalWeight).toFloat().coerceIn(0f, 1f),
        )
    }

    /**
     * A short section-aware transition for songs that should not be forced
     * into a beat blend. Unlike the old 18-second Smooth fade, this is kept
     * short enough that two unrelated songs do not live on top of each other.
     */
    private fun cleanPlan(
        outgoing: AutoMixTrackAnalysis,
        incoming: AutoMixTrackAnalysis,
        mode: AutoMixMode,
        userSpeed: Float,
    ): AutoMixPlan? {
        if (outgoing.durationMs < MIN_CLEAN_OUTGOING_MS || incoming.durationMs < MIN_CLEAN_INCOMING_MS) return null
        val latestEndLimit = min(outgoing.effectiveEndMs, outgoing.durationMs - 250L)
        if (latestEndLimit < 15_000L) return null
        var latestEnd = outgoing.preferredExitDownbeatMs
            ?.coerceAtMost(latestEndLimit)
            ?.takeIf { it >= latestEndLimit - 25_000L }
            ?.takeIf { !hasActiveTailAfter(outgoing.sections, it, latestEndLimit) }
            ?: latestEndLimit
        outgoing.beatGrid?.let { latestEnd = it.downbeatAtOrBefore(latestEnd) }
        if (latestEnd < 12_000L || hasActiveTailAfter(outgoing.sections, latestEnd, latestEndLimit)) {
            latestEnd = outgoing.beatGrid?.downbeatAtOrBefore(latestEndLimit)
                ?.takeIf { it >= 12_000L }
                ?: latestEndLimit
        }

        var incomingStart = incoming.preferredEntryDownbeatMs
            ?: incoming.firstStrongDownbeatMs
            ?: incoming.effectiveStartMs
        incoming.beatGrid?.let { grid ->
            // Preferred entries already sit on section starts; snapping only
            // the generic effective-start fallback is enough. Snapping every
            // value atOrAfter could move a chosen calm bar into a busy one.
            if (incoming.preferredEntryDownbeatMs == null && incoming.firstStrongDownbeatMs == null) {
                incomingStart = grid.downbeatAtOrAfter(incomingStart)
            }
        }
        if (incomingStart !in 0L..MAX_ENTRY_MS) {
            incomingStart = incoming.effectiveStartMs.coerceIn(0L, MAX_ENTRY_MS)
        }

        val probeDurationMs = 6_000L
        val outAverage = averageSections(outgoing.sections, latestEnd - probeDurationMs, probeDurationMs)
        val inAverage = averageSections(incoming.sections, incomingStart, probeDurationMs)
        val busyProduct = outAverage.busy * inAverage.busy
        // If the singer/lead is still present in the actual final seconds,
        // do not lay the next song over several seconds of that phrase. Use
        // only a brief end-of-phrase segue at the natural tail.
        val vocalTail = outAverage.vocalRisk
        val phraseCut = vocalTail || outAverage.melodic * inAverage.melodic > 0.22f ||
            (outAverage.busy > 0.64f && inAverage.busy > 0.60f) || busyProduct > 0.42f

        val outGrid = outgoing.beatGrid
        var bars = 0
        var fadeSourceMs = when {
            phraseCut && outGrid != null -> {
                bars = 1
                (outGrid.beatIntervalMs * 4.0).roundToLong()
            }
            !phraseCut && outGrid != null -> {
                bars = if (mode == AutoMixMode.SMOOTH && max(outAverage.busy, inAverage.busy) < 0.45f) 3 else 2
                (outGrid.beatIntervalMs * 4.0 * bars).roundToLong()
            }
            phraseCut -> 2_500L
            mode == AutoMixMode.SMOOTH -> 7_500L
            else -> 6_500L
        }
        val longestUsable = min(min(outgoing.durationMs / 3L, incoming.durationMs / 2L), 9_000L)
        fadeSourceMs = min(fadeSourceMs, longestUsable)
        if (vocalTail) {
            fadeSourceMs = min(fadeSourceMs, 2_200L)
            bars = 0
        }
        if (fadeSourceMs < 1_800L) return null
        val fadeStart = latestEnd - fadeSourceMs
        if (fadeStart < 6_000L) return null
        if (incomingStart + fadeSourceMs + 8_000L > incoming.durationMs) {
            incomingStart = incoming.effectiveStartMs.coerceIn(0L, MAX_ENTRY_MS)
            if (incomingStart + fadeSourceMs + 8_000L > incoming.durationMs) return null
        }

        val fadeWallMs = (fadeSourceMs / userSpeed).roundToLong().coerceAtLeast(1_500L)
        val confidence = min(
            outgoing.beatGrid?.confidence ?: 0.5f,
            incoming.beatGrid?.confidence ?: 0.5f,
        )
        return AutoMixPlan(
            mode = mode,
            outgoingMediaId = outgoing.mediaId,
            incomingMediaId = incoming.mediaId,
            fadeStartOutgoingMs = fadeStart,
            audibleStartOutgoingMs = fadeStart,
            effectiveEndOutgoingMs = latestEnd,
            incomingStartMs = incomingStart,
            totalFadeWallMs = fadeWallMs,
            syncLeadInMs = 0L,
            audibleFadeWallMs = fadeWallMs,
            outgoingTargetMultiplier = 1f,
            incomingMultiplier = 1f,
            tempoReturnMs = 0L,
            outgoingBpm = outgoing.beatGrid?.bpm,
            incomingBpm = incoming.beatGrid?.bpm,
            sharedBpm = null,
            bars = bars,
            leadInBars = 0,
            confidence = confidence,
            userSpeedUsed = userSpeed,
            transitionKind = if (phraseCut) {
                AutoMixTransitionKind.PHRASE_CUT
            } else {
                AutoMixTransitionKind.CLEAN_BLEND
            },
            keyCompatible = keyCompatibility(outgoing.musicalKey, incoming.musicalKey),
        )
    }
}
