package com.stash.core.media.automix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** A constant-tempo beat grid estimated from one decoded analysis window. */
data class BeatGrid(
    val bpm: Float,
    val confidence: Float,
    val firstBeatMs: Long,
    val downbeatOffsetBeats: Int,
    /** 0..1; whether the first and second halves agree on the same tempo. */
    val stability: Float = 1f,
    /** 0..1; how much stronger on-beat onsets are than off-beat onsets. */
    val beatStrength: Float = 0.5f,
) {
    val beatIntervalMs: Double get() = 60_000.0 / bpm.toDouble()

    fun beatAtOrBefore(timeMs: Long): Long {
        val index = floor((timeMs - firstBeatMs).toDouble() / beatIntervalMs).toLong()
        return firstBeatMs + (index * beatIntervalMs).roundToLong()
    }

    fun beatAtOrAfter(timeMs: Long): Long {
        val index = ceil((timeMs - firstBeatMs).toDouble() / beatIntervalMs).toLong()
        return firstBeatMs + (index * beatIntervalMs).roundToLong()
    }

    fun downbeatAtOrBefore(timeMs: Long): Long {
        val beatIndex = floor((timeMs - firstBeatMs).toDouble() / beatIntervalMs).toLong()
        val downbeatIndex = Math.floorDiv(beatIndex - downbeatOffsetBeats, 4L) * 4L + downbeatOffsetBeats
        return firstBeatMs + (downbeatIndex * beatIntervalMs).roundToLong()
    }

    fun downbeatAtOrAfter(timeMs: Long): Long {
        val atOrBefore = downbeatAtOrBefore(timeMs)
        if (atOrBefore >= timeMs) return atOrBefore
        return atOrBefore + (4.0 * beatIntervalMs).roundToLong()
    }
}

/** Pitch classes use C=0, C#=1, ..., B=11. */
data class MusicalKey(
    val rootPitchClass: Int,
    val minor: Boolean,
    val confidence: Float,
) {
    /** Camelot-style compatibility: same key, relative major/minor, or a fifth/fourth away. */
    fun compatibleWith(other: MusicalKey): Boolean {
        if (rootPitchClass == other.rootPitchClass && minor == other.minor) return true
        if (minor == other.minor) {
            val diff = ((rootPitchClass - other.rootPitchClass) % 12 + 12) % 12
            return diff == 5 || diff == 7
        }
        val majorRoot = if (minor) other.rootPitchClass else rootPitchClass
        val minorRoot = if (minor) rootPitchClass else other.rootPitchClass
        return ((majorRoot - minorRoot) % 12 + 12) % 12 == 3
    }
}

/**
 * One bar (or a two-second fallback segment) of normalized musical activity.
 * Values are relative to the analysed window, so they are useful for choosing
 * a calmer intro/outro rather than for absolute loudness decisions.
 */
data class AutoMixSection(
    val startMs: Long,
    val endMs: Long,
    val rms: Float,
    val onset: Float,
    val brightness: Float,
    /**
     * 0..1 proxy for sustained pitched, vocal/lead-like content. It is not a
     * voice detector; the planner uses it conservatively to avoid looping or
     * cutting in the middle of a sung/melodic phrase.
     */
    val melodic: Float = 0f,
    /** 0..1; how continuously the section's envelope stays near its own peak. */
    val sustained: Float = 0f,
) {
    /** A deliberately simple vocal/arrangement-density proxy. */
    val busy: Float get() = (0.55f * rms + 0.25f * onset + 0.20f * brightness).coerceIn(0f, 1f)
}

data class BeatWindowAnalysis(
    val beatGrid: BeatGrid?,
    val effectiveStartMs: Long,
    val effectiveEndMs: Long,
    val firstStrongDownbeatMs: Long?,
    val sections: List<AutoMixSection> = emptyList(),
    val preferredEntryDownbeatMs: Long? = null,
    val preferredExitDownbeatMs: Long? = null,
    val musicalKey: MusicalKey? = null,
    /**
     * Phase fitted on the first seconds of the window. A single grid fitted
     * over a whole 65-second analysis window can be a few tens of milliseconds
     * out by the time it is extrapolated to the actual cue point; that is
     * enough to make two kicks flam. The entry/exit grids keep the global
     * tempo evidence but re-fit the phase where the transition will use it.
     */
    val entryBeatGrid: BeatGrid? = null,
    val exitBeatGrid: BeatGrid? = null,
)

object AutoMixBeatAnalyzer {
    private const val FRAME_SIZE = 1024
    private const val HOP_SIZE = 512
    private const val MIN_BPM = 45.0
    private const val MAX_BPM = 190.0
    private const val MIN_ONSET_STRENGTH = 0.035f
    private const val FFT_SIZE = 4096
    private const val FFT_HOP = 2048

    private val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    fun analyze(samples: FloatArray, sampleRate: Int, windowStartMs: Long): BeatWindowAnalysis? {
        if (sampleRate <= 0 || samples.size < sampleRate * 2) return null
        val windowDurationMs = samples.size * 1_000L / sampleRate
        val windowEndMs = windowStartMs + windowDurationMs
        val frames = frameCount(samples.size)
        if (frames < 16) return null

        val rms = FloatArray(frames)
        val highFrequency = FloatArray(frames)
        val brightnessRatio = FloatArray(frames)
        for (frame in 0 until frames) {
            val start = frame * HOP_SIZE
            val end = min(samples.size, start + FRAME_SIZE)
            var squares = 0.0
            var differences = 0.0
            var previous = samples[start]
            for (i in start until end) {
                val sample = samples[i]
                squares += sample.toDouble() * sample.toDouble()
                if (i > start) differences += abs(sample - previous).toDouble()
                previous = sample
            }
            val count = max(1, end - start)
            val frameRms = sqrt(squares / count).toFloat()
            val frameHigh = (differences / count).toFloat()
            rms[frame] = frameRms
            highFrequency[frame] = frameHigh
            brightnessRatio[frame] = frameHigh / (frameRms + 0.02f)
        }

        val effectiveStartMs = windowStartMs + frameToMs(firstAudibleFrame(rms), sampleRate)
        val effectiveEndMs = windowStartMs + frameToMs(lastAudibleFrame(rms) + 1, sampleRate).coerceAtMost(windowDurationMs)
        val novelty = onsetEnvelope(rms, highFrequency)
        val rmsP95 = percentile(rms, 0.95f)
        if (rmsP95 <= 1e-5f) {
            return BeatWindowAnalysis(null, effectiveStartMs, effectiveEndMs, null)
        }
        val normalizedNovelty = FloatArray(novelty.size) { novelty[it] / rmsP95 }
        val spectral = analyzeSpectrum(samples, sampleRate)
        val musicalKey = spectral.key

        fun noGrid(): BeatWindowAnalysis {
            val sections = buildSections(
                grid = null, rms = rms, novelty = normalizedNovelty, brightness = brightnessRatio,
                spectralFrames = spectral.frames,
                sampleRate = sampleRate, windowStartMs = windowStartMs, windowEndMs = windowEndMs,
            )
            return BeatWindowAnalysis(
                beatGrid = null,
                effectiveStartMs = effectiveStartMs,
                effectiveEndMs = effectiveEndMs,
                firstStrongDownbeatMs = null,
                sections = sections,
                preferredEntryDownbeatMs = preferredEntry(sections, windowStartMs, effectiveStartMs, null),
                preferredExitDownbeatMs = preferredExit(sections, effectiveEndMs),
                musicalKey = musicalKey,
            )
        }

        if (percentile(normalizedNovelty, 0.95f) < MIN_ONSET_STRENGTH) return noGrid()
        val envelopeRate = sampleRate.toDouble() / HOP_SIZE.toDouble()
        val candidate = bestTempo(normalizedNovelty, envelopeRate) ?: return noGrid()
        val refined = refineTempo(normalizedNovelty, envelopeRate, candidate.first)
        val periodFrames = 60.0 * envelopeRate / refined.bpm
        val downbeatOffset = bestDownbeatOffset(normalizedNovelty, periodFrames, refined.phaseFrame)
        val grid = BeatGrid(
            bpm = refined.bpm.toFloat(),
            confidence = candidate.second,
            firstBeatMs = windowStartMs + frameToMs(refined.phaseFrame, sampleRate),
            downbeatOffsetBeats = downbeatOffset,
            stability = tempoStability(normalizedNovelty, envelopeRate),
            beatStrength = refined.beatStrength,
        )
        val entryGrid = localBeatGrid(
            global = grid,
            novelty = normalizedNovelty,
            envelopeRate = envelopeRate,
            sampleRate = sampleRate,
            windowStartMs = windowStartMs,
            atStart = true,
        )
        val exitGrid = localBeatGrid(
            global = grid,
            novelty = normalizedNovelty,
            envelopeRate = envelopeRate,
            sampleRate = sampleRate,
            windowStartMs = windowStartMs,
            atStart = false,
        )
        val firstStrong = firstStrongDownbeat(
            entryGrid, rms, normalizedNovelty, sampleRate, windowStartMs, effectiveStartMs, windowEndMs,
        )
        val sections = buildSections(
            grid = grid, rms = rms, novelty = normalizedNovelty, brightness = brightnessRatio,
            spectralFrames = spectral.frames,
            sampleRate = sampleRate, windowStartMs = windowStartMs, windowEndMs = windowEndMs,
        )
        return BeatWindowAnalysis(
            beatGrid = grid,
            effectiveStartMs = effectiveStartMs,
            effectiveEndMs = effectiveEndMs,
            firstStrongDownbeatMs = firstStrong,
            sections = sections,
            preferredEntryDownbeatMs = preferredEntry(sections, windowStartMs, effectiveStartMs, firstStrong),
            preferredExitDownbeatMs = preferredExit(sections, effectiveEndMs),
            musicalKey = musicalKey,
            entryBeatGrid = entryGrid,
            exitBeatGrid = exitGrid,
        )
    }

    private fun frameCount(sampleCount: Int): Int =
        if (sampleCount <= FRAME_SIZE) 1 else 1 + (sampleCount - FRAME_SIZE) / HOP_SIZE

    private fun frameToMs(frame: Int, sampleRate: Int): Long = frame.toLong() * HOP_SIZE * 1_000L / sampleRate

    private fun timeToFrame(timeMs: Long, windowStartMs: Long, sampleRate: Int, frameCount: Int): Int =
        (((timeMs - windowStartMs).coerceAtLeast(0L) * sampleRate) / (HOP_SIZE * 1_000L))
            .toInt().coerceIn(0, max(0, frameCount - 1))

    private fun onsetEnvelope(rms: FloatArray, highFrequency: FloatArray): FloatArray {
        val raw = FloatArray(rms.size)
        for (i in 1 until rms.size) {
            raw[i] = max(0f, rms[i] - rms[i - 1]) + 0.65f * max(0f, highFrequency[i] - highFrequency[i - 1])
        }
        return FloatArray(raw.size) { i ->
            val left = if (i == 0) raw[i] else raw[i - 1]
            val right = if (i == raw.lastIndex) raw[i] else raw[i + 1]
            (left + raw[i] * 2f + right) / 4f
        }
    }

    private fun firstAudibleFrame(rms: FloatArray): Int {
        val threshold = max(0.008f, percentile(rms, 0.90f) * 0.07f)
        for (i in rms.indices) {
            if (rms[i] >= threshold && (i + 1 >= rms.size || rms[i + 1] >= threshold) &&
                (i + 2 >= rms.size || rms[i + 2] >= threshold)
            ) return i
        }
        return 0
    }

    private fun lastAudibleFrame(rms: FloatArray): Int {
        val threshold = max(0.008f, percentile(rms, 0.90f) * 0.07f)
        for (i in rms.indices.reversed()) {
            if (rms[i] >= threshold && (i - 1 < 0 || rms[i - 1] >= threshold) &&
                (i - 2 < 0 || rms[i - 2] >= threshold)
            ) return i
        }
        return rms.lastIndex
    }

    private fun buildSections(
        grid: BeatGrid?,
        rms: FloatArray,
        novelty: FloatArray,
        brightness: FloatArray,
        spectralFrames: List<SpectralFrame>,
        sampleRate: Int,
        windowStartMs: Long,
        windowEndMs: Long,
    ): List<AutoMixSection> {
        val ranges = ArrayList<Pair<Long, Long>>()
        if (grid != null) {
            val barMs = (grid.beatIntervalMs * 4.0).roundToLong().coerceAtLeast(250L)
            var start = grid.downbeatAtOrAfter(windowStartMs)
            // Include the partial first bar as its own range when the window starts mid-bar.
            if (start > windowStartMs + 300L) ranges += windowStartMs to min(start, windowEndMs)
            while (start + barMs <= windowEndMs) {
                ranges += start to (start + barMs)
                start += barMs
            }
            if (start < windowEndMs && windowEndMs - start >= 300L) ranges += start to windowEndMs
        } else {
            var start = windowStartMs
            while (start < windowEndMs) {
                ranges += start to min(start + 2_000L, windowEndMs)
                start += 2_000L
            }
        }
        if (ranges.isEmpty()) ranges += windowStartMs to windowEndMs

        val rmsScale = percentile(rms, 0.95f).takeIf { it > 1e-9f } ?: 1f
        val noveltyScale = percentile(novelty, 0.95f).takeIf { it > 1e-9f } ?: 1f
        val brightnessScale = percentile(brightness, 0.95f).takeIf { it > 1e-9f } ?: 1f
        return ranges.map { (startMs, endMs) ->
            val from = timeToFrame(startMs, windowStartMs, sampleRate, rms.size)
            val toExclusive = (timeToFrame(endMs, windowStartMs, sampleRate, rms.size) + 1).coerceIn(from + 1, rms.size)
            var rmsSum = 0.0
            var noveltySum = 0.0
            var brightnessSum = 0.0
            for (i in from until toExclusive) {
                rmsSum += rms[i].toDouble()
                noveltySum += novelty[i].toDouble()
                brightnessSum += brightness[i].toDouble()
            }
            val count = max(1, toExclusive - from).toDouble()
            val sectionRms = rms.copyOfRange(from, toExclusive)
            val sectionPeak = percentile(sectionRms, 0.90f)
            val sustainedThreshold = max(0.012f, sectionPeak * 0.45f)
            val sustained = if (sectionRms.isEmpty()) {
                0f
            } else {
                sectionRms.count { it >= sustainedThreshold }.toFloat() / sectionRms.size.toFloat()
            }
            var melodicWeighted = 0.0
            var melodicWeight = 0.0
            for (spectralFrame in spectralFrames) {
                val centerMs = windowStartMs +
                    (spectralFrame.startSample + FFT_SIZE / 2).toLong() * 1_000L / sampleRate
                if (centerMs in startMs until endMs) {
                    val weight = spectralFrame.weight.toDouble().coerceAtLeast(1e-9)
                    melodicWeighted += spectralFrame.melodic.toDouble() * weight
                    melodicWeight += weight
                }
            }
            AutoMixSection(
                startMs = startMs,
                endMs = endMs,
                rms = (rmsSum / count / rmsScale).toFloat().coerceIn(0f, 1f),
                onset = (noveltySum / count / noveltyScale).toFloat().coerceIn(0f, 1f),
                brightness = (brightnessSum / count / brightnessScale).toFloat().coerceIn(0f, 1f),
                melodic = if (melodicWeight > 0.0) {
                    (melodicWeighted / melodicWeight).toFloat().coerceIn(0f, 1f)
                } else {
                    0f
                },
                sustained = sustained.coerceIn(0f, 1f),
            )
        }
    }

    private fun preferredEntry(
        sections: List<AutoMixSection>,
        windowStartMs: Long,
        effectiveStartMs: Long,
        firstStrongDownbeatMs: Long?,
    ): Long? {
        var bestIndex = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (index in sections.indices) {
            val section = sections[index]
            if (section.startMs > windowStartMs + 35_000L) break
            if (section.endMs < effectiveStartMs || section.rms < 0.04f) continue
            val next = sections.getOrNull(index + 1) ?: section
            val third = sections.getOrNull(index + 2) ?: next
            val score = 1.6 -
                abs(section.rms - 0.34f) -
                0.55 * abs(next.rms - 0.52f) -
                0.65 * section.onset -
                0.25 * section.brightness -
                0.85 * section.melodic -
                0.25 * section.sustained +
                0.70 * max(0f, next.rms - section.rms) +
                0.35 * max(0f, third.rms - next.rms) -
                index * 0.015
            if (score > bestScore) {
                bestScore = score.toDouble()
                bestIndex = index
            }
        }
        return sections.getOrNull(bestIndex)?.startMs ?: firstStrongDownbeatMs
    }

    private fun preferredExit(sections: List<AutoMixSection>, effectiveEndMs: Long): Long? {
        var best: AutoMixSection? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (index in sections.indices) {
            val section = sections[index]
            if (section.endMs > effectiveEndMs + 250L || section.endMs < effectiveEndMs - 35_000L) continue
            if (section.rms < 0.05f) continue
            val previous = sections.getOrNull(index - 1) ?: section
            val previous2 = sections.getOrNull(index - 2) ?: previous
            val remainingPenalty = ((effectiveEndMs - section.endMs).coerceAtLeast(0L) / 35_000.0) * 0.25
            val score = 1.4 -
                1.15 * section.rms -
                0.55 * section.onset -
                0.20 * section.brightness -
                1.05 * section.melodic -
                0.35 * section.sustained +
                0.80 * max(0f, previous.rms - section.rms) +
                0.35 * max(0f, previous2.rms - section.rms) -
                remainingPenalty
            if (score > bestScore) {
                bestScore = score.toDouble()
                best = section
            }
        }
        return best?.endMs
    }

    private fun bestTempo(novelty: FloatArray, envelopeRate: Double): Pair<Double, Float>? {
        val mean = novelty.average().toFloat()
        val centered = FloatArray(novelty.size) { novelty[it] - mean }
        val prefixSquares = DoubleArray(centered.size + 1)
        for (i in centered.indices) prefixSquares[i + 1] = prefixSquares[i] + centered[i].toDouble() * centered[i]
        if (prefixSquares.last() <= 1e-12) return null
        val minLag = floor(60.0 * envelopeRate / MAX_BPM).toInt().coerceAtLeast(1)
        val maxLag = ceil(60.0 * envelopeRate / MIN_BPM).toInt().coerceAtMost(centered.size / 3)
        if (maxLag <= minLag) return null
        val scores = HashMap<Int, Double>(maxLag - minLag + 1)
        var bestLag = minLag
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            var dot = 0.0
            for (i in lag until centered.size) dot += centered[i].toDouble() * centered[i - lag]
            val leftEnergy = prefixSquares[centered.size - lag]
            val rightEnergy = prefixSquares.last() - prefixSquares[lag]
            val normalized = if (leftEnergy > 0.0 && rightEnergy > 0.0) dot / sqrt(leftEnergy * rightEnergy) else 0.0
            val bpm = 60.0 * envelopeRate / lag
            val weighted = normalized * tempoPrior(bpm)
            scores[lag] = weighted
            if (weighted > bestScore) {
                bestScore = weighted
                bestLag = lag
            }
        }
        var chosenLag = bestLag
        val chosenBpm = 60.0 * envelopeRate / chosenLag
        if (chosenBpm > 155.0) {
            val halfLag = (chosenLag * 2).coerceAtMost(maxLag)
            if ((scores[halfLag] ?: 0.0) >= bestScore * 0.86) chosenLag = halfLag
        } else if (chosenBpm < 72.0) {
            val doubleLag = max(minLag, chosenLag / 2)
            if ((scores[doubleLag] ?: 0.0) >= bestScore * 0.88) chosenLag = doubleLag
        }
        val sortedScores = scores.values.sorted()
        val median = sortedScores.getOrElse(sortedScores.size / 2) { 0.0 }
        val chosenScore = scores[chosenLag] ?: bestScore
        val confidence = ((chosenScore - median).coerceAtLeast(0.0) / (1.0 - median).coerceAtLeast(0.1))
            .toFloat().coerceIn(0f, 1f)
        if (confidence <= 0.01f) return null
        val left = scores[chosenLag - 1] ?: chosenScore
        val right = scores[chosenLag + 1] ?: chosenScore
        val denominator = left - 2.0 * chosenScore + right
        val fractionalOffset = if (abs(denominator) > 1e-12) {
            (0.5 * (left - right) / denominator).coerceIn(-0.5, 0.5)
        } else 0.0
        return (60.0 * envelopeRate / (chosenLag + fractionalOffset)) to confidence
    }

    private data class RefinedTempo(val bpm: Double, val phaseFrame: Int, val beatStrength: Float)

    private data class KeyCandidate(val root: Int, val minor: Boolean, val correlation: Double)

    /**
     * Search ±3% around the autocorrelation estimate in 0.1% steps with a beat
     * comb. This is important over a 65-second window: even a 0.5 BPM error
     * walks several hundred milliseconds by the outro and makes kicks flam.
     */
    private fun refineTempo(novelty: FloatArray, envelopeRate: Double, coarseBpm: Double): RefinedTempo {
        var bestBpm = coarseBpm
        var bestPeriod = 60.0 * envelopeRate / coarseBpm
        var bestPhase = bestPhase(novelty, bestPeriod)
        var bestScore = phaseScore(novelty, bestPeriod, bestPhase.toDouble())
        for (step in -30..30) {
            if (step == 0) continue
            val bpm = coarseBpm * (1.0 + step * 0.001)
            if (bpm !in MIN_BPM..MAX_BPM) continue
            val period = 60.0 * envelopeRate / bpm
            val phase = bestPhase(novelty, period)
            val score = phaseScore(novelty, period, phase.toDouble())
            if (score > bestScore * 1.0005) {
                bestScore = score
                bestBpm = bpm
                bestPeriod = period
                bestPhase = phase
            }
        }
        val onBeat = bestScore
        val offBeat = phaseScore(novelty, bestPeriod, bestPhase + bestPeriod / 2.0)
        val strength = if (onBeat + offBeat > 1e-12) {
            ((onBeat - offBeat) / (onBeat + offBeat)).toFloat().coerceIn(0f, 1f)
        } else 0f
        return RefinedTempo(bestBpm, bestPhase, strength)
    }

    /**
     * Re-fit only the phase (and at most a ±1.5% local tempo correction) on
     * the first or last ~24 seconds. Beat-loop cue points live at those
     * edges, and a globally correct BPM can still leave the edge phase far
     * enough out to make a loop seam or two kicks audibly separate.
     */
    private fun localBeatGrid(
        global: BeatGrid,
        novelty: FloatArray,
        envelopeRate: Double,
        sampleRate: Int,
        windowStartMs: Long,
        atStart: Boolean,
    ): BeatGrid {
        if (novelty.size < 240) return global
        val desiredFrames = (24.0 * envelopeRate).roundToInt()
        val localSize = desiredFrames.coerceIn(min(600, novelty.size), novelty.size)
        val fromFrame = if (atStart) 0 else novelty.size - localSize
        val localNovelty = novelty.copyOfRange(fromFrame, fromFrame + localSize)
        val refined = refineTempo(localNovelty, envelopeRate, global.bpm.toDouble())
        val localBpm = if (abs(ln(refined.bpm / global.bpm.toDouble())) <= ln(1.015)) {
            refined.bpm
        } else {
            global.bpm.toDouble()
        }
        val periodFrames = 60.0 * envelopeRate / localBpm
        val phaseFrame = if (localBpm == refined.bpm) {
            refined.phaseFrame
        } else {
            bestPhase(localNovelty, periodFrames)
        }
        val onBeat = phaseScore(localNovelty, periodFrames, phaseFrame.toDouble())
        val offBeat = phaseScore(localNovelty, periodFrames, phaseFrame + periodFrames / 2.0)
        val localStrength = if (onBeat + offBeat > 1e-12) {
            ((onBeat - offBeat) / (onBeat + offBeat)).toFloat().coerceIn(0f, 1f)
        } else {
            global.beatStrength
        }
        return global.copy(
            bpm = localBpm.toFloat(),
            firstBeatMs = windowStartMs + frameToMs(fromFrame + phaseFrame, sampleRate),
            downbeatOffsetBeats = bestDownbeatOffset(localNovelty, periodFrames, phaseFrame),
            beatStrength = localStrength,
        )
    }

    private fun tempoStability(novelty: FloatArray, envelopeRate: Double): Float {
        if (novelty.size < 1_200) return 0.45f
        val mid = novelty.size / 2
        val first = bestTempo(novelty.copyOfRange(0, mid), envelopeRate)?.first ?: return 0.35f
        val second = bestTempo(novelty.copyOfRange(mid, novelty.size), envelopeRate)?.first ?: return 0.35f
        var ratio = first / second
        while (ratio < 0.75) ratio *= 2.0
        while (ratio > 1.3333333) ratio /= 2.0
        val difference = abs(ln(ratio))
        return (1.0 - difference / ln(1.08)).toFloat().coerceIn(0f, 1f)
    }

    private fun tempoPrior(bpm: Double): Double = when {
        bpm in 70.0..160.0 -> 1.0
        bpm < 70.0 -> 0.72 + 0.28 * ((bpm - MIN_BPM) / (70.0 - MIN_BPM)).coerceIn(0.0, 1.0)
        else -> 1.0 - 0.28 * ((bpm - 160.0) / (MAX_BPM - 160.0)).coerceIn(0.0, 1.0)
    }

    private fun phaseScore(novelty: FloatArray, periodFrames: Double, offsetFrames: Double): Double {
        var score = 0.0
        var count = 0
        var position = offsetFrames
        while (position < novelty.size) {
            score += novelty[position.roundToInt().coerceIn(0, novelty.lastIndex)]
            count++
            position += periodFrames
        }
        return if (count == 0) 0.0 else score / count
    }

    private fun bestPhase(novelty: FloatArray, periodFrames: Double): Int {
        val steps = max(1, periodFrames.roundToInt())
        var bestOffset = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (offset in 0 until steps) {
            val average = phaseScore(novelty, periodFrames, offset.toDouble())
            if (average > bestScore) {
                bestScore = average
                bestOffset = offset
            }
        }
        return bestOffset
    }

    private fun bestDownbeatOffset(novelty: FloatArray, periodFrames: Double, phaseFrame: Int): Int {
        var bestOffset = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (offset in 0..3) {
            val average = phaseScore(novelty, periodFrames * 4.0, phaseFrame + offset * periodFrames)
            if (average > bestScore) {
                bestScore = average
                bestOffset = offset
            }
        }
        return bestOffset
    }

    private fun firstStrongDownbeat(
        grid: BeatGrid,
        rms: FloatArray,
        novelty: FloatArray,
        sampleRate: Int,
        windowStartMs: Long,
        effectiveStartMs: Long,
        windowEndMs: Long,
    ): Long? {
        val rmsThreshold = max(0.03f, percentile(rms, 0.90f) * 0.25f)
        val noveltyThreshold = percentile(novelty, 0.65f)
        var candidate = grid.downbeatAtOrAfter(effectiveStartMs)
        val deadline = min(windowEndMs, windowStartMs + 45_000L)
        while (candidate <= deadline) {
            val frame = timeToFrame(candidate, windowStartMs, sampleRate, rms.size)
            var localRms = 0f
            var localNovelty = 0f
            for (i in (frame - 2)..(frame + 2)) {
                if (i in rms.indices) {
                    localRms = max(localRms, rms[i])
                    localNovelty = max(localNovelty, novelty[i])
                }
            }
            if (localRms >= rmsThreshold && localNovelty >= noveltyThreshold) return candidate
            candidate += (grid.beatIntervalMs * 4.0).roundToLong()
        }
        return grid.downbeatAtOrAfter(effectiveStartMs).takeIf { it <= windowEndMs }
    }

    private data class SpectralFrame(
        val startSample: Int,
        val melodic: Float,
        val weight: Float,
    )

    private data class SpectralAnalysis(
        val key: MusicalKey?,
        val frames: List<SpectralFrame>,
    )

    /**
     * One FFT pass for both the coarse key estimate and the per-section
     * vocal/lead-risk proxy. A frame is considered melodic when it is both
     * concentrated in the 300 Hz–3.4 kHz band and tonally peaky; broadband
     * drums tend to fail the tonal half and a bass line tends to fail the
     * mid-band half. This is only a conservative veto for transitions.
     */
    private fun analyzeSpectrum(samples: FloatArray, sampleRate: Int): SpectralAnalysis {
        if (samples.size < FFT_SIZE || sampleRate <= 0) return SpectralAnalysis(null, emptyList())
        val chroma = DoubleArray(12)
        val frames = ArrayList<SpectralFrame>()
        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)
        val window = DoubleArray(FFT_SIZE) { i -> 0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1)) }
        var start = 0
        var usedFrames = 0
        while (start + FFT_SIZE <= samples.size) {
            var energy = 0.0
            for (i in 0 until FFT_SIZE) {
                val sample = samples[start + i].toDouble()
                energy += sample * sample
                real[i] = sample * window[i]
                imag[i] = 0.0
            }
            if (energy / FFT_SIZE > 1e-6) {
                fft(real, imag)
                val localChroma = DoubleArray(12)
                var totalMagnitude = 0.0
                var midMagnitude = 0.0
                for (bin in 1 until FFT_SIZE / 2) {
                    val frequency = bin.toDouble() * sampleRate / FFT_SIZE
                    if (frequency !in 40.0..8_000.0) continue
                    val magnitude = sqrt(real[bin] * real[bin] + imag[bin] * imag[bin])
                    if (magnitude <= 0.0) continue
                    totalMagnitude += magnitude
                    if (frequency in 300.0..3_400.0) midMagnitude += magnitude
                    if (frequency in 55.0..4_000.0) {
                        val midi = (69.0 + 12.0 * ln(frequency / 440.0) / ln(2.0)).roundToInt()
                        val pitchClass = ((midi % 12) + 12) % 12
                        localChroma[pitchClass] += magnitude
                        chroma[pitchClass] += magnitude
                    }
                }
                val chromaSum = localChroma.sum()
                if (totalMagnitude > 0.0 && chromaSum > 0.0) {
                    val peakShare = (localChroma.maxOrNull() ?: 0.0) / chromaSum
                    val tonal = ((peakShare - (1.0 / 12.0)) / (0.42 - (1.0 / 12.0))).coerceIn(0.0, 1.0)
                    val midRatio = (midMagnitude / totalMagnitude).coerceIn(0.0, 1.0)
                    val melodic = (midRatio * (0.30 + 0.70 * tonal)).toFloat().coerceIn(0f, 1f)
                    frames += SpectralFrame(
                        startSample = start,
                        melodic = melodic,
                        weight = totalMagnitude.toFloat(),
                    )
                }
                usedFrames++
            }
            start += FFT_HOP
        }
        if (usedFrames < 8 || chroma.sum() <= 0.0) return SpectralAnalysis(null, frames)

        val candidates = ArrayList<KeyCandidate>(24)
        for (root in 0..11) {
            for (minor in listOf(false, true)) {
                val profile = if (minor) MINOR_PROFILE else MAJOR_PROFILE
                val rotated = DoubleArray(12) { pitchClass ->
                    profile[((pitchClass - root) % 12 + 12) % 12]
                }
                candidates += KeyCandidate(root, minor, pearson(chroma, rotated))
            }
        }
        candidates.sortByDescending { it.correlation }
        val best = candidates[0]
        val second = candidates[1]
        if (best.correlation < 0.25) return SpectralAnalysis(null, frames)
        val confidence = (((best.correlation - second.correlation) * 3.0).coerceIn(0.0, 1.0) *
            ((best.correlation + 1.0) / 2.0).coerceIn(0.0, 1.0)).toFloat()
        if (confidence < 0.05f) return SpectralAnalysis(null, frames)
        return SpectralAnalysis(MusicalKey(best.root, best.minor, confidence), frames)
    }

    private fun pearson(first: DoubleArray, second: DoubleArray): Double {
        val n = min(first.size, second.size)
        if (n == 0) return 0.0
        val firstMean = first.average()
        val secondMean = second.average()
        var numerator = 0.0
        var firstSquares = 0.0
        var secondSquares = 0.0
        for (i in 0 until n) {
            val a = first[i] - firstMean
            val b = second[i] - secondMean
            numerator += a * b
            firstSquares += a * a
            secondSquares += b * b
        }
        val denominator = sqrt(firstSquares * secondSquares)
        return if (denominator <= 1e-12) 0.0 else numerator / denominator
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tempReal = real[i]
                real[i] = real[j]
                real[j] = tempReal
                val tempImag = imag[i]
                imag[i] = imag[j]
                imag[j] = tempImag
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wLengthReal = cos(angle)
            val wLengthImag = sin(angle)
            var i = 0
            while (i < n) {
                var wReal = 1.0
                var wImag = 0.0
                for (k in 0 until length / 2) {
                    val u = i + k
                    val v = u + length / 2
                    val vReal = real[v] * wReal - imag[v] * wImag
                    val vImag = real[v] * wImag + imag[v] * wReal
                    real[v] = real[u] - vReal
                    imag[v] = imag[u] - vImag
                    real[u] += vReal
                    imag[u] += vImag
                    val nextWReal = wReal * wLengthReal - wImag * wLengthImag
                    wImag = wReal * wLengthImag + wImag * wLengthReal
                    wReal = nextWReal
                }
                i += length
            }
            length *= 2
        }
    }

    private fun percentile(values: FloatArray, percentile: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.copyOf().also { it.sort() }
        return sorted[(percentile * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.lastIndex)]
    }
}
