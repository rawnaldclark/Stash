package com.stash.core.media.service

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/** Which gain choreography a two-player transition should use. */
enum class CrossfadeBlendKind {
    EQUAL_POWER,
    BEAT_LOCK,
    BEAT_LOOP_BRIDGE,
    CLEAN_BLEND,
    PHRASE_CUT,
}

/** (outgoing, incoming) gain for ramp progress [t] in [0,1]; equal-power. */
fun equalPowerVolumes(t: Float): Pair<Float, Float> {
    val c = t.coerceIn(0f, 1f)
    val rad = c * (Math.PI.toFloat() / 2f)
    return cos(rad) to sin(rad)
}

private fun blendSmoothStep(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

private fun blendLerp(from: Float, to: Float, t: Float): Float = from + (to - from) * blendSmoothStep(t)

/**
 * Staged DJ-style gains for a beat-locked blend.
 *
 * The handoff is deliberately decisive around the middle: keeping both songs
 * near -6 dB for most of an 8/16-bar overlap is what made the old curve sound
 * muddy. The incoming song is only a quiet rhythmic preview at first, takes
 * over around the bass swap, and the outgoing tail disappears soon after.
 */
fun autoMixBlendVolumes(t: Float): Pair<Float, Float> {
    val c = t.coerceIn(0f, 1f)
    return when {
        c < 0.25f -> 1f to blendLerp(0f, 0.30f, c / 0.25f)
        c < 0.55f -> {
            val u = (c - 0.25f) / 0.30f
            blendLerp(1f, 0.38f, u) to blendLerp(0.30f, 0.86f, u)
        }
        c < 0.78f -> {
            val u = (c - 0.55f) / 0.23f
            blendLerp(0.38f, 0.08f, u) to blendLerp(0.86f, 1f, u)
        }
        else -> {
            val u = (c - 0.78f) / 0.22f
            blendLerp(0.08f, 0f, u) to 1f
        }
    }
}

/**
 * BPM at wall-clock [progress] of a beat-loop bridge. Tempo perception is
 * multiplicative, so the morph is log-linear: the same percentage change in
 * every equal slice of bridge time. The planner integrates this exact curve
 * when it decides how many loop cycles fit in the bridge.
 */
fun bridgeBpmAtProgress(startBpm: Float, endBpm: Float, progress: Float): Float {
    if (!startBpm.isFinite() || !endBpm.isFinite() || startBpm <= 0f || endBpm <= 0f) return 0f
    val c = progress.coerceIn(0f, 1f)
    return startBpm * (endBpm / startBpm).toDouble().pow(c.toDouble()).toFloat()
}

/**
 * Signed shortest distance, in beats, from [followerPhase] to
 * [referencePhase]. Positive means the follower is behind and should speed
 * up briefly. Phases may be any real number; only their fractional parts
 * matter.
 */
fun circularBeatPhaseError(referencePhase: Float, followerPhase: Float): Float {
    if (!referencePhase.isFinite() || !followerPhase.isFinite()) return 0f
    var error = referencePhase - followerPhase
    error -= floor(error.toDouble()).toFloat()
    if (error > 0.5f) error -= 1f
    return error
}

/**
 * Small temporary speed bend used to pull the incoming deck onto the loop's
 * live phase. This is deliberately capped: it corrects scheduling/output
 * drift, it does not try to rescue a wrong beat grid by audibly warping a
 * whole song.
 */
fun beatPhaseBendFactor(errorBeats: Float): Float {
    if (!errorBeats.isFinite()) return 1f
    return (1f + errorBeats.coerceIn(-0.5f, 0.5f) * 0.12f).coerceIn(0.94f, 1.06f)
}

/** (loop, incoming) gains for the final bars of a beat-loop bridge. */
fun autoMixBeatLoopVolumes(t: Float): Pair<Float, Float> = autoMixBlendVolumes(t)

/** Short blend for a calm outro/intro pair that is not beat-locked. */
fun autoMixCleanBlendVolumes(t: Float): Pair<Float, Float> {
    val c = t.coerceIn(0f, 1f)
    return when {
        c < 0.20f -> blendLerp(1f, 0.92f, c / 0.20f) to blendLerp(0f, 0.28f, c / 0.20f)
        c < 0.62f -> {
            val u = (c - 0.20f) / 0.42f
            blendLerp(0.92f, 0.18f, u) to blendLerp(0.28f, 0.94f, u)
        }
        else -> {
            val u = (c - 0.62f) / 0.38f
            blendLerp(0.18f, 0f, u) to blendLerp(0.94f, 1f, u)
        }
    }
}

/**
 * A phrase handoff for two busy sections. There is a brief overlap to avoid a
 * click/gap, but the outgoing song is gone before the two arrangements can
 * fight each other for long.
 */
fun autoMixPhraseCutVolumes(t: Float): Pair<Float, Float> {
    val c = t.coerceIn(0f, 1f)
    return when {
        c < 0.42f -> blendLerp(1f, 0.86f, c / 0.42f) to blendLerp(0f, 0.12f, c / 0.42f)
        c < 0.66f -> {
            val u = (c - 0.42f) / 0.24f
            blendLerp(0.86f, 0f, u) to blendLerp(0.12f, 1f, u)
        }
        else -> 0f to 1f
    }
}

/**
 * (outgoing, incoming) low-shelf gains in dB for a DJ bass swap.
 *
 * The incoming deck starts with its low end removed while only its rhythm is
 * audible. Around the middle of the blend the shelves cross, after which the
 * incoming song owns the bass and the outgoing tail is thin. This prevents
 * the most audible failure of a volume-only beat blend: two unrelated
 * basslines/kicks playing together.
 */
fun autoMixBassSwapShelves(t: Float): Pair<Float, Float> {
    val c = t.coerceIn(0f, 1f)
    val cutDb = -20f
    return when {
        c < 0.45f -> 0f to cutDb
        c < 0.58f -> {
            val u = (c - 0.45f) / 0.13f
            blendLerp(0f, cutDb, u) to blendLerp(cutDb, 0f, u)
        }
        else -> cutDb to 0f
    }
}

/** Inputs to the [shouldArm] decision, evaluated each position-poll tick. */
data class ArmInputs(
    val enabled: Boolean,
    val repeatOne: Boolean,
    val hasResolvedNext: Boolean,
    val remainingMs: Long,
    val trackDurationMs: Long,
    val crossfadeMs: Long,
)

/** Arm the fade only when every condition holds (see spec §Trigger). */
fun shouldArm(i: ArmInputs): Boolean =
    i.enabled &&
        !i.repeatOne &&
        i.hasResolvedNext &&
        i.trackDurationMs > 2 * i.crossfadeMs &&
        i.remainingMs in 1..i.crossfadeMs
