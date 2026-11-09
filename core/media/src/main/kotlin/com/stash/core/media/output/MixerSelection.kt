package com.stash.core.media.output

import com.stash.core.data.prefs.HiResOutputMode

/**
 * One entry of `AudioManager.getSupportedMixerAttributes(device)`, stripped of
 * platform types so the choice below is testable on the JVM.
 *
 * @property format null when the platform encoding is one we can't produce.
 * @property index position in the platform list, to map the choice back.
 */
data class MixerOption(
    val format: SampleFormat?,
    val sampleRate: Int,
    val channelCount: Int,
    val bitPerfect: Boolean,
    val index: Int,
)

/** Why a bit-perfect request was served as rate matching instead; the signal path says which. */
enum class BitPerfectMiss {
    /** This phone or DAC offers no bit-perfect mode at this rate and channel count. */
    NOT_OFFERED,

    /** Bit-perfect only holds at 1x: time-stretching rewrites every sample. */
    SPEED,

    /** Tuning retunes every sample (a reference other than 440 Hz); it wins over bit-perfect. */
    TUNING,

    /**
     * Held off for a Listen Together session, whose drift correction nudges
     * the speed all session long (see [HiResOutputController.setBitPerfectHeld]).
     */
    LISTEN_TOGETHER,
}

/** What the output stage should do with the USB mixer for one stream. */
sealed interface MixerDecision {
    /** Leave the mixer alone; [reason] is shown on the signal path when it matters. */
    data class Shared(val reason: Reason) : MixerDecision

    /**
     * Configure the mixer as [option] and write [trackFormat] to the AudioTrack.
     * [bitPerfectMiss] says why a bit-perfect request was served as rate-match.
     */
    data class Configure(
        val option: MixerOption,
        val trackFormat: SampleFormat,
        val bitPerfectMiss: BitPerfectMiss? = null,
    ) : MixerDecision {
        val bitPerfect: Boolean get() = option.bitPerfect

        /** Bit-perfect was asked for and this stream is rate-matched instead. */
        val bitPerfectUnavailable: Boolean get() = bitPerfectMiss != null
    }

    enum class Reason {
        MODE_STANDARD,
        UNSUPPORTED_ANDROID,
        NOT_USB,
        RATE_UNSUPPORTED_BY_DAC,
        NOT_MEDIA_USAGE,

        /** Android (or the AudioTrack) refused the configuration it advertised. */
        REJECTED,

        /**
         * Not Stash's main playback (the search preview): it plays over the
         * shared mixer and never moves the DAC (see [OutputSession.mainPlayback]).
         */
        NOT_MAIN_PLAYBACK,
    }
}

object MixerSelection {

    /**
     * Picks the mixer configuration for a [sampleRate] × [channelCount] stream.
     *
     * - The sample rate must match exactly — the whole point. A DAC without
     *   88.2 kHz gets no override for an 88.2 kHz song, and Android resamples it
     *   as before (we never pick a "nearby" rate: that is resampling too).
     * - Bit-perfect also needs an exact channel count (the track's format must
     *   equal the mixer's) and normal speed (time-stretching alters samples).
     *   When it isn't available, fall back to a rate-matched shared mixer.
     * - Among equals, the widest format wins: 32-bit > 24-bit > float > 16-bit.
     *   For ≤24-bit sources all but 16-bit carry every bit; wider leaves more
     *   room for the mixer's own math in the shared case.
     * - [bitPerfectHeld] (a Listen Together session) plans rate matching at
     *   every speed, so the session's speed nudges never change the plan.
     * - [tuned] (Tuning away from 440 Hz) rewrites every sample, so it plans
     *   rate matching too: the song's own rate, in float, never resampled.
     */
    fun choose(
        options: List<MixerOption>,
        mode: HiResOutputMode,
        sampleRate: Int,
        channelCount: Int,
        speed: Float,
        bitPerfectHeld: Boolean = false,
        tuned: Boolean = false,
    ): MixerDecision {
        if (mode == HiResOutputMode.STANDARD) return MixerDecision.Shared(MixerDecision.Reason.MODE_STANDARD)
        val atRate = options.filter { it.sampleRate == sampleRate && it.format != null }
        if (atRate.isEmpty()) return MixerDecision.Shared(MixerDecision.Reason.RATE_UNSUPPORTED_BY_DAC)

        val wantBitPerfect = mode == HiResOutputMode.BIT_PERFECT
        val bp = if (wantBitPerfect) {
            atRate
                .filter { it.bitPerfect && it.channelCount == channelCount }
                .maxByOrNull { rank(it.format!!) }
        } else {
            null
        }
        if (bp != null && speed == 1f && !tuned && !bitPerfectHeld) return MixerDecision.Configure(bp, trackFormat = bp.format!!)

        val shared = atRate
            .filter { !it.bitPerfect }
            .sortedWith(
                compareByDescending<MixerOption> { it.channelCount == channelCount }
                    .thenByDescending { rank(it.format!!) },
            )
            .firstOrNull()
            ?: return MixerDecision.Shared(MixerDecision.Reason.RATE_UNSUPPORTED_BY_DAC)
        // The real blocker first: a DAC with no bit-perfect mode here would
        // miss it at any speed and outside any session; Tuning stays on after
        // the session ends and at every speed.
        val miss = when {
            !wantBitPerfect -> null
            bp == null -> BitPerfectMiss.NOT_OFFERED
            tuned -> BitPerfectMiss.TUNING
            bitPerfectHeld -> BitPerfectMiss.LISTEN_TOGETHER
            else -> BitPerfectMiss.SPEED
        }
        // Shared mixer: hand it float — it mixes in float and converts to the
        // DAC format itself, so nothing is lost before that last step.
        return MixerDecision.Configure(shared, trackFormat = SampleFormat.FLOAT, bitPerfectMiss = miss)
    }

    private fun rank(f: SampleFormat): Int = when (f) {
        SampleFormat.INT32 -> 4
        SampleFormat.INT24 -> 3
        SampleFormat.FLOAT -> 2
        SampleFormat.INT16 -> 1
    }
}
